package myau.module.modules;

import myau.Myau;
import myau.clutch.fall.ClutchHost;
import myau.clutch.fall.ClutchPath;
import myau.clutch.fall.ClutchRuntime;
import myau.clutch.fall.LocalInput;
import myau.clutch.fall.MovementSnapshot;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LeftClickMouseEvent;
import myau.events.LoadWorldEvent;
import myau.events.MoveInputEvent;
import myau.events.PacketEvent;
import myau.events.RightClickMouseEvent;
import myau.events.TickEvent;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorMinecraft;
import myau.mixin.IAccessorTimer;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ItemListProperty;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * Saves you from falling: when a fall would end in the void, kill you, or (optionally) drop you
 * more than a set number of blocks, it builds a line of blocks under your path and turns to click
 * each one in time. Ported from Vape's Clutch - which its source calls {@code BlockIn}; the
 * module Vape calls Block-In is {@link BlockIn} here.
 * <p>
 * The planning is {@link myau.clutch.fall.ClutchPlanner} and the tick-by-tick behaviour
 * {@link ClutchRuntime}; this class hands them the live game and the settings.
 * <p>
 * Differences from the original, all deliberate:
 * <ul>
 *     <li>The keys that keep you moving while the view turns are worked out from where you meant
 *     to go; the original's version kept forward movement straight but sent strafing and
 *     backward movement the wrong way.</li>
 *     <li>Blocks a click would open instead of build against are recognised by type; the
 *     original's name matching missed most of them in 1.8.9.</li>
 *     <li>Packets are only noted on the network thread and acted on at the next tick.</li>
 * </ul>
 */
