package de.pixelstats.core.data;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** LabyWorld apartment signs: marker, apartment name, tenant name, remaining duration. */
public final class ApartmentSignParser {

  private static final Pattern DURATION = Pattern.compile("(?i)([0-9]+)\\s*([dthms])");

  private ApartmentSignParser() {}

  public static Optional<ApartmentLease> owned(List<String> rawLines, String playerName, long now) {
    if (rawLines == null
        || rawLines.size() != 4
        || playerName == null
        || !playerName.matches("[A-Za-z0-9_]{1,16}")
        || now < 0) {
      return Optional.empty();
    }
    List<String> lines = PixelTextParser.cleanLines(rawLines);
    if (!lines.getFirst().equalsIgnoreCase("[Apartment]")
        || lines.get(1).isBlank()
        || !lines.get(2).equalsIgnoreCase(playerName)) {
      return Optional.empty();
    }
    String time = lines.get(3).toLowerCase(Locale.ROOT);
    var matcher = DURATION.matcher(time);
    int end = 0;
    long duration = 0;
    long precision = Long.MAX_VALUE;
    boolean found = false;
    try {
      if (time.equals("abgelaufen") || time.equals("expired")) {
        return Optional.of(
            new ApartmentLease(lines.get(1), lines.get(2), now, 0, ApartmentLease.MINUTE));
      }
      while (matcher.find()) {
        if (!time.substring(end, matcher.start()).isBlank()) {
          return Optional.empty();
        }
        long unit =
            switch (matcher.group(2)) {
              case "d", "t" -> ApartmentLease.DAY;
              case "h" -> ApartmentLease.HOUR;
              case "m" -> ApartmentLease.MINUTE;
              case "s" -> 1_000L;
              default -> throw new IllegalArgumentException("Unknown time unit");
            };
        if (unit >= precision) {
          return Optional.empty();
        }
        duration =
            Math.addExact(duration, Math.multiplyExact(Long.parseLong(matcher.group(1)), unit));
        precision = unit;
        end = matcher.end();
        found = true;
      }
      if (!found || !time.substring(end).isBlank() || duration > ApartmentLease.MAX_DURATION) {
        return Optional.empty();
      }
      return Optional.of(new ApartmentLease(lines.get(1), lines.get(2), now, duration, precision));
    } catch (IllegalArgumentException | ArithmeticException exception) {
      return Optional.empty();
    }
  }
}
