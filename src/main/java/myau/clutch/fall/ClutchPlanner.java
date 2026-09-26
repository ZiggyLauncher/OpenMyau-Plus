package myau.clutch.fall;

import myau.clutch.PlacementTarget;
import myau.rotation.mouse.EntityRotator;
import myau.rotation.mouse.MouseAim;
import myau.rotation.mouse.SilentRotator;
import myau.rotation.mouse.TargetRotator;
import net.minecraft.block.Block;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.Vector;

/**
 * Plans a clutch: works out where a fall ends, finds solid blocks near your path that a line of
 * placed blocks could reach from, then rehearses the whole thing - your fall, the aim turning
 * onto each face with the ticks it has, every click, every block appearing - and keeps the first
 * line of blocks that the rehearsal shows you landing on. Ported from Vape's Clutch (the class is
 * {@code BlockIn} in its source; the Block-In module is called {@code Clutch} there).
 * <p>
 * The planner holds the state the original keeps on the module, so the same code runs in game
 * and against a test world.
 */
public final class ClutchPlanner {
    public static final double STATIONARY_FALL_MOTION = -0.0784000015258789;

    // ---------------------------------------------------------------- context, set by the caller
    public World world;
    /** The real player. */
    public EntityPlayer localPlayer;
    /** The real player's timers and input, refreshed before each planning pass. */
    public LocalInput localInput;
    public double blockReach = 4.5;
    /** The live aim's fractional update count, so rehearsals line up with it. */
    public double rotationAccumulator;
    /** The live silent aim, when one is running. */
    public SilentRotator liveSilent;
    /** The module's current aim controller, if any. */
    public TargetRotator rotationController;
    /** Whether the jump key is physically held. */
    public boolean jumpKeyPhysicallyDown;

    // ---------------------------------------------------------------- settings
    public boolean silentAim;
    public float speed = 3.5F;
    public boolean onVoid = true;
    public boolean onMoreThanXBlocks;

    // ---------------------------------------------------------------- state
    public MovementSnapshot graph;
    public float placeYaw;
    public float originalYaw;
    public boolean counterMotion;
    public boolean forcingCounterMotion;
    public boolean takingKnockback;
    public int knockbackTicks;
    public boolean recentlyClutched;
    public boolean staircaseQueued;
    public double fallTargetY = -999.0;
    public final HashSet<BlockPos> rejectedBlocks = new HashSet<BlockPos>();
    public final HashSet<BlockPos> placeableBlocks = new HashSet<BlockPos>();
    public final HashMap<BlockPos, HashSet<BlockPos>> blockGraphMap = new HashMap<BlockPos, HashSet<BlockPos>>();
    public final ExpiringBlocks placedBlocks = new ExpiringBlocks(5000L);

    /** A rehearsal of {@code source}, seeded from {@code snapshot}'s keys. */
    public MovementSimulation simulation(EntityPlayer source, MovementSnapshot snapshot) {
        return new MovementSimulation(source, this.localInput, this.localPlayer, this.world, snapshot,
                this.blockReach, this.rotationAccumulator, this.liveSilent);
    }

    // ---------------------------------------------------------------- where the fall ends

    /**
     * Plays your movement forward to where you land: the block under you once you are on the
     * ground (or on a stair or slab) for one tick - three with "more than x blocks" on - or
     * straight away if you jump off it. Null means the void.
     */
    public BlockPos findLandingBlock(int maxTicks, EntityPlayer player, List<Vec3> simulatedPositions) {
        int worldBottom = 0;
        MovementSimulation simulation = this.simulation(player, this.graph);
        try {
            simulation.applySnapshot(this.graph);
            simulation.restoreSnapshotInput();
            if (simulatedPositions != null) {
                simulatedPositions.add(simulation.getSimulatedPosition());
            }
            boolean returnFinalPosition = !this.onVoid;
            int requiredGroundTicks = this.onMoreThanXBlocks ? 3 : 1;
            EntityPlayer simulatedPlayer = simulation.getSimulatedPlayer();
            int groundedTicks = 0;
            BlockPos landingBlock = null;
            for (int tick = 0; tick <= maxTicks; ++tick) {
                simulation.simulateTick();
                if (simulatedPositions != null) {
                    simulatedPositions.add(simulation.getSimulatedPosition());
                }
                int blockX = MathHelper.floor_double(simulatedPlayer.posX);
                int blockY = MathHelper.floor_double(simulatedPlayer.posY - 0.015625);
                int blockZ = MathHelper.floor_double(simulatedPlayer.posZ);
                Block block = FallBlocks.blockAt(this.world, new BlockPos(blockX, blockY, blockZ));
                boolean specialLandingBlock = block instanceof BlockStairs || block instanceof BlockSlab;
                if (simulatedPlayer.onGround || specialLandingBlock) {
                    ++groundedTicks;
                    if (landingBlock == null) {
                        landingBlock = new BlockPos(blockX, blockY, blockZ);
                    }
                    if (groundedTicks >= requiredGroundTicks || simulation.isJumpInput()) {
                        return landingBlock;
                    }
                } else {
                    landingBlock = null;
                    groundedTicks = 0;
                }
                if (simulatedPlayer.posY <= (double) worldBottom) {
                    return null;
                }
            }
            if (returnFinalPosition) {
                return new BlockPos(MathHelper.floor_double(simulatedPlayer.posX),
                        MathHelper.floor_double(simulatedPlayer.posY) - 1, MathHelper.floor_double(simulatedPlayer.posZ));
            }
            return null;
        } finally {
            simulation.close();
        }
    }

    // ---------------------------------------------------------------- finding a line of blocks

