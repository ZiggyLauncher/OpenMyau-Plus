package myau.rotation;

import net.minecraft.entity.Entity;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

/**
 * An immutable yaw/pitch pair, with the angle arithmetic every rotation path needs: yaw wraps
 * across the -180/180 seam, pitch clamps at straight up and straight down.
 */
public final class Rotation {
    public final float yaw;
    public final float pitch;

    public Rotation(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public static Rotation of(Entity entity) {
        return new Rotation(entity.rotationYaw, entity.rotationPitch);
    }

    /** The rotation that looks from {@code from} straight at {@code to}. */
    public static Rotation toward(Vec3 from, Vec3 to) {
        double dx = to.xCoord - from.xCoord;
        double dy = to.yCoord - from.yCoord;
        double dz = to.zCoord - from.zCoord;
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new Rotation(yaw, pitch);
    }

    /** Signed shortest yaw difference to {@code target}. */
    public float yawTo(Rotation target) {
        return MathHelper.wrapAngleTo180_float(target.yaw - this.yaw);
    }

    /** Signed pitch difference to {@code target}, which cannot wrap. */
    public float pitchTo(Rotation target) {
        return MathHelper.clamp_float(target.pitch, -90.0F, 90.0F) - this.pitch;
    }

    public float distanceTo(Rotation target) {
        float deltaYaw = this.yawTo(target);
        float deltaPitch = this.pitchTo(target);
        return (float) Math.sqrt(deltaYaw * deltaYaw + deltaPitch * deltaPitch);
    }

    public Rotation moved(float deltaYaw, float deltaPitch) {
        return new Rotation(this.yaw + deltaYaw,
                MathHelper.clamp_float(this.pitch + deltaPitch, -90.0F, 90.0F));
    }

    public static float length(float x, float y) {
        return (float) Math.sqrt(x * x + y * y);
    }

    @Override
    public String toString() {
        return "Rotation[" + this.yaw + ", " + this.pitch + "]";
    }
}
