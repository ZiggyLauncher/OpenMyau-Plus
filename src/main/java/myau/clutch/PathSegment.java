package myau.clutch;

import net.minecraft.util.EnumFacing;

import java.util.Vector;

/**
 * One way of sealing one side of a column: the direction it seals, and the clicks it takes, in
 * the order they have to happen.
 * <p>
 * This is the original's {@code BlockPlacementPathSegmentState}, which lives in a package that was
 * not included with the rest of the sources; it is reconstructed from how the module uses it.
 */
public final class PathSegment {
    public final EnumFacing placementFacing;
    public final Vector<PlacementTarget> pendingTargets;

    public PathSegment(EnumFacing placementFacing, Vector<PlacementTarget> pendingTargets) {
        this.placementFacing = placementFacing;
        this.pendingTargets = pendingTargets;
    }

    public void clearPendingTargets() {
        this.pendingTargets.clear();
    }
}
