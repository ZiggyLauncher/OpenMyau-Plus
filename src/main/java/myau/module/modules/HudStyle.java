package myau.module.modules;

import myau.Myau;
import myau.font.CFontRenderer;
import myau.util.shader.RoundedUtils;
import net.minecraft.client.Minecraft;

import java.awt.Color;

/**
 * Shared look and feel for the HUD modules: the same rounded translucent panel, the same colour
 * modes and the same font as the rest of the client.
 * <p>
 * Deliberately blur-free. The glass effect elsewhere costs a full-screen framebuffer pass per
 * element per frame; these modules draw a handful of rounded rects and some text instead, which is
 * a few dozen triangles and no render target switches, so they cost nothing measurable.
 * <p>
 * Lives in this package because {@link HUD#fontRenderer} is package-private.
 */
final class HudStyle {
    static final String[] COLOR_MODES = {"Custom", "HUD Theme", "Rainbow"};
    static final int MODE_CUSTOM = 0;
    static final int MODE_HUD = 1;
    static final int MODE_RAINBOW = 2;

    private static final Minecraft mc = Minecraft.getMinecraft();

    private HudStyle() {
    }

    private static HUD hud() {
        return Myau.moduleManager == null ? null : (HUD) Myau.moduleManager.modules.get(HUD.class);
    }

    /** The client font, or null when the user picked the vanilla one. */
    static CFontRenderer font() {
        HUD hud = hud();
        if (hud == null || hud.fontMode.getValue() == 1) {
            return null;
        }
        return hud.fontRenderer;
    }

    static float stringWidth(String text) {
        CFontRenderer font = font();
        return font != null ? font.getStringWidth(text) : mc.fontRendererObj.getStringWidth(text);
    }

    static float fontHeight() {
        CFontRenderer font = font();
        return font != null ? font.FONT_HEIGHT : mc.fontRendererObj.FONT_HEIGHT;
    }

    static void drawString(String text, float x, float y, int color, boolean shadow) {
        CFontRenderer font = font();
        if (font != null) {
            if (shadow) {
                font.drawStringWithShadow(text, x, y, color);
            } else {
                font.drawString(text, x, y, color);
            }
        } else if (shadow) {
            mc.fontRendererObj.drawStringWithShadow(text, x, y, color);
        } else {
            mc.fontRendererObj.drawString(text, (int) x, (int) y, color);
        }
    }

    static void drawCentered(String text, float centerX, float y, int color, boolean shadow) {
        drawString(text, centerX - stringWidth(text) / 2.0F, y, color, shadow);
    }

    /**
     * @param mode   index into {@link #COLOR_MODES}
     * @param rgb    the module's own colour, used by {@code Custom}
     * @param alpha  0-255
     * @param offset milliseconds added to the animation clock, so several elements can be offset
     *               against each other in the animated modes
     */
    static int color(int mode, int rgb, int alpha, long offset) {
        int clamped = Math.max(0, Math.min(255, alpha));
        long now = System.currentTimeMillis();
        switch (mode) {
            case MODE_HUD: {
                HUD hud = hud();
                int themed = hud != null ? hud.getColor(now, offset) : rgb;
                return (clamped << 24) | (themed & 0xFFFFFF);
            }
            case MODE_RAINBOW: {
                float hue = ((now + offset) % 4000L) / 4000.0F;
                return (clamped << 24) | (Color.HSBtoRGB(hue, 0.7F, 1.0F) & 0xFFFFFF);
            }
            case MODE_CUSTOM:
            default:
                return (clamped << 24) | (rgb & 0xFFFFFF);
        }
    }

    static Color awt(int argb) {
        return new Color(argb, true);
    }

    /** Rounded translucent background with the faint outline the rest of the client uses. */
    static void panel(float x, float y, float width, float height, float radius, int backgroundAlpha) {
        if (width <= 0.0F || height <= 0.0F || backgroundAlpha <= 0) {
            return;
        }
        int alpha = Math.max(0, Math.min(255, backgroundAlpha));
        RoundedUtils.drawRoundOutline(x, y, width, height, radius, 0.5F,
                new Color(20, 20, 24, alpha), new Color(255, 255, 255, Math.min(40, alpha / 2)));
    }

    static void rect(float x, float y, float width, float height, float radius, int argb) {
        if (width <= 0.0F || height <= 0.0F) {
            return;
        }
        RoundedUtils.drawRound(x, y, width, height, radius, awt(argb));
    }

    static void gradient(float x, float y, float width, float height, float radius, int leftArgb, int rightArgb) {
        if (width <= 0.0F || height <= 0.0F) {
            return;
        }
        RoundedUtils.drawGradientHorizontal(x, y, width, height, radius, awt(leftArgb), awt(rightArgb));
    }

    /** Frame-rate independent approach of {@code current} to {@code target}. */
    static float approach(float current, float target, float speed, float deltaSeconds) {
        float factor = 1.0F - (float) Math.exp(-speed * deltaSeconds);
        return current + (target - current) * Math.max(0.0F, Math.min(1.0F, factor));
    }

    /** Seconds since the last call, clamped so a lag spike or a paused game can't jump animations. */
    static float delta(long[] lastFrameHolder) {
        long now = System.nanoTime();
        long last = lastFrameHolder[0];
        lastFrameHolder[0] = now;
        if (last == 0L) {
            return 0.0F;
        }
        return Math.min(0.1F, Math.max(0.0F, (now - last) / 1.0e9F));
    }
}
