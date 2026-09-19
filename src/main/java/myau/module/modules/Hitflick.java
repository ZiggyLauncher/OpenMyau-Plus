package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.Priority;
import myau.events.LeftClickMouseEvent;
import myau.mixin.IAccessorMinecraft;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.ItemUtil;
import myau.util.VoidUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MovingObjectPosition;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Knockback displacement, ported from Grizzly Client's "Hit Flick" (KnockbackDisplacement, b3-26.3).
 * <p>
 * The server computes the sprint/Knockback-enchant part of knockback from the attacker's yaw when
 * the attack is processed, so the flicked yaw must already be on the server before the hit. The
 * click is handed to {@link myau.management.HitManager}, which sends the flick (and the Auto-KB
 * slot) a tick early, performs the hit the next tick, and restores everything the tick after.
 * The camera and hotbar never move.
 */
public class Hitflick extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final float VOID_MAX_DEVIATION = 180.0F;

    private static final int MODE_LEFT = 0;
    private static final int MODE_RIGHT = 1;
    private static final int MODE_RANDOM = 2;
    private static final int MODE_STRAFE = 3;
    private static final int MODE_STRAFE_INVERTED = 4;
    private static final int MODE_VOID = 5;

    public final ModeProperty mode = new ModeProperty("Mode", MODE_RIGHT, new String[]{"Left", "Right", "Random", "Strafe", "Strafe-Inverted", "Void"});
    public final FloatProperty minAngle = new FloatProperty("Min-Angle", 85.0F, 10.0F, 180.0F, () -> this.mode.getValue() != MODE_VOID);
    public final FloatProperty maxAngle = new FloatProperty("Max-Angle", 95.0F, 10.0F, 180.0F, () -> this.mode.getValue() != MODE_VOID);
    public final FloatProperty voidDistance = new FloatProperty("Void-Distance", 2.0F, 0.5F, 5.0F, () -> this.mode.getValue() == MODE_VOID);
    public final IntProperty voidDepth = new IntProperty("Void-Depth", 6, 2, 32, () -> this.mode.getValue() == MODE_VOID);
    public final BooleanProperty requireSprint = new BooleanProperty("Require-Sprint", true);
    public final IntProperty cooldown = new IntProperty("Cooldown", 250, 0, 2000);
    public final BooleanProperty autoKb = new BooleanProperty("Auto-KB", false);
    /** Really switch the hotbar to the stick (visible in hand) instead of a server-side-only swap. */
    public final BooleanProperty visibleSwap = new BooleanProperty("Visible-Swap", true, autoKb::getValue);
    public final BooleanProperty moveFix = new BooleanProperty("Move-Fix", true);
    public final IntProperty rampIn = new IntProperty("Ramp-In", 3, 1, 5);
    public final IntProperty rampOut = new IntProperty("Ramp-Out", 3, 0, 6);
    /** Keep the sent rotation inside the target's hitbox so the hit stays legitimate for raycast checks (limits the angle). */
    public final BooleanProperty hitboxSafe = new BooleanProperty("Hitbox-Safe", true);
    public final BooleanProperty debug = new BooleanProperty("Debug", false);

    private long lastFlickTime;

    public Hitflick() {
        super("Hitflick", false, true, "Silently flicks rotation on each attack to displace knockback sideways");
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
     * Runs after every module that can cancel a click (Scaffold, KillAura, AutoHeal...) and after
     * VoidFlick, so an already-taken click is never flicked twice.
     */
    @EventTarget(Priority.LOWEST)
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

        EntityLivingBase target = targetUnderCrosshair();
        if (target == null) return;

        long now = System.currentTimeMillis();
        float flickYaw = Float.NaN;
        if ((!this.requireSprint.getValue() || mc.thePlayer.isSprinting()) && now - this.lastFlickTime >= this.cooldown.getValue()) {
            flickYaw = this.chooseFlickYaw(target);
        }

        int slot = -1;
        long restoreDelay = 0L;
        MoreKB moreKb = (MoreKB) Myau.moduleManager.modules.get(MoreKB.class);
        boolean moreKbStick = moreKb != null && moreKb.isEnabled() && moreKb.stickSwap.getValue();
        if (this.autoKb.getValue() || moreKbStick) {
            slot = knockbackSlotToUse();
            if (moreKbStick) {
                restoreDelay = moreKb.swapBackDelay.getValue();
                Myau.hitManager.setVisibleSwap(moreKb.visibleSwap.getValue());
            } else {
                Myau.hitManager.setVisibleSwap(this.visibleSwap.getValue());
            }
        }

        if (Float.isNaN(flickYaw) && slot < 0) return;

        // Per-hit variance: a hand's turn and swap timings are never identical twice.
        if (Myau.hitManager.schedule(target, flickYaw, this.moveFix.getValue(), slot,
                myau.management.HitManager.jitterMs(restoreDelay),
                myau.management.HitManager.jitterTicks(this.rampIn.getValue(), 1, 5),
                myau.management.HitManager.jitterTicks(this.rampOut.getValue(), 0, 6),
                this.hitboxSafe.getValue(), this.debug.getValue())) {
            if (!Float.isNaN(flickYaw)) {
                this.lastFlickTime = now;
            }
            // Suppresses the vanilla swing and attack; the manager replays both next tick.
            event.setCancelled(true);
        }
    }

    /** The living entity a vanilla click would hit right now, or null. */
    static EntityLivingBase targetUnderCrosshair() {
        MovingObjectPosition hit = mc.objectMouseOver;
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.ENTITY) return null;
        if (!(hit.entityHit instanceof EntityLivingBase) || hit.entityHit == mc.thePlayer) return null;
        EntityLivingBase target = (EntityLivingBase) hit.entityHit;
        if (target.isDead) return null;
        // KillAura owns rotations and attacks while it has a target; leave manual hits alone then.
        KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        if (killAura != null && killAura.isEnabled() && killAura.target != null) return null;
        return target;
    }

    /** Best Knockback hotbar slot if it isn't the one already held, else -1. */
    static int knockbackSlotToUse() {
        int best = ItemUtil.findBestKnockbackHotbarSlot();
        return best >= 0 && best != mc.thePlayer.inventory.currentItem ? best : -1;
    }

    /** Yaw from the player to the target (Minecraft convention: 0 = south, -90 = east). */
    static float yawTo(EntityLivingBase target) {
        double dx = target.posX - mc.thePlayer.posX;
        double dz = target.posZ - mc.thePlayer.posZ;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /**
     * @return the yaw to send for the hit, or NaN when this click shouldn't be flicked.
     */
    private float chooseFlickYaw(EntityLivingBase target) {
        float baseYaw = yawTo(target);

        if (this.mode.getValue() == MODE_VOID) {
            return VoidUtil.findVoidYaw(target, baseYaw, this.voidDistance.getValue(), this.voidDepth.getValue(), VOID_MAX_DEVIATION);
        }

        float side;
        switch (this.mode.getValue()) {
            case MODE_LEFT:
                side = -1.0F;
                break;
            case MODE_RIGHT:
                side = 1.0F;
                break;
            case MODE_RANDOM:
                side = ThreadLocalRandom.current().nextBoolean() ? -1.0F : 1.0F;
                break;
            case MODE_STRAFE:
            case MODE_STRAFE_INVERTED: {
                boolean left = mc.gameSettings.keyBindLeft.isKeyDown();
                boolean right = mc.gameSettings.keyBindRight.isKeyDown();
                if (left == right) {
                    return Float.NaN;
                }
                side = left ? -1.0F : 1.0F;
                if (this.mode.getValue() == MODE_STRAFE_INVERTED) {
                    side = -side;
                }
                break;
            }
            default:
                return Float.NaN;
        }

        float lo = Math.min(this.minAngle.getValue(), this.maxAngle.getValue());
        float hi = Math.max(this.minAngle.getValue(), this.maxAngle.getValue());
        float angle = hi > lo ? lo + ThreadLocalRandom.current().nextFloat() * (hi - lo) : lo;
        return baseYaw + side * angle;
    }

    @Override
    public String[] getSuffix() {
        if (this.mode.getValue() == MODE_VOID) {
            return new String[]{"Void"};
        }
        float lo = Math.min(this.minAngle.getValue(), this.maxAngle.getValue());
        float hi = Math.max(this.minAngle.getValue(), this.maxAngle.getValue());
        String text = String.format("%.0f-%.0f°", lo, hi);
        if (hi - lo < 15.0F && hi > 90.0F && lo < 90.0F) text = "90°";
        if (hi - lo < 15.0F && hi >= 179.0F) text = "180°";
        return new String[]{this.mode.getModeString(), text};
    }
}
