package myau.ui.impl.clickgui.vape;

import myau.property.properties.ItemListProperty;

import java.awt.*;
import java.util.List;

/**
 * An item list setting ({@code ListValueComponent}): a card with the list's name, how many
 * entries it has and the first few of them. Clicking it opens the list editor beside the frame.
 */
final class ListRow extends ValueRow {
    final ListSource source;
    private final VapeAnimation.Colour hoverColor = new VapeAnimation.Colour(0.15, VapeTheme.OUTLINE, VapeTheme.ICON);
    ListFrame editor;

    ListRow(VapeClickGui gui, ItemListProperty value, ModuleRow owner) {
        super(gui, value, owner);
        this.source = new ListSource.CommaList(value);
    }

    @Override
    double preferredHeight() {
        return 25.0;
    }

    boolean isExpanded() {
        return this.editor != null && this.gui.isOpenListEditor(this);
    }

    @Override
    void hoverChanged(boolean hovered) {
        this.hoverColor.toggle();
    }

    @Override
    void render(double mouseX, double mouseY) {
        this.fillBackground();
        VapeFont titleFont = font(0.9);
        VapeFont summaryFont = font(0.75);
        boolean expanded = this.isExpanded();
        Color primary = this.hovered || expanded ? VapeTheme.TEXT_BRIGHT : VapeTheme.TEXT;
        double iconY = this.y + this.height / 2.0 - 3.0;
        double titleY = this.y + this.height / 2.0 - titleFont.height() / 2.0 - 2.5;
        double summaryY = titleY + 7.5;
        VapeRender.rounded(this.x + 5.0, this.y + 2.5, this.width - 10.0, this.height - 5.0,
                expanded ? VapeTheme.accent() : this.hoverColor.color());
        VapeRender.rounded(this.x + 5.5, this.y + 3.0, this.width - 11.0, this.height - 6.0, VapeTheme.FRAME);
        List<String> entries = this.source.entries();
        titleFont.draw(this.source.title(), this.x + 15.0 + 8.0, titleY, primary);
        titleFont.draw(String.valueOf(entries.size()), this.x + this.width - 10.0 - titleFont.width("10"), titleY, primary);
        summaryFont.draw(summary(summaryFont, entries, this.width - 35.0), this.x + 15.0 + 8.0, summaryY, VapeTheme.TEXT_DIM);
        if (this.source.blocked()) {
            VapeRender.image(primary, this.x + 10.0 + 0.5, iconY, "newblockedlist", 6.0, 6.0);
            VapeRender.image(VapeTheme.RED, this.x + 10.0 - 0.5, iconY, "newblocked", 6.0, 6.0);
        } else {
            VapeRender.image(primary, this.x + 10.0 + 0.5, iconY, "newallowedlist", 6.0, 6.0);
            VapeRender.image(VapeTheme.GREEN, this.x + 10.0 + 0.5, iconY, "newallowed", 6.0, 6.0);
        }
    }

    static String summary(VapeFont font, List<String> entries, double maxWidth) {
        StringBuilder summary = new StringBuilder();
        for (String entry : entries) {
            if (summary.length() < 1) {
                summary.append(entry);
                continue;
            }
            String next = ", " + entry;
            if (font.width(summary + next) < maxWidth) {
                summary.append(next);
                continue;
            }
            summary.append("...");
            break;
        }
        if (summary.length() < 1) {
            summary.append("None");
        }
        return summary.toString();
    }

    @Override
    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        this.gui.toggleListEditor(this);
        return true;
    }
}
