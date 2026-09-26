package myau.ui.impl.clickgui.vape;

import myau.property.Property;

import java.util.Locale;

/**
 * A module setting shown under its module while the module is open. With no owner row it is
 * always shown (the settings frame lists the GUI's own options that way).
 */
abstract class ValueRow extends VComponent {
    final VapeClickGui gui;
    final Property<?> property;
    final ModuleRow owner;

    ValueRow(VapeClickGui gui, Property<?> property, ModuleRow owner) {
        this.gui = gui;
        this.property = property;
        this.owner = owner;
        this.background = VapeTheme.DARKEST;
    }

    @Override
    boolean isShown() {
        if (!this.property.isVisible()) {
            return false;
        }
        if (this.owner == null) {
            return true;
        }
        return this.owner.expanded && this.owner.isShown() && !this.gui.editingHidden;
    }

    String label() {
        return displayName(this.property.getName());
    }

    /**
     * Vape shows settings as short phrases ("Allow staircase up"); Myau+ names them for chat
     * commands ("allow-staircase-up"), so the dashes become spaces and the first letter is raised.
     */
    static String displayName(String name) {
        String spaced = name.replace('-', ' ').replace('_', ' ').trim();
        if (spaced.isEmpty()) {
            return name;
        }
        if (spaced.equals(spaced.toLowerCase(Locale.ROOT))) {
            return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
        }
        return spaced;
    }
}
