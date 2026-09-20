package myau.rotation;

/**
 * One step of a humanised turn. Implementations keep their own state between calls, so a rotator
 * belongs to a single aim and is thrown away when the target is lost.
 */
public interface Rotator {
    /**
     * @param from       where the view is now
     * @param target     where it should end up
     * @param config     smoothness and mode for this aim
     * @param deltaTicks time since the previous step, in ticks (1.0 = one full tick)
     * @return where the view should be after this step
     */
    Rotation step(Rotation from, Rotation target, RotationConfig config, float deltaTicks);
}
