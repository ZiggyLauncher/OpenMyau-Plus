package myau.clutch;

import myau.util.BlockUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.util.Vec3i;
import net.minecraft.world.World;

import java.util.List;
import java.util.Stack;
import java.util.Vector;

/**
 * The path searches and placement geometry behind Clutch, ported from the original's
 * {@code ClutchPlacementPathUtils}.
 * <p>
 * The original is written against its own block helpers, which survive only as single obfuscated
 * letters. Each was mapped by how it is used rather than by guessing at its name:
 * <ul>
 *     <li>"replaceable" - the position can be built into: air, water, tall grass, a thin snow
 *     layer. This client's {@link BlockUtil#isReplaceable(Block)} is exactly that.</li>
 *     <li>"empty" - specifically air. It separates a position that is open from one holding
 *     something replaceable but still clickable, such as grass, which is placed into directly
 *     rather than against.</li>
 *     <li>"filled" - the opposite of replaceable: a real block you can stand on or place against.
 *     </li>
 *     <li>"interactive" - a block a right click would open or toggle instead of building against,
 *     such as a chest or a door. This client's {@link BlockUtil#isInteractable(Block)}.</li>
 * </ul>
 * Only the searches the module actually runs are ported; the original file also carries helpers
 * for its separate Block-In module that Clutch never calls.
 */
public final class ClutchPaths {
    private static final EnumFacing[] ALL = EnumFacing.values();

    private ClutchPaths() {
    }

    // ---------------------------------------------------------------- block classification

    public static Block blockAt(World world, BlockPos pos) {
        return world.getBlockState(pos).getBlock();
    }

    public static boolean isReplaceable(World world, BlockPos pos) {
        return BlockUtil.isReplaceable(blockAt(world, pos));
    }

    public static boolean isEmpty(World world, BlockPos pos) {
        return blockAt(world, pos).getMaterial() == Material.air;
    }

    public static boolean isFilled(World world, BlockPos pos) {
        return !isReplaceable(world, pos);
    }

    public static boolean isBed(World world, BlockPos pos) {
        return blockAt(world, pos) instanceof BlockBed;
    }

    /** A block a right click lands on and builds against, rather than opening. */
    public static boolean canPlaceAgainst(World world, BlockPos pos) {
        Block block = blockAt(world, pos);
        return !BlockUtil.isReplaceable(block) && !BlockUtil.isInteractable(block);
    }

    /**
     * The block's outline. Air and anything without one is treated as a full cube, because the
     * only reason to ask about an empty position is that a full block is about to go there.
     */
    public static AxisAlignedBB bounds(World world, BlockPos pos) {
        Block block = blockAt(world, pos);
        AxisAlignedBB box = null;
        if (block.getMaterial() != Material.air) {
            block.setBlockBoundsBasedOnState(world, pos);
            box = block.getSelectedBoundingBox(world, pos);
        }
        return box != null ? box : fullCube(pos);
    }

    public static AxisAlignedBB fullCube(BlockPos pos) {
        return new AxisAlignedBB(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0);
    }

    private static AxisAlignedBB shrink(AxisAlignedBB box, double amount) {
        return new AxisAlignedBB(box.minX + amount, box.minY + amount, box.minZ + amount,
                box.maxX - amount, box.maxY - amount, box.maxZ - amount);
    }

    // ---------------------------------------------------------------- the two searches

