package myau.ui.impl.clickgui.vape;

import myau.property.properties.BooleanProperty;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * An on/off setting ({@code BooleanToggleComponent}): the name on the left, a small switch on
 * the right whose knob slides across and whose track takes the GUI colour when on.
 */
final class ToggleRow extends ValueRow {
    private final BooleanProperty value;
    private final VapeAnimation.Colour hoverColor = new VapeAnimation.Colour(0.15, VapeTheme.SWITCH_OFF, VapeTheme.ICON);
    private final VapeAnimation.Colour enabledColor = new VapeAnimation.Colour(0.15, VapeTheme.ICON, VapeTheme.accent());
    private final VapeAnimation.Value knob = new VapeAnimation.Value(0.15, 0.0, 5.0);
    private List<String> lines = new ArrayList<String>();
    private String wrappedFor = "";
    private double wrappedWidth = -1.0;

    ToggleRow(VapeClickGui gui, BooleanProperty value, ModuleRow owner) {
        super(gui, value, owner);
        this.value = value;
        if (value.getValue()) {
            this.enabledColor.snapToEnd();
            this.knob.snapToEnd();
        } else {
            this.enabledColor.snapToStart();
            this.knob.snapToStart();
        }
    }

    private boolean animatedOn() {
        return this.knob.isOn();
    }

    private boolean animating() {
        double progress = this.knob.progress();
        return progress != 0.0 && progress != 100.0;
    }

    @Override
    double preferredHeight() {
        VapeFont font = font(0.9);
        return 15.0 + (this.wrap(font).size() - 1) * font.height();
    }

    private List<String> wrap(VapeFont font) {
        String label = this.label();
        if (label.equals(this.wrappedFor) && this.width == this.wrappedWidth) {
            return this.lines;
        }
        double available = this.width - 20.0;
        List<String> result = new ArrayList<String>();
        double lineWidth = 0.0;
        StringBuilder line = new StringBuilder();
        for (String word : label.split(" ")) {
            double next = lineWidth + font.width(word + " ");
            if (next > available) {
                lineWidth = 0.0;
                result.add(line.toString());
                line = new StringBuilder(word + " ");
                // The original measures the new line from zero, not from this word.
                continue;
            }
            lineWidth = next;
            line.append(word).append(' ');
        }
        result.add(line.toString());
        this.lines = result;
        this.wrappedFor = label;
        this.wrappedWidth = this.width;
        return result;
    }

    @Override
    void update() {
        this.enabledColor.setTo(VapeTheme.accent());
        if (this.value.getValue() != this.animatedOn() && !this.animating()) {
            this.flip();
        }
    }

    private void flip() {
        this.enabledColor.toggle();
        this.knob.toggle();
    }

    @Override
    void hoverChanged(boolean hovered) {
        this.hoverColor.toggle();
    }

    @Override
    void render(double mouseX, double mouseY) {
        this.fillBackground();
        VapeFont font = font(0.9);
        List<String> lines = this.wrap(font);
        double total = font.height() * lines.size();
        double lineY = this.y + this.height / 2.0 - total / 2.0;
        for (String line : lines) {
            if (!line.equals(" ")) {
                font.draw(line, this.x + 5.0, lineY, VapeTheme.TEXT);
            }
            lineY += total / lines.size();
        }
        double switchX = this.x + this.width - 10.0 - 5.0;
        double switchY = this.y + this.height / 2.0 - 3.0;
        Color track = this.enabledColor.progress() > 0.0 ? this.enabledColor.color() : this.hoverColor.color();
        VapeRender.capsule(switchX - 1.0, switchY - 0.5, 12.5, 7.0, track);
        VapeRender.circle(switchX + 1.0 + this.knob.value(), switchY + 1.0, 4.0, 0.8 / VapeTheme.scale(), VapeTheme.FRAME);
    }

    @Override
    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        boolean next = !this.value.getValue();
        if (this.value.setValue(next)) {
            this.flip();
        }
        return true;
    }
}
