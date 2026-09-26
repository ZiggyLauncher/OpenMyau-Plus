package myau.clutch.fall;

import myau.clutch.PlacementTarget;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Vector;

/**
 * One way out of a fall: build a line of blocks from {@code start} - something solid already in
 * the world - to {@code target}, the block your feet will come down on. The original's
 * {@code BlockPlacementPathSegment} with its {@code BlockPlacementPathSegmentState} folded in.
 */
public final class ClutchPath {
    public final BlockPos start;
    public BlockPos target;
    /** Where the rehearsal had the player each tick. */
    public final List<Vec3> simulatedPositions;
    /** The clicks still to make, in order, or null once none are planned. */
    public Vector<PlacementTarget> pendingTargets;
    private int expectedCount;
    private final HashSet<Long> targetPositions = new HashSet<Long>();
    public EnumFacing placementFacing;
    /** The eye position the next click is aimed from. */
    public Vec3 hitVector;
    public String failureReason;
    public int simulationTickLimit;

    public ClutchPath(BlockPos start, BlockPos target, List<Vec3> simulatedPositions) {
        this.start = start;
        this.target = target;
        this.simulatedPositions = simulatedPositions;
    }

    public ClutchPath(BlockPos start, BlockPos target) {
        this(start, target, new ArrayList<Vec3>());
    }

    public void plan(EnumFacing facing, Vector<PlacementTarget> targets) {
        this.placementFacing = facing;
        this.pendingTargets = targets;
        this.targetPositions.clear();
        for (PlacementTarget target : targets) {
            this.targetPositions.add(target.getPlacedBlock().toLong());
        }
        this.expectedCount = targets.size();
    }

    public boolean hasPlan() {
        return this.pendingTargets != null;
    }

    public void addTargets(Collection<PlacementTarget> targets) {
        this.pendingTargets.addAll(targets);
        for (PlacementTarget target : targets) {
            this.targetPositions.add(target.getPlacedBlock().toLong());
        }
        this.expectedCount += targets.size();
    }

    public boolean containsPosition(BlockPos pos) {
        return this.targetPositions.contains(pos.toLong());
    }

    public int getPendingPlacementCount() {
        return this.pendingTargets != null ? this.pendingTargets.size() : 0;
    }

    public int getExpectedPlacementCount() {
        return this.pendingTargets != null ? this.expectedCount : 0;
    }

    public boolean hasFailed() {
        return this.failureReason != null;
    }

    /** Gives up on this path for {@code reason}; the first reason given sticks. */
    public void fail(String reason) {
        if (this.failureReason == null) {
            this.failureReason = reason + " (Left: " + this.getPendingPlacementCount() + ")";
            this.hitVector = null;
            this.pendingTargets = null;
        }
    }
}
