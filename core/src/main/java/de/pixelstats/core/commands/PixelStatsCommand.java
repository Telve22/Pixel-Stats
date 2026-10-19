package de.pixelstats.core.commands;

import de.pixelstats.core.PixelStatsAddon;
import de.pixelstats.core.data.PixelTextParser;
import de.pixelstats.core.hud.PixelHudLayout;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import net.labymod.api.client.chat.command.Command;
import net.labymod.api.client.component.Component;
import net.labymod.api.client.component.format.NamedTextColor;

/** This is a client command. Returning true prevents forwarding it to the server. */
public final class PixelStatsCommand extends Command {

  private final PixelStatsAddon addon;

  public PixelStatsCommand(PixelStatsAddon addon) {
    super("pixelstats", "pstats");
    this.addon = addon;
  }

  @Override
  public boolean execute(String prefix, String[] arguments) {
    String action = arguments.length == 0 ? "hilfe" : arguments[0].toLowerCase(Locale.ROOT);
    switch (action) {
      case "dashboard", "statistik", "stats" -> {
        translated(this.addon.openDashboard(false) ? "dashboardOpened" : "joinFirst");
      }
      case "resetincome", "verdienstreset" -> {
        if (!this.addon.tracking()) {
          translated("joinFirst");
        } else if (arguments.length != 2) {
          translated("incomeResetUsage");
        } else {
          LocalDate today = LocalDate.now();
          String scope = arguments[1].toLowerCase(Locale.ROOT);
          LocalDate from =
              switch (scope) {
                case "today", "heute" -> today;
                case "week", "woche" ->
                    today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                case "all", "alles" -> LocalDate.MIN;
                default -> null;
              };
          if (from == null) {
            translated("incomeResetUsage");
          } else {
            this.addon.resetIncome(from, from.equals(LocalDate.MIN) ? LocalDate.MAX : today);
            translated("incomeResetSuccess");
          }
        }
      }
      case "miete", "rent" -> {
        if (!this.addon.tracking()) {
          translated("joinFirst");
        } else if (arguments.length != 2) {
          translated("rentUsage");
        } else {
          var amount = PixelTextParser.amount(arguments[1]);
          if (amount.isEmpty()) {
            translated("invalidAmount");
          } else {
            this.addon.setManualRent(amount.get());
            translated("rentSaved", format(this.addon.ledger().rentPerDay()));
          }
        }
      }
      case "auto" -> {
        if (!this.addon.tracking()) {
          translated("joinFirst");
        } else {
          this.addon.useAutomaticRent();
          translated("autoRent");
        }
      }
      case "debug" -> {
        translated("scoreboardHeader");
        if (this.addon.scoreboardLines().isEmpty()) {
          translated("noScoreboard");
        }
        this.addon.scoreboardLines().forEach(this::reply);
        translated("tooltipHeader");
        this.addon.tooltipLines().forEach(this::reply);
        translated("rewardHeader");
        if (this.addon.lastRewardMessage().isEmpty()) {
          translated("noReward");
        } else {
          reply(this.addon.lastRewardMessage());
        }
        translated("signHeader");
        this.addon.apartmentSignLines().forEach(this::reply);
      }
      case "status" -> {
        translated(
            "balanceStatus",
            this.addon.hasKnownBalance()
                ? format(this.addon.ledger().balance())
                : translate("waitingForScoreboard"));
        translated(
            "rentStatus",
            format(this.addon.ledger().rentPerDay()),
            this.addon.ledger().manualRent()
                ? translate("manualSource")
                : this.addon.ledger().rentSource());
        var lease = this.addon.apartmentLease();
        translated(
            "apartmentStatus",
            lease == null
                ? translate("readSign")
                : lease.apartment()
                    + " – "
                    + de.pixelstats.core.hud.PixelHudLocalization.apartmentTime(
                        lease,
                        System.currentTimeMillis(),
                        this.addon.labyAPI().internationalization()));
      }
      default -> {
        translated("helpWidgets");
        translated("helpCommands");
        translated("helpEarnings");
      }
    }
    return true;
  }

  private void reply(String text) {
    this.displayMessage(
        Component.text("[Pixel Stats] ", NamedTextColor.AQUA)
            .append(Component.text(text, NamedTextColor.GRAY)));
  }

  private void translated(String key, Object... arguments) {
    reply(translate(key, arguments));
  }

  private String translate(String key, Object... arguments) {
    return this.addon
        .labyAPI()
        .internationalization()
        .translate("pixelstats.commands." + key, arguments);
  }

  private String format(java.math.BigDecimal value) {
    return PixelHudLayout.format(
        value,
        de.pixelstats.core.hud.PixelHudLocalization.locale(
            this.addon.labyAPI().minecraft().options().getCurrentLanguage()));
  }
}