    /**
     * Looks for a clutch: while rehearsing the fall, every solid, clickable block below your feet
     * within four blocks is a possible start, and every tick you come down onto a block height is
     * a possible end. Start and end pairs close enough to build between in the ticks there are
     * become candidate lines, cheapest first, and the first four with a plan are rehearsed in
     * full.
     */
    public ClutchPath searchClutchPath(MovementSimulation simulation, ItemStack blockItem) {
        int worldBottom = 0;
        MovementSnapshot initialGraph = MovementSnapshot.of(simulation);
        EntityPlayer localPlayer = this.localPlayer;
        ArrayList<Vec3> simulatedPositions = new ArrayList<Vec3>();
        EntityPlayer simulatedPlayer = simulation.getSimulatedPlayer();
        boolean staircaseSearch = this.staircaseQueued && simulatedPlayer.motionY > 0.0;
        if (staircaseSearch) {
            this.fallTargetY = localPlayer.posY + simulatedPlayer.motionY;
        }

        int estimatedTicks = 0;
        double estimatedY = simulatedPlayer.posY;
        double estimatedMotionY = simulatedPlayer.motionY;
        boolean distantFallTarget = Math.abs(this.fallTargetY - localPlayer.posY) > 1.0;
        while (estimatedTicks < 20) {
            estimatedY += estimatedMotionY;
            if (!distantFallTarget && estimatedY < this.fallTargetY) {
                break;
            }
            estimatedMotionY = (estimatedMotionY - 0.08) * (double) 0.98F;
            ++estimatedTicks;
        }

        // How far you would drift doing nothing; four blocks or more and you have to push back.
        double initialMotionY = simulatedPlayer.motionY;
        boolean initiallyGrounded = simulatedPlayer.onGround;
        simulation.restoreSnapshotInput();
        simulation.simulateTick();
        double projectedX = simulatedPlayer.posX - simulatedPlayer.prevPosX;
        double projectedZ = simulatedPlayer.posZ - simulatedPlayer.prevPosZ;
        double projectedMotionX = simulatedPlayer.motionX;
        double projectedMotionZ = simulatedPlayer.motionZ;
        simulation.applySnapshot(initialGraph);
        for (int tick = 0; tick < estimatedTicks - 1; ++tick) {
            double friction = (initiallyGrounded && initialMotionY > 0.0 ? (double) 0.6F : 1.0) * (double) 0.91F;
            if (Math.abs(projectedMotionX) < 0.005) {
                projectedMotionX = 0.0;
            }
            if (Math.abs(projectedMotionZ) < 0.005) {
                projectedMotionZ = 0.0;
            }
            projectedX += projectedMotionX;
            projectedZ += projectedMotionZ;
            projectedMotionX *= friction;
            projectedMotionZ *= friction;
            initiallyGrounded = false;
        }
        int estimatedBlockDistance = (int) Math.round(Math.sqrt(projectedX * projectedX + projectedZ * projectedZ));
        if (estimatedBlockDistance >= 4) {
            // Not possible standing still: face back the way you came and walk into it.
            this.graph.jumpKeyDown = this.takingKnockback && this.jumpKeyPhysicallyDown;
            this.graph.forwardKeyDown = true;
            this.graph.backwardKeyDown = false;
            this.graph.leftKeyDown = false;
            this.graph.rightKeyDown = false;
            this.counterMotion = true;
            this.forcingCounterMotion = true;
            this.placeYaw = movementYaw(localPlayer);
        } else {
            this.forcingCounterMotion = false;
            this.counterMotion = false;
        }
        if (this.counterMotion) {
            simulation.setForwardOnly();
        } else {
            simulation.restoreSnapshotInput();
        }

        double motionX = simulatedPlayer.motionX;
        double motionZ = simulatedPlayer.motionZ;
        BlockPos blockBelow = new BlockPos(MathHelper.floor_double(simulatedPlayer.posX),
                MathHelper.floor_double(simulatedPlayer.posY - 0.015625), MathHelper.floor_double(simulatedPlayer.posZ));
        boolean standingAboveBlock = !FallBlocks.isReplaceable(FallBlocks.blockAt(this.world, blockBelow));
        Vec3 eyePosition = new Vec3(simulatedPlayer.posX, simulatedPlayer.posY + (double) simulatedPlayer.getEyeHeight(),
                simulatedPlayer.posZ);
        Vec3 rotationTarget;
        if (this.forcingCounterMotion) {
            rotationTarget = eyePosition.addVector(-motionX, 0.0, -motionZ);
        } else {
            Vec3 look = simulatedPlayer.getLook(1.0F);
            rotationTarget = eyePosition.addVector(look.xCoord * 5.0, look.yCoord * 5.0, look.zCoord * 5.0);
        }
        TargetRotator searchRotation = this.buildRotation(simulatedPlayer, eyePosition, rotationTarget, null, 1, this.placeYaw);
        simulation.setRotationController(searchRotation);

        HashMap<BlockPos, Double> pathScores = new HashMap<BlockPos, Double>();
        int maxSearchRadius = 4;
        int initialSearchRadius = standingAboveBlock ? 1 : maxSearchRadius;
        Vec3 simulatedPosition = simulation.getSimulatedPosition();
        simulatedPositions.add(simulatedPosition);
        int lowestYOffset = localPlayer.onGround ? -2 : (localPlayer.motionY > 0.0 ? -3 : -1);
        boolean knockbackSettled = false;
        int simulatedTicks = 0;
        int remainingKnockbackTicks = this.knockbackTicks;
        for (int tick = 0; tick <= Math.max(estimatedTicks, 15); ++tick) {
            double playerFeetY = simulatedPlayer.getEntityBoundingBox().minY;
            ++simulatedTicks;
            for (int yOffset = 0; yOffset >= lowestYOffset; --yOffset) {
                for (int radius = 0; radius < maxSearchRadius; ++radius) {
                    int minXOffset = motionX >= 0.0 ? -initialSearchRadius : -radius;
                    int maxXOffset = motionX >= 0.0 ? radius : initialSearchRadius;
                    int minZOffset = motionZ >= 0.0 ? -initialSearchRadius : -radius;
                    int maxZOffset = motionZ >= 0.0 ? radius : initialSearchRadius;
                    for (int xOffset = minXOffset; xOffset <= maxXOffset; ++xOffset) {
                        for (int zOffset = minZOffset; zOffset <= maxZOffset; ++zOffset) {
                            if (Math.abs(xOffset) != radius && Math.abs(zOffset) != radius) {
                                continue;
                            }
                            BlockPos candidate = new BlockPos(MathHelper.floor_double(simulatedPlayer.posX) + xOffset,
                                    MathHelper.floor_double(simulatedPlayer.posY) + yOffset,
                                    MathHelper.floor_double(simulatedPlayer.posZ) + zOffset);
                            if (this.rejectedBlocks.contains(candidate)) {
                                continue;
                            }
                            Block block = FallBlocks.blockAt(this.world, candidate);
                            if (!this.placeableBlocks.contains(candidate) && FallBlocks.isAir(block)
                                    || FallBlocks.isLiquid(block) || !FallBlocks.isSolid(block)) {
                                this.rejectedBlocks.add(candidate);
                                continue;
                            }
                            if ((double) (candidate.getY() + 1) > playerFeetY) {
                                continue;
                            }
                            boolean placeable = this.placeableBlocks.contains(candidate)
                                    || !FallBlocks.isReplaceable(block) && !FallBlocks.isInteractive(block);
                            if (!placeable) {
                                this.rejectedBlocks.add(candidate);
                                continue;
                            }
                            this.placeableBlocks.add(candidate);
                            Vec3 candidateCenter = new Vec3(candidate.getX() + 0.5, candidate.getY() + 0.5, candidate.getZ() + 0.5);
                            Vec3 simulatedEye = simulatedPosition.addVector(0.0, simulatedPlayer.getEyeHeight(), 0.0);
                            float[] rotation = MouseAim.toward(simulatedEye, candidateCenter,
                                    searchRotation.getCurrentYaw(), searchRotation.getCurrentPitch());
                            double yawDistance = Math.abs(MouseAim.wrap(rotation[0] - searchRotation.getCurrentYaw()));
                            if (yawDistance > 120.0) {
                                yawDistance = Math.abs(MouseAim.wrap(yawDistance + 180.0));
                            }
                            if (!this.blockGraphMap.containsKey(candidate)) {
                                this.blockGraphMap.put(candidate, new HashSet<BlockPos>());
                            }
                            Double previousScore = pathScores.get(candidate);
                            if (previousScore == null || yawDistance < previousScore) {
                                pathScores.put(candidate, yawDistance);
                            }
                        }
                    }
                }
            }

            boolean[] strafeState = null;
            if (!this.silentAim) {
                strafeState = computeStrafeState(simulatedPlayer, this.placeYaw, this.graph.forwardKeyDown,
                        this.graph.backwardKeyDown, this.graph.leftKeyDown, this.graph.rightKeyDown);
                if (strafeState != null) {
                    simulation.setDirectionalKeys(strafeState[0], strafeState[1], strafeState[2], strafeState[3]);
                }
            }
            if (knockbackSettled && searchRotation instanceof SilentRotator) {
                ((SilentRotator) searchRotation).setReferenceYawOverride(this.originalYaw);
            }
            simulation.simulateTick();
            if (strafeState != null) {
                simulation.setDirectionalKeys(this.graph.forwardKeyDown, this.graph.backwardKeyDown,
                        this.graph.leftKeyDown, this.graph.rightKeyDown);
            }
            simulation.finishSimulation();
            simulatedPosition = simulation.getSimulatedPosition();
            simulatedPositions.add(simulatedPosition);
            if (this.takingKnockback && remainingKnockbackTicks > 0 && --remainingKnockbackTicks == 0) {
                knockbackSettled = true;
            }
            if (simulatedPlayer.motionY <= 0.0) {
                int landingY = MathHelper.floor_double(simulatedPlayer.posY) - 1;
                BlockPos landingBlock = new BlockPos(MathHelper.floor_double(simulatedPlayer.posX), landingY,
                        MathHelper.floor_double(simulatedPlayer.posZ));
                for (BlockPos supportBlock : this.blockGraphMap.keySet()) {
                    boolean validHeight = staircaseSearch ? landingY >= supportBlock.getY() : landingY == supportBlock.getY();
                    int distance = Math.abs(supportBlock.getX() - landingBlock.getX())
                            + Math.abs(supportBlock.getZ() - landingBlock.getZ())
                            + Math.abs(supportBlock.getY() - landingBlock.getY()) - 1;
                    if (distance <= simulatedTicks && validHeight && !supportBlock.equals(landingBlock)) {
                        this.blockGraphMap.get(supportBlock).add(landingBlock);
                    }
                }
            }
            if (simulatedPlayer.posY <= (double) worldBottom) {
                break;
            }
        }

        Vec3 playerPosition = new Vec3(localPlayer.posX, localPlayer.posY, localPlayer.posZ);
        ArrayList<ClutchPath> candidatePaths = new ArrayList<ClutchPath>();
        for (Map.Entry<BlockPos, Double> entry : pathScores.entrySet()) {
            BlockPos supportBlock = entry.getKey();
            HashSet<BlockPos> landingBlocks = this.blockGraphMap.get(supportBlock);
            if (supportBlock == null || landingBlocks == null || landingBlocks.isEmpty()) {
                continue;
            }
            ArrayList<ClutchPath> supportPaths = new ArrayList<ClutchPath>();
            landingLoop:
            for (BlockPos landingBlock : landingBlocks) {
                int deltaX = landingBlock.getX() - supportBlock.getX();
                int deltaY = landingBlock.getY() - supportBlock.getY();
                int deltaZ = landingBlock.getZ() - supportBlock.getZ();
                int xFacing = deltaX > 0 ? 5 : (deltaX < 0 ? 4 : -1);
                int yFacing = deltaY > 0 ? 1 : (deltaY < 0 ? 0 : -1);
                int zFacing = deltaZ > 0 ? 3 : (deltaZ < 0 ? 2 : -1);
                for (int facingIndex : new int[]{xFacing, yFacing, zFacing}) {
                    if (facingIndex == -1) {
                        continue;
                    }
                    BlockPos placedBlock = supportBlock.offset(EnumFacing.values()[facingIndex]);
                    Block block = FallBlocks.blockAt(this.world, placedBlock);
                    if (this.placedBlocks.contains(placedBlock) || !FallBlocks.isReplaceable(block)) {
                        continue;
                    }
                    supportPaths.add(new ClutchPath(supportBlock, landingBlock));
                    continue landingLoop;
                }
            }
            if (supportPaths.isEmpty()) {
                continue;
            }
            this.sortClutchPaths(supportPaths, playerPosition);
            candidatePaths.add(supportPaths.get(0));
        }
        if (candidatePaths.isEmpty()) {
            return null;
        }
        this.sortClutchPaths(candidatePaths, playerPosition);
        int checkedPaths = 0;
        for (ClutchPath candidatePath : candidatePaths) {
            if (checkedPaths > 3) {
                break;
            }
            this.planPlacementSearch(candidatePath, simulatedPositions);
            if (!candidatePath.hasPlan() || candidatePath.pendingTargets.isEmpty()) {
                continue;
            }
            ++checkedPaths;
            candidatePath.simulationTickLimit = estimatedTicks;
            int extraFallTicks = (double) candidatePath.target.getY() < localPlayer.posY
                    ? (int) Math.ceil((localPlayer.posY - (double) candidatePath.target.getY()) / 0.08)
                    : 7;
            ClutchPath result = this.simulateClutchPath(estimatedTicks + Math.min(extraFallTicks, 25), candidatePath, blockItem);
            if (result != null && !result.hasFailed()) {
                return result;
            }
        }
        ClutchPath firstPath = candidatePaths.get(0);
        return firstPath.hasFailed() ? firstPath : null;
    }

