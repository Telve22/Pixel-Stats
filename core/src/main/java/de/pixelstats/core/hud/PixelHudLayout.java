package de.pixelstats.core.hud;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

/** Card geometry and drawing operations, independent of the canvas implementation. */
public final class PixelHudLayout {

  public static final int WIDTH = 228;
  public static final int CARD_HEIGHT = 40;
  private static final int WHITE = 0xffe7edff;
  private static final int BLUE = 0xff32a5ff;
  private static final int AQUA = 0xff35ebef;
  private static final int GREEN = 0xff72ef7e;
  private static final int GOLD = 0xffffbf48;

  private PixelHudLayout() {}

  /** Each widget measures only its own label and value at their normal font size. */
  public static Layout layout(
      Draw draw, Metric metric, Model model, Labels labels, Locale locale, int requestedWidth) {
    return layout(
        draw, metric.label(labels, model.net()), metric.value(model, locale), requestedWidth);
  }

  public static Layout layout(Draw draw, String label, String value, int requestedWidth) {
    float textWidth =
        64 + Math.max(draw.textWidth(label, 9, false), draw.textWidth(value, 17, true));
    int width = Math.max(Math.max(100, requestedWidth), (int) Math.ceil(textWidth));
    return new Layout(width, CARD_HEIGHT);
  }

  public static void paint(
      Draw draw,
      Metric metric,
      Model model,
      Labels labels,
      Locale locale,
      Layout layout,
      Palette palette) {
    paint(
        draw,
        metric,
        model,
        metric.label(labels, model.net()),
        metric.value(model, locale),
        layout,
        palette);
  }

  public static void paint(
      Draw draw,
      Metric metric,
      Model model,
      String label,
      String value,
      Layout layout,
      Palette palette) {
    int color = metric.negative(model) ? palette.negative() : palette.value();
    draw.panel(
        1,
        1,
        layout.width() - 2,
        38,
        6,
        palette.backgroundTop(),
        palette.backgroundBottom(),
        palette.border());
    draw.panel(
        8,
        6,
        27,
        28,
        4,
        shade(palette.backgroundTop(), 0.8f),
        shade(palette.backgroundBottom(), 0.8f),
        shade(palette.border(), 0.75f));
    draw.icon(metric.cardIcon(), 11.5f, 10, 20, 20);
    draw.rect(42, 8, 0.7f, 24, palette.border());
    draw.text(label, 52, 6.5f, 9, palette.text(), false);
    draw.text(value, 52, 18, 17, color, true);
  }

  private static int shade(int color, float factor) {
    return (color & 0xff000000)
        | (Math.round(((color >>> 16) & 255) * factor) << 16)
        | (Math.round(((color >>> 8) & 255) * factor) << 8)
        | Math.round((color & 255) * factor);
  }

  public static String format(BigDecimal value) {
    return format(value, Locale.GERMANY);
  }

  public static String format(BigDecimal value, Locale locale) {
    if (value == null) {
      return "—";
    }
    NumberFormat formatter = NumberFormat.getNumberInstance(locale);
    formatter.setMaximumFractionDigits(2);
    return formatter.format(value);
  }

  public static String signed(BigDecimal value) {
    return signed(value, Locale.GERMANY);
  }

  public static String signed(BigDecimal value, Locale locale) {
    return (value.signum() >= 0 ? "+" : "") + format(value, locale);
  }

  public enum Metric {
    BALANCE("pixel_balance", AQUA),
    TODAY("pixel_today", GREEN),
    RENT("pixel_rent", GOLD),
    WEEK("pixel_week", GREEN),
    APARTMENT("apartment_time", BLUE);

    private final String id;
    private final int defaultColor;

    Metric(String id, int defaultColor) {
      this.id = id;
      this.defaultColor = defaultColor;
    }

    public String id() {
      return this.id;
    }

    public int defaultColor() {
      return this.defaultColor;
    }

    public CardIcon cardIcon() {
      return switch (this) {
        case BALANCE -> CardIcon.CASH;
        case TODAY -> CardIcon.INCOME;
        case RENT -> CardIcon.BANK;
        case WEEK -> CardIcon.CALENDAR;
        case APARTMENT -> CardIcon.CLOCK;
      };
    }

