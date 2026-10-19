package de.pixelstats.core.hud;

import de.pixelstats.core.data.ApartmentLease;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import net.labymod.api.localization.Internationalization;

/** Never caches missing translations; resource reloads invalidate the widgets' resolved labels. */
public final class PixelHudLocalization {

  private static final String PREFIX = "pixelstats.hud.";
  private static final AtomicLong REVISION = new AtomicLong();

  private PixelHudLocalization() {}

  public static PixelHudLayout.Labels labels(Internationalization translations) {
    var labels =
        new PixelHudLayout.Labels(
            translations.translate(PREFIX + "balance"),
            translations.translate(PREFIX + "today"),
            translations.translate(PREFIX + "todayNet"),
            translations.translate(PREFIX + "rent"),
            translations.translate(PREFIX + "week"),
            translations.translate(PREFIX + "weekNet"),
            translations.translate(PREFIX + "apartment"));
    if (!resolved("balance", labels.balance())
        || !resolved("today", labels.today())
        || !resolved("todayNet", labels.todayNet())
        || !resolved("rent", labels.rent())
        || !resolved("week", labels.week())
        || !resolved("weekNet", labels.weekNet())
        || !resolved("apartment", labels.apartment())) {
      return null;
    }
    return labels;
  }

  private static boolean resolved(String name, String value) {
    return value != null && !value.isBlank() && !value.equals(PREFIX + name);
  }

  public static long revision() {
    return REVISION.get();
  }

  public static void invalidate() {
    REVISION.incrementAndGet();
  }

  public static String apartmentTime(
      ApartmentLease lease, long now, Internationalization translations) {
    return lease == null
        ? "—"
        : lease.remainingText(now, translations.translate(PREFIX + "apartmentExpired"));
  }

  public static Locale locale(String minecraftLanguage) {
    if (minecraftLanguage == null || minecraftLanguage.isBlank()) {
      return Locale.US;
    }
    Locale locale = Locale.forLanguageTag(minecraftLanguage.replace('_', '-'));
    return locale.getLanguage().isEmpty() ? Locale.US : locale;
  }
}