    /** Cheapest first: fewest blocks across, rising lines favoured, near the height you fell from. */
    public double clutchPathSortCost(Vec3 playerPosition, ClutchPath path) {
        BlockPos target = path.target;
        BlockPos start = path.start;
        int horizontalDistance = Math.abs(start.getX() - target.getX()) + Math.abs(start.getZ() - target.getZ());
        double cost = (double) (horizontalDistance * 100);
        if (target.getY() > start.getY()) {
            cost -= (double) ((target.getY() - start.getY()) * 200);
        }
        if (this.recentlyClutched) {
            cost += Math.sqrt(Math.pow((double) start.getX() + 0.5 - playerPosition.xCoord, 2.0)
                    + Math.pow((double) start.getZ() + 0.5 - playerPosition.zCoord, 2.0)) * 1000.0;
        }
        return cost + Math.abs((double) (start.getY() + 1) - this.fallTargetY) * 200.0;
    }

    private void sortClutchPaths(List<ClutchPath> paths, Vec3 playerPosition) {
        paths.sort(Comparator.comparingDouble(path -> this.clutchPathSortCost(playerPosition, path)));
    }

    /** Plans the clicks for one line, from its start block to its end block. */
    public void planPlacementSearch(ClutchPath path, List<Vec3> candidatePositions) {
        if (path == null || path.hasPlan()) {
            return;
        }
        BlockPos start = path.start;
        BlockPos target = path.target;
        int maxDepth = Math.abs(start.getX() - target.getX()) + Math.abs(start.getZ() - target.getZ())
                + Math.abs(start.getY() - target.getY());
        if (!FallBlocks.isReplaceable(FallBlocks.blockAt(this.world, target))) {
            return;
        }
        BridgeSearch search = new BridgeSearch(maxDepth, this.world,
                candidate -> this.scorePlacementPath(candidatePositions, candidate));
        Vector<PlacementTarget> found = search.findPath(start, target);
        if (found != null && !found.isEmpty()) {
            path.plan(found.lastElement().facing, found);
        }
    }

