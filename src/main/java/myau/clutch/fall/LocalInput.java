package myau.clutch.fall;

import myau.mixin.IAccessorEntityPlayerSP;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;

/**
 * The few things a rehearsal always reads from the real player, whoever it is rehearsing: the
 * sprint timers and last tick's input, which seed a fresh rehearsal, and jump boost, which the
 * original takes from the real player even when simulating a copy.
 */
public final class LocalInput {
    public final int sprintToggleTimer;
    public final int sprintingTicksLeft;
    public final float moveForward;
    public final float moveStrafe;
    /** Jump boost amplifier + 1, or 0 without the effect. */
    public final int jumpBoostLevel;

    public LocalInput(int sprintToggleTimer, int sprintingTicksLeft, float moveForward, float moveStrafe,
                      int jumpBoostLevel) {
        this.sprintToggleTimer = sprintToggleTimer;
        this.sprintingTicksLeft = sprintingTicksLeft;
        this.moveForward = moveForward;
        this.moveStrafe = moveStrafe;
        this.jumpBoostLevel = jumpBoostLevel;
    }

    public static LocalInput capture(EntityPlayerSP player) {
        PotionEffect jumpBoost = player.getActivePotionEffect(Potion.jump);
        return new LocalInput(
                ((IAccessorEntityPlayerSP) player).getSprintToggleTimer(),
                player.sprintingTicksLeft,
                player.movementInput.moveForward,
                player.movementInput.moveStrafe,
                jumpBoost != null ? jumpBoost.getAmplifier() + 1 : 0);
    }
}
