package myau.ui.impl.clickgui.vape;

import myau.property.properties.ModeProperty;

import java.awt.*;

/**
 * A mode setting ({@code DropdownSelectComponent}): "Name - Mode" in a rounded box; clicking
 * it drops the modes down in a panel that the box's outline grows around.
 */
final class DropdownRow extends ValueRow {
    private static final double OPTION_HEIGHT = 12.0;
    private static final double MAX_POPUP_HEIGHT = 120.0;
    private static final float ARROW_SIZE = 2.0F;

    private final ModeProperty value;
    private final VapeAnimation.Colour border = new VapeAnimation.Colour(0.15, VapeTheme.OUTLINE, VapeTheme.BORDER_HOVER);
    private boolean pressed;
    private double popupScroll;

    DropdownRow(VapeClickGui gui, ModeProperty value, ModuleRow owner) {
        super(gui, value, owner);
        this.value = value;
    }

    @Override
    double preferredHeight() {
        return 20.0;
    }

    boolean isOpen() {
        return this.gui.openDropdown == this;
    }

    @Override
    void update() {
        if (this.pressed && !this.hovered && !this.isOpen()) {
            this.border.toggle();
            this.pressed = false;
        }
    }

    @Override
    void hoverChanged(boolean hovered) {
        if (hovered) {
            if (!this.pressed) {
                this.border.toggle();
            }
            this.pressed = true;
        }
    }

    // ------------------------------------------------------------------ popup geometry

    double popupX() {
        return this.x + 5.0;
    }

    double popupY() {
        return this.y + 17.0;
    }

    double popupWidth() {
        return this.width - 5.0 - 8.0 + 2.0;
    }

    private double contentHeight() {
        return this.value.getModes().length * OPTION_HEIGHT + 0.5;
    }

    double popupHeight() {
        return Math.min(MAX_POPUP_HEIGHT, this.contentHeight());
    }

    boolean popupContains(double mouseX, double mouseY) {
        return mouseX >= this.popupX() && mouseX <= this.popupX() + this.popupWidth()
                && mouseY >= this.popupY() && mouseY <= this.popupY() + this.popupHeight();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    void render(double mouseX, double mouseY) {
        this.fillBackground();
        this.renderBox();
    }

    private void renderBox() {
        VapeFont font = font(0.85);
        String modes = this.value.getModeString();
        String text = this.label() + " - " + modes;
        double textY = this.y + this.height / 2.0 - font.height() / 2.0 + 5.0F / 8.0F;
        double arrowY = this.y + this.height / 2.0 - ARROW_SIZE / 2.0F;
        double boxHeight = this.height - 5.0;
        if (this.isOpen()) {
            boxHeight += this.popupHeight();
        }
        double boxX = this.x + 5.0;
        double boxWidth = this.width - 5.0 - 8.0 + 2.0;
        VapeRender.rounded(boxX, this.y + 5.0F / 2.0F + 0.5, boxWidth, boxHeight - 1.0, VapeTheme.FRAME, false, 2.0F, 1.0F);
        VapeRender.border(boxX, this.y + 5.0F / 2.0F, boxWidth, boxHeight - 1.0, this.border.color(), 3.0F, 0.75F, 1.0F);
        font.draw(text, this.x + 5.0 + 5.0, textY, VapeTheme.TEXT);
        VapeRender.image(VapeTheme.ICON, this.x + this.width - 5.0F * 3.0F, arrowY,
                this.isOpen() ? "upcollapse" : "downexpand", ARROW_SIZE, ARROW_SIZE);
    }

    /**
     * The open dropdown, drawn again above every frame (without its row background, as the
     * original does for its active component) with the option list inside it.
     */
    void renderPopup(double mouseX, double mouseY) {
        this.renderBox();
        double x = this.popupX();
        double y = this.popupY();
        double width = this.popupWidth();
        double height = this.popupHeight();
        VapeRender.rounded(x, y, width, height, VapeTheme.POPUP, false, 3.0F, 1.0F, 8.0F, VapeTheme.SHADOW, 15);
        VapeRender.pushScissor(x, y, width, height);
        try {
            VapeFont font = font(0.85);
            String[] modes = this.value.getModes();
            for (int i = 0; i < modes.length; i++) {
                double optionY = y + i * OPTION_HEIGHT + this.popupScroll;
                if (optionY + OPTION_HEIGHT < y || optionY > y + height) {
                    continue;
                }
                boolean hovered = mouseX >= x && mouseX <= x + width - 1.0
                        && mouseY >= optionY && mouseY < optionY + OPTION_HEIGHT && this.popupContains(mouseX, mouseY);
                if (hovered) {
                    VapeRender.rect(x + 0.5, optionY, width - 1.0 - 0.5, OPTION_HEIGHT, VapeTheme.HOVER);
                }
                double textY = optionY + OPTION_HEIGHT / 2.0 - font.height() / 2.0;
                font.draw(modes[i], x + 5.0, textY, hovered ? VapeTheme.TEXT_BRIGHT : VapeTheme.TEXT);
            }
        } finally {
            VapeRender.popScissor();
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 && button != 1) {
            return false;
        }
        this.gui.setOpenDropdown(this.isOpen() ? null : this);
        this.popupScroll = 0.0;
        return true;
    }

    /** A click while the list is open: pick an option, or close it. */
    void popupClicked(double mouseX, double mouseY) {
        if (this.popupContains(mouseX, mouseY)) {
            int index = (int) ((mouseY - this.popupY() - this.popupScroll) / OPTION_HEIGHT);
            if (index >= 0 && index < this.value.getModes().length) {
                this.value.setValue(index);
            }
        }
        this.gui.setOpenDropdown(null);
    }

    void popupScrolled(int delta) {
        double limit = -(this.contentHeight() - this.popupHeight());
        this.popupScroll = Math.max(limit, Math.min(0.0, this.popupScroll + (double) (delta / 15)));
    }
}
