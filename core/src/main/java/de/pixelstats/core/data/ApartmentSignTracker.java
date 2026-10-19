package de.pixelstats.core.data;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import net.labymod.api.client.blockentity.BlockEntity;
import net.labymod.api.client.world.ClientWorld;

/** Defers event reads to the game tick and follows updates of the last clicked owned sign. */
public final class ApartmentSignTracker {

  private final ConcurrentLinkedQueue<Click> clicks = new ConcurrentLinkedQueue<>();
  private final AtomicBoolean refresh = new AtomicBoolean();
  private volatile Watch watch;

  public void clicked(
      ClientWorld world, ApartmentSignReader.Position position, UUID account, String playerName) {
    if (world != null && account != null && playerName != null) {
      this.clicks.add(new Click(world, position, account, playerName));
    }
  }

  public void updated(BlockEntity entity) {
    Watch current = this.watch;
    if (current != null && current.click().position().matches(entity.position())) {
      this.refresh.set(true);
    }
  }

  public Optional<ApartmentLease> poll(
      ClientWorld world, UUID account, String playerName, long now) {
    if (this.watch != null && !this.watch.click().matches(world, account, playerName)) {
      this.watch = null;
    }
    ApartmentLease observed = null;
    Click click;
    while ((click = this.clicks.poll()) != null) {
      if (!click.matches(world, account, playerName)) {
        continue;
      }
      var reading = ApartmentSignReader.read(world, click.position(), playerName, now);
      if (reading.isPresent()) {
        this.watch = new Watch(click, reading.get().lines(), now + 15_000);
        observed = reading.get().lease();
      }
    }
    boolean changed = this.refresh.getAndSet(false);
    Watch current = this.watch;
    if (current != null && (changed || now <= current.pollUntil())) {
      var reading = ApartmentSignReader.read(world, current.click().position(), playerName, now);
      if (reading.isPresent() && !reading.get().lines().equals(current.lines())) {
        this.watch = new Watch(current.click(), reading.get().lines(), current.pollUntil());
        observed = reading.get().lease();
      }
    }
    return Optional.ofNullable(observed);
  }

  public List<String> lines() {
    Watch current = this.watch;
    return current == null ? List.of() : current.lines();
  }

  public void clear() {
    this.watch = null;
    this.clicks.clear();
    this.refresh.set(false);
  }

  private record Click(
      ClientWorld world, ApartmentSignReader.Position position, UUID account, String playerName) {
    boolean matches(ClientWorld world, UUID account, String playerName) {
      return this.world == world
          && this.account.equals(account)
          && this.playerName.equalsIgnoreCase(playerName);
    }
  }

  private record Watch(Click click, List<String> lines, long pollUntil) {}
}