    public String labelKey(boolean net) {
      return switch (this) {
        case BALANCE -> "pixelstats.hud.balance";
        case TODAY -> net ? "pixelstats.hud.todayNet" : "pixelstats.hud.today";
        case RENT -> "pixelstats.hud.rent";
        case WEEK -> net ? "pixelstats.hud.weekNet" : "pixelstats.hud.week";
        case APARTMENT -> "pixelstats.hud.apartment";
      };
    }

    public String label(Labels labels, boolean net) {
      return switch (this) {
        case BALANCE -> labels.balance();
        case TODAY -> net ? labels.todayNet() : labels.today();
        case RENT -> labels.rent();
        case WEEK -> net ? labels.weekNet() : labels.week();
        case APARTMENT -> labels.apartment();
      };
    }

    public String value(Model model, Locale locale) {
      return switch (this) {
        case BALANCE -> (model.balanceProvisional() ? "≈ " : "") + model.balance();
        case TODAY -> (model.todayProvisional() ? "≈ " : "") + signed(model.today(), locale);
        case RENT -> model.rent();
        case WEEK -> (model.weekProvisional() ? "≈ " : "") + signed(model.week(), locale);
        case APARTMENT -> model.apartmentTime();
      };
    }

    public boolean negative(Model model) {
      return (this == TODAY && model.today().signum() < 0)
          || (this == WEEK && model.week().signum() < 0)
          || (this == APARTMENT && model.apartmentWarning());
    }
  }

  public record Labels(
      String balance,
      String today,
      String todayNet,
      String rent,
      String week,
      String weekNet,
      String apartment) {}

  public record Model(
      String balance,
      BigDecimal today,
      String rent,
      BigDecimal week,
      boolean net,
      String apartmentTime,
      boolean apartmentWarning,
      boolean balanceProvisional,
      boolean todayProvisional,
      boolean weekProvisional) {
    public Model(
        String balance,
        BigDecimal today,
        String rent,
        BigDecimal week,
        boolean net,
        String apartmentTime,
        boolean apartmentWarning) {
      this(balance, today, rent, week, net, apartmentTime, apartmentWarning, false, false, false);
    }

    public Model(String balance, BigDecimal today, String rent, BigDecimal week, boolean net) {
      this(balance, today, rent, week, net, "—", false);
    }
  }

  public enum Style {
    CARDS,
    TEXT
  }

  /** References LabyMod-owned resources; no copies of client artwork are bundled. */
  public enum CardIcon {
    CASH("textures/hudwidgets/economy/cash.png", false, 0xffffffff),
    INCOME("worldsharing/upgrade", true, 0xffffffff),
    BANK("textures/hudwidgets/economy/bank.png", false, 0xffffffff),
    CALENDAR("mods/features/date", true, 0xffffffff),
    CLOCK("mods/features/clock", true, 0xffffffff);

    private final String path;
    private final boolean themeSprite;
    private final int tint;

    CardIcon(String path, boolean themeSprite, int tint) {
      this.path = path;
      this.themeSprite = themeSprite;
      this.tint = tint;
    }

    public String path() {
      return this.path;
    }

    public boolean themeSprite() {
      return this.themeSprite;
    }

    public int tint() {
      return this.tint;
    }
  }

  public record Palette(
      int backgroundTop, int backgroundBottom, int border, int text, int value, int negative) {
    public static final Palette DEFAULT = defaults(Metric.BALANCE);

    public static Palette defaults(Metric metric) {
      return new Palette(
          0xff253049, 0xff121925, 0xff4b5a77, WHITE, metric.defaultColor(), 0xffff7d7d);
    }
  }

  public record Layout(int width, int height) {}

  public interface Draw {
    void icon(CardIcon icon, float x, float y, float width, float height);

    void rect(float x, float y, float width, float height, int color);

    void panel(
        float x, float y, float width, float height, float radius, int top, int bottom, int border);

    void text(String text, float x, float y, float size, int color, boolean bold);

    float textWidth(String text, float size, boolean bold);
  }
}
