package myau.module.modules;

import myau.Myau;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ColorProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;

/**
 * Restyles the inventory, container and menu screens to match the custom hotbar: rounded
 * translucent panels, rounded slots and rounded buttons in the client accent colour.
 * <p>
 * Everything is drawn <i>over</i> what the game (or a resource pack) already produced, and the
 * only vanilla draw that is suppressed is the full-width background slab of a container - so packs
 * keep their item icons, progress arrows, the player preview and every custom widget, and there is
 * no texture to replace or reload.
 * <p>
 * Cost is a replacement, not an addition: a handful of rounded rects instead of a handful of
 * textured ones, only while a screen is open, and never while the world is rendering.
 */
public class MenuStyle extends Module {
    private static final int SLOT_NONE = 0;
    private static final int SLOT_FLAT = 1;
    private static final int SLOT_ROUNDED = 2;

    public static final int HIGHLIGHT_ACCENT = 0;
    public static final int HIGHLIGHT_WHITE = 1;
    public static final int HIGHLIGHT_VANILLA = 2;

    public final BooleanProperty containers = new BooleanProperty("Containers", true);
    public final BooleanProperty buttons = new BooleanProperty("Buttons", true);
    public final ModeProperty colorMode = new ModeProperty("Color-Mode", HudStyle.MODE_HUD, HudStyle.COLOR_MODES);
    public final ColorProperty color = new ColorProperty("Color", 0x4FACFE, () -> this.colorMode.getValue() == HudStyle.MODE_CUSTOM);
    public final IntProperty panelAlpha = new IntProperty("Panel-Alpha", 220, 60, 255, containers::getValue);
    public final FloatProperty panelRadius = new FloatProperty("Panel-Radius", 8.0F, 0.0F, 16.0F, containers::getValue);
    public final ModeProperty slotStyle = new ModeProperty("Slot-Style", SLOT_ROUNDED,
            new String[]{"None", "Flat", "Rounded"}, containers::getValue);
    public final FloatProperty slotRadius = new FloatProperty("Slot-Radius", 4.0F, 0.0F, 8.0F,
            () -> this.containers.getValue() && this.slotStyle.getValue() == SLOT_ROUNDED);
    /** Inset inside each 18px slot cell. Zero makes neighbours touch and read as one slab. */
    public final FloatProperty slotGap = new FloatProperty("Slot-Gap", 1.0F, 0.0F, 3.0F,
            () -> this.containers.getValue() && this.slotStyle.getValue() != SLOT_NONE);
    public final IntProperty slotAlpha = new IntProperty("Slot-Alpha", 26, 0, 255,
            () -> this.containers.getValue() && this.slotStyle.getValue() != SLOT_NONE);
    public final BooleanProperty slotBorder = new BooleanProperty("Slot-Border", true,
            () -> this.containers.getValue() && this.slotStyle.getValue() != SLOT_NONE);
    public final ModeProperty highlight = new ModeProperty("Highlight", HIGHLIGHT_ACCENT,
            new String[]{"Accent", "White", "Vanilla"}, containers::getValue);
    public final IntProperty highlightAlpha = new IntProperty("Highlight-Alpha", 95, 10, 255,
            () -> this.containers.getValue() && this.highlight.getValue() != HIGHLIGHT_VANILLA);
    public final BooleanProperty accentBorder = new BooleanProperty("Accent-Border", true, containers::getValue);
    public final FloatProperty buttonRadius = new FloatProperty("Button-Radius", 5.0F, 0.0F, 12.0F, buttons::getValue);
    public final IntProperty buttonAlpha = new IntProperty("Button-Alpha", 170, 40, 255, buttons::getValue);
    public final FloatProperty hoverSpeed = new FloatProperty("Hover-Speed", 12.0F, 2.0F, 30.0F, buttons::getValue);
    /** Keep the creative inventory vanilla: its tabs are drawn as part of the background slab. */
    public final BooleanProperty skipCreative = new BooleanProperty("Skip-Creative", true, containers::getValue);

    /**
     * Set by the container mixin while the styled background layer runs, read by the Gui mixin to
     * drop the background slab. It lives here rather than in a mixin because mixin classes are
     * merged into their targets and are not loadable at runtime.
     * Screens only ever draw on the client thread, so a plain static is enough.
     */
    public static boolean skipBackgroundSlab;
    public static int slabWidth;

    public MenuStyle() {
        super("MenuStyle", false, false, "Rounded inventory, container and button styling");
    }

    private static MenuStyle get() {
        if (Myau.moduleManager == null) {
            return null;
        }
        Module module = Myau.moduleManager.modules.get(MenuStyle.class);
        return module instanceof MenuStyle ? (MenuStyle) module : null;
    }

    /** True when containers should be restyled right now. */
    public static MenuStyle containerStyle() {
        MenuStyle style = get();
        return style != null && style.isEnabled() && style.containers.getValue() ? style : null;
    }

    /** True when vanilla buttons should be replaced right now. */
    public static MenuStyle buttonStyle() {
        MenuStyle style = get();
        return style != null && style.isEnabled() && style.buttons.getValue() ? style : null;
    }

    public int accent(int alpha, long offset) {
        return HudStyle.color(this.colorMode.getValue(), this.color.getValue(), alpha, offset);
    }

    public boolean slotsVisible() {
        return this.slotStyle.getValue() != SLOT_NONE;
    }

    public boolean slotsRounded() {
        return this.slotStyle.getValue() == SLOT_ROUNDED;
    }
}
