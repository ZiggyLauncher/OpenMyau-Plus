package myau.clutch.fall;

import myau.Myau;
import myau.mixin.IAccessorEntityPlayerSP;
import myau.module.Module;
import myau.module.modules.Sprint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.player.EntityPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything a movement rehearsal needs to pick up where a player is: position, motion, rotation,
 * the sprint and jump timers, and which movement keys are down. The original calls this
 * {@code BlockPlacementGraph}, though it is a movement snapshot and nothing else.
 */
public final class MovementSnapshot {
    public final double positionX;
    public final double positionY;
    public final double positionZ;
    public final double previousPositionX;
    public final double previousPositionY;
    public final double previousPositionZ;
    public final double motionX;
    public final double motionY;
    public final double motionZ;
    public final float yaw;
    public final float pitch;
    public final float previousYaw;
    public final float previousPitch;
    public final int sprintingTicksLeft;
    public final int sprintToggleTimer;
    public final boolean onGround;
    public final boolean sneaking;
    public final boolean sprinting;
    public final int jumpTicks;
    public final float jumpMovementFactor;
    public final float aiMoveSpeed;
    public final boolean inWater;
    public final double movementSpeedBase;
    public final List<AttributeModifier> movementSpeedModifiers;
    public final float moveForward;
    public final float moveStrafe;
    public final boolean jumpInput;
    public final boolean sneakInput;
    public boolean forwardKeyDown;
    public boolean backwardKeyDown;
    public boolean leftKeyDown;
    public boolean rightKeyDown;
    public boolean sneakKeyDown;
    public boolean jumpKeyDown;
    public boolean sprintKeyDown;

    private MovementSnapshot(EntityPlayer player, int sprintingTicksLeft, int sprintToggleTimer, boolean sneaking,
                             float moveForward, float moveStrafe, boolean jumpInput, boolean sneakInput) {
        this.positionX = player.posX;
        this.positionY = player.posY;
        this.positionZ = player.posZ;
        this.previousPositionX = player.prevPosX;
        this.previousPositionY = player.prevPosY;
        this.previousPositionZ = player.prevPosZ;
        this.motionX = player.motionX;
        this.motionY = player.motionY;
        this.motionZ = player.motionZ;
        this.yaw = player.rotationYaw;
        this.pitch = player.rotationPitch;
        this.previousYaw = player.prevRotationYaw;
        this.previousPitch = player.prevRotationPitch;
        this.sprintingTicksLeft = sprintingTicksLeft;
        this.sprintToggleTimer = sprintToggleTimer;
        this.onGround = player.onGround;
        this.sneaking = sneaking;
        this.sprinting = player.isSprinting();
        this.jumpTicks = EntityAccess.getJumpTicks(player);
        this.jumpMovementFactor = player.jumpMovementFactor;
        this.aiMoveSpeed = player.getAIMoveSpeed();
        this.inWater = player.isInWater();
        IAttributeInstance speed = player.getEntityAttribute(SharedMonsterAttributes.movementSpeed);
        this.movementSpeedBase = speed.getBaseValue();
        this.movementSpeedModifiers = new ArrayList<AttributeModifier>(speed.func_111122_c());
        this.moveForward = moveForward;
        this.moveStrafe = moveStrafe;
        this.jumpInput = jumpInput;
        this.sneakInput = sneakInput;
    }

    /** The real player as it stands, with its movement keys as the game currently has them. */
    public static MovementSnapshot of(EntityPlayerSP player) {
        MovementSnapshot snapshot = new MovementSnapshot(player, player.sprintingTicksLeft,
                ((IAccessorEntityPlayerSP) player).getSprintToggleTimer(), player.isSneaking(),
                player.movementInput.moveForward, player.movementInput.moveStrafe,
                player.movementInput.jump, player.movementInput.sneak);
        GameSettings settings = Minecraft.getMinecraft().gameSettings;
        snapshot.forwardKeyDown = settings.keyBindForward.isKeyDown();
        snapshot.backwardKeyDown = settings.keyBindBack.isKeyDown();
        snapshot.leftKeyDown = settings.keyBindLeft.isKeyDown();
        snapshot.rightKeyDown = settings.keyBindRight.isKeyDown();
        snapshot.sneakKeyDown = settings.keyBindSneak.isKeyDown();
        snapshot.jumpKeyDown = settings.keyBindJump.isKeyDown();
        snapshot.sprintKeyDown = settings.keyBindSprint.isKeyDown() || sprintModuleEnabled();
        return snapshot;
    }

    /** A rehearsal as it stands, tick for tick where it has got to. */
    public static MovementSnapshot of(MovementSimulation simulation) {
        EntityPlayer player = simulation.getSimulatedPlayer();
        MovementSnapshot snapshot = new MovementSnapshot(player, simulation.getSprintingTicksLeft(),
                simulation.getSprintToggleTimer(), simulation.isSneaking(), simulation.getMoveForward(),
                simulation.getMoveStrafe(), simulation.isJumpInput(), simulation.isSneaking());
        snapshot.forwardKeyDown = simulation.isForwardKeyDown();
        snapshot.backwardKeyDown = simulation.isBackwardKeyDown();
        snapshot.leftKeyDown = simulation.isLeftKeyDown();
        snapshot.rightKeyDown = simulation.isRightKeyDown();
        snapshot.sneakKeyDown = simulation.isSneakKeyDown();
        snapshot.jumpKeyDown = simulation.isJumpKeyDown();
        snapshot.sprintKeyDown = simulation.isSprintKeyDown() || sprintModuleEnabled();
        return snapshot;
    }

    /** For tests: a player snapshot with the input spelled out rather than read from the game. */
    public static MovementSnapshot of(EntityPlayer player, int sprintingTicksLeft, int sprintToggleTimer,
                                      float moveForward, float moveStrafe, boolean jumpInput, boolean sneakInput) {
        return new MovementSnapshot(player, sprintingTicksLeft, sprintToggleTimer, player.isSneaking(),
                moveForward, moveStrafe, jumpInput, sneakInput);
    }

    private static boolean sprintModuleEnabled() {
        if (Myau.moduleManager == null) {
            return false;
        }
        Module sprint = Myau.moduleManager.modules.get(Sprint.class);
        return sprint != null && sprint.isEnabled();
    }
}
