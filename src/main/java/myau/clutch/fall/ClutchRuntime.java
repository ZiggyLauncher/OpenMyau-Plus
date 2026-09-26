package myau.clutch.fall;

import myau.clutch.PlacementTarget;
import myau.rotation.mouse.MouseAim;
import myau.rotation.mouse.MouseRotator;
import myau.rotation.mouse.ReturnRotator;
import myau.rotation.mouse.SilentRotator;
import myau.rotation.mouse.TargetRotator;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovementInput;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Everything Vape's Clutch module does around its planner, tick by tick: watching for a fall
 * worth saving, choosing the block slot, clicking when the crosshair is on the planned face,
 * steering your keys while the view turns, reacting to knockback, teleports and refused
 * placements, and afterwards looking back, returning to your slot, and holding still for a moment
 * after a hard clutch.
 * <p>
 * It runs against a {@link ClutchHost}, so the module and the tests share this code.
 */
public final class ClutchRuntime {
    public static final List<String> DEFAULT_BLOCK_NAMES = Arrays.asList(
            "Wool", "Stone", "Wood Planks", "Red Sandstone", "Stained Clay", "End Stone", "Obsidian");

    // ---------------------------------------------------------------- settings, set by the module
    public boolean onVoid = true;
    public boolean onLethalFall = true;
    public boolean onMoreThanXBlocks;
    public int blocksThreshold = 6;
    public float speed = 3.5F;
    public boolean silentAim;
    public boolean resetAngle = true;
    public int resetAngleDelayMin = 3;
    public int resetAngleDelayMax = 6;
    public boolean returnToLastSlot = true;
    public int returnDelayMin = 3;
    public int returnDelayMax = 6;
    public int moveDelayMin = 3;
    public int moveDelayMax = 6;
    public int failDelay = 100;
    public boolean allowStaircaseUp = true;
    public boolean limitBlocks;
    public int maxBlocks = 5;
    public boolean heldWhitelist;
    /** Whether a stack may be clutched with at all (block, and not blacklisted). */
    public Predicate<ItemStack> validBlock = stack -> stack != null && stack.getItem() instanceof ItemBlock;
    /** Whether a held stack passes the held-block whitelist. */
    public Predicate<ItemStack> whitelisted = stack -> stack != null && stack.getItem() instanceof ItemBlock;
    /** A stack's name as shown, for the preferred-block order. */
    public Function<ItemStack, String> displayName = ItemStack::getDisplayName;
    /** Milliseconds, for the fail, landing and staircase timers. */
    public LongSupplier clock = System::currentTimeMillis;

    public final ClutchPlanner planner = new ClutchPlanner();
    public final AimDriver driver = new AimDriver();
    private final ClutchHost host;

    private ClutchPath clutchPath;
    private PlacementTarget placeTarget;

    private boolean inputForward;
    private boolean inputBack;
    private boolean inputLeft;
    private boolean inputRight;
    private boolean[] pendingInputs;
    private boolean pendingInputApply;
    private boolean freezeMovement;

    private double savedYaw = -999.0;
    private double savedPitch = -999.0;
    private int previousSlot = -1;
    private int moveDelayTicks;
    private int returnDelayTicks;
    private int resetAngleDelayTicks;
    private int groundStuckTicks;
    private boolean prevJumpHeld;
    private boolean pauseRequested;
    private long landTimer;
    private long failTimer;
    private long staircaseTimer;
    private String pendingFailMessage;
    private int pendingFailDelayTicks;
    private long lastFailNotification;
    /** Set when the runtime asks to be switched off: a paused silent aim has finished easing back. */
    private boolean disableRequested;

    private volatile boolean knockbackPending;
    private volatile boolean explosionPending;
    private volatile boolean teleportPending;
    private volatile boolean placementRejected;

    public ClutchRuntime(ClutchHost host) {
        this.host = host;
    }

    // ---------------------------------------------------------------- state for the module

    public boolean isClutching() {
        return this.clutchPath != null;
    }

    public ClutchPath getClutchPath() {
        return this.clutchPath;
    }

    public boolean isAiming() {
        return this.driver.getActiveController() != null;
    }

