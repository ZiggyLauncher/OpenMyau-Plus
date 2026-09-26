package myau.module.modules;

import myau.Myau;
import myau.clutch.BedSearch;
import myau.clutch.ClutchPaths;
import myau.clutch.PathSegment;
import myau.clutch.PlacementNode;
import myau.clutch.PlacementSearch;
import myau.clutch.PlacementTarget;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.MoveInputEvent;
import myau.events.UpdateEvent;
import myau.management.RotationState;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.rotation.Rotation;
import myau.util.ItemUtil;
import myau.util.MoveUtil;
import myau.util.RotationUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovementInput;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Stack;
import java.util.Vector;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Blocks you in: walls on all four sides of the column you stand in, at feet and head height, and
 * a roof above. Ported from the Vape client's module of the same name, "Block-In". Its source
 * calls the class and its helpers Clutch - and calls the fall-saving Clutch BlockIn.
 * <p>
 * It picks the spot needing the fewest blocks - your own column, or an open neighbour that
 * already has a wall beside it - walks you into its centre, then plans each side as a chain of
 * right clicks: one click if there is something to build against, or a support block first if
 * there is not. The bed finder can instead run the enclosure as a short tunnel to the nearest bed.
 * It aims at the exact point on each face it will click, places, and switches itself off when the
 * enclosure is finished or it cannot go on.
 * <p>
 * Differences from the original, all deliberate:
 * <ul>
 *     <li>It walks into the enclosure first and only then aims and places. The original does both
 *     at once; doing them in sequence means a scripted walk is never steered through a silent
 *     rotation, which is a movement mismatch a prediction anticheat flags.</li>
 *     <li>The walk uses the eight directions a keyboard can produce, with a sneak-speed final
 *     approach, instead of an analog input no keyboard can produce.</li>
 *     <li>Three defects in the recovered source are fixed: the path loop could spin forever when a
 *     node had an invalid path followed by a finished one; the slot to return to was never
 *     recorded, so "return to last slot" never did anything; and a column a step below you made
 *     the roof block the place to stand in, so the walk in never finished. That walk now drops
 *     into the lower column, and gives up after two seconds if something is in the way.</li>
 *     <li>Planning and clicking wait while you are in the air: plans are checked from the eye they
 *     will be clicked from, which is the standing one.</li>
 *     <li>Out in the open the original leaves the roof off - nothing up there is clickable from
 *     the ground. The roof-jump option (on by default) jumps once to put a support on a
 *     head-level wall and then places the roof against it.</li>
 *     <li>The original aims with its mouse-step point controller (adaptive when silent), which
 *     eases in with linear acceleration. This port turns at a constant rate - the configured aim
 *     speed, in degrees a tick - instead.</li>
 * </ul>
 */
