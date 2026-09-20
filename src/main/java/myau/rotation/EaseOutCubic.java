package myau.rotation;

/**
 * Plain eased turn: a large share of the remaining angle is covered immediately and the rest
 * tapers off, so the view arrives without a hard stop.
 */
final class EaseOutCubic implements Rotator {
    private static final float SLOWEST_TICKS = 40.0F;

    @Override
    public Rotation step(Rotation from, Rotation target, RotationConfig config, float deltaTicks) {
        float duration = config.scaled(SLOWEST_TICKS, 0.0F);
        float remaining = duration <= 0.0F ? 0.0F : 1.0F - Math.min(1.0F, deltaTicks / duration);
        float fraction = 1.0F - remaining * remaining * remaining;
        return from.moved(from.yawTo(target) * fraction, from.pitchTo(target) * fraction);
    }
}
