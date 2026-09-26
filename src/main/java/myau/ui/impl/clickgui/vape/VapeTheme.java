package myau.ui.impl.clickgui.vape;

import myau.Myau;
import myau.module.Module;
import myau.module.modules.ClickGUIModule;
import org.lwjgl.opengl.Display;

import java.awt.*;

/**
 * Vape's theme colours ({@code ThemeColors}), under names instead of its single letters. The
 * letter each one had in the original is given alongside, since every draw call in the
 * recovered source refers to them that way.
 */
final class VapeTheme {
    /** r: settings rows, main frame, search bar. */
    static final Color DARKEST = new Color(20, 20, 20);
    /** i: category frames, module rows, knobs. */
    static final Color FRAME = new Color(26, 25, 26);
    /** m: hovered rows. */
    static final Color HOVER = new Color(31, 30, 31);
    /** R: dropdown popups. */
    static final Color POPUP = new Color(35, 34, 35);
    /** S. */
    static final Color PANEL = new Color(37, 36, 37);
    /** a. */
    static final Color PANEL_HOVER = new Color(40, 39, 40);
    /** F. */
    static final Color BADGE = new Color(46, 45, 47);
    /** K: switch track while off. */
    static final Color SWITCH_OFF = new Color(54, 53, 54);
    /** y: dropdown border on hover. */
    static final Color BORDER_HOVER = new Color(54, 53, 54);
    /** l: outlines, dividers, empty slider track. */
    static final Color OUTLINE = new Color(54, 53, 54, 128);
    /** h: dim text. */
    static final Color TEXT_DIM = new Color(89, 88, 89);
    /** C. */
    static final Color TEXT_MUTED = new Color(115, 113, 115);
    /** W: icons, switch track on hover. */
    static final Color ICON = new Color(122, 122, 122);
    /** Z: normal text. */
    static final Color TEXT = new Color(163, 163, 163);
    /** A and f: bright text, hovered icons. */
    static final Color TEXT_BRIGHT = new Color(209, 209, 209);
    /** u: frame shadows. */
    static final Color SHADOW = new Color(0, 0, 0, 152);
    /** I: favourite star, highlighted bind icon. */
    static final Color ORANGE = new Color(236, 129, 44);
    /** N: highlighted bind background. */
    static final Color ORANGE_FAINT = new Color(236, 129, 44, 51);
    /** Y: highlighted bind text. */
    static final Color ORANGE_LIGHT = new Color(255, 160, 84);
    /** d: blocked-list marks. */
    static final Color RED = new Color(250, 50, 56);
    /** B: allowed-list marks, and the default GUI colour. */
    static final Color GREEN = new Color(5, 134, 105);
    /** k: list input borders. */
    static final Color WHITE_FAINT = new Color(255, 255, 255, 10);

    static final int DEFAULT_ACCENT = 0x058669;

    /** The scale factors behind the Tiny, Small, Normal, Large and Huge options. */
    private static final double[] SCALES = {0.5, 0.8, 1.0, 1.2, 1.5};

    private static Color cachedAccent;
    private static Color cachedAccentText;

    private VapeTheme() {
    }

    static ClickGUIModule module() {
        if (Myau.moduleManager == null) {
            return null;
        }
        Module module = Myau.moduleManager.getModule(ClickGUIModule.class);
        return module instanceof ClickGUIModule ? (ClickGUIModule) module : null;
    }

    /** The GUI colour ({@code getAccentColor}). */
    static Color accent() {
        ClickGUIModule module = module();
        int rgb = module != null ? module.vapeColor.getValue() : DEFAULT_ACCENT;
        if (cachedAccent == null || (cachedAccent.getRGB() & 0xFFFFFF) != rgb) {
            cachedAccent = new Color(rgb);
            cachedAccentText = contrastingGray(cachedAccent, 45, 240);
        }
        return cachedAccent;
    }

    /** Text drawn over the GUI colour: dark on light colours, light on dark ones. */
    static Color accentText() {
        accent();
        return cachedAccentText;
    }

    static Color contrastingGray(Color color, int dark, int light) {
        int gray = perceivedBrightness(color) > 130 ? dark : light;
        return new Color(gray, gray, gray);
    }

    static int perceivedBrightness(Color color) {
        double red = color.getRed() * color.getRed() * 0.241;
        double green = color.getGreen() * color.getGreen() * 0.691;
        double blue = color.getBlue() * color.getBlue() * 0.068;
        return (int) Math.sqrt(red + green + blue);
    }

    static Color offset(Color color, double amount) {
        int red = (int) Math.max(0.0, Math.min(255.0, color.getRed() + amount));
        int green = (int) Math.max(0.0, Math.min(255.0, color.getGreen() + amount));
        int blue = (int) Math.max(0.0, Math.min(255.0, color.getBlue() + amount));
        return new Color(red, green, blue, color.getAlpha());
    }

    static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    /**
     * How many screen pixels one GUI unit covers is twice this. "Auto" picks by window height,
     * the way the original does.
     */
    static double scale() {
        ClickGUIModule module = module();
        int index = module != null ? module.vapeScale.getValue() : 0;
        if (index <= 0 || index > SCALES.length) {
            int height = Display.getHeight();
            if (height >= 2000) {
                return 1.5;
            }
            if (height >= 1000) {
                return 1.2;
            }
            return 1.0;
        }
        return SCALES[index - 1];
    }
}
