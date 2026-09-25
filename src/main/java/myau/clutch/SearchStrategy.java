package myau.clutch;

import net.minecraft.util.BlockPos;

import java.util.Vector;

/**
 * The rules a path search follows: how deep it may go, which blocks it may pass through, which
 * blocks end it, and how competing paths are ranked. Both searches the module runs - sealing the
 * walls and tunnelling toward a bed - share the same recursive walk and differ only in these.
 */
public interface SearchStrategy<T> {
    int getMaxDepth();

    /** Whether the search may step into this block at all. */
    default boolean canVisit(BlockPos pos) {
        return true;
    }

    /** Whether reaching this block ends the search successfully. */
    boolean isValidBlock(BlockPos pos);

    default int scorePath(Vector<T> path, int depth) {
        return this.scorePath(path);
    }

    /** Lower is better. */
    default int scorePath(Vector<T> path) {
        return path.size();
    }
}
