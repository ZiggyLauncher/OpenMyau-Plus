package myau.clutch.fall;

import myau.rotation.mouse.MouseAim;
import myau.rotation.mouse.MouseRotator;
import myau.rotation.mouse.SilentRotator;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.potion.Potion;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

/**
 * Plays a player's movement forward tick by tick, off the real world, with the keys and aim it is
 * given. Ported from Vape's {@code BlockPathPlanner} and its 1.8.9 movement controller, which
 * together are the game's own {@code EntityPlayerSP.onLivingUpdate} and
 * {@code EntityLivingBase.moveEntityWithHeading} rewritten to run on a copy: sprint and
 * double-tap timers, sneaking, using an item, being pushed out of blocks, jumping and jump boost,
 * ground friction, air control, ladders, water and lava.
 * <p>
 * A rotation controller can be attached; it is then advanced the same number of updates a real
 * tick gets, so the rehearsal turns exactly as the live aim will. With a silent controller, the
 * movement keys are corrected against the reported yaw the way the live movement correction
 * does, so the rehearsal moves where the real player will.
 */
public final class MovementSimulation implements AutoCloseable {
    private final SimulatedPlayer simulatedPlayer;
    private final EntityPlayer sourcePlayer;
    private final World world;
    private final LocalInput local;
    private final MovementSnapshot initialSnapshot;
    private final double blockReach;
    private final Entity localPlayer;
    private MouseRotator rotationController;
    private double rotationUpdateAccumulator;
    private float simulatedYaw;
    private float simulatedPitch;
    private float savedYaw;
    private boolean restoreYawPending;
    private boolean movementKeysAdjusted;

    // The movement controller's state.
    private boolean forwardKeyDown;
    private boolean backwardKeyDown;
    private boolean leftKeyDown;
    private boolean rightKeyDown;
    private boolean jumpKeyDown;
    private boolean sneakKeyDown;
    private boolean sprintKeyDown;
    private float moveForward;
    private float moveStrafe;
    private boolean jumpInput;
    private boolean sneakInput;
    private int sprintToggleTimer;
    private int sprintingTicksLeft;

    /**
     * @param source            who to start from: the real player, or a player in another rehearsal
     * @param local             the real player's timers and input, which seed every rehearsal
     * @param localPlayer       the real player entity, which never blocks its own traces; may be null
     * @param rotationAccumulator the live aim's fractional update count, so updates line up
     * @param liveSilent        the live silent aim, if any; the rehearsal starts from what it reports
     */
    public MovementSimulation(EntityPlayer source, LocalInput local, Entity localPlayer, World world,
                              MovementSnapshot initialSnapshot, double blockReach, double rotationAccumulator,
                              SilentRotator liveSilent) {
        this(SimulatedPlayer.acquire(world, source), source, local, localPlayer, world, initialSnapshot,
                blockReach, rotationAccumulator, liveSilent);
    }

    private MovementSimulation(SimulatedPlayer simulatedPlayer, EntityPlayer source, LocalInput local,
                               Entity localPlayer, World world, MovementSnapshot initialSnapshot, double blockReach,
                               double rotationAccumulator, SilentRotator liveSilent) {
        this.simulatedPlayer = simulatedPlayer;
        this.sourcePlayer = source;
        this.local = local;
        this.localPlayer = localPlayer;
        this.world = world;
        this.initialSnapshot = initialSnapshot;
        this.blockReach = blockReach;
        this.rotationUpdateAccumulator = rotationAccumulator;
        this.initialize();
        if (source == localPlayer && liveSilent != null) {
            this.simulatedYaw = liveSilent.getRenderedYaw();
            this.simulatedPitch = liveSilent.getRenderedPitch();
        }
    }

    @Override
    public void close() {
        SimulatedPlayer.release(this.simulatedPlayer);
    }

    // ---------------------------------------------------------------- set-up

    /** The original controller's {@code initialize}: timers and last input from the real player. */
    private void initialize() {
        this.sprintToggleTimer = this.local.sprintToggleTimer;
        this.sprintingTicksLeft = this.local.sprintingTicksLeft;
        this.moveForward = this.local.moveForward;
        this.moveStrafe = this.local.moveStrafe;
        IAttributeInstance sourceSpeed = this.sourcePlayer.getEntityAttribute(SharedMonsterAttributes.movementSpeed);
        this.simulatedPlayer.setMovementSpeed(sourceSpeed.getBaseValue(), sourceSpeed.func_111122_c());
    }

