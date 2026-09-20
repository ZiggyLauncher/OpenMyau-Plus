package myau.backtrack;

import net.minecraft.entity.Entity;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

/**
 * Where a target really is, as opposed to where the client is still drawing them.
 * <p>
 * While packets are held the entity in the world stops moving, so its position is the stale one.
 * Feeding every held movement packet through here keeps a running total of the position those
 * packets would have produced, which is what the module compares against to decide whether the
 * target is closing in and the hold has to be given up.
 * <p>
 * 1.8.9 sends entity movement as fixed point in units of 1/32 of a block, relative for
 * {@link S14PacketEntity} and absolute for {@link S18PacketEntityTeleport}, so the running total
 * is kept in the same terms the server uses rather than in interpolated render positions.
 */
public final class TrackedPosition {
    private static final double FIXED_POINT = 32.0;

    private Vec3 base;

    public Vec3 get() {
        return this.base;
    }

    public boolean isTracking() {
        return this.base != null;
    }

    /** Anchors the running total to where the entity is right now. */
    public void syncTo(Entity entity) {
        this.base = new Vec3(entity.posX, entity.posY, entity.posZ);
    }

    public void clear() {
        this.base = null;
    }

    /**
     * Applies {@code packet} to the running total if it moves {@code target}.
     *
     * @return the target's real position after this packet, or null when the packet does not move it
     */
    public Vec3 handle(Packet<?> packet, World world, Entity target) {
        if (this.base == null || world == null || target == null) {
            return null;
        }

        if (packet instanceof S18PacketEntityTeleport) {
            S18PacketEntityTeleport teleport = (S18PacketEntityTeleport) packet;
            if (teleport.getEntityId() != target.getEntityId()) {
                return null;
            }
            this.base = new Vec3(teleport.getX() / FIXED_POINT,
                    teleport.getY() / FIXED_POINT,
                    teleport.getZ() / FIXED_POINT);
            return this.base;
        }

        if (packet instanceof S14PacketEntity) {
            S14PacketEntity move = (S14PacketEntity) packet;
            if (move.getEntity(world) != target) {
                return null;
            }
            // The look-only and no-op subclasses carry zeroes here, so adding unconditionally is
            // the same as testing for a position first, without depending on the nested types.
            double deltaX = move.func_149062_c() / FIXED_POINT;
            double deltaY = move.func_149061_d() / FIXED_POINT;
            double deltaZ = move.func_149064_e() / FIXED_POINT;
            if (deltaX == 0.0 && deltaY == 0.0 && deltaZ == 0.0) {
                return null;
            }
            this.base = this.base.addVector(deltaX, deltaY, deltaZ);
            return this.base;
        }

        return null;
    }
}
