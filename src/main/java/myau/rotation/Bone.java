package myau.rotation;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

/**
 * A point on a target to aim at. The named bones are fixed offsets in the target's local space,
 * rotated by its body yaw so they follow the model as it turns; {@code MULTIPOINT} instead picks
 * whatever spot on the hitbox is nearest to where you are already looking, which is what makes
 * tracking feel like it tracks the player rather than snapping to one anchor.
 */
public enum Bone {
    MULTIPOINT(null),
    HEAD(new double[]{0.0, 1.75, 0.0}),
    BODY(new double[]{0.0, 1.125, 0.0}),
    LEFT_ARM(new double[]{0.375, 1.125, 0.0}),
    RIGHT_ARM(new double[]{-0.375, 1.125, 0.0}),
    LEFT_LEG(new double[]{0.125, 0.375, 0.0}),
    RIGHT_LEG(new double[]{-0.125, 0.375, 0.0});

    public static final Bone[] ALL = values();
    public static final String[] NAMES = {"Multipoint", "Head", "Body", "Left arm", "Right arm", "Left leg", "Right leg"};

    /** Kept off the hitbox edge so the aim point is never exactly on the surface. */
    private static final double MARGIN = 0.1;
    private static final double MARGIN_FRACTION = 0.25;
    private static final int REFINEMENTS = 2;

    private final double[] center;

    Bone(double[] center) {
        this.center = center;
    }

    /** Where this bone is, in world space, at the given render partial tick. */
    public Vec3 point(Entity viewer, EntityLivingBase target, float partialTicks) {
        double x = interpolate(target.lastTickPosX, target.posX, partialTicks);
        double y = interpolate(target.lastTickPosY, target.posY, partialTicks);
        double z = interpolate(target.lastTickPosZ, target.posZ, partialTicks);

        if (this.center == null) {
            AxisAlignedBB box = target.getEntityBoundingBox()
                    .offset(x - target.posX, y - target.posY, z - target.posZ);
            // The ray is the live look, not the interpolated one - the original uses the plain
            // look angle here. Interpolating it makes the aim point chase a rotation that is
            // itself still catching up, which reads as the assist hunting around the hitbox.
            return closest(viewer.getPositionEyes(partialTicks), viewer.getLook(1.0F), box);
        }

        float bodyYaw = rotationLerp(partialTicks, target.prevRenderYawOffset, target.renderYawOffset);
        double angle = Math.toRadians(bodyYaw);
        double sin = Math.sin(angle);
        double cos = Math.cos(angle);
        return new Vec3(
                x + this.center[0] * cos - this.center[2] * sin,
                y + this.center[1],
                z + this.center[0] * sin + this.center[2] * cos);
    }

    /**
     * The point inside {@code box} closest to the ray you are looking along. Two refinements are
     * enough: the first walks the ray out to the box centre's depth, the second corrects for the
     * clamp the first one applied.
     */
    static Vec3 closest(Vec3 eye, Vec3 look, AxisAlignedBB box) {
        double marginX = margin(box.maxX - box.minX);
        double marginY = margin(box.maxY - box.minY);
        double marginZ = margin(box.maxZ - box.minZ);
        AxisAlignedBB inner = new AxisAlignedBB(
                box.minX + marginX, box.minY + marginY, box.minZ + marginZ,
                box.maxX - marginX, box.maxY - marginY, box.maxZ - marginZ);

        double pointX = (inner.minX + inner.maxX) * 0.5;
        double pointY = (inner.minY + inner.maxY) * 0.5;
        double pointZ = (inner.minZ + inner.maxZ) * 0.5;

        for (int i = 0; i < REFINEMENTS; i++) {
            double depth = Math.max(0.0,
                    (pointX - eye.xCoord) * look.xCoord
                            + (pointY - eye.yCoord) * look.yCoord
                            + (pointZ - eye.zCoord) * look.zCoord);
            pointX = clamp(eye.xCoord + look.xCoord * depth, inner.minX, inner.maxX);
            pointY = clamp(eye.yCoord + look.yCoord * depth, inner.minY, inner.maxY);
            pointZ = clamp(eye.zCoord + look.zCoord * depth, inner.minZ, inner.maxZ);
        }
        return new Vec3(pointX, pointY, pointZ);
    }

    /** Never eats more than a quarter of a thin hitbox, so the inner box can't invert. */
    private static double margin(double size) {
        return Math.min(MARGIN, size * MARGIN_FRACTION);
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static double interpolate(double last, double current, float partialTicks) {
        return last + (current - last) * partialTicks;
    }

    /** Interpolates an angle the short way round, so a body turning past 180 does not spin. */
    private static float rotationLerp(float partialTicks, float previous, float current) {
        return previous + MathHelper.wrapAngleTo180_float(current - previous) * partialTicks;
    }
}
