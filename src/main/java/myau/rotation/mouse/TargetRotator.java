package myau.rotation.mouse;

/**
 * Turns toward a fixed yaw and pitch. Ported from Vape's {@code FixedRotationController}.
 * <p>
 * Each update looks at where the view will be once the counts already pending are applied, and
 * adds {@code speed / 4} counts toward the target on each axis - scaled down on the shorter axis
 * when proportional scaling is on, so both axes arrive together, and clamped to what is left when
 * step clamping is on, so it never overshoots. Stepping stops once the error is within the
 * tolerance, measured in whole counts.
 */
public abstract class TargetRotator extends MouseRotator {
    public static final float UNSET = -999.0F;

    public float targetYaw;
    public float targetPitch;
    private boolean clampStepToRemaining;
    private boolean scaleAxesProportionally;
    private boolean cubicAcceleration;
    private boolean linearAcceleration;
    private boolean angleBasedAcceleration;

    protected TargetRotator(float yaw, float pitch) {
        this.targetYaw = yaw;
        this.targetPitch = pitch;
    }

    public abstract float getCurrentYaw();

    public abstract float getCurrentPitch();

    @Override
    public boolean updateYaw() {
        if (this.targetYaw == UNSET) {
            return true;
        }
        float mouseScale = MouseAim.mouseScale();
        float predictedYaw = this.getCurrentYaw() + (float) (int) this.pendingYawDelta * mouseScale * 0.15F;
        float predictedPitch = this.getCurrentPitch() - (float) (int) (-this.pendingPitchDelta) * mouseScale * 0.15F;
        double yawError = MouseAim.wrap((this.targetYaw - predictedYaw) % 360.0F);
        double pitchError = MouseAim.wrap((this.targetPitch - predictedPitch) % 360.0F);
        double absoluteYawError = Math.abs(yawError);
        if (!this.isOutsideTolerance(absoluteYawError)) {
            return true;
        }
        double step = this.getSpeed() * 0.25;
        double axisRatio = absoluteYawError / Math.abs(pitchError);
        if (this.scaleAxesProportionally && axisRatio < 1.0) {
            step *= axisRatio;
        }
        step = this.applyYawAcceleration(step, absoluteYawError);
        this.pendingYawDelta = this.addPendingStep(this.pendingYawDelta, yawError, step);
        return false;
    }

    @Override
    public boolean updatePitch() {
        if (this.targetPitch == UNSET) {
            return true;
        }
        float currentPitch = this.getCurrentPitch() == -90.0F ? -89.99F : this.getCurrentPitch();
        float mouseScale = MouseAim.mouseScale();
        float predictedYaw = this.getCurrentYaw() + (float) (int) this.pendingYawDelta * mouseScale * 0.15F;
        float predictedPitch = currentPitch - (float) (int) (-this.pendingPitchDelta) * mouseScale * 0.15F;
        double yawError = MouseAim.wrap((this.targetYaw - predictedYaw) % 360.0F);
        double pitchError = MouseAim.wrap((this.targetPitch - predictedPitch) % 360.0F);
        double absolutePitchError = Math.abs(pitchError);
        if (!this.isOutsideTolerance(absolutePitchError)) {
            return true;
        }
        double step = this.getSpeed() * 0.25;
        double axisRatio = absolutePitchError / Math.abs(yawError);
        if (this.scaleAxesProportionally && axisRatio < 1.0) {
            step *= axisRatio;
        }
        step = this.applyPitchAcceleration(step, absolutePitchError);
        this.pendingPitchDelta = this.addPendingStep(this.pendingPitchDelta, pitchError, step);
        return false;
    }

    protected boolean isOutsideTolerance(double absoluteError) {
        float rotationPerStep = MouseAim.mouseScale() * 0.15F;
        return Math.round(absoluteError / (double) rotationPerStep)
                > Math.max((long) Math.round(this.tolerance / rotationPerStep), 0L);
    }

    protected double applyYawAcceleration(double step, double absoluteError) {
        if (this.angleBasedAcceleration) {
            return step * (225.0 + absoluteError) / 180.0;
        }
        if (this.linearAcceleration) {
            return step + absoluteError * 0.05;
        }
        if (!this.cubicAcceleration) {
            return step;
        }
        double shifted = absoluteError / 100.0 + 0.7;
        double multiplier = 0.4 + 2.0 * Math.pow(shifted, 3.0) + Math.pow(shifted, 2.0);
        return step * Math.min(Math.max(1.0, multiplier), 4.0);
    }

    protected double applyPitchAcceleration(double step, double absoluteError) {
        if (this.angleBasedAcceleration) {
            return step * (135.0 + absoluteError) / 90.0;
        }
        if (this.linearAcceleration) {
            return step + absoluteError * 0.05;
        }
        if (!this.cubicAcceleration) {
            return step;
        }
        double shifted = absoluteError / 75.0 + 0.7;
        double multiplier = 0.4 + 2.0 * Math.pow(shifted, 3.0) + Math.pow(shifted, 2.0);
        return step * Math.max(1.0, multiplier);
    }

    protected float addPendingStep(float pendingDelta, double error, double step) {
        if (!this.clampStepToRemaining) {
            return (float) (error > 0.0 ? (double) pendingDelta + step : (double) pendingDelta - step);
        }
        double remainingSteps = Math.abs(error / (double) (MouseAim.mouseScale() * 0.15F));
        double appliedStep = Math.min(step, remainingSteps);
        return (float) (error > 0.0 ? (double) pendingDelta + appliedStep : (double) pendingDelta - appliedStep);
    }

    public void setTargetRotation(float yaw, float pitch) {
        this.targetYaw = yaw;
        this.targetPitch = pitch;
        this.setComplete(false);
    }

    public float getTargetYaw() {
        return this.targetYaw;
    }

    public float getTargetPitch() {
        return this.targetPitch;
    }

    public void setClampStepToRemaining(boolean enabled) {
        this.clampStepToRemaining = enabled;
    }

    public boolean isStepClampedToRemaining() {
        return this.clampStepToRemaining;
    }

    public void setScaleAxesProportionally(boolean enabled) {
        this.scaleAxesProportionally = enabled;
    }

    public boolean isAxisScalingEnabled() {
        return this.scaleAxesProportionally;
    }

    public void setCubicAcceleration(boolean enabled) {
        this.cubicAcceleration = enabled;
    }

    public boolean isCubicAccelerationEnabled() {
        return this.cubicAcceleration;
    }

    public void setLinearAcceleration(boolean enabled) {
        this.linearAcceleration = enabled;
    }

    public boolean isLinearAccelerationEnabled() {
        return this.linearAcceleration;
    }

    public void setAngleBasedAcceleration(boolean enabled) {
        this.angleBasedAcceleration = enabled;
    }

    public boolean isAngleBasedAccelerationEnabled() {
        return this.angleBasedAcceleration;
    }
}
