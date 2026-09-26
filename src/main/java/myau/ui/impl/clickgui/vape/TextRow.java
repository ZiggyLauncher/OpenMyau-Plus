package myau.ui.impl.clickgui.vape;

import myau.property.properties.TextProperty;
import net.minecraft.util.ChatAllowedCharacters;
import org.lwjgl.input.Keyboard;

/**
 * A free-text setting. Vape has no plain string rows in its module settings, so this pairs the
 * slider's label style with Vape's text box ({@code TextInputComponentBase}): a rounded outline
 * that brightens on hover over the darkest background, with a blinking bar while typing.
 */
final class TextRow extends ValueRow implements TextEditable {
    private final TextProperty value;
    private final VapeAnimation.Colour border = new VapeAnimation.Colour(0.15, VapeTheme.HOVER, VapeTheme.OUTLINE);
    private String editText;

    TextRow(VapeClickGui gui, TextProperty value, ModuleRow owner) {
        super(gui, value, owner);
        this.value = value;
    }

    @Override
    double preferredHeight() {
        return 30.0;
    }

    private double boxX() {
        return this.x + 5.0;
    }

    private double boxY() {
        return this.y + 12.5;
    }

    private double boxWidth() {
        return this.width - 10.0;
    }

    private static final double BOX_HEIGHT = 13.0;

    @Override
    void hoverChanged(boolean hovered) {
        this.border.toggle();
    }

    @Override
    void render(double mouseX, double mouseY) {
        this.fillBackground();
        VapeFont labelFont = font(0.75);
        labelFont.draw(this.label(), this.x + 5.0, this.y + 5.0, VapeTheme.TEXT);
        double boxX = this.boxX();
        double boxY = this.boxY();
        double boxWidth = this.boxWidth();
        VapeRender.border(boxX, boxY, boxWidth, BOX_HEIGHT, this.editText != null ? VapeTheme.OUTLINE : this.border.color(), 2.0F, 1.0F, 1.0F);
        VapeRender.rounded(boxX + 0.5, boxY + 0.5, boxWidth - 1.0, BOX_HEIGHT - 0.5, VapeTheme.DARKEST);

        VapeFont font = font(0.8);
        String text = this.editText != null ? this.editText : this.value.getValue();
        boolean placeholder = text.isEmpty() && this.editText == null;
        String shown = placeholder ? "Empty" : text;
        double textX = boxX + 4.0;
        double available = boxWidth - 8.0;
        double textWidth = font.width(shown);
        if (textWidth > available) {
            // Keep the end of the text (and the caret) in view.
            textX -= textWidth - available;
        }
        double textY = boxY + BOX_HEIGHT / 2.0 - font.height() / 2.0;
        VapeRender.pushScissor(boxX + 2.0, boxY, boxWidth - 4.0, BOX_HEIGHT);
        try {
            font.draw(shown, textX, textY, placeholder ? VapeTheme.TEXT_DIM : VapeTheme.TEXT_BRIGHT);
            if (this.editText != null && (System.currentTimeMillis() / 500L) % 2L == 0L) {
                VapeFont caret = font(1.0);
                caret.draw("|", textX + textWidth, boxY + BOX_HEIGHT / 2.0 - caret.height() / 2.0, VapeTheme.TEXT_BRIGHT);
            }
        } finally {
            VapeRender.popScissor();
        }
    }

    @Override
    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && VFrame.inside(mouseX, mouseY, this.boxX(), this.boxY(), this.boxWidth(), BOX_HEIGHT)) {
            this.editText = this.value.getValue();
            this.gui.focus(this);
            return true;
        }
        if (button == 1 && VFrame.inside(mouseX, mouseY, this.boxX(), this.boxY(), this.boxWidth(), BOX_HEIGHT)) {
            this.editText = "";
            this.gui.focus(this);
            return true;
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
        if (net.minecraft.client.gui.GuiScreen.isKeyComboCtrlV(keyCode)) {
            String clipboard = net.minecraft.client.gui.GuiScreen.getClipboardString();
            if (clipboard != null) {
                this.editText += ChatAllowedCharacters.filterAllowedCharacters(clipboard);
            }
            return true;
        }
        if (ChatAllowedCharacters.isAllowedCharacter(typedChar)) {
            this.editText += typedChar;
        }
        return true;
    }

    @Override
    public void focusLost() {
        if (this.editText != null && !this.editText.equals(this.value.getValue())) {
            this.value.setValue(this.editText);
        }
        this.editText = null;
    }
}
