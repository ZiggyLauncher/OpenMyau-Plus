package myau.mixin;

import myau.Myau;
import myau.config.Config;
import myau.init.Initializer;
import myau.event.EventManager;
import myau.event.types.EventType;
import myau.events.*;
import myau.module.modules.NoHitDelay;
import myau.ui.impl.mainmenu.MyauMainMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@SideOnly(Side.CLIENT)
@Mixin(value = {Minecraft.class}, priority = 9999)
public abstract class MixinMinecraft {
    @Shadow
    private int leftClickCounter;
    @Shadow
    public PlayerControllerMP playerController;
    @Shadow
    public WorldClient theWorld;
    @Shadow
    public EntityPlayerSP thePlayer;
    @Shadow
    public GuiScreen currentScreen;

    @Inject(
            method = {"displayGuiScreen"},
            at = {@At("HEAD")},
            cancellable = true
    )
    private void replaceMainMenu(GuiScreen guiScreen, CallbackInfo callbackInfo) {
        if (isVanillaMainMenu(guiScreen)) {
            displayMyauMainMenu();
            callbackInfo.cancel();
        }
    }

    @Inject(
            method = {"startGame"},
            at = {@At("HEAD")}
    )
    private void startGame(CallbackInfo callbackInfo) {
        new Initializer();
    }

    @Inject(
            method = {"startGame"},
            at = {@At("RETURN")}
    )
    private void postStartGame(CallbackInfo callbackInfo) {
        new Myau();
        Myau.updateDisplayTitle();
    }

    @Inject(
            method = {"runTick"},
            at = {@At("HEAD")}
    )
    private void runTick(CallbackInfo callbackInfo) {
        if (this.theWorld != null && this.thePlayer != null) {
            EventManager.call(new TickEvent(EventType.PRE));
        }
    }

    @Inject(
            method = {"runTick"},
            at = {@At("RETURN")}
    )
    private void postRunTick(CallbackInfo callbackInfo) {
        if (this.theWorld != null && this.thePlayer != null) {
            EventManager.call(new TickEvent(EventType.POST));
        }
        if (isVanillaMainMenu(this.currentScreen)) {
            displayMyauMainMenu();
        }
    }

    private boolean isVanillaMainMenu(GuiScreen guiScreen) {
        return guiScreen != null && "net.minecraft.client.gui.GuiMainMenu".equals(guiScreen.getClass().getName());
    }

    private void displayMyauMainMenu() {
        ((Minecraft) (Object) this).displayGuiScreen(new MyauMainMenu());
    }

    @Inject(
            method = {"loadWorld(Lnet/minecraft/client/multiplayer/WorldClient;Ljava/lang/String;)V"},
            at = {@At("HEAD")}
    )
    private void loadWorld(WorldClient worldClient, String string, CallbackInfo callbackInfo) {
        // Snapshot the module state before modules reset themselves for the new/no world.
        Config.autoSave();
        EventManager.call(new LoadWorldEvent());
    }

    /**
     * shutdown() is the first thing both "Quit Game" and closing the window call, while the
     * world and every module's live state are still intact.
     */
    @Inject(
            method = {"shutdown"},
            at = {@At("HEAD")}
    )
    private void shutdown(CallbackInfo callbackInfo) {
        Myau.onShutdownRequested();
    }

    @Inject(
            method = {"updateFramebufferSize"},
            at = {@At("RETURN")}
    )
    private void updateFramebufferSize(CallbackInfo callbackInfo) {
        EventManager.call(new ResizeEvent());
    }

    @Inject(
            method = {"clickMouse"},
            at = {@At("HEAD")},
            cancellable = true
    )
    private void clickMouse(CallbackInfo callbackInfo) {
        if (Myau.moduleManager != null && Myau.moduleManager.modules.get(NoHitDelay.class).isEnabled()) {
            this.leftClickCounter = 0;
        }
        LeftClickMouseEvent event = new LeftClickMouseEvent();
        EventManager.call(event);
        if (event.isCancelled()) {
            callbackInfo.cancel();
        }
    }

    @Inject(
            method = {"rightClickMouse"},
            at = {@At("HEAD")},
            cancellable = true
    )
    private void rightClickMouse(CallbackInfo callbackInfo) {
        RightClickMouseEvent event = new RightClickMouseEvent();
        EventManager.call(event);
        if (event.isCancelled()) {
            callbackInfo.cancel();
        }
    }

    @Inject(
            method = {"sendClickBlockToController"},
            at = {@At("HEAD")},
            cancellable = true
    )
    private void sendClickBlockToController(CallbackInfo callbackInfo) {
        HitBlockEvent event = new HitBlockEvent();
        EventManager.call(event);
        if (event.isCancelled()) {
            callbackInfo.cancel();
            this.playerController.resetBlockRemoving();
        }
    }

    @Redirect(
            method = {"runTick"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/settings/KeyBinding;setKeyBindState(IZ)V"
            )
    )
    private void setKeyBindState(int integer, boolean boolean2) {
        int button = integer + 100;
        if (button >= 3 && button < myau$sideButtonDown.length) {
            myau$handleSideButton(integer, button, boolean2);
            return;
        }
        KeyBinding.setKeyBindState(integer, boolean2);
        // Keyboard repeat events (left enabled by some screens) are not new presses.
        if (boolean2 && this.currentScreen == null && !(integer > 0 && Keyboard.isRepeatEvent())) {
            EventManager.call(new KeyEvent(integer));
        }
    }

    /** What we believe each mouse side button's physical state is, indexed by LWJGL button. */
    @Unique
    private final boolean[] myau$sideButtonDown = new boolean[16];

    /**
     * LWJGL's Windows handler decides which side button went down with {@code (wParam & 0xFF) == MK_XBUTTON1},
     * but that byte also carries every other held button and modifier: pressing side button 1 while
     * holding LMB, Shift or Ctrl is reported as side button 2. The release is decoded correctly, and
     * it never matches the bogus press, so LWJGL's own button state for the wrong button stays stuck
     * "down". Side-button binds therefore toggle on release, using the true button, and an orphan
     * release corrects the phantom press.
     */
    @Unique
    private void myau$handleSideButton(int key, int button, boolean pressed) {
        if (pressed) {
            myau$sideButtonDown[button] = true;
            KeyBinding.setKeyBindState(key, true);
            return;
        }
        if (!myau$sideButtonDown[button]) {
            // Released a button we never saw pressed: the press was attributed to another side button.
            for (int other = 3; other < myau$sideButtonDown.length; other++) {
                if (other != button && myau$sideButtonDown[other]) {
                    myau$sideButtonDown[other] = false;
                    KeyBinding.setKeyBindState(other - 100, false);
                }
            }
        }
        myau$sideButtonDown[button] = false;
        KeyBinding.setKeyBindState(key, false);
        if (this.currentScreen == null) {
            EventManager.call(new KeyEvent(key));
        }
    }

    @Redirect(
            method = {"runTick"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/player/InventoryPlayer;changeCurrentItem(I)V"
            )
    )
    private void changeCurrentItem(InventoryPlayer inventoryPlayer, int slot) {
        SwapItemEvent event = new SwapItemEvent(-1, slot);
        EventManager.call(event);
        if (!event.isCancelled()) {
            inventoryPlayer.changeCurrentItem(slot);
        }
    }
}
