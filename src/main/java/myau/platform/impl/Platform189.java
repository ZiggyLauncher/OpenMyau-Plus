package myau.platform.impl;

import myau.Myau;
import myau.module.modules.HUD;
import myau.platform.Platform;
import myau.util.KeyBindUtil;

/**
 * {@link Platform} for Minecraft 1.8.9.
 * <p>
 * Each method is the code that used to sit inline in the shared classes, moved here unchanged so
 * behaviour on this version is identical.
 */
public final class Platform189 implements Platform {
    @Override
    public String keyName(int keyCode) {
        return KeyBindUtil.getKeyName(keyCode);
    }

    @Override
    public void onModuleToggled(String moduleName, boolean enabled) {
        if (Myau.moduleManager != null) {
            HUD hud = (HUD) Myau.moduleManager.modules.get(HUD.class);
            if (hud != null && hud.toggleSound.getValue()) {
                Myau.moduleManager.playSound();
            }
        }
        try {
            if (Myau.notificationManager != null) {
                String action = enabled ? "was toggled successfully" : "was untoggled successfully";
                // green for enabled, red for disabled
                int color = enabled ? 0x00FF00 : 0xFF0000;
                Myau.notificationManager.add(moduleName + " " + action, color);
            }
        } catch (Exception ignored) {
        }
    }
}