    /** Puts the copy exactly where a snapshot says, keys included. */
    public void applySnapshot(MovementSnapshot snapshot) {
        SimulatedPlayer player = this.simulatedPlayer;
        this.sprintingTicksLeft = snapshot.sprintingTicksLeft;
        this.sprintToggleTimer = snapshot.sprintToggleTimer;
        this.moveForward = snapshot.moveForward;
        this.moveStrafe = snapshot.moveStrafe;
        this.jumpInput = snapshot.jumpInput;
        this.sneakInput = snapshot.sneakInput;
        player.setPosition(snapshot.positionX, snapshot.positionY, snapshot.positionZ);
        player.prevPosX = snapshot.previousPositionX;
        player.prevPosY = snapshot.previousPositionY;
        player.prevPosZ = snapshot.previousPositionZ;
        player.motionX = snapshot.motionX;
        player.motionY = snapshot.motionY;
        player.motionZ = snapshot.motionZ;
        player.rotationYaw = snapshot.yaw;
        player.rotationPitch = snapshot.pitch;
        player.prevRotationYaw = snapshot.previousYaw;
        player.prevRotationPitch = snapshot.previousPitch;
        player.onGround = snapshot.onGround;
        player.setSneaking(snapshot.sneaking);
        player.setMovementSpeed(snapshot.movementSpeedBase, snapshot.movementSpeedModifiers);
        player.setSprinting(snapshot.sprinting);
        EntityAccess.setJumpTicks(player, snapshot.jumpTicks);
        player.jumpMovementFactor = snapshot.jumpMovementFactor;
        player.setAIMoveSpeed(snapshot.aiMoveSpeed);
        player.setInWater(snapshot.inWater);
        this.forwardKeyDown = snapshot.forwardKeyDown;
        this.backwardKeyDown = snapshot.backwardKeyDown;
        this.leftKeyDown = snapshot.leftKeyDown;
        this.rightKeyDown = snapshot.rightKeyDown;
        this.sneakKeyDown = snapshot.sneakKeyDown;
        this.jumpKeyDown = snapshot.jumpKeyDown;
        this.sprintKeyDown = snapshot.sprintKeyDown;
    }

    /** The keys the rehearsal was started with. */
    public void restoreSnapshotInput() {
        this.setInput(this.initialSnapshot.forwardKeyDown, this.initialSnapshot.backwardKeyDown,
                this.initialSnapshot.leftKeyDown, this.initialSnapshot.rightKeyDown,
                this.initialSnapshot.jumpInput, this.initialSnapshot.sneakInput);
    }

    /** Only forward held, from here on. */
    public void setForwardOnly() {
        if (this.initialSnapshot != null) {
            this.initialSnapshot.forwardKeyDown = true;
            this.initialSnapshot.backwardKeyDown = false;
            this.initialSnapshot.leftKeyDown = false;
            this.initialSnapshot.rightKeyDown = false;
        }
        this.setDirectionalKeys(true, false, false, false);
    }

    public void clearInput() {
        this.setInput(false, false, false, false, false, false);
    }

    public void setInput(boolean forward, boolean backward, boolean left, boolean right, boolean jump, boolean sneak) {
        this.forwardKeyDown = forward;
        this.backwardKeyDown = backward;
        this.leftKeyDown = left;
        this.rightKeyDown = right;
        this.jumpKeyDown = jump;
        this.sneakKeyDown = sneak;
    }

    public void setDirectionalKeys(boolean forward, boolean backward, boolean left, boolean right) {
        this.forwardKeyDown = forward;
        this.backwardKeyDown = backward;
        this.leftKeyDown = left;
        this.rightKeyDown = right;
    }

    /** No more jumping from here on. */
    public void finishSimulation() {
        this.jumpKeyDown = false;
    }