    /**
     * Finds the clicks that fill {@code currentBlock}.
     * <p>
     * If something is next to it that can be built against, and the face pointing at it is
     * visible from where the player will stand, one click does it. If not, the search steps into
     * a neighbour, fills that first, and then builds off it - up to the strategy's depth. The
     * returned list is in placement order: supports first, the block itself last. Neighbours are
     * tried below, ahead, right, left, behind, then above, and the path needing fewest clicks
     * wins.
     *
     * @param originBlock    the column the player will be standing in
     * @param initialFacing  the direction from the column out to the wall being sealed
     * @param incomingFacing the direction the search stepped in to reach {@code currentBlock}
     */
    public static Vector<PlacementTarget> findPlacementPath(BlockPos originBlock, Vec3 eyePosition,
                                                            Entity player, World world,
                                                            BlockPos currentBlock, EnumFacing initialFacing,
                                                            EnumFacing incomingFacing,
                                                            SearchStrategy<PlacementTarget> searchStrategy,
                                                            int depth) {
        if (depth > searchStrategy.getMaxDepth() || !searchStrategy.canVisit(currentBlock)) {
            return null;
        }
        if (!isEmpty(world, currentBlock)) {
            // Occupied. Only something replaceable yet clickable - grass, a snow layer - can be
            // built into, and then by clicking it directly rather than something beside it.
            if (isReplaceable(world, currentBlock)
                    && canRayTracePlacement(originBlock, eyePosition.yCoord, world, currentBlock, incomingFacing.getOpposite())) {
                PlacementTarget target = new PlacementTarget(currentBlock, incomingFacing.getOpposite(), false);
                target.depth = depth;
                Vector<PlacementTarget> path = new Vector<PlacementTarget>();
                path.add(target);
                return path;
            }
            return null;
        }

        int horizontalIndex = initialFacing.getHorizontalIndex();
        if (horizontalIndex == -1) {
            horizontalIndex = 0;
        }
        int forward = EnumFacing.getHorizontal(horizontalIndex).getIndex();
        int right = EnumFacing.getHorizontal((horizontalIndex + 1) % 4).getIndex();
        int left = EnumFacing.getHorizontal((horizontalIndex + 3) % 4).getIndex();
        int backward = EnumFacing.getHorizontal((horizontalIndex + 2) % 4).getIndex();
        int[] facingOrder = {EnumFacing.DOWN.getIndex(), forward, right, left, backward, EnumFacing.UP.getIndex()};

        int bestScore = Integer.MAX_VALUE;
        Vector<PlacementTarget> bestPath = null;
        for (int facingIndex : facingOrder) {
            EnumFacing nextFacing = ALL[facingIndex];
            if (nextFacing == initialFacing.getOpposite()) {
                // That way is the player's own column.
                continue;
            }
            BlockPos adjacentBlock = currentBlock.offset(nextFacing);
            EnumFacing targetFacing = isReplaceable(world, adjacentBlock) ? null : nextFacing.getOpposite();
            if (!isBlockFaceVisible(eyePosition, world, adjacentBlock, targetFacing)) {
                continue;
            }

            Vector<PlacementTarget> candidatePath = new Vector<PlacementTarget>();
            if (searchStrategy.isValidBlock(adjacentBlock)) {
                // A support that is still air is one planned by an earlier click: it will be a
                // full block by the time it is needed, so its face is assumed and not traced.
                boolean plannedSupport = isEmpty(world, adjacentBlock);
                if (!plannedSupport && !canRayTracePlacement(originBlock, eyePosition.yCoord, world, adjacentBlock, targetFacing)) {
                    continue;
                }
                targetFacing = plannedSupport ? nextFacing.getOpposite() : targetFacing;
                PlacementTarget target = new PlacementTarget(adjacentBlock, targetFacing);
                target.depth = depth;
                candidatePath.add(target);
            } else {
                Vector<PlacementTarget> deeperPath = findPlacementPath(originBlock, eyePosition, player, world,
                        adjacentBlock, initialFacing, nextFacing, searchStrategy, depth + 1);
                if (deeperPath != null && !deeperPath.isEmpty()) {
                    PlacementTarget target = new PlacementTarget(adjacentBlock, nextFacing.getOpposite());
                    target.depth = depth;
                    candidatePath.addAll(deeperPath);
                    candidatePath.add(target);
                }
            }
            if (candidatePath.isEmpty()) {
                continue;
            }
            int candidateScore = searchStrategy.scorePath(candidatePath);
            if (bestPath != null && candidateScore >= bestScore) {
                continue;
            }
            bestPath = candidatePath;
            bestScore = candidateScore;
        }
        return bestPath;
    }

