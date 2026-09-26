package myau.clutch.fall;

import myau.clutch.PlacementTarget;
import myau.rotation.mouse.MouseAim;
import net.minecraft.block.Block;
import net.minecraft.block.BlockAnvil;
import net.minecraft.block.BlockBarrier;
import net.minecraft.block.BlockBeacon;
import net.minecraft.block.BlockBed;
import net.minecraft.block.BlockBrewingStand;
import net.minecraft.block.BlockButton;
import net.minecraft.block.BlockCake;
import net.minecraft.block.BlockChest;
import net.minecraft.block.BlockCommandBlock;
import net.minecraft.block.BlockDaylightDetector;
import net.minecraft.block.BlockDispenser;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockDragonEgg;
import net.minecraft.block.BlockEnchantmentTable;
import net.minecraft.block.BlockEnderChest;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockFurnace;
import net.minecraft.block.BlockHopper;
import net.minecraft.block.BlockJukebox;
import net.minecraft.block.BlockLever;
import net.minecraft.block.BlockNote;
import net.minecraft.block.BlockRedstoneDiode;
import net.minecraft.block.BlockSign;
import net.minecraft.block.BlockTrapDoor;
import net.minecraft.block.BlockWorkbench;
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

/**
 * The block tests and placement geometry the fall clutch is written against, each matching the
 * original helper it replaces: Vape's {@code BlockUtil} and its {@code ClutchPlacementPathUtils}.
 */
public final class FallBlocks {
    private FallBlocks() {
    }

    public static Block blockAt(World world, BlockPos pos) {
        return world.getBlockState(pos).getBlock();
    }

    /** Air, or anything a placement simply replaces. The original's {@code BlockUtil.u}. */
    public static boolean isReplaceable(Block block) {
        Material material = block.getMaterial();
        return material == Material.air || material.isReplaceable();
    }

    /** The original's {@code BlockUtil.p}. */
    public static boolean isAir(Block block) {
        return block.getMaterial() == Material.air;
    }

    /** Solid and blocks movement: something to stand on. The original's {@code BlockUtil.b}. */
    public static boolean isSolid(Block block) {
        Material material = block.getMaterial();
        return material.isSolid() && material.blocksMovement();
    }

    /** The original's {@code BlockUtil.C}. */
    public static boolean isLiquid(Block block) {
        return block.getMaterial().isLiquid();
    }

    /** Liquid, air or fire: nothing with an outline of its own. The original's {@code BlockUtil.J}. */
    public static boolean isShapeless(Block block) {
        Material material = block.getMaterial();
        return material.isLiquid() || material == Material.air || material == Material.fire;
    }

    /**
     * Whether a click on this block would use it rather than build against it. The original
     * compares its list of display names with 1.8.9's internal block names, so most of its
     * multi-word entries - crafting table, brewing stand, trapped chest - never match and those
     * blocks get chosen to build off, which opens them mid-fall. This checks the same list by
     * block type instead.
     */
    public static boolean isInteractive(Block block) {
        if (block instanceof BlockDoor || block instanceof BlockTrapDoor) {
            return block.getMaterial() != Material.iron;
        }
        return block instanceof BlockAnvil || block instanceof BlockBarrier || block instanceof BlockBeacon
                || block instanceof BlockBed || block instanceof BlockBrewingStand || block instanceof BlockButton
                || block instanceof BlockCake || block instanceof BlockChest || block instanceof BlockWorkbench
                || block instanceof BlockEnderChest || block instanceof BlockCommandBlock
                || block instanceof BlockDragonEgg || block instanceof BlockDaylightDetector
                || block instanceof BlockDispenser || block instanceof BlockEnchantmentTable
                || block instanceof BlockFenceGate || block instanceof BlockFurnace || block instanceof BlockHopper
                || block instanceof BlockJukebox || block instanceof BlockLever || block instanceof BlockNote
                || block instanceof BlockSign || block instanceof BlockRedstoneDiode;
    }

    /** The block's outline; a full cube when it has none. The original's {@code BlockUtil.F}. */
    public static AxisAlignedBB bounds(World world, BlockPos pos) {
        AxisAlignedBB full = new AxisAlignedBB(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0);
        Block block = blockAt(world, pos);
        if (block != null && !isShapeless(block)) {
            block.setBlockBoundsBasedOnState(world, pos);
            AxisAlignedBB outline = block.getSelectedBoundingBox(world, pos);
            if (outline != null) {
                return outline;
            }
        }
        return full;
    }

    /** Whether you are on the outer side of that face, where a click on it can come from. */
    public static boolean isBlockFaceVisible(Vec3 eye, World world, BlockPos pos, EnumFacing facing) {
        if (facing == null) {
            return true;
        }
        AxisAlignedBB bounds = bounds(world, pos);
        switch (facing.getIndex()) {
            case 1:
                return eye.yCoord > bounds.maxY;
            case 0:
                return bounds.minY > eye.yCoord;
            case 2:
                return bounds.minZ > eye.zCoord;
            case 3:
                return eye.zCoord > bounds.maxZ;
            case 4:
                return bounds.minX > eye.xCoord;
            case 5:
                return eye.xCoord > bounds.maxX;
            default:
                return false;
        }
    }

