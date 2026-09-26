package myau.ui.impl.clickgui.vape;

import myau.module.Module;
import myau.util.KeyBindUtil;
import org.lwjgl.input.Keyboard;

import java.awt.*;
import java.util.Locale;

/**
 * The keybind chip on a module row ({@code BindableInputComponent}): the bound key's name in a
 * faint box, a pen icon while hovered, and a cross while it waits for a key. Clicking it while
 * waiting removes the bind, as does Escape.
 */
final class BindButton {
    /** Vape's {@code activeAlpha}: the box alpha while the module is on. */
    private static final int ACTIVE_ALPHA = 51;
    private static final int IDLE_ALPHA = 17;

    final Module module;
    double x;
    double y;
    double width = 10.0;
    double height = 10.0;
    boolean visible;
    boolean hovered;
    boolean highlighted;
    /** Longest the key name may be before it is cut short with "...". */
    double maxLabelWidth = 50.0;

    BindButton(Module module) {
        this.module = module;
    }

    boolean contains(double mouseX, double mouseY) {
        return this.visible && mouseX >= this.x && mouseX <= this.x + this.width
                && mouseY >= this.y && mouseY <= this.y + this.height;
    }

    private boolean isActive() {
        return this.module.isEnabled();
    }

    String bindText() {
        return keyName(this.module.getKey());
    }

    private String label(VapeFont font) {
        String text = this.bindText();
        double limit = Math.min(50.0, this.maxLabelWidth);
        if (font.width(text) <= limit) {
            return text;
        }
        String cut = text;
        while (!cut.isEmpty() && font.width(cut + "...") > limit) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "...";
    }

    /** Sizes the chip for its current text; the row positions it from the result. */
    void measure(boolean capturing) {
        String text = this.bindText();
        if (capturing || text.isEmpty() || text.length() == 1) {
            this.width = 10.0;
        } else {
            this.width = font().width(this.label(font())) + 5.0;
        }
    }

    private static VapeFont font() {
        return VapeFont.get(0.8);
    }

    void render(boolean capturing) {
        if (!this.visible) {
            return;
        }
        Color accentText = VapeTheme.accentText();
        float iconSize = 5.0F;
        float iconOffset = this.width != 10.0 ? (float) this.width - 6.0F : iconSize;
        Color idle = new Color(255, 255, 255, this.isActive() ? ACTIVE_ALPHA : IDLE_ALPHA);
        if (this.hovered || capturing) {
            VapeRender.rounded(this.x, this.y, this.width, this.height, idle);
            VapeRender.image(this.isActive() ? accentText : VapeTheme.TEXT, this.x + iconOffset / 2.0F,
                    this.y + iconSize / 2.0F - 0.5F, capturing ? "newclose" : "newedit", iconSize, iconSize);
            return;
        }
        String text = this.bindText();
        if (text.isEmpty()) {
            VapeRender.rounded(this.x, this.y, this.width, this.height, this.highlighted ? VapeTheme.ORANGE_FAINT : idle);
            Color iconColor = this.highlighted ? VapeTheme.ORANGE : (this.isActive() ? accentText : VapeTheme.TEXT_DIM);
            VapeRender.image(iconColor, this.x + iconOffset / 2.0F, this.y + iconSize / 2.0F - 0.5F, "newbind",
                    iconSize, iconSize);
            return;
        }
        VapeRender.rounded(this.x, this.y, this.width, this.height, this.highlighted ? VapeTheme.ORANGE_FAINT : idle);
        VapeFont font = font();
        String label = this.label(font);
        double labelWidth = font.width(label);
        Color color = this.highlighted ? VapeTheme.ORANGE_LIGHT : (this.isActive() ? accentText : VapeTheme.TEXT_DIM);
        font.draw(label, this.x + this.width / 2.0 - labelWidth / 2.0,
                this.y + this.height / 2.0 - font.height() / 2.0 - 0.5, color);
    }

    /**
     * Key names as Windows spells them, which is what the original shows (it asks the OS). Mouse
     * binds are M1..M5.
     */
    static String keyName(int key) {
        if (key == KeyBindUtil.NONE) {
            return "";
        }
        if (KeyBindUtil.isMouseKey(key)) {
            return "M" + (key + 101);
        }
        switch (key) {
            case Keyboard.KEY_LSHIFT:
                return "Shift";
            case Keyboard.KEY_RSHIFT:
                return "Right Shift";
            case Keyboard.KEY_LCONTROL:
                return "Ctrl";
            case Keyboard.KEY_RCONTROL:
                return "Right Ctrl";
            case Keyboard.KEY_LMENU:
                return "Alt";
            case Keyboard.KEY_RMENU:
                return "Right Alt";
            case Keyboard.KEY_SPACE:
                return "Space";
            case Keyboard.KEY_TAB:
                return "Tab";
            case Keyboard.KEY_CAPITAL:
                return "Caps Lock";
            case Keyboard.KEY_RETURN:
                return "Enter";
            case Keyboard.KEY_NUMPADENTER:
                return "Num Enter";
            case Keyboard.KEY_BACK:
                return "Backspace";
            case Keyboard.KEY_ESCAPE:
                return "Esc";
            case Keyboard.KEY_INSERT:
                return "Insert";
            case Keyboard.KEY_DELETE:
                return "Delete";
            case Keyboard.KEY_HOME:
                return "Home";
            case Keyboard.KEY_END:
                return "End";
            case Keyboard.KEY_PRIOR:
                return "Page Up";
            case Keyboard.KEY_NEXT:
                return "Page Down";
            case Keyboard.KEY_UP:
                return "Up";
            case Keyboard.KEY_DOWN:
                return "Down";
            case Keyboard.KEY_LEFT:
                return "Left";
            case Keyboard.KEY_RIGHT:
                return "Right";
            case Keyboard.KEY_NUMLOCK:
                return "Num Lock";
            case Keyboard.KEY_SCROLL:
                return "Scroll Lock";
            case Keyboard.KEY_GRAVE:
                return "`";
            case Keyboard.KEY_MINUS:
                return "-";
            case Keyboard.KEY_EQUALS:
                return "=";
            case Keyboard.KEY_LBRACKET:
                return "[";
            case Keyboard.KEY_RBRACKET:
                return "]";
            case Keyboard.KEY_SEMICOLON:
                return ";";
            case Keyboard.KEY_APOSTROPHE:
                return "'";
            case Keyboard.KEY_BACKSLASH:
                return "\\";
            case Keyboard.KEY_COMMA:
                return ",";
            case Keyboard.KEY_PERIOD:
                return ".";
            case Keyboard.KEY_SLASH:
                return "/";
            default:
                break;
        }
        if (key >= Keyboard.KEY_NUMPAD7 && key <= Keyboard.KEY_NUMPAD0 || key == Keyboard.KEY_DECIMAL) {
            String name = KeyBindUtil.getKeyName(key);
            return "Num " + name.replace("NUMPAD", "").replace("DECIMAL", "Del");
        }
        String name = KeyBindUtil.getKeyName(key);
        if (name == null || name.isEmpty()) {
            return "";
        }
        if (name.length() <= 3) {
            return name.toUpperCase(Locale.ROOT);
        }
        return name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT);
    }
}
