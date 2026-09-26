package myau.ui.impl.clickgui.vape;

import myau.property.properties.ColorProperty;
import org.lwjgl.input.Mouse;

import java.awt.*;

/**
 * A colour setting ({@code ColorValueEditorComponent}): a rainbow hue track with a swatch of
 * the colour at the end; the arrow next to the name opens saturation and vibrance tracks under
 * it. Myau+ colours have no alpha, so the original's fourth (opacity) track is left out.
 */
final class ColorRow extends ValueRow {
    private static final int HUE = 0;
    private static final int SATURATION = 1;
    private static final int VIBRANCE = 2;
    private static final String[] NAMES = {null, "Saturation", "Vibrance"};

    private final ColorProperty value;
    private boolean collapsed = true;
    private final float[] hsb = new float[3];
    private int syncedRgb = -1;
    private int dragging = -1;
    private final VapeAnimation.Value[] handleSizes = {
            new VapeAnimation.Value(0.15, 7.0, 8.0), new VapeAnimation.Value(0.15, 7.0, 8.0),
            new VapeAnimation.Value(0.15, 7.0, 8.0)};
    private final boolean[] handleHovered = new boolean[3];

    ColorRow(VapeClickGui gui, ColorProperty value, ModuleRow owner) {
        super(gui, value, owner);
        this.value = value;
        this.sync();
    }

    @Override
    double preferredHeight() {
        return this.collapsed ? 25.0 : 25.0 * NAMES.length + 4.0;
    }

    /** Re-reads the colour when something other than this row changed it. */
    private void sync() {
        int rgb = this.value.getValue() & 0xFFFFFF;
        if (rgb != this.syncedRgb) {
            Color.RGBtoHSB(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, this.hsb);
            this.syncedRgb = rgb;
        }
    }

    private void write() {
        int rgb = Color.HSBtoRGB(this.hsb[0], this.hsb[1], this.hsb[2]) & 0xFFFFFF;
        this.syncedRgb = rgb;
        if (rgb != (this.value.getValue() & 0xFFFFFF)) {
            this.value.setValue(rgb);
        }
    }

    private int channelCount() {
        return this.collapsed ? 1 : NAMES.length;
    }

    private double channelY(int channel) {
        return this.y + channel * 25.0;
    }

    @Override
    void update() {
        this.sync();
        if (this.dragging >= 0) {
            if (!Mouse.isButtonDown(0)) {
                this.dragging = -1;
            } else {
                this.dragTo(this.dragging, VapeRender.mouseX());
            }
        }
    }

    private void dragTo(int channel, double mouseX) {
        double trackStart = this.x + 5.0 + 4.0;
        double trackEnd = this.x + this.width - 5.0 - 4.0;
        double fraction = (mouseX - trackStart) / Math.max(1.0, trackEnd - trackStart);
        fraction = Math.round(Math.max(0.0, Math.min(1.0, fraction)) * 255.0 * 1000.0) / 1000.0 / 255.0;
        this.hsb[channel] = (float) fraction;
        this.write();
    }

    private float[] stop(int channel, double ratio) {
        float value = (float) ratio;
        switch (channel) {
            case SATURATION:
                return new float[]{this.hsb[0], value, this.hsb[2], 1.0F};
            case VIBRANCE:
                return new float[]{this.hsb[0], this.hsb[1], value, 1.0F};
            default:
                return new float[]{value, 1.0F, 1.0F, 1.0F};
        }
    }

    @Override
    void render(double mouseX, double mouseY) {
        this.fillBackground();
        VapeFont font = font(0.75);
        String label = this.label();
        double labelWidth = font.width(label);
        Color color = new Color(this.value.getValue() & 0xFFFFFF);

        for (int channel = 0; channel < this.channelCount(); channel++) {
            double rowY = this.channelY(channel);
            boolean hovered = this.hovered && mouseY >= rowY && mouseY < rowY + 25.0;
            if (hovered != this.handleHovered[channel]) {
                this.handleHovered[channel] = hovered;
                this.handleSizes[channel].toggle();
            }
            String name = channel == HUE ? label : NAMES[channel];
            font.draw(name, this.x + 5.0, rowY + 5.0, VapeTheme.TEXT);
            double trackY = rowY + 12.5 + font.height();
            double usable = this.width - 5.0 * 2.0;
            double trackStart = this.x + 5.0;
            double handleCenterX = this.x + (this.width - 10.0 - 8.0) * this.hsb[channel] + 5.0 + 4.0;
            double padding = 4.0 + 0.5;
            double left = handleCenterX - trackStart - padding;
            if (left >= 2.0) {
                VapeRender.gradientCapsule(trackStart, trackY, left, 2.0, this.stop(channel, 0.0),
                        this.stop(channel, left / usable));
            }
            double rightOffset = handleCenterX - trackStart + padding;
            double right = usable - rightOffset;
            if (right >= 2.0) {
                VapeRender.gradientCapsule(trackStart + rightOffset, trackY, right, 2.0,
                        this.stop(channel, rightOffset / usable), this.stop(channel, 1.0));
            }
            double size = this.handleSizes[channel].value();
            VapeRender.circle(handleCenterX - size / 2.0, trackY + 0.5 - size / 2.0, size, 0.8 / VapeTheme.scale(),
                    VapeTheme.TEXT_BRIGHT);
        }

        // The arrow that opens the other tracks, and the colour swatch.
        double arrowX = this.x + labelWidth + 5.0;
        boolean arrowHovered = this.hovered && this.onArrow(mouseX, mouseY, labelWidth);
        double iconSize = 8.0 * 0.3;
        VapeRender.icon(this.collapsed ? "downexpand" : "upcollapse", arrowX + 5.0, this.y + 0.5 + 12.5 / 2.0,
                iconSize, iconSize, arrowHovered ? VapeTheme.TEXT_BRIGHT : VapeTheme.ICON);
        VapeRender.image(color, this.x + this.width - 5.0 - 6.0, this.y + 5.0, "colorpreview", 6.0, 6.0);
    }

    private boolean onArrow(double mouseX, double mouseY, double labelWidth) {
        double arrowX = this.x + labelWidth + 5.0;
        return mouseX >= arrowX && mouseX <= arrowX + 10.0 && mouseY >= this.y + 0.5 && mouseY <= this.y + 0.5 + 12.5;
    }

    @Override
    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        if (this.onArrow(mouseX, mouseY, font(0.75).width(this.label()))) {
            this.collapsed = !this.collapsed;
            return true;
        }
        for (int channel = 0; channel < this.channelCount(); channel++) {
            double trackY = this.channelY(channel) + 12.5 + font(0.75).height();
            double handleY = trackY + 0.5;
            if (mouseY >= handleY - 4.0 && mouseY <= handleY + 4.0) {
                this.dragging = channel;
                this.dragTo(channel, mouseX);
                return true;
            }
        }
        return true;
    }
}
