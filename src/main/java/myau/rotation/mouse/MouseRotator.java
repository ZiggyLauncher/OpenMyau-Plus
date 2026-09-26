package myau.rotation.mouse;

import java.util.Random;

/**
 * A rotation turned the way a hand turns a mouse: whole mouse counts, a few per update, with
 * about fifty updates to a tick. Ported from Vape's {@code MouseRotationController}.
 * <p>
 * Each update adds a fractional number of counts to a pending total; applying the pending movement
 * turns by its whole part and keeps the fraction for next time. Because every turn is a whole
 * number of counts, the result sits on exactly the angles a real mouse can reach.
 */
public abstract class MouseRotator {
    public float tolerance = 3.0F;
    public float speed = 1.0F;
    public float pendingYawDelta;
    public float pendingPitchDelta;
    /** Degrees actually turned since this controller was made, per axis. */
    public float yawDistance;
    public float pitchDistance;
    private boolean complete;
    private boolean retainAfterCompletion;
    private boolean randomizeMovement;
    private int yawJitterDirection;
    private int pitchJitterDirection;
    private int jitterTicks;
    private int yawJitter;
    private int pitchJitter;
    private long jitterTimer = Long.MIN_VALUE;
    private final Random random = new Random();

    /** @return true once yaw needs no more movement */
    public abstract boolean updateYaw();

    /** @return true once pitch needs no more movement */
    public abstract boolean updatePitch();

    /** Turns by this many mouse-scaled units; the game multiplies by 0.15 and subtracts pitch. */
    protected abstract void applyMouseDelta(float yawDelta, float pitchDelta);

    public void update() {
        boolean yawComplete = this.updateYaw();
        boolean pitchComplete = this.updatePitch();
        if (yawComplete && pitchComplete && Math.abs(this.pendingYawDelta) < 1.0F
                && Math.abs(this.pendingPitchDelta) < 1.0F) {
            this.complete = true;
        }
    }

    /** Turns by the whole counts pending, keeping the fractions for the next update. */
    public void applyPendingMovement() {
        if (this.randomizeMovement) {
            long now = System.currentTimeMillis();
            if (this.jitterTimer == Long.MIN_VALUE) {
                this.jitterTimer = now - 1L;
            }
            long elapsedMillis = now - this.jitterTimer;
            this.jitterTimer = now;
            while (elapsedMillis-- > 0L) {
                this.updateJitter();
            }
            this.pendingYawDelta += (float) this.yawJitter;
            this.pendingPitchDelta += (float) this.pitchJitter;
        }
        int yawSteps = (int) this.pendingYawDelta;
        int pitchSteps = (int) this.pendingPitchDelta;
        float remainingYaw = this.pendingYawDelta - (float) yawSteps;
        float remainingPitch = this.pendingPitchDelta - (float) pitchSteps;
        float mouseScale = MouseAim.mouseScale();
        float yawDelta = (float) yawSteps * mouseScale;
        float pitchDelta = (float) pitchSteps * mouseScale;
        this.applyMouseDelta(yawDelta, pitchDelta * -1.0F);
        this.yawDistance = (float) ((double) this.yawDistance + Math.abs((double) yawDelta * 0.15));
        this.pitchDistance = (float) ((double) this.pitchDistance + Math.abs((double) pitchDelta * 0.15));
        this.pendingYawDelta = remainingYaw;
        this.pendingPitchDelta = remainingPitch;
        this.yawJitter = 0;
        this.pitchJitter = 0;
    }

    private void updateJitter() {
        ++this.jitterTicks;
        if (this.jitterTicks >= 250 + this.random.nextInt(50)) {
            this.jitterTicks = randomBetween(this.random, -100, -50);
            this.yawJitterDirection = randomBetween(this.random, -1, 2);
            this.pitchJitterDirection = -randomBetween(this.random, -1, 2);
        }
        int yawStep = this.yawJitterDirection;
        int pitchStep = this.pitchJitterDirection;
        if (this.random.nextInt(10) < 2) {
            yawStep = 0;
        }
        if (this.random.nextInt(10) < 2) {
            pitchStep = 0;
        }
        if (this.jitterTicks < 0) {
            yawStep = 0;
            pitchStep = 0;
        }
        if (this.random.nextInt(20) == 1) {
            this.yawJitter += yawStep;
            this.pitchJitter += pitchStep;
        }
        if (this.pendingYawDelta > 0.0F && this.yawJitter < 0 || this.pendingYawDelta < 0.0F && this.yawJitter > 0) {
            this.yawJitter = 0;
        }
    }

    private static int randomBetween(Random random, int lowInclusive, int highExclusive) {
        return random.nextInt(highExclusive - lowInclusive) + lowInclusive;
    }

    public boolean isComplete() {
        return this.complete;
    }

    public void setComplete(boolean complete) {
        this.complete = complete;
    }

    public boolean shouldRetainAfterCompletion() {
        return this.retainAfterCompletion;
    }

    public void setRetainAfterCompletion(boolean retainAfterCompletion) {
        this.retainAfterCompletion = retainAfterCompletion;
    }

    public void setRandomizeMovement(boolean randomizeMovement) {
        this.randomizeMovement = randomizeMovement;
    }

    public float getSpeed() {
        return this.speed;
    }

    public MouseRotator setSpeed(float speed) {
        this.speed = speed;
        return this;
    }

    public MouseRotator setTolerance(float tolerance) {
        this.tolerance = tolerance;
        return this;
    }

    public float getYawDistance() {
        return this.yawDistance;
    }

    public float getPitchDistance() {
        return this.pitchDistance;
    }
}
