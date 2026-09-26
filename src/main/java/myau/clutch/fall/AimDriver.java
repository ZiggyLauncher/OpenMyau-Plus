package myau.clutch.fall;

import myau.rotation.mouse.MouseAim;
import myau.rotation.mouse.MouseRotator;
import myau.rotation.mouse.SilentRotator;

/**
 * Runs the clutch's aim controller, the part of Vape's {@code RotationManager} the clutch uses.
 * <p>
 * Every frame the controller gets as many updates as milliseconds have passed - about one a
 * millisecond at default sensitivity - and at the start of each tick it is topped up to exactly a
 * tick's worth, so the aim moves the same distance per tick however the frames fall. A silent
 * controller's rotation is read off at the tick as the one to report.
 * <p>
 * A released controller keeps running until it finishes: a visible one stops at once, a silent
 * one eases the reported rotation back onto your view first.
 */
public final class AimDriver {
    private MouseRotator active;
    private double accumulator;
    private int updatesThisTick;
    private long lastUpdateNanos = System.nanoTime();
    private float managedYaw;
    private float managedPitch;

    public MouseRotator getActiveController() {
        return this.active;
    }

    public boolean hasSilent() {
        return this.active instanceof SilentRotator;
    }

    public SilentRotator silent() {
        return this.active instanceof SilentRotator ? (SilentRotator) this.active : null;
    }

    public double getAccumulator() {
        return this.accumulator;
    }

    /** The silent rotation to report this tick. */
    public float getManagedYaw() {
        return this.managedYaw;
    }

    public float getManagedPitch() {
        return this.managedPitch;
    }

    public void setController(MouseRotator controller) {
        if (this.active == controller) {
            return;
        }
        if (this.active instanceof SilentRotator && controller instanceof SilentRotator) {
            SilentRotator previous = (SilentRotator) this.active;
            previous.setRelativeMode(true);
            previous.setComplete(true);
            SilentRotator next = (SilentRotator) controller;
            next.setRelativeMode(false);
            next.setCurrentYaw(previous.getRenderedYaw());
            next.setCurrentPitch(previous.getRenderedPitch());
        }
        if (this.active == null && controller instanceof SilentRotator) {
            SilentRotator silent = (SilentRotator) controller;
            this.managedYaw = silent.getRenderedYaw();
            this.managedPitch = silent.getRenderedPitch();
        }
        this.active = controller;
    }

    /** Lets the controller finish on its own terms: silent ones ease back, visible ones stop. */
    public void releaseController(MouseRotator controller) {
        if (this.active != null && this.active == controller) {
            if (this.active instanceof SilentRotator) {
                ((SilentRotator) this.active).setRelativeMode(true);
            } else {
                this.active.setRetainAfterCompletion(false);
                this.active.setComplete(true);
            }
        }
    }

    /** Drops everything at once, for a world change. */
    public void clear() {
        this.active = null;
        this.resetUpdates();
    }

    /** The start of a tick: top the controller up to a whole tick's updates. */
    public void tick(boolean playerPresent, boolean screenClosed) {
        if (this.active == null || !playerPresent) {
            this.active = null;
            this.resetUpdates();
            return;
        }
        if (this.active.isComplete() && !this.active.shouldRetainAfterCompletion()) {
            this.active = null;
            this.resetUpdates();
            return;
        }
        this.lastUpdateNanos = System.nanoTime();
        if (screenClosed) {
            double remaining = Math.max((double) MouseAim.updatesPerTick() - (double) this.updatesThisTick, 0.0);
            this.advance(remaining);
        }
        this.updatesThisTick = 0;
        if (this.active instanceof SilentRotator) {
            SilentRotator silent = (SilentRotator) this.active;
            this.managedYaw = silent.getRenderedYaw();
            this.managedPitch = Math.max(-90.0F, Math.min(90.0F, silent.getRenderedPitch()));
        }
    }

    /** A rendered frame: as many updates as game milliseconds have passed. */
    public void frame(float timerSpeed, boolean screenClosed) {
        if (this.active == null) {
            this.resetUpdates();
            return;
        }
        if (this.active.isComplete() && !this.active.shouldRetainAfterCompletion()) {
            this.active = null;
            this.resetUpdates();
            return;
        }
        long now = System.nanoTime();
        double elapsedMillis = (double) (now - this.lastUpdateNanos) / 1_000_000.0 * (double) timerSpeed;
        this.lastUpdateNanos = now;
        if (screenClosed) {
            this.advance(elapsedMillis * (double) MouseAim.timeScale());
        }
    }

    private void advance(double updates) {
        this.accumulator += updates;
        int wholeUpdates = (int) Math.round(this.accumulator);
        for (int update = 0; update < wholeUpdates; ++update) {
            try {
                this.active.update();
                this.active.applyPendingMovement();
            } catch (Exception ignored) {
                // A failed update is skipped, as in the original.
            }
            ++this.updatesThisTick;
        }
        this.accumulator -= wholeUpdates;
    }

    private void resetUpdates() {
        this.accumulator = 0.0;
        this.updatesThisTick = 0;
        this.lastUpdateNanos = System.nanoTime();
    }
}
