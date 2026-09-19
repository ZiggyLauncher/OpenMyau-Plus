package myau.management;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LoadWorldEvent;
import myau.events.MoveInputEvent;
import myau.events.PacketEvent;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorPlayerControllerMP;
import myau.module.modules.Reach;
import myau.util.ChatUtil;
import myau.util.MoveUtil;
import myau.util.PacketUtil;
import myau.util.RotationUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraft.network.play.client.C10PacketCreativeInventoryAction;
import net.minecraft.network.play.server.S09PacketHeldItemChange;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Performs a manual hit one tick late so a silent rotation and/or a silent hotbar swap can be in
 * effect on the server <i>before</i> the attack arrives and stay in effect <i>after</i> it.
 * <p>
 * Timeline, with T = the player's tick when the click happened (ticksExisted at that tick's
 * UpdateEvent PRE):
 * <pre>
 *  T          click intercepted; C09(swap slot) sent now; this tick's C03 starts turning toward the flick
 *  T+rampIn-1 (rampIn = 1: still T) the sent yaw reaches the flick
 *  T+rampIn   attack (C02 + swing) at UpdateEvent PRE, before this tick's C03, which still carries the flick
 *  then       rampOut ticks turning back to the real yaw; afterwards nothing is overridden; C09(real slot) re-sent
 * </pre>
 * The turn in and out is interpolated per tick, GCD-aligned and lightly jittered, because a
 * one-tick 90 degree snap that reverses two ticks later is the easiest aim signature there is.
 * Restoring in the same tick as the attack (swap, hit, swap-back back to back) is not credited by
 * servers that resolve combat at the end of their tick, which is why both are held one tick past
 * the hit. Only the server-side slot changes: the client's hotbar and camera never move.
 * <p>
 * Shared by Hitflick, MoreKB (stick swap) and VoidFlick so only one deferred hit exists at a time.
 */
public class HitManager {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public static final int ROTATION_PRIORITY = 10;
    /** Server-side entity reach is 3.0; a hit lined up a tick ago may drift slightly past it. */
    private static final double DEFAULT_REACH = 3.0;
    private static final double REACH_TOLERANCE = 0.3;

    private static final class Pending {
        final EntityLivingBase target;
        final int clickTick;
        final float yaw;
        /** Real yaw at the click, the start of the turn in. */
        final float startYaw;
        final boolean moveFix;
        final int rampIn;
        final int rampOut;
        final long restoreDelayMs;
        final boolean hitboxSafe;
        final boolean debug;

        Pending(EntityLivingBase target, int clickTick, float yaw, float startYaw, boolean moveFix,
                int rampIn, int rampOut, long restoreDelayMs, boolean hitboxSafe, boolean debug) {
            this.target = target;
            this.clickTick = clickTick;
            this.yaw = yaw;
            this.startYaw = startYaw;
            this.moveFix = moveFix;
            this.rampIn = Math.max(1, rampIn);
            this.rampOut = Math.max(0, rampOut);
            this.restoreDelayMs = restoreDelayMs;
            this.hitboxSafe = hitboxSafe;
            this.debug = debug;
        }

        int hitTick() {
            return this.clickTick + this.rampIn;
        }

        boolean flicks() {
            return !Float.isNaN(this.yaw);
        }
    }

    private Pending pending;
    private boolean performing;
    /** The player entity the current state belongs to; a respawn replaces it and resets ticksExisted. */
    private EntityPlayerSP player;
    /** Slot the server holds because of us, or -1 while it matches the client. */
    private int heldSlot = -1;
    /** Visible swap: the hotbar itself was switched, and goes back to this slot afterwards. */
    private boolean heldSlotVisible;
    private int originalSlot = -1;
    private int restoreSlotTick = Integer.MAX_VALUE;
    private long restoreSlotTime;
    private boolean sendingSlotPacket;
    /** Whether the next scheduled swap should change the hotbar for real (set by modules). */
    private boolean visibleSwap = true;