public class Clutch extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    /** Above every other module's silent rotation: a clutch comes first. */
    private static final int ROTATION_PRIORITY = 50;
    private static final String DEFAULT_BLACKLIST = "Dispenser, Note Block, Cobweb, TNT, Monster Spawner, "
            + "Enchantment Table, Oak Fence, Jukebox, Melon, Command Block, Anvil, Glass Pane, "
            + "White Stained Glass Pane, Iron Bars, Ice, Packed Ice, Block of Redstone, Gold Ore, Iron Ore, "
            + "Coal Ore, Lapis Lazuli Ore, Redstone Ore, Acacia Wood Stairs, Wooden Pressure Plate, "
            + "Stone Pressure Plate, Beacon, Oak Sapling, Powered Rail, Detector Rail, Shrub, Dead Bush, "
            + "Dandelion, Poppy, Mushroom, Ladder, Rail, Wooden Trapdoor, Lily Pad, Tripwire Hook, Carpet, "
            + "Snow, Trapped Chest, Daylight Sensor, Hopper, Chest, Torch, Lever, Redstone Torch, Button, Cactus";

    public final BooleanProperty onVoid = new BooleanProperty("on-void", true);
    public final BooleanProperty onLethalFall = new BooleanProperty("on-lethal-fall", true);
    public final BooleanProperty onMoreThanXBlocks = new BooleanProperty("on-more-than-x-blocks", false);
    public final IntProperty blocksThreshold = new IntProperty("blocks", 6, 3, 10, this.onMoreThanXBlocks::getValue);
    public final FloatProperty speed = new FloatProperty("speed", 3.5F, 1.0F, 10.0F);
    public final BooleanProperty silentAim = new BooleanProperty("silent-aim", false);
    public final BooleanProperty resetAngle = new BooleanProperty("reset-angle", true, () -> !this.silentAim.getValue());
    public final IntProperty resetAngleDelayMin = new IntProperty("reset-angle-delay-min", 3, 0, 10,
            () -> !this.silentAim.getValue() && this.resetAngle.getValue());
    public final IntProperty resetAngleDelayMax = new IntProperty("reset-angle-delay-max", 6, 0, 10,
            () -> !this.silentAim.getValue() && this.resetAngle.getValue());
    public final BooleanProperty returnToLastSlot = new BooleanProperty("return-to-last-slot", true);
    public final IntProperty returnDelayMin = new IntProperty("return-delay-min", 3, 0, 10, this.returnToLastSlot::getValue);
    public final IntProperty returnDelayMax = new IntProperty("return-delay-max", 6, 0, 10, this.returnToLastSlot::getValue);
    public final IntProperty moveDelayMin = new IntProperty("clutch-move-delay-min", 3, 0, 10);
    public final IntProperty moveDelayMax = new IntProperty("clutch-move-delay-max", 6, 0, 10);
    public final IntProperty failDelay = new IntProperty("fail-delay", 100, 0, 500);
    public final BooleanProperty allowStaircaseUp = new BooleanProperty("allow-staircase-up", true);
    public final BooleanProperty showBlockCount = new BooleanProperty("show-block-count", false);
    public final BooleanProperty limitBlocks = new BooleanProperty("limit-blocks", false);
    public final IntProperty maxBlocks = new IntProperty("max-blocks", 5, 1, 10, this.limitBlocks::getValue);
    public final BooleanProperty blacklist = new BooleanProperty("blacklist", true);
    public final ItemListProperty blacklistBlocks = new ItemListProperty("block-blacklist", DEFAULT_BLACKLIST,
            this.blacklist::getValue);
    public final BooleanProperty heldWhitelist = new BooleanProperty("held-whitelist", false);
    /** Empty means any block. */
    public final ItemListProperty whitelistBlocks = new ItemListProperty("held-block-whitelist", "",
            this.heldWhitelist::getValue);

    private final ClutchRuntime runtime = new ClutchRuntime(new LiveHost());

    public Clutch() {
        super("Clutch", false, false, "Saves yourself from falling");
        this.runtime.validBlock = stack -> stack != null && stack.getItem() instanceof ItemBlock
                && (!this.blacklist.getValue() || !this.blacklistBlocks.matches(stack));
        this.runtime.whitelisted = stack -> {
            if (stack == null || !(stack.getItem() instanceof ItemBlock)) {
                return false;
            }
            String whitelist = this.whitelistBlocks.getValue();
            return whitelist == null || whitelist.trim().isEmpty() || this.whitelistBlocks.matches(stack);
        };
    }

    private static Clutch instance() {
        if (Myau.moduleManager == null) {
            return null;
        }
        Module module = Myau.moduleManager.modules.get(Clutch.class);
        return module instanceof Clutch ? (Clutch) module : null;
    }

    /** True while the clutch is turning your view, so the aim assist stays out of the way. */
    public static boolean isAiming() {
        Clutch clutch = instance();
        return clutch != null && clutch.runtime.isAiming();
    }

    /** The start of every tick, before anything else runs: the aim is topped up here. */
    public static void driverTick() {
        Clutch clutch = instance();
        if (clutch != null) {
            clutch.runtime.driver.tick(mc.thePlayer != null && mc.theWorld != null, mc.currentScreen == null);
        }
    }

    /** Every frame, right after your own mouse movement is applied. */
    public static void frame(float partialTicks) {
        Clutch clutch = instance();
        if (clutch == null || mc.thePlayer == null) {
            return;
        }
        float timerSpeed = ((IAccessorTimer) ((IAccessorMinecraft) mc).getTimer()).getTimerSpeed();
        clutch.runtime.driver.frame(timerSpeed, mc.currentScreen == null);
    }

    private void applySettings() {
        ClutchRuntime runtime = this.runtime;
        runtime.onVoid = this.onVoid.getValue();
        runtime.onLethalFall = this.onLethalFall.getValue();
        runtime.onMoreThanXBlocks = this.onMoreThanXBlocks.getValue();
        runtime.blocksThreshold = this.blocksThreshold.getValue();
        runtime.speed = this.speed.getValue();
        runtime.silentAim = this.silentAim.getValue();
        runtime.resetAngle = this.resetAngle.getValue();
        runtime.resetAngleDelayMin = this.resetAngleDelayMin.getValue();
        runtime.resetAngleDelayMax = this.resetAngleDelayMax.getValue();
        runtime.returnToLastSlot = this.returnToLastSlot.getValue();
        runtime.returnDelayMin = this.returnDelayMin.getValue();
        runtime.returnDelayMax = this.returnDelayMax.getValue();
        runtime.moveDelayMin = this.moveDelayMin.getValue();
        runtime.moveDelayMax = this.moveDelayMax.getValue();
        runtime.failDelay = this.failDelay.getValue();
        runtime.allowStaircaseUp = this.allowStaircaseUp.getValue();
        runtime.limitBlocks = this.limitBlocks.getValue();
        runtime.maxBlocks = this.maxBlocks.getValue();
        runtime.heldWhitelist = this.heldWhitelist.getValue();
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onEnabled() {
        this.applySettings();
        this.runtime.onEnabled();
    }

    /**
     * With a silent aim running, switching off first lets the reported rotation ease back onto
     * your view, as the original does; asking again in the meantime cancels that.
     */
    @Override
    public void setEnabled(boolean enabled) {
        if (!enabled && this.isEnabled() && this.runtime.requestPause()) {
            return;
        }
        super.setEnabled(enabled);
    }

    @Override
    public void onDisabled() {
        this.runtime.onDisabled();
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.runtime.onWorldChange();
    }

    // ---------------------------------------------------------------- events

    @EventTarget
    public void onTick(TickEvent event) {
        this.applySettings();
        if (event.getType() == EventType.PRE) {
            this.runtime.preTick();
            if (this.runtime.consumeDisableRequest()) {
                super.setEnabled(false);
            }
        } else {
            this.runtime.postTick();
        }
    }

    /** Runs after every other module's input handling, so the clutch has the last word. */
    @EventTarget(Priority.LOWEST)
    public void onMoveInput(MoveInputEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (player != null) {
            this.runtime.moveInput(player.movementInput, player.rotationYaw);
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE || mc.thePlayer == null) {
            return;
        }
        float[] rotation = this.runtime.reportedRotation(mc.thePlayer);
        if (rotation != null) {
            event.setRotation(rotation[0], rotation[1], ROTATION_PRIORITY);
            event.setPervRotation(rotation[0], ROTATION_PRIORITY);
        }
    }

    @EventTarget
    public void onLeftClick(LeftClickMouseEvent event) {
        if (this.runtime.isClutching()) {
            event.setCancelled(true);
        }
    }

    @EventTarget
    public void onRightClick(RightClickMouseEvent event) {
        if (this.runtime.isClutching()) {
            event.setCancelled(true);
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onPacket(PacketEvent event) {
        if (event.getType() != EventType.RECEIVE || event.isCancelled() || mc.thePlayer == null) {
            return;
        }
        Packet<?> packet = event.getPacket();
        if (packet instanceof S12PacketEntityVelocity) {
            if (((S12PacketEntityVelocity) packet).getEntityID() == mc.thePlayer.getEntityId()) {
                this.runtime.noteKnockback();
            }
        } else if (packet instanceof S27PacketExplosion) {
            S27PacketExplosion explosion = (S27PacketExplosion) packet;
            if (Math.abs(explosion.func_149149_c()) >= 0.005 || Math.abs(explosion.func_149144_d()) >= 0.005
                    || Math.abs(explosion.func_149147_e()) >= 0.005) {
                this.runtime.noteExplosion();
            }
        } else if (packet instanceof S08PacketPlayerPosLook) {
            this.runtime.noteTeleport();
        } else if (packet instanceof S23PacketBlockChange) {
            S23PacketBlockChange change = (S23PacketBlockChange) packet;
            if (change.getBlockState() != null && change.getBlockState().getBlock().getMaterial() == Material.air) {
                this.runtime.noteBlockCleared(change.getBlockPosition());
            }
        }
    }

    @Override
    public String[] getSuffix() {
        if (!this.showBlockCount.getValue() || mc.thePlayer == null) {
            return new String[0];
        }
        ClutchPath path = this.runtime.getClutchPath();
        int blockCount = path == null ? this.runtime.countBlocks(mc.thePlayer) : path.getPendingPlacementCount();
        String color = blockCount >= 32 ? "§a" : (blockCount >= 16 ? "§e" : "§c");
        return new String[]{(path == null ? color : "§6§l") + blockCount};
    }

    /** Whether the key is held down on the keyboard or mouse, whatever any module made of it. */
    private static boolean physicallyDown(KeyBinding key) {
        int code = key.getKeyCode();
        if (code == 0) {
            return false;
        }
        return code < 0 ? Mouse.isButtonDown(code + 100) : Keyboard.isKeyDown(code);
    }

    /** The live game, as the clutch sees it. */
    private static final class LiveHost implements ClutchHost {
        @Override
        public EntityPlayer player() {
            return mc.thePlayer;
        }

        @Override
        public World world() {
            return mc.theWorld;
        }

        @Override
        public boolean screenOpen() {
            return mc.currentScreen != null;
        }

        @Override
        public boolean forwardPhysicallyDown() {
            return physicallyDown(mc.gameSettings.keyBindForward);
        }

        @Override
        public boolean jumpPhysicallyDown() {
            return physicallyDown(mc.gameSettings.keyBindJump);
        }

        @Override
        public boolean[] heldMovementKeys() {
            GameSettings settings = mc.gameSettings;
            return new boolean[]{settings.keyBindForward.isKeyDown(), settings.keyBindBack.isKeyDown(),
                    settings.keyBindLeft.isKeyDown(), settings.keyBindRight.isKeyDown()};
        }

        @Override
        public MovementSnapshot snapshot() {
            return MovementSnapshot.of(mc.thePlayer);
        }

        @Override
        public LocalInput localInput() {
            return LocalInput.capture(mc.thePlayer);
        }

        @Override
        public double blockReach() {
            return mc.playerController != null ? mc.playerController.getBlockReachDistance() : 4.5;
        }

        @Override
        public void rightClick(MovingObjectPosition hit) {
            EntityPlayerSP player = mc.thePlayer;
            ItemStack stack = player.inventory.getCurrentItem();
            ((IAccessorMinecraft) mc).setRightClickDelayTimer(4);
            if (mc.playerController.onPlayerRightClick(player, mc.theWorld, stack, hit.getBlockPos(), hit.sideHit, hit.hitVec)) {
                player.swingItem();
            }
        }

        @Override
        public void releaseAttackKey() {
            KeyBinding attack = mc.gameSettings.keyBindAttack;
            if (attack.isKeyDown()) {
                KeyBinding.setKeyBindState(attack.getKeyCode(), false);
            }
        }

        @Override
        public void notifyFailure(String message) {
            if (Myau.notificationManager != null) {
                Myau.notificationManager.add("Clutch failed: " + message, 3500L, 0xFF5555);
            }
        }
    }
}
