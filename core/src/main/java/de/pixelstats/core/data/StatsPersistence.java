package de.pixelstats.core.data;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Serializes disk access on one worker. Call public methods and run the client executor exclusively
 * on the client thread. Only immutable save snapshots cross to the worker; a loaded ledger is
 * handed to the client once and is never touched by the worker again.
 */
public final class StatsPersistence {

  private static final long SAVE_INTERVAL = 5_000;
  private static final long RETRY_INTERVAL = 60_000;

  private final StatsStore store;
  private final ExecutorService worker;
  private final Executor client;
  private final LongSupplier clock;
  private final Consumer<Failure> errors;
  // Retain visited accounts so switching back cannot reload an older, still-pending disk write.
  private final Map<UUID, AccountState> accounts = new HashMap<>();
  private AccountState selected;
  private Consumer<StatsLedger> onReady;
  private boolean closed;

  public StatsPersistence(StatsStore store, Executor client, Consumer<Failure> errors) {
    this(store, newWorker(), client, System::currentTimeMillis, errors);
    registerShutdownHook(this.worker);
  }

  StatsPersistence(
      StatsStore store,
      ExecutorService worker,
      Executor client,
      LongSupplier clock,
      Consumer<Failure> errors) {
    this.store = store;
    this.worker = worker;
    this.client = client;
    this.clock = clock;
    this.errors = errors;
  }

  private static ExecutorService newWorker() {
    return Executors.newSingleThreadExecutor(
        task -> {
          Thread thread = new Thread(task, "pixelstats-storage");
          thread.setDaemon(true);
          return thread;
        });
  }

  /**
   * Minecraft's PRE shutdown listener queues the final snapshots without waiting on the client
   * thread. This JVM hook only drains already-queued work, with a bounded wait on its own thread so
   * System.exit cannot interrupt an otherwise healthy final write. It never accesses game state.
   */
  private static void registerShutdownHook(ExecutorService worker) {
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  worker.shutdown();
                  try {
                    worker.awaitTermination(5, TimeUnit.SECONDS);
                  } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                  }
                },
                "pixelstats-storage-shutdown"));
  }

  /**
   * Selects cached history or starts a load; completion runs through the supplied client executor.
   */
  public void select(UUID account, Consumer<StatsLedger> onReady) {
    if (this.closed) {
      return;
    }
    AccountState state = this.accounts.get(account);
    boolean load = state == null;
    if (load) {
      state = new AccountState(account);
      this.accounts.put(account, state);
    }
    this.selected = state;
    this.onReady = onReady;
    if (load) {
      AccountState requested = state;
      this.worker.execute(() -> load(requested));
    } else if (state.ledger != null) {
      onReady.accept(state.ledger);
    }
  }

  /** Cancels activation of a pending load without discarding unsaved account data. */
  public void deactivate() {
    this.selected = null;
    this.onReady = null;
  }

  private void load(AccountState state) {
    StatsLedger loaded;
    IOException failure = null;
    try {
      loaded = this.store.load(state.account);
    } catch (IOException exception) {
      loaded = new StatsLedger();
      failure = exception;
    }
    StatsLedger result = loaded;
    IOException error = failure;
    this.client.execute(() -> loaded(state, result, error));
  }

  private void loaded(AccountState state, StatsLedger ledger, IOException error) {
    if (this.closed) {
      return;
    }
    state.ledger = ledger;
    state.writable = error == null;
    if (error != null) {
      this.errors.accept(new Failure(Operation.LOAD, state.account, error));
    }
    if (this.selected == state) {
      this.onReady.accept(ledger);
    }
  }

  /** Coalesces saves; a forced flush also saves edits made during an in-flight write. */
  public void flush(boolean force) {
    if (this.closed) {
      return;
    }
    long now = this.clock.getAsLong();
    for (AccountState state : this.accounts.values()) {
      if (!canSave(state)) {
        continue;
      }
      if (state.writing) {
        state.flushRequested |= force;
      } else if (force || now >= state.nextSaveAt) {
        save(state);
      }
    }
  }

  private static boolean canSave(AccountState state) {
    return state.ledger != null && state.writable && state.ledger.dirty();
  }

  private void save(AccountState state) {
    StatsSnapshot snapshot = state.ledger.snapshot();
    state.writing = true;
    state.flushRequested = false;
    state.nextSaveAt = this.clock.getAsLong() + SAVE_INTERVAL;
    this.worker.execute(
        () -> {
          IOException error = write(state.account, snapshot);
          this.client.execute(() -> saved(state, snapshot.revision(), error));
        });
  }

  private IOException write(UUID account, StatsSnapshot snapshot) {
    try {
      this.store.save(account, snapshot);
      return null;
    } catch (IOException exception) {
      return exception;
    }
  }

  private void saved(AccountState state, long revision, IOException error) {
    if (this.closed) {
      return;
    }
    state.writing = false;
    boolean flushAgain = state.flushRequested;
    state.flushRequested = false;
    if (error != null) {
      state.nextSaveAt = this.clock.getAsLong() + RETRY_INTERVAL;
      this.errors.accept(new Failure(Operation.SAVE, state.account, error));
      return;
    }
    state.ledger.markSaved(revision);
    if (flushAgain && state.ledger.dirty()) {
      save(state);
    }
  }

  /**
   * Queues the latest snapshots before shutting down the worker. Final writes must not depend on
   * client callbacks, since ticks may already have stopped. FIFO execution keeps them after earlier
   * saves of the same account. This method never waits for disk I/O.
   */
  public void shutdown() {
    if (this.closed) {
      return;
    }
    this.closed = true;
    deactivate();
    for (AccountState state : this.accounts.values()) {
      if (canSave(state)) {
        StatsSnapshot snapshot = state.ledger.snapshot();
        this.worker.execute(
            () -> {
              IOException error = write(state.account, snapshot);
              if (error != null) {
                this.client.execute(
                    () -> this.errors.accept(new Failure(Operation.SAVE, state.account, error)));
              }
            });
      }
    }
    this.worker.shutdown();
  }

  public enum Operation {
    LOAD,
    SAVE
  }

  public record Failure(Operation operation, UUID account, IOException cause) {}

  private static final class AccountState {
    private final UUID account;
    private StatsLedger ledger;
    private boolean writable;
    private boolean writing;
    private boolean flushRequested;
    private long nextSaveAt;

    private AccountState(UUID account) {
      this.account = account;
    }
  }
}