    /** No other entity that would block a placement is in the way of it. */
    public static boolean isPlacementSpaceClear(World world, Entity source, BlockPos pos) {
        AxisAlignedBB placement = bounds(world, pos);
        List<Entity> nearby = world.getEntitiesWithinAABBExcludingEntity(source, placement);
        for (Entity candidate : nearby) {
            if (candidate == null || candidate.isDead || !candidate.preventEntitySpawning || candidate == source) {
                continue;
            }
            if (candidate.getEntityBoundingBox().intersectsWith(placement)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The point on the target's face nearest your current aim that a click can actually reach,
     * scanning the face in ten-percent insets. Points within half a degree of where you already
     * look are passed over, and the first under a degree away is taken outright. The original's
     * {@code findPlacementHitPoint}, quirks included.
     */
    public static Vec3 findPlacementHitPoint(World world, Vec3 eye, PlacementTarget target, float yaw, float pitch) {
        BlockPos support = target.supportBlock;
        EnumFacing facing = target.facing;
        AxisAlignedBB bounds = bounds(world, support);
        if (facing == null) {
            return closestPoint(eye, bounds);
        }
        bounds = bounds.contract(0.002, 0.002, 0.002);
        Vec3i direction = facing.getDirectionVec();
        double width = bounds.maxX - bounds.minX;
        double depth = bounds.maxZ - bounds.minZ;
        double height = bounds.maxY - bounds.minY;
        double directionX = direction.getX();
        double directionY = direction.getY();
        double directionZ = direction.getZ();
        double bestDistance = Double.MAX_VALUE;
        Vec3 best = null;
        int facingIndex = facing.getIndex();
        for (int horizontalInset = 0; horizontalInset <= 70; horizontalInset += 10) {
            double horizontalScale = 1.0 - (double) horizontalInset / 100.0;
            double insetX = width / 2.0 * horizontalScale;
            double insetZ = height / 2.0 * horizontalScale;
            double minX = bounds.minX + (directionX > 0.0 ? width : (directionX < 0.0 ? 0.0 : insetX));
            double minZ = bounds.minZ + (directionZ > 0.0 ? depth : (directionZ < 0.0 ? 0.0 : insetZ));
            double maxX = bounds.maxX - (directionX < 0.0 ? width : (directionX > 0.0 ? 0.0 : insetX));
            double maxZ = bounds.maxZ - (directionZ < 0.0 ? depth : (directionZ > 0.0 ? 0.0 : insetZ));
            verticalLoop:
            for (int verticalInset = 0; verticalInset <= 70; verticalInset += 10) {
                double verticalScale = 1.0 - (double) verticalInset / 100.0;
                double insetY = depth / 2.0 * verticalScale;
                double minY = bounds.minY + (directionY > 0.0 ? height : (directionY < 0.0 ? 0.0 : insetY));
                double maxY = bounds.maxY - (directionY < 0.0 ? height : (directionY > 0.0 ? 0.0 : insetY));
                for (Vec3 corner : faceCorners(facingIndex, minX, minY, minZ, maxX, maxY, maxZ)) {
                    double distance = MouseAim.rotationDistance(eye, corner, yaw, pitch);
                    if (distance >= bestDistance || distance <= 0.5) {
                        if (verticalInset == 0) {
                            continue verticalLoop;
                        }
                        continue;
                    }
                    MovingObjectPosition hit = world.rayTraceBlocks(eye, corner, false, false, true);
                    if (!isExpectedBlockHit(hit, support, facingIndex)) {
                        continue;
                    }
                    bestDistance = distance;
                    best = corner;
                    if (bestDistance < 1.0) {
                        return best;
                    }
                }
            }
        }
        return best;
    }

    /** The nearest point of a box to a point, nudged off the point itself on x and z. The original's {@code RotationUtil.M}. */
    public static Vec3 closestPoint(Vec3 point, AxisAlignedBB box) {
        double x = clampInside(point.xCoord, box.minX, box.maxX);
        double y = clampInside(point.yCoord, box.minY, box.maxY);
        double z = clampInside(point.zCoord, box.minZ, box.maxZ);
        if (x == point.xCoord) {
            x = point.xCoord + 0.01;
        }
        if (z == point.zCoord) {
            z = point.zCoord + 0.01;
        }
        return new Vec3(x, y, z);
    }

    private static double clampInside(double value, double minimum, double maximum) {
        return value > maximum ? maximum : (value < minimum ? minimum : value);
    }

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

    public static boolean isExpectedBlockHit(MovingObjectPosition hit, BlockPos block, int facingIndex) {
        return hit != null
                && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && hit.sideHit != null
                && hit.sideHit.getIndex() == facingIndex
                && block.equals(hit.getBlockPos());
    }
}
