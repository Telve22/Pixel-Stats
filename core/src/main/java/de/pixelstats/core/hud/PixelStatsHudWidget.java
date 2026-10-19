package de.pixelstats.core.hud;

import de.pixelstats.core.PixelStatsAddon;
import de.pixelstats.core.data.ApartmentLease;
import de.pixelstats.core.data.StatsLedger;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Objects;
import net.labymod.api.client.component.Component;
import net.labymod.api.client.component.format.TextDecoration;
import net.labymod.api.client.gfx.pipeline.renderer.text.FontFlags;
import net.labymod.api.client.gui.hud.binding.category.HudWidgetCategory;
import net.labymod.api.client.gui.hud.hudwidget.text.TextHudWidget;
import net.labymod.api.client.gui.hud.hudwidget.text.TextHudWidgetConfig;
import net.labymod.api.client.gui.hud.position.HudSize;
import net.labymod.api.client.gui.icon.Icon;
import net.labymod.api.client.gui.screen.ScreenContext;
import net.labymod.api.client.gui.screen.state.RoundedData;
import net.labymod.api.client.gui.screen.state.ScreenCanvas;
import net.labymod.api.client.gui.screen.state.states.GuiRectangleRenderState.RectConfig;
import net.labymod.api.client.gui.screen.widget.widgets.input.SliderWidget.SliderSetting;
import net.labymod.api.client.gui.screen.widget.widgets.input.color.ColorPickerWidget.ColorPickerSetting;
import net.labymod.api.client.gui.screen.widget.widgets.input.dropdown.DropdownWidget.DropdownSetting;
import net.labymod.api.configuration.loader.property.ConfigProperty;
import net.labymod.api.configuration.settings.annotation.ColorRowBreak;
import net.labymod.api.configuration.settings.annotation.SettingSection;
import net.labymod.api.util.color.GradientDirection;

