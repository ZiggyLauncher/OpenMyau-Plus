package myau.ui.impl.clickgui.vape;

/**
 * The page behind the main frame's gear ({@code ClientSettingsFrame}): it takes the main
 * frame's place, with a back arrow and a close cross, and lists the ClickGUI module's options.
 */
final class SettingsFrame extends VFrame {
    SettingsFrame(VapeClickGui gui) {
        super(gui);
        this.background = VapeTheme.DARKEST;
        this.positioned = true;
        this.setHeader(new SettingsHeader());
    }

    @Override
    String name() {
        return "Settings";
    }

    /** {@code SettingsFrameHeaderComponent}. */
    private final class SettingsHeader extends VFrame.Header {
        private final IconButton back = this.icon(new IconButton("moduleback"));
        private final IconButton close = this.icon(new IconButton("newclose", 1.2));

        SettingsHeader() {
            this.back.divisor = 3.5;
            this.close.divisor = 3.5 / 0.7;
        }

        @Override
        void render(double mouseX, double mouseY) {
            VapeFont font = font(0.9);
            double textY = this.y + this.height / 2.0 - font.height() / 2.0 + 1.0;
            font.draw("Settings", this.x + 10.0 + 8.0, textY, VapeTheme.TEXT_BRIGHT);
            this.back.place(this.x + 5.0 - 2.0, this.y + 1.0, this.height);
            this.close.place(this.x + this.width - 13.0, this.y + 1.0, this.height);
            this.renderIcons();
        }

        @Override
        boolean iconClicked(IconButton button, int mouseButton) {
            if (mouseButton == 0 && (button == this.back || button == this.close)) {
                SettingsFrame.this.gui.closeSettings();
            }
            return true;
        }
    }
}
