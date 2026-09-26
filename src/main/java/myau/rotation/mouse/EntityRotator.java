package myau.rotation.mouse;

import net.minecraft.entity.player.EntityPlayer;

/**
 * Turns a player's actual view: the local player's for a visible aim, or a simulated player's
 * while a plan is being rehearsed. Ported from Vape's {@code EntityFixedRotationController} and
 * the {@code PlayerMouseRotationApplier} it turns through.
 * <p>
 * The turn is written straight to the rotation fields, the same arithmetic as the game's own
 * {@code setAngles}. Going through {@code setAngles} itself would hand it to anything hooked
 * there for real mouse input, such as a free-look camera.
 */
public class EntityRotator extends TargetRotator {
    private final EntityPlayer player;

    public EntityRotator(float yaw, float pitch, EntityPlayer player) {
        super(yaw, pitch);
        this.player = player;
    }

    public EntityPlayer getPlayer() {
        return this.player;
    }

    @Override
    public float getCurrentYaw() {
        return this.player.rotationYaw;
    }

    @Override
    public float getCurrentPitch() {
        return this.player.rotationPitch;
    }

    @Override
    protected void applyMouseDelta(float yawDelta, float pitchDelta) {
        applyTurn(this.player, yawDelta, pitchDelta);
    }

    /** The original's {@code PlayerMouseRotationApplier.applyMouseDelta}. */
    public static void applyTurn(EntityPlayer player, float yawDelta, float pitchDelta) {
        float previousPitch = player.rotationPitch;
        float previousYaw = player.rotationYaw;
        player.rotationYaw = (float) ((double) player.rotationYaw + (double) yawDelta * 0.15);
        player.rotationPitch = (float) ((double) player.rotationPitch - (double) pitchDelta * 0.15);
        if (player.rotationPitch < -90.0F) {
            player.rotationPitch = -90.0F;
        }
        if (player.rotationPitch > 90.0F) {
            player.rotationPitch = 90.0F;
        }
        player.prevRotationPitch = player.prevRotationPitch + player.rotationPitch - previousPitch;
        player.prevRotationYaw = player.prevRotationYaw + player.rotationYaw - previousYaw;
        player.rotationYawHead = player.rotationYaw;
        player.prevRotationYawHead = player.rotationYawHead;
    }
}
