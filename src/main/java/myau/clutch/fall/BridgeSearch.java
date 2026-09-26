package myau.clutch.fall;

import myau.clutch.PlacementTarget;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.function.ToIntFunction;

/**
 * Finds the line of clicks that builds from a solid block to the block you will land on: each
 * click puts a block on the face of the last one, stepping along x, y or z toward the target.
 * Ported from Vape's {@code BlockInSearchPlanner} with its placement search strategy.
 * <p>
 * It tries the steps that score best first, remembers the best line found from each block it
 * reaches, and keeps a partial line when no complete one exists - exactly as the original does,
 * memo shortcut included, so it picks the same lines.
 */
final class BridgeSearch {
    private static final EnumFacing[] DIRECTIONS = EnumFacing.values();

    private final int maxDepth;
    private final World world;
    private final ToIntFunction<Vector<PlacementTarget>> scorer;
    private final Map<BlockPos, Vector<PlacementTarget>> pathCache = new HashMap<BlockPos, Vector<PlacementTarget>>();

    BridgeSearch(int maxDepth, World world, ToIntFunction<Vector<PlacementTarget>> scorer) {
        this.maxDepth = maxDepth;
        this.world = world;
        this.scorer = scorer;
    }

    Vector<PlacementTarget> findPath(BlockPos start, BlockPos destination) {
        this.pathCache.clear();
        return this.findPathRecursive(start, destination, 0, new Vector<PlacementTarget>());
    }

    private Vector<PlacementTarget> findPathRecursive(BlockPos current, BlockPos destination, int depth,
                                                      Vector<PlacementTarget> currentPath) {
        if (this.pathCache.containsKey(current)) {
            return this.pathCache.get(current);
        }
        if (depth > this.maxDepth) {
            return null;
        }
        int deltaX = destination.getX() - current.getX();
        int deltaY = destination.getY() - current.getY();
        int deltaZ = destination.getZ() - current.getZ();
        int xDirection = deltaX > 0 ? 5 : (deltaX < 0 ? 4 : -1);
        int yDirection = deltaY > 0 ? 1 : (deltaY < 0 ? 0 : -1);
        int zDirection = deltaZ > 0 ? 3 : (deltaZ < 0 ? 2 : -1);

        List<PlacementTarget> candidates = new ArrayList<PlacementTarget>();
        for (int directionIndex : new int[]{xDirection, yDirection, zDirection}) {
            if (directionIndex == -1) {
                continue;
            }
            EnumFacing direction = DIRECTIONS[directionIndex];
            if (!FallBlocks.isReplaceable(FallBlocks.blockAt(this.world, current.offset(direction)))) {
                continue;
            }
            PlacementTarget target = new PlacementTarget(current, direction);
            target.depth = depth;
            candidates.add(target);
        }
        candidates.sort(Comparator.comparingInt(candidate -> this.scoreCandidate(currentPath, candidate)));

        Vector<PlacementTarget> bestPath = null;
        int bestScore = Integer.MAX_VALUE;
        for (PlacementTarget candidate : candidates) {
            BlockPos nextBlock = candidate.supportBlock.offset(candidate.facing);
            Vector<PlacementTarget> candidatePath = new Vector<PlacementTarget>(currentPath);
            candidatePath.add(candidate);
            if (nextBlock.equals(destination)) {
                bestPath = candidatePath;
                break;
            }
            Vector<PlacementTarget> recursivePath = this.findPathRecursive(nextBlock, destination, depth + 1, candidatePath);
            if (recursivePath != null && !recursivePath.isEmpty()) {
                candidatePath = new Vector<PlacementTarget>(recursivePath);
            }
            if (candidatePath.isEmpty()) {
                break;
            }
            int score = this.scorer.applyAsInt(candidatePath);
            if (bestPath == null || score < bestScore) {
                bestPath = candidatePath;
                bestScore = score;
            }
        }
        this.pathCache.put(current, bestPath);
        return bestPath;
    }

    private int scoreCandidate(Vector<PlacementTarget> currentPath, PlacementTarget candidate) {
        Vector<PlacementTarget> candidatePath = new Vector<PlacementTarget>(currentPath);
        candidatePath.add(candidate);
        return this.scorer.applyAsInt(candidatePath);
    }
}