    /**
     * Lower is better. Walks the rehearsed positions against the clicks from the last one back:
     * each position either can see the next click's face or costs a large penalty, and distance
     * from the placed block costs too. A line nothing can see scores worst of all. The original's
     * {@code scorePlacementPath}, which compares feet positions, as it does.
     */
    public int scorePlacementPath(List<Vec3> candidatePositions, Vector<PlacementTarget> path) {
        if (path == null || path.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        Stack<PlacementTarget> remainingTargets = new Stack<PlacementTarget>();
        for (PlacementTarget target : path) {
            remainingTargets.push(target);
        }
        boolean noTargetReached = true;
        double score = path.size() * 100;
        for (Vec3 candidatePosition : candidatePositions) {
            if (remainingTargets.isEmpty()) {
                break;
            }
            PlacementTarget target = remainingTargets.peek();
            if (FallBlocks.isBlockFaceVisible(candidatePosition, this.world, target.supportBlock, target.facing)) {
                remainingTargets.pop();
                noTargetReached = false;
            } else {
                score += 100000.0;
            }
            BlockPos placedBlock = target.getPlacedBlock();
            Vec3 targetPosition = new Vec3(placedBlock.getX() + 0.5, candidatePosition.yCoord, placedBlock.getZ() + 0.5);
            score += candidatePosition.distanceTo(targetPosition) * 50000.0;
        }
        score += (double) (remainingTargets.size() * 100000);
        if (noTargetReached) {
            score = Integer.MAX_VALUE;
        }
        return (int) score;
    }

    // ---------------------------------------------------------------- rehearsing a line

    /**
     * Rehearses a candidate line tick by tick: the fall, the aim turning, each click the moment
     * the crosshair is on the face, the blocks appearing. Placed blocks are written into the
     * world for the rehearsal and put back afterwards. Returns the path if you land on it, the
     * path with a reason if it fails, or null when the ticks ran out first.
     */
    public ClutchPath simulateClutchPath(int maxTicks, ClutchPath pathSegment, ItemStack blockItem) {
        int worldBottom = 0;
        IBlockState placedBlockState = blockStateOf(blockItem);
        HashMap<BlockPos, IBlockState> replacedStates = new HashMap<BlockPos, IBlockState>();
        MovementSimulation simulation = this.simulation(this.localPlayer, this.graph);
        EntityPlayer simulatedPlayer = simulation.getSimulatedPlayer();
        simulation.applySnapshot(this.graph);
        if (this.counterMotion) {
            simulation.setForwardOnly();
        } else {
            simulation.restoreSnapshotInput();
        }
        int initialTargetCount = pathSegment.pendingTargets.size();
        boolean landedOnTarget = false;
        ClutchPath simulatedPath = new ClutchPath(pathSegment.start, pathSegment.target);
        simulatedPath.plan(pathSegment.placementFacing, new Vector<PlacementTarget>(pathSegment.pendingTargets));
        simulatedPath.simulatedPositions.add(simulation.getSimulatedPosition());
        TargetRotator simulatedRotation = null;
        if (this.rotationController instanceof SilentRotator && this.liveSilent != null) {
            SilentRotator fork = new SilentRotator(simulatedPlayer);
            fork.copyFrom((SilentRotator) this.rotationController);
            simulatedRotation = fork;
            simulation.setRotationController(simulatedRotation);
        }
        int groundedTicks = 0;
        int remainingKnockbackTicks = this.knockbackTicks;
        boolean knockbackSettled = false;
        boolean simulationFinished = false;
        boolean pathExtended = false;
        try {
            for (int tick = 0; tick <= maxTicks; ++tick) {
                MovementSnapshot tickGraph = MovementSnapshot.of(simulation);
                if (simulatedPlayer.onGround) {
                    boolean wouldLeaveGround = this.landsOnTargetFrom(simulatedPlayer, tickGraph, simulatedPath);
                    if (++groundedTicks >= 5 || !wouldLeaveGround) {
                        simulationFinished = true;
                        break;
                    }
                } else {
                    groundedTicks = 0;
                }

                PlacementTarget placementTarget = null;
                float currentYaw = this.localPlayer.rotationYaw;
                if (simulatedPath.hasPlan()) {
                    placementTarget = this.resolvePlaceTarget(simulatedPath, this.localPlayer, placedBlockState);
                    if (placementTarget != null) {
                        currentYaw = sourceRotation(simulatedRotation)[0];
                        if (this.canPlaceOnTarget(placementTarget, simulation)) {
                            BlockPos placedBlock = placementTarget.getPlacedBlock();
                            replacedStates.put(placedBlock, this.world.getBlockState(placedBlock));
                            SilentBlocks.set(this.world, placedBlock, placedBlockState);
                            if (simulatedPath.hasPlan()) {
                                placementTarget = null;
                                Vector<PlacementTarget> pendingTargets = simulatedPath.pendingTargets;
                                if (!pendingTargets.isEmpty()) {
                                    pendingTargets.removeElementAt(0);
                                }
                                if (pendingTargets.isEmpty() && initialTargetCount > 3 && !this.counterMotion
                                        && !this.graph.jumpKeyDown) {
                                    this.tryExtendPath(simulatedPlayer, tickGraph, simulatedPath, pathSegment, pendingTargets);
                                    pathExtended |= pathSegment.target != simulatedPath.target;
                                }
                                while (!pendingTargets.isEmpty()) {
                                    PlacementTarget candidate = pendingTargets.firstElement();
                                    BlockPos candidateBlock = candidate.getPlacedBlock();
                                    if (FallBlocks.isReplaceable(FallBlocks.blockAt(this.world, candidateBlock))) {
                                        if (FallBlocks.isPlacementSpaceClear(this.world, this.localPlayer, candidateBlock)) {
                                            placementTarget = candidate;
                                            break;
                                        }
                                        simulatedPath.fail("[SIM] Entity collision");
                                        break;
                                    }
                                    simulatedPath.hitVector = null;
                                    pendingTargets.removeElementAt(0);
                                }
                                if (placementTarget == null && this.forcingCounterMotion && simulatedRotation != null) {
                                    float targetYaw = this.takingKnockback ? this.originalYaw : this.placeYaw;
                                    float yawDistance = Math.abs(MouseAim.wrap(targetYaw - currentYaw));
                                    simulatedRotation.setSpeed(yawDistance / 1.8F / 3.0F);
                                    simulatedRotation.setTargetRotation(targetYaw, simulatedRotation.getTargetPitch());
                                }
                            }
                        }
                    } else if (simulatedPath.hasPlan() && simulatedPath.getExpectedPlacementCount() > maxTicks) {
                        break;
                    }
                }

                boolean wasOnGround = simulatedPlayer.onGround;
                boolean[] strafeState = null;
                float movementYaw = this.takingKnockback && knockbackSettled ? this.originalYaw : this.placeYaw;
                if (simulatedRotation != null && !(simulatedRotation instanceof SilentRotator)) {
                    strafeState = computeStrafeState(simulatedPlayer, movementYaw, this.graph.forwardKeyDown,
                            this.graph.backwardKeyDown, this.graph.leftKeyDown, this.graph.rightKeyDown);
                    if (strafeState != null) {
                        simulation.setDirectionalKeys(strafeState[0], strafeState[1], strafeState[2], strafeState[3]);
                    }
                }
                simulation.simulateTick(false);
                if (strafeState != null) {
                    simulation.setDirectionalKeys(this.graph.forwardKeyDown, this.graph.backwardKeyDown,
                            this.graph.leftKeyDown, this.graph.rightKeyDown);
                }
                if (!wasOnGround && simulatedPlayer.onGround
                        && MathHelper.floor_double(simulatedPlayer.posY) >= simulatedPath.target.getY() + 1) {
                    landedOnTarget = true;
                    if (!this.forcingCounterMotion || this.takingKnockback && this.graph.jumpKeyDown) {
                        simulationFinished = true;
                    }
                } else if (wasOnGround && !simulatedPlayer.onGround && pathExtended) {
                    simulationFinished = false;
                }
                if (this.takingKnockback && (simulatedPlayer.onGround || --remainingKnockbackTicks == 0)) {
                    knockbackSettled = true;
                }

                if (placementTarget != null) {
                    float[] source = sourceRotation(simulatedRotation);
                    int placementTicks = this.simulatePlacementTick(simulatedPath, placementTarget, simulatedPlayer,
                            source[0], source[1], tickGraph);
                    Vec3 eyePosition = simulatedPath.hitVector;
                    float targetYaw = this.takingKnockback && knockbackSettled ? this.originalYaw : this.placeYaw;
                    simulatedRotation = this.buildRotation(simulatedPlayer, eyePosition, placementTarget.hitPoint,
                            simulatedRotation, placementTicks, targetYaw);
                    simulation.setRotationController(simulatedRotation);
                }
                simulation.updateRotation();
                if (this.forcingCounterMotion && !this.takingKnockback) {
                    simulation.finishSimulation();
                }
                simulatedPath.simulatedPositions.add(simulation.getSimulatedPosition());
                if (simulatedPlayer.posY <= (double) worldBottom) {
                    simulatedPath.fail("Player would be below the world");
                } else if (simulatedPlayer.posY < (double) (simulatedPath.target.getY() - 1)
                        && simulatedPlayer.posY < (double) (simulatedPath.start.getY() - 1)) {
                    simulatedPath.fail(landedOnTarget && !simulatedPlayer.onGround
                            ? "Player would fall off after landing" : "Player would be too low to land");
                } else if ((double) pathSegment.target.getY() > simulatedPlayer.getEntityBoundingBox().minY
                        && (double) pathSegment.start.getY() > simulatedPlayer.getEntityBoundingBox().minY) {
                    simulatedPath.fail(landedOnTarget && !simulatedPlayer.onGround
                            ? "Player would fall off after landing" : "Player would be too low to land");
                } else if (placementTarget != null || simulatedPath.hasPlan() || !simulationFinished) {
                    continue;
                }
                break;
            }
        } catch (Exception ignored) {
            // The original logs and falls through to judging what the rehearsal got to.
        } finally {
            for (Map.Entry<BlockPos, IBlockState> entry : replacedStates.entrySet()) {
                SilentBlocks.set(this.world, entry.getKey(), entry.getValue());
            }
            simulation.close();
        }
        if (landedOnTarget && (simulatedPlayer.onGround || simulationFinished)) {
            pathSegment.simulatedPositions.clear();
            pathSegment.simulatedPositions.addAll(simulatedPath.simulatedPositions);
            return pathSegment;
        }
        int remainingTargets = simulatedPath.hasPlan() ? simulatedPath.pendingTargets.size() : -1;
        if (simulatedPath.hasFailed() || remainingTargets > 0) {
            pathSegment.failureReason = simulatedPath.failureReason;
            return pathSegment;
        }
        return null;
    }

    /**
     * After the last block of a long line, if you would walk on past its end, carry the line on to
     * where you would stop - as long as that is less than three blocks further.
     */
    private void tryExtendPath(EntityPlayer simulatedPlayer, MovementSnapshot tickGraph, ClutchPath simulatedPath,
                               ClutchPath pathSegment, Vector<PlacementTarget> pendingTargets) {
        MovementSimulation landing = this.simulation(simulatedPlayer, tickGraph);
        try {
            if (!this.simulateLandsOnTarget(simulatedPlayer, landing, simulatedPath)) {
                return;
            }
            EntityPlayer landingPlayer = landing.getSimulatedPlayer();
            int landingX = MathHelper.floor_double(landingPlayer.posX);
            int landingZ = MathHelper.floor_double(landingPlayer.posZ);
            int deltaX = landingX - simulatedPath.target.getX();
            int deltaZ = landingZ - simulatedPath.target.getZ();
            if (Math.abs(deltaX) + Math.abs(deltaZ) >= 3) {
                return;
            }
            ClutchPath extension = new ClutchPath(simulatedPath.target,
                    new BlockPos(landingX, simulatedPath.target.getY(), landingZ));
            this.planPlacementSearch(extension, new ArrayList<Vec3>());
            if (extension.hasPlan() && !extension.pendingTargets.isEmpty()) {
                pendingTargets.addAll(extension.pendingTargets);
                pathSegment.addTargets(extension.pendingTargets);
                pathSegment.target = extension.target;
            }
        } finally {
            landing.close();
        }
    }

    /** {@link #simulateLandsOnTarget} from a fresh rehearsal of {@code player}. */
    private boolean landsOnTargetFrom(EntityPlayer player, MovementSnapshot snapshot, ClutchPath path) {
        MovementSimulation landing = this.simulation(player, snapshot);
        try {
            return this.simulateLandsOnTarget(player, landing, path);
        } finally {
            landing.close();
        }
    }

    /**
     * Whether, keys as they are, you would walk off again rather than settle on the path's end
     * block within ten ticks.
     */
    public boolean simulateLandsOnTarget(EntityPlayer player, MovementSimulation simulation, ClutchPath path) {
        boolean leavesGround = false;
        boolean wasOnGround = player.onGround;
        BlockPos target = path.target;
        EntityPlayer simulatedPlayer = simulation.getSimulatedPlayer();
        simulation.restoreSnapshotInput();
        for (int i = 0; i < 10; ++i) {
            simulation.simulateTick();
            double motionX = simulatedPlayer.motionX;
            double motionZ = simulatedPlayer.motionZ;
            if (simulatedPlayer.onGround) {
                boolean insideTargetX = simulatedPlayer.posX > (double) target.getX() && simulatedPlayer.posX < (double) (target.getX() + 1);
                boolean insideTargetZ = simulatedPlayer.posZ > (double) target.getZ() && simulatedPlayer.posZ < (double) (target.getZ() + 1);
                if (Math.abs(motionX) < 0.005 && Math.abs(motionZ) < 0.005 && insideTargetX && insideTargetZ) {
                    leavesGround = false;
                    break;
                }
            } else if (wasOnGround) {
                leavesGround = true;
                break;
            }
            wasOnGround = simulatedPlayer.onGround;
        }
        return leavesGround;
    }

    /**
     * The next click on the path that can still happen, dropping clicks already done. Fails the
     * path if the block to click is gone or something is standing where the block would go.
     */
    public PlacementTarget resolvePlaceTarget(ClutchPath path, EntityPlayer player, IBlockState simulatedBlockState) {
        Vector<PlacementTarget> pendingTargets = path.pendingTargets;
        while (!pendingTargets.isEmpty()) {
            PlacementTarget candidate = pendingTargets.firstElement();
            Block block = FallBlocks.blockAt(this.world, candidate.supportBlock);
            if (simulatedBlockState != null && FallBlocks.isAir(block) && !FallBlocks.isAir(simulatedBlockState.getBlock())) {
                block = simulatedBlockState.getBlock();
            }
            if (FallBlocks.isReplaceable(block) && (candidate.offsetFromSupport || FallBlocks.isShapeless(block))) {
                path.fail("Block to click on was removed");
                return null;
            }
            BlockPos placedBlock = candidate.getPlacedBlock();
            if (FallBlocks.isReplaceable(FallBlocks.blockAt(this.world, placedBlock))) {
                if (FallBlocks.isPlacementSpaceClear(this.world, player, placedBlock)) {
                    return candidate;
                }
                path.fail("Entity blocking placement");
                return null;
            }
            path.hitVector = null;
            pendingTargets.removeElementAt(0);
        }
        return null;
    }

    /** Whether the rehearsal's crosshair is on a face a click there would put the block in place from. */
    public boolean canPlaceOnTarget(PlacementTarget target, MovementSimulation simulation) {
        if (target == null) {
            return false;
        }
        AxisAlignedBB placement = FallBlocks.bounds(this.world, target.getPlacedBlock());
        if (simulation.getSimulatedPlayer().getEntityBoundingBox().intersectsWith(placement)) {
            return false;
        }
        return isPlacementHit(simulation.rayTrace(3.0), target);
    }

    /** A crosshair result that places {@code target}: its face of its block, or any face next to where it goes. */
    public static boolean isPlacementHit(MovingObjectPosition hit, PlacementTarget target) {
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return false;
        }
        if (hit.getBlockPos().equals(target.supportBlock)) {
            EnumFacing requiredFacing = target.offsetFromSupport ? target.facing : null;
            return requiredFacing == null || requiredFacing == hit.sideHit;
        }
        return target.getPlacedBlock().equals(hit.getBlockPos().offset(hit.sideHit));
    }

