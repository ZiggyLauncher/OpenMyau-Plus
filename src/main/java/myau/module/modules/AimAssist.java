package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.MoveInputEvent;
import myau.events.Render3DEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import myau.rotation.Bone;
import myau.rotation.Rotation;
import myau.rotation.RotationConfig;
import myau.rotation.Rotator;
import myau.util.MoveUtil;
import myau.util.RotationUtil;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

/**
 * Bone-based aim assist.
 * <p>
 * Every tick it picks the target whose nearest enabled bone sits closest to the centre of your
 * view, then hands that bone to a rotator which walks the view toward it. Nothing snaps: the
 * rotator only ever produces a step, and the aim point is re-evaluated every frame, so the turn
 * tracks a moving target instead of chasing where it used to be.
 * <p>
 * Two rotators are available. <b>Ease-Out-Cubic</b> covers a share of the remaining angle each
 * step and tapers in. <b>WindMouse</b> integrates a gravity pull toward the target against a
 * random sideways wind, which drifts and settles the way a hand does; it also folds your own
 * mouse movement back into its current segment, so moving the mouse steers the assist rather
 * than fighting it.
 * <p>
 * In <b>Regular</b> mode the turn goes through {@link Entity#setAngles}, the exact path the
 * vanilla mouse uses, so the view really moves. In <b>Silent</b> mode the view is untouched and
 * only the rotation reported to the server changes, quantised onto the same sensitivity steps a
 * real mouse can produce.
 */
