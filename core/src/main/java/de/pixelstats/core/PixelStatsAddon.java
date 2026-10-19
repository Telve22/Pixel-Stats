package de.pixelstats.core;

import de.pixelstats.core.commands.PixelStatsCommand;
import de.pixelstats.core.dashboard.DashboardActivity;
import de.pixelstats.core.dashboard.DashboardHotkeyListener;
import de.pixelstats.core.data.ApartmentLease;
import de.pixelstats.core.data.ApartmentSignReader;
import de.pixelstats.core.data.ApartmentSignTracker;
import de.pixelstats.core.data.PixelBalanceTracker;
import de.pixelstats.core.data.PixelRewardMessages;
import de.pixelstats.core.data.PixelTextParser;
import de.pixelstats.core.data.StatsLedger;
import de.pixelstats.core.data.StatsPersistence;
import de.pixelstats.core.data.StatsStore;
import de.pixelstats.core.hud.PixelHudLayout.Metric;
import de.pixelstats.core.hud.PixelHudLocalization;
import de.pixelstats.core.hud.PixelStatsHudWidget;
import de.pixelstats.core.hud.PixelWidgetConfigMigration;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import net.labymod.api.addon.LabyAddon;
import net.labymod.api.client.component.Component;
import net.labymod.api.client.component.serializer.plain.PlainTextComponentSerializer;
import net.labymod.api.client.gui.hud.binding.category.HudWidgetCategory;
import net.labymod.api.client.network.server.ServerData;
import net.labymod.api.client.scoreboard.DisplaySlot;
import net.labymod.api.client.scoreboard.Scoreboard;
import net.labymod.api.client.scoreboard.ScoreboardObjective;
import net.labymod.api.client.scoreboard.ScoreboardScore;
import net.labymod.api.client.scoreboard.ScoreboardTeam;
import net.labymod.api.client.world.phys.hit.BlockHitResult;
import net.labymod.api.event.Phase;
import net.labymod.api.event.Subscribe;
import net.labymod.api.event.client.blockentity.BlockEntityUpdateEvent;
import net.labymod.api.event.client.blockentity.SignBlockEntityPostLoadEvent;
import net.labymod.api.event.client.chat.ChatReceiveEvent;
import net.labymod.api.event.client.entity.player.ClientPlayerInteractEvent;
import net.labymod.api.event.client.lifecycle.GameTickEvent;
import net.labymod.api.event.client.lifecycle.ShutdownEvent;
import net.labymod.api.event.client.network.server.ServerDisconnectEvent;
import net.labymod.api.event.client.network.server.ServerJoinEvent;
import net.labymod.api.event.client.resources.pack.ResourceReloadEvent;
import net.labymod.api.event.client.world.ItemStackTooltipEvent;
import net.labymod.api.event.client.world.WorldEnterEvent;
import net.labymod.api.models.addon.annotation.AddonMain;

@AddonMain
public class PixelStatsAddon extends LabyAddon<PixelStatsConfiguration> {

  private static final Set<String> SERVERS =
      Set.of("laby.world", "laby.net", "labymod.net", "laby.wtf");
  private static final Comparator<ScoreboardScore> SCORE_ORDER =
      Comparator.comparingInt(ScoreboardScore::getValue)
          .reversed()
          .thenComparing(ScoreboardScore::getName, String.CASE_INSENSITIVE_ORDER);
  private StatsPersistence persistence;
  private StatsLedger ledger = new StatsLedger();
  private PixelBalanceTracker balanceTracker = new PixelBalanceTracker(this.ledger);
  private final ArrayBlockingQueue<Reward> rewards =
      new ArrayBlockingQueue<>(StatsLedger.MAX_PENDING_REWARDS);
  private final ApartmentSignTracker apartmentSigns = new ApartmentSignTracker();
  private UUID account;
  private UUID loadingAccount;
  private boolean active;
  private volatile boolean shuttingDown;
  private long lastPoll;
  private BigDecimal candidate;
  private long candidateSince;
  private PixelTextParser.RentQuote pendingRent;
  private List<String> scoreboardLines = List.of();
  private final List<ScoreboardScore> scoreboardScores = new ArrayList<>();
  private final List<String> scoreboardBuffer = new ArrayList<>(15);
  private Optional<BigDecimal> parsedBalance = Optional.empty();
  private List<String> tooltipLines = List.of();
  private String lastRewardMessage = "";
  private String cachedHost;
  private String cachedExtraServer;
  private boolean serverMatches;
  private List<Component> lastTooltip = List.of();

