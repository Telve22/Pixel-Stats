package de.pixelstats.core.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Reconciles explicit chat rewards with authoritative scoreboard updates in either order. */
public final class PixelBalanceTracker {

  private static final long CHAT_MATCH_WINDOW = 5_000;
  private static final long SCOREBOARD_SETTLE_TIME = 2_000;
  private final StatsLedger ledger;
  private final Deque<Credit> recentScoreboardIncome = new ArrayDeque<>();
  private final Set<UUID> processedMessages = new LinkedHashSet<>();
  private BigDecimal scoreboardBalance;
  private boolean scoreboardVisible;
  private long stableSince;
  private long lastRewardAt;

  public PixelBalanceTracker(StatsLedger ledger) {
    this.ledger = ledger;
  }

  public void reward(BigDecimal amount, LocalDate day, long time, UUID messageId) {
    if (amount.signum() <= 0) {
      throw new IllegalArgumentException("Nonpositive reward");
    }
    if (this.ledger.confirmedBalance() == null) {
      return;
    }
    // Deduplicate the same event, never the text: identical payouts can be legitimate.
    if (messageId != null && !this.processedMessages.add(messageId)) {
      return;
    }
    if (this.processedMessages.size() > 256) {
      this.processedMessages.remove(this.processedMessages.iterator().next());
    }
    this.recentScoreboardIncome.removeIf(credit -> time - credit.time() > CHAT_MATCH_WINDOW);
    BigDecimal remaining = consume(this.recentScoreboardIncome, amount);
    if (remaining.signum() > 0) {
      this.ledger.stageReward(remaining, day, time);
      this.lastRewardAt = time;
    }
  }

  public void observe(BigDecimal value, LocalDate day, long time) {
    if (value.signum() < 0) {
      throw new IllegalArgumentException("Negative balance");
    }
    if (this.scoreboardBalance == null) {
      BigDecimal openingBalance = this.ledger.confirmedBalance();
      this.ledger.endSession();
      // Saved pending claims may be matched, but unrelated offline gains are excluded.
      if (openingBalance != null && value.compareTo(openingBalance) > 0) {
        this.ledger.confirmPending(value.subtract(openingBalance));
      }
      this.ledger.observe(value, day, time);
      this.stableSince = time;
    } else {
      BigDecimal delta = value.subtract(this.scoreboardBalance);
      if (!this.scoreboardVisible || delta.signum() != 0) {
        this.stableSince = time;
      }
      if (delta.signum() > 0) {
        BigDecimal unmatched = this.ledger.confirmPending(delta);
        if (unmatched.signum() > 0) {
          this.recentScoreboardIncome.addLast(new Credit(unmatched, time));
        }
      }
      this.ledger.observe(value, day, time);
      if (time - Math.max(this.stableSince, this.lastRewardAt) >= SCOREBOARD_SETTLE_TIME) {
        this.ledger.discardPending();
      }
    }
    this.ledger.showProvisional(false);
    this.scoreboardBalance = value;
    this.scoreboardVisible = true;
    this.recentScoreboardIncome.removeIf(credit -> time - credit.time() > CHAT_MATCH_WINDOW);
  }

  /** A minigame/world change keeps both the balance and the session baseline. */
  public void scoreboardMissing() {
    this.scoreboardVisible = false;
    this.ledger.showProvisional(true);
  }

  public boolean scoreboardVisible() {
    return this.scoreboardVisible;
  }

  public void endSession() {
    this.ledger.endSession();
    this.ledger.showProvisional(false);
    this.scoreboardBalance = null;
    this.scoreboardVisible = false;
    this.recentScoreboardIncome.clear();
    this.processedMessages.clear();
    this.stableSince = 0;
    this.lastRewardAt = 0;
  }

  private static BigDecimal consume(Deque<Credit> credits, BigDecimal amount) {
    BigDecimal remaining = amount;
    while (remaining.signum() > 0 && !credits.isEmpty()) {
      Credit credit = credits.removeFirst();
      BigDecimal matched = remaining.min(credit.amount());
      remaining = remaining.subtract(matched);
      BigDecimal unused = credit.amount().subtract(matched);
      if (unused.signum() > 0) {
        credits.addFirst(new Credit(unused, credit.time()));
      }
    }
    return remaining;
  }

  private record Credit(BigDecimal amount, long time) {}
}
