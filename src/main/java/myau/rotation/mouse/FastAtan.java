package myau.rotation.mouse;

/**
 * Vape's lookup-table arctangents, bit for bit. The mouse-step aim computes every target angle
 * through these rather than {@link Math#atan2}, so matching its turns exactly means matching its
 * rounding too.
 * <p>
 * The original keeps eight 100 001-entry tables; seven of them are the first one shifted or
 * negated in float arithmetic, so only that one is stored here and the rest are derived with the
 * same float operations, which gives the same values.
 */
public final class FastAtan {
    private static final float HALF_PI = 1.5707964F;
    private static final float PI = (float) Math.PI;
    private static final float[] COEFFICIENTS = {-0.33333147F, 0.19993551F, -0.142089F, 0.10656264F, 1.5707964F};
    /** atan(i / 100000) for i in 0..100000. */
    private static final float[] TABLE = new float[100001];

    static {
        for (int i = 0; i <= 100000; ++i) {
            TABLE[i] = (float) Math.atan2(1.0 * ((double) i / 100000.0), 1.0);
        }
    }

    private FastAtan() {
    }

    /** {@code atan2(y, x)} by table: the original's {@code atan2Approximation}. */
    public static float atan2(float y, float x) {
        if (y < 0.0F) {
            if (x < 0.0F) {
                if (y < x) {
                    return -HALF_PI - TABLE[(int) (x / y * 100000.0F)];
                }
                return -PI + TABLE[(int) (y / x * 100000.0F)];
            }
            y = -y;
            if (y > x) {
                return -HALF_PI + TABLE[(int) (x / y * 100000.0F)];
            }
            return -TABLE[(int) (y / x * 100000.0F)];
        }
        if (x < 0.0F) {
            x = -x;
            if (y > x) {
                return HALF_PI + TABLE[(int) (x / y * 100000.0F)];
            }
            return PI - TABLE[(int) (y / x * 100000.0F)];
        }
        if (y > x) {
            return HALF_PI - TABLE[(int) (x / y * 100000.0F)];
        }
        return TABLE[(int) (y / x * 100000.0F)];
    }

    /**
     * {@code atan(value)} by polynomial: the original's {@code atanApproximation}. For values of -1
     * and below the original subtracts the tail from -pi/2 instead of adding it, which is out by up
     * to 1.6 radians; the sign is applied to the whole result here, which is what it meant.
     */
    public static float atan(float value) {
        float magnitude = Math.abs(value);
        if (magnitude < 1.0F) {
            float square = value * value;
            return value * (1.0F + square * (COEFFICIENTS[0] + square * (COEFFICIENTS[1]
                    + square * (COEFFICIENTS[2] + square * COEFFICIENTS[3]))));
        }
        float inverse = 1.0F / magnitude;
        float square = inverse * inverse;
        float tail = inverse * (1.0F + square * (COEFFICIENTS[0] + square * (COEFFICIENTS[1]
                + square * (COEFFICIENTS[2] + square * COEFFICIENTS[3]))));
        return Math.copySign(COEFFICIENTS[4] - tail, value);
    }
}