    /**
     * Finds a run of columns from {@code currentBlock} to a bed, for the tunnel the bed finder
     * builds. Each step goes ahead, left, right or down; once a step has turned off the original
     * heading, only straight ahead is allowed, so the tunnel does not wander.
     */
    public static Stack<PlacementNode> findBlockPlacementPath(BlockPos currentBlock, EnumFacing incomingFacing,
                                                             EnumFacing initialFacing,
                                                             SearchStrategy<PlacementNode> searchStrategy,
                                                             int depth) {
        if (depth > searchStrategy.getMaxDepth() || !searchStrategy.canVisit(currentBlock)) {
            return null;
        }
        int horizontalIndex = initialFacing.getHorizontalIndex();
        if (horizontalIndex == -1) {
            return null;
        }
        int forward = EnumFacing.getHorizontal(horizontalIndex).getIndex();
        int left = EnumFacing.getHorizontal(horizontalIndex == 0 ? 3 : horizontalIndex - 1).getIndex();
        int right = EnumFacing.getHorizontal((horizontalIndex + 1) % 4).getIndex();
        int down = EnumFacing.DOWN.getIndex();
        int[] facingOrder = {forward, left, right, down};
        if (incomingFacing != null && incomingFacing != EnumFacing.DOWN && incomingFacing != initialFacing) {
            facingOrder = new int[]{forward};
        }

        EnumFacing reverseIncomingFacing = incomingFacing == null ? null : incomingFacing.getOpposite();
        boolean useSupportBlock = incomingFacing == null || incomingFacing != EnumFacing.DOWN;
        int bestScore = Integer.MAX_VALUE;
        Stack<PlacementNode> bestPath = null;
        for (int facingIndex : facingOrder) {
            EnumFacing nextFacing = ALL[facingIndex];
            if (nextFacing == reverseIncomingFacing || nextFacing == initialFacing.getOpposite()) {
                continue;
            }
            BlockPos adjacentBlock = currentBlock.offset(nextFacing);
            Stack<PlacementNode> candidatePath = new Stack<PlacementNode>();
            if (searchStrategy.isValidBlock(adjacentBlock)) {
                PlacementNode node = new PlacementNode(currentBlock, reverseIncomingFacing, useSupportBlock);
                node.baseFacing = nextFacing;
                candidatePath.add(node);
            } else {
                Stack<PlacementNode> deeperPath = findBlockPlacementPath(adjacentBlock, nextFacing, initialFacing,
                        searchStrategy, depth + 1);
                if (deeperPath != null && !deeperPath.isEmpty()) {
                    PlacementNode node = new PlacementNode(currentBlock, reverseIncomingFacing, useSupportBlock);
                    node.baseFacing = nextFacing;
                    candidatePath.addAll(deeperPath);
                    candidatePath.add(node);
                }
            }
            if (candidatePath.isEmpty()) {
                continue;
            }
            int candidateScore = searchStrategy.scorePath(candidatePath);
            if (bestPath != null && candidateScore >= bestScore) {
                continue;
            }
            bestPath = candidatePath;
            bestScore = candidateScore;
        }
        return bestPath;
    }

    // ---------------------------------------------------------------- aiming geometry

