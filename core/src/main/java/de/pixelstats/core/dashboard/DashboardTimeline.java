package de.pixelstats.core.dashboard;

import de.pixelstats.core.data.StatsLedger;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Confirmed calendar statistics; unavailable hours/future dates are gaps, not invented zeroes. */
public record DashboardTimeline(
    DashboardPeriod period,
    Granularity granularity,
    List<Bucket> buckets,
    StatsLedger.DayTotals unallocated) {
  private static final StatsLedger.DayTotals EMPTY =
      new StatsLedger.DayTotals(BigDecimal.ZERO, BigDecimal.ZERO);

  public DashboardTimeline {
    buckets = List.copyOf(buckets);
  }

  public static DashboardTimeline from(
      StatsLedger ledger, LocalDate today, DashboardPeriod period) {
    return from(ledger, today.atTime(23, 59), period);
  }

  public static DashboardTimeline from(
      StatsLedger ledger, LocalDateTime now, DashboardPeriod period) {
    LocalDate today = now.toLocalDate();
    var buckets = new ArrayList<Bucket>();
    if (period == DashboardPeriod.DAY) {
      var assigned = EMPTY;
      for (int hour = 0; hour < 24; hour++) {
        var time = today.atTime(hour, 0);
        var totals = ledger.hourTotals(time);
        assigned = StatsLedger.plus(assigned, totals);
        boolean known =
            ledger.hourlySince() != null
                && !time.isBefore(ledger.hourlySince())
                && !time.isAfter(now);
        buckets.add(new Bucket(today, today, totals, false, hour, known));
      }
      var daily = ledger.totals(today, today);
      return new DashboardTimeline(
          period,
          Granularity.HOUR,
          buckets,
          new StatsLedger.DayTotals(
              daily.income().subtract(assigned.income()), daily.net().subtract(assigned.net())));
    }
    if (period == DashboardPeriod.WEEK) {
      var monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
      for (int day = 0; day < 7; day++) {
        var date = monday.plusDays(day);
        buckets.add(
            new Bucket(
                date,
                date,
                date.isAfter(today) ? EMPTY : ledger.totals(date, date),
                false,
                -1,
                !date.isAfter(today)));
      }
      return new DashboardTimeline(period, Granularity.DAY, buckets, EMPTY);
    }
    LocalDate since = ledger.recordingSince() == null ? today : ledger.recordingSince();
    long span = ChronoUnit.DAYS.between(since, today) + 1;
    Granularity unit =
        span <= 14
            ? Granularity.DAY
            : span <= 90 ? Granularity.WEEK : span <= 730 ? Granularity.MONTH : Granularity.YEAR;
    LocalDate first = today;
    if (unit == Granularity.DAY || unit == Granularity.WEEK) {
      for (var date : ledger.days().keySet()) {
        if (date.isBefore(first)) {
          first = date;
        }
      }
    } else {
      for (var month : ledger.months().keySet()) {
        if (month.atDay(1).isBefore(first)) {
          first = month.atDay(1);
        }
      }
    }
    var accounted = EMPTY;
    var months = ledger.months();
    for (LocalDate start = floor(first, unit); !start.isAfter(today); start = next(start, unit)) {
      LocalDate end = next(start, unit).minusDays(1);
      if (end.isAfter(today)) {
        end = today;
      }
      var totals = EMPTY;
      if (unit == Granularity.DAY || unit == Granularity.WEEK) {
        totals = ledger.totals(start, end);
      } else {
        for (var entry : months.entrySet()) {
          var month = entry.getKey().atDay(1);
          if (!month.isBefore(start) && !month.isAfter(end)) {
            totals = StatsLedger.plus(totals, entry.getValue());
          }
        }
      }
      accounted = StatsLedger.plus(accounted, totals);
      buckets.add(new Bucket(start, end, totals, false));
    }
    var earlier =
        new StatsLedger.DayTotals(
            ledger.lifetime().income().subtract(accounted.income()),
            ledger.lifetime().net().subtract(accounted.net()));
    if (earlier.income().signum() != 0 || earlier.net().signum() != 0) {
      buckets.addFirst(new Bucket(since, first.minusDays(1), earlier, true));
    }
    return new DashboardTimeline(period, unit, buckets, EMPTY);
  }

  private static LocalDate floor(LocalDate date, Granularity unit) {
    return switch (unit) {
      case WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
      case MONTH -> date.withDayOfMonth(1);
      case YEAR -> date.withDayOfYear(1);
      default -> date;
    };
  }

  private static LocalDate next(LocalDate date, Granularity unit) {
    return switch (unit) {
      case WEEK -> date.plusWeeks(1);
      case MONTH -> date.plusMonths(1);
      case YEAR -> date.plusYears(1);
      default -> date.plusDays(1);
    };
  }

  public enum Granularity {
    HOUR,
    DAY,
    WEEK,
    MONTH,
    YEAR
  }

  public record Bucket(
      LocalDate from,
      LocalDate through,
      StatsLedger.DayTotals totals,
      boolean archived,
      int hour,
      boolean available) {
    public Bucket(
        LocalDate from, LocalDate through, StatsLedger.DayTotals totals, boolean archived) {
      this(from, through, totals, archived, -1, true);
    }

    public String label(Locale locale) {
      if (hour >= 0) {
        return String.format(Locale.ROOT, "%02d:00 – %02d:00", hour, hour + 1);
      }
      var format = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale);
      return from.equals(through)
          ? format.format(from)
          : format.format(from) + " – " + format.format(through);
    }

    public String axisLabel(DashboardPeriod period, Locale locale) {
      if (hour >= 0) {
        return String.format(Locale.ROOT, "%02d:00", hour);
      }
      if (period == DashboardPeriod.WEEK) {
        return DateTimeFormatter.ofPattern("EE", locale).format(from);
      }
      return DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale).format(from);
    }

    public String axisLabel(DashboardTimeline timeline, Locale locale) {
      return switch (timeline.granularity()) {
        case MONTH -> DateTimeFormatter.ofPattern("MMM yy", locale).format(from);
        case YEAR -> Integer.toString(from.getYear());
        default -> axisLabel(timeline.period(), locale);
      };
    }
  }
}
