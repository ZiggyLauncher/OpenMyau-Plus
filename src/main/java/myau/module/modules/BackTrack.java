package myau.module.modules;

import myau.Myau;
import myau.backtrack.Backtracker;
import myau.backtrack.TrackedPosition;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.AttackEvent;
import myau.events.PacketEvent;
import myau.events.Render3DEvent;
import myau.events.TickEvent;
import myau.mixin.IAccessorRenderManager;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.RenderUtil;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

import java.awt.Color;
import java.util.function.Supplier;

/**
 * Holds the inbound packet stream so a target stays drawn where they were, and hits land there.
 * <p>
 * Two modes. <b>Delay</b> keeps every packet for a fixed time and then lets it through, so the
 * world runs continuously but behind. <b>Freeze</b> holds indefinitely while a target is locked,
 * which is stronger and far more obvious.
 * <p>
 * The hold is given up early whenever keeping it would be wrong rather than merely late: if a
 * held packet turns out to be one that redefines the world ({@link Backtracker#forcesRelease}),
 * or if the target's real position is closing on you faster than the drawn one, the queue drains
 * immediately. That second rule is what stops backtrack turning into free damage taken - without
 * it you keep swinging at a ghost while the real player is already inside your reach.
 */
public class BackTrack extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final int MODE_DELAY = 0;
    private static final int MODE_FREEZE = 1;
    private static final int VISUALIZE_OFF = 0;
    private static final int VISUALIZE_BOX = 1;
    private static final int VISUALIZE_BOTH = 2;

    /** Keep tracking for this long after the target steps out of reach, so a dodge is not a reset. */
    private static final long TRACKING_BUFFER_MILLIS = 500L;
    /** Only backtrack a target you are actually fighting, or one an aim module is on. */
    private static final long ATTACK_WINDOW_MILLIS = 1000L;
    /** Skip the first half second in a world, while entities are still streaming in. */
    private static final int MIN_TICKS = 10;
    private static final int HURT_TIME = 3;
    /** The drawn box halves its distance to the real position every this many ticks. */
    private static final float HALF_LIFE_TICKS = 2.0F;
    private static final double SETTLED_DISTANCE_SQR = 1.0E-4;
    /**
     * Hard ceilings on the hold. Freeze mode has no natural end - it holds for as long as the
     * target is alive - so without these a long freeze grows the queue until the game runs out of
     * memory, and the server drops a client that has stopped acknowledging for too long.
     */
    private static final int MAX_HELD_PACKETS = 2000;
    private static final long MAX_HOLD_MILLIS = 10000L;
    /**
     * Vanilla entity interaction range. Not a setting in the original either - the hold is only
     * worth taking while the target is inside the reach the server will actually accept.
     */
    private static final double RANGE = 3.0;

    // Setting order and names follow the original module exactly, apart from the last one.
    public final ModeProperty mode = new ModeProperty("mode", MODE_DELAY, new String[]{"Delay", "Freeze"});
    public final IntProperty delay = new IntProperty("delay", 120, 0, 1000, () -> this.mode.getValue() != MODE_FREEZE);
    public final BooleanProperty pauseOnHurt = new BooleanProperty("pauseOnHurt", false, () -> this.mode.getValue() != MODE_FREEZE);
    public final BooleanProperty teams = new BooleanProperty("teams", true);
    public final BooleanProperty botCheck = new BooleanProperty("bot-check", true);
    public final ModeProperty visualize = new ModeProperty("visualize", VISUALIZE_BOX, new String[]{"Off", "Box", "Both"});
    /** Not in the original: this client has its own packet-delaying module to stay clear of. */
    public final BooleanProperty interruptLagRange = new BooleanProperty("interrupt-lagrange", true);

    private final TrackedPosition tracked = new TrackedPosition();
    private EntityLivingBase target;
    private long attackedAt;
    private long inRangeAt;
    private boolean lagRangeInterrupted;

    /** Render-only smoothing so the box slides to the real position instead of snapping. */
    private EntityLivingBase shownFor;
    private Vec3 shown;
    private long lastRenderNanos;

    public BackTrack() {
        super("Backtrack", false, false, "Holds the packet stream so hits land where the target was");
    }

    @Override
    public void onEnabled() {
        this.target = null;
        this.tracked.clear();
        this.attackedAt = 0L;
        this.inRangeAt = 0L;
        this.shownFor = null;
        this.shown = null;
        this.lastRenderNanos = 0L;
        Backtracker.hold(false);
    }

    @Override
    public void onDisabled() {
        Backtracker.hold(false);
        if (mc.thePlayer != null && mc.getNetHandler() != null) {
            Backtracker.releaseAll();
        } else {
            Backtracker.drop();
        }
        this.setLagRangeEnabled(true);
        this.target = null;
        this.tracked.clear();
        this.shownFor = null;
    }

    private boolean frozen() {
        return this.mode.getValue() == MODE_FREEZE;
    }

    // ------------------------------------------------------------------ packets

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (event.getType() != EventType.RECEIVE) {
            return;
        }
        if (Backtracker.intercept(event.getPacket())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ tick

    @EventTarget(Priority.LOW)
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE) {
            return;
        }
        if (mc.theWorld == null || mc.thePlayer == null || mc.getNetHandler() == null) {
            Backtracker.drop();
            this.target = null;
            this.tracked.clear();
            this.setLagRangeEnabled(true);
            return;
        }

        boolean hadHeld = Backtracker.isLagging();
        if (this.target != null) {
            this.scan();
        }

        EntityLivingBase assisted = this.assisted();
        if (assisted != null) {
            this.processTarget(assisted);
        }

        if (this.holding()) {
            if (!this.frozen()) {
                Backtracker.release(this.delay.getValue());
            }
        } else if (hadHeld) {
            this.reset();
        }

        if (this.overheld()) {
            this.reset();
        }

        boolean active = this.holding();
        Backtracker.hold(active);
        this.setLagRangeEnabled(!(active && Backtracker.isLagging()));

        // With nothing held the drawn position is the real one, so re-anchor. Without this the
        // base goes stale every time the hold lapses and resumes on the same target, and the
        // approach check then compares against a position from minutes ago.
        if (this.target != null && !Backtracker.isLagging()) {
            this.tracked.syncTo(this.target);
        }
    }

    /** True once the queue has grown or aged past what is safe to keep holding. */
    private boolean overheld() {
        if (!Backtracker.isLagging()) {
            return false;
        }
        if (Backtracker.size() > MAX_HELD_PACKETS) {
            return true;
        }
        long oldest = Backtracker.oldestAt();
        return oldest != 0L && System.currentTimeMillis() - oldest > MAX_HOLD_MILLIS;
    }

    /**
     * Inspects newly held packets: drains on anything that invalidates the held world, and gives
     * the hold up as soon as the target's real position is closer to you than the drawn one.
     */
    private void scan() {
        for (Backtracker.Held held : Backtracker.held()) {
            if (held.scanned()) {
                continue;
            }
            held.markScanned();

            Packet<?> packet = held.packet();
            if (Backtracker.forcesRelease(packet)) {
                this.reset();
                return;
            }

            Vec3 real = this.tracked.handle(packet, mc.theWorld, this.target);
            if (real == null || this.frozen() || !this.approaching(real)) {
                continue;
            }
            // Releasing invalidates our position in the queue, so the rest waits for next tick.
            Backtracker.releaseThrough(held);
            return;
        }
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        this.attackedAt = System.currentTimeMillis();
        Entity attacked = event.getTarget();
        if (attacked instanceof EntityLivingBase) {
            this.processTarget((EntityLivingBase) attacked);
        }
        Backtracker.hold(this.holding());
    }

    // ------------------------------------------------------------------ targeting

    /** The target an aim module has locked, when it is close enough to be worth holding for. */
    private EntityLivingBase assisted() {
        EntityLivingBase aimed = aimAssistTarget();
        if (aimed != null && this.reachable(aimed)) {
            return aimed;
        }
        EntityLivingBase aura = killAuraTarget();
        return aura != null && this.reachable(aura) ? aura : null;
    }

    private boolean assists(EntityLivingBase entity) {
        return aimAssistTarget() == entity || killAuraTarget() == entity;
    }

    private static EntityLivingBase aimAssistTarget() {
        if (Myau.moduleManager == null) {
            return null;
        }
        Module module = Myau.moduleManager.modules.get(AimAssist.class);
        return module instanceof AimAssist ? ((AimAssist) module).getTarget() : null;
    }

    private static EntityLivingBase killAuraTarget() {
        if (Myau.moduleManager == null) {
            return null;
        }
        Module module = Myau.moduleManager.modules.get(KillAura.class);
        return module instanceof KillAura && module.isEnabled() ? ((KillAura) module).getTarget() : null;
    }

    private void processTarget(EntityLivingBase enemy) {
        if (mc.thePlayer == null) {
            return;
        }
        // In freeze mode the first target owns the hold until it ends; swapping mid-freeze would
        // replay one target's packets while still holding another's.
        if (this.frozen() && this.target != null) {
            return;
        }
        if (!this.shouldBacktrack(enemy)) {
            return;
        }
        if (enemy != this.target) {
            this.reset();
            this.tracked.syncTo(enemy);
        }
        this.target = enemy;
    }

    private void reset() {
        Backtracker.releaseAll();
        this.target = null;
        this.tracked.clear();
    }

    /** True while the current target still justifies holding packets. */
    private boolean holding() {
        if (this.target == null || this.target.isDead || this.target.getHealth() <= 0.0F) {
            return false;
        }
        if (this.target.worldObj != mc.theWorld) {
            return false;
        }
        return this.frozen() || this.shouldBacktrack(this.target);
    }

    private boolean shouldBacktrack(EntityLivingBase entity) {
        if (mc.thePlayer == null || entity == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        boolean inRange = this.reachable(entity);
        if (inRange) {
            this.inRangeAt = now;
        }
        if (!inRange && now - this.inRangeAt >= TRACKING_BUFFER_MILLIS) {
            return false;
        }
        if (!this.isValidTarget(entity)) {
            return false;
        }
        if (mc.thePlayer.ticksExisted <= MIN_TICKS) {
            return false;
        }
        if (now - this.attackedAt > ATTACK_WINDOW_MILLIS && !this.assists(entity)) {
            return false;
        }
        return !this.pauseOnHurt.getValue() || entity.hurtTime < HURT_TIME;
    }

    private boolean isValidTarget(EntityLivingBase entity) {
        if (entity == mc.thePlayer || entity == mc.getRenderViewEntity()) {
            return false;
        }
        if (entity.isDead || entity.deathTime > 0 || entity.getHealth() <= 0.0F) {
            return false;
        }
        if (!(entity instanceof EntityPlayer)) {
            return false;
        }
        EntityPlayer player = (EntityPlayer) entity;
        if (TeamUtil.isFriend(player)) {
            return false;
        }
        if (this.teams.getValue() && TeamUtil.isSameTeam(player)) {
            return false;
        }
        return !this.botCheck.getValue() || !TeamUtil.isBot(player);
    }

    private boolean reachable(EntityLivingBase entity) {
        return boxedDistanceSqr(entity, entityPosition(entity), eyePosition()) <= RANGE * RANGE;
    }

    /** True when the real position is nearer to you than the one currently drawn. */
    private boolean approaching(Vec3 real) {
        if (this.target == null || mc.thePlayer == null) {
            return false;
        }
        Vec3 eye = eyePosition();
        return boxedDistanceSqr(this.target, real, eye)
                < boxedDistanceSqr(this.target, entityPosition(this.target), eye);
    }

    private static Vec3 eyePosition() {
        return mc.thePlayer.getPositionEyes(1.0F);
    }

    private static Vec3 entityPosition(Entity entity) {
        return new Vec3(entity.posX, entity.posY, entity.posZ);
    }

    /**
     * Squared distance from {@code from} to the entity's hitbox, moved so it sits at {@code at}.
     * Comparing hitbox to hitbox rather than centre to centre is what makes the reach check agree
     * with what the server will actually accept.
     */
    private static double boxedDistanceSqr(Entity entity, Vec3 at, Vec3 from) {
        double border = entity.getCollisionBorderSize();
        AxisAlignedBB box = entity.getEntityBoundingBox()
                .expand(border, border, border)
                .offset(at.xCoord - entity.posX, at.yCoord - entity.posY, at.zCoord - entity.posZ);

        double dx = Math.max(Math.max(box.minX - from.xCoord, 0.0), from.xCoord - box.maxX);
        double dy = Math.max(Math.max(box.minY - from.yCoord, 0.0), from.yCoord - box.maxY);
        double dz = Math.max(Math.max(box.minZ - from.zCoord, 0.0), from.zCoord - box.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    // ------------------------------------------------------------------ LagRange interlock

    /** LagRange holds packets of its own; the two must never be stacked. */
    private void setLagRangeEnabled(boolean enabled) {
        if (!this.interruptLagRange.getValue() || Myau.moduleManager == null) {
            if (enabled) {
                this.lagRangeInterrupted = false;
            }
            return;
        }
        Module module = Myau.moduleManager.modules.get(LagRange.class);
        if (!(module instanceof LagRange)) {
            return;
        }
        if (enabled) {
            if (this.lagRangeInterrupted) {
                this.lagRangeInterrupted = false;
                module.setEnabled(true);
            }
        } else if (!this.lagRangeInterrupted && module.isEnabled()) {
            this.lagRangeInterrupted = true;
            module.setEnabled(false);
        }
    }

    // ------------------------------------------------------------------ render

    @EventTarget(Priority.HIGH)
    public void onRender3D(Render3DEvent event) {
        long now = System.nanoTime();
        float deltaTicks = this.lastRenderNanos == 0L ? 0.0F
                : Math.min(4.0F, Math.max(0.0F, (now - this.lastRenderNanos) / 1.0e9F * 20.0F));
        this.lastRenderNanos = now;

        if (this.visualize.getValue() == VISUALIZE_OFF || mc.theWorld == null || mc.thePlayer == null) {
            this.shownFor = null;
            return;
        }

        boolean lagging = this.target != null && Backtracker.isLagging();
        EntityLivingBase entity = lagging ? this.target : this.shownFor;
        if (entity == null || entity.isDead || entity.worldObj != mc.theWorld) {
            this.shownFor = null;
            return;
        }

        float partialTicks = Math.max(0.0F, Math.min(1.0F, event.getPartialTicks()));
        Vec3 displayed = new Vec3(
                entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks,
                entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks,
                entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks);
        boolean toReal = lagging && entity == this.target && this.tracked.isTracking();
        Vec3 goal = toReal ? this.tracked.get() : displayed;

        if (entity != this.shownFor || this.shown == null) {
            this.shownFor = entity;
            this.shown = displayed;
        }
        // Frame-rate independent half-life, so the slide looks the same at any FPS.
        double factor = 1.0 - Math.pow(0.5, deltaTicks / HALF_LIFE_TICKS);
        this.shown = new Vec3(
                this.shown.xCoord + (goal.xCoord - this.shown.xCoord) * factor,
                this.shown.yCoord + (goal.yCoord - this.shown.yCoord) * factor,
                this.shown.zCoord + (goal.zCoord - this.shown.zCoord) * factor);

        if (!toReal && this.shown.squareDistanceTo(displayed) < SETTLED_DISTANCE_SQR) {
            // Caught up with the live position: nothing left worth drawing.
            this.shownFor = null;
            return;
        }

        IAccessorRenderManager renderManager = (IAccessorRenderManager) mc.getRenderManager();
        AxisAlignedBB box = entity.getEntityBoundingBox().offset(
                this.shown.xCoord - entity.posX - renderManager.getRenderPosX(),
                this.shown.yCoord - entity.posY - renderManager.getRenderPosY(),
                this.shown.zCoord - entity.posZ - renderManager.getRenderPosZ());

        Color color = entity instanceof EntityPlayer
                ? TeamUtil.getTeamColor((EntityPlayer) entity, 1.0F)
                : new Color(255, 60, 60);

        RenderUtil.enableRenderState();
        if (this.visualize.getValue() == VISUALIZE_BOTH) {
            RenderUtil.drawFilledBox(box, color.getRed(), color.getGreen(), color.getBlue());
        }
        RenderUtil.drawBoundingBox(box, color.getRed(), color.getGreen(), color.getBlue(), 255, 2.0F);
        RenderUtil.disableRenderState();
        RenderUtil.resetColor();
    }

    // ------------------------------------------------------------------ real-position access

    private static BackTrack instance() {
        if (Myau.moduleManager == null) {
            return null;
        }
        Module module = Myau.moduleManager.modules.get(BackTrack.class);
        return module instanceof BackTrack ? (BackTrack) module : null;
    }

    /**
     * Runs {@code action} with {@code entity} temporarily standing where the held packets say it
     * really is, so a caller measuring distance sees the real target rather than the ghost being
     * drawn. Falls straight through whenever nothing is being held for that entity.
     * <p>
     * The whole motion is shifted - position, previous position and hitbox all move by the same
     * offset - so anything deriving a velocity from those fields still sees the right one, and the
     * restore runs in a finally so a throwing action cannot leave the entity displaced.
     */
    public static boolean runWithNearestTrackedDistance(Entity entity, Supplier<Boolean> action) {
        BackTrack backtrack = instance();
        if (backtrack == null || !backtrack.isEnabled() || entity == null
                || backtrack.target != entity
                || !backtrack.tracked.isTracking()
                || !Backtracker.isLagging()) {
            return action.get();
        }

        Vec3 real = backtrack.tracked.get();
        double offsetX = real.xCoord - entity.posX;
        double offsetY = real.yCoord - entity.posY;
        double offsetZ = real.zCoord - entity.posZ;
        AxisAlignedBB box = entity.getEntityBoundingBox();
        double posX = entity.posX;
        double posY = entity.posY;
        double posZ = entity.posZ;
        double prevX = entity.prevPosX;
        double prevY = entity.prevPosY;
        double prevZ = entity.prevPosZ;
        double lastX = entity.lastTickPosX;
        double lastY = entity.lastTickPosY;
        double lastZ = entity.lastTickPosZ;

        try {
            entity.posX += offsetX;
            entity.posY += offsetY;
            entity.posZ += offsetZ;
            entity.prevPosX += offsetX;
            entity.prevPosY += offsetY;
            entity.prevPosZ += offsetZ;
            entity.lastTickPosX += offsetX;
            entity.lastTickPosY += offsetY;
            entity.lastTickPosZ += offsetZ;
            entity.setEntityBoundingBox(box.offset(offsetX, offsetY, offsetZ));
            return action.get();
        } finally {
            entity.posX = posX;
            entity.posY = posY;
            entity.posZ = posZ;
            entity.prevPosX = prevX;
            entity.prevPosY = prevY;
            entity.prevPosZ = prevZ;
            entity.lastTickPosX = lastX;
            entity.lastTickPosY = lastY;
            entity.lastTickPosZ = lastZ;
            entity.setEntityBoundingBox(box);
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.frozen() ? "Freeze" : this.delay.getValue() + "ms"};
    }
}