/** One independently configurable HUD widget per statistic; all share the same ledger. */
public final class PixelStatsHudWidget<C extends PixelStatsHudWidget.Config>
    extends TextHudWidget<C> {

  private final PixelStatsAddon addon;
  private final PixelHudLayout.Metric metric;
  private PixelHudLayout.Model model;
  private PixelHudLayout.Labels labels;
  private Locale locale;
  private String language;
  private String lineKey;
  private PixelTextLine textLine;
  private long lastUpdate;
  private boolean editorContext;
  private CanvasDraw cardDraw;
  private String cardLabel;
  private String currentValue;
  private PixelHudLayout.Style lastStyle;
  private long localizationRevision = -1;
  private long lastTranslationCheck;
  private final Icon cardIcon;
  private StatsLedger displayedLedger;
  private long displayedRevision = -1;
  private LocalDate displayedDay;
  private long displayedLeaseHour = -1;
  private PixelHudLayout.Layout cardLayout;
  private String layoutLabel;
  private String layoutValue;
  private int layoutWidth;

  public PixelStatsHudWidget(
      PixelStatsAddon addon,
      HudWidgetCategory category,
      PixelHudLayout.Metric metric,
      Class<C> configClass) {
    super(metric.id(), configClass);
    this.addon = addon;
    this.metric = metric;
    this.bindCategory(category);
    this.cardIcon = PixelHudIcons.create(metric.cardIcon());
    this.setIcon(this.cardIcon);
  }

  @Override
  public boolean isVisibleInGame() {
    return this.labels != null && this.addon.tracking();
  }

  @Override
  public void load(C config) {
    super.load(config);
    this.model = null;
    this.textLine = null;
    updateSnapshot(true, System.currentTimeMillis());
  }

  @Override
  public void onTick(boolean editor) {
    updateSnapshot(editor, System.currentTimeMillis());
  }

  void updateSnapshot(boolean editor, long now) {
    boolean contextChanged = this.editorContext != editor;
    String currentLanguage = this.addon.labyAPI().minecraft().options().getCurrentLanguage();
    boolean net = this.addon.configuration().netEarnings().get();
    var style = this.getConfig().style().get();
    boolean languageChanged = !Objects.equals(this.language, currentLanguage);
    boolean translationsChanged = this.localizationRevision != PixelHudLocalization.revision();
    boolean checkTranslations =
        this.labels == null
            || languageChanged
            || translationsChanged
            || now - this.lastTranslationCheck >= 1_000;
    if (this.model != null
        && this.editorContext == editor
        && this.model.net() == net
        && this.displayedLedger == this.addon.ledger()
        && this.lastStyle == style
        && !checkTranslations
        && now - this.lastUpdate < 250) {
      return;
    }
    this.lastUpdate = now;
    this.editorContext = editor;
    this.lastStyle = style;
    if (checkTranslations) {
      this.language = currentLanguage;
      var resolvedLabels = PixelHudLocalization.labels(this.addon.labyAPI().internationalization());
      translationsChanged |= !Objects.equals(this.labels, resolvedLabels);
      this.labels = resolvedLabels;
      this.lastTranslationCheck = now;
      this.locale = PixelHudLocalization.locale(currentLanguage);
      this.localizationRevision = PixelHudLocalization.revision();
      if (translationsChanged || languageChanged) {
        this.cardLayout = null;
      }
    }
    if (this.labels == null) {
      this.model = null;
      this.textLine = null;
      this.lines.clear();
      return;
    }
    var ledger = this.addon.ledger();
    LocalDate day = LocalDate.now();
    var lease = editor ? null : this.addon.apartmentLease();
    long remaining = lease == null ? -1 : lease.remainingMillis(now);
    long leaseHour =
        remaining < 0
            ? -1
            : remaining == 0
                ? -2
                : remaining >= ApartmentLease.DAY
                    ? (remaining + ApartmentLease.HOUR - 1) / ApartmentLease.HOUR
                    : remaining / ApartmentLease.HOUR;
    boolean rebuild =
        this.model == null
            || contextChanged
            || languageChanged
            || translationsChanged
            || this.model.net() != net
            || (!editor
                && (ledger != this.displayedLedger
                    || ledger.revision() != this.displayedRevision
                    || !day.equals(this.displayedDay)
                    || leaseHour != this.displayedLeaseHour));
    if (editor && rebuild) {
      this.model =
          new PixelHudLayout.Model(
              PixelHudLayout.format(BigDecimal.valueOf(15894), this.locale),
              BigDecimal.valueOf(1240),
              "320",
              BigDecimal.valueOf(6870),
              net,
              "≈ 1d 13h",
              false);
    } else if (!editor && rebuild) {
      this.model =
          new PixelHudLayout.Model(
              this.addon.hasKnownBalance()
                  ? PixelHudLayout.format(ledger.balance(), this.locale)
                  : "—",
              ledger.today(day, net),
              PixelHudLayout.format(ledger.rentPerDay(), this.locale),
              ledger.week(day, net),
              net,
              PixelHudLocalization.apartmentTime(
                  lease, now, this.addon.labyAPI().internationalization()),
              lease != null && lease.needsRenewal(now),
              ledger.provisionalBalance(),
              ledger.provisionalToday(day),
              ledger.provisionalWeek(day));
    }
    this.displayedLedger = ledger;
    this.displayedRevision = ledger.revision();
    this.displayedDay = day;
    this.displayedLeaseHour = leaseHour;
    if (rebuild) {
      this.cardLabel = this.metric.label(this.labels, net);
      this.currentValue = this.metric.value(this.model, this.locale);
    }
    String key = this.metric.labelKey(net);
    boolean recreated = this.textLine == null || !key.equals(this.lineKey) || translationsChanged;
    if (recreated) {
      this.lines.clear();
      this.textLine = createLine(Component.translatable(key), "—", PixelTextLine::new);
      this.lineKey = key;
    }
    if (this.getConfig().style().get() == PixelHudLayout.Style.TEXT) {
      this.textLine.updateValue(
          this.currentValue,
          this.metric.negative(this.model),
          this.getConfig().negativeColor(),
          languageChanged && !recreated);
    }
  }

  @Override
  public void render(RenderPhase phase, ScreenContext context, boolean editor, HudSize size) {
    if (this.model == null
        || this.displayedLedger != this.addon.ledger()
        || this.editorContext != editor
        || this.lastStyle != this.getConfig().style().get()) {
      updateSnapshot(editor, System.currentTimeMillis());
    }
    if (this.model == null || this.labels == null) {
      size.set(0f, 0f);
      return;
    }
    if (this.getConfig().style().get() == PixelHudLayout.Style.TEXT) {
      // Use the same native line layout/rendering/settings as the built-in FPS widget.
      super.render(phase, context, editor, size);
      return;
    }
    float opacity = this.getConfig().opacity().get() / 100f;
    if (this.cardDraw == null
        || this.cardDraw.canvas != context.canvas()
        || this.cardDraw.opacity != opacity) {
      this.cardDraw = new CanvasDraw(context.canvas(), opacity);
      this.cardDraw.icons.put(this.metric.cardIcon(), this.cardIcon);
      this.cardLayout = null;
    }
    int width = this.getConfig().widgetWidth().get();
    if (this.cardLayout == null
        || width != this.layoutWidth
        || !Objects.equals(this.layoutLabel, this.cardLabel)
        || !Objects.equals(this.layoutValue, this.currentValue)) {
      this.cardLayout =
          PixelHudLayout.layout(this.cardDraw, this.cardLabel, this.currentValue, width);
      this.layoutLabel = this.cardLabel;
      this.layoutValue = this.currentValue;
      this.layoutWidth = width;
    }
    var layout = this.cardLayout;
    size.set((float) layout.width(), (float) layout.height());
    if (!phase.canRender()) {
      return;
    }
    PixelHudLayout.paint(
        this.cardDraw,
        this.metric,
        this.model,
        this.cardLabel,
        this.currentValue,
        layout,
        this.getConfig().palette());
  }

  public abstract static class Config extends TextHudWidgetConfig {

    @DropdownSetting
    private final ConfigProperty<PixelHudLayout.Style> style =
        new ConfigProperty<>(PixelHudLayout.Style.CARDS);

    @SliderSetting(min = 100, max = 320, steps = 1)
    private final ConfigProperty<Integer> widgetWidth = new ConfigProperty<>(PixelHudLayout.WIDTH);

    @SliderSetting(min = 20, max = 100, steps = 1)
    private final ConfigProperty<Integer> opacity = new ConfigProperty<>(94);

    @SettingSection("colors")
    @ColorRowBreak
    @ColorPickerSetting
    private final ConfigProperty<Integer> backgroundTopColor =
        new ConfigProperty<>(PixelHudLayout.Palette.DEFAULT.backgroundTop());

    @ColorRowBreak @ColorPickerSetting
    private final ConfigProperty<Integer> backgroundBottomColor =
        new ConfigProperty<>(PixelHudLayout.Palette.DEFAULT.backgroundBottom());

    @ColorRowBreak @ColorPickerSetting
    private final ConfigProperty<Integer> borderColor =
        new ConfigProperty<>(PixelHudLayout.Palette.DEFAULT.border());

    @ColorRowBreak @ColorPickerSetting
    private final ConfigProperty<Integer> textColor =
        new ConfigProperty<>(PixelHudLayout.Palette.DEFAULT.text());

    @ColorRowBreak @ColorPickerSetting private final ConfigProperty<Integer> cardValueColor;
    private transient PixelHudLayout.Palette cachedPalette;

    protected Config(PixelHudLayout.Metric metric) {
      this.cardValueColor = new ConfigProperty<>(metric.defaultColor());
      this.setEnabled(true);
      this.setX(8);
      this.setY(8 + metric.ordinal() * 43);
      this.widgetWidth.visibilitySupplier(() -> this.style.get() == PixelHudLayout.Style.CARDS);
      this.opacity.visibilitySupplier(() -> this.style.get() == PixelHudLayout.Style.CARDS);
      this.backgroundTopColor.visibilitySupplier(
          () -> this.style.get() == PixelHudLayout.Style.CARDS);
      this.backgroundBottomColor.visibilitySupplier(
          () -> this.style.get() == PixelHudLayout.Style.CARDS);
      this.borderColor.visibilitySupplier(() -> this.style.get() == PixelHudLayout.Style.CARDS);
      this.textColor.visibilitySupplier(() -> this.style.get() == PixelHudLayout.Style.CARDS);
      this.cardValueColor.visibilitySupplier(() -> this.style.get() == PixelHudLayout.Style.CARDS);
    }

    @Override
    public int getConfigVersion() {
      return 2;
    }

    public ConfigProperty<Integer> opacity() {
      return this.opacity;
    }

    public ConfigProperty<Integer> widgetWidth() {
      return this.widgetWidth;
    }

    public ConfigProperty<PixelHudLayout.Style> style() {
      return this.style;
    }

    public ConfigProperty<Integer> cardValueColor() {
      return this.cardValueColor;
    }

    protected int negativeColor() {
      return PixelHudLayout.Palette.DEFAULT.negative();
    }

    public PixelHudLayout.Palette palette() {
      if (this.cachedPalette == null
          || this.cachedPalette.backgroundTop() != this.backgroundTopColor.get()
          || this.cachedPalette.backgroundBottom() != this.backgroundBottomColor.get()
          || this.cachedPalette.border() != this.borderColor.get()
          || this.cachedPalette.text() != this.textColor.get()
          || this.cachedPalette.value() != this.cardValueColor.get()
          || this.cachedPalette.negative() != this.negativeColor()) {
        this.cachedPalette =
            new PixelHudLayout.Palette(
                this.backgroundTopColor.get(),
                this.backgroundBottomColor.get(),
                this.borderColor.get(),
                this.textColor.get(),
                this.cardValueColor.get(),
                this.negativeColor());
      }
      return this.cachedPalette;
    }
  }

  public static final class BalanceConfig extends Config {
    public BalanceConfig() {
      super(PixelHudLayout.Metric.BALANCE);
    }
  }

  public static final class RentConfig extends Config {
    public RentConfig() {
      super(PixelHudLayout.Metric.RENT);
    }
  }

  public abstract static class EarningsConfig extends Config {
    @ColorRowBreak @ColorPickerSetting
    private final ConfigProperty<Integer> negativeColor =
        new ConfigProperty<>(PixelHudLayout.Palette.DEFAULT.negative());

    protected EarningsConfig(PixelHudLayout.Metric metric) {
      super(metric);
    }

    @Override
    protected int negativeColor() {
      return this.negativeColor.get();
    }
  }

  public static final class TodayConfig extends EarningsConfig {
    public TodayConfig() {
      super(PixelHudLayout.Metric.TODAY);
    }

    @Override
    public int getConfigVersion() {
      return 3;
    }
  }

  public static final class WeekConfig extends EarningsConfig {
    public WeekConfig() {
      super(PixelHudLayout.Metric.WEEK);
    }
  }

  public static final class ApartmentConfig extends Config {
    @ColorRowBreak @ColorPickerSetting
    private final ConfigProperty<Integer> warningColor = new ConfigProperty<>(0xffff5555);

    public ApartmentConfig() {
      super(PixelHudLayout.Metric.APARTMENT);
    }

    public ConfigProperty<Integer> warningColor() {
      return this.warningColor;
    }

    @Override
    protected int negativeColor() {
      return this.warningColor.get();
    }
  }

  static final class CanvasDraw implements PixelHudLayout.Draw {

    final ScreenCanvas canvas;
    final float opacity;
    private final EnumMap<PixelHudLayout.CardIcon, Icon> icons =
        new EnumMap<>(PixelHudLayout.CardIcon.class);
    private String plainText;
    private String boldText;
    private Component plainComponent;
    private Component boldComponent;
    private final PanelStyle[] panelStyles = new PanelStyle[4];
    private int nextPanelStyle;

    CanvasDraw(ScreenCanvas canvas, float opacity) {
      this.canvas = canvas;
      this.opacity = opacity;
    }

    private int alpha(int color) {
      return (Math.round((color >>> 24) * this.opacity) << 24) | (color & 0xffffff);
    }

    @Override
    public void icon(PixelHudLayout.CardIcon source, float x, float y, float width, float height) {
      Icon icon = this.icons.computeIfAbsent(source, PixelHudIcons::create);
      this.canvas.submitIcon(icon, x, y, width, height, false, alpha(source.tint()));
    }

    @Override
    public void rect(float x, float y, float width, float height, int color) {
      this.canvas.submitRelativeRect(x, y, width, height, color);
    }

    @Override
    public void panel(
        float x,
        float y,
        float width,
        float height,
        float radius,
        int top,
        int bottom,
        int border) {
      for (PanelStyle style : this.panelStyles) {
        if (style != null
            && style.radius() == radius
            && style.top() == top
            && style.bottom() == bottom
            && style.border() == border) {
          this.canvas.submitGuiRect(x, y, width, height, style.config());
          return;
        }
      }
      RoundedData rounded =
          RoundedData.builder()
              .setRadius(radius)
              .setBorderThickness(border == 0 ? 0 : 0.5f)
              .setBorderColor(alpha(border))
              .setLowerEdgeSoftness(0.6f)
              .setUpperEdgeSoftness(0.6f)
              .build();
      RectConfig config =
          RectConfig.builder()
              .setGradient(GradientDirection.TOP_TO_BOTTOM, alpha(top), alpha(bottom))
              .setRoundedData(rounded)
              .build();
      this.panelStyles[this.nextPanelStyle] = new PanelStyle(radius, top, bottom, border, config);
      this.nextPanelStyle = (this.nextPanelStyle + 1) % this.panelStyles.length;
      this.canvas.submitGuiRect(x, y, width, height, config);
    }

    @Override
    public void text(String text, float x, float y, float size, int color, boolean bold) {
      // ScreenCanvas expects a multiplier, not a height in GUI pixels.
      float scale = size / this.canvas.getLineHeight();
      this.canvas.submitComponent(component(text, bold), x, y, color, scale, FontFlags.SHADOW);
    }

    @Override
    public float textWidth(String text, float size, boolean bold) {
      return this.canvas.getTextWidth(component(text, bold)) / this.canvas.getLineHeight() * size;
    }

    private Component component(String text, boolean bold) {
      if (bold) {
        if (!text.equals(this.boldText)) {
          this.boldText = text;
          this.boldComponent = Component.text(text).decorate(TextDecoration.BOLD);
        }
        return this.boldComponent;
      }
      if (!text.equals(this.plainText)) {
        this.plainText = text;
        this.plainComponent = Component.text(text);
      }
      return this.plainComponent;
    }

    private record PanelStyle(float radius, int top, int bottom, int border, RectConfig config) {}
  }
}
