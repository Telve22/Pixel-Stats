package de.pixelstats.core.dashboard;

import de.pixelstats.core.data.StatsLedger;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/** Immutable confirmed account statistics, separate from optional HUD estimates. */
public record DashboardSnapshot(
    BigDecimal balance,
    StatsLedger.DayTotals today,
    StatsLedger.DayTotals week,
    StatsLedger.DayTotals total,
    LocalDate since,
    BigDecimal pending) {

  public static DashboardSnapshot from(StatsLedger ledger, LocalDate date) {
    BigDecimal pending = BigDecimal.ZERO;
    for (var reward : ledger.pendingRewards()) {
      pending = pending.add(reward.amount());
    }
    return new DashboardSnapshot(
        ledger.confirmedBalance(),
        ledger.totals(date, date),
        ledger.totals(date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), date),
        ledger.lifetime(),
        ledger.recordingSince(),
        pending);
  }
}