    /** True once, after a paused silent aim has eased back and the module may switch off. */
    public boolean consumeDisableRequest() {
        boolean requested = this.disableRequested;
        this.disableRequested = false;
        return requested;
    }

    /** Switching off with a silent aim running waits for it to ease back; asking twice cancels. */
    public boolean requestPause() {
        if (this.planner.rotationController instanceof SilentRotator) {
            this.pauseRequested = !this.pauseRequested;
            return true;
        }
        this.pauseRequested = false;
        return false;
    }

    public void onEnabled() {
        this.pauseRequested = false;
        this.clearAll();
    }

    public void onDisabled() {
        EntityPlayer player = this.host.player();
        if (player != null) {
            this.resetRotation(player);
        }
        this.clearAll();
    }

    public void onWorldChange() {
        this.driver.clear();
        this.planner.rotationController = null;
        SimulatedPlayer.clearPool();
        this.clutchPath = null;
        this.placeTarget = null;
        this.planner.placedBlocks.clear();
    }

    private void clearAll() {
        this.planner.counterMotion = false;
        this.planner.forcingCounterMotion = false;
        this.resetAngleDelayTicks = 0;
        this.moveDelayTicks = 0;
        this.returnDelayTicks = 0;
        this.freezeMovement = false;
        this.resetState();
    }

    // ---------------------------------------------------------------- packets, from any thread

    public void noteKnockback() {
        this.knockbackPending = true;
    }

    public void noteExplosion() {
        this.explosionPending = true;
    }

    public void noteTeleport() {
        this.teleportPending = true;
    }

    /** A block set to air by the server: a refusal, if it is one of the planned positions. */
    public void noteBlockCleared(BlockPos pos) {
        ClutchPath path = this.clutchPath;
        if (path != null && path.hasPlan() && path.containsPosition(pos)) {
            this.planner.placedBlocks.add(pos);
            this.placementRejected = true;
        }
    }

    // ---------------------------------------------------------------- the tick

    public void preTick() {
        EntityPlayer player = this.host.player();
        World world = this.host.world();
        if (player == null || world == null) {
            this.resetState();
            return;
        }
        this.drainPackets(player);
        if (this.placementRejected) {
            this.showFailNotification("Server rejected block placement!", true);
            this.resetClutch(player);
            this.placementRejected = false;
        }
        this.tickSlotAndReset(player);
        if (player.onGround && player.motionY == ClutchPlanner.STATIONARY_FALL_MOTION) {
            this.planner.fallTargetY = player.posY;
        }
        boolean forwardPressed = this.host.forwardPhysicallyDown();
        this.bindPlanner(player, world);
        this.planner.graph = this.host.snapshot();
        if (this.pendingInputApply) {
            this.planner.graph.forwardKeyDown = this.pendingInputs[0];
            this.planner.graph.backwardKeyDown = this.pendingInputs[1];
            this.planner.graph.leftKeyDown = this.pendingInputs[2];
            this.planner.graph.rightKeyDown = this.pendingInputs[3];
            this.pendingInputs = null;
            this.pendingInputApply = false;
        }
        this.planner.graph.forwardKeyDown = forwardPressed;
        if (this.pauseRequested || this.host.screenOpen()) {
            this.resetState();
            return;
        }
        ItemStack blockItem = this.findBlockItem(player);
        if (player.capabilities.isFlying || player.isOnLadder() || player.isRiding() || blockItem == null) {
            this.resetState();
            return;
        }
        if (!this.onVoid && !this.onLethalFall && !this.onMoreThanXBlocks) {
            this.resetState();
            return;
        }
        boolean jumpPressed = this.host.jumpPhysicallyDown();
        if (this.clutchPath != null) {
            if (player.onGround) {
                if (++this.groundStuckTicks >= 5 || !this.wouldLeaveGround(player)) {
                    this.resetState();
                }
            } else {
                this.groundStuckTicks = 0;
            }
        }
        if (this.allowStaircaseUp) {
            if (jumpPressed) {
                if (!this.prevJumpHeld && this.clock.getAsLong() - this.staircaseTimer < 500L) {
                    this.planner.staircaseQueued = true;
                }
            } else if (this.prevJumpHeld != jumpPressed) {
                if (this.planner.staircaseQueued) {
                    this.planner.staircaseQueued = false;
                } else {
                    this.staircaseTimer = this.clock.getAsLong();
                }
            }
            this.prevJumpHeld = jumpPressed;
        } else {
            this.prevJumpHeld = false;
            this.planner.staircaseQueued = false;
        }
        if (this.clutchPath != null) {
            if (player.posY < (double) this.clutchPath.start.getY()) {
                this.resetState();
            } else {
                double targetX = (double) this.clutchPath.target.getX() + 0.5;
                double targetZ = (double) this.clutchPath.target.getZ() + 0.5;
                double currentDistance = Math.hypot(player.posX - targetX, player.posZ - targetZ);
                double previousDistance = Math.hypot(player.prevPosX - targetX, player.prevPosZ - targetZ);
                if (currentDistance > previousDistance && currentDistance > 1.2 && !player.onGround) {
                    this.resetState();
                }
            }
        }
        if (isMoving(player) && this.clutchPath == null) {
            this.checkForFall(player, world, blockItem, jumpPressed);
        }
        this.tickFailDelay();
        if (this.clutchPath != null) {
            this.placeTarget = null;
            if (this.clutchPath.hasPlan()) {
                this.placeTarget = this.planner.resolvePlaceTarget(this.clutchPath, player, null);
                if (this.placeTarget == null && this.clutchPath.hasPlan()) {
                    this.clutchPath.fail("Failed to find a place target");
                }
            } else if (!this.planner.forcingCounterMotion || this.planner.takingKnockback) {
                this.resetState();
                return;
            }
        } else {
            this.resetState();
            return;
        }
        if (this.placeTarget != null && this.selectBlockSlot(player)) {
            if (this.driver.getActiveController() == null) {
                this.planner.rotationController = null;
            }
            float currentYaw = this.currentYaw(player);
            if (this.isLookingAtTarget(player, world)) {
                this.place(player, world);
                if (this.clutchPath.hasPlan()) {
                    this.advanceAfterPlacement(player, world, currentYaw);
                }
            }
        }
    }