    /**
     * Attaches the rehearsal's aim. Swapping one silent aim for another carries the reported
     * rotation across, as the live aim does when it changes hands.
     */
    public void setRotationController(MouseRotator controller) {
        if (this.rotationController == controller) {
            return;
        }
        if (this.rotationController instanceof SilentRotator && controller instanceof SilentRotator) {
            SilentRotator previous = (SilentRotator) this.rotationController;
            previous.setRelativeMode(true);
            previous.setComplete(true);
            SilentRotator next = (SilentRotator) controller;
            next.setRelativeMode(false);
            next.setCurrentYaw(previous.getRenderedYaw());
            next.setCurrentPitch(previous.getRenderedPitch());
        }
        if (this.rotationController == null && controller instanceof SilentRotator) {
            SilentRotator silent = (SilentRotator) controller;
            this.simulatedYaw = silent.getRenderedYaw();
            this.simulatedPitch = silent.getRenderedPitch();
        }
        this.rotationController = controller;
    }

    public MouseRotator getRotationController() {
        return this.rotationController;
    }

    public boolean hasSilentRotation() {
        return this.rotationController instanceof SilentRotator;
    }

    // ---------------------------------------------------------------- ticking

    /** One tick's worth of aim updates, exactly as many as the live aim gets. */
    public void updateRotation() {
        if (this.rotationController == null) {
            return;
        }
        double updatesPerTick = (double) MouseAim.updatesPerTick();
        this.rotationUpdateAccumulator += updatesPerTick;
        int updateCount = (int) Math.round(this.rotationUpdateAccumulator);
        for (int update = 0; update < updateCount; ++update) {
            try {
                this.rotationController.update();
                this.rotationController.applyPendingMovement();
            } catch (Exception ignored) {
                // The original swallows a failed update and carries on with the next.
            }
        }
        this.rotationUpdateAccumulator -= updateCount;
        if (this.hasSilentRotation()) {
            SilentRotator silent = (SilentRotator) this.rotationController;
            this.simulatedYaw = silent.getRenderedYaw();
            this.simulatedPitch = silent.getRenderedPitch();
        }
    }

    public void simulateTick() {
        this.simulateTick(true);
    }

    public void simulateTick(boolean updateRotation) {
        if (updateRotation) {
            this.updateRotation();
        }
        SimulatedPlayer player = this.simulatedPlayer;
        player.prevRotationYaw = player.rotationYaw;
        player.prevRotationPitch = player.rotationPitch;
        player.prevPosX = player.posX;
        player.prevPosY = player.posY;
        player.prevPosZ = player.posZ;
        if (this.hasSilentRotation()) {
            SilentRotator silent = (SilentRotator) this.rotationController;
            this.simulatedYaw = silent.getRenderedYaw();
            this.simulatedPitch = MathHelper.clamp_float(silent.getRenderedPitch(), -90.0F, 90.0F);
            this.adjustMovementForRotation();
        }
        this.tickMovement();
        if (this.restoreYawPending) {
            player.rotationYaw = this.savedYaw;
            player.rotationYawHead = this.savedYaw;
            this.restoreYawPending = false;
        }
        if (this.movementKeysAdjusted) {
            this.restoreSnapshotInput();
            this.movementKeysAdjusted = false;
        }
    }

    /**
     * The movement correction for a silent aim: move with the reported yaw, and press whichever
     * keys point nearest to where you meant to go. The live correction does the same thing, so
     * the rehearsal and the real movement stay together.
     */
    private void adjustMovementForRotation() {
        if (!this.hasSilentRotation()) {
            if (this.movementKeysAdjusted) {
                this.restoreSnapshotInput();
                this.movementKeysAdjusted = false;
            }
            return;
        }
        boolean hasDirectionalInput = this.hasDirectionalInput();
        SilentRotator silent = (SilentRotator) this.rotationController;
        this.savedYaw = this.simulatedPlayer.rotationYaw;
        float movementYaw = movementYaw(silent.getReferenceYaw(), this.forwardKeyDown, this.backwardKeyDown,
                this.leftKeyDown, this.rightKeyDown);
        float correctedYaw = this.simulatedYaw;
        this.simulatedPlayer.rotationYaw = correctedYaw;
        this.simulatedPlayer.rotationYawHead = correctedYaw;
        this.restoreYawPending = true;
        if (hasDirectionalInput) {
            boolean[] keys = keysToward(correctedYaw, movementYaw, 0.4F);
            this.setDirectionalKeys(keys[0], keys[1], keys[2], keys[3]);
            this.movementKeysAdjusted = true;
        } else if (this.movementKeysAdjusted) {
            this.restoreSnapshotInput();
            this.movementKeysAdjusted = false;
        }
    }

