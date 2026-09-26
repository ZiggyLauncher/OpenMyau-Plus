package myau.ui.impl.clickgui.vape;

import myau.module.Module;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A module in a category frame ({@code ModuleComponent}): left click toggles it, right click
 * or the three dots open its settings underneath. An enabled module fills the row with the GUI
 * colour; hovered or opened ones go a shade lighter.
 */
final class ModuleRow extends VComponent {
    private static final long STATUS_TIMEOUT_MS = 2000L;

    final Module module;
    final VapeClickGui gui;
    final BindButton bind;
    final IconButton settings = new IconButton("settingdots");
    final List<VComponent> values = new ArrayList<VComponent>();
    boolean expanded;
    /** Rows in the search results show even when hidden from their category. */
    boolean alwaysShown;

    private String statusText;
    private long statusTime;
    private int statusStage;

    ModuleRow(VapeClickGui gui, Module module) {
        this.gui = gui;
        this.module = module;
        this.bind = new BindButton(module);
        this.background = VapeTheme.FRAME;
    }

    @Override
    boolean isShown() {
        return this.alwaysShown || this.gui.editingHidden || !this.gui.isHiddenInGui(this.module);
    }

    @Override
    double preferredHeight() {
        return 20.0;
    }

    boolean capturing() {
        return this.gui.binding == this;
    }

    void setStatusText(String text) {
        if (text == null) {
            this.setStatusStage(1);
            return;
        }
        this.statusText = text.toUpperCase(Locale.ROOT);
    }

    private void setStatusStage(int stage) {
        this.statusStage = stage;
        this.statusTime = System.currentTimeMillis();
    }

    @Override
    void update() {
        boolean capturing = this.capturing();
        if (capturing && (this.statusText == null || !this.statusText.toLowerCase(Locale.ROOT).startsWith("press"))) {
            this.setStatusText("press a key to bind");
        }
        if (this.statusStage != 0 && System.currentTimeMillis() > this.statusTime + STATUS_TIMEOUT_MS) {
            this.statusText = null;
            this.bind.highlighted = false;
            this.statusStage = 0;
        } else if (this.statusText != null && this.statusText.toLowerCase(Locale.ROOT).startsWith("press") && !capturing) {
            this.setStatusStage(1);
            this.setStatusText(this.bind.bindText().length() > 0 ? "bound to" : "bind removed");
        }
    }

    @Override
    void hoverChanged(boolean hovered) {
        if (!hovered && this.statusStage == 0 && this.statusText != null
                && !this.statusText.toLowerCase(Locale.ROOT).startsWith("press")
                && System.currentTimeMillis() > this.statusTime + STATUS_TIMEOUT_MS) {
            this.setStatusText(null);
        }
    }

