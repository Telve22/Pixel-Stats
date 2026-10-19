package de.pixelstats.core.data;

/** A wall-clock estimate from an owned apartment sign, independent of the current world/session. */
public record ApartmentLease(
    String apartment, String tenant, long observedAt, long durationMillis, long precisionMillis) {

  public static final long MINUTE = 60_000;
  public static final long HOUR = 60 * MINUTE;
  public static final long DAY = 24 * HOUR;
  public static final long RENEWAL_PERIOD = 3 * DAY;
  public static final long MAX_DURATION = 365 * DAY;

  public ApartmentLease {
    if (apartment == null
        || apartment.isBlank()
        || tenant == null
        || !tenant.matches("[A-Za-z0-9_]{1,16}")
        || observedAt < 0
        || durationMillis < 0
        || durationMillis > MAX_DURATION
        || precisionMillis <= 0
        || precisionMillis > DAY) {
      throw new IllegalArgumentException("Invalid apartment lease");
    }
    Math.addExact(observedAt, durationMillis);
  }

  public long expiresAt() {
    return this.observedAt + this.durationMillis;
  }

  public long remainingMillis(long now) {
    return Math.max(0, this.expiresAt() - now);
  }

  public boolean needsRenewal(long now) {
    return this.remainingMillis(now) < DAY;
  }

  /**
   * Merges owned-sign observations without resetting a rounded countdown on each click. LabyWorld
   * adds three days when renewing; a matching jump extends the saved deadline, retaining its
   * original uncertainty. observedAt then records detection, not an authenticated server
   * transaction time. Small rounding changes retain the old anchor.
   */
  public ApartmentLease reconcile(ApartmentLease reading) {
    if (!this.apartment.equalsIgnoreCase(reading.apartment)
        || !this.tenant.equalsIgnoreCase(reading.tenant)) {
      return reading;
    }
    if (reading.observedAt < this.observedAt) {
      return this;
    }
    if (reading.durationMillis == 0 && reading.precisionMillis == MINUTE) {
      return this.remainingMillis(reading.observedAt) == 0 ? this : reading;
    }
    // A valid owned lease after the saved one expired may be a new rental or a
    // renewal missed while offline. Its current sign supplies the new estimate.
    if (this.remainingMillis(reading.observedAt) == 0 && reading.durationMillis > 0) {
      return reading;
    }
    long tolerance = Math.max(this.precisionMillis, reading.precisionMillis);
    long difference = reading.expiresAt() - this.expiresAt();
    if (difference < -tolerance) {
      return reading;
    }
    if (difference <= tolerance) {
      return this;
    }

    long periods = difference / RENEWAL_PERIOD;
    if (difference % RENEWAL_PERIOD >= RENEWAL_PERIOD / 2) {
      periods++;
    }
    if (periods < 1 || periods > MAX_DURATION / RENEWAL_PERIOD) {
      return this;
    }
    long extension = periods * RENEWAL_PERIOD;
    if (Math.abs(difference - extension) > tolerance) {
      return this;
    }
    try {
      long deadline = Math.addExact(this.expiresAt(), extension);
      long remaining = Math.max(0, deadline - reading.observedAt);
      if (remaining > MAX_DURATION) {
        return reading;
      }
      return new ApartmentLease(
          reading.apartment, reading.tenant, reading.observedAt, remaining, tolerance);
    } catch (ArithmeticException exception) {
      return this;
    }
  }

  public String remainingText(long now, String expiredText) {
    long remaining = this.remainingMillis(now);
    if (remaining == 0) {
      return expiredText;
    }
    if (remaining >= DAY) {
      // Round coarse sign hours upwards, so "1d 13h" doesn't immediately jump to "1d 12h".
      long hours = (remaining + HOUR - 1) / HOUR;
      return "≈ " + hours / 24 + "d " + hours % 24 + "h";
    }
    long hours = remaining / HOUR;
    if (hours == 0) {
      return "≈ <1h";
    }
    return "≈ " + hours + "h";
  }
}
