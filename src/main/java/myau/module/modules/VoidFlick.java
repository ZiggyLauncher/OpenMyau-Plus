package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.Priority;
import myau.events.LeftClickMouseEvent;
import myau.management.HitManager;
import myau.mixin.IAccessorMinecraft;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.util.ChatUtil;
import myau.util.VoidUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;

/**
 * Edge kills on manual hits: when the target can be knocked off a drop, the hit is sent with the
 * yaw that pushes them toward the nearest void and (optionally) with the best Knockback item,
 * both silently. Hits with no void in reach are left completely untouched.
 * <p>
 * Uses Grizzly Client's void trajectory scan ({@link VoidUtil}) and the deferred-hit timing of
 * {@link myau.management.HitManager}: flick + stick go out a tick before the attack packet and
 * stay a tick after it, with GCD-aligned rotation and (optionally) movement correction.
 */
public class VoidFlick extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final FloatProperty range = new FloatProperty("Range", 3.0F, 0.5F, 6.0F);
    public final IntProperty depth = new IntProperty("Depth", 8, 2, 64);
    public final FloatProperty maxAngle = new FloatProperty("Max-Angle", 45.0F, 0.0F, 180.0F);
    public final BooleanProperty kbStick = new BooleanProperty("KB-Stick", true);
    /** Really switch the hotbar to the stick (visible in hand) instead of a server-side-only swap. */
    public final BooleanProperty visibleSwap = new BooleanProperty("Visible-Swap", true, kbStick::getValue);
    public final IntProperty stickHold = new IntProperty("Stick-Hold", 300, 0, 1000, kbStick::getValue);
    public final BooleanProperty requireSprint = new BooleanProperty("Require-Sprint", false);
    public final IntProperty cooldown = new IntProperty("Cooldown", 500, 0, 2000);
    public final BooleanProperty moveFix = new BooleanProperty("Move-Fix", true);
    public final IntProperty rampIn = new IntProperty("Ramp-In", 3, 1, 5);
    public final IntProperty rampOut = new IntProperty("Ramp-Out", 4, 0, 6);
    /** Keep the sent rotation inside the target's hitbox so the hit stays legitimate for raycast checks (limits the angle). */
    public final BooleanProperty hitboxSafe = new BooleanProperty("Hitbox-Safe", true);
    public final BooleanProperty debug = new BooleanProperty("Debug", false);

    private long lastFlickTime;

    public VoidFlick() {
        super("VoidFlick", false, false, "Flicks knockback toward the nearest void and hits with your KB stick when a drop is in reach");
    }

    @Override
    public void onEnabled() {
        this.lastFlickTime = 0L;
    }

    @Override
    public void onDisabled() {
        Myau.hitManager.cancel();
    }

    /**
     * Runs after every module that can cancel a click (Scaffold, KillAura, AutoHeal...) but before
     * Hitflick and MoreKB: a void hit takes precedence, and when no void is in reach the click
     * falls through to them untouched.
     */
    @EventTarget(Priority.LOW)
    public void onLeftClick(LeftClickMouseEvent event) {
        if (event.isCancelled() || Myau.hitManager.isAttacking()) return;
        if (mc.thePlayer == null || mc.theWorld == null || mc.playerController == null) return;
        // clickMouse() itself does nothing during the hit delay, so neither do we.
        if (((IAccessorMinecraft) mc).getLeftClickCounter() > 0) return;

        if (Myau.hitManager.isBusy()) {
            // Extra clicks while a hit is deferred are swallowed, exactly like the original swing.
            event.setCancelled(true);
            return;
        }

        EntityLivingBase target = Hitflick.targetUnderCrosshair();
        if (target == null) return;
        if (this.requireSprint.getValue() && !mc.thePlayer.isSprinting()) return;

        long now = System.currentTimeMillis();
        if (now - this.lastFlickTime < this.cooldown.getValue()) return;

        float baseYaw = Hitflick.yawTo(target);
        float minDeviation = -this.maxAngle.getValue();
        float maxDeviation = this.maxAngle.getValue();
        if (this.hitboxSafe.getValue()) {
            // Only look for a drop among directions that still land a legitimate hit.
            float[] span = HitManager.hitboxYawSpan(target);
            if (span != null) {
                float centerOffset = net.minecraft.util.MathHelper.wrapAngleTo180_float(span[0] - baseYaw);
                minDeviation = Math.max(minDeviation, centerOffset + span[1]);
                maxDeviation = Math.min(maxDeviation, centerOffset + span[2]);
            }
        }
        float voidYaw = VoidUtil.findVoidYaw(target, baseYaw, this.range.getValue(), this.depth.getValue(), minDeviation, maxDeviation);
        if (Float.isNaN(voidYaw)) {
            if (this.debug.getValue()) {
                ChatUtil.sendFormatted(String.format("%s&7VoidFlick: no drop within %.1f blocks, normal hit&r", Myau.clientName, this.range.getValue()));
            }
            return;
        }
        // The best direction is usually right at the hitbox edge; a hand doesn't land on the exact
        // same spot every time, so use a random 70-100% of the way there.
        float deviation = net.minecraft.util.MathHelper.wrapAngleTo180_float(voidYaw - baseYaw);
        voidYaw = baseYaw + deviation * (0.7F + 0.3F * java.util.concurrent.ThreadLocalRandom.current().nextFloat());

        int slot = this.kbStick.getValue() ? Hitflick.knockbackSlotToUse() : -1;
        Myau.hitManager.setVisibleSwap(this.visibleSwap.getValue());
        // Per-hit variance: a hand's turn and swap timings are never identical twice.
        if (Myau.hitManager.schedule(target, voidYaw, this.moveFix.getValue(), slot,
                HitManager.jitterMs(this.stickHold.getValue()),
                HitManager.jitterTicks(this.rampIn.getValue(), 1, 5),
                HitManager.jitterTicks(this.rampOut.getValue(), 0, 6),
                this.hitboxSafe.getValue(), this.debug.getValue())) {
            this.lastFlickTime = now;
            // Suppresses the vanilla swing and attack; the manager replays both next tick.
            event.setCancelled(true);
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.kbStick.getValue() ? "Stick" : "Flick"};
    }
}
