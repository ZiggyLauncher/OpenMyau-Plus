package myau.ui.impl.clickgui.vape;

import myau.module.Module;
import myau.ui.ModuleCategories;

import java.util.ArrayList;
import java.util.List;

/**
 * One category's module list ({@code ModuleCategoryFrame}): the category's icon and name in the
 * title bar, then a row per module with each module's settings folded in below it.
 */
final class CategoryFrame extends VFrame {
    final ModuleCategories.Category category;
    final String icon;
    private List<Module> builtFor = new ArrayList<Module>();

    CategoryFrame(VapeClickGui gui, ModuleCategories.Category category, String icon) {
        super(gui);
        this.category = category;
        this.icon = icon;
        this.background = VapeTheme.FRAME;
        this.x = 200.0;
        this.y = 100.0;
        this.setHeader(new CategoryHeader());
        this.rebuild();
    }

    @Override
    String name() {
        return this.category.getLabel();
    }

    /** Rebuilds the rows when the category's modules changed (scripts come and go). */
    void rebuild() {
        List<Module> modules = ModuleCategories.modules(this.category);
        if (modules.equals(this.builtFor)) {
            return;
        }
        this.builtFor = new ArrayList<Module>(modules);
        List<Module> expanded = new ArrayList<Module>();
        for (VComponent component : this.components) {
            if (component instanceof ModuleRow && ((ModuleRow) component).expanded) {
                expanded.add(((ModuleRow) component).module);
            }
        }
        this.components.clear();
        for (Module module : modules) {
            ModuleRow row = this.gui.addModuleRow(this, module);
            row.expanded = expanded.contains(module);
        }
    }

    int hiddenCount() {
        int count = 0;
        for (Module module : this.builtFor) {
            if (this.gui.isHiddenInGui(module)) {
                count++;
            }
        }
        return count;
    }

    @Override
    void collapsedChanged() {
        if (this.gui.editingHidden) {
            this.gui.editingHidden = false;
        }
        for (VComponent component : this.components) {
            if (component instanceof ModuleRow) {
                ((ModuleRow) component).expanded = false;
            }
        }
        this.gui.closePopups();
    }

    /** {@code ModuleCategoryFrameHeader}. */
    private final class CategoryHeader extends VFrame.Header {
        private final IconButton edit = this.icon(new IconButton("newedit", 0.7));
        private final IconButton collapse = this.icon(new IconButton("upcollapse", 0.3));
        private double doneX;
        private boolean doneHovered;

        @Override
        boolean collapsesOnRightClick() {
            return true;
        }

        @Override
        void render(double mouseX, double mouseY) {
            VapeClickGui gui = CategoryFrame.this.gui;
            VapeFont font = font(0.9);
            String name = CategoryFrame.this.name();
            double textY = this.y + this.height / 2.0 - font.height() / 2.0 + 1.0;
            double iconWidth = VapeRender.imageWidth(CategoryFrame.this.icon) / 3.5;
            double iconHeight = VapeRender.imageHeight(CategoryFrame.this.icon) / 3.5;
            double iconX = this.x + 6.0;
            double iconY = this.y + this.height / 2.0 - iconHeight / 2.0 + 1.0;
            font.draw(name, iconX + iconWidth + 4.0, textY, VapeTheme.TEXT_BRIGHT);
            VapeRender.image(VapeTheme.TEXT_BRIGHT, iconX, iconY, CategoryFrame.this.icon, iconWidth, iconHeight);

            this.doneHovered = false;
            if (gui.editingHidden) {
                this.edit.visible = false;
                VapeFont small = font(0.75);
                this.doneX = this.x + this.width - 10.0 - 16.0 - font.width("Done") / 2.0;
                this.doneHovered = this.hovered && mouseX >= this.doneX && mouseX <= this.doneX + small.width("Done");
                small.draw("Done", this.doneX, this.y + this.height / 2.0 - small.height() / 2.0,
                        this.doneHovered ? VapeTheme.TEXT_BRIGHT : VapeTheme.TEXT);
            } else {
                boolean frameHovered = CategoryFrame.this.contains(mouseX, mouseY) && CategoryFrame.this.hovered;
                int hidden = CategoryFrame.this.hiddenCount();
                if (hidden > 0) {
                    String count = String.valueOf(hidden);
                    if (frameHovered) {
                        font.draw(count, this.x + this.width - 5.0 - 16.0 - 3.0 - font.width(count), textY, VapeTheme.TEXT);
                    }
                    this.edit.icon = "newhide";
                } else {
                    this.edit.icon = "newedit";
                }
                this.edit.visible = frameHovered;
                this.edit.place(this.x + this.width - 10.0 - 16.0, this.y + 1.0, this.height);
            }
            this.collapse.place(this.x + this.width - 7.5 - 8.0, this.y, this.height);
            this.collapse.icon = CategoryFrame.this.collapsed ? "downexpand" : "upcollapse";
            this.renderIcons();
        }

        @Override
        boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (CategoryFrame.this.gui.editingHidden && button == 0 && this.doneHovered) {
                CategoryFrame.this.gui.editingHidden = false;
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        boolean iconClicked(IconButton button, int mouseButton) {
            if (mouseButton != 0) {
                return true;
            }
            if (button == this.edit) {
                CategoryFrame.this.gui.editingHidden = !CategoryFrame.this.gui.editingHidden;
                CategoryFrame.this.gui.closePopups();
            } else if (button == this.collapse) {
                CategoryFrame.this.toggleCollapsed();
            }
            return true;
        }
    }
}
