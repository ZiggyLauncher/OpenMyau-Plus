package myau.ui.impl.clickgui.vape;

import myau.property.Property;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.LongProperty;
import myau.property.properties.PercentProperty;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;

/**
 * A number setting ({@code NumberSliderComponent}): the name and value above a thin track
 * that fills with the GUI colour up to a round handle. The value itself can be clicked and
 * typed in.
 */
final class SliderRow extends ValueRow implements TextEditable {
    private static final double VALUE_WIDTH = 15.0;

    private final VapeAnimation.Value handleSize = new VapeAnimation.Value(0.15, 7.0, 8.0);
    private final VapeAnimation.Colour underline = new VapeAnimation.Colour(0.15,
            VapeTheme.withAlpha(VapeTheme.OUTLINE, 0), VapeTheme.withAlpha(VapeTheme.OUTLINE, 100));
    private VapeAnimation.Value handlePosition = new VapeAnimation.Value(0.0, 0.0, 0.0);
    private double handleTarget = Double.NaN;
    private double handleCenterY;
    private boolean dragging;
    private boolean valueHovered;
    private String editText;
    private final DecimalFormat format;
    private final String suffix;

    SliderRow(VapeClickGui gui, Property<?> property, ModuleRow owner) {
        super(gui, property, owner);
        this.format = new DecimalFormat(property instanceof FloatProperty ? "#.##" : "#");
        this.format.setMinimumIntegerDigits(1);
        this.suffix = property instanceof PercentProperty ? " %" : "";
    }

    static boolean handles(Property<?> property) {
        return property instanceof IntProperty || property instanceof FloatProperty
                || property instanceof PercentProperty || property instanceof LongProperty;
    }

    @Override
    double preferredHeight() {
        return 25.0;
    }

    // ------------------------------------------------------------------ value access

    private double minimum() {
        if (this.property instanceof FloatProperty) {
            return ((FloatProperty) this.property).getMinimum();
        }
        if (this.property instanceof IntProperty) {
            return ((IntProperty) this.property).getMinimum();
        }
        if (this.property instanceof PercentProperty) {
            return ((PercentProperty) this.property).getMinimum();
        }
        return ((LongProperty) this.property).getMinimum();
    }

    private double maximum() {
        if (this.property instanceof FloatProperty) {
            return ((FloatProperty) this.property).getMaximum();
        }
        if (this.property instanceof IntProperty) {
            return ((IntProperty) this.property).getMaximum();
        }
        if (this.property instanceof PercentProperty) {
            return ((PercentProperty) this.property).getMaximum();
        }
        return ((LongProperty) this.property).getMaximum();
    }

    private double current() {
        return ((Number) this.property.getValue()).doubleValue();
    }

    private void set(double value) {
        value = Math.max(this.minimum(), Math.min(this.maximum(), value));
        if (this.property instanceof FloatProperty) {
            float rounded = new BigDecimal(Double.toString(value)).setScale(2, RoundingMode.HALF_UP).floatValue();
            if (rounded != (Float) this.property.getValue()) {
                ((FloatProperty) this.property).setValue(Math.max(((FloatProperty) this.property).getMinimum(),
                        Math.min(((FloatProperty) this.property).getMaximum(), rounded)));
            }
        } else if (this.property instanceof LongProperty) {
            long rounded = Math.round(value);
            if (rounded != (Long) this.property.getValue()) {
                ((LongProperty) this.property).setValue(rounded);
            }
        } else {
            int rounded = (int) Math.round(value);
            if (rounded != (Integer) this.property.getValue()) {
                this.property.setValue(rounded);
            }
        }
    }

    private String formatted() {
        return this.format.format(this.current());
    }

    // ------------------------------------------------------------------ drawing

    private double handleOffset() {
        double trackWidth = this.width - (5.0 + 5.0);
        double span = this.maximum() - this.minimum();
        double normalized = span <= 0.0 ? 0.0 : (this.current() - this.minimum()) / span;
        normalized = Math.max(0.0, Math.min(1.0, normalized));
        return (trackWidth - this.handleSize.end()) * normalized + 5.0 + this.handleSize.end() / 2.0;
    }

    private void updateHandle() {
        double target = this.handleOffset();
        if (Double.isNaN(this.handleTarget)) {
            this.handlePosition = new VapeAnimation.Value(0.0, target, target);
            this.handlePosition.snapToEnd();
        } else if (target != this.handleTarget) {
            this.handlePosition = new VapeAnimation.Value(0.05, this.handlePosition.value(), target);
            this.handlePosition.forward();
        }
        this.handleTarget = target;
    }

    @Override
    void update() {
        if (this.dragging) {
            if (!Mouse.isButtonDown(0)) {
                this.dragging = false;
            } else {
                this.dragTo(VapeRender.mouseX());
            }
        }
    }

