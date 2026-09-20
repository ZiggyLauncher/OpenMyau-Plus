package myau.ui.impl.clickgui.adin;

/**
 * A value that moves to its target over a fixed wall-clock duration rather than per frame, so
 * every animation in this skin runs at the same speed whatever the frame rate is.
 * <p>
 * {@link #flight()} reports how much of the current move is still left, which is what drives the
 * selection pill's squash: it stretches while travelling and relaxes as it lands.
 */
public final class AdinTransition {
    public interface Easing {
        float apply(float t);
    }

    public static final Easing EASE_OUT_CUBIC = t -> 1.0F - (1.0F - t) * (1.0F - t) * (1.0F - t);
    public static final Easing EASE_OUT_EXPO = t -> t >= 1.0F ? 1.0F : 1.0F - (float) Math.pow(2.0, -10.0F * t);
    public static final Easing SMOOTHSTEP = t -> t * t * (3.0F - 2.0F * t);

    private final long durationNanos;
    private final Easing easing;
    private float from;
    private float target;
    private long startedAt;

    public AdinTransition(float initial, int durationMillis) {
        this(initial, durationMillis, EASE_OUT_CUBIC);
    }

    public AdinTransition(float initial, int durationMillis, Easing easing) {
        this.durationNanos = Math.max(1L, (long) durationMillis * 1000000L);
        this.easing = easing;
        this.snap(initial);
    }

    /** Jumps to a value with no animation. */
    public void snap(float value) {
        this.from = value;
        this.target = value;
        this.startedAt = System.nanoTime();
    }

    public void set(float newTarget) {
        if (newTarget == this.target) {
            return;
        }
        this.from = this.value();
        this.target = newTarget;
        this.startedAt = System.nanoTime();
    }

    public float target() {
        return this.target;
    }

    public float value() {
        long elapsed = System.nanoTime() - this.startedAt;
        if (elapsed <= 0L) {
            return this.from;
        }
        float progress = Math.max(0.0F, Math.min(1.0F, (float) elapsed / (float) this.durationNanos));
        return progress >= 1.0F ? this.target : this.from + (this.target - this.from) * this.easing.apply(progress);
    }

    /** 1 at the start of a move, 0 once it has landed. */
    public float flight() {
        float span = Math.abs(this.target - this.from);
        if (span <= 0.0F) {
            return 0.0F;
        }
        return Math.max(0.0F, Math.min(1.0F, Math.abs(this.target - this.value()) / span));
    }
}