public class BlockIn extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final int PRIORITY_LOWEST_COST = 0;
    private static final int PRIORITY_HARDEST = 1;
    /** Wins over everything else that rotates, as the original's claim priority 9 does. */
    private static final int ROTATION_PRIORITY = 9;
    /** Further than this from the chosen column, the original gives up rather than walking. */
    private static final double MAX_WALK_DISTANCE = 1.75;
    /** The original's arrival tolerance for the walk into the column. */
    private static final double WALK_TOLERANCE = 0.075;
    /** Inside this the walk slows to sneak speed, so the stop lands in the column rather than past it. */
    private static final double SLOW_APPROACH = 0.5;
    /** How far from the aimed point the crosshair may land and still count as on the face. */
    private static final double HIT_TOLERANCE = 0.3;
    /** Past this many ticks of walking in without arriving, something is in the way. */
    private static final int MAX_WALK_TICKS = 40;
    /** How high the feet are at the top of a jump; the roof jump's aim is checked from there. */
    private static final double JUMP_PEAK = 1.249;
    /**
     * The roof jump looks this far down, straight out over a head-level wall. From the middle of
     * the column that line crosses the wall's top from the third tick of the jump to the eighth.
     */
    private static final float ROOF_JUMP_PITCH = 40.0F;
    /** Ticks a pressed jump may take to leave the ground before the roof is given up. */
    private static final int JUMP_START_TICKS = 3;
    /** For each face index, the two faces perpendicular to it; vertical faces have none. */
    private static final int[][] FACE_OFFSETS = {null, null, {5, 4}, {5, 4}, {2, 3}, {2, 3}};
    private static final List<String> BLOCK_PRIORITY = Collections.unmodifiableList(Arrays.asList(
            "Wool", "Stone", "Wood Planks", "Red Sandstone", "Stained Clay", "End Stone", "Obsidian"));

    public final FloatProperty aimSpeed = new FloatProperty("aim-speed", 12.0F, 1.0F, 25.0F);
    public final IntProperty placeDelayMin = new IntProperty("place-delay-min", 0, 0, 250);
    public final IntProperty placeDelayMax = new IntProperty("place-delay-max", 30, 0, 250);
    public final ModeProperty blockPriority = new ModeProperty("block-priority", PRIORITY_LOWEST_COST,
            new String[]{"Lowest cost", "Hardest"});
    public final BooleanProperty silentAim = new BooleanProperty("silent-aim", false);
    public final BooleanProperty sneak = new BooleanProperty("sneak", false);
    public final BooleanProperty keepSneak = new BooleanProperty("keep-sneak", true, this.sneak::getValue);
    public final BooleanProperty bedFinder = new BooleanProperty("bed-finder", true);
    public final BooleanProperty returnToLastSlot = new BooleanProperty("return-to-last-slot", true);
    public final BooleanProperty useBlacklist = new BooleanProperty("use-blacklist", true);
    /**
     * Not in the original. Out in the open the roof cannot be placed from the ground: it needs a
     * support two blocks up, and every such block needs a top face clicked that sits above your
     * eye. The original quietly leaves the roof off. With this on, once the walls are up a single
     * jump lifts your eye high enough to put a support on a head-level wall, and the roof goes
     * against that support's side after you land.
     */
    public final BooleanProperty roofJump = new BooleanProperty("roof-jump", true);

    private final List<PlacementNode> placementNodes = new ArrayList<PlacementNode>();
    private PlacementTarget currentTarget;
    private int blockSlot = -1;
    private int previousSlot = -1;
    private boolean sneaking;

    /** Set while walking into the chosen column; the walk overrides the movement keys. */
    private boolean walking;
    private double walkX;
    private double walkZ;
    /** The chosen column's floor, which may be a step below where you stand. */
    private double walkFloorY;
    private int walkTicks;

    private long lastClickAt;
    private long lastAimAt;
    /** The delay for the next click, drawn fresh after every placement. */
    private long clickDelay;

    /** The rotation the server last received - what every placement is validated against. */
    private Rotation serverRotation;
    /** Silent aim only: the rotation being reported instead of the real one. */
    private Rotation silent;
    /** Visible aim only: where the per-frame turn is heading, or null to hold still. */
    private volatile Rotation turnTarget;
    private long lastFrameNanos;
    /** Silent aim only: the reported rotation is walking back to the real one before switching off. */
    private boolean finishing;
    /** The side whose head-level wall gets a support for the roof, while the roof jump runs. */
    private EnumFacing roofSide;
    /** The roof jump's aim is close enough and the jump has been pressed. */
    private boolean roofJumpPressed;
    /** The roof jump has left the ground. */
    private boolean roofJumpAirborne;
    private int roofJumpWait;
    /** The one roof jump per run has been spent. */
    private boolean roofJumpUsed;
    /** Press jump on the next movement input. */
    private boolean jumpRequested;

    public BlockIn() {
        super("BlockIn", false, false, "Blocks you in with walls and a roof");
    }

    // ---------------------------------------------------------------- lifecycle

    /** True while BlockIn owns the rotation, so the aim assist knows to stay out of the way. */
    public static boolean isActive() {
        if (Myau.moduleManager == null) {
            return false;
        }
        Module module = Myau.moduleManager.modules.get(BlockIn.class);
        return module != null && module.isEnabled();
    }

    @Override
    public void onEnabled() {
        this.placementNodes.clear();
        this.currentTarget = null;
        this.blockSlot = -1;
        this.sneaking = false;
        this.walking = false;
        this.walkTicks = 0;
        this.finishing = false;
        this.roofSide = null;
        this.roofJumpUsed = false;
        this.jumpRequested = false;
        this.silent = null;
        this.turnTarget = null;
        this.lastFrameNanos = 0L;
        this.lastClickAt = 0L;
        this.lastAimAt = 0L;
        this.clickDelay = this.drawDelay();
        EntityPlayerSP player = mc.thePlayer;
        this.previousSlot = player != null ? player.inventory.currentItem : -1;
        this.serverRotation = player != null ? Rotation.of(player) : null;
    }

    /**
     * With silent aim, switching off first walks the reported rotation back to where you are
     * really looking. Dropping it in one step would be a jump no mouse can make; the original
     * pauses the module for the same reason. Asking to switch off a second time forces it.
     */
    @Override
    public void setEnabled(boolean enabled) {
        if (!enabled && this.isEnabled() && !this.finishing && this.silent != null && mc.thePlayer != null) {
            this.finishing = true;
            this.stopBuilding();
            return;
        }
        super.setEnabled(enabled);
    }

    @Override
    public void onDisabled() {
        this.stopBuilding();
        if (this.sneaking && !this.keepSneak.getValue()) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), false);
        }
        this.sneaking = false;
        this.finishing = false;
        this.silent = null;
        this.turnTarget = null;
    }

    /** Everything the original's reset does short of switching off. */
    private void stopBuilding() {
        EntityPlayerSP player = mc.thePlayer;
        if (this.returnToLastSlot.getValue() && this.previousSlot != -1 && player != null
                && player.inventory.currentItem != this.previousSlot) {
            player.inventory.currentItem = this.previousSlot;
        }
        this.previousSlot = -1;
        this.currentTarget = null;
        this.blockSlot = -1;
        this.placementNodes.removeIf(PlacementNode::clearCandidatePaths);
        this.walking = false;
        this.walkTicks = 0;
        this.roofSide = null;
        this.jumpRequested = false;
        this.turnTarget = null;
    }

    /** Ends the run, with a reason when it did not simply finish. */
    private void stop(String reason) {
        if (reason != null && Myau.notificationManager != null) {
            Myau.notificationManager.add("BlockIn: " + reason, 0xFFAA00);
        }
        this.setEnabled(false);
    }

    // ---------------------------------------------------------------- the build loop

    @EventTarget(Priority.HIGH)
    public void onUpdate(UpdateEvent event) {
        if (event.getType() == EventType.POST) {
            // What was just sent; the next placement is validated against exactly this.
            this.serverRotation = new Rotation(event.getYaw(), event.getPitch());
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        World world = mc.theWorld;
        if (player == null || world == null) {
            this.finishing = false;
            super.setEnabled(false);
            return;
        }
        if (this.serverRotation == null) {
            this.serverRotation = new Rotation(event.getYaw(), event.getPitch());
        }
        if (this.finishing) {
            this.walkBack(event, player);
            return;
        }

        if (this.blockSlot == -1 || !this.isUsableBlockSlot(player, this.blockSlot)) {
            this.blockSlot = this.selectBestBlockSlot(player, world);
        }
        if (this.blockSlot != -1 && this.placementNodes.isEmpty()) {
            this.buildPlacementNodes(player, world);
        }
        if (this.blockSlot == -1 || this.placementNodes.isEmpty()) {
            boolean hadNodes = !this.placementNodes.isEmpty();
            this.stop(this.blockSlot == -1
                    ? (hadNodes ? "Ran out of blocks!" : "No blocks in hotbar!")
                    : "Could not block in!");
            return;
        }

        BlockPos enclosureBlock = standingColumn(this.placementNodes);
        if (!ClutchPaths.isPlacementSpaceClear(world, player, enclosureBlock)) {
            this.stop("Entity in the way!");
            return;
        }
        player.inventory.currentItem = this.blockSlot;

        if (!insideColumn(player.getEntityBoundingBox(), enclosureBlock)) {
            // Not in yet, or knocked out mid-jump; either way a roof jump is off.
            this.roofSide = null;
            double targetX = enclosureBlock.getX() + 0.5;
            double targetZ = enclosureBlock.getZ() + 0.5;
            if (player.getDistance(targetX, player.posY, targetZ) > MAX_WALK_DISTANCE) {
                this.stop(null);
                return;
            }
            if (++this.walkTicks > MAX_WALK_TICKS) {
                this.stop("Could not get into position!");
                return;
            }
            // Walk in first; aiming and placing wait until you are standing in the column.
            this.walking = true;
            this.walkX = targetX;
            this.walkZ = targetZ;
            this.walkFloorY = enclosureBlock.getY();
            this.holdAim(event);
            return;
        }
        this.walking = false;
        this.walkTicks = 0;
        if (this.sneak.getValue()) {
            // Re-asserted every tick, which is what cancelling the key release does in the original.
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), true);
            this.sneaking = true;
        }

        Vec3 eyePosition = player.getPositionEyes(1.0F);
        if (this.roofSide != null) {
            this.roofJumpTick(event, player, world, enclosureBlock, eyePosition);
            return;
        }
        if (isAirborne(player)) {
            // Plans are made, and clicks checked, from where you will be standing: wait to land.
            this.holdAim(event);
            return;
        }
        PlanStep step = this.planStep(world, player, enclosureBlock,
                player.getHorizontalFacing().getHorizontalIndex(), player.posY, eyePosition);
        this.currentTarget = step.target;
        if (this.currentTarget == null) {
            if (step.failedPlacements > 0) {
                this.stop("Failed to place " + step.failedPlacements + " block(s)!");
            } else if (step.nodesWithoutPaths > 0) {
                this.stop("No valid path found!");
            } else if (this.startRoofJump(player, world, enclosureBlock)) {
                this.roofJumpTick(event, player, world, enclosureBlock, eyePosition);
            } else {
                this.stop(this.roofJump.getValue() && ClutchPaths.isReplaceable(world, enclosureBlock.up(2))
                        ? "Could not place the roof!" : null);
            }
            return;
        }

        if (!this.isFacingTarget(player, world)) {
            this.aim(event, player, eyePosition);
            return;
        }
        // On target. Hold the aim still and click once the delay is up.
        this.holdAim(event);
        long now = System.currentTimeMillis();
        if (now - this.lastClickAt >= this.clickDelay) {
            this.place(player, world);
            this.lastClickAt = now;
            this.lastAimAt = now;
            this.clickDelay = this.drawDelay();
        }
    }

    /** What one planning pass decided: the next click, or why there is none. */
    static final class PlanStep {
        PlacementTarget target;
        int failedPlacements;
        int nodesWithoutPaths;
    }

    /**
     * One pass of the original's tick: plan any node that has no plan yet, then walk the nodes
     * from last to first for the next click that is actually possible right now. A side whose
     * next click is blocked is dropped; a node that loses every side on its first attempt is
     * re-planned once, after which failures count.
     * <p>
     * Takes the player's position and heading as plain values rather than the player, so the
     * same code can be run against a test world.
     *
     * @param self the entity to leave out of collision checks; the player in game
     */
    PlanStep planStep(World world, net.minecraft.entity.Entity self, BlockPos enclosureBlock,
                      int playerFacing, double playerY, Vec3 eyePosition) {
        PlanStep step = new PlanStep();
        double eyeY = eyePosition.yCoord;
        for (int i = this.placementNodes.size() - 1; i >= 0; --i) {
            PlacementNode node = this.placementNodes.get(i);
            if (!node.candidatePaths.isEmpty() || node.retryState != 0) {
                continue;
            }
            this.computePlacementPaths(enclosureBlock, node, self, playerFacing, playerY, eyeY, world);
            if (node.candidatePaths.isEmpty() && node.hasSupportBlock()) {
                ++step.nodesWithoutPaths;
            }
        }

        for (int i = this.placementNodes.size() - 1; i >= 0; --i) {
            PlacementNode node = this.placementNodes.get(i);
            if (node.candidatePaths.isEmpty() || step.target != null) {
                continue;
            }
            int invalidPaths = 0;
            pathSearch:
            while (!node.candidatePaths.isEmpty()) {
                PathSegment segment = node.candidatePaths.get(0);
                boolean invalid = false;
                while (!segment.pendingTargets.isEmpty()) {
                    PlacementTarget target = segment.pendingTargets.firstElement();
                    if (target == null) {
                        segment.pendingTargets.removeElementAt(0);
                        continue;
                    }
                    BlockPos placedBlock = target.getPlacedBlock();
                    if (!ClutchPaths.isReplaceable(world, placedBlock)) {
                        // Already there - placed earlier, or filled some other way.
                        segment.pendingTargets.removeElementAt(0);
                        continue;
                    }
                    if (ClutchPaths.isPlacementSpaceClear(world, self, placedBlock)) {
                        Vec3 hitPoint = ClutchPaths.findFirstPlacementHitPoint(self, world, eyePosition, target);
                        if (hitPoint != null && ClutchPaths.isEntityPathClear(world, self,
                                nodeBounds(node), eyePosition, hitPoint)) {
                            step.target = target;
                            target.hitPoint = hitPoint;
                            break pathSearch;
                        }
                    }
                    if (node.retryState == 0) {
                        ++invalidPaths;
                    } else {
                        ++step.failedPlacements;
                    }
                    invalid = true;
                    break;
                }
                // A finished segment and an unusable one are both done with. The original only
                // removed the finished one when nothing else had failed first, and otherwise
                // looped on it forever.
                node.candidatePaths.remove(0);
                if (!invalid && invalidPaths == 0) {
                    node.retryState = -1;
                }
            }
            if (invalidPaths <= 0 || step.target != null
                    || !node.candidatePaths.isEmpty() || node.retryState != 0) {
                continue;
            }
            // One re-plan per node, then failures count for real.
            this.computePlacementPaths(enclosureBlock, node, self, playerFacing, playerY, eyeY, world);
            ++node.retryState;
            ++i;
        }
        return step;
    }

    /** The nodes being built, for the simulation to seed and inspect. */
    List<PlacementNode> nodes() {
        return this.placementNodes;
    }

    /**
     * The column you stand in while building: the first node that is a column rather than a roof.
     * The original takes the first node outright, which is a roof whenever the chosen column is a
     * step below you - and then waits to see you standing in a roof block, forever.
     */
    static BlockPos standingColumn(List<PlacementNode> nodes) {
        for (PlacementNode node : nodes) {
            if (node.hasSupportBlock()) {
                return node.baseBlock;
            }
        }
        return nodes.get(0).baseBlock;
    }

    // ---------------------------------------------------------------- the roof jump

    /** Sets up a roof jump when the roof is all that is left and one jump can get it on. */
    private boolean startRoofJump(EntityPlayerSP player, World world, BlockPos standBlock) {
        if (!this.roofJump.getValue() || this.roofJumpUsed || !player.onGround) {
            return false;
        }
        Rotation facing = this.silentAim.getValue() && this.silent != null ? this.silent : Rotation.of(player);
        EnumFacing side = chooseRoofSide(world, player, standBlock, facing.yaw, player.getEyeHeight());
        if (side == null) {
            return false;
        }
        this.roofJumpUsed = true;
        this.roofSide = side;
        this.roofJumpPressed = false;
        this.roofJumpAirborne = false;
        this.roofJumpWait = 0;
        return true;
    }

    /**
     * One tick of the roof jump. On the ground it turns to the jump aim and jumps once the rest of
     * the turn can finish on the way up; in the air it clicks the top of the chosen wall as soon as
     * the aim line crosses it; on landing it hands back to the planner, which puts the roof against
     * the new support from the ground.
     */
    private void roofJumpTick(UpdateEvent event, EntityPlayerSP player, World world, BlockPos standBlock,
                              Vec3 eyePosition) {
        BlockPos wall = standBlock.up().offset(this.roofSide);
        Rotation aim = roofJumpAim(this.roofSide);
        if (!this.roofJumpPressed) {
            this.aimToward(event, player, aim);
            Rotation current = this.silentAim.getValue() && this.silent != null ? this.silent : Rotation.of(player);
            // The wall's top comes into view on the third tick up; two ticks of turning fit before it.
            if (current.distanceTo(aim) <= Math.max(1.0F, 2.0F * this.aimSpeed.getValue())) {
                this.roofJumpPressed = true;
                this.jumpRequested = true;
            }
            return;
        }
        if (!this.roofJumpAirborne) {
            if (player.onGround) {
                if (++this.roofJumpWait > JUMP_START_TICKS) {
                    // The jump never went. The planner finds the roof still missing and says so.
                    this.roofSide = null;
                    this.holdAim(event);
                    return;
                }
                this.jumpRequested = true;
                this.aimToward(event, player, aim);
                return;
            }
            this.roofJumpAirborne = true;
        }
        if (player.onGround) {
            this.roofSide = null;
            this.replanRoof(standBlock);
            this.holdAim(event);
            return;
        }
        if (!ClutchPaths.isReplaceable(world, wall.up())) {
            // The support is in; wait for the landing.
            this.holdAim(event);
            return;
        }
        this.aimToward(event, player, aim);
        long now = System.currentTimeMillis();
        if (now - this.lastClickAt >= this.clickDelay && this.serverRotation != null
                && hitsTopOf(world, eyePosition, this.serverRotation, wall)) {
            this.place(player, world);
            this.lastClickAt = now;
            this.lastAimAt = now;
            this.clickDelay = this.drawDelay();
        }
    }

    /**
     * Has the roof over {@code standBlock} planned afresh. The planner stops re-planning a node
     * once it has failed it, and the new support is exactly what a failed roof was missing.
     */
    void replanRoof(BlockPos standBlock) {
        BlockPos roof = standBlock.up(2);
        for (PlacementNode node : this.placementNodes) {
            if (!node.hasSupportBlock() && node.baseBlock.equals(roof)) {
                node.candidatePaths.clear();
                node.retryState = 0;
            }
        }
    }

    /**
     * The head-level wall to put the roof's support on: one that is there to build on, with room
     * on top, whose top the jump aim really crosses. The one nearest the way you face wins, so the
     * turn before the jump is short. Null when the roof is not missing or no wall will do.
     */
    static EnumFacing chooseRoofSide(World world, net.minecraft.entity.Entity self, BlockPos standBlock,
                                     float yaw, double eyeHeight) {
        BlockPos roof = standBlock.up(2);
        if (!ClutchPaths.isReplaceable(world, roof) || !ClutchPaths.isPlacementSpaceClear(world, self, roof)) {
            return null;
        }
        Vec3 peakEye = new Vec3(standBlock.getX() + 0.5, standBlock.getY() + JUMP_PEAK + eyeHeight,
                standBlock.getZ() + 0.5);
        EnumFacing best = null;
        float bestTurn = Float.MAX_VALUE;
        for (int h = 0; h < 4; ++h) {
            EnumFacing side = EnumFacing.getHorizontal(h);
            BlockPos wall = standBlock.up().offset(side);
            BlockPos support = wall.up();
            Rotation aim = roofJumpAim(side);
            if (!ClutchPaths.canPlaceAgainst(world, wall) || !ClutchPaths.isReplaceable(world, support)
                    || !ClutchPaths.isPlacementSpaceClear(world, self, support)
                    || !hitsTopOf(world, peakEye, aim, wall)) {
                continue;
            }
            float turn = Math.abs(MathHelper.wrapAngleTo180_float(aim.yaw - yaw));
            if (turn < bestTurn) {
                bestTurn = turn;
                best = side;
            }
        }
        return best;
    }

    /** Straight out over the wall on {@code side}, looking down at the roof jump's pitch. */
    static Rotation roofJumpAim(EnumFacing side) {
        return new Rotation(MathHelper.wrapAngleTo180_float(side.getHorizontalIndex() * 90.0F), ROOF_JUMP_PITCH);
    }

    /** Whether a click along {@code rotation} from {@code eye} lands on the top face of {@code wall}. */
    static boolean hitsTopOf(World world, Vec3 eye, Rotation rotation, BlockPos wall) {
        Vec3 direction = rotation.direction();
        double reach = 4.5;
        Vec3 end = eye.addVector(direction.xCoord * reach, direction.yCoord * reach, direction.zCoord * reach);
        MovingObjectPosition hit = world.rayTraceBlocks(eye, end, false, false, false);
        return ClutchPaths.isExpectedBlockHit(hit, wall, EnumFacing.UP.getIndex());
    }

    /** In the air, as opposed to standing, swimming or climbing. */
    private static boolean isAirborne(EntityPlayerSP player) {
        return !player.onGround && !player.isInWater() && !player.isInLava() && !player.isOnLadder();
    }

    /** The column a node occupies, grown by a block, as the space an aim line must stay clear of. */
    private static AxisAlignedBB nodeBounds(PlacementNode node) {
        BlockPos base = node.baseBlock;
        return new AxisAlignedBB(base.getX(), base.getY(), base.getZ(),
                base.getX() + 1, base.getY() + (node.hasSupportBlock() ? 2 : 1), base.getZ() + 1)
                .expand(1.0, 1.0, 1.0);
    }

    // ---------------------------------------------------------------- aiming

    private long drawDelay() {
        int low = Math.min(this.placeDelayMin.getValue(), this.placeDelayMax.getValue());
        int high = Math.max(this.placeDelayMin.getValue(), this.placeDelayMax.getValue());
        return high > low ? low + ThreadLocalRandom.current().nextInt(high - low + 1) : low;
    }

    /**
     * Turns toward the current target's hit point. As in the original, the aim holds still until
     * half the place delay has passed since the last click, so a new target is not snapped to in
     * the same instant the previous block lands.
     */
    private void aim(UpdateEvent event, EntityPlayerSP player, Vec3 eyePosition) {
        boolean ready = System.currentTimeMillis() - this.lastAimAt >= this.clickDelay / 2L;
        this.aimToward(event, player, ready ? Rotation.toward(eyePosition, this.currentTarget.hitPoint) : null);
    }

    /** Turns toward {@code goal} at the aim speed, or holds the aim still when it is null. */
    private void aimToward(UpdateEvent event, EntityPlayerSP player, Rotation goal) {
        if (!this.silentAim.getValue()) {
            this.turnTarget = goal;
            return;
        }
        Rotation from = this.silent != null ? this.silent : this.serverRotation;
        Rotation next = goal == null ? from : stepToward(from, goal, this.aimSpeed.getValue());
        this.sendSilent(event, player, quantize(next, event.getYaw(), event.getPitch()));
    }

    /** Keeps the current aim without moving it. */
    private void holdAim(UpdateEvent event) {
        this.turnTarget = null;
        if (this.silentAim.getValue() && this.silent != null && mc.thePlayer != null) {
            this.sendSilent(event, mc.thePlayer, this.silent);
        }
    }

    private void sendSilent(UpdateEvent event, EntityPlayerSP player, Rotation rotation) {
        this.silent = rotation;
        event.setRotation(rotation.yaw, rotation.pitch, ROTATION_PRIORITY);
        // Movement is simulated from the rotation that is sent, so it is corrected to match.
        event.setPervRotation(rotation.yaw, ROTATION_PRIORITY);
    }

    /** Switching off with silent aim: walk the reported rotation back, then really switch off. */
    private void walkBack(UpdateEvent event, EntityPlayerSP player) {
        if (this.silent == null) {
            this.finishing = false;
            super.setEnabled(false);
            return;
        }
        Rotation actual = Rotation.of(player);
        Rotation next = quantize(stepToward(this.silent, actual, this.aimSpeed.getValue()),
                event.getYaw(), event.getPitch());
        if (next.distanceTo(actual) < (float) RotationUtil.gcd()) {
            this.silent = null;
            this.finishing = false;
            super.setEnabled(false);
            return;
        }
        this.sendSilent(event, player, next);
    }

    /**
     * At most {@code speed} degrees along a straight line to the goal, and exactly onto it when it
     * is closer than that.
     */
    private static Rotation stepToward(Rotation from, Rotation goal, float speed) {
        float deltaYaw = from.yawTo(goal);
        float deltaPitch = from.pitchTo(goal);
        float distance = Rotation.length(deltaYaw, deltaPitch);
        if (distance <= speed) {
            return from.moved(deltaYaw, deltaPitch);
        }
        float scale = speed / distance;
        return from.moved(deltaYaw * scale, deltaPitch * scale);
    }

    /** Onto the steps a real mouse produces at this sensitivity, from the last rotation sent. */
    private static Rotation quantize(Rotation rotation, float baseYaw, float basePitch) {
        double step = RotationUtil.gcd();
        if (step <= 0.0) {
            return rotation;
        }
        float yaw = baseYaw + (float) (Math.round((rotation.yaw - baseYaw) / step) * step);
        float pitch = basePitch + (float) (Math.round((rotation.pitch - basePitch) / step) * step);
        return new Rotation(yaw, MathHelper.clamp_float(pitch, -90.0F, 90.0F));
    }

    /**
     * The visible turn, run every frame right after the game applies your own mouse movement, so
     * it is as smooth as the frame rate rather than stepping once a tick.
     */
    public static void turn(float partialTicks) {
        if (Myau.moduleManager == null) {
            return;
        }
        Module module = Myau.moduleManager.modules.get(BlockIn.class);
        if (module instanceof BlockIn && module.isEnabled()) {
            ((BlockIn) module).applyTurn();
        }
    }

    private void applyTurn() {
        long now = System.nanoTime();
        float deltaTicks = this.lastFrameNanos == 0L ? 0.0F
                : Math.min(4.0F, Math.max(0.0F, (now - this.lastFrameNanos) / 1.0e9F * 20.0F));
        this.lastFrameNanos = now;
        Rotation goal = this.turnTarget;
        EntityPlayerSP player = mc.thePlayer;
        if (goal == null || player == null || this.silentAim.getValue() || deltaTicks <= 0.0F) {
            return;
        }
        Rotation actual = Rotation.of(player);
        Rotation next = stepToward(actual, goal, this.aimSpeed.getValue() * deltaTicks);
        float deltaYaw = actual.yawTo(next);
        float deltaPitch = actual.pitchTo(next);
        if (deltaYaw != 0.0F || deltaPitch != 0.0F) {
            player.setAngles(deltaYaw / 0.15F, -deltaPitch / 0.15F);
        }
    }

    // ---------------------------------------------------------------- placing

    /**
     * Where the rotation the server last received is pointing. Built from that rotation directly,
     * not from {@code getLook}, which reads a head yaw that lags the real one by up to a tick.
     */
    private MovingObjectPosition traceFromServerRotation(EntityPlayerSP player, World world) {
        if (this.serverRotation == null) {
            return null;
        }
        Vec3 eye = player.getPositionEyes(1.0F);
        Vec3 direction = this.serverRotation.direction();
        double reach = mc.playerController.getBlockReachDistance();
        Vec3 end = eye.addVector(direction.xCoord * reach, direction.yCoord * reach, direction.zCoord * reach);
        return world.rayTraceBlocks(eye, end, false, false, false);
    }

    /** The original's facing check: on the support block, on the right face, near the aimed point. */
    private boolean isFacingTarget(EntityPlayerSP player, World world) {
        if (this.currentTarget == null) {
            return false;
        }
        MovingObjectPosition hit = this.traceFromServerRotation(player, world);
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || !this.currentTarget.supportBlock.equals(hit.getBlockPos())) {
            return false;
        }
        EnumFacing requiredFacing = this.currentTarget.offsetFromSupport ? this.currentTarget.facing : null;
        return requiredFacing == null
                || (requiredFacing == hit.sideHit
                && this.currentTarget.hitPoint != null
                && this.currentTarget.hitPoint.distanceTo(hit.hitVec) <= HIT_TOLERANCE);
    }

    private void place(EntityPlayerSP player, World world) {
        ItemStack stack = player.inventory.getCurrentItem();
        if (stack == null || !(stack.getItem() instanceof ItemBlock)) {
            return;
        }
        KeyBinding useKey = mc.gameSettings.keyBindUseItem;
        if (useKey.isKeyDown()) {
            // A held use key would place on its own schedule as well.
            KeyBinding.setKeyBindState(useKey.getKeyCode(), false);
        }
        MovingObjectPosition hit = this.traceFromServerRotation(player, world);
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return;
        }
        if (mc.playerController.onPlayerRightClick(player, mc.theWorld, stack, hit.getBlockPos(), hit.sideHit, hit.hitVec)) {
            player.swingItem();
        }
    }

    // ---------------------------------------------------------------- walking in

    /**
     * Walks you into the chosen column while it is being approached. Only the eight directions
     * a keyboard can produce are used - a prediction anticheat models movement from exactly those
     * - and the last half block is covered at sneak speed so the stop lands inside the column.
     */
    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            return;
        }
        MovementInput input = player.movementInput;
        if (this.walking) {
            this.steerInto(player, input);
            return;
        }
        if (this.roofSide != null) {
            // Straight up and down in the middle of the column, where the jump aim is worked out from.
            input.moveForward = 0.0F;
            input.moveStrafe = 0.0F;
            input.jump = this.jumpRequested;
            this.jumpRequested = false;
            player.setSprinting(false);
            return;
        }
        // While building, your own movement is left alone but kept in step with a silent aim.
        // The MoveFix module does this itself when it is on; doing it twice would rotate the
        // input twice.
        if (this.silent != null && this.silentAim.getValue() && RotationState.isActived()
                && RotationState.getPriority() == ROTATION_PRIORITY && MoveUtil.isForwardPressed()
                && !moveFixModuleActive()) {
            MoveUtil.fixStrafe(this.silent.yaw);
        }
    }

    private static boolean moveFixModuleActive() {
        Module module = Myau.moduleManager == null ? null : Myau.moduleManager.modules.get(MoveFix.class);
        return module != null && module.isEnabled();
    }

    private void steerInto(EntityPlayerSP player, MovementInput input) {
        double deltaX = this.walkX - player.posX;
        double deltaZ = this.walkZ - player.posZ;
        double distance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        input.jump = false;
        player.setSprinting(false);
        if (distance <= WALK_TOLERANCE) {
            input.moveForward = 0.0F;
            input.moveStrafe = 0.0F;
            return;
        }
        // Whichever yaw movement is actually simulated with this tick.
        float yaw = RotationState.isActived() ? RotationState.getSmoothedYaw() : player.rotationYaw;
        float[] keys = keyInput(deltaX, deltaZ, yaw);
        float keyForward = keys[0];
        float keyStrafe = keys[1];

        // A column a step down is dropped into. Sneaking would stop you at the edge, so the slow
        // approach waits until you are down at its floor.
        boolean above = player.getEntityBoundingBox().minY > this.walkFloorY + 0.5;
        if (above) {
            input.sneak = false;
        }
        boolean slow = !above && (distance < SLOW_APPROACH || input.sneak);
        if (slow) {
            input.sneak = true;
            keyForward *= 0.3F;
            keyStrafe *= 0.3F;
        }
        input.moveForward = keyForward;
        input.moveStrafe = keyStrafe;
    }

    /**
     * The key combination - forward and strafe, each -1, 0 or 1 - whose movement points nearest
     * to the world direction {@code (deltaX, deltaZ)} for a player facing {@code yaw}.
     * <p>
     * This is the inverse of {@code moveFlying}, which turns keys into world motion as
     * {@code x = strafe * cos - forward * sin, z = forward * cos + strafe * sin}; each axis is then
     * snapped, counting only once it is past 22.5 degrees, which picks the nearest of the eight
     * directions a keyboard can produce.
     */
    static float[] keyInput(double deltaX, double deltaZ, float yaw) {
        double distance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        if (distance <= 1.0E-9) {
            return new float[]{0.0F, 0.0F};
        }
        double radians = Math.toRadians(yaw);
        double sin = Math.sin(radians);
        double cos = Math.cos(radians);
        double forward = -deltaX * sin + deltaZ * cos;
        double strafe = deltaX * cos + deltaZ * sin;
        double threshold = distance * Math.sin(Math.toRadians(22.5));
        float keyForward = forward > threshold ? 1.0F : (forward < -threshold ? -1.0F : 0.0F);
        float keyStrafe = strafe > threshold ? 1.0F : (strafe < -threshold ? -1.0F : 0.0F);
        return new float[]{keyForward, keyStrafe};
    }

    /** Package-visible so the walk and the aim can be checked without a running game. */
    static Rotation stepTowardForTest(Rotation from, Rotation goal, float speed) {
        return stepToward(from, goal, speed);
    }

    /**
     * Wholly inside the column - clear of every neighbour a wall goes into - with a small margin
     * so the stop does not settle right on the edge. Anywhere from the column's floor up to the top
     * of a jump counts, so the roof jump stays inside.
     */
    static boolean insideColumn(AxisAlignedBB box, BlockPos pos) {
        double margin = 0.05;
        return box.minX >= pos.getX() + margin && box.maxX <= pos.getX() + 1.0 - margin
                && box.minZ >= pos.getZ() + margin && box.maxZ <= pos.getZ() + 1.0 - margin
                && box.minY >= pos.getY() - 0.001 && box.minY < pos.getY() + 1.5;
    }

    // ---------------------------------------------------------------- planning

    private void buildPlacementNodes(EntityPlayerSP player, World world) {
        if (!this.placementNodes.isEmpty() || !isStandingOnSolid(player, world)) {
            return;
        }
        ClutchSpot spot = this.findClutchPosition(player, world);
        BlockPos baseBlock = spot.pos;
        PlacementNode baseNode = new PlacementNode(baseBlock, (EnumFacing) null);

        BedSpot bed = findNearestBed(world, baseBlock);
        if (this.bedFinder.getValue() && spot.facingIndex != 0 && bed != null) {
            Stack<PlacementNode> bedPath = this.findBestPath(baseBlock, bed.facing, world);
            if (bedPath != null && !bedPath.isEmpty()) {
                Collections.reverse(bedPath);
                this.placementNodes.addAll(bedPath);
            }
        }
        if (this.placementNodes.isEmpty()) {
            this.placementNodes.add(baseNode);
        }

        // A roof block over every column you stand in.
        boolean playerAtBaseHeight = baseBlock.getY() == MathHelper.floor_double(player.posY);
        List<PlacementNode> upperNodes = new ArrayList<PlacementNode>();
        for (PlacementNode node : this.placementNodes) {
            if (node.hasSupportBlock()) {
                upperNodes.add(new PlacementNode(node.supportBlock.up()));
            }
        }
        if (playerAtBaseHeight) {
            this.placementNodes.addAll(upperNodes);
        } else {
            this.placementNodes.addAll(0, upperNodes);
        }
    }

    /**
     * The tunnel toward a bed. The original loops over four headings here but passes the same one
     * every time, so it computes one path four times; this computes it once.
     */
    private Stack<PlacementNode> findBestPath(BlockPos startBlock, EnumFacing facing, World world) {
        Stack<PlacementNode> bestPath = ClutchPaths.findBlockPlacementPath(startBlock, null, facing,
                new BedSearch(this, world), 0);
        if (bestPath == null || bestPath.isEmpty()) {
            return bestPath;
        }
        // Where the tunnel steps down, the faces joining the step must stay open.
        for (int index = bestPath.size() - 1; index >= 2; --index) {
            PlacementNode current = bestPath.get(index);
            PlacementNode previous = bestPath.get(index - 1);
            PlacementNode preceding = bestPath.get(index - 2);
            if (!current.hasSupportBlock() || previous.hasSupportBlock() || !preceding.hasSupportBlock()) {
                continue;
            }
            if (previous.baseFacing != null) {
                current.blockBaseFacing(previous.baseFacing);
            }
            if (preceding.supportFacing != null) {
                preceding.blockSupportFacing(preceding.supportFacing);
            }
        }
        return bestPath;
    }

    /**
     * Plans the clicks that seal a node. For a column, each side gets its own plan, filling the
     * feet-level neighbour and then the head-level one on top of it. For a roof, the block is
     * filled from below. Plans may build off blocks other nodes are going to place.
     */
    void computePlacementPaths(BlockPos enclosureBlock, PlacementNode node, net.minecraft.entity.Entity player,
                               int playerFacing, double playerY, double eyeY, World world) {
        if (node == null || !node.candidatePaths.isEmpty()) {
            return;
        }
        int startingFacing = playerFacing;
        if (node.baseFacing != null && node.baseFacing.getHorizontalIndex() != -1) {
            startingFacing = node.baseFacing.getOpposite().getHorizontalIndex();
        }

        HashSet<BlockPos> occupiedBlocks = new HashSet<BlockPos>();
        HashSet<BlockPos> enclosureBlocks = new HashSet<BlockPos>();
        for (int i = this.placementNodes.size() - 1; i >= 0; --i) {
            PlacementNode existing = this.placementNodes.get(i);
            if (existing.hasSupportBlock()) {
                enclosureBlocks.add(existing.baseBlock);
                enclosureBlocks.add(existing.supportBlock);
            }
            for (PathSegment segment : existing.candidatePaths) {
                for (PlacementTarget target : segment.pendingTargets) {
                    if (target != null) {
                        occupiedBlocks.add(target.getPlacedBlock());
                    }
                }
            }
        }

        BlockPos baseBlock = node.baseBlock;
        BlockPos upperBlock = node.supportBlock;
        EnumFacing upperFacing = node.supportFacing;
        EnumFacing baseFacing = node.baseFacing;
        PlacementSearch search = new PlacementSearch(enclosureBlocks, node, world, player, occupiedBlocks);
        // Plans are made from where the eye will be once you stand in the column, not from where it is.
        Vec3 plannedEye = new Vec3(enclosureBlock.getX() + 0.5, eyeY, enclosureBlock.getZ() + 0.5);

        boolean roof = !node.hasSupportBlock() && baseBlock.getY() > playerY;
        if (roof) {
            if (ClutchPaths.isReplaceable(world, baseBlock)) {
                Vector<PlacementTarget> path = ClutchPaths.findPlacementPath(enclosureBlock, plannedEye, player,
                        world, baseBlock, EnumFacing.UP, EnumFacing.DOWN, search, 0);
                if (path != null && !path.isEmpty()) {
                    node.candidatePaths.add(new PathSegment(EnumFacing.UP, path));
                }
            }
            return;
        }

        for (int facingOffset = 0; facingOffset < 4; ++facingOffset) {
            EnumFacing facing = EnumFacing.getHorizontal((startingFacing + facingOffset) % 4);
            if (facing == upperFacing || facing == baseFacing) {
                continue;
            }
            BlockPos adjacentBase = baseBlock.offset(facing);
            boolean baseBlocked = occupiedBlocks.contains(adjacentBase) || node.isBaseFacingBlocked(facing)
                    || ClutchPaths.isFilled(world, adjacentBase);
            boolean upperBlocked = true;
            if (node.hasSupportBlock()) {
                BlockPos adjacentUpper = upperBlock.offset(facing);
                upperBlocked = occupiedBlocks.contains(adjacentUpper) || node.isSupportFacingBlocked(facing)
                        || ClutchPaths.isFilled(world, adjacentUpper);
            }
            if (baseBlocked && upperBlocked) {
                continue;
            }
            BlockPos primarySource = baseBlocked ? upperBlock : baseBlock;
            Vector<PlacementTarget> path = ClutchPaths.findPlacementPath(enclosureBlock, plannedEye, player, world,
                    primarySource.offset(facing), facing, facing, search, 0);
            if (path == null || path.isEmpty()) {
                if (baseBlocked || upperBlocked) {
                    continue;
                }
                path = ClutchPaths.findPlacementPath(enclosureBlock, plannedEye, player, world,
                        upperBlock.offset(facing), facing, facing, search, 0);
                if (path == null || path.isEmpty()) {
                    continue;
                }
            } else if (!baseBlocked && !upperBlocked) {
                // The feet-level wall is planned; stack the head-level one on top of it.
                PlacementTarget upperPlacement = new PlacementTarget(adjacentBase, EnumFacing.UP);
                upperPlacement.depth = path.size();
                path.add(upperPlacement);
            }
            node.candidatePaths.add(new PathSegment(facing, path));
        }

        // The original's ordering, kept as-is: it compares a horizontal index with a face index,
        // which only affects which side is sealed first, never whether it is.
        final int playerFacingIndex = playerFacing;
        node.candidatePaths.sort((first, second) -> {
            int firstDistance = Math.abs(playerFacingIndex - first.placementFacing.getIndex());
            int secondDistance = Math.abs(playerFacingIndex - second.placementFacing.getIndex());
            return Integer.compare(secondDistance, firstDistance);
        });
    }

    /**
     * How many blocks a run of columns would take to seal, plus a penalty for anything in the way
     * of the space itself. Lower is better. Public because the bed search ranks tunnels with it.
     */
    public int computePathCost(World world, Vector<PlacementNode> path) {
        int cost = path.size();
        for (PlacementNode node : path) {
            for (int h = 0; h < 4; h++) {
                EnumFacing facing = EnumFacing.getHorizontal(h);
                if (facing == node.baseFacing || facing == node.supportFacing) {
                    continue;
                }
                if (node.hasSupportBlock()) {
                    if (!node.isSupportFacingBlocked(facing)
                            && ClutchPaths.isReplaceable(world, node.supportBlock.offset(facing))) {
                        ++cost;
                    }
                    if (!ClutchPaths.isReplaceable(world, node.supportBlock) && !ClutchPaths.isBed(world, node.supportBlock)) {
                        ++cost;
                    }
                }
                if (!ClutchPaths.isReplaceable(world, node.baseBlock) && !ClutchPaths.isBed(world, node.baseBlock)) {
                    ++cost;
                }
                if (!node.isBaseFacingBlocked(facing)
                        && ClutchPaths.isReplaceable(world, node.baseBlock.offset(facing))) {
                    ++cost;
                }
            }
        }
        return cost;
    }

    /** A column and, when one was found, the face of it that already has a wall. */
    private static final class ClutchSpot {
        final BlockPos pos;
        final int facingIndex;

        ClutchSpot(BlockPos pos, int facingIndex) {
            this.pos = pos;
            this.facingIndex = facingIndex;
        }
    }

    /**
     * Your own column, or an open neighbour - at your height or one below - that already has a
     * solid block beside it, whichever needs the fewest blocks. Facing index 0 means nothing
     * better than standing still was found.
     */
    private ClutchSpot findClutchPosition(EntityPlayerSP player, World world) {
        BlockPos playerPosition = new BlockPos(MathHelper.floor_double(player.posX),
                MathHelper.floor_double(player.getEntityBoundingBox().minY), MathHelper.floor_double(player.posZ));
        ClutchSpot best = null;
        double bestCost = Double.MAX_VALUE;
        Stack<PlacementNode> candidatePath = new Stack<PlacementNode>();
        int playerFacing = player.getHorizontalFacing().getHorizontalIndex();

        for (int yOffset = 0; yOffset > -2; --yOffset) {
            BlockPos layer = playerPosition.add(0, yOffset, 0);
            for (int facingOffset = 0; facingOffset < 4; ++facingOffset) {
                EnumFacing facing = EnumFacing.getHorizontal((playerFacing + facingOffset) % 4);
                BlockPos adjacent = layer.offset(facing);
                if (ClutchPaths.isReplaceable(world, adjacent) && ClutchPaths.isFilled(world, adjacent.down())) {
                    int[] sides = FACE_OFFSETS[facing.getIndex()];
                    if (sides == null) {
                        continue;
                    }
                    for (int sideIndex : sides) {
                        BlockPos sidePosition = adjacent.offset(EnumFacing.getFront(sideIndex));
                        if (!ClutchPaths.isFilled(world, sidePosition)) {
                            continue;
                        }
                        candidatePath.clear();
                        candidatePath.push(new PlacementNode(adjacent, facing));
                        double cost = this.computePathCost(world, candidatePath);
                        if (cost < bestCost) {
                            bestCost = cost;
                            best = new ClutchSpot(adjacent, sideIndex);
                        }
                    }
                    continue;
                }
                double cost = this.computePathCost(world, candidatePath);
                if (!ClutchPaths.isFilled(world, adjacent) || !(cost < bestCost)) {
                    continue;
                }
                bestCost = cost;
                best = new ClutchSpot(playerPosition, facing.getIndex());
            }
        }
        return best == null ? new ClutchSpot(playerPosition, 0) : best;
    }

    /** A bed and the heading from the column toward it. */
    private static final class BedSpot {
        final EnumFacing facing;

        BedSpot(EnumFacing facing) {
            this.facing = facing;
        }
    }

    /** The nearest bed within ten blocks across and three up or down. */
    private static BedSpot findNearestBed(World world, BlockPos origin) {
        BedSpot nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        int searchRadius = 10;
        for (int xOffset = -searchRadius; xOffset < searchRadius; ++xOffset) {
            for (int zOffset = -searchRadius; zOffset < searchRadius; ++zOffset) {
                for (int yOffset = -3; yOffset < 3; ++yOffset) {
                    double distance = Math.sqrt(xOffset * xOffset + zOffset * zOffset);
                    if (!(distance < nearestDistance)) {
                        continue;
                    }
                    if (!ClutchPaths.isBed(world, origin.add(xOffset, yOffset, zOffset))) {
                        continue;
                    }
                    double angle = Math.toDegrees(Math.atan2(zOffset, xOffset)) - 90.0;
                    nearestDistance = distance;
                    nearest = new BedSpot(EnumFacing.fromAngle(angle));
                }
            }
        }
        return nearest;
    }

    private static boolean isStandingOnSolid(EntityPlayerSP player, World world) {
        BlockPos below = new BlockPos(MathHelper.floor_double(player.posX),
                MathHelper.floor_double(player.getEntityBoundingBox().minY - 1.0), MathHelper.floor_double(player.posZ));
        return ClutchPaths.isFilled(world, below);
    }

    // ---------------------------------------------------------------- blocks

    private boolean isAllowedBlock(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof ItemBlock) || stack.stackSize <= 0) {
            return false;
        }
        // The blacklist is this client's own: solid, and not something a click would open.
        return !this.useBlacklist.getValue() || ItemUtil.isBlock(stack);
    }

    private boolean isUsableBlockSlot(EntityPlayerSP player, int slot) {
        return this.isAllowedBlock(player.inventory.getStackInSlot(slot));
    }

    /** Lower is better: the position of the first priority name the block's name contains. */
    private static int blockPriorityIndex(ItemStack stack) {
        String name = stack.getDisplayName();
        for (int i = 0; i < BLOCK_PRIORITY.size(); ++i) {
            if (name.contains(BLOCK_PRIORITY.get(i))) {
                return i;
            }
        }
        return BLOCK_PRIORITY.size();
    }

    private int selectBestBlockSlot(EntityPlayerSP player, World world) {
        int bestSlot = -1;
        int bestStackSize = 0;
        if (this.blockPriority.getValue() == PRIORITY_HARDEST) {
            float bestHardness = -1.0F;
            for (int i = 0; i < 9; ++i) {
                if (!this.isUsableBlockSlot(player, i)) {
                    continue;
                }
                ItemStack stack = player.inventory.getStackInSlot(i);
                float hardness = ((ItemBlock) stack.getItem()).getBlock().getBlockHardness(world, BlockPos.ORIGIN);
                if (!(hardness > bestHardness) && (hardness != bestHardness || stack.stackSize <= bestStackSize)) {
                    continue;
                }
                bestHardness = hardness;
                bestStackSize = stack.stackSize;
                bestSlot = i;
            }
            return bestSlot;
        }
        int bestPriority = Integer.MAX_VALUE;
        for (int i = 0; i < 9; ++i) {
            if (!this.isUsableBlockSlot(player, i)) {
                continue;
            }
            ItemStack stack = player.inventory.getStackInSlot(i);
            int priority = blockPriorityIndex(stack);
            if (priority >= bestPriority && (priority != bestPriority || stack.stackSize <= bestStackSize)) {
                continue;
            }
            bestPriority = priority;
            bestStackSize = stack.stackSize;
            bestSlot = i;
        }
        return bestSlot;
    }
}