  @Override
  protected void enable() {
    this.persistence =
        new StatsPersistence(
            new StatsStore(
                this.labyAPI()
                    .labyModLoader()
                    .getGameDirectory()
                    .resolve("labymod-neo/addons/pixelstats/data")),
            this.labyAPI().minecraft()::executeOnRenderThread,
            failure -> {
              String message =
                  failure.operation() == StatsPersistence.Operation.LOAD
                      ? "Could not load Pixel Stats history; preserving the existing file."
                      : "Could not save Pixel Stats history; keeping unsaved changes in memory.";
              this.logger().error(message, failure.cause());
            });
    this.registerSettingCategory();
    this.registerListener(new PixelWidgetConfigMigration());
    HudWidgetCategory category = new HudWidgetCategory(this, "pixelstats");
    this.labyAPI().hudWidgetRegistry().categoryRegistry().register(category);
    this.labyAPI()
        .hudWidgetRegistry()
        .register(
            new PixelStatsHudWidget<>(
                this, category, Metric.BALANCE, PixelStatsHudWidget.BalanceConfig.class));
    this.labyAPI()
        .hudWidgetRegistry()
        .register(
            new PixelStatsHudWidget<>(
                this, category, Metric.TODAY, PixelStatsHudWidget.TodayConfig.class));
    this.labyAPI()
        .hudWidgetRegistry()
        .register(
            new PixelStatsHudWidget<>(
                this, category, Metric.RENT, PixelStatsHudWidget.RentConfig.class));
    this.labyAPI()
        .hudWidgetRegistry()
        .register(
            new PixelStatsHudWidget<>(
                this, category, Metric.WEEK, PixelStatsHudWidget.WeekConfig.class));
    this.labyAPI()
        .hudWidgetRegistry()
        .register(
            new PixelStatsHudWidget<>(
                this, category, Metric.APARTMENT, PixelStatsHudWidget.ApartmentConfig.class));
    this.registerListener(this);
    this.registerListener(new DashboardHotkeyListener(this));
    this.registerCommand(new PixelStatsCommand(this));
  }

  @Override
  protected Class<PixelStatsConfiguration> configurationClass() {
    return PixelStatsConfiguration.class;
  }

  public boolean isLabyWorld() {
    ServerData server = this.labyAPI().serverController().getCurrentServerData();
    if (server == null
        || server.address() == null
        || !this.labyAPI().minecraft().isIngame()
        || this.labyAPI().minecraft().isSingleplayer()) {
      return false;
    }
    String host = server.address().getHost();
    String extra = this.configuration().extraServer().get();
    if (!Objects.equals(host, this.cachedHost) || !Objects.equals(extra, this.cachedExtraServer)) {
      this.cachedHost = host;
      this.cachedExtraServer = extra;
      this.serverMatches = matchesServer(host, extra);
    }
    return this.serverMatches;
  }

  private static boolean matchesServer(String address, String additionalAddress) {
    String host = address.toLowerCase(Locale.ROOT);
    if (host.endsWith(".")) {
      host = host.substring(0, host.length() - 1);
    }
    for (String domain : SERVERS) {
      if (host.equals(domain) || host.endsWith("." + domain)) {
        return true;
      }
    }
    String extra = additionalAddress.trim().toLowerCase(Locale.ROOT);
    return !extra.isEmpty() && host.equals(extra);
  }

  public boolean tracking() {
    return !this.shuttingDown
        && this.configuration().enabled().get()
        && isLabyWorld()
        && this.account != null
        && this.account.equals(this.labyAPI().getUniqueId());
  }

  public StatsLedger ledger() {
    return this.ledger;
  }

  public boolean dashboardAvailable() {
    return tracking();
  }

  public boolean openDashboard(boolean requireNoScreen) {
    if (!dashboardAvailable()) {
      return false;
    }
    UUID requestedAccount = this.account;
    this.labyAPI()
        .minecraft()
        .executeNextTick(
            () -> {
              var window = this.labyAPI().minecraft().minecraftWindow();
              if (dashboardAvailable()
                  && requestedAccount.equals(this.account)
                  && (!requireNoScreen || window.currentScreen() == null)) {
                window.displayScreen(new DashboardActivity(this, requestedAccount));
              }
            });
    return true;
  }

  public List<String> scoreboardLines() {
    return List.copyOf(this.scoreboardLines);
  }

  public List<String> tooltipLines() {
    return List.copyOf(this.tooltipLines);
  }

  public String lastRewardMessage() {
    return this.lastRewardMessage;
  }

  public List<String> apartmentSignLines() {
    return this.apartmentSigns.lines();
  }

  public ApartmentLease apartmentLease() {
    return tracking() ? this.ledger.apartmentLease() : null;
  }

  public boolean hasKnownBalance() {
    return tracking() && this.ledger.balance() != null;
  }

