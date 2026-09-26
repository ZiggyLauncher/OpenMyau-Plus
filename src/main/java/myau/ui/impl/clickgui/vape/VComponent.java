package myau.ui.impl.clickgui.vape;

import java.awt.*;

/**
 * One row or control inside a frame (Vape's {@code GuiComponent}). Frames stack their
 * components top to bottom at the frame's width; {@link #preferredHeight()} is how tall each
 * one asks to be.
 */
abstract class VComponent {
    VFrame frame;
    double x;
    double y;
    double width = 110.0;
    double height;
    /** Vape's "disabled overlay colour": the row's background. */
    Color background = VapeTheme.FRAME;
    boolean hovered;
    private boolean wasHovered;

    /** Whether the component takes part in layout at all. */
    boolean isShown() {
        return true;
    }

    abstract double preferredHeight();

    /** Per-frame state update ({@code u()}), before drawing. */
    void update() {
    }

    abstract void render(double mouseX, double mouseY);

    /** Called when a click lands on this component; returns whether it used it. */
    boolean mouseClicked(double mouseX, double mouseY, int button) {
        return false;
    }

    void mouseReleased(double mouseX, double mouseY, int button) {
    }

    boolean keyTyped(char typedChar, int keyCode) {
        return false;
    }

    boolean contains(double mouseX, double mouseY) {
        return mouseX >= this.x && mouseX <= this.x + this.width && mouseY >= this.y && mouseY <= this.y + this.height;
    }

    /** Fills the row with its background ({@code onDisable()}). */
    void fillBackground() {
        VapeRender.rect(this.x, this.y, this.width, this.height, this.background);
    }

    /** Hover changes, delivered once each (Vape's {@code F()} on entry and the check in {@code u()}). */
    void hoverChanged(boolean hovered) {
    }

    final void setHovered(boolean hovered) {
        this.hovered = hovered;
        if (hovered != this.wasHovered) {
            this.wasHovered = hovered;
            this.hoverChanged(hovered);
        }
    }

    static VapeFont font(double scale) {
        return VapeFont.get(scale);
    }
}