    public void setVisibleSwap(boolean visible) {
        this.visibleSwap = visible;
    }
    /** Flick yaw being turned back from after the hit (NaN when no turn is active). */
    private float heldYaw = Float.NaN;
    private boolean heldMoveFix;
    private int rampOutStart = Integer.MIN_VALUE;
    private int rampOutTicks;

    public boolean isBusy() {
        return this.pending != null;
    }

    /** True while the deferred attack itself is running (its AttackEvent is a replay, not a new click). */
    public boolean isAttacking() {
        return this.performing;
    }

    /**
     * Takes over the current click. The caller must cancel its LeftClickMouseEvent when this
     * returns true.
     *
     * @param yaw            server yaw to hit with, or NaN to keep the real rotation
     * @param moveFix        correct strafing so movement stays legal relative to {@code yaw}
     * @param slot           hotbar slot to hit with, or -1 for the held item
     * @param restoreDelayMs how long to keep {@code slot} selected after the hit (0 = next tick)
     * @param rampIn         ticks to turn toward {@code yaw}; the hit lands after the last one (min 1)
     * @param rampOut        ticks to turn back to the real yaw after the hit (0 = snap back)
     * @param hitboxSafe     clamp {@code yaw} so a ray along the sent rotation still enters the
     *                       target's hitbox - the hit stays a legitimate hit for raycast checks
     */
    public boolean schedule(EntityLivingBase target, float yaw, boolean moveFix, int slot, long restoreDelayMs, int rampIn, int rampOut,
                            boolean hitboxSafe, boolean debug) {
        if (mc.thePlayer == null || mc.theWorld == null || target == null) {
            return false;
        }
        this.syncPlayer();
        if (this.pending != null) {
            return false;
        }
        if (hitboxSafe && !Float.isNaN(yaw)) {
            yaw = clampYawToHitbox(target, yaw);
        }
        if (Float.isNaN(yaw) && slot < 0) {
            return false;
        }
        if (slot >= 0 && slot != this.heldSlot) {
            this.applySlot(slot);
        }
        this.restoreSlotTick = Integer.MAX_VALUE;
        this.heldYaw = Float.NaN;
        this.pending = new Pending(target, mc.thePlayer.ticksExisted, yaw, mc.thePlayer.rotationYaw, moveFix, rampIn, rampOut, restoreDelayMs, hitboxSafe, debug);
        if (debug) {
            ChatUtil.sendFormatted(String.format("%s&7Hit deferred: yaw=%s slot=%d target=%s&r", Myau.clientName,
                    Float.isNaN(yaw) ? "keep" : String.format("%.1f", yaw), slot, target.getName()));
        }
        return true;
    }

    /** Server-side hitboxes are grown by the collision border (0.1 for players); stay clear of the edge. */
    private static final float HITBOX_MARGIN_DEGREES = 1.0F;

    /**
     * A hand never repeats the same timing twice. Varies a tick count by one in either direction,
     * staying inside the setting's own range.
     */
    public static int jitterTicks(int base, int min, int max) {
        int jittered = base + ThreadLocalRandom.current().nextInt(-1, 2);
        return Math.max(min, Math.min(max, jittered));
    }

    /** Varies a millisecond delay by +/-25%. */
    public static long jitterMs(long base) {
        if (base <= 0L) {
            return base;
        }
        double factor = 0.75 + ThreadLocalRandom.current().nextDouble() * 0.5;
        return Math.max(0L, Math.round(base * factor));
    }

    /**
     * The widest yaw the flick may use while a ray from the eye along the sent rotation still
     * enters the target's expanded hitbox: the horizontal angular span of the box's corners,
     * shrunk by a margin. The real aim (which just hit the target) and the clamped flick both
     * lie in that span, so every ramp step between them does too.
     */
    static float clampYawToHitbox(EntityLivingBase target, float desiredYaw) {
        float[] span = hitboxYawSpan(target);
        if (span == null) {
            return desiredYaw; // standing inside the box: every direction hits it
        }
        if (span[1] > span[2]) {
            return span[0]; // box too thin from here: aim dead center
        }
        float delta = MathHelper.wrapAngleTo180_float(desiredYaw - span[0]);
        return span[0] + MathHelper.clamp_float(delta, span[1], span[2]);
    }

