package myau.clutch;

import myau.module.modules.Clutch;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

import java.util.Vector;

/**
 * Rules for the bed finder's tunnel search; the original's
 * {@code ClutchSolidBlockPathSearchStrategy}. The search ends at a bed, may run four columns deep,
 * and ranks tunnels by how many blocks they would take to seal rather than by length, so a longer
 * tunnel along an existing wall beats a shorter one through the open.
 */
public final class BedSearch implements SearchStrategy<PlacementNode> {
    private final Clutch clutch;
    private final World world;

    public BedSearch(Clutch clutch, World world) {
        this.clutch = clutch;
        this.world = world;
    }

    @Override
    public int getMaxDepth() {
        return 4;
    }

    @Override
    public boolean isValidBlock(BlockPos pos) {
        return ClutchPaths.isBed(this.world, pos);
    }

    @Override
    public int scorePath(Vector<PlacementNode> path) {
        return this.clutch.computePathCost(this.world, path);
    }
}
