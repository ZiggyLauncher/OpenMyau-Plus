package myau.module.modules;

import myau.Myau;
import myau.module.Module;

/**
 * Gates the client's own main menu. While this is off the game shows the vanilla (or resource
 * pack's) main menu exactly as it always did; while it is on the custom menu takes its place.
 * <p>
 * The check is deliberately null-safe: the very first main menu is displayed at the end of
 * {@code Minecraft.startGame()}, which is <i>before</i> the module manager exists. The menu is
 * then swapped on the first {@code runTick}, which still runs before the first frame is drawn,
 * so there is no flash of the vanilla menu either way.
 */
public class CustomMenu extends Module {
    public CustomMenu() {
        super("CustomMenu", false, false, "Replaces the main menu with the client's own");
    }

    /** True only when the module exists and is enabled - safe to call before the client is built. */
    public static boolean shouldReplaceMainMenu() {
        if (Myau.moduleManager == null) {
            return false;
        }
        Module module = Myau.moduleManager.modules.get(CustomMenu.class);
        return module instanceof CustomMenu && module.isEnabled();
    }
}
