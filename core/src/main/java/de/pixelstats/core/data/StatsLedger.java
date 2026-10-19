package de.pixelstats.core.data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Account history. Session baselines intentionally never survive reconnects. */
public final class StatsLedger {

  private final NavigableMap<LocalDate, DayTotals> days = new TreeMap<>();
  private final NavigableMap<LocalDateTime, DayTotals> hours = new TreeMap<>();
  private final NavigableMap<YearMonth, DayTotals> months = new TreeMap<>();
  private LocalDateTime hourlySince;
  private DayTotals lifetime = DayTotals.ZERO;
  private LocalDate recordingSince;
  public static final int MAX_PENDING_REWARDS = 256;
  private final List<PendingReward> pendingRewards = new ArrayList<>();
  private boolean showProvisional;
  private BigDecimal balance;
  private BigDecimal baseline;
  private BigDecimal rentPrice;
  private int rentDays = 3;
  private String rentSource = "";
  private boolean manualRent;
  private ApartmentLease apartmentLease;
  private boolean dirty;
  private long revision;

  public void observe(BigDecimal value, LocalDate day, long time) {
    if (value.signum() < 0) {
      throw new IllegalArgumentException("Negative balance");
    }
    recordSince(day);
    trackHourlySince(hour(day, time));
    if (this.baseline != null) {
      BigDecimal delta = value.subtract(this.baseline);
      if (delta.signum() != 0) {
        addTotals(delta.max(BigDecimal.ZERO), delta, day, time);
      }
    }
    if (this.balance == null || value.compareTo(this.balance) != 0) {
      changed();
    }
    this.balance = value;
    this.baseline = value;
    prune(day);
  }

  /** Commits a credit only after the tracker matched it to an actual scoreboard increase. */
  public void reward(BigDecimal amount, LocalDate day, long time) {
    if (amount.signum() <= 0) {
      throw new IllegalArgumentException("Nonpositive reward");
    }
    addTotals(amount, amount, day, time);
    if (this.balance != null) {
      this.balance = this.balance.add(amount);
    }
    if (this.baseline != null) {
      this.baseline = this.baseline.add(amount);
    }
    prune(day);
  }

  private void addTotals(BigDecimal income, BigDecimal net, LocalDate day, long time) {
    DayTotals totals = this.days.getOrDefault(day, DayTotals.ZERO);
    this.days.put(day, new DayTotals(totals.income().add(income), totals.net().add(net)));
    this.lifetime = new DayTotals(this.lifetime.income().add(income), this.lifetime.net().add(net));
    LocalDateTime hour = hour(day, time);
    trackHourlySince(hour);
    this.hours.merge(hour, new DayTotals(income, net), StatsLedger::plus);
    this.months.merge(YearMonth.from(day), new DayTotals(income, net), StatsLedger::plus);
    recordSince(day);
    changed();
  }

