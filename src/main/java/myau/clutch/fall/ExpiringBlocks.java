package myau.clutch.fall;

import net.minecraft.util.BlockPos;

import java.util.HashMap;

/**
 * Positions remembered for a while and then forgotten: here, blocks the server refused to let
 * you place, which are not planned into again until the memory lapses. The original's
 * {@code VisibleModuleList}.
 */
public final class ExpiringBlocks {
    private final HashMap<BlockPos, Long> lastSeen = new HashMap<BlockPos, Long>();
    private final long expiryMillis;

    public ExpiringBlocks(long expiryMillis) {
        this.expiryMillis = expiryMillis;
    }

    private void cleanUp() {
        long now = System.currentTimeMillis();
        this.lastSeen.entrySet().removeIf(entry -> now - entry.getValue() > this.expiryMillis);
    }

    public synchronized void add(BlockPos pos) {
        this.cleanUp();
        this.lastSeen.put(pos, System.currentTimeMillis());
    }

    public synchronized boolean contains(BlockPos pos) {
        this.cleanUp();
        return this.lastSeen.containsKey(pos);
    }

    public synchronized void clear() {
        this.lastSeen.clear();
    }

    public synchronized int size() {
        this.cleanUp();
        return this.lastSeen.size();
    }
}