    @Override
    void render(double mouseX, double mouseY) {
        boolean editing = this.gui.editingHidden;
        boolean capturing = this.capturing();
        boolean hiddenInGui = this.gui.isHiddenInGui(this.module);
        VapeFont font = font(0.9);
        String name = this.module.getName();
        double textY = this.y + this.height / 2.0 - font.height() / 2.0;
        double contentX = this.x + 6.0;
        Color background = this.background;
        Color textColor = VapeTheme.TEXT;
        if (this.module.isEnabled()) {
            background = VapeTheme.accent();
            textColor = VapeTheme.accentText();
        } else if (this.hovered || this.expanded || this.statusText != null) {
            background = VapeTheme.HOVER;
            textColor = VapeTheme.TEXT_BRIGHT;
        }

        this.bind.measure(capturing);
        double rightX = this.x + this.width - 10.0 - 8.0;
        if ((this.module.getKey() != 0 || this.hovered || capturing || this.expanded) && !editing) {
            rightX -= this.bind.width;
            this.bind.x = rightX;
            this.bind.y = this.y + 5.0;
            this.bind.visible = true;
        } else {
            this.bind.visible = false;
        }

        VapeRender.rect(this.x, this.y, this.width, this.height, background);
        if (editing) {
            // The edit-hidden-modules view: a check box on the left instead of the dots.
            double toggleWidth = 20.0;
            contentX += toggleWidth;
            VapeRender.rect(this.x, this.y, toggleWidth, this.height, VapeTheme.DARKEST);
            this.settings.visible = false;
            double inset = 7.0;
            double border = 0.5;
            double fill = border + 0.5;
            if (!hiddenInGui) {
                VapeRender.rect(this.x + inset, this.y + inset, toggleWidth - inset * 2.0, this.height - inset * 2.0, VapeTheme.accent());
                VapeRender.rect(this.x + inset + border, this.y + inset + border, toggleWidth - (inset + border) * 2.0,
                        this.height - (inset + border) * 2.0, VapeTheme.DARKEST);
                VapeRender.rect(this.x + inset + fill, this.y + inset + fill, toggleWidth - (inset + fill) * 2.0,
                        this.height - (inset + fill) * 2.0, VapeTheme.accent());
            } else {
                VapeRender.rect(this.x + inset, this.y + inset, toggleWidth - inset * 2.0, this.height - inset * 2.0, VapeTheme.OUTLINE);
                VapeRender.rect(this.x + inset + border, this.y + inset + border, toggleWidth - (inset + border) * 2.0,
                        this.height - (inset + border) * 2.0, VapeTheme.DARKEST);
            }
        } else {
            this.settings.visible = true;
            this.settings.overrideColor = this.module.isEnabled() ? textColor : null;
            this.settings.place(this.x + this.width - 5.0 - 8.0, this.y, this.height);
            this.settings.hovered = this.hovered && this.settings.contains(mouseX, mouseY);
        }

        if (this.statusText != null) {
            this.settings.visible = false;
            this.renderStatus(contentX, textY, textColor);
        } else {
            font.draw(name, contentX, textY, hiddenInGui ? VapeTheme.TEXT_DIM : textColor);
        }
        if (this.module.isEnabled()) {
            double offset = editing ? 20.0 : 0.0;
            VapeRender.rect(this.x + offset, this.y + this.height - 0.5, this.width - offset, 0.5, VapeTheme.OUTLINE);
        }

        double nameWidth = this.statusText != null ? font.width(this.statusText) + 3.0 : font.width(name);
        this.bind.maxLabelWidth = this.width - 20.0 - (this.settings.visible ? this.settings.width : 0.0) - nameWidth;
        this.bind.measure(capturing);
        if (this.statusText != null) {
            double preferred = this.x + this.width - 10.0 - 8.0 - this.bind.width;
            double minimum = this.x + 15.0 + font.width(this.statusText);
            double maximum = this.x + this.width - 5.0 - this.bind.width;
            this.bind.x = Math.min(maximum, Math.max(preferred, minimum));
        } else {
            this.bind.x = this.x + this.width - 10.0 - 8.0 - this.bind.width;
        }
        this.bind.hovered = this.hovered && this.bind.contains(mouseX, mouseY);
        this.bind.render(capturing);
        this.settings.render();
    }

    /** The "PRESS A KEY TO BIND" / "BOUND TO" flag over the row. */
    private void renderStatus(double textX, double textY, Color textColor) {
        VapeRender.pushScissor(this.x, this.y, this.width, this.height);
        try {
            VapeFont font = font(0.75);
            double boxWidth = font.width(this.statusText) + 10.0;
            VapeRender.rect(this.x, this.y, boxWidth, this.height, new Color(20, 20, 20, 255));
            int pointer = 7;
            double pointerX = this.x + boxWidth - 1.0;
            double bottom = this.y + this.height;
            double top = this.y - 4.0;
            VapeRender.triangle(pointerX + 1.0, bottom, pointerX + 1.0, top - 20.0, pointerX + pointer + 1.0, bottom,
                    new Color(16, 16, 16, 255));
            VapeRender.triangle(pointerX, bottom, pointerX, top, pointerX + pointer, bottom, new Color(20, 20, 20, 255));
            font.draw(this.statusText, textX, textY, textColor);
        } finally {
            VapeRender.popScissor();
        }
    }

    @Override
    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.gui.editingHidden) {
            if (button == 0 && mouseX <= this.x + 20.0) {
                this.gui.setHiddenInGui(this.module, !this.gui.isHiddenInGui(this.module));
            }
            return true;
        }
        if (this.bind.contains(mouseX, mouseY) && (button == 0 || button == 1)) {
            if (this.capturing()) {
                // Clicking the cross while listening clears the bind.
                this.module.setKey(0);
                this.gui.binding = null;
            } else {
                this.gui.binding = this;
            }
            return true;
        }
        if (this.settings.contains(mouseX, mouseY) && (button == 0 || button == 1)) {
            this.setExpanded(!this.expanded);
            return true;
        }
        if (button == 0) {
            this.module.toggle();
            return true;
        }
        if (button == 1) {
            this.setExpanded(!this.expanded);
            return true;
        }
        return false;
    }

    void setExpanded(boolean expanded) {
        this.expanded = expanded;
        this.gui.closePopups();
    }
}
