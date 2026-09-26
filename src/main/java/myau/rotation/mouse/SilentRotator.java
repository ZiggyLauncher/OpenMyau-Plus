package myau.rotation.mouse;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.EntityLivingBase;

/**
 * A rotation turned the same way as {@link TargetRotator}, but kept to itself: it is what gets
 * reported to the server while your own view stays where it is. Ported from Vape's
 * {@code AdaptiveRotationController}, cut down to what its Clutch uses.
 * <p>
 * It runs in one of two modes. Absolute: its yaw and pitch are its own. Relative: they are an
 * offset from the reference player's view, with a target of no offset - which is how a silent aim
 * is handed back, easing the reported rotation onto the real one instead of dropping it.
 */
public class SilentRotator extends TargetRotator {
    private final EntityLivingBase reference;
    private float currentYaw;
    private float currentPitch;
    private boolean relativeMode;
    private Float referenceYawOverride;

    public SilentRotator(EntityLivingBase reference) {
        super(reference.rotationYaw, reference.rotationPitch);
        this.reference = reference;
        this.currentYaw = this.targetYaw;
        this.currentPitch = this.targetPitch;
    }

    @Override
    public void update() {
        if (this.relativeMode) {
            this.setTargetRotationKeepTarget(0.0F, 0.0F);
        }
        boolean yawComplete = this.updateYaw();
        boolean pitchComplete = this.updatePitch();
        boolean rotationComplete = yawComplete && pitchComplete
                && Math.abs(this.pendingYawDelta) < 1.0F
                && Math.abs(this.pendingPitchDelta) < 1.0F;
        if (!this.relativeMode && rotationComplete && !this.shouldRetainAfterCompletion()) {
            this.setRelativeMode(true);
        } else {
            this.setComplete(rotationComplete);
        }
    }

    private void setTargetRotationKeepTarget(float yaw, float pitch) {
        super.setTargetRotation(yaw, pitch);
    }

    @Override
    protected void applyMouseDelta(float yawDelta, float pitchDelta) {
        this.currentYaw = (float) ((double) this.currentYaw + (double) yawDelta * 0.15);
        this.currentPitch = (float) ((double) this.currentPitch - (double) pitchDelta * 0.15);
        this.currentPitch = clamp(this.currentPitch);
    }

    @Override
    public float getCurrentYaw() {
        return this.currentYaw;
    }

    @Override
    public float getCurrentPitch() {
        return this.currentPitch;
    }

    public void setCurrentYaw(float yaw) {
        this.currentYaw = yaw;
    }

    public void setCurrentPitch(float pitch) {
        this.currentPitch = pitch;
    }

    /** What is reported: the rotation itself, or in relative mode the view plus the offset. */
    public float getRenderedYaw() {
        return this.relativeMode ? this.reference.rotationYaw + this.currentYaw : this.currentYaw;
    }

    public float getRenderedPitch() {
        return clamp(this.relativeMode ? this.reference.rotationPitch + this.currentPitch : this.currentPitch);
    }

    /** The yaw movement is meant relative to: the override when set, the real view otherwise. */
    public float getReferenceYaw() {
        return this.referenceYawOverride != null ? this.referenceYawOverride : this.reference.rotationYaw;
    }

    public void setReferenceYawOverride(Float yawOverride) {
        this.referenceYawOverride = yawOverride;
    }

    public boolean isRelativeMode() {
        return this.relativeMode;
    }

    @Override
    public boolean shouldRetainAfterCompletion() {
        return !this.relativeMode;
    }

    /**
     * Switches mode, converting the rotation so the reported one does not jump. Going relative
     * also moves the real view by whole turns if that is what it takes to keep the offset inside
     * half a turn, exactly as the original does.
     */
    public void setRelativeMode(boolean relativeMode) {
        if (this.relativeMode != relativeMode) {
            if (relativeMode) {
                this.setReferenceYawOverride(null);
                float yawDifference = this.reference.rotationYaw - this.currentYaw;
                float pitchDifference = this.reference.rotationPitch - this.currentPitch;
                float offset = 0.0F;
                while (yawDifference + offset > 180.0F) {
                    offset -= 360.0F;
                }
                while (yawDifference + offset < -180.0F) {
                    offset += 360.0F;
                }
                if (offset != 0.0F) {
                    this.reference.rotationYaw += offset;
                    this.reference.rotationYawHead += offset;
                    this.reference.prevRotationYaw += offset;
                    if (this.reference instanceof EntityPlayerSP) {
                        // Or the held item swings the long way round to catch up.
                        ((EntityPlayerSP) this.reference).renderArmYaw += offset;
                    }
                }
                this.currentYaw = MouseAim.wrap(-yawDifference);
                this.currentPitch = clamp(-pitchDifference);
            } else {
                this.currentYaw += this.reference.rotationYaw;
                this.currentPitch += this.reference.rotationPitch;
                this.currentPitch = clamp(this.currentPitch);
            }
            this.setComplete(false);
        }
        this.relativeMode = relativeMode;
    }

    /** Takes over another silent rotation's state, as the rehearsal does when it forks one. */
    public void copyFrom(SilentRotator source) {
        this.currentYaw = source.currentYaw;
        this.currentPitch = source.currentPitch;
        this.targetYaw = source.targetYaw;
        this.targetPitch = source.targetPitch;
        this.pendingYawDelta = source.pendingYawDelta;
        this.pendingPitchDelta = source.pendingPitchDelta;
        this.yawDistance = source.yawDistance;
        this.pitchDistance = source.pitchDistance;
        this.setClampStepToRemaining(source.isStepClampedToRemaining());
        this.setScaleAxesProportionally(source.isAxisScalingEnabled());
        this.setAngleBasedAcceleration(source.isAngleBasedAccelerationEnabled());
        this.setLinearAcceleration(source.isLinearAccelerationEnabled());
        this.setCubicAcceleration(source.isCubicAccelerationEnabled());
        this.setRetainAfterCompletion(source.shouldRetainAfterCompletion());
        this.setComplete(source.isComplete());
        this.speed = source.speed;
        this.tolerance = source.tolerance;
        this.relativeMode = source.relativeMode;
        this.referenceYawOverride = source.referenceYawOverride;
    }

    private static float clamp(float pitch) {
        return pitch < -90.0F ? -90.0F : (pitch > 90.0F ? 90.0F : pitch);
    }
}
