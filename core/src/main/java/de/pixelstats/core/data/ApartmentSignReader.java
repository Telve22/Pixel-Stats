package de.pixelstats.core.data;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.labymod.api.client.blockentity.BlockEntity;
import net.labymod.api.client.blockentity.SignBlockEntity;
import net.labymod.api.client.blockentity.SignBlockEntity.SignSide;
import net.labymod.api.client.world.ClientWorld;
import net.labymod.api.client.world.block.BlockPosition;
import net.labymod.api.client.world.phys.hit.BlockHitResult;

/** Reads only the clicked sign's loaded chunk, using the version-independent API. */
public final class ApartmentSignReader {

  private ApartmentSignReader() {}

  public static Optional<Position> position(BlockHitResult hit) {
    var vector = hit.getBlockPosition();
    if (vector == null
        || !Float.isFinite(vector.getX())
        || !Float.isFinite(vector.getY())
        || !Float.isFinite(vector.getZ())) {
      return Optional.empty();
    }
    return Optional.of(
        new Position(
            (int) Math.floor(vector.getX()),
            (int) Math.floor(vector.getY()),
            (int) Math.floor(vector.getZ())));
  }

  public static Optional<Reading> read(
      ClientWorld world, Position position, String playerName, long now) {
    if (world == null) {
      return Optional.empty();
    }
    var chunk = world.getChunk(position.x() >> 4, position.z() >> 4);
    if (chunk == null || !chunk.isLoaded()) {
      return Optional.empty();
    }
    for (BlockEntity entity : chunk.getBlockEntities()) {
      if (!(entity instanceof SignBlockEntity sign)
          || sign.isRemoved()
          || !position.matches(sign.position())) {
        continue;
      }
      Reading reading = null;
      for (SignSide side : SignSide.values()) {
        if (!sign.hasSide(side)) {
          continue;
        }
        List<String> lines = PixelTextParser.cleanLines(Arrays.asList(sign.getStringLines(side)));
        var lease = ApartmentSignParser.owned(lines, playerName, now);
        if (lease.isEmpty()) {
          continue;
        }
        if (reading != null && !reading.lease().equals(lease.get())) {
          return Optional.empty(); // Two conflicting owned sides are not an unambiguous lease.
        }
        reading = new Reading(lines, lease.get());
      }
      return Optional.ofNullable(reading);
    }
    return Optional.empty();
  }

  public record Position(int x, int y, int z) {
    public boolean matches(BlockPosition position) {
      return position != null
          && this.x == position.getX()
          && this.y == position.getY()
          && this.z == position.getZ();
    }
  }

  public record Reading(List<String> lines, ApartmentLease lease) {}
}