    /** The original's trigger: is this fall one to save, and if so, is there a way? */
    private void checkForFall(EntityPlayer player, World world, ItemStack blockItem, boolean jumpPressed) {
        boolean fallingOrRising = !player.onGround || player.motionY >= 0.0;
        if (!fallingOrRising || player.isOnLadder() || player.isRiding() || player.isInWater()
                || this.clock.getAsLong() - this.failTimer < (long) this.failDelay) {
            return;
        }
        BlockPos landingBlock = this.planner.findLandingBlock(50, player, null);
        boolean fallingIntoVoid = false;
        boolean lethalFall = false;
        boolean exceedsBlockThreshold = false;
        if (landingBlock != null) {
            if (this.onLethalFall && player.posY - (double) landingBlock.getY() - 3.0 > (double) player.getHealth()) {
                lethalFall = true;
            }
            if (this.onMoreThanXBlocks && player.posY - (double) (landingBlock.getY() + 1) >= (double) this.blocksThreshold) {
                exceedsBlockThreshold = true;
            }
        } else {
            fallingIntoVoid = this.onVoid;
        }
        if (!fallingIntoVoid && !lethalFall && !exceedsBlockThreshold) {
            return;
        }
        ClutchPath path = this.computeClutchPath(player, blockItem);
        if (path != null && !path.hasFailed()) {
            this.captureMovementInputs();
            this.resetPendingFail();
            this.groundStuckTicks = 0;
            this.resetAngleDelayTicks = 0;
            this.moveDelayTicks = 0;
            this.returnDelayTicks = 0;
            this.clutchPath = path;
            this.prevJumpHeld = jumpPressed;
            this.host.releaseAttackKey();
            if (!this.silentAim) {
                if (this.savedYaw == -999.0) {
                    this.savedYaw = player.rotationYaw;
                    this.savedPitch = player.rotationPitch;
                }
            } else {
                this.savedYaw = -999.0;
            }
            return;
        }
        String failureMessage = null;
        if (path == null) {
            if (!this.planner.blockGraphMap.isEmpty()) {
                failureMessage = "Could not find a clutch path!";
            }
        } else {
            failureMessage = path.failureReason != null ? path.failureReason : "Could not find a clutch path!";
        }
        if (failureMessage != null && !failureMessage.isEmpty()) {
            this.queueFailMessage(failureMessage);
        }
        this.resetMovementInputs();
        this.clutchPath = null;
        this.failTimer = this.clock.getAsLong();
    }

