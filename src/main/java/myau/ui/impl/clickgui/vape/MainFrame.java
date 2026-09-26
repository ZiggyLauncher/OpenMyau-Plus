package myau.ui.impl.clickgui.vape;

/**
 * The "GUI" frame ({@code ClientSettingsSearchFrame}): the logo and settings gear on top, then
 * a button per category that opens that category's frame, and the MISC section.
 */
final class MainFrame extends VFrame {
    MainFrame(VapeClickGui gui) {
        super(gui);
        this.background = VapeTheme.DARKEST;
        this.x = 32.0;
        this.y = 32.0;
        this.visible = true;
        this.positioned = true;
        this.setHeader(new MainHeader());
    }

    @Override
    String name() {
        return "GUI";
    }

    /** {@code ClientSettingsSearchFrameHeader}. */
    private final class MainHeader extends VFrame.Header {
        private final IconButton settings = this.icon(new IconButton("newsettings", 0.9));

        @Override
        boolean collapsesOnRightClick() {
            return true;
        }

        @Override
        void render(double mouseX, double mouseY) {
            double right = this.x + this.width - 7.5 - 8.0;
            float divisor = 6.5F;
            float logoWidth = (float) VapeRender.imageWidth("vapelogo") / divisor;
            float logoHeight = (float) VapeRender.imageHeight("vapelogo") / divisor;
            float versionWidth = (float) VapeRender.imageWidth("v4") / divisor;
            float versionHeight = (float) VapeRender.imageHeight("v4") / divisor;
            VapeRender.image(java.awt.Color.WHITE, this.x + 6.0F, this.y + 5.0F, "vapelogo", logoWidth, logoHeight);
            VapeRender.image(VapeTheme.accent(), this.x + 6.0F + 27.0F, this.y + 5.0F, "v4", versionWidth, versionHeight);
            this.settings.overrideColor = null;
            this.settings.place(right, this.y, this.height);
            this.renderIcons();
        }

        @Override
        boolean iconClicked(IconButton button, int mouseButton) {
            if (button == this.settings && mouseButton == 0) {
                MainFrame.this.gui.openSettings();
            }
            return true;
        }
    }
}