    /**
     * @return {center yaw, min delta, max delta} (margins applied, deltas relative to the center)
     *         for rotations whose ray enters the target's expanded hitbox, or null when the eye is
     *         inside the box horizontally.
     */
    public static float[] hitboxYawSpan(EntityLivingBase target) {
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
        float border = target.getCollisionBorderSize();
        AxisAlignedBB box = target.getEntityBoundingBox().expand(border, border, border);
        if (eye.xCoord > box.minX && eye.xCoord < box.maxX && eye.zCoord > box.minZ && eye.zCoord < box.maxZ) {
            return null;
        }
        float center = yawFromEye(eye, (box.minX + box.maxX) / 2.0, (box.minZ + box.maxZ) / 2.0);
        float min = 0.0F;
        float max = 0.0F;
        double[][] corners = {{box.minX, box.minZ}, {box.minX, box.maxZ}, {box.maxX, box.minZ}, {box.maxX, box.maxZ}};
        for (double[] corner : corners) {
            float delta = MathHelper.wrapAngleTo180_float(yawFromEye(eye, corner[0], corner[1]) - center);
            min = Math.min(min, delta);
            max = Math.max(max, delta);
        }
        return new float[]{center, min + HITBOX_MARGIN_DEGREES, max - HITBOX_MARGIN_DEGREES};
    }

    private static float yawFromEye(Vec3 eye, double x, double z) {
        return (float) Math.toDegrees(Math.atan2(-(x - eye.xCoord), z - eye.zCoord));
    }

    /**
     * Vertical counterpart of {@link #clampYawToHitbox}: the pitch range that still enters the
     * expanded hitbox, measured at the nearest horizontal distance to the box.
     */
    static float clampPitchToHitbox(EntityLivingBase target, float desiredPitch) {
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
        float border = target.getCollisionBorderSize();
        AxisAlignedBB box = target.getEntityBoundingBox().expand(border, border, border);
        double nearestX = MathHelper.clamp_double(eye.xCoord, box.minX, box.maxX);
        double nearestZ = MathHelper.clamp_double(eye.zCoord, box.minZ, box.maxZ);
        double horizontal = Math.sqrt((nearestX - eye.xCoord) * (nearestX - eye.xCoord) + (nearestZ - eye.zCoord) * (nearestZ - eye.zCoord));
        if (horizontal < 1.0e-3) {
            return desiredPitch; // standing over/under the box
        }
        float top = (float) -Math.toDegrees(Math.atan2(box.maxY - eye.yCoord, horizontal)) + HITBOX_MARGIN_DEGREES;
        float bottom = (float) -Math.toDegrees(Math.atan2(box.minY - eye.yCoord, horizontal)) - HITBOX_MARGIN_DEGREES;
        if (top > bottom) {
            return (top + bottom) / 2.0F;
        }
        return MathHelper.clamp_float(desiredPitch, top, bottom);
    }