    private void dragTo(double mouseX) {
        double trackStart = this.x + 5.0 + 4.0;
        double trackEnd = this.x + this.width - 5.0 - 4.0;
        double offset = mouseX - this.x - 5.0 - 4.0;
        double span = trackEnd - trackStart;
        double value = span <= 0.0 ? this.minimum() : this.minimum() + offset / span * (this.maximum() - this.minimum());
        this.set(value);
    }

    @Override
    void hoverChanged(boolean hovered) {
        this.handleSize.toggle();
    }

    @Override
    void render(double mouseX, double mouseY) {
        this.updateHandle();
        this.fillBackground();
        VapeFont font = font(0.75);
        double labelHeight = font.height();
        double size = this.handleSize.value();
        double trackCenterY = this.y + 12.5 + labelHeight;
        double handleCenterX = this.x + this.handlePosition.value();
        font.draw(this.label(), this.x + 5.0, this.y + 5.0, VapeTheme.TEXT);
        this.renderValue(font, mouseX, mouseY);

        double handleLeft = handleCenterX - this.handleSize.end() / 2.0;
        this.handleCenterY = trackCenterY + 0.5;
        double filled = handleLeft - this.x - 5.0;
        double remaining = this.x + this.width - handleLeft - 5.0;
        double trackY = trackCenterY + 0.5 - 1.0;
        if (filled - 0.5 >= 2.0) {
            VapeRender.capsule(this.x + 5.0, trackY, filled - 0.5, 2.0, VapeTheme.accent());
        }
        if (remaining - 8.5 >= 2.0) {
            VapeRender.capsule(handleLeft + 8.5, trackY, remaining - 8.5, 2.0, VapeTheme.OUTLINE);
        }
        VapeRender.circle(handleCenterX - size / 2.0, trackCenterY + 0.5 - size / 2.0, size, 0.8 / VapeTheme.scale(),
                VapeTheme.accent());
    }

    private void renderValue(VapeFont font, double mouseX, double mouseY) {
        double valueX = this.x + this.width - 5.0 - VALUE_WIDTH;
        double valueY = this.y + 5.0;
        boolean hovered = this.hovered && inValue(mouseX, mouseY, valueX, valueY);
        if (hovered != this.valueHovered) {
            this.valueHovered = hovered;
            this.underline.toggle();
        }
        String text;
        if (this.editText != null) {
            text = this.editText;
        } else if (hovered || this.suffix.length() <= 1) {
            text = this.formatted();
        } else {
            text = this.formatted() + " " + this.suffix;
        }
        font.draw(text, valueX + (VALUE_WIDTH - font.width(text)), valueY, VapeTheme.TEXT);
        if (this.editText != null && (System.currentTimeMillis() / 500L) % 2L == 0L) {
            VapeFont caret = font(1.2);
            caret.draw("|", valueX + VALUE_WIDTH, valueY + font.height() / 2.0 - caret.height() / 2.0, VapeTheme.TEXT_BRIGHT);
        }
        VapeRender.rect(valueX, valueY + 5.0 + 2.0, VALUE_WIDTH, 1.0,
                this.editText != null ? VapeTheme.withAlpha(VapeTheme.OUTLINE, 100) : this.underline.color());
    }

    private static boolean inValue(double mouseX, double mouseY, double valueX, double valueY) {
        return mouseX >= valueX && mouseX <= valueX + VALUE_WIDTH && mouseY >= valueY && mouseY <= valueY + 6.0 + 2.0;
    }

    // ------------------------------------------------------------------ input

    @Override
    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        double valueX = this.x + this.width - 5.0 - VALUE_WIDTH;
        if (inValue(mouseX, mouseY, valueX, this.y + 5.0)) {
            this.editText = "";
            this.gui.focus(this);
            return true;
        }
        double half = this.handleSize.end() / 2.0;
        if (mouseY >= this.handleCenterY - half && mouseY <= this.handleCenterY + half) {
            this.dragging = true;
            this.dragTo(mouseX);
        }
        return true;
    }

    @Override
    public boolean editKey(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            this.gui.focus(null);
            return true;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            this.editText = null;
            this.gui.focus(null);
            return true;
        }
        if (keyCode == Keyboard.KEY_BACK) {
            if (!this.editText.isEmpty()) {
                this.editText = this.editText.substring(0, this.editText.length() - 1);
            }
            return true;
        }
        if (Character.isDigit(typedChar) || typedChar == '.' || typedChar == ',' || typedChar == '-') {
            if (this.editText.length() < 12) {
                this.editText += typedChar == ',' ? '.' : typedChar;
            }
        }
        return true;
    }

    @Override
    public void focusLost() {
        if (this.editText != null && !this.editText.isEmpty()) {
            try {
                this.set(Double.parseDouble(this.editText));
            } catch (NumberFormatException ignored) {
            }
        }
        this.editText = null;
    }
}