    /**
     * The direction the movement keys point for a player facing {@code yaw}: left is a quarter
     * turn anticlockwise, right a quarter turn clockwise. The original's {@code adjustMovementYaw}.
     */
    public static float movementYaw(float yaw, boolean forward, boolean backward, boolean left, boolean right) {
        float adjustedYaw = yaw;
        if (forward && right) {
            adjustedYaw += 45.0F;
        } else if (backward && right) {
            adjustedYaw += 135.0F;
        } else if (right) {
            adjustedYaw += 90.0F;
        } else if (forward && left) {
            adjustedYaw -= 45.0F;
        } else if (backward && left) {
            adjustedYaw -= 135.0F;
        } else if (left) {
            adjustedYaw -= 90.0F;
        } else if (backward) {
            adjustedYaw += 180.0F;
        }
        return adjustedYaw;
    }

    /**
     * The keys - forward, back, left, right - that move a player facing {@code facingYaw} nearest
     * to {@code movementYaw}: each is held when the travel direction leans its way by more than
     * {@code threshold} (a cosine). Only whole key presses, as a keyboard makes.
     */
    public static boolean[] keysToward(float facingYaw, float movementYaw, float threshold) {
        float yawDifference = MouseAim.wrap(MouseAim.wrap(facingYaw) - movementYaw);
        float radians = yawDifference * ((float) Math.PI / 180.0F);
        float forwardProjection = (float) Math.cos(radians);
        float rightProjection = (float) (-Math.sin(radians));
        return new boolean[]{
                (double) forwardProjection >= (double) threshold,
                (double) forwardProjection <= -(double) threshold,
                (double) rightProjection <= -(double) threshold,
                (double) rightProjection >= (double) threshold};
    }