public class AimAssist extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final int MODE_REGULAR = 0;
    private static final int MODE_SILENT = 1;
    private static final int PRIORITY_CLOSEST = 0;
    private static final int PRIORITY_HEALTH = 1;

    /** Vanilla converts mouse counts to degrees with this factor, and setAngles multiplies it in. */
    private static final float TURN_FACTOR = 0.15F;
    /** Rotation priority for the silent path. KillAura uses 1, so it always wins over this. */
    private static final int ROTATION_PRIORITY = 0;
    /** Fixed reach, as in the original - it is not a setting there and should not be one here. */
    private static final double RANGE = 6.0;

    // Setting order and names follow the original module exactly.
    public final FloatProperty fov = new FloatProperty("fov", 80.0F, 10.0F, 360.0F);
    public final ModeProperty mode = new ModeProperty("mode", MODE_REGULAR, new String[]{"Regular", "Silent"});
    public final ModeProperty rotation = new ModeProperty("rotation", RotationConfig.EASE_OUT_CUBIC, RotationConfig.SMOOTHING);
    public final FloatProperty smoothness = new FloatProperty("smoothness", 50.0F, 0.0F, 100.0F);
    public final BooleanProperty onHold = new BooleanProperty("onHold", false);
    public final BooleanProperty weaponsOnly = new BooleanProperty("weaponsOnly", false);
    public final ModeProperty priority = new ModeProperty("target", PRIORITY_CLOSEST, new String[]{"Closest to FOV", "Lowest health"});
    /**
     * Rotates your movement input to match the rotation being reported, the way the original's
     * MoveFix does. Silent mode is unusable without it: the server simulates your movement from
     * the rotation you send, so sending one rotation while walking along another is a movement
     * mismatch on every tick, which is what a prediction anticheat flags and lags you back for.
     */
    public final BooleanProperty moveFix = new BooleanProperty("move-fix", true,
            () -> this.mode.getValue() == MODE_SILENT);
    // The original's TargetSettings group: Players, Invisible, Entities. It carries no team or
    // bot filter, so neither does this; friends are excluded unconditionally, as there.
    public final BooleanProperty players = new BooleanProperty("players", true);
    public final BooleanProperty invisible = new BooleanProperty("invisible", false);
    public final BooleanProperty entities = new BooleanProperty("entities", false);

    /**
     * Selecting multipoint hides the named bones, matching the original: multipoint already
     * tracks whichever part of the hitbox is nearest the crosshair, so a fixed bone next to it
     * would only ever pull the aim away from it.
     */
    public final BooleanProperty boneMultipoint = new BooleanProperty("multipoint", false);
    public final BooleanProperty boneHead = new BooleanProperty("head", true, () -> !this.boneMultipoint.getValue());
    public final BooleanProperty boneBody = new BooleanProperty("body", false, () -> !this.boneMultipoint.getValue());
    public final BooleanProperty boneArms = new BooleanProperty("arms", false, () -> !this.boneMultipoint.getValue());
    public final BooleanProperty boneLegs = new BooleanProperty("legs", false, () -> !this.boneMultipoint.getValue());

    /** The target chosen this tick, and the bone on it being aimed at. */
    private EntityLivingBase current;
    private Bone currentBone;
    /** Kept between ticks so the visible path can keep stepping toward the same bone. */
    private Rotator rotator;
    private int rotatorKind = -1;
    /** Silent mode only: the rotation being reported, which decays back to the real one. */
    private Rotation silent;
    /** Set while another module owns the rotation, so the per-frame visible turn stops too. */
    private boolean yielded;
    private long lastFrameNanos;

    public AimAssist() {
        super("AimAssist", false, false, "Bone-based aim assist with humanised rotation");
    }

    @Override
    public void onEnabled() {
        this.reset();
    }

    @Override
    public void onDisabled() {
        this.reset();
        this.silent = null;
        reported = null;
    }

    private void reset() {
        this.current = null;
        this.currentBone = null;
        this.rotator = null;
        this.rotatorKind = -1;
        this.lastFrameNanos = 0L;
        this.yielded = false;
    }

    /**
     * Gives the rotation up entirely for this tick: no target, nothing reported, and the visible
     * turn skipped until the owning module is done. The silent rotation is dropped rather than
     * decayed, because decaying would mean sending one, and the point is to send nothing.
     */
    private void standDown() {
        this.current = null;
        this.currentBone = null;
        this.silent = null;
        this.rotator = null;
        reported = null;
        this.yielded = true;
    }

    /**
     * The rotation the server currently believes we are looking along, or null in Regular mode.
     * Read from the render thread by the pick redirect, written on the client thread.
     */
    private static volatile Rotation reported;

    /** The entity being assisted onto, for other modules and the HUD. Null when idle. */
    public EntityLivingBase getTarget() {
        return this.isEnabled() ? this.current : null;
    }

    /**
     * The look vector the client's own hit detection should use for {@code entity}, or null to
     * leave it alone.
     * <p>
     * Without this, silent mode changes only the rotation in the outgoing packet: the server sees
     * the aim, but {@code EntityRenderer.getMouseOver} still traces along the real view, so there
     * is never anything under the crosshair to attack. Pointing the pick down the same ray is
     * what makes the mode do anything at all.
     */
    public static Vec3 silentLook(Entity entity) {
        Rotation rotation = reported;
        if (rotation == null || entity == null || entity != mc.thePlayer) {
            return null;
        }
        return direction(rotation);
    }

    /** Same construction as {@code Entity.getVectorForRotation}, so the ray matches vanilla's. */
    private static Vec3 direction(Rotation rotation) {
        float yawCos = MathHelper.cos(-rotation.yaw * 0.017453292F - (float) Math.PI);
        float yawSin = MathHelper.sin(-rotation.yaw * 0.017453292F - (float) Math.PI);
        float pitchCos = -MathHelper.cos(-rotation.pitch * 0.017453292F);
        float pitchSin = MathHelper.sin(-rotation.pitch * 0.017453292F);
        return new Vec3(yawSin * pitchCos, pitchSin, yawCos * pitchCos);
    }

    private RotationConfig config() {
        float smooth = Math.max(0.0F, Math.min(1.0F, this.smoothness.getValue() / 100.0F));
        int curve = this.rotation.getValue();
        return this.mode.getValue() == MODE_SILENT
                ? RotationConfig.silent(smooth, curve)
                : RotationConfig.visible(smooth, curve);
    }

    /** The live rotator, rebuilt when the curve is switched so no state carries across. */
    private Rotator rotator() {
        int kind = this.rotation.getValue();
        if (this.rotator == null || this.rotatorKind != kind) {
            this.rotatorKind = kind;
            this.rotator = this.config().createRotator();
        }
        return this.rotator;
    }

    private boolean boneEnabled(Bone bone) {
        switch (bone) {
            case MULTIPOINT:
                return this.boneMultipoint.getValue();
            case HEAD:
                return this.boneHead.getValue();
            case BODY:
                return this.boneBody.getValue();
            case LEFT_ARM:
            case RIGHT_ARM:
                return this.boneArms.getValue();
            case LEFT_LEG:
            case RIGHT_LEG:
                return this.boneLegs.getValue();
            default:
                return false;
        }
    }

    /**
     * The original's {@code Game.playing}: in a world, no screen open, and the mouse grabbed.
     * {@code inGameHasFocus} is this version's mouse-grabbed flag. There is deliberately no check
     * on your own health - the original has none either.
     */
    private boolean canAim() {
        if (mc.thePlayer == null || mc.theWorld == null || mc.currentScreen != null || !mc.inGameHasFocus) {
            return false;
        }
        if (this.onHold.getValue() && !mc.gameSettings.keyBindAttack.isKeyDown()) {
            return false;
        }
        if (this.weaponsOnly.getValue() && !holdsWeapon()) {
            return false;
        }
        // Not a setting: two aim systems pulling the view at once is a bug, not a choice, so
        // KillAura always wins while it holds a target.
        return !this.killAuraBusy();
    }

    /**
     * The original tests for the item's weapon component, which on this version means the classes
     * that carry an attack damage modifier by default: swords and axes.
     */
    private static boolean holdsWeapon() {
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || held.getItem() == null) {
            return false;
        }
        return held.getItem() instanceof ItemSword || held.getItem() instanceof ItemAxe;
    }

    /** KillAura owns the rotation while it has a target; two modules aiming at once looks wrong. */
    private boolean killAuraBusy() {
        if (Myau.moduleManager == null) {
            return false;
        }
        Module module = Myau.moduleManager.modules.get(KillAura.class);
        if (!(module instanceof KillAura) || !module.isEnabled()) {
            return false;
        }
        return ((KillAura) module).getTarget() != null;
    }

    /**
     * Modules that steer the rotation themselves for as long as they are on. This one stands
     * down while any of them is engaged, rather than taking turns with them tick by tick.
     * <p>
     * The modules that only rotate in bursts - the flicks, AntiFireball - are not listed: they
     * are already covered by this handler running at the lowest event priority and standing down
     * whenever it finds the rotation has been claimed for the tick.
     */
    private static boolean foreignRotation() {
        if (Myau.moduleManager == null) {
            return false;
        }
        return enabled(Scaffold.class)
                || enabled(ChestAura.class)
                || enabled(BedNuker.class)
                || enabled(AutoBlockIn.class)
                || enabled(AutoBedDef.class)
                || enabled(AutoHeadHitter.class);
    }

    private static boolean enabled(Class<? extends Module> type) {
        Module module = Myau.moduleManager.modules.get(type);
        return module != null && module.isEnabled();
    }

    /**
     * The original's {@code TargetSettings.accepts}, then its reach and line-of-sight checks.
     * <p>
     * Distance is tested before the rest because it is a subtraction while line of sight is a ray
     * trace; in a full lobby that ordering is most of the cost.
     */
    private boolean targeted(EntityPlayerSP player, EntityLivingBase entity) {
        if (entity == player || entity == mc.getRenderViewEntity()) {
            return false;
        }
        if (player.getDistanceSqToEntity(entity) > RANGE * RANGE) {
            return false;
        }
        if (!entity.isEntityAlive()) {
            return false;
        }
        if (entity instanceof EntityPlayer && ((EntityPlayer) entity).isSpectator()) {
            return false;
        }
        // Friends are protected unconditionally there, not behind a setting.
        if (entity instanceof EntityPlayer && TeamUtil.isFriend((EntityPlayer) entity)) {
            return false;
        }
        if (entity.isInvisible() && !this.invisible.getValue()) {
            return false;
        }
        if (entity instanceof EntityPlayer) {
            if (!this.players.getValue()) {
                return false;
            }
        } else if (!this.entities.getValue()) {
            return false;
        }
        return player.canEntityBeSeen(entity);
    }

    /**
     * Picks the target and the bone on it. The bone must sit inside the FOV cone; among the
     * targets that qualify, the configured priority breaks the tie.
     */
    private void selectTarget() {
        this.current = null;
        this.currentBone = null;
        EntityPlayerSP player = mc.thePlayer;
        Vec3 eye = player.getPositionEyes(1.0F);
        Vec3 look = player.getLook(1.0F);
        double halfFov = this.fov.getValue() * 0.5;
        double bestScore = Double.MAX_VALUE;

        for (int i = 0; i < mc.theWorld.loadedEntityList.size(); i++) {
            Entity entity = mc.theWorld.loadedEntityList.get(i);
            if (!(entity instanceof EntityLivingBase)) {
                continue;
            }
            EntityLivingBase living = (EntityLivingBase) entity;
            if (!this.targeted(player, living)) {
                continue;
            }

            Bone closest = null;
            double closestAngle = halfFov;
            for (int b = 0; b < Bone.ALL.length; b++) {
                Bone bone = Bone.ALL[b];
                if (!this.boneEnabled(bone)) {
                    continue;
                }
                double angle = angleBetween(look, bone.point(player, living, 1.0F).subtract(eye));
                if (angle >= closestAngle) {
                    continue;
                }
                closest = bone;
                closestAngle = angle;
            }
            if (closest == null) {
                continue;
            }

            double score = this.priority.getValue() == PRIORITY_HEALTH ? living.getHealth() : closestAngle;
            if (score >= bestScore) {
                continue;
            }
            bestScore = score;
            this.current = living;
            this.currentBone = closest;
        }
    }

    /** Where the view should be pointing right now, at the given partial tick. */
    private Rotation aimAt(float partialTicks) {
        return Rotation.toward(mc.thePlayer.getPositionEyes(partialTicks),
                this.currentBone.point(mc.thePlayer, this.current, partialTicks));
    }

    /**
     * Runs at the lowest event priority on purpose, so every module that steers the rotation has
     * already had its say by the time this one looks. If the rotation is already claimed for the
     * tick this module stands down - which is the same outcome the original reaches by requesting
     * at priority zero and letting its rotation manager pick the highest bidder.
     */
    @EventTarget(Priority.LOWEST)
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE) {
            return;
        }
        if (event.isRotated() || foreignRotation()) {
            this.standDown();
            return;
        }
        this.yielded = false;
        if (!this.canAim()) {
            this.current = null;
            this.currentBone = null;
            this.decaySilent(event);
            return;
        }

        this.selectTarget();
        if (this.current == null || this.currentBone == null) {
            this.decaySilent(event);
            return;
        }

        if (this.mode.getValue() != MODE_SILENT) {
            // Regular mode turns the view itself, once per frame, in onRender3D.
            this.silent = null;
            reported = null;
            return;
        }

        Rotation actual = Rotation.of(mc.thePlayer);
        Rotation from = this.silent != null ? this.silent : actual;
        Rotation stepped = this.rotator().step(from, this.aimAt(1.0F), this.config(), 1.0F);
        this.silent = quantize(stepped, event.getYaw(), event.getPitch());
        reported = this.silent;
        event.setRotation(this.silent.yaw, this.silent.pitch, ROTATION_PRIORITY);
        this.reportMovementYaw(event, this.silent.yaw);
    }

    /**
     * Tells the client's MoveFix which yaw the server is going to simulate this tick, so it can
     * rotate the movement input to match. Passing the real yaw instead is the same as asking for
     * no correction, which is what the other modules here do when their move-fix is off.
     */
    private void reportMovementYaw(UpdateEvent event, float silentYaw) {
        event.setPervRotation(this.moveFix.getValue() ? silentYaw : mc.thePlayer.rotationYaw,
                ROTATION_PRIORITY);
    }

    /**
     * Applies the strafe correction directly when the MoveFix module is not already doing it.
     * <p>
     * Silent mode is not safe to run uncorrected, and MoveFix is off by default, so leaving this
     * to the user to discover means the first thing silent mode does is get them lagged back. The
     * MoveFix module owns the correction whenever it is on - running both would rotate the input
     * twice and send you somewhere neither rotation points.
     */
    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (this.yielded || this.mode.getValue() != MODE_SILENT || !this.moveFix.getValue()) {
            return;
        }
        Rotation current = this.silent;
        if (current == null || mc.thePlayer == null || moveFixModuleActive()) {
            return;
        }
        if (MoveUtil.isForwardPressed()) {
            MoveUtil.fixStrafe(current.yaw);
        }
    }

    private static boolean moveFixModuleActive() {
        if (Myau.moduleManager == null) {
            return false;
        }
        Module module = Myau.moduleManager.modules.get(MoveFix.class);
        return module != null && module.isEnabled();
    }

    /**
     * With no target the reported rotation walks back to the real one instead of snapping to it,
     * which would otherwise be a jump no mouse could produce.
     */
    private void decaySilent(UpdateEvent event) {
        if (this.silent == null || mc.thePlayer == null) {
            this.silent = null;
            reported = null;
            this.rotator = null;
            return;
        }
        Rotation actual = Rotation.of(mc.thePlayer);
        Rotation stepped = this.rotator().step(this.silent, actual, this.config(), 1.0F);
        Rotation next = quantize(stepped, event.getYaw(), event.getPitch());
        if (next.distanceTo(actual) < (float) RotationUtil.gcd()) {
            this.silent = null;
            reported = null;
            this.rotator = null;
            return;
        }
        this.silent = next;
        reported = next;
        event.setRotation(next.yaw, next.pitch, ROTATION_PRIORITY);
        this.reportMovementYaw(event, next.yaw);
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        long now = System.nanoTime();
        float deltaTicks = this.lastFrameNanos == 0L ? 0.0F
                : Math.min(4.0F, Math.max(0.0F, (now - this.lastFrameNanos) / 1.0e9F * 20.0F));
        this.lastFrameNanos = now;

        // The visible turn has to respect the stand-down too: it moves the view directly, outside
        // the rotation arbitration, so it would otherwise fight whichever module took over.
        if (this.yielded || this.mode.getValue() == MODE_SILENT
                || this.current == null || this.currentBone == null) {
            return;
        }
        if (deltaTicks <= 0.0F || mc.thePlayer == null || mc.theWorld == null || mc.currentScreen != null) {
            return;
        }
        if (this.current.isDead || this.current.getHealth() <= 0.0F) {
            return;
        }

        float partialTicks = Math.max(0.0F, Math.min(1.0F, event.getPartialTicks()));
        Rotation actual = Rotation.of(mc.thePlayer);
        Rotation next = this.rotator().step(actual, this.aimAt(partialTicks), this.config(), deltaTicks);

        float deltaYaw = actual.yawTo(next);
        float deltaPitch = actual.pitchTo(next);
        if (deltaYaw == 0.0F && deltaPitch == 0.0F) {
            return;
        }
        // setAngles is the vanilla mouse path: yaw += x * 0.15, pitch -= y * 0.15, then clamped.
        // It carries prevRotation along too, so the view does not smear across the frame.
        mc.thePlayer.setAngles(deltaYaw / TURN_FACTOR, -deltaPitch / TURN_FACTOR);
    }

    /**
     * Rounds onto the steps a real mouse produces at this sensitivity, measured from the last
     * rotation the server was given.
     */
    private static Rotation quantize(Rotation rotation, float baseYaw, float basePitch) {
        double step = RotationUtil.gcd();
        if (step <= 0.0) {
            return rotation;
        }
        float yaw = baseYaw + (float) (Math.round((rotation.yaw - baseYaw) / step) * step);
        float pitch = basePitch + (float) (Math.round((rotation.pitch - basePitch) / step) * step);
        return new Rotation(yaw, MathHelper.clamp_float(pitch, -90.0F, 90.0F));
    }

    /** Angle between two vectors, in degrees. */
    private static double angleBetween(Vec3 first, Vec3 second) {
        if (second.lengthVector() <= 1.0E-7) {
            return 0.0;
        }
        double cosine = first.normalize().dotProduct(second.normalize());
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, cosine))));
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }
}