    // ---------------------------------------------------------------- timing the aim

    /**
     * How many ticks until the target face can be clicked, rehearsed up to six ahead, and the eye
     * position and face point to aim at by then. Clicks right off the start block while standing
     * get one tick, or two when you face away from it.
     */
    public int simulatePlacementTick(ClutchPath path, PlacementTarget target, EntityPlayer player, float yaw,
                                     float pitch, MovementSnapshot placementGraph) {
        MovementSimulation simulation = this.simulation(player, placementGraph);
        try {
            if (this.counterMotion) {
                simulation.setForwardOnly();
            } else {
                simulation.restoreSnapshotInput();
            }
            EntityPlayer simulatedPlayer = simulation.getSimulatedPlayer();
            simulatedPlayer.rotationYaw = yaw;
            simulatedPlayer.rotationYawHead = yaw;
            simulatedPlayer.rotationPitch = pitch;
            if (this.forcingCounterMotion && !this.takingKnockback && player.onGround
                    && player.motionY == STATIONARY_FALL_MOTION) {
                simulation.clearInput();
            }
            double eyeHeight = this.localPlayer.getEyeHeight();
            boolean facingAwayFromTarget = false;
            boolean targetReached = false;
            int simulatedTicks = 0;
            while (simulatedTicks < 6) {
                ++simulatedTicks;
                Vec3 position = simulation.getSimulatedPosition();
                Vec3 eye = new Vec3(position.xCoord, position.yCoord + eyeHeight, position.zCoord);
                if (FallBlocks.isBlockFaceVisible(eye, this.world, target.supportBlock, target.facing)) {
                    Vec3 hit = FallBlocks.findPlacementHitPoint(this.world, eye, target, yaw, pitch);
                    if (hit != null && eye.distanceTo(hit) <= 5.0) {
                        targetReached = true;
                        target.hitPoint = hit;
                        path.hitVector = eye;
                        break;
                    }
                }
                double targetX = (double) target.supportBlock.getX() + 0.5;
                double targetZ = (double) target.supportBlock.getZ() + 0.5;
                float targetYawDelta = (float) MouseAim.yawDistance(eye.xCoord, eye.zCoord, yaw, targetX, targetZ);
                facingAwayFromTarget = Math.abs(MouseAim.wrap(targetYawDelta)) > 110.0F;
                if (position.yCoord <= (double) path.target.getY()) {
                    break;
                }
                boolean[] strafeState = null;
                if (!this.silentAim) {
                    strafeState = computeStrafeState(simulatedPlayer, this.placeYaw, this.graph.forwardKeyDown,
                            this.graph.backwardKeyDown, this.graph.leftKeyDown, this.graph.rightKeyDown);
                    if (strafeState != null) {
                        simulation.setDirectionalKeys(strafeState[0], strafeState[1], strafeState[2], strafeState[3]);
                    }
                }
                simulation.simulateTick();
                if (strafeState != null) {
                    simulation.setDirectionalKeys(this.graph.forwardKeyDown, this.graph.backwardKeyDown,
                            this.graph.leftKeyDown, this.graph.rightKeyDown);
                }
            }
            Vec3 finalPosition = simulation.getSimulatedPosition();
            if (targetReached) {
                path.hitVector = new Vec3(finalPosition.xCoord, finalPosition.yCoord + eyeHeight, finalPosition.zCoord);
                boolean targetTouchesStart = target.supportBlock.equals(path.start);
                if (targetTouchesStart && player.onGround) {
                    return facingAwayFromTarget ? 2 : 1;
                }
                return simulatedTicks;
            }
            BlockPos placedBlock = target.getPlacedBlock();
            Vec3 fallbackEye = new Vec3(placedBlock.getX() + 0.5, player.posY + eyeHeight, placedBlock.getZ() + 0.5);
            Vec3 finalHit = FallBlocks.findPlacementHitPoint(this.world, fallbackEye, target, yaw, pitch);
            path.hitVector = fallbackEye;
            target.hitPoint = finalHit == null
                    ? FallBlocks.closestPoint(fallbackEye, FallBlocks.bounds(this.world, target.supportBlock))
                    : finalHit;
            return 2;
        } finally {
            simulation.close();
        }
    }