    private ClutchPath computeClutchPath(EntityPlayer player, ItemStack blockItem) {
        ClutchPlanner planner = this.planner;
        planner.rejectedBlocks.clear();
        planner.placeableBlocks.clear();
        planner.blockGraphMap.clear();
        planner.placeYaw = this.savedYaw != -999.0 ? (float) this.savedYaw : player.rotationYaw;
        planner.originalYaw = planner.placeYaw;
        planner.counterMotion = false;
        planner.forcingCounterMotion = false;
        planner.recentlyClutched = this.clock.getAsLong() - this.landTimer < 250L;
        planner.jumpKeyPhysicallyDown = this.host.jumpPhysicallyDown();
        ClutchPath path;
        MovementSimulation search = planner.simulation(player, planner.graph);
        try {
            path = planner.searchClutchPath(search, blockItem);
        } finally {
            search.close();
        }
        if (path != null && !path.hasFailed() && this.limitBlocks) {
            int requiredBlocks = path.getPendingPlacementCount();
            if (requiredBlocks > this.maxBlocks) {
                path.fail("Requires " + requiredBlocks + " blocks (max: " + this.maxBlocks + ")");
            }
        }
        return path;
    }

    /** After a click: on to the next block, or, with none left while pushing back, turn to face forward again. */
    private void advanceAfterPlacement(EntityPlayer player, World world, float currentYaw) {
        this.placeTarget = null;
        Vector<PlacementTarget> pendingTargets = this.clutchPath.pendingTargets;
        if (!pendingTargets.isEmpty()) {
            pendingTargets.removeElementAt(0);
        }
        while (!pendingTargets.isEmpty()) {
            PlacementTarget candidate = pendingTargets.firstElement();
            BlockPos placed = candidate.getPlacedBlock();
            if (FallBlocks.isReplaceable(FallBlocks.blockAt(world, placed))) {
                if (FallBlocks.isPlacementSpaceClear(world, player, placed)) {
                    this.placeTarget = candidate;
                    break;
                }
                this.clutchPath.fail("Entity blocking placement");
                break;
            }
            this.clutchPath.hitVector = null;
            pendingTargets.removeElementAt(0);
        }
        TargetRotator controller = this.planner.rotationController;
        if (this.placeTarget == null && this.planner.forcingCounterMotion && controller != null) {
            float targetYaw = this.planner.takingKnockback ? this.planner.originalYaw : this.planner.placeYaw;
            float yawDistance = Math.abs(MouseAim.wrap(targetYaw - currentYaw));
            controller.setSpeed(yawDistance / 1.8F / 3.0F);
            controller.setTargetRotation(targetYaw, controller.getTargetPitch());
        }
    }

    public void postTick() {
        EntityPlayer player = this.host.player();
        World world = this.host.world();
        if (player == null || world == null) {
            this.resetState();
            return;
        }
        ClutchPlanner planner = this.planner;
        if (planner.knockbackTicks > 0) {
            --planner.knockbackTicks;
        }
        // Back to the original heading once knockback is over - at once, if you have landed.
        if (this.clutchPath != null && planner.forcingCounterMotion && planner.takingKnockback
                && (player.onGround || planner.knockbackTicks == 0)) {
            planner.placeYaw = planner.originalYaw;
        }
        this.bindPlanner(player, world);
        MovementSnapshot currentGraph = this.host.snapshot();
        if (this.clutchPath != null && this.clutchPath.hasPlan() && this.placeTarget != null) {
            float[] rotation = this.currentRotation(player);
            int placementTicks = planner.simulatePlacementTick(this.clutchPath, this.placeTarget, player,
                    rotation[0], rotation[1], currentGraph);
            planner.rotationController = planner.buildRotation(player, this.clutchPath.hitVector,
                    this.placeTarget.hitPoint, planner.rotationController, placementTicks, planner.placeYaw);
            this.driver.setController(planner.rotationController);
        }
    }

