package myau.clutch.fall;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.potion.PotionEffect;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

/**
 * The stand-in a rehearsal moves around: a player entity that is never spawned, so the world never
 * sees it, but that collides, steps, slips and swims exactly as a player does.
 * <p>
 * The original makes a fresh copy of the player for every rehearsal. A player entity is costly to
 * build - it comes with an inventory and a crafting grid - and rehearsals run every tick, so these
 * are pooled per world and reset from the source player instead.
 * <p>
 * Not final, only so the mixin accessor casts compile; nothing extends it.
 */
public class SimulatedPlayer extends EntityPlayer {
    private static final GameProfile PROFILE = new GameProfile(UUID.fromString("5c1e4a39-6c7b-4f2e-9a1d-3b8f0d2c7e11"), "Clutch");
    private static final ArrayDeque<SimulatedPlayer> POOL = new ArrayDeque<SimulatedPlayer>();

    private SimulatedPlayer(World world) {
        super(world, PROFILE);
        this.setSilent(true);
    }

    @Override
    public boolean isSpectator() {
        return false;
    }

    /** A stand-in for {@code world}, set up as a copy of {@code source}. Give it back with {@link #release}. */
    public static SimulatedPlayer acquire(World world, EntityPlayer source) {
        SimulatedPlayer player;
        synchronized (POOL) {
            player = POOL.pollFirst();
        }
        if (player == null || player.worldObj != world) {
            player = new SimulatedPlayer(world);
        }
        player.copyFrom(source);
        return player;
    }

    public static void release(SimulatedPlayer player) {
        if (player == null) {
            return;
        }
        synchronized (POOL) {
            if (POOL.size() < 16) {
                POOL.addFirst(player);
            }
        }
    }

    /** Forgets every pooled stand-in, for when the world they were made for is gone. */
    public static void clearPool() {
        synchronized (POOL) {
            POOL.clear();
        }
    }

    /**
     * Everything the movement code reads, taken from {@code source}. The original's
     * {@code PlayerSimulationUtil.s}, plus what its one-off NBT copy carried over: attributes,
     * potion effects, abilities and food.
     */
    public void copyFrom(EntityPlayer source) {
        this.noClip = false;
        this.stepHeight = source.stepHeight;
        this.posX = source.posX;
        this.posY = source.posY;
        this.posZ = source.posZ;
        this.prevPosX = source.prevPosX;
        this.prevPosY = source.prevPosY;
        this.prevPosZ = source.prevPosZ;
        this.lastTickPosX = source.lastTickPosX;
        this.lastTickPosY = source.lastTickPosY;
        this.lastTickPosZ = source.lastTickPosZ;
        this.setEntityBoundingBox(source.getEntityBoundingBox());
        this.motionX = source.motionX;
        this.motionY = source.motionY;
        this.motionZ = source.motionZ;
        this.rotationYaw = source.rotationYaw;
        this.prevRotationYaw = source.prevRotationYaw;
        this.rotationPitch = source.rotationPitch;
        this.prevRotationPitch = source.prevRotationPitch;
        this.rotationYawHead = source.rotationYawHead;
        this.prevRotationYawHead = source.prevRotationYawHead;
        this.onGround = source.onGround;
        this.isCollidedHorizontally = source.isCollidedHorizontally;
        this.isCollidedVertically = source.isCollidedVertically;
        this.isCollided = source.isCollided;
        this.fallDistance = source.fallDistance;
        this.inWater = source.isInWater();
        this.moveForward = source.moveForward;
        this.moveStrafing = source.moveStrafing;
        this.jumpMovementFactor = source.jumpMovementFactor;
        this.capabilities.isFlying = source.capabilities.isFlying;
        this.capabilities.allowFlying = source.capabilities.allowFlying;
        this.getFoodStats().setFoodLevel(source.getFoodStats().getFoodLevel());
        this.setSneaking(source.isSneaking());
        EntityAccess.setJumpTicks(this, EntityAccess.getJumpTicks(source));

        Map<Integer, PotionEffect> potions = EntityAccess.activePotions(this);
        potions.clear();
        potions.putAll(EntityAccess.activePotions(source));

        IAttributeInstance sourceSpeed = source.getEntityAttribute(SharedMonsterAttributes.movementSpeed);
        this.setMovementSpeed(sourceSpeed.getBaseValue(), new ArrayList<AttributeModifier>(sourceSpeed.func_111122_c()));
        this.setSprinting(source.isSprinting());
        this.setAIMoveSpeed(source.getAIMoveSpeed());
    }

    /** Replaces the walk speed attribute outright: base value and every modifier. */
    public void setMovementSpeed(double base, Iterable<AttributeModifier> modifiers) {
        IAttributeInstance speed = this.getEntityAttribute(SharedMonsterAttributes.movementSpeed);
        speed.removeAllModifiers();
        speed.setBaseValue(base);
        for (AttributeModifier modifier : modifiers) {
            if (speed.getModifier(modifier.getID()) == null) {
                speed.applyModifier(modifier);
            }
        }
    }

    /** {@code inWater} is only protected; the rehearsal restores it from snapshots. */
    public void setInWater(boolean inWater) {
        this.inWater = inWater;
    }
}