  @Subscribe
  public void onTick(GameTickEvent event) {
    if (this.shuttingDown || event.phase() != Phase.POST) {
      return;
    }
    long now = System.currentTimeMillis();
    if (now - this.lastPoll < 250) {
      return;
    }
    this.lastPoll = now;
    if (!this.configuration().enabled().get() || !isLabyWorld()) {
      if (this.active || this.loadingAccount != null) {
        suspend();
      }
      flush(false);
      return;
    }
    UUID currentAccount = this.labyAPI().getUniqueId();
    if (currentAccount == null) {
      suspend();
      flush(false);
      return;
    }
    if (!currentAccount.equals(this.account)) {
      if (!currentAccount.equals(this.loadingAccount)) {
        loadAccount(currentAccount);
      }
      return;
    }
    this.active = true;
    this.apartmentSigns
        .poll(
            this.labyAPI().minecraft().clientWorld(), currentAccount, this.labyAPI().getName(), now)
        .ifPresent(this.ledger::setApartmentLease);
    Reward reward;
    while ((reward = this.rewards.poll()) != null) {
      if (!currentAccount.equals(reward.account())) {
        continue;
      }
      this.balanceTracker.reward(
          reward.amount(),
          Instant.ofEpochMilli(reward.time()).atZone(ZoneId.systemDefault()).toLocalDate(),
          reward.time(),
          reward.messageId());
      this.lastRewardMessage = reward.text();
    }
    List<String> lines = readScoreboard();
    if (!lines.equals(this.scoreboardLines)) {
      this.scoreboardLines = List.copyOf(lines);
      this.parsedBalance = PixelTextParser.balance(lines);
    }
    var balance = this.parsedBalance;
    if (balance.isEmpty()) {
      this.candidate = null;
      this.balanceTracker.scoreboardMissing();
    } else {
      BigDecimal value = balance.get();
      if (this.candidate == null || this.candidate.compareTo(value) != 0) {
        this.candidate = value;
        this.candidateSince = now;
      } else if (now - this.candidateSince >= 500) {
        this.balanceTracker.observe(value, LocalDate.now(), now);
      }
    }
    this.ledger.showProvisional(
        this.configuration().provisionalRewards().get()
            && !this.balanceTracker.scoreboardVisible());
    if (this.pendingRent != null) {
      if (this.configuration().automaticRent().get() && !this.ledger.manualRent()) {
        this.ledger.setRent(this.pendingRent, false);
      }
      this.pendingRent = null;
    }
    flush(false);
  }

  private void loadAccount(UUID requestedAccount) {
    flush(true);
    this.balanceTracker.endSession();
    this.active = false;
    this.account = null;
    this.loadingAccount = requestedAccount;
    this.ledger = new StatsLedger();
    this.balanceTracker = new PixelBalanceTracker(this.ledger);
    this.apartmentSigns.clear();
    this.scoreboardLines = List.of();
    this.parsedBalance = Optional.empty();
    this.tooltipLines = List.of();
    this.lastRewardMessage = "";
    this.rewards.removeIf(reward -> !requestedAccount.equals(reward.account()));
    resetBaseline();
    this.persistence.select(
        requestedAccount,
        loaded -> {
          if (!requestedAccount.equals(this.loadingAccount)) {
            return;
          }
          this.loadingAccount = null;
          if (this.shuttingDown
              || !this.configuration().enabled().get()
              || !isLabyWorld()
              || !requestedAccount.equals(this.labyAPI().getUniqueId())) {
            this.persistence.deactivate();
            return;
          }
          this.ledger = loaded;
          this.balanceTracker = new PixelBalanceTracker(loaded);
          resetBaseline();
          this.account = requestedAccount;
        });
  }

  @Subscribe
  public void onInteract(ClientPlayerInteractEvent event) {
    if (this.shuttingDown
        || event.type() != ClientPlayerInteractEvent.InteractionType.INTERACT
        || !this.configuration().enabled().get()
        || !isLabyWorld()) {
      return;
    }
    var minecraft = this.labyAPI().minecraft();
    if (minecraft.getHitResult() instanceof BlockHitResult hit) {
      ApartmentSignReader.position(hit)
          .ifPresent(
              position ->
                  this.apartmentSigns.clicked(
                      minecraft.clientWorld(),
                      position,
                      this.labyAPI().getUniqueId(),
                      this.labyAPI().getName()));
    }
  }

  @Subscribe
  public void onBlockEntityUpdate(BlockEntityUpdateEvent event) {
    this.apartmentSigns.updated(event.blockEntity());
  }

  @Subscribe
  public void onSignLoad(SignBlockEntityPostLoadEvent event) {
    this.apartmentSigns.updated(event.sign());
  }

