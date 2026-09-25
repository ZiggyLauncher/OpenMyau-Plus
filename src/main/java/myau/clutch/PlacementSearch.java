package myau.clutch;

import net.minecraft.entity.Entity;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

import java.util.Set;
import java.util.Vector;

/**
 * Rules for finding the clicks that seal one side of a column; the original's
 * {@code ClutchBlockPlacementPathSearchStrategy}.
 * <p>
 * A search may build off anything solid and non-interactive, and also off blocks that other
 * sides' plans are going to place - they will exist by the time they are clicked. It may not
 * build into the player's own columns, into a neighbour the tunnel has to keep open, or into a
 * spot another entity is standing in. Two supports deep is the limit: past that the chain of
 * placements is too long to finish before it matters.
 */
public final class PlacementSearch implements SearchStrategy<PlacementTarget> {
    private final World world;
    private final Entity player;
    private final PlacementNode node;
    /** The player's own columns: never built into. */
    private final Set<BlockPos> excludedBlocks;
    /** Blocks other sides are already planning to place: safe to build off. */
    private final Set<BlockPos> allowedBlocks;

    public PlacementSearch(Set<BlockPos> excludedBlocks, PlacementNode node, World world,
                           Entity player, Set<BlockPos> allowedBlocks) {
        this.excludedBlocks = excludedBlocks;
        this.node = node;
        this.world = world;
        this.player = player;
        this.allowedBlocks = allowedBlocks;
    }

    @Override
    public int getMaxDepth() {
        return 2;
    }

    @Override
    public boolean isValidBlock(BlockPos pos) {
        return this.allowedBlocks.contains(pos) || ClutchPaths.canPlaceAgainst(this.world, pos);
    }

    @Override
    public boolean canVisit(BlockPos pos) {
        if (this.excludedBlocks.contains(pos) || this.node.occupiedBlocks.contains(pos)) {
            return false;
        }
        return ClutchPaths.isPlacementSpaceClear(this.world, this.player, pos);
    }

    @Override
    public int scorePath(Vector<PlacementTarget> path) {
        return path.size();
    }
}
