package myau.ui.impl.clickgui.vape;

import java.awt.*;

/**
 * A clickable icon ({@code IconButtonComponent}): a 13x13 box by default with the image drawn
 * centred in it at {@code 8 * scale} units, grey until hovered.
 */
final class IconButton {
    String icon;
    double scale;
    /** When set, the image is drawn at its own size divided by this instead. */
    double divisor = -1.0;
    double x;
    double y;
    double width = 13.0;
    double height = 13.0;
    boolean visible = true;
    boolean hovered;
    Color overrideColor;

    IconButton(String icon, double scale) {
        this.icon = icon;
        this.scale = scale;
    }

    IconButton(String icon) {
        this(icon, 1.0);
    }

    void place(double x, double y, double height) {
        this.x = x;
        this.y = y;
        this.height = height;
    }

    boolean contains(double mouseX, double mouseY) {
        return this.visible && mouseX >= this.x && mouseX <= this.x + this.width
                && mouseY >= this.y && mouseY <= this.y + this.height;
    }

    void render() {
        if (!this.visible) {
            return;
        }
        double imageWidth;
        double imageHeight;
        if (this.divisor != -1.0) {
            imageWidth = VapeRender.imageWidth(this.icon) / this.divisor;
            imageHeight = VapeRender.imageHeight(this.icon) / this.divisor;
        } else {
            imageWidth = imageHeight = 8.0F * (float) this.scale;
        }
        Color color = this.overrideColor != null ? this.overrideColor
                : (this.hovered ? VapeTheme.TEXT_BRIGHT : VapeTheme.ICON);
        VapeRender.icon(this.icon, this.x + this.width / 2.0, this.y + this.height / 2.0, imageWidth, imageHeight, color);
    }
}