    /** The 1.8.9 player's {@code onLivingUpdate}, as the original's movement controller has it. */
    private void tickMovement() {
        SimulatedPlayer player = this.simulatedPlayer;
        player.handleWaterMovement();
        if (this.sprintingTicksLeft > 0) {
            --this.sprintingTicksLeft;
            if (this.sprintingTicksLeft == 0) {
                player.setSprinting(false);
            }
        }
        if (this.sprintToggleTimer > 0) {
            --this.sprintToggleTimer;
        }
        boolean wasSneaking = this.sneakInput;
        float sprintThreshold = 0.8F;
        boolean wasMovingForward = this.moveForward >= sprintThreshold;
        this.updateMovementInput();
        boolean usingItem = player.isUsingItem();
        if (usingItem && !player.isRiding()) {
            this.moveStrafe *= 0.2F;
            this.moveForward *= 0.2F;
            this.sprintToggleTimer = 0;
        }
        AxisAlignedBB box = player.getEntityBoundingBox();
        double collisionOffset = (double) player.width * 0.35;
        double collisionY = box.minY + 0.5;
        this.pushOutOfBlocks(player.posX - collisionOffset, collisionY, player.posZ + collisionOffset);
        this.pushOutOfBlocks(player.posX - collisionOffset, collisionY, player.posZ - collisionOffset);
        this.pushOutOfBlocks(player.posX + collisionOffset, collisionY, player.posZ - collisionOffset);
        this.pushOutOfBlocks(player.posX + collisionOffset, collisionY, player.posZ + collisionOffset);
        boolean canSprint = (float) player.getFoodStats().getFoodLevel() > 6.0F || player.capabilities.allowFlying;
        boolean blind = player.isPotionActive(Potion.blindness);
        if (player.onGround && !wasSneaking && !wasMovingForward && this.moveForward >= sprintThreshold
                && !player.isSprinting() && canSprint && !usingItem && !blind) {
            if (this.sprintToggleTimer <= 0 && !this.sprintKeyDown) {
                this.sprintToggleTimer = 7;
            } else {
                player.setSprinting(true);
                this.sprintingTicksLeft = 600;
            }
        }
        if (!player.isSprinting() && this.moveForward >= sprintThreshold && canSprint && !usingItem && !blind
                && this.sprintKeyDown) {
            player.setSprinting(true);
            this.sprintingTicksLeft = 600;
        }
        if (player.isSprinting() && (this.moveForward < sprintThreshold || player.isCollidedHorizontally || !canSprint)) {
            player.setSprinting(false);
            this.sprintingTicksLeft = 0;
        }

        // EntityLivingBase.onLivingUpdate from here.
        if (EntityAccess.getJumpTicks(player) > 0) {
            EntityAccess.setJumpTicks(player, EntityAccess.getJumpTicks(player) - 1);
        }
        if (Math.abs(player.motionX) < 0.005) {
            player.motionX = 0.0;
        }
        if (Math.abs(player.motionY) < 0.005) {
            player.motionY = 0.0;
        }
        if (Math.abs(player.motionZ) < 0.005) {
            player.motionZ = 0.0;
        }
        player.moveStrafing = this.moveStrafe;
        player.moveForward = this.moveForward;
        player.setJumping(this.jumpInput);
        if (this.jumpInput) {
            if (player.isInWater()) {
                player.motionY += (double) 0.04F;
            } else if (player.isInLava()) {
                player.motionY += (double) 0.04F;
            } else if (player.onGround && EntityAccess.getJumpTicks(player) == 0) {
                player.motionY = (double) 0.42F;
                if (this.local.jumpBoostLevel > 0) {
                    player.motionY += (double) ((float) this.local.jumpBoostLevel * 0.1F);
                }
                if (player.isSprinting()) {
                    float yawRadians = player.rotationYaw * 0.017453292F;
                    player.motionX -= (double) (MathHelper.sin(yawRadians) * 0.2F);
                    player.motionZ += (double) (MathHelper.cos(yawRadians) * 0.2F);
                }
                EntityAccess.setJumpTicks(player, 10);
            }
        } else {
            EntityAccess.setJumpTicks(player, 0);
        }
        player.moveStrafing *= 0.98F;
        player.moveForward *= 0.98F;
        moveEntityWithHeading(player, this.world, player.moveStrafing, player.moveForward);

        // EntityPlayer.onLivingUpdate after the move.
        IAttributeInstance speed = player.getEntityAttribute(SharedMonsterAttributes.movementSpeed);
        player.jumpMovementFactor = 0.02F;
        if (player.isSprinting()) {
            player.jumpMovementFactor = (float) ((double) player.jumpMovementFactor + 0.005999999865889549);
        }
        player.setAIMoveSpeed((float) speed.getAttributeValue());
    }

    private void updateMovementInput() {
        this.moveForward = 0.0F;
        this.moveStrafe = 0.0F;
        if (this.forwardKeyDown) {
            ++this.moveForward;
        }
        if (this.backwardKeyDown) {
            --this.moveForward;
        }
        if (this.leftKeyDown) {
            ++this.moveStrafe;
        }
        if (this.rightKeyDown) {
            --this.moveStrafe;
        }
        this.jumpInput = this.jumpKeyDown;
        this.sneakInput = this.sneakKeyDown;
        if (this.sneakInput) {
            this.moveStrafe = (float) ((double) this.moveStrafe * 0.3);
            this.moveForward = (float) ((double) this.moveForward * 0.3);
        }
    }

