package myau.rotation;

/**
 * How an aim should be turned: how smooth, with which curve, and whether the turn is visible on
 * screen or only applied to the rotation the server is told about.
 */
public final class RotationConfig {
    public static final int EASE_OUT_CUBIC = 0;
    public static final int WIND_MOUSE = 1;
    public static final String[] SMOOTHING = {"Ease-Out-Cubic", "WindMouse"};

    private final float smoothness;
    private final int smoothing;
    private final boolean silent;

    private RotationConfig(float smoothness, int smoothing, boolean silent) {
        this.smoothness = Math.max(0.0F, Math.min(1.0F, smoothness));
        this.smoothing = smoothing;
        this.silent = silent;
    }

    public static RotationConfig visible(float smoothness, int smoothing) {
        return new RotationConfig(smoothness, smoothing, false);
    }

    public static RotationConfig silent(float smoothness, int smoothing) {
        return new RotationConfig(smoothness, smoothing, true);
    }

    public float smoothness() {
        return this.smoothness;
    }

    public int smoothing() {
        return this.smoothing;
    }

    public boolean isSilent() {
        return this.silent;
    }

    /**
     * Maps smoothness onto a speed: fully smooth returns {@code slow}, no smoothing returns
     * {@code fast}. Squared, so the top half of the slider is where the fine control lives.
     */
    public float scaled(float slow, float fast) {
        float eager = (1.0F - this.smoothness) * (1.0F - this.smoothness);
        return slow + (fast - slow) * eager;
    }

    public Rotator createRotator() {
        return this.smoothing == WIND_MOUSE ? new WindMouse() : new EaseOutCubic();
    }
}
