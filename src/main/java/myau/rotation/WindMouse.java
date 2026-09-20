package myau.rotation;

import java.util.concurrent.ThreadLocalRandom;

/**
 * The WindMouse turn: the view is pulled toward the target by a constant "gravity" while a
 * random "wind" term pushes it sideways, both integrated into a velocity that is speed-capped.
 * The result overshoots slightly, drifts, and settles - the shape a hand makes, rather than the
 * straight line an interpolation makes.
 * <p>
 * Nodes are generated one tick apart and the view is interpolated between them, so the turn is
 * frame-rate independent: the same path is walked at 30 FPS and at 300.
 * <p>
 * If the player moves their own mouse mid-turn, the drift between where this expected the view to
 * be and where it actually is gets folded back into the current segment, so the assist follows the
 * hand instead of fighting it.
 */
final class WindMouse implements Rotator {
    private static final float FASTEST = 14.0F;
    private static final float SLOWEST = 1.0F;
    private static final float GRAVITY = 0.6F;
    private static final float WIND = 0.2F;
    private static final float SETTLE = 0.8F;
    private static final float FLOOR = 0.2F;
    private static final float FLOOR_TOLERANCE = 1.0001F;
    private static final float DONE = 1.0F / 15.0F;
    private static final float FULL_TURN = 60.0F;
    private static final float SHORT_PACE = 0.4F;
    private static final float SQRT3 = 1.7320508F;
    private static final float SQRT5 = 2.236068F;

    private Rotation node;
    private Rotation next;
    private Rotation expected;
    private float phase;
    private float velocityYaw;
    private float velocityPitch;
    private float windYaw;
    private float windPitch;
    private float cap;
    private float pace = SHORT_PACE;
    private boolean travelling;
    private boolean settled;

    @Override
    public Rotation step(Rotation from, Rotation target, RotationConfig config, float deltaTicks) {
        if (config.smoothness() <= 0.0F) {
            this.rest();
            this.next = null;
            return from.moved(from.yawTo(target), from.pitchTo(target));
        }

        if (this.next == null) {
            this.node = from;
            this.next = this.advance(from, target, config);
            this.phase = 0.0F;
        } else {
            // Whatever the player's own mouse did since the last step shifts the whole segment,
            // so the assist rides along with the hand instead of dragging the view back.
            float driftYaw = this.expected.yawTo(from);
            float driftPitch = this.expected.pitchTo(from);
            if (driftYaw != 0.0F || driftPitch != 0.0F) {
                this.node = this.node.moved(driftYaw, driftPitch);
                this.next = this.next.moved(driftYaw, driftPitch);
            }
        }

        this.phase += deltaTicks;
        while (this.phase >= 1.0F) {
            this.phase -= 1.0F;
            this.node = this.next;
            this.next = this.advance(this.node, target, config);
        }

        this.expected = lerp(this.node, this.next, this.phase);
        return this.expected;
    }

    /** One tick of the wind/gravity integration: where the view should be a tick from now. */
    private Rotation advance(Rotation from, Rotation target, RotationConfig config) {
        float deltaYaw = from.yawTo(target);
        float deltaPitch = from.pitchTo(target);
        float distance = Rotation.length(deltaYaw, deltaPitch);
        float base = config.scaled(SLOWEST, FASTEST);

        if (!this.travelling || (this.settled && distance >= base * SETTLE)) {
            // A fresh flick: long turns get a faster pace than short corrections.
            float reach = Math.min(1.0F, distance / FULL_TURN);
            this.pace = (SHORT_PACE + (1.0F - SHORT_PACE) * reach) * random(0.85F, 1.15F);
            this.cap = base * this.pace;
            this.travelling = true;
            this.settled = false;
        }

        float speed = base * this.pace;
        if (distance < speed * DONE) {
            this.rest();
            return from.moved(deltaYaw, deltaPitch);
        }

        if (distance >= speed * SETTLE) {
            float gust = Math.min(speed * WIND, distance) / SQRT5;
            this.windYaw = this.windYaw / SQRT3 + random(-1.0F, 1.0F) * gust;
            this.windPitch = this.windPitch / SQRT3 + random(-1.0F, 1.0F) * gust;
        } else {
            // Close in: the wind dies down and the speed cap is pulled in so it can settle.
            this.windYaw /= SQRT3;
            this.windPitch /= SQRT3;
            this.cap = this.cap < speed * FLOOR * FLOOR_TOLERANCE
                    ? speed * (FLOOR + FLOOR * ThreadLocalRandom.current().nextFloat())
                    : this.cap / SQRT5;
            this.settled = true;
        }

        float gravity = speed * GRAVITY;
        this.velocityYaw += this.windYaw + gravity * deltaYaw / distance;
        this.velocityPitch += this.windPitch + gravity * deltaPitch / distance;

        float magnitude = Rotation.length(this.velocityYaw, this.velocityPitch);
        if (magnitude > this.cap) {
            float clipped = this.cap * (0.5F + 0.5F * ThreadLocalRandom.current().nextFloat());
            this.velocityYaw *= clipped / magnitude;
            this.velocityPitch *= clipped / magnitude;
        }
        return from.moved(this.velocityYaw, this.velocityPitch);
    }

    private void rest() {
        this.windYaw = 0.0F;
        this.windPitch = 0.0F;
        this.velocityYaw = 0.0F;
        this.velocityPitch = 0.0F;
        this.travelling = false;
        this.settled = false;
    }

    private static Rotation lerp(Rotation from, Rotation to, float phase) {
        if (phase <= 0.0F) {
            return from;
        }
        return new Rotation(from.yaw + (to.yaw - from.yaw) * phase,
                from.pitch + (to.pitch - from.pitch) * phase);
    }

    /**
     * Java 8's ThreadLocalRandom has no bounded nextFloat, so the range is applied by hand.
     */
    private static float random(float low, float high) {
        return low + ThreadLocalRandom.current().nextFloat() * (high - low);
    }
}