    /** The local player's {@code pushOutOfBlocks}: nudges out of a block you are standing inside. */
    private void pushOutOfBlocks(double x, double y, double z) {
        BlockPos pos = new BlockPos(x, y, z);
        double offsetX = x - (double) pos.getX();
        double offsetZ = z - (double) pos.getZ();
        if (this.isOpenBlockSpace(pos)) {
            return;
        }
        int direction = -1;
        double nearest = 9999.0;
        if (this.isOpenBlockSpace(pos.west()) && offsetX < nearest) {
            nearest = offsetX;
            direction = 0;
        }
        if (this.isOpenBlockSpace(pos.east()) && 1.0 - offsetX < nearest) {
            nearest = 1.0 - offsetX;
            direction = 1;
        }
        if (this.isOpenBlockSpace(pos.north()) && offsetZ < nearest) {
            nearest = offsetZ;
            direction = 4;
        }
        if (this.isOpenBlockSpace(pos.south()) && 1.0 - offsetZ < nearest) {
            direction = 5;
        }
        float push = 0.1F;
        if (direction == 0) {
            this.simulatedPlayer.motionX = -push;
        }
        if (direction == 1) {
            this.simulatedPlayer.motionX = push;
        }
        if (direction == 4) {
            this.simulatedPlayer.motionZ = -push;
        }
        if (direction == 5) {
            this.simulatedPlayer.motionZ = push;
        }
    }

    private boolean isOpenBlockSpace(BlockPos pos) {
        return !this.world.getBlockState(pos).getBlock().isNormalCube()
                && !this.world.getBlockState(pos.up()).getBlock().isNormalCube();
    }

    /**
     * {@code EntityLivingBase.moveEntityWithHeading} for 1.8.9, which the game only runs for players
     * it controls; the original's {@code PlayerSimulationUtil.g}.
     */
    static void moveEntityWithHeading(EntityPlayer player, World world, float strafe, float forward) {
        player.noClip = false;
        if (!player.isInWater() || player.capabilities.isFlying) {
            if (!player.isInLava() || player.capabilities.isFlying) {
                float friction = 0.91F;
                if (player.onGround) {
                    friction = groundSlipperiness(player, world) * 0.91F;
                }
                float acceleration = 0.16277136F / (friction * friction * friction);
                float speed = player.onGround ? player.getAIMoveSpeed() * acceleration : player.jumpMovementFactor;
                player.moveFlying(strafe, forward, speed);
                friction = 0.91F;
                if (player.onGround) {
                    friction = groundSlipperiness(player, world) * 0.91F;
                }
                if (player.isOnLadder()) {
                    float ladderSpeed = 0.15F;
                    player.motionX = MathHelper.clamp_double(player.motionX, (double) (-ladderSpeed), (double) ladderSpeed);
                    player.motionZ = MathHelper.clamp_double(player.motionZ, (double) (-ladderSpeed), (double) ladderSpeed);
                    player.fallDistance = 0.0F;
                    if (player.motionY < -0.15) {
                        player.motionY = -0.15;
                    }
                    if (player.isSneaking() && player.motionY < 0.0) {
                        player.motionY = 0.0;
                    }
                }
                player.moveEntity(player.motionX, player.motionY, player.motionZ);
                if (player.isCollidedHorizontally && player.isOnLadder()) {
                    player.motionY = 0.2;
                }
                BlockPos column = new BlockPos((int) player.posX, 0, (int) player.posZ);
                boolean unloaded = !world.isBlockLoaded(column) || !world.getChunkFromBlockCoords(column).isLoaded();
                if (world.isRemote && unloaded) {
                    player.motionY = player.posY > 0.0 ? -0.1 : 0.0;
                } else {
                    player.motionY -= 0.08;
                }
                player.motionY *= (double) 0.98F;
                player.motionX *= (double) friction;
                player.motionZ *= (double) friction;
            } else {
                double startY = player.posY;
                player.moveFlying(strafe, forward, 0.02F);
                player.moveEntity(player.motionX, player.motionY, player.motionZ);
                player.motionX *= 0.5;
                player.motionY *= 0.5;
                player.motionZ *= 0.5;
                player.motionY -= 0.02;
                if (player.isCollidedHorizontally && player.isOffsetPositionInLiquid(player.motionX,
                        player.motionY + (double) 0.6F - player.posY + startY, player.motionZ)) {
                    player.motionY = (double) 0.3F;
                }
            }
        } else {
            double startY = player.posY;
            float drag = 0.8F;
            float speed = 0.02F;
            float depthStrider = (float) net.minecraft.enchantment.EnchantmentHelper.getDepthStriderModifier(player);
            if (depthStrider > 3.0F) {
                depthStrider = 3.0F;
            }
            if (!player.onGround) {
                depthStrider *= 0.5F;
            }
            if (depthStrider > 0.0F) {
                drag += (0.54600006F - drag) * depthStrider / 3.0F;
                speed += (player.getAIMoveSpeed() * 1.0F - speed) * depthStrider / 3.0F;
            }
            player.moveFlying(strafe, forward, speed);
            player.moveEntity(player.motionX, player.motionY, player.motionZ);
            player.motionX *= (double) drag;
            player.motionY *= (double) 0.8F;
            player.motionZ *= (double) drag;
            player.motionY -= 0.02;
            if (player.isCollidedHorizontally && player.isOffsetPositionInLiquid(player.motionX,
                    player.motionY + (double) 0.6F - player.posY + startY, player.motionZ)) {
                player.motionY = (double) 0.3F;
            }
        }
    }

