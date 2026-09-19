package myau.util;

import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public class KeyBindUtil {
    /**
     * Key codes follow Minecraft's own convention: LWJGL keyboard codes are positive, mouse
     * buttons are stored as {@code button - 100} (so LMB = -100, MOUSE4 = -96), and 0 is unbound.
     */
    public static final int NONE = Keyboard.KEY_NONE;
    private static final int MOUSE_OFFSET = 100;

    public static int mouseButtonToKey(int button) {
        return button - MOUSE_OFFSET;
    }

    public static int keyToMouseButton(int keyCode) {
        return keyCode + MOUSE_OFFSET;
    }

    public static boolean isMouseKey(int keyCode) {
        return keyCode < 0;
    }

    /**
     * Keys that clear a bind while a bind component is listening (ESC also cancels).
     */
    public static boolean isUnbindKey(int keyCode) {
        return keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_DELETE || keyCode == Keyboard.KEY_BACK;
    }

    /**
     * Whether a mouse button pressed while a bind component is listening should become the bind.
     * The left button is reserved for clicking around the GUI (and cancelling), everything else -
     * right, middle and the side buttons - is bindable.
     */
    public static boolean isBindableMouseButton(int button) {
        return button > 0;
    }

    /**
     * Parses a key as typed by the user: an LWJGL key name ("R", "RSHIFT"), or a mouse button
     * ("MOUSE4", "M4", "BUTTON4", "LMB", "RMB", "MMB", "XBUTTON1"...). Returns
     * {@link Integer#MIN_VALUE} when nothing matches so callers can tell "unknown" from "none".
     */
    public static int parseKey(String input) {
        if (input == null) {
            return Integer.MIN_VALUE;
        }
        String name = input.trim().toUpperCase(java.util.Locale.ROOT);
        if (name.isEmpty()) {
            return Integer.MIN_VALUE;
        }
        if (name.equals("NONE") || name.equals("NULL") || name.equals("0") || name.equals("UNBIND")) {
            return NONE;
        }
        int keyIndex = Keyboard.getKeyIndex(name);
        if (keyIndex != Keyboard.KEY_NONE) {
            return keyIndex;
        }
        int button = parseMouseButton(name);
        if (button != -1) {
            return mouseButtonToKey(button);
        }
        // Raw numeric code (what the config stores), e.g. "-96" or "54".
        try {
            return Integer.parseInt(name);
        } catch (NumberFormatException ignored) {
            return Integer.MIN_VALUE;
        }
    }

    private static int parseMouseButton(String name) {
        switch (name) {
            case "LBUTTON":
            case "LMB":
            case "LEFTCLICK":
                return 0;
            case "RBUTTON":
            case "RMB":
            case "RIGHTCLICK":
                return 1;
            case "MBUTTON":
            case "MMB":
            case "MIDDLECLICK":
            case "SCROLLCLICK":
                return 2;
            case "XBUTTON1":
            case "SIDEBUTTON1":
            case "BOTTOMSIDE":
                return 3;
            case "XBUTTON2":
            case "SIDEBUTTON2":
            case "TOPSIDE":
                return 4;
            default:
                break;
        }
        // MOUSE4, M4, BUTTON4, MB4
        String[] prefixes = {"MOUSE", "BUTTON", "MB", "M"};
        for (String prefix : prefixes) {
            if (name.startsWith(prefix) && name.length() > prefix.length()) {
                try {
                    int button = Integer.parseInt(name.substring(prefix.length()));
                    if (button >= 0 && button < 16) {
                        return button;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return -1;
    }

    public static String getKeyName(int keyCode) {
        if (keyCode == Keyboard.KEY_NONE) {
            return "None";
        }

        if (keyCode < 0) {
            int mouseButton = keyCode + 100;
            switch (mouseButton) {
                case 0:
                    return "LMB";
                case 1:
                    return "RMB";
                case 2:
                    return "MMB";
                case 3:
                    return "MOUSE3";
                case 4:
                    return "MOUSE4";
                case 5:
                    return "MOUSE5";
                case 6:
                    return "MOUSE6";
                case 7:
                    return "MOUSE7";
                default:
                    if (mouseButton >= 0 && mouseButton < Mouse.getButtonCount()) {
                        String buttonName = Mouse.getButtonName(mouseButton);
                        return buttonName != null ? buttonName : "MOUSE" + mouseButton;
                    }
                    return "MOUSE" + mouseButton;
            }
        }

        if (keyCode >= Keyboard.KEYBOARD_SIZE) {
            return "KEY" + keyCode;
        }

        String keyName = Keyboard.getKeyName(keyCode);
        return keyName != null ? keyName : "KEY" + keyCode;
    }

    public static boolean isKeyDown(int keyCode) {
        return keyCode < 0 ? Mouse.isButtonDown(keyCode + 100) : Keyboard.isKeyDown(keyCode);
    }

    public static void updateKeyState(int keyCode) {
        KeyBindUtil.setKeyBindState(keyCode, keyCode < 0 ? Mouse.isButtonDown(keyCode + 100) : Keyboard.isKeyDown(keyCode));
    }

    public static void setKeyBindState(int keyCode, boolean pressed) {
        KeyBinding.setKeyBindState(keyCode, pressed);
    }

    public static void pressKeyOnce(int keyCode) {
        KeyBinding.onTick(keyCode);
    }
}
