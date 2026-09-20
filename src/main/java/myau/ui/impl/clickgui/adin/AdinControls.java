package myau.ui.impl.clickgui.adin;

import myau.util.RenderUtil;
import myau.util.font.FontManager;
import myau.util.font.impl.FontRenderer;
import myau.util.shader.RoundedUtils;
import net.minecraft.client.Minecraft;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

/**
 * The individual controls the adin skin is made of, drawn to the same geometry the original uses:
 * a toggle whose thumb squashes as it travels, a segmented picker with a sliding highlight, and a
 * slider whose knob is always on screen with its value in an aligned column beside it.
 * <p>
 * Animation state is keyed off the thing being drawn rather than held by a widget object, because
 * this skin rebuilds its layout every frame and has no persistent widgets to hang it on.
 */
public final class AdinControls {
    /** Toggle geometry, from adin: a 24x12 pill with the thumb inset by a sixth of the height. */
    public static final int TOGGLE_WIDTH = 24;
    public static final int TOGGLE_HEIGHT = 12;
    private static final float THUMB_STRETCH = 0.28F;

    /** Slider geometry: a 3px track with a 10x6 knob riding it. */
    public static final float TRACK_HEIGHT = 3.0F;
    public static final float KNOB_WIDTH = 10.0F;
    public static final float KNOB_HEIGHT = 6.0F;
    private static final float KNOB_RADIUS = 2.0F;

    private static final float SEGMENT_STRETCH = 0.18F;
    private static final float SEGMENT_RADIUS = 4.0F;

    private static final Map<Object, AdinTransition> TOGGLES = new HashMap<Object, AdinTransition>();
    private static final Map<Object, AdinTransition> SEGMENTS = new HashMap<Object, AdinTransition>();
    private static final Map<Object, AdinTransition> SLIDERS = new HashMap<Object, AdinTransition>();

    private AdinControls() {
    }

    /** Forgets every animation, so a reopened GUI does not replay the last session's movement. */
    public static void reset() {
        TOGGLES.clear();
        SEGMENTS.clear();
        SLIDERS.clear();
    }

    private static AdinTransition toggle(Object key, boolean on) {
        AdinTransition transition = TOGGLES.get(key);
        if (transition == null) {
            transition = new AdinTransition(on ? 1.0F : 0.0F, 170, AdinTransition.EASE_OUT_SETTLE);
            TOGGLES.put(key, transition);
        }
        return transition;
    }

    private static AdinTransition segment(Object key, float index) {
        AdinTransition transition = SEGMENTS.get(key);
        if (transition == null) {
            transition = new AdinTransition(index, 170, AdinTransition.EASE_OUT_EXPO);
            SEGMENTS.put(key, transition);
        }
        return transition;
    }

    private static AdinTransition slider(Object key, float value) {
        AdinTransition transition = SLIDERS.get(key);
        if (transition == null) {
            transition = new AdinTransition(value, 120);
            SLIDERS.put(key, transition);
        }
        return transition;
    }

    /**
     * The pill toggle. The border and body both fade to the accent, and the thumb stretches along
     * its direction of travel and relaxes as it lands, which is what makes the flick read.
     */
    public static void drawToggle(Object key, float x, float y, float width, float height, boolean on, int accent) {
        AdinTransition transition = toggle(key, on);
        transition.set(on ? 1.0F : 0.0F);
        float raw = transition.value();
        float progress = Math.max(0.0F, Math.min(1.0F, raw));

        float radius = height / 2.0F;
        RoundedUtils.drawRound(x, y, width, height, radius,
                color(AdinTheme.lerp(AdinTheme.TOGGLE_OFF_BORDER, accent, progress)));
        RoundedUtils.drawRound(x + 1.0F, y + 1.0F, width - 2.0F, height - 2.0F, Math.max(0.0F, radius - 1.0F),
                color(AdinTheme.lerp(AdinTheme.TOGGLE_OFF, accent, progress)));

        float inset = Math.max(1.0F, Math.round(height / 6.0F));
        float size = Math.max(1.0F, height - 2.0F * inset);
        float stretch = 1.0F + THUMB_STRETCH * transition.flight();
        float wide = Math.max(1.0F, size * stretch);
        float tall = Math.max(1.0F, size / stretch);
        float travel = width - 2.0F * inset - size;
        float thumbX = x + inset + travel * progress - (wide - size) * 0.5F;
        float limit = x + width - inset - wide;
        thumbX = Math.max(x + inset, Math.min(limit, thumbX));

        RoundedUtils.drawRound(thumbX, y + inset + (size - tall) * 0.5F, wide, tall, tall / 2.0F,
                color(AdinTheme.lerp(AdinTheme.THUMB_OFF, AdinTheme.ON_ACCENT, progress)));
    }