    private static float groundSlipperiness(EntityPlayer player, World world) {
        BlockPos below = new BlockPos(MathHelper.floor_double(player.posX),
                MathHelper.floor_double(player.getEntityBoundingBox().minY) - 1, MathHelper.floor_double(player.posZ));
        Block block = world.getBlockState(below).getBlock();
        return block.slipperiness;
    }

    // ---------------------------------------------------------------- tracing

    /**
     * What the rehearsal's crosshair is on: through the silent rotation when there is one, through
     * its view otherwise. The original's {@code rayTrace}.
     */
    public MovingObjectPosition rayTrace(double entityReach) {
        SimulatedPlayer player = this.simulatedPlayer;
        if (this.hasSilentRotation()) {
            float savedRenderYaw = player.rotationYaw;
            float savedHeadYaw = player.rotationYawHead;
            float savedPitch = player.rotationPitch;
            player.rotationYaw = this.simulatedYaw;
            player.rotationYawHead = this.simulatedYaw;
            player.rotationPitch = this.simulatedPitch;
            MovingObjectPosition result = MouseOver.trace(this.world, player, entityReach, this.blockReach, this.localPlayer);
            player.rotationYaw = savedRenderYaw;
            player.rotationYawHead = savedHeadYaw;
            player.rotationPitch = savedPitch;
            return result;
        }
        player.rotationYawHead = player.rotationYaw;
        return MouseOver.trace(this.world, player, entityReach, this.blockReach, this.localPlayer);
    }

    // ---------------------------------------------------------------- state

    public SimulatedPlayer getSimulatedPlayer() {
        return this.simulatedPlayer;
    }

    public Vec3 getSimulatedPosition() {
        return new Vec3(this.simulatedPlayer.posX, this.simulatedPlayer.posY, this.simulatedPlayer.posZ);
    }

    /** The rotation the rehearsal is looking with: its silent one if it has one. */
    public float getSimulatedYaw() {
        return this.hasSilentRotation() ? this.simulatedYaw : this.simulatedPlayer.rotationYaw;
    }

    public boolean hasDirectionalInput() {
        return this.forwardKeyDown || this.backwardKeyDown || this.leftKeyDown || this.rightKeyDown;
    }

    public boolean isForwardKeyDown() {
        return this.forwardKeyDown;
    }

    public boolean isBackwardKeyDown() {
        return this.backwardKeyDown;
    }

    public boolean isLeftKeyDown() {
        return this.leftKeyDown;
    }

    public boolean isRightKeyDown() {
        return this.rightKeyDown;
    }

    public boolean isJumpKeyDown() {
        return this.jumpKeyDown;
    }

    public boolean isSneakKeyDown() {
        return this.sneakKeyDown;
    }

    public boolean isSprintKeyDown() {
        return this.sprintKeyDown;
    }

    public boolean isSneaking() {
        return this.sneakInput;
    }

    public boolean isJumpInput() {
        return this.jumpInput;
    }

    public float getMoveForward() {
        return this.moveForward;
    }

    public float getMoveStrafe() {
        return this.moveStrafe;
    }

    public int getSprintToggleTimer() {
        return this.sprintToggleTimer;
    }

    public int getSprintingTicksLeft() {
        return this.sprintingTicksLeft;
    }

    public void setSprintKeyDown(boolean sprintKeyDown) {
        this.sprintKeyDown = sprintKeyDown;
    }
}
