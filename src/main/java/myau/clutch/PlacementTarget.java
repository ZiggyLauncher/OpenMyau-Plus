package myau.clutch;

import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Vec3;

/**
 * One right click: which block to click, and on which face.
 * <p>
 * Normally the block ends up on the far side of the clicked face ({@code offsetFromSupport}), the
 * way placing against a wall works. The exception is a replaceable block that can still be
 * clicked - tall grass, a thin snow layer - where the new block goes <i>into</i> the clicked
 * position instead, so the clicked block and the placed block are the same one.
 */
public final class PlacementTarget {
    /** The block that is clicked on. */
    public final BlockPos supportBlock;
    /** The face clicked; null means "any point on the block", for replaceable targets. */
    public final EnumFacing facing;
    /** True when the placed block is next to the support, false when it replaces the support. */
    public final boolean offsetFromSupport;
    /** How many placements deep in the chain this one is; deeper ones must go first. */
    public int depth;
    /** The exact point on the face to aim at, filled in once a clear line to it is found. */
    public Vec3 hitPoint;

    private BlockPos placedBlock;

    public PlacementTarget(BlockPos supportBlock, EnumFacing facing) {
        this(supportBlock, facing, true);
    }

    public PlacementTarget(BlockPos supportBlock, EnumFacing facing, boolean offsetFromSupport) {
        this.supportBlock = supportBlock;
        this.facing = facing;
        this.offsetFromSupport = offsetFromSupport;
    }

    /** Where the block actually ends up once this click lands. */
    public BlockPos getPlacedBlock() {
        if (this.placedBlock == null) {
            this.placedBlock = this.offsetFromSupport && this.facing != null
                    ? this.supportBlock.offset(this.facing)
                    : this.supportBlock;
        }
        return this.placedBlock;
    }
}