  @Subscribe
  public void onChat(ChatReceiveEvent event) {
    if (this.shuttingDown || !this.configuration().enabled().get() || !isLabyWorld()) {
      return;
    }
    var message = event.chatMessage();
    UUID recipient = this.labyAPI().getUniqueId();
    if (recipient == null) {
      return;
    }
    PixelRewardMessages.amount(message)
        .ifPresent(
            amount ->
                this.rewards.offer(
                    new Reward(
                        recipient,
                        amount,
                        System.currentTimeMillis(),
                        message.messageId(),
                        message.getOriginalPlainText())));
  }

  private List<String> readScoreboard() {
    Scoreboard scoreboard = this.labyAPI().minecraft().getScoreboard();
    if (scoreboard == null) {
      return List.of();
    }
    ScoreboardObjective objective = scoreboard.getObjective(DisplaySlot.SIDEBAR);
    if (objective == null) {
      return List.of();
    }
    List<ScoreboardScore> scores = this.scoreboardScores;
    scores.clear();
    for (ScoreboardScore score : scoreboard.getScores(objective)) {
      if (!score.isHidden()) {
        scores.add(score);
      }
    }
    scores.sort(SCORE_ORDER);
    List<String> lines = this.scoreboardBuffer;
    lines.clear();
    for (ScoreboardScore score : scores) {
      if (lines.size() == 15) {
        break;
      }
      Component name = score.getOwnerName();
      if (name == null) {
        name = Component.text(score.getName());
      }
      ScoreboardTeam team = scoreboard.teamByEntry(score.getName());
      if (team != null) {
        name = team.formatDisplayName(name);
      }
      lines.add(plain(name));
    }
    return lines;
  }

  @Subscribe
  public void onTooltip(ItemStackTooltipEvent event) {
    if (!tracking() || !this.configuration().automaticRent().get()) {
      return;
    }
    Object screen = this.labyAPI().minecraft().minecraftWindow().getCurrentVersionedScreen();
    if (screen == null || !this.labyAPI().screenService().isInventory(screen)) {
      return;
    }
    if (event.getTooltipLines().equals(this.lastTooltip)) {
      return;
    }
    this.lastTooltip = List.copyOf(event.getTooltipLines());
    List<String> lines = new ArrayList<>();
    for (Component line : event.getTooltipLines()) {
      lines.add(plain(line));
    }
    this.tooltipLines = lines;
    PixelTextParser.ownRent(lines).ifPresent(quote -> this.pendingRent = quote);
  }

  @Subscribe
  public void onResourceReload(ResourceReloadEvent event) {
    if (event.phase() == Phase.POST) {
      PixelHudLocalization.invalidate();
    }
  }

  @Subscribe
  public void onJoin(ServerJoinEvent event) {
    suspend();
  }

  @Subscribe
  public void onDisconnect(ServerDisconnectEvent event) {
    suspend();
    flush(true);
  }

  @Subscribe
  public void onWorldEnter(WorldEnterEvent event) {
    this.apartmentSigns.clear();
    this.candidate = null;
    this.pendingRent = null;
    this.balanceTracker.scoreboardMissing();
  }

  @Subscribe
  public void onShutdown(ShutdownEvent event) {
    if (event.stage() == ShutdownEvent.Stage.PRE) {
      this.shuttingDown = true;
      suspend();
      this.persistence.shutdown();
    }
  }

  @Override
  protected void onDeactivated() {
    suspend();
    flush(true);
  }

  private void suspend() {
    this.loadingAccount = null;
    if (this.persistence != null) {
      this.persistence.deactivate();
    }
    this.apartmentSigns.clear();
    this.active = false;
    this.scoreboardLines = List.of();
    this.parsedBalance = Optional.empty();
    this.scoreboardScores.clear();
    this.scoreboardBuffer.clear();
    this.tooltipLines = List.of();
    this.lastTooltip = List.of();
    this.rewards.clear();
    this.lastRewardMessage = "";
    resetBaseline();
  }

  private void resetBaseline() {
    this.balanceTracker.endSession();
    this.candidate = null;
    this.pendingRent = null;
    this.lastTooltip = List.of();
  }

  public void setManualRent(BigDecimal price) {
    this.ledger.setRent(new PixelTextParser.RentQuote(price, 3, "manual"), true);
    flush(true);
  }

  public void useAutomaticRent() {
    this.ledger.clearRent();
    this.lastTooltip = List.of();
    flush(true);
  }

  public void resetIncome(LocalDate from, LocalDate through) {
    this.rewards.clear();
    this.ledger.resetIncome(from, through);
    flush(true);
  }

  public void flush(boolean force) {
    if (this.persistence != null) {
      this.persistence.flush(force);
    }
  }

  private static String plain(Component component) {
    return PixelTextParser.clean(PlainTextComponentSerializer.plainText().serialize(component));
  }

  private record Reward(UUID account, BigDecimal amount, long time, UUID messageId, String text) {}
}