    /**
     * Points an aim at {@code target} from {@code eye}, fast enough to arrive within
     * {@code ticksAvailable} - capped by the speed setting, plus a jitter of up to 4 from your
     * height - and holding there. Creates the controller the first time: silent or visible per the
     * setting.
     */
    public TargetRotator buildRotation(EntityPlayer player, Vec3 eye, Vec3 target, TargetRotator existing,
                                       int ticksAvailable, float referenceYaw) {
        float sourceYaw;
        float sourcePitch;
        TargetRotator controller = existing;
        if (existing == null && player != this.localPlayer && this.liveSilent != null) {
            sourceYaw = this.liveSilent.getRenderedYaw();
            sourcePitch = this.liveSilent.getRenderedPitch();
        } else if (existing == null) {
            sourceYaw = player.rotationYaw;
            sourcePitch = player.rotationPitch;
        } else {
            sourceYaw = existing.getCurrentYaw();
            sourcePitch = existing.getCurrentPitch();
        }
        float[] targetRotation = MouseAim.toward(eye, target, sourceYaw, sourcePitch);
        if (existing == null) {
            controller = this.silentAim ? new SilentRotator(player)
                    : new EntityRotator(targetRotation[0], targetRotation[1], player);
        }
        controller.setTargetRotation(targetRotation[0], targetRotation[1]);
        float currentYaw;
        float currentPitch;
        if (controller instanceof SilentRotator) {
            SilentRotator silent = (SilentRotator) controller;
            silent.setReferenceYawOverride(referenceYaw);
            silent.setRelativeMode(false);
            currentYaw = silent.getRenderedYaw();
            currentPitch = silent.getRenderedPitch();
        } else {
            currentYaw = player.rotationYaw;
            currentPitch = player.rotationPitch;
        }
        float yawDistance = Math.abs(MouseAim.wrap(targetRotation[0] - currentYaw));
        float pitchDistance = Math.abs(targetRotation[1] - currentPitch);
        float rotationSpeed = (yawDistance + pitchDistance) / 1.8F / (float) Math.max(ticksAvailable, 1);
        float maximumSpeed = 15.0F + 85.0F * (this.speed / 10.0F);
        rotationSpeed = Math.min(maximumSpeed + (float) ((int) (player.posY * 100.0) % 5), rotationSpeed);
        controller.setSpeed(rotationSpeed);
        controller.setTolerance(0.0F);
        controller.setClampStepToRemaining(true);
        controller.setCubicAcceleration(false);
        controller.setLinearAcceleration(false);
        controller.setScaleAxesProportionally(true);
        controller.setRandomizeMovement(false);
        controller.setRetainAfterCompletion(true);
        return controller;
    }

