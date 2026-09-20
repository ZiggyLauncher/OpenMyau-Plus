package myau.ui.impl.clickgui.adin;

import myau.Myau;
import myau.module.Module;
import myau.module.modules.ClickGUIModule;

import java.awt.Color;

/**
 * The palette this skin is built on: a near-black stack of greys with one accent colour, so the
 * accent is the only thing on screen that carries meaning.
 * <p>
 * The greys are fixed rather than derived from the client's colour setting, because the whole
 * look depends on the separation between them staying constant; only {@link #accent()} follows
 * the ClickGUI module's colour.
 */
public final class AdinTheme {
    public static final int ON_ACCENT = 0xFF101010;
    public static final int TEXT = 0xFFEAEAEA;
    public static final int MUTED = 0xFF858585;
    public static final int DIM = 0xFF999999;
    public static final int SIDEBAR = 0xFF161616;
    public static final int MAIN = 0xFF0F0F0F;
    public static final int ROW = 0xFF1C1C1C;
    public static final int OVERLAY = 0xFF171717;
    public static final int FIELD = 0xFF141414;
    public static final int CONTROL = 0xFF262626;
    public static final int CONTROL_ACTIVE = 0xFF3A3A3A;
    public static final int TRACK = 0xFF343434;
    public static final int HIGHLIGHT = 0xFF2B322B;
    public static final int TOGGLE_OFF = 0xFF171717;
    public static final int TOGGLE_OFF_BORDER = 0xFF2B2B2B;
    public static final int THUMB_OFF = 0xFF484848;

    private AdinTheme() {
    }

    /** The client's accent colour, opaque. */
    public static int accent() {
        if (Myau.moduleManager != null) {
            Module module = Myau.moduleManager.getModule("ClickGUI");
            if (module instanceof ClickGUIModule) {
                return 0xFF000000 | (((ClickGUIModule) module).getAccentColor().getRGB() & 0xFFFFFF);
            }
        }
        return 0xFFB8DDB0;
    }

    public static Color color(int argb) {
        return new Color(argb, true);
    }

    public static int alpha(int argb, float alpha) {
        int clamped = Math.max(0, Math.min(255, Math.round(((argb >>> 24) & 0xFF) * alpha)));
        return (clamped << 24) | (argb & 0xFFFFFF);
    }

    /** Straight per-channel blend, used for every hover and selection fade in this skin. */
    public static int lerp(int from, int to, float t) {
        float clamped = Math.max(0.0F, Math.min(1.0F, t));
        int a = Math.round(((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * clamped);
        int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * clamped);
        int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * clamped);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * clamped);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