    /** Hands the planner the live context it plans against. */
    private void bindPlanner(EntityPlayer player, World world) {
        ClutchPlanner planner = this.planner;
        planner.world = world;
        planner.localPlayer = player;
        planner.localInput = this.host.localInput();
        planner.blockReach = this.host.blockReach();
        planner.rotationAccumulator = this.driver.getAccumulator();
        planner.liveSilent = this.driver.silent();
        planner.silentAim = this.silentAim;
        planner.speed = this.speed;
        planner.onVoid = this.onVoid;
        planner.onMoreThanXBlocks = this.onMoreThanXBlocks;
    }

    private float[] currentRotation(EntityPlayer player) {
        TargetRotator controller = this.planner.rotationController;
        if (controller instanceof SilentRotator) {
            SilentRotator silent = (SilentRotator) controller;
            return new float[]{silent.getRenderedYaw(), silent.getRenderedPitch()};
        }
        if (controller != null) {
            return new float[]{controller.getCurrentYaw(), controller.getCurrentPitch()};
        }
        return new float[]{player.rotationYaw, player.rotationPitch};
    }

    private float currentYaw(EntityPlayer player) {
        TargetRotator controller = this.planner.rotationController;
        if (controller == null) {
            SilentRotator live = this.driver.silent();
            return live != null ? live.getRenderedYaw() : player.rotationYaw;
        }
        return controller instanceof SilentRotator ? ((SilentRotator) controller).getRenderedYaw() : controller.getCurrentYaw();
    }

    // ---------------------------------------------------------------- clicking

    /** What a click this tick would hit: through the rotation the server is about to get. */
    public MovingObjectPosition traceSentRotation(EntityPlayer player, World world) {
        float savedYaw = player.rotationYaw;
        float savedPitch = player.rotationPitch;
        float savedHeadYaw = player.rotationYawHead;
        if (this.driver.hasSilent()) {
            player.rotationYaw = this.driver.getManagedYaw();
            player.rotationPitch = this.driver.getManagedPitch();
        }
        player.rotationYawHead = player.rotationYaw;
        try {
            return MouseOver.trace(world, player, 3.0, this.planner.blockReach, player);
        } finally {
            player.rotationYaw = savedYaw;
            player.rotationPitch = savedPitch;
            player.rotationYawHead = savedHeadYaw;
        }
    }

    private boolean isLookingAtTarget(EntityPlayer player, World world) {
        if (this.placeTarget == null) {
            return false;
        }
        AxisAlignedBB placement = FallBlocks.bounds(world, this.placeTarget.getPlacedBlock());
        if (player.getEntityBoundingBox().intersectsWith(placement)) {
            return false;
        }
        return ClutchPlanner.isPlacementHit(this.traceSentRotation(player, world), this.placeTarget);
    }