    /**
     * The first point on the clicked face that the eye has a clear line to, scanning from the
     * face centre outward in ten-percent steps, so the aim lands as close to the middle of the
     * face as the surroundings allow.
     */
    public static Vec3 findFirstPlacementHitPoint(Entity player, World world, Vec3 eyePosition,
                                                  PlacementTarget placementTarget) {
        BlockPos supportBlock = placementTarget.supportBlock;
        EnumFacing facing = placementTarget.facing;
        AxisAlignedBB bounds = bounds(world, supportBlock);
        if (facing == null) {
            return closestPoint(eyePosition, bounds);
        }
        bounds = shrink(bounds, 0.002);
        Vec3i direction = facing.getDirectionVec();
        double sizeX = bounds.maxX - bounds.minX;
        double sizeY = bounds.maxY - bounds.minY;
        double sizeZ = bounds.maxZ - bounds.minZ;
        int facingIndex = facing.getIndex();

        for (int insetPercent = 0; insetPercent <= 100; insetPercent += 10) {
            double scale = 1.0 - insetPercent / 100.0;
            double insetX = sizeX / 2.0 * scale;
            double insetY = sizeY / 2.0 * scale;
            double insetZ = sizeZ / 2.0 * scale;
            double minX = bounds.minX + (direction.getX() > 0 ? sizeX : (direction.getX() < 0 ? 0.0 : insetX));
            double minY = bounds.minY + (direction.getY() > 0 ? sizeY : (direction.getY() < 0 ? 0.0 : insetY));
            double minZ = bounds.minZ + (direction.getZ() > 0 ? sizeZ : (direction.getZ() < 0 ? 0.0 : insetZ));
            double maxX = bounds.maxX - (direction.getX() < 0 ? sizeX : (direction.getX() > 0 ? 0.0 : insetX));
            double maxY = bounds.maxY - (direction.getY() < 0 ? sizeY : (direction.getY() > 0 ? 0.0 : insetY));
            double maxZ = bounds.maxZ - (direction.getZ() < 0 ? sizeZ : (direction.getZ() > 0 ? 0.0 : insetZ));
            for (Vec3 corner : faceCorners(facingIndex, minX, minY, minZ, maxX, maxY, maxZ)) {
                MovingObjectPosition hit = world.rayTraceBlocks(eyePosition, corner, false, false, false);
                if (isExpectedBlockHit(hit, supportBlock, facingIndex)) {
                    return corner;
                }
            }
        }
        return null;
    }

    /** The four corners of one face of a box, in a fixed winding per face. */
    static Vec3[] faceCorners(int facingIndex, double minX, double minY, double minZ,
                              double maxX, double maxY, double maxZ) {
        switch (facingIndex) {
            case 0:
                return new Vec3[]{new Vec3(minX, minY, minZ), new Vec3(minX, minY, maxZ),
                        new Vec3(maxX, minY, maxZ), new Vec3(maxX, minY, minZ)};
            case 1:
                return new Vec3[]{new Vec3(minX, maxY, minZ), new Vec3(maxX, maxY, minZ),
                        new Vec3(maxX, maxY, maxZ), new Vec3(minX, maxY, maxZ)};
            case 2:
                return new Vec3[]{new Vec3(minX, minY, minZ), new Vec3(maxX, minY, minZ),
                        new Vec3(maxX, maxY, minZ), new Vec3(minX, maxY, minZ)};
            case 3:
                return new Vec3[]{new Vec3(minX, minY, maxZ), new Vec3(maxX, minY, maxZ),
                        new Vec3(maxX, maxY, maxZ), new Vec3(minX, maxY, maxZ)};
            case 4:
                return new Vec3[]{new Vec3(minX, minY, minZ), new Vec3(minX, minY, maxZ),
                        new Vec3(minX, maxY, maxZ), new Vec3(minX, maxY, minZ)};
            case 5:
                return new Vec3[]{new Vec3(maxX, minY, minZ), new Vec3(maxX, minY, maxZ),
                        new Vec3(maxX, maxY, maxZ), new Vec3(maxX, maxY, minZ)};
            default:
                return new Vec3[0];
        }
    }

    private static Vec3 closestPoint(Vec3 point, AxisAlignedBB box) {
        return new Vec3(
                Math.max(box.minX, Math.min(box.maxX, point.xCoord)),
                Math.max(box.minY, Math.min(box.maxY, point.yCoord)),
                Math.max(box.minZ, Math.min(box.maxZ, point.zCoord)));
    }

    /** Whether a trace landed on exactly this block, on exactly this face. */
    public static boolean isExpectedBlockHit(MovingObjectPosition hit, BlockPos block, int facingIndex) {
        return hit != null
                && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && hit.sideHit != null
                && hit.sideHit.getIndex() == facingIndex
                && block.equals(hit.getBlockPos());
    }

