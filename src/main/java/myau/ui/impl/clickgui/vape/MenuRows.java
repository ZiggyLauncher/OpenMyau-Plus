package myau.ui.impl.clickgui.vape;

import java.awt.*;

/** The plain rows of the main frame: dividers, spacers, section labels and frame buttons. */
final class MenuRows {
    private MenuRows() {
    }

    /** {@code ColorDividerComponent}: a half-unit line. */
    static final class Divider extends VComponent {
        private final Color color;

        Divider(Color color) {
            this.color = color;
        }

        @Override
        double preferredHeight() {
            return 0.5;
        }

        @Override
        void render(double mouseX, double mouseY) {
            VapeRender.rect(this.x, this.y, this.width, this.height, this.color);
        }
    }

    /** {@code SpacerComponent}. */
    static final class Spacer extends VComponent {
        private final double size;

        Spacer(double size) {
            this.size = size;
        }

        @Override
        double preferredHeight() {
            return this.size;
        }

        @Override
        void render(double mouseX, double mouseY) {
        }
    }

    /** {@code SimpleTextLabelComponent}: small dim text, e.g. the "MISC" heading. */
    static final class Label extends VComponent {
        private final String text;
        private final double scale;

        Label(String text, double scale) {
            this.text = text;
            this.scale = scale;
        }

        @Override
        double preferredHeight() {
            return font(this.scale).height();
        }

        @Override
        void render(double mouseX, double mouseY) {
            VapeFont font = font(this.scale);
            font.draw(this.text, this.x + 5.0, this.y + this.height / 2.0 - font.height() / 2.0, VapeTheme.TEXT_DIM);
        }
    }

    /**
     * {@code FrameNavigationButtonComponent}: opens or closes another frame. Its name turns the
     * GUI colour and the arrow slides out while that frame is open.
     */
    static final class NavButton extends VComponent {
        private final String name;
        private final String icon;
        private final int iconOffset;
        private final VFrame target;
        private final Runnable action;
        private final VapeAnimation.Value arrow = new VapeAnimation.Value(0.15, 0.0, 3.0);
        private boolean open;

        NavButton(String name, String icon, int iconOffset, VFrame target, Runnable action) {
            this.name = name;
            this.icon = icon;
            this.iconOffset = iconOffset;
            this.target = target;
            this.action = action;
            this.syncImmediately();
        }

        private void syncImmediately() {
            if (this.target != null && this.target.visible) {
                this.arrow.snapToEnd();
                this.open = true;
            }
        }

        @Override
        double preferredHeight() {
            return 20.0;
        }

        @Override
        void update() {
            if (this.target == null) {
                return;
            }
            if (this.target.visible) {
                if (!this.arrow.isOn()) {
                    this.arrow.forward();
                    this.open = true;
                }
            } else if (this.arrow.isOn()) {
                this.arrow.backward();
                this.open = false;
            }
        }

        @Override
        void render(double mouseX, double mouseY) {
            VapeFont font = font(0.9);
            double textY = this.y + this.height / 2.0 - font.height() / 2.0;
            float arrowSize = 4.0F;
            double arrowY = this.y + this.height / 2.0 - arrowSize / 2.0F;
            Color background = VapeTheme.FRAME;
            Color text = VapeTheme.TEXT;
            if (this.hovered) {
                background = VapeTheme.HOVER;
                text = VapeTheme.TEXT_BRIGHT;
            }
            if (this.open) {
                background = VapeTheme.HOVER;
                text = VapeTheme.accent();
                if (this.hovered) {
                    text = VapeTheme.offset(text, 30.0);
                }
            }
            VapeRender.rect(this.x, this.y, this.width, this.height, background);
            VapeRender.image(VapeTheme.ICON, this.x + this.width - 5.0 - 5.0 + this.arrow.value(), arrowY,
                    "expandarrow", arrowSize, arrowSize);
            if (this.icon != null) {
                float iconGap = 6.72F;
                font.draw(this.name, this.x + 8.0 + iconGap + 5.0, (int) textY, text);
                int iconWidth = (int) (VapeRender.imageWidth(this.icon) / 3.1F);
                int iconHeight = (int) (VapeRender.imageHeight(this.icon) / 3.1F);
                int iconY = (int) (textY + font.height() / 2.0 - iconHeight / 2.0F) + this.iconOffset;
                VapeRender.image(text, (int) this.x + 8, iconY, this.icon, iconWidth, iconHeight);
            } else {
                font.draw(this.name, this.x + 8.0, textY, text);
            }
        }

        @Override
        boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0) {
                return false;
            }
            this.open = !this.open;
            this.arrow.toggle();
            this.action.run();
            return true;
        }
    }
}
