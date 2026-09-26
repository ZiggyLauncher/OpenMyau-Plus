package myau.clutch.fall;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/**
 * Writes a block straight into chunk storage: no neighbour updates, no lighting, no re-render.
 * A rehearsal uses it to try its placements in the real world for a moment and then put back
 * exactly what was there, all before the next frame is drawn. The original's
 * {@code BlockUtil.z}.
 */
public final class SilentBlocks {
    private SilentBlocks() {
    }

    /** @return whether anything was written */
    public static boolean set(World world, BlockPos pos, IBlockState state) {
        int y = pos.getY();
        if (y < 0 || y >= 256) {
            return false;
        }
        Chunk chunk = world.getChunkFromBlockCoords(pos);
        ExtendedBlockStorage[] storage = chunk.getBlockStorageArray();
        int section = y >> 4;
        ExtendedBlockStorage sectionStorage = storage[section];
        if (sectionStorage == null) {
            if (state.getBlock().getMaterial() == Material.air) {
                return false;
            }
            sectionStorage = new ExtendedBlockStorage(section << 4, !world.provider.getHasNoSky());
            storage[section] = sectionStorage;
        }
        sectionStorage.set(pos.getX() & 15, y & 15, pos.getZ() & 15, state);
        return true;
    }
}