    /**
     * Whether a face can be clicked from here at all. A face only faces you if you are on its side
     * of the block - the top of a block is unreachable from below it however clear the line is.
     */
    public static boolean isBlockFaceVisible(Vec3 eyePosition, World world, BlockPos pos, EnumFacing facing) {
        if (facing == null) {
            return true;
        }
        AxisAlignedBB bounds = bounds(world, pos);
        switch (facing.getIndex()) {
            case 1:
                return eyePosition.yCoord > bounds.maxY;
            case 0:
                return bounds.minY > eyePosition.yCoord;
            case 2:
                return bounds.minZ > eyePosition.zCoord;
            case 3:
                return eyePosition.zCoord > bounds.maxZ;
            case 4:
                return bounds.minX > eyePosition.xCoord;
            case 5:
                return eyePosition.xCoord > bounds.maxX;
            default:
                return false;
        }
    }

    /**
     * Whether any corner of the face can be reached from the middle of the column the player will
     * stand in, at eye height - that is, nothing else is in the way of it.
     */
    public static boolean canRayTracePlacement(BlockPos sourceBlock, double eyeY, World world,
                                               BlockPos targetBlock, EnumFacing facing) {
        AxisAlignedBB targetBounds = shrink(bounds(world, targetBlock), 0.002);
        Vec3 rayStart = new Vec3(sourceBlock.getX() + 0.5, eyeY, sourceBlock.getZ() + 0.5);
        if (facing == null) {
            MovingObjectPosition hit = world.rayTraceBlocks(rayStart, closestPoint(rayStart, targetBounds), false, false, false);
            return isMissOrTargetBlock(hit, targetBlock);
        }
        for (Vec3 corner : faceCorners(facing.getIndex(), targetBounds.minX, targetBounds.minY, targetBounds.minZ,
                targetBounds.maxX, targetBounds.maxY, targetBounds.maxZ)) {
            MovingObjectPosition hit = world.rayTraceBlocks(rayStart, corner, false, false, false);
            if (isMissOrTargetBlock(hit, targetBlock)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMissOrTargetBlock(MovingObjectPosition hit, BlockPos targetBlock) {
        if (hit == null || hit.typeOfHit == MovingObjectPosition.MovingObjectType.MISS) {
            return true;
        }
        if (hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return false;
        }
        return targetBlock.equals(hit.getBlockPos());
    }

    // ---------------------------------------------------------------- entities

    /**
     * Whether a placement could go through: no other entity overlaps the block. Mirrors what the
     * server checks before accepting one - the placing player is excluded, as they are there.
     */
    public static boolean isPlacementSpaceClear(World world, Entity sourceEntity, BlockPos pos) {
        AxisAlignedBB placementBounds = fullCube(pos);
        List<Entity> nearby = world.getEntitiesWithinAABBExcludingEntity(sourceEntity, placementBounds);
        for (Entity candidate : nearby) {
            if (candidate == null || candidate.isDead || !candidate.preventEntitySpawning) {
                continue;
            }
            if (candidate.getEntityBoundingBox().intersectsWith(placementBounds)) {
                return false;
            }
        }
        return true;
    }

    /** Whether any entity sits across the line from the eye to the point being aimed at. */
    public static boolean isEntityPathClear(World world, Entity sourceEntity, AxisAlignedBB searchBounds,
                                            Vec3 rayStart, Vec3 rayEnd) {
        List<Entity> nearby = world.getEntitiesWithinAABBExcludingEntity(sourceEntity, searchBounds);
        for (Entity candidate : nearby) {
            if (candidate == null || !candidate.canBeCollidedWith()) {
                continue;
            }
            float border = candidate.getCollisionBorderSize();
            AxisAlignedBB collision = candidate.getEntityBoundingBox().expand(border, border, border);
            if (collision.isVecInside(rayStart) || collision.calculateIntercept(rayStart, rayEnd) != null) {
                return false;
            }
        }
        return true;
    }
}
