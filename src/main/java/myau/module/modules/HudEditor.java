package myau.module.modules;

import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.ui.impl.hud.HudEditorScreen;
import net.minecraft.client.Minecraft;

/**
 * Opens the HUD editor, where every draggable HUD element can be moved with the mouse.
 * Toggling the module (or its bind) opens the screen; closing the screen toggles it back off,
 * the same way the ClickGUI module works.
 */
public class HudEditor extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final BooleanProperty dimBackground = new BooleanProperty("Dim-Background", true);
    public final BooleanProperty snapping = new BooleanProperty("Snapping", true);

    public HudEditor() {
        super("HudEditor", false, false, "Drag HUD elements where you want them");
    }

    public void open() {
        if (mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        mc.displayGuiScreen(new HudEditorScreen());
    }

    @Override
    public void onEnabled() {
        if (mc.theWorld == null || mc.thePlayer == null) {
            // Nothing to lay out on the main menu; don't leave the module stuck on.
            this.setEnabled(false);
            return;
        }
        this.open();
    }

    @Override
    public void onDisabled() {
        if (mc.currentScreen instanceof HudEditorScreen) {
            mc.displayGuiScreen(null);
        }
    }
}
