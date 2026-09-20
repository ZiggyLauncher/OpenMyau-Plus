package myau.backtrack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S00PacketKeepAlive;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S29PacketSoundEffect;
import net.minecraft.network.play.server.S40PacketDisconnect;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Holds inbound packets back so the world the client shows lags behind the world the server has.
 * That is the whole trick behind backtrack: while packets sit in this queue a target stays drawn
 * where they were, and the server - which rewinds for lag compensation anyway - still credits the
 * hit.
 * <p>
 * Packets arrive on the netty thread and are only ever replayed from the client thread, which is
 * why the queue is concurrent and why {@link #dispatch} re-checks the handler it captured: a world
 * change swaps the net handler, and replaying into the old one would tear the client apart.
 * <p>
 * Two packet classes are never held. {@link #immediate} ones would be wrong to delay at all - a
 * held keep-alive is a timeout kick. {@link #forcesRelease} ones mean the held world is about to
 * stop being true, so the caller drains everything the moment it sees one.
 */
public final class Backtracker {
    private static final ConcurrentLinkedQueue<Held> QUEUE = new ConcurrentLinkedQueue<Held>();
    private static volatile boolean holding;

    private Backtracker() {
    }

    public static void hold(boolean hold) {
        holding = hold;
    }

    public static boolean isHolding() {
        return holding;
    }

    public static boolean isLagging() {
        return !QUEUE.isEmpty();
    }

    public static Iterable<Held> held() {
        return QUEUE;
    }

    public static int size() {
        return QUEUE.size();
    }

    /** When the oldest held packet arrived, or 0 when nothing is held. */
    public static long oldestAt() {
        Held head = QUEUE.peek();
        return head == null ? 0L : head.at;
    }

    /**
     * Called on the netty thread for every inbound packet.
     *
     * @return true when the packet has been taken over and must not be processed now
     */
    public static boolean intercept(Packet<?> packet) {
        if (!holding && QUEUE.isEmpty()) {
            return false;
        }
        INetHandler handler = Minecraft.getMinecraft().getNetHandler();
        if (!(handler instanceof NetHandlerPlayClient)) {
            return false;
        }
        if (immediate(packet)) {
            return false;
        }
        QUEUE.add(new Held(packet, (NetHandlerPlayClient) handler, System.currentTimeMillis()));
        return true;
    }

    /** Packets after which the held view is no longer safe to keep: drain immediately. */
    public static boolean forcesRelease(Packet<?> packet) {
        if (packet instanceof S08PacketPlayerPosLook
                || packet instanceof S40PacketDisconnect
                || packet instanceof S07PacketRespawn
                || packet instanceof S01PacketJoinGame) {
            return true;
        }
        return packet instanceof S06PacketUpdateHealth
                && ((S06PacketUpdateHealth) packet).getHealth() <= 0.0F;
    }

    /** Packets that must never be delayed, whatever else is going on. */
    private static boolean immediate(Packet<?> packet) {
        if (packet instanceof S00PacketKeepAlive || packet instanceof S02PacketChat) {
            return true;
        }
        // Holding the hurt sound would delay the one cue telling you that you are being hit.
        return packet instanceof S29PacketSoundEffect
                && "game.player.hurt".equals(((S29PacketSoundEffect) packet).getSoundName());
    }

    /** Replays everything that has been held for at least {@code delayMillis}. */
    public static void release(long delayMillis) {
        long now = System.currentTimeMillis();
        Held head;
        while ((head = QUEUE.peek()) != null && now - head.at >= delayMillis) {
            Held next = QUEUE.poll();
            if (next == null) {
                return;
            }
            dispatch(next);
        }
    }

    /** Replays up to and including {@code last}, leaving anything newer held. */
    public static void releaseThrough(Held last) {
        Held head;
        while ((head = QUEUE.poll()) != null) {
            dispatch(head);
            if (head == last) {
                return;
            }
        }
    }

    public static void releaseAll() {
        Held head;
        while ((head = QUEUE.poll()) != null) {
            dispatch(head);
        }
    }

    /** Throws the queue away without replaying it. Only correct when the world is already gone. */
    public static void drop() {
        QUEUE.clear();
        holding = false;
    }

    /**
     * Must only be called from the client thread: the packet handlers assume they are on it, and
     * replaying into a handler the client has since replaced would corrupt the new world.
     */
    @SuppressWarnings("unchecked")
    private static void dispatch(Held held) {
        if (Minecraft.getMinecraft().getNetHandler() != held.listener) {
            return;
        }
        try {
            ((Packet<INetHandler>) held.packet).processPacket(held.listener);
        } catch (Throwable throwable) {
            // A single bad replay must not take the queue - or the game - down with it.
        }
    }

    /** One held packet, with the handler it arrived on and when it arrived. */
    public static final class Held {
        private final Packet<?> packet;
        private final NetHandlerPlayClient listener;
        private final long at;
        private boolean scanned;

        private Held(Packet<?> packet, NetHandlerPlayClient listener, long at) {
            this.packet = packet;
            this.listener = listener;
            this.at = at;
        }

        public Packet<?> packet() {
            return this.packet;
        }

        public long at() {
            return this.at;
        }

        /** Each packet is inspected once; the flag keeps a re-scan from double counting movement. */
        public boolean scanned() {
            return this.scanned;
        }

        public void markScanned() {
            this.scanned = true;
        }
    }
}
