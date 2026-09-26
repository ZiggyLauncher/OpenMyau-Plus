package myau.rotation.mouse;

import net.minecraft.client.Minecraft;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

/**
 * The mouse arithmetic under Vape's aim: how far one mouse count turns you at the current
 * sensitivity, how many controller updates make up a tick, and the angle helpers that are written
 * in terms of those counts.
 * <p>
 * A mouse count turns the view by {@code (s * 0.6 + 0.2)^3 * 8 * 0.15} degrees for sensitivity
 * {@code s}, which is exactly how the game turns raw mouse input. Controllers are run
 * {@code 50 / scale} times a tick, so a given speed setting turns the same number of degrees a
 * tick whatever the sensitivity.
 */
public final class MouseAim {
    /** Set by tests; the game's own sensitivity otherwise. */
    public static Float sensitivityOverride;

    private MouseAim() {
    }

    public static float sensitivity() {
        if (sensitivityOverride != null) {
            return sensitivityOverride;
        }
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && mc.gameSettings != null ? mc.gameSettings.mouseSensitivity : 0.5F;
    }

    /** One mouse count, before the game's 0.15 factor. */
    public static float mouseScale() {
        float base = sensitivity() * 0.6F + 0.2F;
        return base * base * base * 8.0F;
    }

    /** One mouse count, in degrees. */
    public static float degreesPerCount() {
        return mouseScale() * 0.15F;
    }

    /** The original's {@code getSensitivityTimeScale}: the default sensitivity's scale over the current one. */
    public static float timeScale() {
        float reference = 0.5F * 0.5F * 0.5F * 8.0F;
        return reference / mouseScale();
    }

    /** Controller updates in one tick. */
    public static int updatesPerTick() {
        return Math.round(50.0F * timeScale());
    }

    public static float wrap(float angle) {
        if ((angle %= 360.0F) >= 180.0F) {
            angle -= 360.0F;
        }
        if (angle < -180.0F) {
            angle += 360.0F;
        }
        return angle;
    }

    public static double wrap(double angle) {
        if ((angle %= 360.0) >= 180.0) {
            angle -= 360.0;
        }
        if (angle < -180.0) {
            angle += 360.0;
        }
        return angle;
    }

    /**
     * The rotation from {@code eye} to {@code target}, snapped to whole mouse counts away from
     * {@code (yaw, pitch)} so a controller starting there can land on it exactly. The original's
     * {@code RotationVectorMath.d}.
     *
     * @return {yaw, pitch}
     */
    public static float[] toward(Vec3 eye, Vec3 target, float yaw, float pitch) {
        double deltaX = eye.xCoord - target.xCoord;
        double deltaY = eye.yCoord - target.yCoord;
        double deltaZ = eye.zCoord - target.zCoord;
        double targetYaw = (double) FastAtan.atan2((float) deltaX, (float) (-deltaZ)) * 57.29577951308232;
        double horizontal = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        double targetPitch = (double) FastAtan.atan2((float) deltaY, (float) horizontal) * 57.29577951308232;
        float step = (float) (0.0 + (double) mouseScale() * 0.15);
        int yawCounts = Math.round(wrap((float) (targetYaw - (double) yaw)) / step);
        int pitchCounts = Math.round(wrap((float) (targetPitch - (double) pitch)) / step);
        return new float[]{yaw + step * (float) yawCounts, pitch + step * (float) pitchCounts};
    }

    /**
     * How far {@code yaw} is from facing the point {@code (targetX, targetZ)} seen from
     * {@code (sourceX, sourceZ)}, in degrees, 0 to 180. The original's {@code RotationUtil.N},
     * including its habit of calling a point straight along an axis "yaw 0".
     */
    public static double yawDistance(double sourceX, double sourceZ, double yaw, double targetX, double targetZ) {
        double facing = 0.0;
        float offsetX = (float) (targetX - sourceX);
        float offsetZ = (float) (targetZ - sourceZ);
        if ((double) offsetZ > 0.0 && (double) offsetX > 0.0) {
            facing = Math.toDegrees(-FastAtan.atan(offsetX / offsetZ));
        } else if ((double) offsetZ > 0.0 && (double) offsetX < 0.0) {
            facing = Math.toDegrees(-FastAtan.atan(offsetX / offsetZ));
        } else if ((double) offsetZ < 0.0 && (double) offsetX > 0.0) {
            facing = -90.0 + Math.toDegrees(FastAtan.atan(offsetZ / offsetX));
        } else if ((double) offsetZ < 0.0 && (double) offsetX < 0.0) {
            facing = 90.0 + Math.toDegrees(FastAtan.atan(offsetZ / offsetX));
        }
        double difference = Math.abs(facing - yaw) % 360.0;
        return difference > 180.0 ? 360.0 - difference : difference;
    }

    /**
     * The straight-line turn, in degrees, from {@code (yaw, pitch)} to looking from {@code source}
     * at {@code target}. The original's {@code calculateRotationDistance}.
     */
    public static float rotationDistance(Vec3 source, Vec3 target, float yaw, float pitch) {
        double deltaX = source.xCoord - target.xCoord;
        double deltaZ = source.zCoord - target.zCoord;
        double deltaY = source.yCoord - target.yCoord;
        double horizontal = MathHelper.sqrt_double(deltaX * deltaX + deltaZ * deltaZ);
        float yawDelta = (float) yawDistance(source.xCoord, source.zCoord, yaw, target.xCoord, target.zCoord);
        float targetPitch = wrap((float) Math.toDegrees(FastAtan.atan2((float) deltaY, (float) horizontal)));
        float absoluteYaw = Math.abs(wrap(yawDelta));
        float absolutePitch = Math.abs(wrap(targetPitch - pitch));
        return (float) Math.sqrt(absoluteYaw * absoluteYaw + absolutePitch * absolutePitch);
    }
}
