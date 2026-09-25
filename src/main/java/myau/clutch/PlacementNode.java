package myau.clutch;

import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * One column of the enclosure: the feet-level block, and - for a column the player stands in -
 * the head-level block above it. The original calls these the base and the support block; the
 * names are kept so the two versions line up.
 * <p>
 * A column is sealed by filling its four horizontal neighbours. Faces that another column already
 * covers, or that face the way the tunnel continues, are marked blocked so they are not sealed.
 * A roof node has no support block: it is a single block above a column's head.
 */
public final class PlacementNode {
    /** The feet-level block of the column. */
    public final BlockPos baseBlock;
    /** The head-level block above it, or null for a single-block node such as a roof. */
    public final BlockPos supportBlock;
    /** The face the head-level block connects through to the previous column, if any. */
    public final EnumFacing supportFacing;
    /** The face the feet-level block connects through to the next column, if any. */
    public EnumFacing baseFacing;

    public final List<PathSegment> candidatePaths = new ArrayList<PathSegment>();
    public final List<EnumFacing> blockedBaseFacings = new ArrayList<EnumFacing>();
    public final List<EnumFacing> blockedSupportFacings = new ArrayList<EnumFacing>();
    /** Neighbours that must stay open, so no seal is planned into them. */
    public final HashSet<BlockPos> occupiedBlocks = new HashSet<BlockPos>();
    /** 0 before the first attempt, positive after a re-plan, -1 once its paths are exhausted. */
    public int retryState;

    public PlacementNode(BlockPos baseBlock) {
        this(baseBlock, null, false);
    }

    public PlacementNode(BlockPos baseBlock, EnumFacing supportFacing) {
        this(baseBlock, supportFacing, true);
    }

    public PlacementNode(BlockPos baseBlock, EnumFacing supportFacing, boolean withSupportBlock) {
        this.baseBlock = baseBlock;
        this.supportFacing = supportFacing;
        this.supportBlock = withSupportBlock ? baseBlock.up() : null;
    }

    public boolean hasSupportBlock() {
        return this.supportBlock != null;
    }

    public void blockBaseFacing(EnumFacing facing) {
        this.blockedBaseFacings.add(facing);
        this.occupiedBlocks.add(this.baseBlock.offset(facing));
    }

    public void blockSupportFacing(EnumFacing facing) {
        this.blockedSupportFacings.add(facing);
        if (this.supportBlock != null) {
            this.occupiedBlocks.add(this.supportBlock.offset(facing));
        }
    }

    public boolean isBaseFacingBlocked(EnumFacing facing) {
        return this.blockedBaseFacings.contains(facing);
    }

    public boolean isSupportFacingBlocked(EnumFacing facing) {
        return this.blockedSupportFacings.contains(facing);
    }

    /** Always returns true, so it can drive a {@code removeIf} that empties the node list. */
    public boolean clearCandidatePaths() {
        for (PathSegment segment : this.candidatePaths) {
            segment.clearPendingTargets();
        }
        this.candidatePaths.clear();
        return true;
    }
}
