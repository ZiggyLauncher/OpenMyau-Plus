package myau.rotation.mouse;

import net.minecraft.entity.player.EntityPlayer;

/**
 * Turns the view back by a set amount - for looking back where you were after a clutch turned you
 * away. It finishes on whichever comes first: arriving, or having turned as far as it set out to
 * on both axes. Ported from Vape's {@code ThresholdFixedRotationController}.
 */
public class ReturnRotator extends EntityRotator {
    private final double yawThreshold;
    private final double pitchThreshold;
    private final Runnable onComplete;

    /**
     * @param yawDelta   how far the view has turned away; the target is the current yaw minus this
     * @param onComplete run once when it finishes
     */
    public ReturnRotator(EntityPlayer player, float yawDelta, float pitchDelta, Runnable onComplete) {
        super(player.rotationYaw - yawDelta, player.rotationPitch - pitchDelta, player);
        this.yawThreshold = Math.abs(yawDelta);
        this.pitchThreshold = Math.abs(pitchDelta);
        this.onComplete = onComplete;
    }

    @Override
    public void update() {
        boolean yawThresholdReached = (double) this.getYawDistance() >= this.yawThreshold;
        boolean pitchThresholdReached = (double) this.getPitchDistance() >= this.pitchThreshold;
        boolean yawAligned = false;
        boolean pitchAligned = false;
        if (!yawThresholdReached && (yawAligned = this.updateYaw())) {
            yawThresholdReached = true;
        }
        if (!pitchThresholdReached && (pitchAligned = this.updatePitch())) {
            pitchThresholdReached = true;
        }
        if (yawAligned && pitchAligned && Math.abs(this.pendingYawDelta) < 1.0F
                && Math.abs(this.pendingPitchDelta) < 1.0F) {
            this.setComplete(true);
        }
        if (yawThresholdReached && pitchThresholdReached) {
            this.setComplete(true);
        }
    }

    @Override
    public void setComplete(boolean complete) {
        boolean wasComplete = this.isComplete();
        super.setComplete(complete);
        if (complete && !wasComplete && this.onComplete != null) {
            this.onComplete.run();
        }
    }
}
