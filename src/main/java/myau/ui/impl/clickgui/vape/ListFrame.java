package myau.ui.impl.clickgui.vape;

import net.minecraft.util.ChatAllowedCharacters;
import org.lwjgl.input.Keyboard;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * The list editor ({@code ListValueDropdownLayer}/{@code ListValueOptionsPanel}): an "Add
 * entry..." box over the list's entries, each with a coloured mark and a cross to remove it.
 * Opened from an item list setting it sits beside that setting's frame; opened from the main
 * frame (friends, profiles) it is a normal window.
 */
final class ListFrame extends VFrame {
    final ListSource source;
    /** The setting this editor belongs to, or null for a standalone window. */
    final ListRow anchor;
    private final String name;
    private final AddEntryRow input;
    private List<String> shownEntries = new ArrayList<String>();

    ListFrame(VapeClickGui gui, ListSource source, ListRow anchor, String name) {
        super(gui);
        this.source = source;
        this.anchor = anchor;
        this.name = name;
        this.background = VapeTheme.FRAME;
        this.draggable = anchor == null;
        this.setHeader(new ListHeader());
        this.input = new AddEntryRow();
        this.add(this.input);
        this.refresh();
    }

    @Override
    String name() {
        return this.name;
    }

    /** Rebuilds the entry rows when the list changed. */
    void refresh() {
        List<String> entries = this.source.entries();
        if (entries.equals(this.shownEntries) && this.components.size() == entries.size() + 1) {
            return;
        }
        this.shownEntries = new ArrayList<String>(entries);
        this.components.clear();
        this.add(this.input);
        for (String entry : entries) {
            this.add(new EntryRow(entry));
        }
    }

    @Override
    void update(double mouseX, double mouseY) {
        this.refresh();
        if (this.anchor != null) {
            this.follow();
        }
        super.update(mouseX, mouseY);
    }

    /** Keeps the editor next to its setting ({@code FloatingValueDropdownLayer.updatePosition}). */
    void follow() {
        VFrame owner = this.anchor.frame;
        double preferredX = owner.x + owner.width + 1.0;
        double targetY = this.anchor.y;
        if (owner.scrolling) {
            targetY = Math.min(targetY, owner.y + owner.maxHeight - this.anchor.height);
            targetY = Math.max(targetY, owner.y + (owner.header != null ? owner.header.height : 0.0));
        }
        if (preferredX + this.width > VapeRender.screenWidth()) {
            this.x = Math.floor(owner.x - this.width - 1.0);
        } else {
            this.x = Math.floor(preferredX);
        }
        this.y = Math.floor(targetY);
    }

    private final class ListHeader extends VFrame.Header {
        private final IconButton close;

        ListHeader() {
            this.close = this.icon(new IconButton("newclose", 0.7));
        }

        @Override
        void render(double mouseX, double mouseY) {
            VapeFont font = font(0.9);
            double textY = this.y + this.height / 2.0 - font.height() / 2.0;
            font.draw(ListFrame.this.source.title(), this.x + 10.0 + 8.0, textY, VapeTheme.TEXT_BRIGHT);
            // The original draws its (squarish) list icons in an 8x8 box; wider ones are scaled
            // down the way category icons are, so they stay clear of the title.
            String icon = ListFrame.this.source.icon();
            double iconWidth = 8.0;
            double iconHeight = 8.0;
            if (VapeRender.imageWidth(icon) > VapeRender.imageHeight(icon) * 1.2) {
                iconWidth = VapeRender.imageWidth(icon) / 3.5;
                iconHeight = VapeRender.imageHeight(icon) / 3.5;
            } else {
                iconWidth = 8.0 * VapeRender.imageWidth(icon) / Math.max(1.0, VapeRender.imageHeight(icon));
                iconHeight = 8.0;
                if (iconWidth > 8.0) {
                    iconHeight = 8.0 * 8.0 / iconWidth;
                    iconWidth = 8.0;
                }
            }
            VapeRender.image(VapeTheme.TEXT_BRIGHT, this.x + 5.0 + (8.0 - iconWidth) / 2.0,
                    this.y + this.height / 2.0 - iconHeight / 2.0, icon, iconWidth, iconHeight);
            this.close.place(this.x + this.width - 7.5 - 8.0, this.y, this.height);
            this.renderIcons();
        }

