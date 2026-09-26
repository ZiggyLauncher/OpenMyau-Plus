package myau.ui.impl.clickgui.vape;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A draggable window ({@code Frame}/{@code FrameComponent}): a header, then its components
 * stacked below it. Past {@link #maxHeight} it stops growing and scrolls, with the header kept
 * on top and a thin scrollbar inside the right edge.
 */
abstract class VFrame {
    final VapeClickGui gui;
    double x;
    double y;
    double width = 110.0;
    Header header;
    final List<VComponent> components = new ArrayList<VComponent>();
    Color background = VapeTheme.FRAME;
    boolean outlined = true;
    boolean dropShadow = true;
    boolean draggable = true;
    boolean visible;
    boolean collapsed;
    boolean hovered;
    /** Vape's {@code K}: the height at which the frame starts scrolling. */
    double maxHeight = 300.0;
    /** Scroll offset, zero or negative. */
    double scroll;
    boolean scrolling;
    double contentHeight;
    /** Set once the frame has been given a spot on screen. */
    boolean positioned;

    private boolean dragging;
    private int dragLastX;
    private int dragLastY;
    private boolean thumbDragging;
    private double thumbLastY;
    private double thumbX;
    private double thumbY;
    private double thumbHeight;
    private double trackY;
    private double trackHeight;
    private final VapeAnimation.Colour trackColor = new VapeAnimation.Colour(0.15,
            VapeTheme.withAlpha(VapeTheme.HOVER, 0), VapeTheme.HOVER);
    private final VapeAnimation.Colour thumbColor = new VapeAnimation.Colour(0.15,
            VapeTheme.withAlpha(VapeTheme.OUTLINE, 30), VapeTheme.OUTLINE);

    VFrame(VapeClickGui gui) {
        this.gui = gui;
    }

    /** Key under which the frame's position is saved. */
    abstract String name();

    void setHeader(Header header) {
        this.header = header;
        header.frame = this;
    }

    void add(VComponent component) {
        component.frame = this;
        this.components.add(component);
    }

    /** Components drawn on top of the others, e.g. an open dropdown. */
    boolean isShown(VComponent component) {
        return !this.collapsed && component.isShown();
    }

    // ------------------------------------------------------------------ layout

    void layout() {
        double cursor = this.y;
        if (this.header != null) {
            this.header.x = this.x;
            this.header.y = cursor;
            this.header.width = this.width;
            this.header.height = this.header.preferredHeight();
            cursor += this.header.height;
        }
        for (VComponent component : this.components) {
            if (!this.isShown(component)) {
                continue;
            }
            component.x = this.x;
            component.y = cursor + this.scroll;
            component.width = this.width;
            component.height = component.preferredHeight();
            cursor += component.height;
        }
        this.contentHeight = cursor - this.y;
        this.scrolling = this.contentHeight >= this.maxHeight;
        if (!this.scrolling) {
            if (this.scroll != 0.0) {
                this.scroll = 0.0;
                this.layout();
            }
        } else {
            double limit = -(this.contentHeight - this.maxHeight);
            if (this.scroll < limit) {
                this.scroll = limit;
                this.layout();
            }
        }
    }

    /** Height of the frame on screen: its contents, or the scroll viewport. */
    double height() {
        return this.scrolling ? this.maxHeight : this.contentHeight;
    }

    boolean contains(double mouseX, double mouseY) {
        return mouseX >= this.x && mouseX <= this.x + this.width && mouseY >= this.y && mouseY <= this.y + this.height();
    }

    /** Whether a component lies wholly outside the scroll viewport. */
    boolean isClipped(VComponent component) {
        return component.y + component.height < this.y || component.y > this.y + this.maxHeight;
    }

    boolean isHeaderHovered(double mouseX, double mouseY) {
        return this.header != null && this.header.contains(mouseX, mouseY);
    }

    // ------------------------------------------------------------------ per-frame

    void update(double mouseX, double mouseY) {
        if (this.dragging) {
            if (!org.lwjgl.input.Mouse.isButtonDown(0)) {
                this.dragging = false;
                this.gui.saveState();
            } else {
                int mx = (int) mouseX;
                int my = (int) mouseY;
                this.x = Math.floor(this.x + (mx - this.dragLastX));
                this.y = Math.floor(this.y + (my - this.dragLastY));
                this.dragLastX = mx;
                this.dragLastY = my;
            }
        }
        if (this.header != null) {
            this.header.update();
        }
        for (VComponent component : this.components) {
            if (this.isShown(component)) {
                component.update();
            }
        }
    }

    void render(double mouseX, double mouseY) {
        this.layout();
        this.drawBackground();
        int inset = this.scrolling && this.header != null ? 3 : 0;
        if (this.scrolling) {
            VapeRender.pushScissor(this.x, this.y + inset, this.width, this.maxHeight - inset);
        }
        try {
            if (this.header != null) {
                this.header.render(mouseX, mouseY);
            }
            for (VComponent component : this.components) {
                if (!this.isShown(component) || this.isClipped(component)) {
                    continue;
                }
                component.render(mouseX, mouseY);
            }
            if (this.scroll != 0.0 && this.scrolling && this.header != null) {
                Header head = this.header;
                VapeRender.rounded(head.x, head.y + inset, head.width, head.height - inset, this.background);
                VapeRender.rect(head.x, head.y, head.width, head.height, this.background);
                head.render(mouseX, mouseY);
                VapeRender.rect(this.x, head.y + head.height, 110.0, 0.5, VapeTheme.OUTLINE);
            }
            if (this.scrolling) {
                this.updateThumbDrag(mouseY);
                this.drawScrollbar();
            }
        } finally {
            if (this.scrolling) {
                VapeRender.popScissor();
            }
        }
    }

    void drawBackground() {
        double height = this.height();
        if (this.outlined) {
            VapeRender.border(this.x - 0.5, this.y - 0.5, this.width + 1.0, height + 2.0, VapeTheme.OUTLINE, 2.1F, 1.0F, 1.0F);
        }
        VapeRender.rounded(this.x, this.y, this.width, height + 2.0, this.background,
                this.dropShadow && this.header != null, 1.5F, 1.0F, 8.0F, VapeTheme.SHADOW, 15);
    }

    private void drawScrollbar() {
        double headerHeight = this.header != null ? this.header.height : 0.0;
        this.thumbHeight = (this.maxHeight - headerHeight) / this.contentHeight * this.maxHeight;
        this.thumbY = this.y + headerHeight - this.scroll / this.contentHeight * (this.maxHeight - headerHeight);
        this.thumbX = this.x + this.width - 3.0;
        this.trackY = this.y + headerHeight;
        this.trackHeight = this.maxHeight - headerHeight;
        boolean active = this.hovered || this.thumbDragging;
        this.trackColor.set(active);
        this.thumbColor.set(active);
        VapeRender.rounded(this.thumbX, this.trackY, 2.0, this.trackHeight, this.trackColor.color(), false, 1.0F, 0.5F);
        VapeRender.rounded(this.thumbX, this.thumbY, 2.0, this.thumbHeight, this.thumbColor.color(), false, 1.0F, 0.5F);
    }

    private void updateThumbDrag(double mouseY) {
        if (!this.thumbDragging) {
            return;
        }
        if (!org.lwjgl.input.Mouse.isButtonDown(0)) {
            this.thumbDragging = false;
            return;
        }
        int my = (int) mouseY;
        double delta = my - this.thumbLastY;
        this.thumbLastY = my;
        this.scrollTo(this.scroll - delta * (this.contentHeight / this.maxHeight));
    }

    void scrollTo(double offset) {
        double limit = -(this.contentHeight - this.maxHeight);
        this.scroll = Math.max(limit, Math.min(0.0, offset));
        this.layout();
    }

    /** One wheel event, in raw LWJGL units (120 a notch on Windows). */
    void scrollWheel(int delta) {
        if (this.scrolling && delta != 0) {
            this.scrollTo(this.scroll + (double) (delta / 15));
        }
    }

    // ------------------------------------------------------------------ input

    /** Where the hover state for this frame's parts is worked out. */
    void updateHover(double mouseX, double mouseY, boolean top) {
        this.hovered = top;
        boolean onHeader = top && this.isHeaderHovered(mouseX, mouseY);
        if (this.header != null) {
            this.header.setHovered(onHeader);
            this.header.updateIconHover(onHeader, mouseX, mouseY);
        }
        for (VComponent component : this.components) {
            boolean shown = this.isShown(component) && !this.isClipped(component);
            component.setHovered(top && shown && !onHeader && component.contains(mouseX, mouseY)
                    && (!this.scrolling || mouseY >= this.y && mouseY <= this.y + this.maxHeight));
        }
    }

    void mouseClicked(double mouseX, double mouseY, int button) {
        if (this.scrolling && button == 0) {
            if (inside(mouseX, mouseY, this.thumbX, this.thumbY, 2.0, this.thumbHeight)) {
                this.thumbLastY = (int) mouseY;
                this.thumbDragging = true;
                return;
            }
            if (inside(mouseX, mouseY, this.thumbX, this.trackY, 2.0, this.trackHeight)) {
                this.thumbLastY = mouseY > this.thumbY ? this.thumbY + this.thumbHeight : this.thumbY;
                this.thumbDragging = true;
                return;
            }
        }
        if (this.header != null && this.header.hovered) {
            if (button == 1 && this.header.collapsesOnRightClick()) {
                this.toggleCollapsed();
                return;
            }
            if (this.header.mouseClicked(mouseX, mouseY, button)) {
                return;
            }
            if (button == 0 && this.draggable) {
                this.dragging = true;
                this.dragLastX = (int) mouseX;
                this.dragLastY = (int) mouseY;
            }
            return;
        }
        List<VComponent> reversed = new ArrayList<VComponent>(this.components);
        Collections.reverse(reversed);
        for (VComponent component : reversed) {
            if (component.hovered) {
                component.mouseClicked(mouseX, mouseY, button);
                return;
            }
        }
    }

    void mouseReleased(double mouseX, double mouseY, int button) {
        for (VComponent component : this.components) {
            component.mouseReleased(mouseX, mouseY, button);
        }
    }

    void toggleCollapsed() {
        this.collapsed = !this.collapsed;
        this.collapsedChanged();
        this.gui.saveState();
    }

    void collapsedChanged() {
    }

    static boolean inside(double mouseX, double mouseY, double x, double y, double width, double height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    /**
     * A frame's title bar ({@code FrameHeaderComponent}): 18 units tall, with icon buttons that
     * take clicks before the bar itself starts a drag.
     */
    abstract static class Header extends VComponent {
        final List<IconButton> icons = new ArrayList<IconButton>();

        @Override
        double preferredHeight() {
            return 18.0;
        }

        IconButton icon(IconButton button) {
            this.icons.add(button);
            return button;
        }

        void updateIconHover(boolean headerHovered, double mouseX, double mouseY) {
            for (IconButton button : this.icons) {
                button.hovered = headerHovered && button.contains(mouseX, mouseY);
            }
        }

        boolean collapsesOnRightClick() {
            return false;
        }

        /** Handles a click on one of the icons; returns false to let the bar start a drag. */
        boolean iconClicked(IconButton button, int mouseButton) {
            return false;
        }

        @Override
        boolean mouseClicked(double mouseX, double mouseY, int button) {
            for (int i = this.icons.size() - 1; i >= 0; i--) {
                IconButton icon = this.icons.get(i);
                if (icon.contains(mouseX, mouseY)) {
                    return this.iconClicked(icon, button);
                }
            }
            return false;
        }

        void renderIcons() {
            for (IconButton button : this.icons) {
                button.render();
            }
        }
    }
}