    @EventTarget(Priority.HIGHEST)
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE || mc.thePlayer == null) {
            return;
        }
        this.syncPlayer();
        int tick = mc.thePlayer.ticksExisted;
        Pending pending = this.pending;

        if (pending != null && tick > pending.hitTick() + 2) {
            // Should never happen, but never let a stale hit swallow clicks.
            this.pending = null;
            pending = null;
        }

        float sendYaw = Float.NaN;
        // While a hit is pending the sent pitch is kept inside the target's hitbox as well, so a
        // vertical mouse flick during the ramp can't turn the hit into a hitbox miss.
        float sendPitch = event.getNewPitch();
        if (pending != null && pending.hitboxSafe && pending.flicks() && tick <= pending.hitTick()) {
            sendPitch = clampPitchToHitbox(pending.target, sendPitch);
        }
        if (pending != null && tick >= pending.hitTick()) {
            // Hit tick: the sent yaw is fully at the flick, then the attack goes out.
            this.pending = null;
            if (pending.flicks()) {
                // Re-clamp against where the target is NOW: at close range it moves degrees per tick.
                sendYaw = pending.hitboxSafe ? clampYawToHitbox(pending.target, pending.yaw) : pending.yaw;
                this.heldYaw = sendYaw;
                this.heldMoveFix = pending.moveFix;
                this.rampOutStart = tick;
                this.rampOutTicks = pending.rampOut;
            }
            if (this.isStillValid(pending)) {
                this.performAttack(pending);
            } else if (pending.debug) {
                ChatUtil.sendFormatted(String.format("%s&cDeferred hit dropped (target moved out of reach)&r", Myau.clientName));
            }
            if (this.heldSlot >= 0) {
                this.restoreSlotTick = tick + 1;
                this.restoreSlotTime = System.currentTimeMillis() + pending.restoreDelayMs;
            }
        } else if (pending != null && pending.flicks()) {
            // Turning in: step k of rampIn between the yaw at the click and the flick.
            int step = tick - pending.clickTick + 1;
            float progress = Math.min(1.0F, (float) step / pending.rampIn);
            sendYaw = pending.startYaw + MathHelper.wrapAngleTo180_float(pending.yaw - pending.startYaw) * progress;
            if (pending.hitboxSafe) {
                sendYaw = clampYawToHitbox(pending.target, sendYaw);
            }
            this.heldMoveFix = pending.moveFix;
        } else if (!Float.isNaN(this.heldYaw)) {
            // Turning out: from the flick back toward wherever the player is really looking.
            int step = tick - this.rampOutStart;
            if (step <= this.rampOutTicks) {
                float progress = (float) step / (this.rampOutTicks + 1);
                sendYaw = this.heldYaw + MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationYaw - this.heldYaw) * progress;
            } else {
                this.heldYaw = Float.NaN;
                this.heldMoveFix = false;
            }
        }

        if (!Float.isNaN(sendYaw)) {
            // A little noise so consecutive steps aren't perfectly linear; GCD alignment after it,
            // and live pitch so looking up/down keeps working normally.
            sendYaw += (ThreadLocalRandom.current().nextFloat() - 0.5F) * 0.6F;
            float[] rotation = RotationUtil.gcd(sendYaw, sendPitch, event.getYaw(), event.getPitch());
            event.setRotation(rotation[0], rotation[1], ROTATION_PRIORITY);
            event.setPervRotation(this.heldMoveFix ? rotation[0] : mc.thePlayer.rotationYaw, ROTATION_PRIORITY);
        }

        if (this.heldSlot >= 0 && tick >= this.restoreSlotTick && System.currentTimeMillis() >= this.restoreSlotTime) {
            this.restoreSlot();
        }
    }

    /** A respawn replaces the player entity and resets ticksExisted; the server forgot our state too. */
    private void syncPlayer() {
        if (mc.thePlayer != this.player) {
            this.reset();
            this.player = mc.thePlayer;
        }
    }

    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (this.heldMoveFix
                && RotationState.isActived()
                && RotationState.getPriority() == ROTATION_PRIORITY
                && MoveUtil.isForwardPressed()) {
            MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
        }
    }

    private boolean isStillValid(Pending pending) {
        if (mc.theWorld == null || pending.target.worldObj != mc.theWorld || pending.target.isDead) {
            return false;
        }
        double reach = DEFAULT_REACH;
        Reach reachModule = (Reach) Myau.moduleManager.modules.get(Reach.class);
        if (reachModule != null && reachModule.isEnabled()) {
            reach = Math.max(reach, reachModule.range.getValue());
        }
        double limit = reach + REACH_TOLERANCE;
        return RotationUtil.distanceSqFromEyeToClosestOnAABB(pending.target) <= limit * limit;
    }

    private void performAttack(Pending pending) {
        this.performing = true;
        try {
            // Same order as a real click: swing first, then the attack (AttackEvent for the other
            // modules, C02, client-side hit). syncCurrentPlayItem() inside sends nothing because
            // the client's slot never changed.
            mc.thePlayer.swingItem();
            mc.playerController.attackEntity(mc.thePlayer, pending.target);
            if (pending.debug) {
                ChatUtil.sendFormatted(String.format("%s&aDeferred hit sent (yaw %s, slot %d)&r", Myau.clientName,
                        pending.flicks() ? String.format("%.1f", pending.yaw) : "real", this.heldSlot));
            }
        } finally {
            this.performing = false;
        }
    }

    /**
     * Visible: the hotbar selection itself changes (you see the item in hand) and the packet goes
     * out through vanilla's own sync. Silent: only the server's slot changes.
     */
    private void applySlot(int slot) {
        this.heldSlot = slot;
        this.heldSlotVisible = this.visibleSwap;
        if (this.heldSlotVisible) {
            this.originalSlot = mc.thePlayer.inventory.currentItem;
            mc.thePlayer.inventory.currentItem = slot;
            this.syncSlotNow();
        } else {
            this.sendSlot(slot);
        }
    }

    private void restoreSlot() {
        boolean visible = this.heldSlotVisible;
        int backTo = this.originalSlot;
        int swapped = this.heldSlot;
        this.heldSlot = -1;
        this.heldSlotVisible = false;
        this.originalSlot = -1;
        this.restoreSlotTick = Integer.MAX_VALUE;
        if (mc.thePlayer == null || mc.getNetHandler() == null) {
            return;
        }
        if (visible) {
            // Only go back if the player hasn't picked another slot themselves meanwhile.
            if (backTo >= 0 && mc.thePlayer.inventory.currentItem == swapped) {
                mc.thePlayer.inventory.currentItem = backTo;
                this.syncSlotNow();
            }
        } else {
            this.sendSlot(mc.thePlayer.inventory.currentItem);
        }
    }

    /** Vanilla's own slot sync (PlayerControllerMP.syncCurrentPlayItem), flagged so onPacket ignores it. */
    private void syncSlotNow() {
        this.sendingSlotPacket = true;
        try {
            ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
        } finally {
            this.sendingSlotPacket = false;
        }
    }

    private void sendSlot(int slot) {
        this.sendingSlotPacket = true;
        try {
            PacketUtil.sendPacket(new C09PacketHeldItemChange(slot));
        } finally {
            this.sendingSlotPacket = false;
        }
    }

    @EventTarget(Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        if (this.sendingSlotPacket) {
            return;
        }
        Packet<?> packet = event.getPacket();
        if (event.getType() == EventType.SEND) {
            if (packet instanceof C09PacketHeldItemChange) {
                // The player picked a slot themselves: server and client match again.
                this.heldSlot = -1;
                this.restoreSlotTick = Integer.MAX_VALUE;
            } else if (this.heldSlot >= 0 && usesHeldItem(packet)) {
                // Never let the server place/dig/use with the swapped item instead of what's in hand.
                this.restoreSlot();
            }
        } else if (packet instanceof S09PacketHeldItemChange) {
            this.heldSlot = -1;
            this.restoreSlotTick = Integer.MAX_VALUE;
        }
    }

    private static boolean usesHeldItem(Packet<?> packet) {
        if (packet instanceof C02PacketUseEntity) {
            return ((C02PacketUseEntity) packet).getAction() != C02PacketUseEntity.Action.ATTACK;
        }
        return packet instanceof C08PacketPlayerBlockPlacement
                || packet instanceof C07PacketPlayerDigging
                || packet instanceof C0EPacketClickWindow
                || packet instanceof C10PacketCreativeInventoryAction;
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.reset();
    }

    /** Forgets everything without sending packets (world change / disconnect). */
    public void reset() {
        this.pending = null;
        this.performing = false;
        this.heldSlot = -1;
        this.heldSlotVisible = false;
        this.originalSlot = -1;
        this.restoreSlotTick = Integer.MAX_VALUE;
        this.heldYaw = Float.NaN;
        this.heldMoveFix = false;
        this.rampOutStart = Integer.MIN_VALUE;
    }

    /** Drops a scheduled hit and puts the server back in sync (module toggled off). */
    public void cancel() {
        this.pending = null;
        this.heldYaw = Float.NaN;
        this.heldMoveFix = false;
        if (this.heldSlot >= 0 && mc.theWorld != null) {
            this.restoreSlot();
        }
    }
}
