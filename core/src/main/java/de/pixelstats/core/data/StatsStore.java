package de.pixelstats.core.data;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;

public final class StatsStore {

  private final Path directory;

  public StatsStore(Path directory) {
    this.directory = directory;
  }

  public StatsLedger load(UUID account) throws IOException {
    Path file = file(account);
    StatsLedger ledger = new StatsLedger();
    if (!Files.exists(file)) {
      return ledger;
    }
    try {
      Properties data = new Properties();
      try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        data.load(reader);
      }
      int format = Integer.parseInt(data.getProperty("version", "1"));
      if (format < 1 || format > 5) {
        throw new IllegalArgumentException("Unsupported history format: " + format);
      }
      Map<LocalDate, StatsLedger.DayTotals> history = new TreeMap<>();
      for (String key : data.stringPropertyNames()) {
        if (!key.startsWith("income.")) {
          continue;
        }
        String date = key.substring("income.".length());
        history.put(
            LocalDate.parse(date),
            new StatsLedger.DayTotals(
                positive(data.getProperty(key)),
                new BigDecimal(data.getProperty("net." + date, "0"))));
      }
      BigDecimal balance = optionalPositive(data.getProperty("balance"));
      BigDecimal rent = optionalPositive(data.getProperty("rent"));
      int duration = Integer.parseInt(data.getProperty("rentDays", "3"));
      if (duration <= 0 || duration > 365) {
        throw new IllegalArgumentException("Invalid period");
      }
      ApartmentLease lease = null;
      if (data.containsKey("apartmentObservedAt")) {
        lease =
            new ApartmentLease(
                data.getProperty("apartmentName"),
                data.getProperty("apartmentTenant"),
                Long.parseLong(data.getProperty("apartmentObservedAt")),
                Long.parseLong(data.getProperty("apartmentDurationMillis")),
                Long.parseLong(data.getProperty("apartmentPrecisionMillis")));
      }
      ledger.restore(
          balance,
          rent,
          duration,
          data.getProperty("rentSource", ""),
          Boolean.parseBoolean(data.getProperty("manualRent", "false")),
          history,
          lease);
      if (data.containsKey("lifetime.income") || data.containsKey("lifetime.net")) {
        ledger.restoreLifetime(
            new StatsLedger.DayTotals(
                positive(data.getProperty("lifetime.income")),
                new BigDecimal(data.getProperty("lifetime.net"))),
            data.containsKey("recordingSince")
                ? LocalDate.parse(data.getProperty("recordingSince"))
                : null);
      }
      if (format >= 5) {
        Map<YearMonth, StatsLedger.DayTotals> months = new TreeMap<>();
        Map<LocalDateTime, StatsLedger.DayTotals> hours = new TreeMap<>();
        for (String key : data.stringPropertyNames()) {
          if (key.startsWith("month.income.")) {
            String month = key.substring("month.income.".length());
            months.put(
                YearMonth.parse(month),
                new StatsLedger.DayTotals(
                    positive(data.getProperty(key)),
                    new BigDecimal(data.getProperty("month.net." + month, "0"))));
          } else if (key.startsWith("hour.income.")) {
            String hour = key.substring("hour.income.".length());
            hours.put(
                LocalDateTime.parse(hour),
                new StatsLedger.DayTotals(
                    positive(data.getProperty(key)),
                    new BigDecimal(data.getProperty("hour.net." + hour, "0"))));
          }
        }
        ledger.restoreDetails(
            months,
            hours,
            data.containsKey("hourlySince")
                ? LocalDateTime.parse(data.getProperty("hourlySince"))
                : null);
      }
      int pendingCount = Integer.parseInt(data.getProperty("pending.count", "0"));
      if (pendingCount < 0
          || pendingCount > StatsLedger.MAX_PENDING_REWARDS
          || (pendingCount > 0 && balance == null)) {
        throw new IllegalArgumentException("Invalid provisional reward count");
      }
      List<StatsLedger.PendingReward> pending = new ArrayList<>();
      for (int index = 0; index < pendingCount; index++) {
        String prefix = "pending." + index + ".";
        pending.add(
            new StatsLedger.PendingReward(
                positive(data.getProperty(prefix + "amount")),
                LocalDate.parse(data.getProperty(prefix + "day")),
                Long.parseLong(data.getProperty(prefix + "time"))));
      }
      ledger.restorePending(pending);
      return ledger;
    } catch (RuntimeException exception) {
      throw new IOException("Invalid Pixel Stats history: " + file, exception);
    }
  }

  public void save(UUID account, StatsSnapshot snapshot) throws IOException {
    Files.createDirectories(this.directory);
    Properties data = new Properties();
    data.setProperty("version", "5");
    data.setProperty("lifetime.income", snapshot.lifetime().income().toPlainString());
    data.setProperty("lifetime.net", snapshot.lifetime().net().toPlainString());
    if (snapshot.recordingSince() != null) {
      data.setProperty("recordingSince", snapshot.recordingSince().toString());
    }
    if (snapshot.balance() != null) {
      data.setProperty("balance", snapshot.balance().toPlainString());
    }
    if (snapshot.rentPrice() != null) {
      data.setProperty("rent", snapshot.rentPrice().toPlainString());
    }
    data.setProperty("rentDays", Integer.toString(snapshot.rentDays()));
    data.setProperty("rentSource", snapshot.rentSource());
    data.setProperty("manualRent", Boolean.toString(snapshot.manualRent()));
    if (snapshot.apartmentLease() != null) {
      ApartmentLease lease = snapshot.apartmentLease();
      data.setProperty("apartmentName", lease.apartment());
      data.setProperty("apartmentTenant", lease.tenant());
      data.setProperty("apartmentObservedAt", Long.toString(lease.observedAt()));
      data.setProperty("apartmentDurationMillis", Long.toString(lease.durationMillis()));
      data.setProperty("apartmentPrecisionMillis", Long.toString(lease.precisionMillis()));
    }
    for (var entry : snapshot.days().entrySet()) {
      data.setProperty("income." + entry.getKey(), entry.getValue().income().toPlainString());
      data.setProperty("net." + entry.getKey(), entry.getValue().net().toPlainString());
    }
    if (snapshot.hourlySince() != null) {
      data.setProperty("hourlySince", snapshot.hourlySince().toString());
    }
    for (var entry : snapshot.hours().entrySet()) {
      data.setProperty("hour.income." + entry.getKey(), entry.getValue().income().toPlainString());
      data.setProperty("hour.net." + entry.getKey(), entry.getValue().net().toPlainString());
    }
    for (var entry : snapshot.months().entrySet()) {
      data.setProperty("month.income." + entry.getKey(), entry.getValue().income().toPlainString());
      data.setProperty("month.net." + entry.getKey(), entry.getValue().net().toPlainString());
    }
    List<StatsLedger.PendingReward> pending = snapshot.pendingRewards();
    data.setProperty("pending.count", Integer.toString(pending.size()));
    for (int index = 0; index < pending.size(); index++) {
      String prefix = "pending." + index + ".";
      data.setProperty(prefix + "amount", pending.get(index).amount().toPlainString());
      data.setProperty(prefix + "day", pending.get(index).day().toString());
      data.setProperty(prefix + "time", Long.toString(pending.get(index).time()));
    }
    Path temporary = Files.createTempFile(this.directory, "pixelstats-", ".tmp");
    try {
      try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
        data.store(writer, "Pixel Stats - account history; Monday-based weeks");
      }
      try {
        Files.move(
            temporary,
            file(account),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException exception) {
        Files.move(temporary, file(account), StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  public Path file(UUID account) {
    return this.directory.resolve(account + ".properties");
  }

  private static BigDecimal optionalPositive(String value) {
    return value == null ? null : positive(value);
  }

  private static BigDecimal positive(String value) {
    BigDecimal result = new BigDecimal(value);
    if (result.signum() < 0 || result.precision() > 30 || Math.abs(result.scale()) > 10) {
      throw new IllegalArgumentException("Invalid amount");
    }
    return result;
  }
}