    /**
     * The segmented picker: a dark plate with one lit slot that slides to the selected segment,
     * widening while it moves.
     *
     * @return the width of one segment, so the caller can hit test it
     */
    public static float drawSegmented(Object key, float x, float y, float width, float height,
                                      String[] labels, int selectedIndex, FontRenderer font) {
        float segmentWidth = width / Math.max(1, labels.length);
        RoundedUtils.drawRound(x, y, width, height, SEGMENT_RADIUS, color(AdinTheme.CONTROL));

        AdinTransition transition = segment(key, selectedIndex);
        transition.set(selectedIndex);
        float index = Math.max(0.0F, Math.min(labels.length - 1.0F, transition.value()));
        float wide = segmentWidth * (1.0F + SEGMENT_STRETCH * transition.flight());
        float slot = Math.max(x, Math.min(x + width - wide, x + segmentWidth * index - (wide - segmentWidth) * 0.5F));
        RoundedUtils.drawRound(slot, y, wide, height, SEGMENT_RADIUS, color(AdinTheme.CONTROL_ACTIVE));

        for (int i = 0; i < labels.length; i++) {
            String label = fit(labels[i], segmentWidth - 6.0F, font);
            float textWidth = width(font, label);
            float textX = x + segmentWidth * i + (segmentWidth - textWidth) * 0.5F;
            drawText(font, label, textX, y + (height - height(font)) * 0.5F,
                    i == selectedIndex ? AdinTheme.TEXT : AdinTheme.DIM);
        }
        return segmentWidth;
    }

    /**
     * The slider. The knob is drawn unconditionally rather than on hover, and the value sits in a
     * fixed column on the right - without both of those there is no way to read the setting.
     *
     * @param valueColumn width reserved on the right for the number, shared by every slider in the
     *                    panel so their tracks line up
     */
    public static void drawSlider(Object key, float x, float y, float width, float height,
                                  float fraction, String value, float valueColumn,
                                  int accent, FontRenderer font, boolean dragging) {
        AdinTransition transition = slider(key, fraction);
        if (dragging) {
            // Snap while the cursor owns the knob. Easing toward the value here is what makes a
            // drag feel like it is lagging behind the mouse instead of following it.
            transition.snap(fraction);
        } else {
            transition.set(fraction);
        }
        float shown = Math.max(0.0F, Math.min(1.0F, transition.value()));

        float trackWidth = Math.max(1.0F, width - valueColumn);
        float travel = Math.max(1.0F, trackWidth - KNOB_WIDTH);
        float centreY = y + height * 0.5F;
        float trackY = centreY - TRACK_HEIGHT * 0.5F;

        RoundedUtils.drawRound(x, trackY, trackWidth, TRACK_HEIGHT, TRACK_HEIGHT * 0.5F,
                color(AdinTheme.CONTROL));

        float knobX = x + travel * shown;
        float fillTo = knobX + KNOB_WIDTH * 0.5F;
        RoundedUtils.drawRound(x, trackY, Math.max(TRACK_HEIGHT, fillTo - x), TRACK_HEIGHT,
                TRACK_HEIGHT * 0.5F, color(accent));
        RoundedUtils.drawRound(knobX, centreY - KNOB_HEIGHT * 0.5F, KNOB_WIDTH, KNOB_HEIGHT,
                KNOB_RADIUS, color(accent));

        drawText(font, value, x + width - valueColumn + 6.0F, centreY - height(font) * 0.5F, AdinTheme.TEXT);
    }

    /** Where the cursor sits along a slider's track, 0 to 1. */
    public static float sliderFraction(float mouseX, float x, float width, float valueColumn) {
        float trackWidth = Math.max(1.0F, width - valueColumn);
        float travel = Math.max(1.0F, trackWidth - KNOB_WIDTH);
        return Math.max(0.0F, Math.min(1.0F, (mouseX - x - KNOB_WIDTH * 0.5F) / travel));
    }

    /** A framed value box, used for the bind row. */
    public static void drawField(float x, float y, float width, float height, String text,
                                 int textColor, FontRenderer font) {
        RoundedUtils.drawRound(x, y, width, height, 4.0F, color(AdinTheme.CONTROL));
        String fitted = fit(text, width - 6.0F, font);
        drawText(font, fitted, x + (width - width(font, fitted)) * 0.5F,
                y + (height - height(font)) * 0.5F, textColor);
    }

    // ---------------------------------------------------------------- text helpers

    /** Trims with an ellipsis so a long label never spills out of its control. */
    public static String fit(String text, float available, FontRenderer font) {
        if (text == null) {
            return "";
        }
        if (width(font, text) <= available) {
            return text;
        }
        String trimmed = text;
        while (trimmed.length() > 1 && width(font, trimmed + "..") > available) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + "..";
    }

    public static void drawText(FontRenderer font, String text, float x, float y, int color) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (font != null) {
            font.drawString(text, x, y, color);
        } else {
            Minecraft.getMinecraft().fontRendererObj.drawString(text, (int) x, (int) y, color);
        }
    }

    public static float width(FontRenderer font, String text) {
        if (text == null || text.isEmpty()) {
            return 0.0F;
        }
        return font != null
                ? FontManager.getStringWidth(font, text)
                : Minecraft.getMinecraft().fontRendererObj.getStringWidth(text);
    }

    public static float height(FontRenderer font) {
        return font != null
                ? FontManager.getHeight(font)
                : Minecraft.getMinecraft().fontRendererObj.FONT_HEIGHT;
    }

    public static Color color(int argb) {
        return new Color(argb, true);
    }

    /** Fades whatever is drawn inside to {@code alpha}; used for the staggered row reveal. */
    public static void scissor(double x, double y, double width, double height) {
        RenderUtil.scissor(x, y, width, height);
    }

    public static void releaseScissor() {
        RenderUtil.releaseScissor();
    }
}