    /** Where the aim is now: the rehearsal's own, else the live silent one, else your view. */
    private float[] sourceRotation(TargetRotator simulatedRotation) {
        if (simulatedRotation == null) {
            if (this.liveSilent != null) {
                return new float[]{this.liveSilent.getRenderedYaw(), this.liveSilent.getRenderedPitch()};
            }
            return new float[]{this.localPlayer.rotationYaw, this.localPlayer.rotationPitch};
        }
        if (simulatedRotation instanceof SilentRotator) {
            SilentRotator silent = (SilentRotator) simulatedRotation;
            return new float[]{silent.getRenderedYaw(), silent.getRenderedPitch()};
        }
        return new float[]{simulatedRotation.getCurrentYaw(), simulatedRotation.getCurrentPitch()};
    }

    // ---------------------------------------------------------------- movement

    /**
     * The keys that keep you travelling where you meant to while the view turns away: the
     * direction your keys pointed from {@code targetYaw}, re-expressed from where you now face.
     * Null when no movement key is down.
     * <p>
     * The original builds the direction from your current view instead of {@code targetYaw}, and
     * passes back and right to its direction helper in the wrong slots; together those keep
     * forward movement straight but send strafing and backward movement the wrong way. Both
     * mistakes are corrected here, in the rehearsal and live alike.
     *
     * @return {forward, back, left, right}
     */
    public static boolean[] computeStrafeState(EntityPlayer player, float targetYaw, boolean forward,
                                               boolean backward, boolean left, boolean right) {
        if (!forward && !backward && !left && !right) {
            return null;
        }
        float movementYaw = MovementSimulation.movementYaw(targetYaw, forward, backward, left, right);
        return MovementSimulation.keysToward(player.rotationYaw, movementYaw, 0.45F);
    }

    /** The yaw facing back along the way you are moving. The original's {@code getMovementYaw}. */
    public static float movementYaw(EntityPlayer player) {
        double motionX = player.motionX;
        double motionZ = player.motionZ;
        double yaw = Math.toDegrees(Math.atan2(motionZ, motionX));
        if (motionX != 0.0 || motionZ != 0.0) {
            yaw = MouseAim.wrap(yaw - 90.0);
        }
        return (float) (yaw - 180.0);
    }

    /** The block a stack places, or air. The original's {@code BlockUtil.E}. */
    public static IBlockState blockStateOf(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof ItemBlock)) {
            return Blocks.air.getDefaultState();
        }
        return ((ItemBlock) stack.getItem()).getBlock().getDefaultState();
    }
}