    private void place(EntityPlayer player, World world) {
        this.host.releaseAttackKey();
        MovingObjectPosition hit = this.traceSentRotation(player, world);
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return;
        }
        if (FallBlocks.isAir(FallBlocks.blockAt(world, hit.getBlockPos()))) {
            return;
        }
        this.host.rightClick(hit);
    }

    // ---------------------------------------------------------------- movement

    /**
     * Sets this tick's movement: held still after a hard clutch; during one, your keys as they
     * were, turned so you keep going where you were going; and with a silent aim, corrected
     * against the reported yaw.
     *
     * @param facingYaw the yaw the player is facing as the movement is worked out
     */
    public void moveInput(MovementInput input, float facingYaw) {
        if (this.host.screenOpen()) {
            return;
        }
        if (this.freezeMovement) {
            input.moveForward = 0.0F;
            input.moveStrafe = 0.0F;
            input.jump = false;
            input.sneak = false;
            return;
        }
        boolean[] keys = null;
        if (this.clutchPath != null) {
            boolean[] corrected = null;
            TargetRotator controller = this.planner.rotationController;
            if (controller != null && !(controller instanceof SilentRotator)) {
                corrected = keysFor(facingYaw, this.planner.placeYaw, this.inputForward, this.inputBack,
                        this.inputLeft, this.inputRight);
            }
            if (corrected != null) {
                this.pendingInputApply = true;
                this.pendingInputs = corrected;
            }
            keys = corrected != null ? corrected
                    : new boolean[]{this.inputForward, this.inputBack, this.inputLeft, this.inputRight};
            input.jump = !(this.planner.forcingCounterMotion && !this.planner.takingKnockback) && this.planner.graph.jumpInput;
        }
        SilentRotator silent = this.driver.silent();
        if (silent != null) {
            boolean[] held = keys != null ? keys : this.host.heldMovementKeys();
            if (held[0] || held[1] || held[2] || held[3]) {
                float movementYaw = MovementSimulation.movementYaw(silent.getReferenceYaw(), held[0], held[1], held[2], held[3]);
                keys = MovementSimulation.keysToward(this.driver.getManagedYaw(), movementYaw, 0.4F);
            }
        }
        if (keys != null) {
            float forward = (keys[0] ? 1.0F : 0.0F) - (keys[1] ? 1.0F : 0.0F);
            float strafe = (keys[2] ? 1.0F : 0.0F) - (keys[3] ? 1.0F : 0.0F);
            if (input.sneak) {
                forward = (float) ((double) forward * 0.3);
                strafe = (float) ((double) strafe * 0.3);
            }
            input.moveForward = forward;
            input.moveStrafe = strafe;
        }
    }

    /** {@link ClutchPlanner#computeStrafeState}, for a facing given outright. */
    private static boolean[] keysFor(float facingYaw, float targetYaw, boolean forward, boolean backward,
                                     boolean left, boolean right) {
        if (!forward && !backward && !left && !right) {
            return null;
        }
        float movementYaw = MovementSimulation.movementYaw(targetYaw, forward, backward, left, right);
        return MovementSimulation.keysToward(facingYaw, movementYaw, 0.45F);
    }

    /**
     * The rotation to report this tick, or null to leave it: the silent one; or, while a visible
     * clutch aim runs, the real view - over any other module's, as the original's rotation claim
     * keeps them out until it is done.
     *
     * @return {yaw, pitch}
     */
    public float[] reportedRotation(EntityPlayer player) {
        if (this.driver.hasSilent()) {
            return new float[]{this.driver.getManagedYaw(), this.driver.getManagedPitch()};
        }
        if (this.driver.getActiveController() != null) {
            return new float[]{player.rotationYaw, player.rotationPitch};
        }
        return null;
    }

    private void captureMovementInputs() {
        if (this.planner.counterMotion) {
            this.inputForward = true;
            this.inputBack = false;
            this.inputLeft = false;
            this.inputRight = false;
        } else {
            this.inputForward = this.planner.graph.forwardKeyDown;
            this.inputBack = this.planner.graph.backwardKeyDown;
            this.inputLeft = this.planner.graph.leftKeyDown;
            this.inputRight = this.planner.graph.rightKeyDown;
        }
    }

    private void resetMovementInputs() {
        this.inputForward = false;
        this.inputRight = false;
        this.inputLeft = false;
        this.inputBack = false;
    }

    private boolean wouldLeaveGround(EntityPlayer player) {
        MovementSimulation landing = this.planner.simulation(player, this.planner.graph);
        try {
            return this.planner.simulateLandsOnTarget(player, landing, this.clutchPath);
        } finally {
            landing.close();
        }
    }

    private static boolean isMoving(EntityPlayer player) {
        return player.motionX != 0.0 || player.motionY != 0.0 || player.motionZ != 0.0;
    }

    // ---------------------------------------------------------------- after a clutch

    private void tickSlotAndReset(EntityPlayer player) {
        if (this.clutchPath != null) {
            return;
        }
        if (this.moveDelayTicks-- >= 0) {
            if (this.moveDelayTicks <= 0) {
                this.freezeMovement = false;
                this.moveDelayTicks = -1;
            } else {
                this.freezeMovement = true;
            }
        }
        if (this.returnToLastSlot && this.previousSlot != -1 && this.returnDelayTicks-- <= 0) {
            player.inventory.currentItem = this.previousSlot;
            this.previousSlot = -1;
        }
        if (this.resetAngleDelayTicks >= 0) {
            if (this.resetAngleDelayTicks-- <= 0) {
                this.resetRotation(player);
            } else if (this.planner.rotationController != null && !this.planner.rotationController.isComplete()) {
                this.planner.rotationController.setSpeed(this.speed / (float) this.resetAngleDelayTicks);
                this.planner.rotationController.setRandomizeMovement(true);
            }
        }
    }

    /**
     * Hands the aim back: with "reset angle", a visible aim turns back to where you were looking
     * when the clutch started; otherwise the controller is released to finish on its own.
     */
    private void resetRotation(EntityPlayer player) {
        TargetRotator controller = this.planner.rotationController;
        boolean releaseCurrentRotation = true;
        if (this.resetAngle && !this.silentAim && this.savedYaw != -999.0 && controller != null) {
            this.driver.releaseController(controller);
            this.planner.rotationController = null;
            float wrappedYawDelta = MouseAim.wrap(player.rotationYaw - (float) this.savedYaw);
            float resetSpeed = Math.max(wrappedYawDelta / 90.0F * 5.0F, 1.0F);
            float yawDelta = player.rotationYaw - (float) this.savedYaw;
            ReturnRotator reset = new ReturnRotator(player, yawDelta, player.rotationPitch - (float) this.savedPitch,
                    () -> this.savedYaw = -999.0);
            reset.setRandomizeMovement(true);
            reset.setScaleAxesProportionally(true);
            reset.setLinearAcceleration(true);
            reset.setSpeed(resetSpeed);
            this.driver.setController(reset);
            releaseCurrentRotation = false;
        }
        if (releaseCurrentRotation && controller != null) {
            controller.setClampStepToRemaining(true);
            controller.setCubicAcceleration(true);
            controller.setScaleAxesProportionally(true);
            controller.setTolerance(0.0F);
            controller.setSpeed(3.0F);
            this.driver.releaseController(controller);
        }
    }

    /** Drops the path in progress; the aim is handed back once the delay is up. */
    private void resetClutch(EntityPlayer player) {
        this.clutchPath = null;
        this.placeTarget = null;
        this.returnDelayTicks = randomBetween(this.returnDelayMin, this.returnDelayMax);
        if (this.planner.rotationController != null) {
            this.resetRotation(player);
        }
    }

    /** The original's {@code resetState}: everything back to watching for the next fall. */
    private void resetState() {
        if (this.pendingInputApply) {
            this.pendingInputs = null;
            this.pendingInputApply = false;
        }
        if (this.clutchPath != null) {
            if (this.planner.knockbackTicks <= 0) {
                this.planner.takingKnockback = false;
                this.planner.forcingCounterMotion = false;
            }
            if (this.planner.forcingCounterMotion && !this.planner.takingKnockback && this.moveDelayMax > 0) {
                this.resetMovementInputs();
                this.freezeMovement = true;
                this.planner.forcingCounterMotion = false;
                this.moveDelayTicks = randomBetween(this.moveDelayMin, this.moveDelayMax);
            }
            this.returnDelayTicks = randomBetween(this.returnDelayMin, this.returnDelayMax);
            this.resetAngleDelayTicks = randomBetween(this.resetAngleDelayMin, this.resetAngleDelayMax);
            this.landTimer = this.clock.getAsLong();
        }
        this.clutchPath = null;
        this.placeTarget = null;
        this.planner.blockGraphMap.clear();
        MouseRotator active = this.driver.getActiveController();
        TargetRotator controller = this.planner.rotationController;
        if (active == null || active != controller
                || controller != null && !controller.shouldRetainAfterCompletion() && controller.isComplete()) {
            this.planner.rotationController = null;
            if (this.pauseRequested) {
                this.pauseRequested = false;
                this.disableRequested = true;
            }
        }
        this.planner.forcingCounterMotion = false;
        this.planner.counterMotion = false;
    }

    private void drainPackets(EntityPlayer player) {
        if (this.knockbackPending) {
            this.knockbackPending = false;
            this.planner.takingKnockback = true;
            this.planner.knockbackTicks = 4;
            this.resetClutch(player);
        }
        if (this.explosionPending) {
            this.explosionPending = false;
            this.resetClutch(player);
        }
        if (this.teleportPending) {
            this.teleportPending = false;
            if (this.clutchPath != null && this.clutchPath.hasPlan()) {
                this.showFailNotification("Server teleported you!", true);
                this.resetClutch(player);
            }
        }
    }

    // ---------------------------------------------------------------- blocks

    /** The block the clutch would use, or null for none - which also means no clutch. */
    private ItemStack findBlockItem(EntityPlayer player) {
        if (this.heldWhitelist) {
            ItemStack held = player.getHeldItem();
            return this.whitelisted.test(held) ? held : null;
        }
        for (int i = 0; i < 9; ++i) {
            ItemStack stack = player.inventory.getStackInSlot(i);
            if (stack == null || !(stack.getItem() instanceof ItemBlock)) {
                continue;
            }
            if (this.validBlock.test(stack)) {
                return stack;
            }
            for (String preferredName : DEFAULT_BLOCK_NAMES) {
                if (this.displayName.apply(stack).contains(preferredName)) {
                    return stack;
                }
            }
        }
        return null;
    }

    private int findBestBlockSlot(EntityPlayer player) {
        List<Integer> validSlots = new ArrayList<Integer>();
        for (int i = 0; i < 9; ++i) {
            ItemStack stack = player.inventory.getStackInSlot(i);
            if (stack != null && this.validBlock.test(stack)) {
                validSlots.add(i);
            }
        }
        if (validSlots.isEmpty()) {
            return -1;
        }
        for (String preferredName : DEFAULT_BLOCK_NAMES) {
            for (int slot : validSlots) {
                if (this.displayName.apply(player.inventory.getStackInSlot(slot)).contains(preferredName)) {
                    return slot;
                }
            }
        }
        return validSlots.get(0);
    }

    private boolean selectBlockSlot(EntityPlayer player) {
        int slot = this.findBestBlockSlot(player);
        if (slot == -1) {
            return false;
        }
        if (this.previousSlot == -1) {
            this.previousSlot = player.inventory.currentItem;
        }
        player.inventory.currentItem = slot;
        return true;
    }

    /** Blocks in the hotbar the clutch could use, for the module's display. */
    public int countBlocks(EntityPlayer player) {
        if (this.heldWhitelist) {
            ItemStack held = player.getHeldItem();
            return this.whitelisted.test(held) && this.validBlock.test(held) ? held.stackSize : 0;
        }
        int blockCount = 0;
        for (int i = 0; i < 9; ++i) {
            ItemStack stack = player.inventory.getStackInSlot(i);
            if (stack == null || !(stack.getItem() instanceof ItemBlock)) {
                continue;
            }
            if (this.validBlock.test(stack)) {
                blockCount += stack.stackSize;
                continue;
            }
            for (String preferredName : DEFAULT_BLOCK_NAMES) {
                if (this.displayName.apply(stack).contains(preferredName)) {
                    blockCount += stack.stackSize;
                }
            }
        }
        return blockCount;
    }

    // ---------------------------------------------------------------- notifications

    private void resetPendingFail() {
        this.pendingFailMessage = null;
        this.pendingFailDelayTicks = 0;
    }

    private void queueFailMessage(String message) {
        this.pendingFailMessage = message;
        if (this.pendingFailDelayTicks == 0) {
            this.pendingFailDelayTicks = Math.min(3, Math.max(1, this.failDelay / 50));
        }
    }

    private void tickFailDelay() {
        if (this.pendingFailDelayTicks > 0) {
            --this.pendingFailDelayTicks;
            if (this.pendingFailDelayTicks == 0 && this.pendingFailMessage != null) {
                this.showFailNotification(this.pendingFailMessage, false);
                this.pendingFailMessage = null;
            }
        }
    }

    /** One "Clutch failed" notice at a time, refreshed rather than stacked. */
    private void showFailNotification(String message, boolean forceUpdate) {
        long now = this.clock.getAsLong();
        if (!forceUpdate && now - this.lastFailNotification < 3500L) {
            return;
        }
        this.lastFailNotification = now;
        this.host.notifyFailure(message);
    }

    private static int randomBetween(int minimum, int maximum) {
        int low = Math.min(minimum, maximum);
        int high = Math.max(minimum, maximum);
        return high > low ? low + ThreadLocalRandom.current().nextInt(high - low + 1) : low;
    }
}
