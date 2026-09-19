package myau.util;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;

/**
 * Finds the yaw whose knockback would push a target off the nearest edge. The scan is ported
 * from Grizzly Client's KnockbackDisplacement: every candidate yaw projects the target's hitbox
 * step by step along the knockback direction (stopping at walls) and probes how far it could
 * fall from each projected spot; trajectories that reach a drop of at least {@code voidDepth}
 * blocks are scored by how close the edge is, how long the drop-off run is and how deep it is.
 */
public final class VoidUtil {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final float YAW_STEP = 2.0F;
    private static final double PATH_STEP = 0.15;
    private static final double MIN_VOID_RUN = 0.45;
    private static final int MAX_PROBE_DEPTH = 64;

    private VoidUtil() {
    }

    /**
     * @param baseYaw      yaw from the player to the target; candidates are scored relative to it
     * @param maxDistance  how far from the target to trace possible knockback paths
     * @param voidDepth    how many empty blocks below the path count as a void drop
     * @param maxDeviation only consider yaws within this many degrees of {@code baseYaw}
     * @return the best yaw, or NaN when no knockback direction ends over a void
     */
    public static float findVoidYaw(EntityLivingBase target, float baseYaw, double maxDistance, int voidDepth, float maxDeviation) {
        return findVoidYaw(target, baseYaw, maxDistance, voidDepth, -maxDeviation, maxDeviation);
    }

    /**
     * Same, with an asymmetric window: only yaws with {@code minDeviation <= yaw - baseYaw <=
     * maxDeviation} are considered (e.g. the span that still hits the target's hitbox).
     */
    public static float findVoidYaw(EntityLivingBase target, float baseYaw, double maxDistance, int voidDepth, float minDeviation, float maxDeviation) {
        if (mc.theWorld == null || target == null || minDeviation > maxDeviation) {
            return Float.NaN;
        }
        float bestYaw = Float.NaN;
        double bestScore = Double.NEGATIVE_INFINITY;
        int steps = Math.round(360.0F / YAW_STEP);
        for (int step = 0; step < steps; step++) {
            float yaw = -180.0F + step * YAW_STEP;
            float signedDeviation = MathHelper.wrapAngleTo180_float(yaw - baseYaw);
            if (signedDeviation < minDeviation || signedDeviation > maxDeviation) {
                continue;
            }
            float deviation = Math.abs(signedDeviation);
            double score = scoreTrajectory(target, yaw, deviation, maxDistance, voidDepth);
            if (!Double.isNaN(score) && score > bestScore) {
                bestScore = score;
                bestYaw = yaw;
            }
        }
        return bestYaw;
    }

    /**
     * Distance along {@code yaw} until the target's projected hitbox is over a void drop, or NaN.
     */
    public static double edgeDistance(EntityLivingBase target, float yaw, double maxDistance, int voidDepth) {
        double radians = Math.toRadians(yaw);
        double directionX = -Math.sin(radians);
        double directionZ = Math.cos(radians);
        int probeDepth = Math.min(voidDepth + 12, MAX_PROBE_DEPTH);
        for (double distance = PATH_STEP; distance <= maxDistance + 1.0e-6; distance += PATH_STEP) {
            AxisAlignedBB projected = target.getEntityBoundingBox().offset(directionX * distance, 0.0, directionZ * distance);
            if (!mc.theWorld.getCollisionBoxes(projected.contract(0.03, 0.03, 0.03)).isEmpty()) {
                return Double.NaN;
            }
            if (projectedDrop(projected, probeDepth) >= voidDepth) {
                return distance;
            }
        }
        return Double.NaN;
    }

    private static double scoreTrajectory(EntityLivingBase target, float yaw, float deviation, double maxDistance, int voidDepth) {
        double radians = Math.toRadians(yaw);
        double directionX = -Math.sin(radians);
        double directionZ = Math.cos(radians);
        int probeDepth = Math.min(voidDepth + 12, MAX_PROBE_DEPTH);

        double firstVoidDistance = Double.NaN;
        int voidSamples = 0;
        double totalDepth = 0.0;

        for (double distance = PATH_STEP; distance <= maxDistance + 1.0e-6; distance += PATH_STEP) {
            AxisAlignedBB projected = target.getEntityBoundingBox().offset(directionX * distance, 0.0, directionZ * distance);
            // Knockback can't carry the target through a wall.
            if (!mc.theWorld.getCollisionBoxes(projected.contract(0.03, 0.03, 0.03)).isEmpty()) {
                break;
            }
            int drop = projectedDrop(projected, probeDepth);
            if (drop >= voidDepth) {
                if (Double.isNaN(firstVoidDistance)) {
                    firstVoidDistance = distance;
                }
                voidSamples++;
                totalDepth += drop;
            } else if (!Double.isNaN(firstVoidDistance)) {
                break;
            }
        }

        if (Double.isNaN(firstVoidDistance)) {
            return Double.NaN;
        }
        double voidRun = voidSamples * PATH_STEP;
        if (voidRun < MIN_VOID_RUN) {
            return Double.NaN;
        }
        double averageDepth = totalDepth / voidSamples;
        return 4.0 / (firstVoidDistance + 0.25)
                + voidRun * 2.0
                + averageDepth * 0.05
                - deviation * 0.001;
    }

    /**
     * How many blocks the target would fall from this position before landing on something.
     */
    private static int projectedDrop(AxisAlignedBB projected, int maxDepth) {
        double inset = Math.min(0.08, Math.min((projected.maxX - projected.minX) * 0.15, (projected.maxZ - projected.minZ) * 0.15));
        AxisAlignedBB supportProbe = new AxisAlignedBB(
                projected.minX + inset,
                projected.minY - 0.08,
                projected.minZ + inset,
                projected.maxX - inset,
                projected.minY + 0.01,
                projected.maxZ - inset
        );
        for (int depth = 0; depth < maxDepth; depth++) {
            if (!mc.theWorld.getCollisionBoxes(supportProbe.offset(0.0, -depth, 0.0)).isEmpty()) {
                return depth;
            }
        }
        return maxDepth;
    }
}
