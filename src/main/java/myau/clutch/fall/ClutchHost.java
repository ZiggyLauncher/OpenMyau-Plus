package myau.clutch.fall;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;

/**
 * What the clutch needs from the game around it. The module supplies the live game; a test
 * supplies a synthetic world and player, and the same clutch code runs against both.
 */
public interface ClutchHost {
    /** The real player, or null out of game. */
    EntityPlayer player();

    World world();

    boolean screenOpen();

    /** The keys as held on the keyboard, whatever any module has made of them. */
    boolean forwardPhysicallyDown();

    boolean jumpPhysicallyDown();

    /** Forward, back, left and right as the game currently has them. */
    boolean[] heldMovementKeys();

    /** The player's movement state and keys, as the game has them now. */
    MovementSnapshot snapshot();

    LocalInput localInput();

    double blockReach();

    /** One right click on this crosshair result, made the way the game makes it. */
    void rightClick(MovingObjectPosition hit);

    /** Lets go of the attack key, so nothing is hit or mined mid-clutch. */
    void releaseAttackKey();

    void notifyFailure(String message);
}
