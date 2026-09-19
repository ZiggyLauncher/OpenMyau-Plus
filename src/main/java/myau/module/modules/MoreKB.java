package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.Priority;
import myau.events.AttackEvent;
import myau.events.LeftClickMouseEvent;
import myau.events.MoveInputEvent;
import myau.events.TickEvent;
import myau.mixin.IAccessorMinecraft;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;

public class MoreKB extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int MODE_WTAP = 5;

    /**
     * WTAP is the only mode whose packets are produced by vanilla code: after your hit, forward is
     * released for one tick through the real input path, so the client itself stops sprinting
     * (STOP packet, one tick of non-sprint movement) and starts again next tick (START packet) -
     * exactly what a human W-tap sends.
     * <p>
     * The modes marked {@code *} hand-craft sprint packets - a stop and a start inside one tick,
     * which no key press can produce and which a prediction anticheat models per tick. They are
     * kept for servers that do not simulate movement; on Grim/Polar use WTAP (or LEGIT/LEGIT_FAST,
     * which only touch the client's own sprint state).
     */
    public final ModeProperty mode = new ModeProperty("mode", MODE_WTAP,
            new String[]{"LEGIT", "LEGIT_FAST", "LESS_PACKET*", "PACKET*", "DOUBLE_PACKET*", "WTAP"});
    public final BooleanProperty intelligent = new BooleanProperty("intelligent", false);
    public final BooleanProperty onlyGround = new BooleanProperty("only-ground", true);
    public final IntProperty wtapDelay = new IntProperty("wtap-delay", 1, 0, 5, () -> this.mode.getValue() == MODE_WTAP);
    public final BooleanProperty stickSwap = new BooleanProperty("stick-swap", false);
    /** Really switch the hotbar to the stick (visible in hand) instead of a server-side-only swap. */
    public final BooleanProperty visibleSwap = new BooleanProperty("visible-swap", true, stickSwap::getValue);
    /** Ticks between pressing the stick's hotbar slot and the hit; nobody lands a click 50 ms after a key press every time. */
    public final IntProperty swapDelay = new IntProperty("swap-delay", 3, 1, 5, stickSwap::getValue);
    public final IntProperty swapBackDelay = new IntProperty("swap-back-delay", 250, 0, 1000, stickSwap::getValue);
    public final BooleanProperty debug = new BooleanProperty("debug", false, stickSwap::getValue);
    private boolean shouldSprintReset;
    private EntityLivingBase target;
    /** Tick on which forward is released for the W-tap, or MIN_VALUE when none is scheduled. */
    private int wtapTick = Integer.MIN_VALUE;

    public MoreKB() {
        super("MoreKB", false);
        this.shouldSprintReset = false;
        this.target = null;
    }

    @Override
    public void onDisabled() {
        if (this.stickSwap.getValue()) {
            Myau.hitManager.cancel();
        }
        this.target = null;
        this.wtapTick = Integer.MIN_VALUE;
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        Entity targetEntity = event.getTarget();
        if (targetEntity != null && targetEntity instanceof EntityLivingBase) {
            this.target = (EntityLivingBase) targetEntity;
            if (this.mode.getValue() == MODE_WTAP && mc.thePlayer != null && mc.thePlayer.isSprinting()) {
                this.wtapTick = mc.thePlayer.ticksExisted + this.wtapDelay.getValue();
            }
        }
    }

    /**
     * Runs right after the input state is read: zeroing forward here is the same as the key not
     * being held this tick, so onLivingUpdate stops the sprint and re-enables it next tick.
     */
    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (this.mode.getValue() != MODE_WTAP || mc.thePlayer == null || this.wtapTick == Integer.MIN_VALUE) {
            return;
        }
        if (mc.thePlayer.ticksExisted < this.wtapTick) {
            return;
        }
        this.wtapTick = Integer.MIN_VALUE;
        if (this.onlyGround.getValue() && !mc.thePlayer.onGround) {
            return;
        }
        if (mc.thePlayer.movementInput.moveForward > 0.0F) {
            mc.thePlayer.movementInput.moveForward = 0.0F;
        }
    }

    /**
     * Stick swap: the click is handed to HitManager, which switches to the stick now, hits with it
     * {@code swap-delay} ticks later and switches back after {@code swap-back-delay} - a hotbar
     * key, a click, a hotbar key, at human pace. When Hitflick or VoidFlick is on they include the
     * stick in their own deferred hit instead.
     */
    @EventTarget(Priority.LOWEST)
    public void onLeftClick(LeftClickMouseEvent event) {
        if (!this.stickSwap.getValue() || event.isCancelled() || Myau.hitManager.isAttacking()) return;
        if (mc.thePlayer == null || mc.theWorld == null || mc.playerController == null) return;
        if (((IAccessorMinecraft) mc).getLeftClickCounter() > 0) return;
        Hitflick hitflick = (Hitflick) Myau.moduleManager.modules.get(Hitflick.class);
        VoidFlick voidFlick = (VoidFlick) Myau.moduleManager.modules.get(VoidFlick.class);
        if ((hitflick != null && hitflick.isEnabled()) || (voidFlick != null && voidFlick.isEnabled())) return;

        if (Myau.hitManager.isBusy()) {
            event.setCancelled(true);
            return;
        }
        EntityLivingBase living = Hitflick.targetUnderCrosshair();
        if (living == null) return;
        int slot = Hitflick.knockbackSlotToUse();
        if (slot < 0) return;
        Myau.hitManager.setVisibleSwap(this.visibleSwap.getValue());
        if (Myau.hitManager.schedule(living, Float.NaN, false, slot,
                myau.management.HitManager.jitterMs(this.swapBackDelay.getValue()),
                myau.management.HitManager.jitterTicks(this.swapDelay.getValue(), 1, 5),
                0, false, this.debug.getValue())) {
            event.setCancelled(true);
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        if (this.mode.getValue() == 1) {
            if (this.target != null && this.isMoving()) {
                if ((this.onlyGround.getValue() && mc.thePlayer.onGround) || !this.onlyGround.getValue()) {
                    mc.thePlayer.sprintingTicksLeft = 0;
                }
                this.target = null;
            }
            return;
        }
        EntityLivingBase entity = null;
        if (mc.objectMouseOver != null && mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY && mc.objectMouseOver.entityHit instanceof EntityLivingBase) {
            entity = (EntityLivingBase) mc.objectMouseOver.entityHit;
        }
        if (entity == null) {
            return;
        }
        double x = mc.thePlayer.posX - entity.posX;
        double z = mc.thePlayer.posZ - entity.posZ;
        float calcYaw = (float) (Math.atan2(z, x) * 180.0 / Math.PI - 90.0);
        float diffY = Math.abs(MathHelper.wrapAngleTo180_float(calcYaw - entity.rotationYawHead));
        if (this.intelligent.getValue() && diffY > 120.0F) {
            return;
        }
        if (entity.hurtTime == 10) {
            switch (this.mode.getValue()) {
                case 0:
                    this.shouldSprintReset = true;
                    if (mc.thePlayer.isSprinting()) {
                        mc.thePlayer.setSprinting(false);
                        mc.thePlayer.setSprinting(true);
                    }
                    this.shouldSprintReset = false;
                    break;
                case 2:
                    if (mc.thePlayer.isSprinting()) {
                        mc.thePlayer.setSprinting(false);
                    }
                    mc.getNetHandler().addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING));
                    mc.thePlayer.setSprinting(true);
                    break;
                case 3:
                    mc.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.STOP_SPRINTING));
                    mc.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING));
                    mc.thePlayer.setSprinting(true);
                    break;
                case 4:
                    mc.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.STOP_SPRINTING));
                    mc.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING));
                    mc.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.STOP_SPRINTING));
                    mc.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, C0BPacketEntityAction.Action.START_SPRINTING));
                    mc.thePlayer.setSprinting(true);
                    break;
            }
        }
    }

    private boolean isMoving() {
        return mc.thePlayer.moveForward != 0.0F || mc.thePlayer.moveStrafing != 0.0F;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getValue().toString()};
    }
}