  private static LocalDateTime hour(LocalDate day, long time) {
    int hour = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).getHour();
    return day.atTime(LocalTime.of(hour, 0));
  }

  private void trackHourlySince(LocalDateTime hour) {
    if (this.hourlySince == null || hour.isBefore(this.hourlySince)) {
      this.hourlySince = hour;
      changed();
    }
  }

  public static DayTotals plus(DayTotals left, DayTotals right) {
    return new DayTotals(left.income().add(right.income()), left.net().add(right.net()));
  }

  public DayTotals hourTotals(LocalDateTime hour) {
    return this.hours.getOrDefault(hour, DayTotals.ZERO);
  }

  public LocalDateTime hourlySince() {
    return this.hourlySince;
  }

  public Map<LocalDateTime, DayTotals> hours() {
    return Map.copyOf(this.hours);
  }

  public Map<YearMonth, DayTotals> months() {
    return Map.copyOf(this.months);
  }

  private void recordSince(LocalDate day) {
    if (this.recordingSince == null || day.isBefore(this.recordingSince)) {
      this.recordingSince = day;
      changed();
    }
  }

  /** Confirmed totals only; provisional chat candidates never enter these statistics. */
  public DayTotals totals(LocalDate from, LocalDate through) {
    BigDecimal income = BigDecimal.ZERO;
    BigDecimal net = BigDecimal.ZERO;
    for (DayTotals day : this.days.subMap(from, true, through, true).values()) {
      income = income.add(day.income());
      net = net.add(day.net());
    }
    return new DayTotals(income, net);
  }

  public DayTotals lifetime() {
    return this.lifetime;
  }

  public LocalDate recordingSince() {
    return this.recordingSince;
  }

  private void prune(LocalDate day) {
    if (!this.hours.headMap(day.minusDays(7).atStartOfDay(), false).isEmpty()) {
      this.hours.headMap(day.minusDays(7).atStartOfDay(), false).clear();
      changed();
    }
    if (!this.days.headMap(day.minusDays(90), false).isEmpty()) {
      this.days.headMap(day.minusDays(90), false).clear();
      changed();
    }
  }

  public void endSession() {
    this.baseline = null;
  }

  public BigDecimal today(LocalDate day, boolean net) {
    DayTotals totals = this.days.getOrDefault(day, DayTotals.ZERO);
    BigDecimal value = net ? totals.net() : totals.income();
    return this.showProvisional ? value.add(pendingAmount(day, day)) : value;
  }

  public BigDecimal week(LocalDate day, boolean net) {
    LocalDate monday = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    BigDecimal sum = BigDecimal.ZERO;
    for (DayTotals total : this.days.subMap(monday, true, day, true).values()) {
      sum = sum.add(net ? total.net() : total.income());
    }
    return this.showProvisional ? sum.add(pendingAmount(monday, day)) : sum;
  }

  public BigDecimal[] recentDays(LocalDate day, boolean net) {
    BigDecimal[] chart = new BigDecimal[7];
    for (int i = 0; i < 7; i++) {
      chart[i] = today(day.minusDays(6 - i), net);
    }
    return chart;
  }

  public void setRent(PixelTextParser.RentQuote quote, boolean manual) {
    if (quote.price().signum() < 0 || quote.days() <= 0) {
      throw new IllegalArgumentException("Invalid rent");
    }
    if (this.rentPrice == null
        || this.rentPrice.compareTo(quote.price()) != 0
        || this.rentDays != quote.days()
        || !this.rentSource.equals(quote.source())
        || this.manualRent != manual) {
      this.rentPrice = quote.price();
      this.rentDays = quote.days();
      this.rentSource = quote.source();
      this.manualRent = manual;
      changed();
    }
  }

  public void clearRent() {
    this.rentPrice = null;
    this.rentSource = "";
    this.manualRent = false;
    changed();
  }

  public BigDecimal rentPerDay() {
    return this.rentPrice == null
        ? null
        : this.rentPrice.divide(BigDecimal.valueOf(this.rentDays), 2, RoundingMode.HALF_UP);
  }

  public BigDecimal balance() {
    return this.balance != null && this.showProvisional
        ? this.balance.add(pendingAmount(LocalDate.MIN, LocalDate.MAX))
        : this.balance;
  }

  public BigDecimal confirmedBalance() {
    return this.balance;
  }

  public List<PendingReward> pendingRewards() {
    return List.copyOf(this.pendingRewards);
  }

  public void showProvisional(boolean show) {
    if (this.showProvisional != show) {
      this.showProvisional = show;
      this.revision++;
    }
  }

  public boolean provisionalBalance() {
    return this.showProvisional && this.balance != null && !this.pendingRewards.isEmpty();
  }

  public boolean provisionalToday(LocalDate day) {
    return this.showProvisional && pendingAmount(day, day).signum() > 0;
  }

  public boolean provisionalWeek(LocalDate day) {
    return this.showProvisional
        && pendingAmount(day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), day).signum()
            > 0;
  }

  void stageReward(BigDecimal amount, LocalDate day, long time) {
    if (this.pendingRewards.size() == MAX_PENDING_REWARDS) {
      this.pendingRewards.removeFirst();
    }
    this.pendingRewards.add(new PendingReward(amount, day, time));
    changed();
  }

  /** A confirmed increase is the maximum budget; unmatched claims never become history. */
  BigDecimal confirmPending(BigDecimal budget) {
    BigDecimal remaining = budget;
    while (remaining.signum() > 0 && !this.pendingRewards.isEmpty()) {
      PendingReward pending = this.pendingRewards.removeFirst();
      BigDecimal matched = remaining.min(pending.amount());
      reward(matched, pending.day(), pending.time());
      remaining = remaining.subtract(matched);
      BigDecimal unused = pending.amount().subtract(matched);
      if (unused.signum() > 0) {
        this.pendingRewards.addFirst(new PendingReward(unused, pending.day(), pending.time()));
      }
      changed();
    }
    return remaining;
  }

  void discardPending() {
    if (!this.pendingRewards.isEmpty()) {
      this.pendingRewards.clear();
      changed();
    }
  }

  private BigDecimal pendingAmount(LocalDate from, LocalDate through) {
    BigDecimal sum = BigDecimal.ZERO;
    for (PendingReward pending : this.pendingRewards) {
      if (!pending.day().isBefore(from) && !pending.day().isAfter(through)) {
        sum = sum.add(pending.amount());
      }
    }
    return sum;
  }

  public void resetIncome(LocalDate from, LocalDate through) {
    DayTotals removed = totals(from, through);
    for (var entry : this.days.subMap(from, true, through, true).entrySet()) {
      this.months.merge(
          YearMonth.from(entry.getKey()),
          new DayTotals(entry.getValue().income().negate(), entry.getValue().net().negate()),
          StatsLedger::plus);
    }
    this.hours
        .entrySet()
        .removeIf(
            entry ->
                !entry.getKey().toLocalDate().isBefore(from)
                    && !entry.getKey().toLocalDate().isAfter(through));
    this.lifetime =
        new DayTotals(
            this.lifetime.income().subtract(removed.income()),
            this.lifetime.net().subtract(removed.net()));
    if (from.equals(LocalDate.MIN) && through.equals(LocalDate.MAX)) {
      this.lifetime = DayTotals.ZERO;
      this.recordingSince = null;
      this.months.clear();
      this.hours.clear();
      this.hourlySince = null;
    }
    this.days.subMap(from, true, through, true).clear();
    this.pendingRewards.removeIf(
        pending -> !pending.day().isBefore(from) && !pending.day().isAfter(through));
    changed();
  }

  public BigDecimal rentPrice() {
    return this.rentPrice;
  }

  public int rentDays() {
    return this.rentDays;
  }

  public String rentSource() {
    return this.rentSource;
  }

  public boolean manualRent() {
    return this.manualRent;
  }

  public ApartmentLease apartmentLease() {
    return this.apartmentLease;
  }

  public void setApartmentLease(ApartmentLease lease) {
    if (this.apartmentLease != null) {
      lease = this.apartmentLease.reconcile(lease);
    }
    if (!lease.equals(this.apartmentLease)) {
      this.apartmentLease = lease;
      changed();
    }
  }

  public boolean dirty() {
    return this.dirty;
  }

  public long revision() {
    return this.revision;
  }

  private void changed() {
    this.dirty = true;
    this.revision++;
  }

  public void markSaved() {
    this.dirty = false;
  }

  public Map<LocalDate, DayTotals> days() {
    return Map.copyOf(this.days);
  }

  void restore(
      BigDecimal value,
      BigDecimal price,
      int duration,
      String source,
      boolean manual,
      Map<LocalDate, DayTotals> history,
      ApartmentLease apartmentLease) {
    this.balance = value;
    this.rentPrice = price;
    this.rentDays = duration;
    this.rentSource = source;
    this.manualRent = manual;
    this.days.putAll(history);
    this.apartmentLease = apartmentLease;
    this.lifetime = totals(LocalDate.MIN, LocalDate.MAX);
    this.recordingSince = this.days.isEmpty() ? null : this.days.firstKey();
    for (var entry : history.entrySet()) {
      this.months.merge(YearMonth.from(entry.getKey()), entry.getValue(), StatsLedger::plus);
    }
  }

  void restoreDetails(
      Map<YearMonth, DayTotals> months, Map<LocalDateTime, DayTotals> hours, LocalDateTime since) {
    for (var entry : this.months.entrySet()) {
      if (!covers(months.getOrDefault(entry.getKey(), DayTotals.ZERO), entry.getValue())) {
        throw new IllegalArgumentException("Monthly totals smaller than daily history");
      }
    }
    DayTotals sum = DayTotals.ZERO;
    for (DayTotals value : months.values()) {
      if (!covers(this.lifetime, value)) {
        throw new IllegalArgumentException("Invalid monthly totals");
      }
      sum = plus(sum, value);
    }
    if (!covers(this.lifetime, sum)) {
      throw new IllegalArgumentException("Monthly totals exceed lifetime");
    }
    var hourlyByDay = new TreeMap<LocalDate, DayTotals>();
    for (var entry : hours.entrySet()) {
      if (entry.getKey().getMinute() != 0
          || entry.getKey().getSecond() != 0
          || entry.getKey().getNano() != 0
          || since == null
          || entry.getKey().isBefore(since)) {
        throw new IllegalArgumentException("Invalid hour");
      }
      hourlyByDay.merge(entry.getKey().toLocalDate(), entry.getValue(), StatsLedger::plus);
    }
    for (var entry : hourlyByDay.entrySet()) {
      if (!covers(this.days.getOrDefault(entry.getKey(), DayTotals.ZERO), entry.getValue())) {
        throw new IllegalArgumentException("Hourly totals exceed daily history");
      }
    }
    this.months.clear();
    this.months.putAll(months);
    this.hours.clear();
    this.hours.putAll(hours);
    this.hourlySince = since;
  }

  private static boolean covers(DayTotals total, DayTotals part) {
    return part.income().signum() >= 0
        && part.net().compareTo(part.income()) <= 0
        && total.income().compareTo(part.income()) >= 0
        && total.expenses().compareTo(part.expenses()) >= 0;
  }

  void restoreLifetime(DayTotals lifetime, LocalDate since) {
    DayTotals retained = totals(LocalDate.MIN, LocalDate.MAX);
    if (lifetime.income().compareTo(retained.income()) < 0
        || lifetime.expenses().compareTo(retained.expenses()) < 0
        || (since != null && !this.days.isEmpty() && since.isAfter(this.days.firstKey()))) {
      throw new IllegalArgumentException("Invalid lifetime totals");
    }
    this.lifetime = lifetime;
    if (since != null) {
      this.recordingSince = since;
    }
  }

  void restorePending(List<PendingReward> pending) {
    this.pendingRewards.addAll(pending);
    this.showProvisional = !pending.isEmpty();
  }

  public record PendingReward(BigDecimal amount, LocalDate day, long time) {
    public PendingReward {
      if (amount == null || amount.signum() <= 0 || day == null || time < 0) {
        throw new IllegalArgumentException("Invalid provisional reward");
      }
    }
  }

  public record DayTotals(BigDecimal income, BigDecimal net) {
    static final DayTotals ZERO = new DayTotals(BigDecimal.ZERO, BigDecimal.ZERO);

    public BigDecimal expenses() {
      return this.income.subtract(this.net).max(BigDecimal.ZERO);
    }
  }
}
