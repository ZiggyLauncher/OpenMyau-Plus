package myau.module.modules;

import myau.Myau;
import myau.mixin.IAccessorMinecraft;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ItemListProperty;
import myau.property.properties.ModeProperty;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.passive.EntityWaterMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.item.ItemTool;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * A port of Vape's AimAssist in its default "Simple" mode.
 * <p>
 * It works in mouse counts rather than degrees. A worker thread runs the aim about once a
 * millisecond (Vape's {@code Thread.sleep(1)} loop): it picks a target, predicts where it is
 * heading, and builds up horizontal (and optionally vertical) "force" from how far off you are,
 * with a random component, a boost when close, and optional extra speed while strafing away. That
 * force is double-buffered - collected for ten runs, then applied - and turned into fractional
 * mouse counts. Every frame the whole counts are taken out and moved through the vanilla mouse
 * path ({@code setAngles}, which is exactly Vape's {@code applyTrackedMouseDelta}), scaled by your
 * sensitivity the way the game scales a real mouse, and the fractions carry over. A slow random
 * drift is mixed in so it never tracks perfectly.
 * <p>
 * Differences from the original, all deliberate:
 * <ul>
 *     <li>Only the Simple mode is ported; Vape's Adaptive mode is not, so there is no mode
 *     setting.</li>
 *     <li>The target filter's "Neutral" option is left out: the original never reads it when
 *     choosing a target.</li>
 *     <li>The mouse-count totals are shared between the worker and the render thread under a lock,
 *     where the original races; the counts are the same, just none go missing.</li>
 *     <li>It still stands down while KillAura has a target or BlockIn/Clutch are aiming, as the
 *     module it replaces did - two aims pulling at once is a bug, not a setting.</li>
 * </ul>
 */
public class AimAssist extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final int AREA_CENTER = 0;
    private static final int AREA_CLOSEST = 1;
    private static final int TARGET_YAW = 0;
    private static final int TARGET_DISTANCE = 1;
    private static final int TARGET_ARMOR = 2;
    private static final int TARGET_THREAT = 3;
    private static final int TARGET_HEALTH = 4;

    // ------------------------------------------------------------------ target filter
    public final BooleanProperty players = new BooleanProperty("players", true);
    public final BooleanProperty mobs = new BooleanProperty("mobs", false);
    public final BooleanProperty peaceful = new BooleanProperty("peaceful", false);
    public final BooleanProperty ignoreNaked = new BooleanProperty("ignore-naked", false);
    public final BooleanProperty ignoreInvisible = new BooleanProperty("ignore-invisible", false);
    public final BooleanProperty ignoreBehindWalls = new BooleanProperty("ignore-behind-walls", false);

    // ------------------------------------------------------------------ settings (Vape's order)
    public final BooleanProperty requireMouseDown = new BooleanProperty("require-mouse-down", true);
    public final BooleanProperty strafeIncrease = new BooleanProperty("strafe-increase", false);
    public final BooleanProperty checkBlockBreak = new BooleanProperty("check-block-break", false);
    public final BooleanProperty breakBlocksWhitelist = new BooleanProperty("break-blocks-whitelist", false,
            this.checkBlockBreak::getValue);
    public final ItemListProperty blockBreakItems = new ItemListProperty("items", "pickaxe,shovel",
            () -> this.checkBlockBreak.getValue() && this.breakBlocksWhitelist.getValue());
    public final BooleanProperty aimVertically = new BooleanProperty("aim-vertically", false);
    public final FloatProperty verticalSpeed = new FloatProperty("vertical-speed", 5.0F, 1.0F, 10.0F,
            this.aimVertically::getValue);
    public final FloatProperty horizontalSpeed = new FloatProperty("horizontal-speed", 5.0F, 1.0F, 10.0F);
    public final IntProperty maxAngle = new IntProperty("max-angle", 180, 1, 360);
    public final FloatProperty distance = new FloatProperty("distance", 5.0F, 1.0F, 8.0F);
    public final BooleanProperty limitToItems = new BooleanProperty("limit-to-items", false);
    public final ItemListProperty allowedItems = new ItemListProperty("allowed-items", "sword",
            this.limitToItems::getValue);
    public final ModeProperty targetArea = new ModeProperty("target-area", AREA_CENTER, new String[]{"Center", "Closest"});
    public final ModeProperty targetMode = new ModeProperty("target-mode", TARGET_YAW,
            new String[]{"Yaw", "Distance", "Armor", "Threat", "Health"});

    // ------------------------------------------------------------------ aim state
    private static Thread worker;

    /** Read by the render thread and BackTrack, written by the worker. */
    private volatile EntityLivingBase target;
    private final Object countsLock = new Object();
    /** Fractional mouse counts waiting to be moved, and the drift folded into them (under the lock). */
    private float horizontalMouseAccumulator;
    private float verticalMouseAccumulator;
    private int driftX;
    private int driftY;

    // Worker-thread state only.
    private final Random random = new Random();
    private final Random sharedRandom = new Random();
    private int blockBreakCooldown;
    private int randomOffsetX;
    private int randomOffsetY;
    private double driftTimer;
    private int swapTickCounter;
    private float pitchBoost;
    private float yawBoost;
    private float horizontalVelocity;
    private float horizontalVelocityBuffer;
    private float verticalVelocity;
    private float verticalVelocityBuffer;
    private double targetX;
    private double targetY;
    private double targetZ;
    private double prevTargetX;
    private double prevTargetZ;
    private boolean prevOnLeft;
    private boolean prevAbove;
    private double lastAngleDiff;
    private int sampleCounter;
    private int retargetCounter;

    public AimAssist() {
        super("AimAssist", false, false, "Smoothly aims to closest valid target");
    }

    @Override
    public void onEnabled() {
        startWorker();
    }

    @Override
    public void onDisabled() {
        this.target = null;
        this.resetRotationState();
    }

    public EntityLivingBase getTarget() {
        return this.target;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{String.valueOf(Math.round(this.horizontalSpeed.getValue()))};
    }

    /**
     * The old module could aim silently; Vape's Simple mode always moves the real view, so there is
     * never a separate look direction for the renderer to use.
     */
    public static Vec3 silentLook(Entity entity) {
        return null;
    }

    // ------------------------------------------------------------------ worker

    private static synchronized void startWorker() {
        if (worker != null) {
            return;
        }
        worker = new Thread(AimAssist::workerLoop, "Myau AimAssist");
        worker.setDaemon(true);
        worker.start();
    }

    /** {@code AimAssistRotationWorkerThread}: runs the aim about once a millisecond while on. */
    private static void workerLoop() {
        while (true) {
            try {
                Thread.sleep(1L);
                AimAssist module = instance();
                if (module != null && module.isEnabled()) {
                    module.tick();
                }
            } catch (InterruptedException interrupted) {
                return;
            } catch (Throwable ignored) {
                // Entity lists change under the worker; a failed run is simply skipped, as in Vape.
            }
        }
    }

    private static AimAssist instance() {
        if (Myau.moduleManager == null) {
            return null;
        }
        Module module = Myau.moduleManager.modules.get(AimAssist.class);
        return module instanceof AimAssist ? (AimAssist) module : null;
    }

    private static boolean attackDown() {
        return mc.gameSettings.keyBindAttack.isKeyDown();
    }

    private void tick() {
        EntityPlayerSP player = mc.thePlayer;
        if (mc.theWorld == null || player == null) {
            return;
        }
        if (!this.canAim()) {
            this.resetRotationState();
            return;
        }
        boolean requireMouseDown = this.requireMouseDown.getValue();
        if (requireMouseDown && !attackDown()) {
            this.target = null;
            this.resetRotationState();
            return;
        }
        EntityLivingBase current = this.target;
        if (current != null && (deadOrDying(current) || player.getDistanceToEntity(current) > this.distance.getValue())) {
            this.resetRotationState();
            this.target = null;
            current = null;
        }
        if (requireMouseDown && attackDown() && current == null || !requireMouseDown) {
            EntityLivingBase candidate = this.findBestTarget();
            if (!requireMouseDown) {
                ++this.retargetCounter;
                if (this.retargetCounter > 700 || current == null || !this.isValidTarget(current)) {
                    this.target = candidate;
                    this.retargetCounter = 0;
                }
            } else {
                this.target = candidate;
            }
        }
        if (mc.theWorld == null) {
            return;
        }
        if (this.target != null && mc.currentScreen == null) {
            this.updateVelocityBuffers();
            this.applyRotation();
        } else {
            this.resetRotationState();
        }
    }

    /**
     * Vape's {@code canAim}, plus Myau+'s stand-down: KillAura wins while it holds a target, and
     * BlockIn and Clutch while they aim - their placements are validated against the rotation.
     */
    private boolean canAim() {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.playerController == null) {
            return false;
        }
        if (killAuraBusy() || BlockIn.isActive() || Clutch.isAiming()) {
            return false;
        }
        boolean checkCurrentBlock = this.checkBlockBreak.getValue();
        if (checkCurrentBlock && this.breakBlocksWhitelist.getValue()) {
            checkCurrentBlock = this.blockBreakItems.matches(player.getHeldItem());
        }
        if (checkCurrentBlock) {
            MovingObjectPosition mouseOver = mc.objectMouseOver;
            boolean aimingAtBlock = mouseOver != null
                    && mouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK;
            if (aimingAtBlock) {
                this.blockBreakCooldown = 250;
                return false;
            }
            if (this.blockBreakCooldown > 0) {
                --this.blockBreakCooldown;
            }
            if (this.blockBreakCooldown > 0) {
                return false;
            }
        }
        return this.hasRequiredItem();
    }

    private static boolean killAuraBusy() {
        if (Myau.moduleManager == null) {
            return false;
        }
        Module module = Myau.moduleManager.modules.get(KillAura.class);
        return module instanceof KillAura && module.isEnabled() && ((KillAura) module).getTarget() != null;
    }

    private boolean hasRequiredItem() {
        if (!this.limitToItems.getValue()) {
            return true;
        }
        return this.allowedItems.matches(mc.thePlayer.getHeldItem());
    }

    // ------------------------------------------------------------------ targeting

    private EntityLivingBase findBestTarget() {
        if (mc.theWorld == null) {
            return null;
        }
        List<EntityLivingBase> targets = new ArrayList<EntityLivingBase>();
        for (Object object : new ArrayList<Object>(mc.theWorld.loadedEntityList)) {
            if (object instanceof EntityLivingBase && this.isValidTarget((EntityLivingBase) object)) {
                targets.add((EntityLivingBase) object);
            }
        }
        final EntityPlayerSP player = mc.thePlayer;
        switch (this.targetMode.getValue()) {
            case TARGET_YAW:
                targets.sort(Comparator.comparingInt(entity -> angleToEntity(player, entity)));
                break;
            case TARGET_DISTANCE:
                targets.sort(distanceOrder(player));
                break;
            case TARGET_THREAT:
                targets.sort(playersBy(player, AimAssist::weaponThreat));
                break;
            case TARGET_ARMOR:
                targets.sort(playersBy(player, AimAssist::equipmentValue));
                break;
            case TARGET_HEALTH:
                targets.sort((first, second) -> Float.compare(first.getHealth(), second.getHealth()));
                break;
            default:
                break;
        }
        return targets.isEmpty() ? null : targets.get(0);
    }

    private static Comparator<EntityLivingBase> distanceOrder(EntityPlayerSP player) {
        return (first, second) -> Float.compare(player.getDistanceToEntity(first), player.getDistanceToEntity(second));
    }

    /** Armor and Threat compare players by score and anything else by distance, as Vape's comparators do. */
    private static Comparator<EntityLivingBase> playersBy(EntityPlayerSP player, java.util.function.ToDoubleFunction<EntityPlayer> score) {
        return (first, second) -> {
            if (first instanceof EntityPlayer && second instanceof EntityPlayer) {
                return Double.compare(score.applyAsDouble((EntityPlayer) first), score.applyAsDouble((EntityPlayer) second));
            }
            return Float.compare(player.getDistanceToEntity(first), player.getDistanceToEntity(second));
        };
    }

    private boolean isValidTarget(EntityLivingBase target) {
        EntityPlayerSP player = mc.thePlayer;
        if (target == null || player == null || target == player) {
            return false;
        }
        if (target.getHealth() <= 0.0F || target.isDead) {
            return false;
        }
        // Vape compares against the whole-number part of the distance setting here.
        if (player.getDistanceToEntity(target) >= (float) (int) this.distance.getValue().floatValue()) {
            return false;
        }
        if (angleToEntity(player, target) > this.maxAngle.getValue() / 2) {
            return false;
        }
        if (target instanceof EntityPlayer && TeamUtil.isFriend((EntityPlayer) target)) {
            return false;
        }
        if (target == player.ridingEntity) {
            return false;
        }
        return this.passesItemFilter(target);
    }

    private boolean passesItemFilter(EntityLivingBase target) {
        if (this.limitToItems.getValue() && !this.allowedItems.matches(mc.thePlayer.getHeldItem())) {
            return false;
        }
        return this.passesTargetFilter(target);
    }

    /** Vape's {@code EntityTargetFilterValue.isValidTarget}. */
    private boolean passesTargetFilter(EntityLivingBase entity) {
        EntityPlayerSP player = mc.thePlayer;
        if (entity == player || entity.getHealth() <= 0.0F) {
            return false;
        }
        if (this.ignoreInvisible.getValue() && fullyInvisible(entity)) {
            return false;
        }
        if (this.ignoreBehindWalls.getValue() && !player.canEntityBeSeen(entity)) {
            return false;
        }
        boolean isPlayer = entity instanceof EntityPlayer;
        if (isPlayer && TeamUtil.isFriend((EntityPlayer) entity)) {
            return false;
        }
        boolean isMob = entity instanceof IMob;
        boolean isPeaceful = entity instanceof EntityAnimal || entity instanceof EntityWaterMob
                || entity instanceof EntityVillager;
        if (isPlayer) {
            EntityPlayer target = (EntityPlayer) entity;
            if (!this.players.getValue()) {
                return false;
            }
            if (TeamUtil.isTarget(target)) {
                return true;
            }
            if (this.ignoreNaked.getValue() && naked(target)) {
                return false;
            }
            if (teammate(target)) {
                return false;
            }
            return !AntiBot.isBot(target);
        }
        if (isMob && !this.mobs.getValue()) {
            return false;
        }
        if (isPeaceful && !this.peaceful.getValue()) {
            return false;
        }
        return isMob || isPeaceful || this.peaceful.getValue();
    }

    /** Vape's teammate check only applies while AntiBot is on. */
    private static boolean teammate(EntityPlayer target) {
        if (Myau.moduleManager == null) {
            return false;
        }
        Module module = Myau.moduleManager.modules.get(AntiBot.class);
        return module != null && module.isEnabled() && TeamUtil.isSameTeam(target);
    }

    /** Invisible and nothing held or worn that would give them away. */
    private static boolean fullyInvisible(EntityLivingBase entity) {
        if (!entity.isInvisible() || entity.getHeldItem() != null) {
            return false;
        }
        if (entity instanceof EntityPlayer) {
            for (ItemStack armor : ((EntityPlayer) entity).inventory.armorInventory) {
                if (armor != null) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Nothing in hand and no armor on. */
    private static boolean naked(EntityPlayer player) {
        if (player.getHeldItem() != null) {
            return false;
        }
        for (ItemStack armor : player.inventory.armorInventory) {
            if (armor != null) {
                return false;
            }
        }
        return true;
    }

    private static boolean deadOrDying(EntityLivingBase entity) {
        return entity.isDead || entity.getHealth() <= 0.0F;
    }

    // ------------------------------------------------------------------ item scores (Threat / Armor)

    /** {@code EntityArmorValueComparator}: the held weapon, boosted under Strength. */
    private static double weaponThreat(EntityPlayer player) {
        ItemStack held = player.getHeldItem();
        if (held == null) {
            return 0.0;
        }
        float score = weaponScore(held);
        PotionEffect strength = player.getActivePotionEffect(Potion.damageBoost);
        if (strength != null && strength.getDuration() > 0) {
            score = (float) (score * (1.375 * (strength.getAmplifier() + 1)));
        }
        return score;
    }

    /** {@code ItemStackScoreUtil}'s weapon score: sharpness, base damage and fire aspect. */
    private static float weaponScore(ItemStack stack) {
        if (!(stack.getItem() instanceof ItemSword) && !(stack.getItem() instanceof ItemTool)) {
            return 0.0F;
        }
        return EnchantmentHelper.getEnchantmentLevel(Enchantment.sharpness.effectId, stack) * 1.25F
                + attackDamage(stack)
                + EnchantmentHelper.getEnchantmentLevel(Enchantment.fireAspect.effectId, stack) * 0.01F;
    }

    private static float attackDamage(ItemStack stack) {
        float damage = 0.0F;
        Collection<AttributeModifier> modifiers = stack.getAttributeModifiers()
                .get(SharedMonsterAttributes.attackDamage.getAttributeUnlocalizedName());
        for (AttributeModifier modifier : modifiers) {
            damage += (float) modifier.getAmount();
        }
        return damage;
    }

    /** {@code EntityEquipmentValueComparator}: every worn armor piece's protection score. */
    private static double equipmentValue(EntityPlayer player) {
        double value = 0.0;
        for (ItemStack armor : player.inventory.armorInventory) {
            if (armor == null || !(armor.getItem() instanceof ItemArmor)) {
                continue;
            }
            value += ((ItemArmor) armor.getItem()).damageReduceAmount
                    + EnchantmentHelper.getEnchantmentLevel(Enchantment.protection.effectId, armor)
                    + EnchantmentHelper.getEnchantmentLevel(Enchantment.featherFalling.effectId, armor) * 0.1
                    + EnchantmentHelper.getEnchantmentLevel(Enchantment.fireProtection.effectId, armor) * 0.1
                    + EnchantmentHelper.getEnchantmentLevel(Enchantment.blastProtection.effectId, armor) * 0.1;
        }
        return value;
    }

    // ------------------------------------------------------------------ Vape's rotation math

    private static float partialTicks() {
        return ((IAccessorMinecraft) mc).getTimer().renderPartialTicks;
    }

    private static double lerp(double previous, double current, float partialTicks) {
        return previous + (current - previous) * partialTicks;
    }

    private static double wrapTo180(double angle) {
        angle %= 360.0;
        if (angle >= 180.0) {
            angle -= 360.0;
        }
        if (angle < -180.0) {
            angle += 360.0;
        }
        return angle;
    }

    /** The yaw, in degrees, that faces from (x, z) toward (toX, toZ); Vape's quadrant form. */
    private static double yawToward(double x, double z, double toX, double toZ) {
        double yaw = 0.0;
        double dx = toX - x;
        double dz = toZ - z;
        if (dz > 0.0 && dx > 0.0) {
            yaw = Math.toDegrees(-Math.atan(dx / dz));
        } else if (dz > 0.0 && dx < 0.0) {
            yaw = Math.toDegrees(-Math.atan(dx / dz));
        } else if (dz < 0.0 && dx > 0.0) {
            yaw = -90.0 + Math.toDegrees(Math.atan(dz / dx));
        } else if (dz < 0.0 && dx < 0.0) {
            yaw = 90.0 + Math.toDegrees(Math.atan(dz / dx));
        }
        return yaw;
    }

    /** {@code RotationUtil.a(Entity, Entity)}: whole-degree yaw offset to an entity, 0..180. */
    private static int angleToEntity(Entity from, Entity to) {
        double yaw = yawToward(from.posX, from.posZ, to.posX, to.posZ);
        int angle = (int) (Math.abs(yaw - from.rotationYaw) % 360.0);
        return angle > 180 ? 360 - angle : angle;
    }

    /** {@code RotationUtil.C(x, z, yaw, toX, toZ)}: yaw offset to a point, 0..180. */
    private static double horizontalAngle(double x, double z, float yaw, double toX, double toZ) {
        double offset = Math.abs(yawToward(x, z, toX, toZ) - yaw) % 360.0;
        return offset > 180.0 ? 360.0 - offset : offset;
    }

    /** {@code RotationUtil.p}: whether the point is to the left of the view. */
    private static boolean onLeft(double x, double z, float yaw, double toX, double toZ) {
        int offset = (int) wrapTo180((yawToward(x, z, toX, toZ) - yaw) % 360.0);
        return offset < 0;
    }

    /**
     * {@code RotationUtil.H}: whole-degree pitch offset to a point, measured - as in Vape - from
     * the player's feet rather than the eyes.
     */
    private static int verticalAngle(Entity from, double x, double y, double z) {
        double dx = x - from.posX;
        double dy = y - from.posY;
        double dz = z - from.posZ;
        double flat = Math.sqrt(dx * dx + dz * dz);
        float pitch = (float) (-(Math.atan2(dy, flat) * 180.0 / Math.PI));
        return (int) (float) wrapTo180(from.rotationPitch - pitch);
    }

    private static double clamp(double value, double min, double max) {
        if (value < max && value > min) {
            return value;
        }
        if (value > max) {
            return max;
        }
        if (value < min) {
            return min;
        }
        return value;
    }

    /** {@code AimAssistRotationSubModule.computeTargetOffset}: the hitbox point nearest you. */
    private void computeTargetOffset(EntityPlayerSP player, EntityLivingBase target, float partialTicks) {
        AxisAlignedBB box = target.getEntityBoundingBox();
        // Vape also clamps the eye height into the box here, but only uses X and Z.
        double closestX = clamp(player.posX, box.minX, box.maxX);
        double closestZ = clamp(player.posZ, box.minZ, box.maxZ);
        if (closestX == player.posX) {
            closestX = player.posX + 0.01;
        }
        if (closestZ == player.posZ) {
            closestZ = player.posZ + 0.01;
        }
        double motionX = target.posX - target.prevPosX;
        double motionZ = target.posZ - target.prevPosZ;
        double previousX = closestX - motionX;
        double previousZ = closestZ - motionZ;
        this.targetX = previousX + (closestX - previousX) * partialTicks;
        this.targetZ = previousZ + (closestZ - previousZ) * partialTicks;
    }

    private void updateDrift() {
        this.driftTimer += 1.0;
        if (this.driftTimer >= (double) (250 + this.random.nextInt(50))) {
            this.driftTimer = this.random.nextInt(-50 - -100) + -100;
            this.randomOffsetX = this.random.nextInt(2 - -1) + -1;
            this.randomOffsetY = this.random.nextInt(2 - -1) + -1;
        }
        int horizontalStep = this.randomOffsetX;
        int verticalStep = this.randomOffsetY;
        // Vape draws these two and discards them; kept so the random sequence is the same.
        this.random.nextInt(10);
        this.random.nextInt(10);
        if (this.random.nextInt(10) < 2) {
            horizontalStep = 0;
        }
        if (this.random.nextInt(10) < 2) {
            verticalStep = 0;
        }
        if (this.driftTimer < 0.0) {
            horizontalStep = 0;
            verticalStep = 0;
        }
        synchronized (this.countsLock) {
            if (this.random.nextInt(20) == 1) {
                this.driftX += horizontalStep;
                this.driftY += verticalStep;
            }
            if (this.horizontalMouseAccumulator > 0.0F && this.driftX < 0
                    || this.horizontalMouseAccumulator < 0.0F && this.driftX > 0) {
                this.driftX = 0;
            }
        }
    }

    private void queueVerticalAdjustment(float adjustment, float angleDifference) {
        synchronized (this.countsLock) {
            if (adjustment != 0.0F) {
                adjustment *= 5.0F;
                float speed = this.verticalSpeed.getValue();
                float absoluteAngleDifference = Math.abs(angleDifference);
                if (absoluteAngleDifference <= 10.0F) {
                    this.yawBoost = speed;
                }
                if (this.yawBoost > 0.0F) {
                    speed -= this.yawBoost / 3.0F;
                    this.yawBoost -= absoluteAngleDifference / 200.0F;
                }
                this.verticalMouseAccumulator += speed * adjustment;
            } else {
                this.verticalMouseAccumulator = 0.0F;
            }
        }
    }

    private void queueHorizontalAdjustment(float adjustment, EntityPlayerSP player, EntityLivingBase target) {
        synchronized (this.countsLock) {
            if (adjustment != 0.0F) {
                adjustment *= 5.0F;
                float speed = this.horizontalSpeed.getValue();
                float angleDifference = angleToEntity(player, target);
                if (angleDifference <= 10.0F) {
                    this.pitchBoost = speed;
                }
                if (this.pitchBoost > 0.0F) {
                    speed -= this.pitchBoost / 3.0F;
                    this.pitchBoost -= angleDifference / 200.0F;
                }
                this.horizontalMouseAccumulator += speed * adjustment;
            } else {
                this.horizontalMouseAccumulator = 0.0F;
            }
        }
    }

    private void resetRotationState() {
        synchronized (this.countsLock) {
            this.horizontalMouseAccumulator = 0.0F;
            this.verticalMouseAccumulator = 0.0F;
            this.driftX = 0;
            this.driftY = 0;
        }
        this.randomOffsetX = 0;
        this.randomOffsetY = 0;
    }

    private void updateVelocityBuffers() {
        ++this.swapTickCounter;
        if (this.swapTickCounter > 10) {
            this.verticalVelocityBuffer = this.verticalVelocity;
            this.horizontalVelocity = this.horizontalVelocityBuffer;
            this.horizontalVelocityBuffer = 0.0F;
            this.verticalVelocity = 0.0F;
            this.swapTickCounter = 0;
        }
    }

    /** {@code AimAssistRotationSubModule.applyRotation}. */
    private void applyRotation() {
        EntityPlayerSP player = mc.thePlayer;
        EntityLivingBase target = this.target;
        if (player == null || target == null) {
            return;
        }
        this.updateDrift();
        float partialTicks = partialTicks();
        this.targetX = lerp(target.lastTickPosX, target.posX, partialTicks);
        this.targetY = lerp(target.lastTickPosY, target.posY, partialTicks);
        this.targetZ = lerp(target.lastTickPosZ, target.posZ, partialTicks);
        if (this.targetArea.getValue() == AREA_CLOSEST) {
            this.computeTargetOffset(player, target, partialTicks);
        }
        double targetMotionX = this.targetX - this.prevTargetX;
        double targetMotionZ = this.targetZ - this.prevTargetZ;
        this.prevTargetX = this.targetX;
        this.prevTargetZ = this.targetZ;
        float viewYaw = player.rotationYaw;
        double predictionFactor = 1.7;
        double predictedTargetX = this.targetX + targetMotionX * predictionFactor;
        double predictedTargetZ = this.targetZ + targetMotionZ * predictionFactor;
        double playerX = lerp(player.lastTickPosX, player.posX, partialTicks);
        double playerZ = lerp(player.lastTickPosZ, player.posZ, partialTicks);
        double horizontalAngleDifference = horizontalAngle(playerX, playerZ, viewYaw, predictedTargetX, predictedTargetZ);
        boolean targetOnLeft = onLeft(playerX, playerZ, viewYaw, predictedTargetX, predictedTargetZ);
        int verticalAngleDifference = verticalAngle(player, this.targetX, this.targetY, this.targetZ);
        boolean targetAbove = verticalAngleDifference < 0;
        int verticalDeadZoneDistance = Math.abs(verticalAngleDifference) - 10;

        float horizontalForce = 1.0F;
        float verticalForce = 1.0F;
        horizontalForce = (float) ((double) horizontalForce + (0.0 + 2.0 * this.sharedRandom.nextDouble()));
        horizontalForce = (float) ((double) horizontalForce + horizontalAngleDifference / 50.0);
        verticalForce = (float) ((double) verticalForce + (0.0 + 2.0 * this.sharedRandom.nextDouble()));
        verticalForce += (float) Math.abs(verticalDeadZoneDistance) / 50.0F;
        if (Math.abs(horizontalAngleDifference - this.lastAngleDiff) > 6.0) {
            horizontalForce = (float) ((double) horizontalForce + horizontalAngleDifference / 35.0);
        }
        float distanceToTarget = player.getDistanceToEntity(target);
        double proximityBoost = Math.max(0.0, (9.0F - distanceToTarget) / 2.5F - 2.0F);
        horizontalForce = (float) ((double) horizontalForce + proximityBoost);
        float strafeInput = player.movementInput.moveStrafe;
        boolean strafingAway = targetOnLeft ? strafeInput < 0.0F : strafeInput > 0.0F;
        if (this.strafeIncrease.getValue() && strafingAway) {
            horizontalForce = (float) ((double) horizontalForce * 1.6);
        }
        if (distanceToTarget < 0.5F) {
            horizontalForce /= 5.0F;
        }
        float horizontalAcceleration = horizontalForce / 90.0F * (targetOnLeft ? -1.0F : 1.0F);
        float verticalAcceleration = verticalForce / 90.0F * (targetAbove ? 1.0F : -1.0F);
        if (horizontalAngleDifference < 5.0) {
            horizontalAcceleration = 0.0F;
            this.horizontalVelocity *= 0.7F;
            boolean strafingToward = targetOnLeft ? strafeInput > 0.0F : strafeInput < 0.0F;
            if (strafingToward) {
                this.horizontalVelocity *= 0.5F;
            }
        }
        if (targetOnLeft != this.prevOnLeft) {
            this.horizontalVelocity = -this.horizontalVelocity;
            this.horizontalVelocityBuffer = -this.horizontalVelocityBuffer;
            synchronized (this.countsLock) {
                this.horizontalMouseAccumulator = 0.0F;
            }
        }
        if (targetAbove != this.prevAbove) {
            this.verticalVelocityBuffer = -this.verticalVelocityBuffer;
            this.verticalVelocity = -this.verticalVelocity;
            synchronized (this.countsLock) {
                this.verticalMouseAccumulator = 0.0F;
            }
        }
        if (verticalDeadZoneDistance < 5) {
            verticalAcceleration = 0.0F;
            this.verticalVelocityBuffer *= 0.7F;
        }
        this.horizontalVelocityBuffer += horizontalAcceleration;
        this.verticalVelocity += verticalAcceleration;
        float smoothedHorizontalVelocity = this.horizontalVelocity;
        float smoothedVerticalVelocity = this.verticalVelocityBuffer;
        if (Math.abs(smoothedHorizontalVelocity) > 10.0F) {
            this.horizontalVelocityBuffer = 0.0F;
            this.horizontalVelocity = 0.0F;
            return;
        }
        float horizontalAdjustment = smoothedHorizontalVelocity * 0.15F;
        if (horizontalAngleDifference <= 9.0) {
            horizontalAdjustment = (float) ((double) horizontalAdjustment / (10.0 - horizontalAngleDifference));
        }
        if (Float.isNaN(horizontalAdjustment)) {
            this.horizontalVelocityBuffer = 0.0F;
            this.horizontalVelocity = 0.0F;
            return;
        }
        this.queueHorizontalAdjustment(horizontalAdjustment, player, target);
        if (this.aimVertically.getValue()) {
            float verticalAdjustment = (float) ((double) smoothedVerticalVelocity * 0.15);
            if (Float.isNaN(verticalAdjustment)) {
                this.verticalVelocity = 0.0F;
                this.verticalVelocityBuffer = 0.0F;
                return;
            }
            this.queueVerticalAdjustment(verticalAdjustment, verticalAngleDifference);
        }
        this.prevAbove = targetAbove;
        this.prevOnLeft = targetOnLeft;
        ++this.sampleCounter;
        if (this.sampleCounter > 10) {
            this.lastAngleDiff = horizontalAngleDifference;
            this.sampleCounter = 0;
        }
    }

    // ------------------------------------------------------------------ per frame

    /** Called once a frame after the mouse is read (MixinEntityRenderer). */
    public static void turn(float partialTicks) {
        AimAssist module = instance();
        if (module != null && module.isEnabled()) {
            module.moveMouse();
        }
    }

    /**
     * {@code onPreRenderTick}: takes the whole mouse counts out of the totals and moves the view
     * by them through the vanilla mouse path, scaled by sensitivity as the game scales a mouse.
     */
    private void moveMouse() {
        EntityPlayerSP player = mc.thePlayer;
        if (mc.theWorld == null || player == null || this.target == null) {
            return;
        }
        float horizontalDelta;
        float verticalDelta;
        synchronized (this.countsLock) {
            this.horizontalMouseAccumulator += (float) this.driftX;
            this.verticalMouseAccumulator += (float) this.driftY;
            int horizontalMouseSteps = (int) this.horizontalMouseAccumulator;
            int verticalMouseSteps = (int) this.verticalMouseAccumulator;
            float remainingHorizontal = this.horizontalMouseAccumulator - (float) horizontalMouseSteps;
            float remainingVertical = this.verticalMouseAccumulator - (float) verticalMouseSteps;
            float sensitivity = mc.gameSettings.mouseSensitivity;
            float sensitivityBase = sensitivity * 0.6F + 0.2F;
            float sensitivityScale = sensitivityBase * sensitivityBase * sensitivityBase * 8.0F;
            horizontalDelta = (float) horizontalMouseSteps * sensitivityScale;
            verticalDelta = (float) verticalMouseSteps * sensitivityScale;
            this.horizontalMouseAccumulator = remainingHorizontal;
            this.verticalMouseAccumulator = remainingVertical;
            this.driftX = 0;
            this.driftY = 0;
        }
        if (horizontalDelta != 0.0F || verticalDelta != 0.0F) {
            // setAngles is Vape's applyTrackedMouseDelta: yaw += x * 0.15, pitch -= y * 0.15, clamped.
            player.setAngles(horizontalDelta, -verticalDelta);
        }
    }
}