        @Override
        boolean iconClicked(IconButton button, int mouseButton) {
            if (button == this.close && mouseButton == 0) {
                ListFrame.this.gui.closeListFrame(ListFrame.this);
            }
            return true;
        }
    }

    /** The "Add entry..." box ({@code ListValueAddEntryInputComponent}). */
    private final class AddEntryRow extends VComponent implements TextEditable {
        private final VapeAnimation.Colour border = new VapeAnimation.Colour(0.15, VapeTheme.HOVER, VapeTheme.OUTLINE);
        private String text = "";
        private boolean focused;

        @Override
        double preferredHeight() {
            return 20.0;
        }

        @Override
        void hoverChanged(boolean hovered) {
            this.border.toggle();
        }

        private double buttonX() {
            return this.x + this.width - 5.0 - 5.0 - 8.0 - 2.0;
        }

        @Override
        void render(double mouseX, double mouseY) {
            double boxX = this.x + 5.0;
            double boxY = this.y + 2.5;
            double boxWidth = this.width - 10.0;
            double boxHeight = this.height - 5.0;
            VapeRender.border(boxX, boxY, boxWidth, boxHeight, this.border.color(), 2.0F, 1.0F, 1.0F);
            VapeRender.rounded(boxX + 0.5, boxY + 0.5, boxWidth - 1.0, boxHeight - 0.5, VapeTheme.DARKEST);
            VapeFont font = font(0.9);
            boolean placeholder = this.text.isEmpty() && !this.focused;
            String shown = placeholder ? "Add entry..." : this.text;
            double textX = this.x + 10.0;
            double available = this.buttonX() - textX - 2.0;
            double textWidth = font.width(shown);
            if (textWidth > available) {
                textX -= textWidth - available;
            }
            double textY = this.y + this.height / 2.0 - font.height() / 2.0;
            VapeRender.pushScissor(this.x + 8.0, boxY, this.buttonX() - this.x - 8.0, boxHeight);
            try {
                font.draw(shown, textX, textY, placeholder ? VapeTheme.TEXT_DIM : VapeTheme.TEXT_BRIGHT);
                if (this.focused && (System.currentTimeMillis() / 500L) % 2L == 0L) {
                    VapeFont caret = font(1.2);
                    caret.draw("|", textX + textWidth, this.y + this.height / 2.0 - caret.height() / 2.0, VapeTheme.TEXT_BRIGHT);
                }
            } finally {
                VapeRender.popScissor();
            }
            Color add = ListFrame.this.source.blocked() ? VapeTheme.RED : VapeTheme.GREEN;
            boolean onButton = this.hovered && VFrame.inside(mouseX, mouseY, this.buttonX(), this.y + 4.0, 12.0, 12.0);
            VapeRender.icon("newadd", this.buttonX() + 6.0, this.y + this.height / 2.0, 6.0, 6.0,
                    onButton ? add.brighter() : add);
        }

