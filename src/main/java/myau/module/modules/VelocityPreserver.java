package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.Render2DEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.ChatUtil;
import myau.util.PacketUtil;
import myau.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.network.Packet;
import net.minecraft.network.ThreadQuickExitException;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.item.ItemFireball;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.network.play.server.S40PacketDisconnect;

import java.awt.Color;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Holds knockback the same way KnockbackDelay does - by lag, not by faking physics.
 * <p>
 * Once armed, the first knockback/explosion aimed at you starts a hold: from that packet on the
 * whole inbound stream is queued instead of processed, for up to {@code Hold} seconds or until
 * you press the bind again. Your own movement packets keep flowing normally, so the server only
 * sees a client with a laggy connection: the anticheat's transaction that follows the velocity
 * is not acknowledged yet, so no knockback is expected of you. On release the queue is drained
 * in order - instantly, or replayed at {@code Replay-Speed}x - and the knockback is applied in
 * the very tick its transaction is acknowledged, exactly like a late packet would be.
 * <p>
 * Keep-alives, the disconnect packet, respawns and world/chunk data are never held.
 * <p>
 * Explosion mode only reacts to blasts - fireballs (yours or not), TNT, anything that explodes
 * near you. Servers carry the push in the explosion packet, in an entity-velocity packet sent
 * right after it, or both; all of those count, plus any push shortly after you used a fire
 * charge. The pushes of {@code Blasts} explosions are held and delivered in one tick, exactly as
 * vanilla applies them when they arrive together: explosion pushes add up, velocity packets
 * replace each other (so on servers that send both, the last blast's total is what you get).
 */
public class VelocityPreserver extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final long RELEASED_BANNER_MS = 1500L;

    private enum State { IDLE, ARMED, HOLDING, RELEASING }

    private static final int RELEASE_INSTANT = 0;
    private static final int RELEASE_REPLAY = 1;

    private static final int MODE_KNOCKBACK = 0;
    private static final int MODE_EXPLOSION = 1;
    /** A push this soon after you used a fire charge counts as your own fireball, whatever packet carries it. */
    private static final long FIREBALL_WINDOW_MS = 3000L;
    /** The velocity and explosion packets of one blast arrive within this; count them once. */
    private static final long SAME_BLAST_MS = 250L;
    /** An explosion packet this close is treated as a blast that concerns us even before its push arrives. */
    private static final double BLAST_RADIUS = 6.0;

    public final ModeProperty mode = new ModeProperty("Mode", MODE_KNOCKBACK, new String[]{"Knockback", "Explosion"});
    public final IntProperty blasts = new IntProperty("Blasts", 2, 1, 5, () -> this.mode.getValue() == MODE_EXPLOSION);
    public final FloatProperty hold = new FloatProperty("Hold", 2.0F, 0.5F, 10.0F);
    public final ModeProperty release = new ModeProperty("Release", RELEASE_REPLAY, new String[]{"Instant", "Replay"}, () -> this.mode.getValue() == MODE_KNOCKBACK);
    public final FloatProperty replaySpeed = new FloatProperty("Replay-Speed", 3.0F, 1.5F, 10.0F, () -> this.mode.getValue() == MODE_KNOCKBACK && this.release.getValue() == RELEASE_REPLAY);
    /** Lets the hurt animation through immediately; off by default because instant damage next to lagging transactions is inconsistent. */
    public final BooleanProperty realtimeDamage = new BooleanProperty("Realtime-Damage", false);
    public final BooleanProperty bar = new BooleanProperty("Bar", true);
    public final IntProperty barWidth = new IntProperty("Bar-Width", 120, 40, 400, bar::getValue);
    public final IntProperty offsetX = new IntProperty("Offset-X", 0, -500, 500, bar::getValue);
    public final IntProperty offsetY = new IntProperty("Offset-Y", 0, -500, 500, bar::getValue);

    private static final class TimedPacket {
        final Packet<?> packet;
        final long time;

        TimedPacket(Packet<?> packet, long time) {
            this.packet = packet;
            this.time = time;
        }
    }

    private final Queue<TimedPacket> held = new ConcurrentLinkedQueue<>();
    private volatile State state = State.IDLE;
    private long holdStart;
    /** Arrival time of the first held packet: the replay clock counts from here. */
    private long firstHeldTime;
    private long releaseStart;
    private long releasedAt;
    private float lastPreserved;
    /** Fireball mode: when you last used a fire charge, how many pushes are held, and whether the Nth arrived. */
    private volatile long lastFireballThrow;
    private volatile long lastExplosionPacket;
    private volatile long lastPushTime;
    private volatile int explosionsHeld;
    private volatile boolean releaseRequested;

    public VelocityPreserver() {
        super("VelocityPreserver", false, false, "Holds incoming knockback by lagging the inbound stream, releases it when you want");
    }

    // ---- lifecycle ----

    @Override
    public void onEnabled() {
        this.state = mc.thePlayer != null && mc.theWorld != null ? State.ARMED : State.IDLE;
    }

    /**
     * The bind cycles: on = armed, again while holding = release, again while releasing = finish
     * instantly, again while armed = off.
     */
    @Override
    public void setEnabled(boolean enabled) {
        if (!enabled && this.isEnabled() && mc.thePlayer != null) {
            if (this.state == State.HOLDING) {
                this.startRelease();
                return;
            }
            if (this.state == State.RELEASING) {
                this.finishRelease();
                return;
            }
        }
        super.setEnabled(enabled);
    }

    @Override
    public void onDisabled() {
        this.flush();
        this.state = State.IDLE;
    }

    @EventTarget(runWhenDisabled = true)
    public void onLoadWorld(LoadWorldEvent event) {
        // The old connection's packets mean nothing to the new world.
        this.held.clear();
        this.state = this.isEnabled() ? State.ARMED : State.IDLE;
    }

    // ---- holding ----

    private static boolean isExplosionPush(Packet<?> packet) {
        if (!(packet instanceof S27PacketExplosion)) {
            return false;
        }
        S27PacketExplosion explosion = (S27PacketExplosion) packet;
        return explosion.func_149149_c() != 0.0F || explosion.func_149144_d() != 0.0F || explosion.func_149147_e() != 0.0F;
    }

    /** A packet that belongs to a blast affecting us: the explosion itself, its push, or a push right after a fire charge. */
    private boolean isBlastPacket(Packet<?> packet) {
        long now = System.currentTimeMillis();
        if (packet instanceof S27PacketExplosion) {
            if (isExplosionPush(packet)) {
                return true;
            }
            S27PacketExplosion explosion = (S27PacketExplosion) packet;
            double dx = explosion.getX() - mc.thePlayer.posX;
            double dy = explosion.getY() - mc.thePlayer.posY;
            double dz = explosion.getZ() - mc.thePlayer.posZ;
            return dx * dx + dy * dy + dz * dz <= BLAST_RADIUS * BLAST_RADIUS;
        }
        if (isPlayerKnockback(packet)) {
            return now - this.lastExplosionPacket <= SAME_BLAST_MS || now - this.lastFireballThrow <= FIREBALL_WINDOW_MS;
        }
        return false;
    }

    private boolean isTrigger(Packet<?> packet) {
        return this.mode.getValue() == MODE_EXPLOSION ? this.isBlastPacket(packet) : isPlayerKnockback(packet);
    }

    private static boolean isPlayerKnockback(Packet<?> packet) {
        if (packet instanceof S12PacketEntityVelocity) {
            return ((S12PacketEntityVelocity) packet).getEntityID() == mc.thePlayer.getEntityId();
        }
        if (packet instanceof S27PacketExplosion) {
            S27PacketExplosion explosion = (S27PacketExplosion) packet;
            return explosion.func_149149_c() != 0.0F || explosion.func_149144_d() != 0.0F || explosion.func_149147_e() != 0.0F;
        }
        return false;
    }

    /**
     * Packets that must never wait in the queue. Keep-alives are deliberately NOT here: a client
     * whose keep-alive replies are on time while its transaction replies lag by seconds is not
     * lagging, it is holding packets. Holding them too keeps the lag uniform; the hold cap (10 s)
     * stays far below the server's 30 s timeout.
     */
    private boolean passesThrough(Packet<?> packet) {
        if (packet instanceof S40PacketDisconnect) {
            return true;
        }
        if (PacketUtil.isWorldRenderPacket(packet)) {
            return true;
        }
        if (this.realtimeDamage.getValue() && packet instanceof S19PacketEntityStatus) {
            S19PacketEntityStatus status = (S19PacketEntityStatus) packet;
            return status.getOpCode() == 2 && mc.theWorld != null && status.getEntity(mc.theWorld) == mc.thePlayer;
        }
        return false;
    }

    /** Runs on the Netty thread. */
    @EventTarget(Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        if (event.isCancelled() || mc.thePlayer == null || mc.theWorld == null || mc.isSingleplayer()) {
            return;
        }
        Packet<?> packet = event.getPacket();
        if (event.getType() == EventType.SEND) {
            if (packet instanceof C08PacketPlayerBlockPlacement) {
                ItemStack stack = ((C08PacketPlayerBlockPlacement) packet).getStack();
                if (stack != null && stack.getItem() instanceof ItemFireball) {
                    this.lastFireballThrow = System.currentTimeMillis();
                }
            }
            return;
        }
        if (packet instanceof S27PacketExplosion) {
            this.lastExplosionPacket = System.currentTimeMillis();
        }
        State current = this.state;
        if (current == State.IDLE) {
            return;
        }

        if (packet instanceof S07PacketRespawn) {
            // Everything before a respawn belongs to the old life; deliver it, then the respawn.
            this.flush();
            this.state = State.ARMED;
            return;
        }

        if (current == State.ARMED) {
            if (!this.isTrigger(packet)) {
                return;
            }
            long now = System.currentTimeMillis();
            this.holdStart = now;
            this.firstHeldTime = now;
            this.explosionsHeld = 0;
            this.lastPushTime = 0L;
            this.releaseRequested = false;
            this.state = State.HOLDING;
        } else if (this.passesThrough(packet)) {
            return;
        }

        if (this.mode.getValue() == MODE_EXPLOSION && current != State.RELEASING && this.isBlastPacket(packet)) {
            long now = System.currentTimeMillis();
            if (now - this.lastPushTime > SAME_BLAST_MS) {
                this.explosionsHeld++;
            }
            this.lastPushTime = now;
            if (this.explosionsHeld >= this.blasts.getValue()) {
                // Delivered on the main thread at the next tick, so all pushes land in one tick.
                this.releaseRequested = true;
            }
        }

        this.held.add(new TimedPacket(packet, System.currentTimeMillis()));
        event.setCancelled(true);
    }

    // ---- releasing ----

    private void startRelease() {
        this.lastPreserved = this.secondsHeld();
        this.releaseStart = System.currentTimeMillis();
        this.state = State.RELEASING;
        if (this.mode.getValue() == MODE_EXPLOSION) {
            ChatUtil.sendFormatted(String.format("%sVelocityPreserver: releasing %d blast%s at once&r", Myau.clientName, this.explosionsHeld, this.explosionsHeld == 1 ? "" : "s"));
            this.finishRelease();
            return;
        }
        ChatUtil.sendFormatted(String.format("%sVelocityPreserver: releasing %.1fs of held velocity&r", Myau.clientName, this.lastPreserved));
        if (this.release.getValue() == RELEASE_INSTANT) {
            this.finishRelease();
        }
    }

    private void finishRelease() {
        this.flush();
        this.releasedAt = System.currentTimeMillis();
        this.state = State.ARMED;
    }

    /** Delivers every held packet in order, right now. */
    private void flush() {
        TimedPacket wrapper;
        while ((wrapper = this.held.poll()) != null) {
            this.process(wrapper.packet);
        }
    }

    /** Delivers the held packets at {@code Replay-Speed} times their original pacing. */
    private void replay() {
        long virtual = (long) ((System.currentTimeMillis() - this.releaseStart) * this.replaySpeed.getValue());
        TimedPacket wrapper;
        while ((wrapper = this.held.peek()) != null) {
            if (wrapper.time - this.firstHeldTime > virtual) {
                return;
            }
            this.held.poll();
            this.process(wrapper.packet);
        }
        // Caught up with the live stream.
        this.releasedAt = System.currentTimeMillis();
        this.state = State.ARMED;
    }

    /**
     * Delivers a held packet exactly as vanilla would have on arrival. Nothing is ever rewritten:
     * the server decided the vector, and a client that changed it would be doing something no
     * player can.
     */
    @SuppressWarnings("unchecked")
    private void process(Packet<?> packet) {
        try {
            if (mc.getNetHandler() != null) {
                ((Packet<INetHandlerPlayClient>) packet).processPacket(mc.getNetHandler());
            }
        } catch (ThreadQuickExitException ignored) {
            // Off the main thread (respawn flush): vanilla has re-queued the packet in order.
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private float secondsHeld() {
        return Math.min((System.currentTimeMillis() - this.holdStart) / 1000.0F, this.hold.getValue());
    }

    /** Seconds of held stream still waiting to be delivered during a replay. */
    private float secondsLeft() {
        TimedPacket last = null;
        for (TimedPacket wrapper : this.held) {
            last = wrapper;
        }
        if (last == null) {
            return 0.0F;
        }
        long virtual = (long) ((System.currentTimeMillis() - this.releaseStart) * this.replaySpeed.getValue());
        return Math.max(0.0F, (last.time - this.firstHeldTime - virtual) / 1000.0F);
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE || mc.thePlayer == null) {
            return;
        }
        switch (this.state) {
            case IDLE:
                this.state = State.ARMED;
                break;
            case ARMED:
                // A packet queued in the instant between "caught up" and re-arming.
                if (!this.held.isEmpty()) {
                    this.flush();
                }
                break;
            case HOLDING:
                if (this.releaseRequested || System.currentTimeMillis() - this.holdStart >= this.hold.getValue() * 1000.0F) {
                    this.startRelease();
                }
                break;
            case RELEASING:
                this.replay();
                break;
            default:
                break;
        }
    }

    // ---- HUD ----

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.bar.getValue() || this.state == State.IDLE || mc.thePlayer == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String label;
        float fraction;
        int fill;
        HUD hud = (HUD) Myau.moduleManager.modules.get(HUD.class);
        int accent = hud != null ? hud.getColor(now) : Color.CYAN.getRGB();

        switch (this.state) {
            case HOLDING: {
                float value = this.secondsHeld();
                if (this.mode.getValue() == MODE_EXPLOSION) {
                    fraction = (float) this.explosionsHeld / this.blasts.getValue();
                    label = String.format("Blasts %d / %d  (%.1fs)", this.explosionsHeld, this.blasts.getValue(), value);
                } else {
                    fraction = value / this.hold.getValue();
                    label = String.format("Preserving %.1fs / %.1fs", value, this.hold.getValue());
                }
                fill = accent;
                break;
            }
            case RELEASING: {
                float left = this.secondsLeft();
                fraction = this.lastPreserved > 0.0F ? left / this.lastPreserved : 0.0F;
                label = String.format("Velocity %.1fs left", left);
                fill = new Color(255, 170, 60).getRGB();
                break;
            }
            default: {
                label = now - this.releasedAt < RELEASED_BANNER_MS ? "Released"
                        : this.mode.getValue() == MODE_EXPLOSION ? "Waiting for explosion" : "Waiting for velocity";
                fraction = 0.0F;
                fill = accent;
                break;
            }
        }
        fraction = Math.max(0.0F, Math.min(1.0F, fraction));

        ScaledResolution sr = new ScaledResolution(mc);
        float width = this.barWidth.getValue();
        float height = 6.0F;
        float x = sr.getScaledWidth() / 2.0F - width / 2.0F + this.offsetX.getValue();
        // Sits just above the hotbar/XP bar by default.
        float y = sr.getScaledHeight() - 48.0F + this.offsetY.getValue();

        RenderUtil.enableRenderState();
        RenderUtil.drawRoundedRect(x - 1.0F, y - 1.0F, width + 2.0F, height + 2.0F, 3.0F, new Color(0, 0, 0, 150).getRGB());
        if (fraction > 0.0F) {
            RenderUtil.drawRoundedRect(x, y, width * fraction, height, 2.5F, fill);
        }
        RenderUtil.disableRenderState();
        mc.fontRendererObj.drawStringWithShadow(label, x + width / 2.0F - mc.fontRendererObj.getStringWidth(label) / 2.0F, y - 11.0F, -1);
    }

    @Override
    public String[] getSuffix() {
        String main = this.mode.getValue() == MODE_EXPLOSION ? this.blasts.getValue() + " Blasts" : String.format("%.1fs", this.hold.getValue());
        return new String[]{main};
    }
}
