package de.pixelstats.core.data;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses visible text only; scoreboard sorting scores are never treated as money. */
public final class PixelTextParser {

  private static final String NUMBER = "[0-9]+(?:[.,' ][0-9]+)*";
  private static final Pattern FORMATTING = Pattern.compile("§[^\\r\\n]");
  private static final Pattern INVISIBLE = Pattern.compile("\\p{Cf}");
  private static final Pattern DIACRITICS = Pattern.compile("\\p{M}");
  private static final Pattern AMOUNT = Pattern.compile("[0-9]+(?:[.,][0-9]+)*");
  private static final Pattern INTEGER = Pattern.compile("[0-9]+");
  private static final Pattern DOT_GROUPED = Pattern.compile("[0-9]{1,3}(?:\\.[0-9]{3})+");
  private static final Pattern COMMA_GROUPED = Pattern.compile("[0-9]{1,3}(?:,[0-9]{3})+");
  private static final Pattern PRICE_OR_EARNINGS =
      Pattern.compile("\\b(?:preis|kosten|verdient|earned|price|cost)\\b");
  private static final Pattern PIXEL_LABEL = Pattern.compile("(?i)[^0-9]*\\bpixels?\\b[^0-9]*");
  private static final Pattern BALANCE_OR_DEPOSIT =
      Pattern.compile("\\b(?:kontostand|bestand|balance|deine|your|guthaben|kaution|deposit)\\b");
  private static final Pattern DAILY_PRICE = Pattern.compile("\\b(?:pro|per)\\s+(?:tag|day)\\b");
  private static final Pattern TOTAL_PRICE =
      Pattern.compile("\\b(?:gesamtpreis|gesantpreis|gesamt|total|totalpreis)\\b");
  private static final Pattern PIXELS = Pattern.compile("(?i)\\bpixels?\\b");
  private static final Pattern BEFORE =
      Pattern.compile("(" + NUMBER + ")\\s*(?:[✦✧◆♦●⛃]\\s*)?$", Pattern.UNICODE_CHARACTER_CLASS);
  private static final Pattern AFTER =
      Pattern.compile("^[\\s:»›|=→✦✧◆♦●⛃]*(" + NUMBER + ")(?:\\s|$)");
  private static final Pattern ONLY_AMOUNT =
      Pattern.compile(
          "^[\\s:»›|✦✧◆♦●⛃]*(" + NUMBER + ")\\s*(?:pixels?)?[\\s✦✧◆♦●⛃]*$",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern PERIOD =
      Pattern.compile(
          "(?i)(?:\\b(?:pro|alle|fur|for|every|per)\\s*|\\b(?:dauer|mietdauer|duration)\\s*:\\s*|/\\s*)(\\d+)\\s*(?:tage?n?|days?)\\b");
  private static final Pattern HOUSING =
      Pattern.compile(
          "\\b(?:haus|hauses|hauser|wohnung|wohnungen|apartment|apartments|appartement|house|housing|rent|rental|miete|mietpreis|mietkosten|mieten|gemietet|mietdauer|anmieten|vermieten|verlangerung)\\b");
  private static final Pattern TENANT = Pattern.compile("(?i)\\b(?:gemietet von|rented by)\\b");
  private static final Pattern OWN_RENT_TITLE =
      Pattern.compile(
          "(?:miete\\s+verlangern|(?:renew|extend)\\s+(?:your\\s+)?rent(?:al)?(?:\\s+(?:period|lease))?)");
  private static final Pattern PIXEL_CREDIT =
      Pattern.compile("(?i)(?<![\\p{L}\\p{N}_+\\-.,])\\+\\s*(" + NUMBER + ")\\s*pixels?\\b");
  private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000000");

  private PixelTextParser() {}

  /** Removes formatting from received server text; never emits legacy color codes. */
  public static String clean(String text) {
    if (text == null) {
      return "";
    }
    // LabyWorld also uses nonstandard § + U+0080..U+0087 as invisible row IDs.
    // Strip every formatting pair, including these IDs, before matching numbers.
    String visible =
        FORMATTING.matcher(text).replaceAll("").replace('\u00a0', ' ').replace('\u202f', ' ');
    return INVISIBLE.matcher(visible).replaceAll("").trim();
  }

  public static List<String> cleanLines(List<String> rawLines) {
    List<String> lines = new ArrayList<>(rawLines.size());
    for (String line : rawLines) {
      lines.add(clean(line));
    }
    return lines;
  }

  public static Optional<BigDecimal> amount(String value) {
    String number = clean(value).replace("'", "").replace(" ", "");
    if (!AMOUNT.matcher(number).matches()) {
      return Optional.empty();
    }
    int dot = number.lastIndexOf('.');
    int comma = number.lastIndexOf(',');
    int separator = Math.max(dot, comma);
    if (separator >= 0) {
      int trailing = number.length() - separator - 1;
      char decimal = number.charAt(separator);
      if (trailing == 1 || trailing == 2) {
        String integer = number.substring(0, separator);
        // A decimal separator may appear only once; grouping uses the other character.
        if (integer.indexOf(decimal) >= 0 || !validGrouping(integer)) {
          return Optional.empty();
        }
        number = integer.replace(".", "").replace(",", "") + "." + number.substring(separator + 1);
      } else {
        if (!validGrouping(number)) {
          return Optional.empty();
        }
        number = number.replace(".", "").replace(",", "");
      }
    }
    try {
      BigDecimal result = new BigDecimal(number);
      return result.compareTo(MAX_AMOUNT) <= 0 ? Optional.of(result) : Optional.empty();
    } catch (NumberFormatException exception) {
      return Optional.empty();
    }
  }

  private static boolean validGrouping(String number) {
    return INTEGER.matcher(number).matches()
        || DOT_GROUPED.matcher(number).matches()
        || COMMA_GROUPED.matcher(number).matches();
  }

  public static Optional<BigDecimal> pixelAmount(String text) {
    String line = clean(text);
    Matcher pixel = PIXELS.matcher(line);
    List<BigDecimal> found = new ArrayList<>();
    while (pixel.find()) {
      Matcher before = BEFORE.matcher(line.substring(0, pixel.start()));
      Matcher after = AFTER.matcher(line.substring(pixel.end()));
      if (before.find() && (before.start() == 0 || line.charAt(before.start() - 1) != '-')) {
        amount(before.group(1)).ifPresent(found::add);
      }
      if (after.find()) {
        amount(after.group(1)).ifPresent(found::add);
      }
    }
    return unique(found);
  }

  /** Extracts one explicit +amount Pixel credit; origin is checked by PixelRewardMessages. */
  public static Optional<BigDecimal> reward(String text) {
    String line = clean(text);
    Matcher credit = PIXEL_CREDIT.matcher(line);
    if (!credit.find()) {
      return Optional.empty();
    }
    Optional<BigDecimal> amount = amount(credit.group(1)).filter(value -> value.signum() > 0);
    // Several signed Pixel figures can mean base/bonus/total; don't invent a sum.
    return credit.find() ? Optional.empty() : amount;
  }

  public static Optional<BigDecimal> balance(List<String> rawLines) {
    List<String> lines = cleanLines(rawLines);
    List<BigDecimal> found = new ArrayList<>();
    for (int index = 0; index < lines.size(); index++) {
      String line = lines.get(index);
      if (!PIXELS.matcher(line).find()) {
        continue;
      }
      // Ignore currencies in rent/price/earnings rows in a larger scoreboard.
      String normalized = normalize(line);
      if (HOUSING.matcher(normalized).find() || PRICE_OR_EARNINGS.matcher(normalized).find()) {
        continue;
      }
      Optional<BigDecimal> sameLine = pixelAmount(line);
      if (sameLine.isPresent()) {
        found.add(sameLine.get());
      } else if (PIXEL_LABEL.matcher(line).matches() && index + 1 < lines.size()) {
        Matcher next = ONLY_AMOUNT.matcher(lines.get(index + 1));
        if (next.matches()) {
          amount(next.group(1)).ifPresent(found::add);
        }
      }
    }
    return unique(found);
  }

  public static Optional<RentQuote> rent(List<String> rawLines) {
    List<String> lines = cleanLines(rawLines);
    String context = normalize(String.join(" ", lines));
    if (!HOUSING.matcher(context).find()) {
      return Optional.empty();
    }
    List<BigDecimal> prices = new ArrayList<>();
    List<BigDecimal> dailyPrices = new ArrayList<>();
    List<BigDecimal> totalPrices = new ArrayList<>();
    Integer days = null;
    Matcher period = PERIOD.matcher(context);
    while (period.find()) {
      int parsed;
      try {
        parsed = Integer.parseInt(period.group(1));
      } catch (NumberFormatException exception) {
        return Optional.empty();
      }
      if (parsed <= 0 || parsed > 365 || (days != null && days != parsed)) {
        return Optional.empty();
      }
      days = parsed;
    }
    for (String line : lines) {
      String normalized = normalize(line);
      if (BALANCE_OR_DEPOSIT.matcher(normalized).find()) {
        continue;
      }
      Optional<BigDecimal> price = pixelAmount(line);
      if (price.isEmpty()) {
        continue;
      }
      if (DAILY_PRICE.matcher(normalized).find()) {
        dailyPrices.add(price.get());
      } else if (TOTAL_PRICE.matcher(normalized).find()) {
        totalPrices.add(price.get());
      } else {
        prices.add(price.get());
      }
    }
    int duration = days == null ? 3 : days;
    String source = lines.isEmpty() ? "" : lines.getFirst();
    if (!dailyPrices.isEmpty()) {
      var daily = unique(dailyPrices);
      if (daily.isEmpty()) {
        return Optional.empty();
      }
      BigDecimal total = daily.get().multiply(BigDecimal.valueOf(duration));
      totalPrices.addAll(prices);
      if (!totalPrices.isEmpty()) {
        var statedTotal = unique(totalPrices);
        if (statedTotal.isEmpty() || total.compareTo(statedTotal.get()) != 0) {
          return Optional.empty();
        }
      }
      return Optional.of(new RentQuote(total, duration, source));
    }
    prices.addAll(totalPrices);
    return unique(prices).map(price -> new RentQuote(price, duration, source));
  }

  /** A viewed offer or somebody else's house must never replace the player's saved rent. */
  public static Optional<RentQuote> ownRent(List<String> lines) {
    String title = "";
    for (String raw : lines) {
      String line = clean(raw);
      if (TENANT.matcher(line).find()) {
        return Optional.empty();
      }
      if (title.isEmpty() && !line.isBlank()) {
        title = line;
      }
    }
    // The renewal action is only offered for your own house. Other houses expose
    // their tenant instead; the player's name is absent from the renewal tooltip.
    if (!OWN_RENT_TITLE.matcher(normalize(title)).matches()) {
      return Optional.empty();
    }
    return rent(lines);
  }

  private static Optional<BigDecimal> unique(List<BigDecimal> values) {
    if (values.isEmpty()) {
      return Optional.empty();
    }
    BigDecimal first = values.getFirst();
    for (BigDecimal value : values) {
      if (value.compareTo(first) != 0) {
        return Optional.empty();
      }
    }
    return Optional.of(first);
  }

  private static String normalize(String value) {
    return DIACRITICS
        .matcher(Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD))
        .replaceAll("");
  }

  public record RentQuote(BigDecimal price, int days, String source) {}
}
