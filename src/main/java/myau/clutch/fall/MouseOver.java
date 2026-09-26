package myau.clutch.fall;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

import java.util.List;

/**
 * What a viewer's crosshair lands on, worked out the way the game's own {@code getMouseOver}
 * does it - blocks out to block reach, then anything collidable in front of them - but for any
 * viewer, simulated or real. The original's {@code RayTraceUtil.U}.
 */
public final class MouseOver {
    private MouseOver() {
    }

    /**
     * @param viewer       whose eye and look are used; for a living viewer the look follows its
     *                     head yaw, so callers line that up with the yaw first
     * @param entityReach  beyond this an entity in the way reads as a miss rather than a hit
     * @param blockReach   the controller's block reach
     * @param ignored      the real local player, which never blocks its own line
     */
    public static MovingObjectPosition trace(World world, EntityLivingBase viewer, double entityReach,
                                             double blockReach, Entity ignored) {
        Vec3 eye = viewer.getPositionEyes(1.0F);
        Vec3 look = viewer.getLook(1.0F);
        Vec3 blockEnd = eye.addVector(look.xCoord * blockReach, look.yCoord * blockReach, look.zCoord * blockReach);
        MovingObjectPosition result = world.rayTraceBlocks(eye, blockEnd, false, false, true);
        double blockDistance = blockReach;
        boolean clampEntityReach = blockReach > entityReach;
        double searchReach = Math.max(blockReach, entityReach);
        if (result != null) {
            blockDistance = result.hitVec.distanceTo(eye);
        }
        Vec3 searchEnd = eye.addVector(look.xCoord * searchReach, look.yCoord * searchReach, look.zCoord * searchReach);
        Entity pointed = null;
        Vec3 pointedHit = null;
        double pointedDistance = blockDistance;
        List<Entity> nearby = world.getEntitiesWithinAABBExcludingEntity(viewer, viewer.getEntityBoundingBox()
                .addCoord(look.xCoord * searchReach, look.yCoord * searchReach, look.zCoord * searchReach)
                .expand(1.0, 1.0, 1.0));
        for (Entity entity : nearby) {
            if (entity == ignored || !entity.canBeCollidedWith()) {
                continue;
            }
            float border = entity.getCollisionBorderSize();
            AxisAlignedBB box = entity.getEntityBoundingBox().expand(border, border, border);
            MovingObjectPosition intercept = box.calculateIntercept(eye, searchEnd);
            if (box.isVecInside(eye)) {
                if (!(0.0 < pointedDistance) && pointedDistance != 0.0) {
                    continue;
                }
                pointed = entity;
                pointedHit = intercept == null ? eye : intercept.hitVec;
                pointedDistance = 0.0;
                continue;
            }
            if (intercept == null) {
                continue;
            }
            double distance = eye.distanceTo(intercept.hitVec);
            if (distance < pointedDistance || pointedDistance == 0.0) {
                pointed = entity;
                pointedHit = intercept.hitVec;
                pointedDistance = distance;
            }
        }
        if (pointed != null && (pointedDistance < blockDistance || result == null)) {
            result = clampEntityReach && eye.distanceTo(pointedHit) > entityReach
                    ? new MovingObjectPosition(MovingObjectPosition.MovingObjectType.MISS, pointedHit, null, new BlockPos(pointedHit))
                    : new MovingObjectPosition(pointed, pointedHit);
        }
        return result;
    }
}