        @Override
        boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0) {
                return false;
            }
            if (VFrame.inside(mouseX, mouseY, this.buttonX(), this.y + 4.0, 12.0, 12.0)) {
                this.submit();
                return true;
            }
            this.focused = true;
            ListFrame.this.gui.focus(this);
            return true;
        }

        private void submit() {
            if (!this.text.trim().isEmpty()) {
                ListFrame.this.source.add(this.text.trim());
            }
            this.text = "";
            ListFrame.this.refresh();
        }

        @Override
        public boolean editKey(char typedChar, int keyCode) {
            if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                this.submit();
                return true;
            }
            if (keyCode == Keyboard.KEY_ESCAPE) {
                this.text = "";
                ListFrame.this.gui.focus(null);
                return true;
            }
            if (keyCode == Keyboard.KEY_BACK) {
                if (!this.text.isEmpty()) {
                    this.text = this.text.substring(0, this.text.length() - 1);
                }
                return true;
            }
            if (net.minecraft.client.gui.GuiScreen.isKeyComboCtrlV(keyCode)) {
                String clipboard = net.minecraft.client.gui.GuiScreen.getClipboardString();
                if (clipboard != null) {
                    this.text += ChatAllowedCharacters.filterAllowedCharacters(clipboard);
                }
                return true;
            }
            if (ChatAllowedCharacters.isAllowedCharacter(typedChar)) {
                this.text += typedChar;
            }
            return true;
        }

        @Override
        public void focusLost() {
            this.focused = false;
        }
    }

    /** One entry ({@code SelectableTextRowComponent}). */
    private final class EntryRow extends VComponent {
        private static final int INDICATOR = 4;
        private final String entry;

        EntryRow(String entry) {
            this.entry = entry;
        }

        @Override
        double preferredHeight() {
            return 17.5;
        }

        private double closeX() {
            return this.x + this.width - 5.0 - 5.0 - 8.0;
        }

        private boolean onClose(double mouseX, double mouseY) {
            return ListFrame.this.source.removable() && VFrame.inside(mouseX, mouseY, this.closeX(), this.y, 13.0, this.height);
        }

        @Override
        void render(double mouseX, double mouseY) {
            boolean selected = ListFrame.this.source.selected(this.entry);
            Color text = selected ? (this.hovered ? VapeTheme.TEXT_BRIGHT : VapeTheme.TEXT)
                    : (this.hovered ? VapeTheme.TEXT : VapeTheme.TEXT_DIM);
            VapeRender.rounded(this.x + 5.0, this.y + 1.0, this.width - 10.0, this.height - 2.0, VapeTheme.HOVER);
            if (this.hovered) {
                VapeRender.rounded(this.x + 5.5, this.y + 1.5, this.width - 11.0, this.height - 3.0, VapeTheme.FRAME);
            }
            Color mark = ListFrame.this.source.blocked() ? VapeTheme.RED : VapeTheme.GREEN;
            if (ListFrame.this.source == ListFrame.this.gui.profiles) {
                mark = VapeTheme.accent();
            }
            double indicatorY = this.y + this.height / 2.0 - INDICATOR / 2.0;
            if (selected) {
                VapeRender.circle(this.x + 5.0 + 5.0, indicatorY, INDICATOR, 0.5, mark);
            } else {
                VapeRender.circle(this.x + 5.0 + 5.0, indicatorY, INDICATOR, 0.5, VapeTheme.ICON);
                VapeRender.circle(this.x + 5.0 + 6.0, indicatorY + 1.0, INDICATOR - 2.0, 0.5,
                        this.hovered ? VapeTheme.FRAME : VapeTheme.HOVER);
            }
            VapeFont font = font(0.9);
            String label = this.entry;
            double maxWidth = this.width - 30.0;
            if (font.width(label) > maxWidth) {
                while (!label.isEmpty() && font.width(label + "...") > maxWidth) {
                    label = label.substring(0, label.length() - 1);
                }
                label += "...";
            }
            font.draw(label, this.x + 5.0 + 15.0, this.y + this.height / 2.0 - font.height() / 2.0, text);
            if (ListFrame.this.source.removable()) {
                boolean onClose = this.hovered && this.onClose(mouseX, mouseY);
                VapeRender.icon("newclose", this.closeX() + 6.5, this.y + this.height / 2.0, 5.0, 5.0,
                        onClose ? VapeTheme.TEXT_BRIGHT : VapeTheme.ICON);
            }
        }

        @Override
        boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0) {
                return false;
            }
            if (this.onClose(mouseX, mouseY)) {
                ListFrame.this.source.remove(this.entry);
                ListFrame.this.refresh();
                return true;
            }
            ListFrame.this.source.clicked(this.entry);
            return true;
        }
    }
}
