package de.pixelstats.core.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/** Immutable account data transferred from the client thread to the storage worker. */
public record StatsSnapshot(
    long revision,
    BigDecimal balance,
    BigDecimal rentPrice,
    int rentDays,
    String rentSource,
    boolean manualRent,
    ApartmentLease apartmentLease,
    Map<LocalDate, StatsLedger.DayTotals> days,
    Map<LocalDateTime, StatsLedger.DayTotals> hours,
    Map<YearMonth, StatsLedger.DayTotals> months,
    StatsLedger.DayTotals lifetime,
    LocalDate recordingSince,
    LocalDateTime hourlySince,
    List<StatsLedger.PendingReward> pendingRewards) {

  public StatsSnapshot {
    days = Map.copyOf(days);
    hours = Map.copyOf(hours);
    months = Map.copyOf(months);
    pendingRewards = List.copyOf(pendingRewards);
  }
}
