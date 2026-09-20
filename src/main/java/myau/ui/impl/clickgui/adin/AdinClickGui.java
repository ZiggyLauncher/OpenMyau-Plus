package myau.ui.impl.clickgui.adin;

import myau.Myau;
import myau.module.Module;
import myau.module.modules.ClickGUIModule;
import myau.property.Property;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ColorProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.LongProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.ui.ModuleCategories;
import myau.util.KeyBindUtil;
import myau.util.RenderUtil;
import myau.util.font.FontManager;
import myau.util.font.impl.FontRenderer;
import myau.util.shader.RoundedUtils;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A click GUI in the style of the adin client: one dark rounded window, a narrow sidebar whose
 * selection is an accent pill that slides and squashes between categories, and a scrolling list of
 * module rows that expand in place to reveal their settings.
 * <p>
 * Layout and hit testing are the same pass. Every frame {@link #rebuild()} produces the list of
 * rectangles that will be drawn, and clicks and scrolls are resolved against that same list, so
 * there is no way for a drawn row and its clickable area to disagree - which is exactly the bug
 * that made the Raven skin select several modules at once while scrolled.
 * <p>
 * The window never scales below 1:1. Shrinking it would not shrink the bitmap fonts with it, so on
 * a small scaled resolution it is clipped and scrolled instead of squashed.
 */
public class AdinClickGui extends GuiScreen {
    private static final int BASE_WIDTH = 375;
    private static final int BASE_HEIGHT = 323;
    private static final int SCREEN_MARGIN = 8;
    private static final int RADIUS = 6;
    private static final int SIDEBAR_WIDTH = 78;
    private static final int TOP_BAR_HEIGHT = 20;
    private static final int PADDING = 10;
    private static final int SIDEBAR_INSET = 8;

    private static final int TAB_HEIGHT = 22;
    private static final int TAB_STRIDE = 26;
    private static final int PILL_RADIUS = 6;
    private static final float PILL_STRETCH = 0.16F;

    private static final int ROW_HEIGHT = 26;
    private static final int ROW_GAP = 5;
    private static final int ROW_RADIUS = 5;
    private static final int SETTING_HEIGHT = 17;
    private static final int SLIDER_HEIGHT = 24;
    private static final int TRACK_HEIGHT = 3;

    private static final int TOGGLE_WIDTH = 20;
    private static final int TOGGLE_HEIGHT = 11;
    private static final int SMALL_TOGGLE_WIDTH = 16;
    private static final int SMALL_TOGGLE_HEIGHT = 9;

    private static final int SCROLL_STEP = 24;

    private static AdinClickGui instance;

    /** What a laid-out rectangle does when it is clicked. */
    private enum RowKind {
        MODULE, SETTING_BOOLEAN, SETTING_SLIDER, SETTING_MODE, SETTING_COLOR, SETTING_TEXT, KEYBIND
    }

    private static final class Row {
        RowKind kind;
        Module module;
        Property<?> property;
        int x;
        int y;
        int width;
        int height;

        boolean contains(int mouseX, int mouseY) {
            return mouseX >= this.x && mouseX < this.x + this.width
                    && mouseY >= this.y && mouseY < this.y + this.height;
        }
    }

    private final Set<Module> expanded = new HashSet<Module>();
    private final List<Row> rows = new ArrayList<Row>();
    private final AdinTransition pillY = new AdinTransition(0.0F, 210, AdinTransition.EASE_OUT_EXPO);

    private ModuleCategories.Category selected = ModuleCategories.Category.COMBAT;
    private boolean pillPlaced;

    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int contentHeight;

    private float scroll;
    private float targetScroll;
    private Property<?> dragging;
    private Module binding;

    public static AdinClickGui getInstance() {
        if (instance == null) {
            instance = new AdinClickGui();
        }
        return instance;
    }

    public static void resetInstance() {
        instance = null;
    }

    @Override
    public void initGui() {
        super.initGui();
        FontManager.initializeFonts();
        this.dragging = null;
        this.binding = null;
        this.pillPlaced = false;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ---------------------------------------------------------------- layout

    private void computePanel() {
        ScaledResolution sr = new ScaledResolution(this.mc);
        this.panelWidth = Math.max(160, Math.min(BASE_WIDTH, sr.getScaledWidth() - SCREEN_MARGIN * 2));
        this.panelHeight = Math.max(120, Math.min(BASE_HEIGHT, sr.getScaledHeight() - SCREEN_MARGIN * 2));
        this.panelX = (sr.getScaledWidth() - this.panelWidth) / 2;
        this.panelY = (sr.getScaledHeight() - this.panelHeight) / 2;
    }

    private int contentX() {
        return this.panelX + SIDEBAR_WIDTH;
    }

    private int contentY() {
        return this.panelY + TOP_BAR_HEIGHT;
    }

    private int contentWidth() {
        return this.panelWidth - SIDEBAR_WIDTH;
    }

    private int viewportHeight() {
        return this.panelHeight - TOP_BAR_HEIGHT - PADDING;
    }

    /**
     * Lays out every clickable rectangle for the current category and scroll offset. Called once
     * per frame before drawing, and again before handling input so a click can never be resolved
     * against a stale layout.
     */
    private void rebuild() {
        this.computePanel();
        this.rows.clear();

        int rowX = this.contentX() + PADDING;
        int rowWidth = Math.max(40, this.contentWidth() - PADDING * 2);
        int y = this.contentY() + PADDING - Math.round(this.scroll);

        for (Module module : ModuleCategories.modules(this.selected)) {
            if (module == null || module.isHidden()) {
                continue;
            }
            this.rows.add(row(RowKind.MODULE, module, null, rowX, y, rowWidth, ROW_HEIGHT));
            y += ROW_HEIGHT;

            if (this.expanded.contains(module)) {
                int settingX = rowX + 8;
                int settingWidth = rowWidth - 16;
                List<Property<?>> properties = properties(module);
                for (int i = 0; i < properties.size(); i++) {
                    Property<?> property = properties.get(i);
                    if (property == null || !property.isVisible()) {
                        continue;
                    }
                    RowKind kind = kindOf(property);
                    if (kind == null) {
                        continue;
                    }
                    int height = kind == RowKind.SETTING_SLIDER || kind == RowKind.SETTING_COLOR
                            ? SLIDER_HEIGHT : SETTING_HEIGHT;
                    this.rows.add(row(kind, module, property, settingX, y, settingWidth, height));
                    y += height;
                }
                this.rows.add(row(RowKind.KEYBIND, module, null, settingX, y, settingWidth, SETTING_HEIGHT));
                y += SETTING_HEIGHT + 3;
            }
            y += ROW_GAP;
        }

        int bottom = y + Math.round(this.scroll);
        this.contentHeight = Math.max(0, bottom - (this.contentY() + PADDING));
    }

    private static Row row(RowKind kind, Module module, Property<?> property, int x, int y, int width, int height) {
        Row created = new Row();
        created.kind = kind;
        created.module = module;
        created.property = property;
        created.x = x;
        created.y = y;
        created.width = width;
        created.height = height;
        return created;
    }

    private static List<Property<?>> properties(Module module) {
        if (Myau.propertyManager == null) {
            return new ArrayList<Property<?>>();
        }
        List<Property<?>> list = Myau.propertyManager.properties.get(module);
        return list == null ? new ArrayList<Property<?>>() : list;
    }

    private static RowKind kindOf(Property<?> property) {
        if (property instanceof BooleanProperty) {
            return RowKind.SETTING_BOOLEAN;
        }
        if (property instanceof IntProperty || property instanceof FloatProperty
                || property instanceof PercentProperty || property instanceof LongProperty) {
            return RowKind.SETTING_SLIDER;
        }
        if (property instanceof ModeProperty) {
            return RowKind.SETTING_MODE;
        }
        if (property instanceof ColorProperty) {
            return RowKind.SETTING_COLOR;
        }
        // Anything else is shown read-only rather than silently hidden.
        return RowKind.SETTING_TEXT;
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        this.rebuild();
        this.updateScroll();
        if (this.dragging != null) {
            this.applyDrag(mouseX);
        }

        int accent = AdinTheme.accent();

        RoundedUtils.drawRound(this.panelX, this.panelY, this.panelWidth, this.panelHeight,
                RADIUS, AdinTheme.color(AdinTheme.MAIN));
        RoundedUtils.drawRoundedRectRise(this.panelX, this.panelY, SIDEBAR_WIDTH, this.panelHeight,
                RADIUS, AdinTheme.SIDEBAR, true, false, false, true);

        this.drawBrand(accent);
        this.drawSidebar(accent, mouseX, mouseY);
        this.drawContent(accent, mouseX, mouseY);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawBrand(int accent) {
        FontRenderer font = FontManager.productSans18;
        String name = "Ziggy";
        String suffix = ".client";
        float textY = this.panelY + (TOP_BAR_HEIGHT - height(font)) / 2.0F;
        float x = this.panelX + SIDEBAR_INSET;
        draw(font, name, x, textY, AdinTheme.TEXT);
        draw(font, suffix, x + width(font, name), textY, accent);
    }

    private void drawSidebar(int accent, int mouseX, int mouseY) {
        ModuleCategories.Category[] categories = ModuleCategories.Category.values();
        int tabX = this.panelX + SIDEBAR_INSET;
        int tabWidth = SIDEBAR_WIDTH - SIDEBAR_INSET * 2;
        int firstY = this.contentY() + PADDING;

        int selectedIndex = 0;
        for (int i = 0; i < categories.length; i++) {
            if (categories[i] == this.selected) {
                selectedIndex = i;
            }
        }
        int targetY = firstY + selectedIndex * TAB_STRIDE;
        if (!this.pillPlaced) {
            this.pillY.snap(targetY);
            this.pillPlaced = true;
        }
        this.pillY.set(targetY);

        // The pill stretches while it travels and relaxes as it lands.
        float pillTop = this.pillY.value();
        float pillHeight = TAB_HEIGHT * (1.0F + PILL_STRETCH * this.pillY.flight());
        RoundedUtils.drawRound(tabX, pillTop - (pillHeight - TAB_HEIGHT) * 0.5F, tabWidth, pillHeight,
                PILL_RADIUS, AdinTheme.color(accent));

        FontRenderer font = FontManager.productSans16;
        for (int i = 0; i < categories.length; i++) {
            int tabY = firstY + i * TAB_STRIDE;
            float centre = tabY + TAB_HEIGHT * 0.5F;
            boolean onPill = centre >= pillTop && centre < pillTop + TAB_HEIGHT;
            boolean hovered = mouseX >= tabX && mouseX < tabX + tabWidth
                    && mouseY >= tabY && mouseY < tabY + TAB_HEIGHT;

            int textColor = onPill
                    ? AdinTheme.ON_ACCENT
                    : AdinTheme.lerp(AdinTheme.DIM, AdinTheme.TEXT, hovered ? 1.0F : 0.0F);
            String label = categories[i].getLabel();
            draw(font, label, tabX + 9, tabY + (TAB_HEIGHT - height(font)) / 2.0F, textColor);
        }
    }

    private void drawContent(int accent, int mouseX, int mouseY) {
        int clipY = this.contentY();
        int clipHeight = this.panelHeight - TOP_BAR_HEIGHT;
        RenderUtil.scissor(this.contentX(), clipY, this.contentWidth(), clipHeight);

        for (int i = 0; i < this.rows.size(); i++) {
            Row row = this.rows.get(i);
            if (row.y + row.height < clipY || row.y > clipY + clipHeight) {
                continue;
            }
            boolean hovered = row.contains(mouseX, mouseY) && this.inViewport(mouseY);
            switch (row.kind) {
                case MODULE:
                    this.drawModuleRow(row, accent, hovered);
                    break;
                case SETTING_BOOLEAN:
                    this.drawBooleanRow(row, accent);
                    break;
                case SETTING_SLIDER:
                    this.drawSliderRow(row, accent, hovered);
                    break;
                case SETTING_MODE:
                    this.drawModeRow(row, accent, hovered);
                    break;
                case SETTING_COLOR:
                    this.drawColorRow(row, hovered);
                    break;
                case KEYBIND:
                    this.drawKeybindRow(row, accent, hovered);
                    break;
                case SETTING_TEXT:
                default:
                    this.drawTextRow(row);
                    break;
            }
        }
        RenderUtil.releaseScissor();
    }

    private void drawModuleRow(Row row, int accent, boolean hovered) {
        boolean open = this.expanded.contains(row.module);
        int background = AdinTheme.lerp(AdinTheme.ROW, AdinTheme.CONTROL, hovered ? 0.45F : 0.0F);
        RoundedUtils.drawRound(row.x, row.y, row.width, row.height, ROW_RADIUS,
                AdinTheme.color(open ? AdinTheme.CONTROL : background));

        FontRenderer font = FontManager.productSans16;
        String name = row.module.getName();
        float textY = row.y + (row.height - height(font)) / 2.0F;
        draw(font, name, row.x + 9, textY, row.module.isEnabled() ? AdinTheme.TEXT : AdinTheme.MUTED);

        String suffix = suffixOf(row.module);
        if (suffix != null) {
            draw(font, suffix, row.x + 9 + width(font, name) + 5, textY, AdinTheme.MUTED);
        }

        this.drawToggle(row.x + row.width - TOGGLE_WIDTH - 9,
                row.y + (row.height - TOGGLE_HEIGHT) / 2,
                TOGGLE_WIDTH, TOGGLE_HEIGHT, row.module.isEnabled(), accent);
    }

    private void drawBooleanRow(Row row, int accent) {
        FontRenderer font = FontManager.productSans16;
        BooleanProperty property = (BooleanProperty) row.property;
        draw(font, property.getName(), row.x + 6, row.y + (row.height - height(font)) / 2.0F,
                property.getValue() ? AdinTheme.TEXT : AdinTheme.MUTED);
        this.drawToggle(row.x + row.width - SMALL_TOGGLE_WIDTH - 6,
                row.y + (row.height - SMALL_TOGGLE_HEIGHT) / 2,
                SMALL_TOGGLE_WIDTH, SMALL_TOGGLE_HEIGHT, property.getValue(), accent);
    }

    private void drawSliderRow(Row row, int accent, boolean hovered) {
        FontRenderer font = FontManager.productSans16;
        float textY = row.y + 3.0F;
        draw(font, row.property.getName(), row.x + 6, textY, AdinTheme.TEXT);
        String value = row.property.getValuePrompt();
        draw(font, value, row.x + row.width - 6 - width(font, value), textY, AdinTheme.MUTED);

        int trackX = row.x + 6;
        int trackWidth = row.width - 12;
        int trackY = row.y + row.height - TRACK_HEIGHT - 5;
        RoundedUtils.drawRound(trackX, trackY, trackWidth, TRACK_HEIGHT, TRACK_HEIGHT / 2.0F,
                AdinTheme.color(AdinTheme.TRACK));
        float fraction = fractionOf(row.property);
        float filled = Math.max(TRACK_HEIGHT, trackWidth * fraction);
        RoundedUtils.drawRound(trackX, trackY, filled, TRACK_HEIGHT, TRACK_HEIGHT / 2.0F,
                AdinTheme.color(accent));

        boolean active = this.dragging == row.property;
        if (hovered || active) {
            float knob = trackX + trackWidth * fraction;
            RoundedUtils.drawRound(knob - 3.0F, trackY - 2.0F, 6.0F, TRACK_HEIGHT + 4.0F, 3.0F,
                    AdinTheme.color(active ? AdinTheme.TEXT : accent));
        }
    }

    private void drawModeRow(Row row, int accent, boolean hovered) {
        FontRenderer font = FontManager.productSans16;
        ModeProperty property = (ModeProperty) row.property;
        float textY = row.y + (row.height - height(font)) / 2.0F;
        draw(font, property.getName(), row.x + 6, textY, AdinTheme.TEXT);
        String value = property.getModeString();
        draw(font, value, row.x + row.width - 6 - width(font, value), textY,
                hovered ? accent : AdinTheme.DIM);
    }

    private void drawColorRow(Row row, boolean hovered) {
        FontRenderer font = FontManager.productSans16;
        ColorProperty property = (ColorProperty) row.property;
        draw(font, property.getName(), row.x + 6, row.y + 3.0F, AdinTheme.TEXT);

        int rgb = property.getValue() == null ? 0 : property.getValue();
        RoundedUtils.drawRound(row.x + row.width - 20, row.y + 2, 14, 9, 3.0F,
                AdinTheme.color(0xFF000000 | (rgb & 0xFFFFFF)));

        // A hue strip rather than a full picker: one drag covers every hue at full saturation.
        int trackX = row.x + 6;
        int trackWidth = row.width - 12;
        int trackY = row.y + row.height - TRACK_HEIGHT - 5;
        int steps = Math.max(1, trackWidth / 2);
        float stepWidth = trackWidth / (float) steps;
        for (int i = 0; i < steps; i++) {
            int hueColor = Color.HSBtoRGB(i / (float) steps, 0.85F, 1.0F);
            RenderUtil.drawRect(trackX + i * stepWidth, trackY, trackX + (i + 1) * stepWidth + 0.5F,
                    trackY + TRACK_HEIGHT, 0xFF000000 | (hueColor & 0xFFFFFF));
        }
        if (hovered) {
            float[] hsb = Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
            float knob = trackX + trackWidth * hsb[0];
            RoundedUtils.drawRound(knob - 2.0F, trackY - 2.0F, 4.0F, TRACK_HEIGHT + 4.0F, 2.0F,
                    AdinTheme.color(AdinTheme.TEXT));
        }
    }

    private void drawTextRow(Row row) {
        FontRenderer font = FontManager.productSans16;
        float textY = row.y + (row.height - height(font)) / 2.0F;
        draw(font, row.property.getName(), row.x + 6, textY, AdinTheme.MUTED);
        String value = String.valueOf(row.property.getValuePrompt());
        draw(font, value, row.x + row.width - 6 - width(font, value), textY, AdinTheme.DIM);
    }

    private void drawKeybindRow(Row row, int accent, boolean hovered) {
        FontRenderer font = FontManager.productSans16;
        float textY = row.y + (row.height - height(font)) / 2.0F;
        draw(font, "Bind", row.x + 6, textY, AdinTheme.TEXT);
        boolean listening = this.binding == row.module;
        String value = listening ? "..." : KeyBindUtil.getKeyName(row.module.getKey());
        draw(font, value, row.x + row.width - 6 - width(font, value), textY,
                listening || hovered ? accent : AdinTheme.DIM);
    }

    private void drawToggle(int x, int y, int width, int height, boolean on, int accent) {
        float radius = height / 2.0F;
        if (on) {
            RoundedUtils.drawRound(x, y, width, height, radius, AdinTheme.color(accent));
        } else {
            RoundedUtils.drawRoundOutline(x, y, width, height, radius, 1.0F,
                    AdinTheme.color(AdinTheme.TOGGLE_OFF), AdinTheme.color(AdinTheme.TOGGLE_OFF_BORDER));
        }
        float knob = height - 4.0F;
        float knobX = on ? x + width - knob - 2.0F : x + 2.0F;
        RoundedUtils.drawRound(knobX, y + 2.0F, knob, knob, knob / 2.0F,
                AdinTheme.color(on ? AdinTheme.ON_ACCENT : AdinTheme.THUMB_OFF));
    }

    // ---------------------------------------------------------------- input

    private boolean inViewport(int mouseY) {
        return mouseY >= this.contentY() && mouseY < this.panelY + this.panelHeight;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        this.rebuild();

        if (this.binding != null) {
            // Any bindable button becomes the bind; left click just cancels.
            if (KeyBindUtil.isBindableMouseButton(button)) {
                this.binding.setKey(KeyBindUtil.mouseButtonToKey(button));
            }
            this.binding = null;
            return;
        }

        if (this.clickSidebar(mouseX, mouseY)) {
            return;
        }

        if (this.inViewport(mouseY) && this.clickContent(mouseX, mouseY, button)) {
            return;
        }

        ClickGUIModule module = clickGuiModule();
        if (module != null && module.isCloseMouseButton(button)) {
            this.close();
            return;
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean clickSidebar(int mouseX, int mouseY) {
        ModuleCategories.Category[] categories = ModuleCategories.Category.values();
        int tabX = this.panelX + SIDEBAR_INSET;
        int tabWidth = SIDEBAR_WIDTH - SIDEBAR_INSET * 2;
        int firstY = this.contentY() + PADDING;
        for (int i = 0; i < categories.length; i++) {
            int tabY = firstY + i * TAB_STRIDE;
            if (mouseX >= tabX && mouseX < tabX + tabWidth && mouseY >= tabY && mouseY < tabY + TAB_HEIGHT) {
                if (this.selected != categories[i]) {
                    this.selected = categories[i];
                    this.scroll = 0.0F;
                    this.targetScroll = 0.0F;
                }
                return true;
            }
        }
        return false;
    }

    private boolean clickContent(int mouseX, int mouseY, int button) {
        for (int i = 0; i < this.rows.size(); i++) {
            Row row = this.rows.get(i);
            if (!row.contains(mouseX, mouseY)) {
                continue;
            }
            switch (row.kind) {
                case MODULE: {
                    int toggleX = row.x + row.width - TOGGLE_WIDTH - 9;
                    if (button == 0 && mouseX >= toggleX && mouseX < toggleX + TOGGLE_WIDTH) {
                        row.module.toggle();
                    } else if (button == 0) {
                        if (!this.expanded.remove(row.module)) {
                            this.expanded.add(row.module);
                        }
                    } else if (button == 1) {
                        row.module.toggle();
                    }
                    return true;
                }
                case SETTING_BOOLEAN: {
                    BooleanProperty property = (BooleanProperty) row.property;
                    property.setValue(!property.getValue());
                    row.module.verifyValue(property.getName());
                    return true;
                }
                case SETTING_SLIDER: {
                    this.dragging = row.property;
                    this.applyDrag(mouseX);
                    return true;
                }
                case SETTING_MODE: {
                    ModeProperty property = (ModeProperty) row.property;
                    int count = property.getModes().length;
                    if (count > 0) {
                        int step = button == 1 ? count - 1 : 1;
                        property.setValue((property.getValue() + step) % count);
                        row.module.verifyValue(property.getName());
                    }
                    return true;
                }
                case SETTING_COLOR: {
                    this.dragging = row.property;
                    this.applyDrag(mouseX);
                    return true;
                }
                case KEYBIND: {
                    this.binding = row.module;
                    return true;
                }
                default:
                    return true;
            }
        }
        return false;
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        this.dragging = null;
        super.mouseReleased(mouseX, mouseY, state);
    }

    /** Maps the cursor onto the row the drag started on, so the pointer cannot slip to another. */
    private void applyDrag(int mouseX) {
        Row row = null;
        for (int i = 0; i < this.rows.size(); i++) {
            if (this.rows.get(i).property == this.dragging) {
                row = this.rows.get(i);
                break;
            }
        }
        if (row == null) {
            this.dragging = null;
            return;
        }
        int trackX = row.x + 6;
        int trackWidth = Math.max(1, row.width - 12);
        float fraction = Math.max(0.0F, Math.min(1.0F, (mouseX - trackX) / (float) trackWidth));

        if (row.kind == RowKind.SETTING_COLOR) {
            ColorProperty property = (ColorProperty) row.property;
            property.setValue(Color.HSBtoRGB(fraction, 0.85F, 1.0F) & 0xFFFFFF);
        } else {
            setFraction(row.property, fraction);
        }
        row.module.verifyValue(row.property.getName());
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }
        // Mouse input can reach a freshly opened screen before its first frame, so the panel and
        // content extents have to be measured here rather than assumed from the last draw.
        this.rebuild();
        int overflow = this.contentHeight - this.viewportHeight();
        if (overflow <= 0) {
            this.targetScroll = 0.0F;
            return;
        }
        this.targetScroll -= Math.signum(wheel) * SCROLL_STEP;
        this.targetScroll = Math.max(0.0F, Math.min(overflow, this.targetScroll));
    }

    private void updateScroll() {
        int overflow = Math.max(0, this.contentHeight - this.viewportHeight());
        this.targetScroll = Math.max(0.0F, Math.min(overflow, this.targetScroll));
        this.scroll += (this.targetScroll - this.scroll) * 0.35F;
        if (Math.abs(this.targetScroll - this.scroll) < 0.4F) {
            this.scroll = this.targetScroll;
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (this.binding != null) {
            this.binding.setKey(KeyBindUtil.isUnbindKey(keyCode) ? KeyBindUtil.NONE : keyCode);
            this.binding = null;
            return;
        }
        ClickGUIModule module = clickGuiModule();
        if (module != null && module.isCloseKey(keyCode)) {
            this.close();
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            this.close();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void close() {
        ClickGUIModule module = clickGuiModule();
        if (module != null && module.isEnabled()) {
            module.setEnabled(false);
            return;
        }
        this.mc.displayGuiScreen(null);
    }

    private static ClickGUIModule clickGuiModule() {
        if (Myau.moduleManager == null) {
            return null;
        }
        Module module = Myau.moduleManager.getModule("ClickGUI");
        return module instanceof ClickGUIModule ? (ClickGUIModule) module : null;
    }

    // ---------------------------------------------------------------- numeric properties

    /** Where a numeric property sits between its bounds, 0 to 1. */
    private static float fractionOf(Property<?> property) {
        double value;
        double min;
        double max;
        if (property instanceof FloatProperty) {
            FloatProperty typed = (FloatProperty) property;
            value = typed.getValue();
            min = typed.getMinimum();
            max = typed.getMaximum();
        } else if (property instanceof IntProperty) {
            IntProperty typed = (IntProperty) property;
            value = typed.getValue();
            min = typed.getMinimum();
            max = typed.getMaximum();
        } else if (property instanceof PercentProperty) {
            PercentProperty typed = (PercentProperty) property;
            value = typed.getValue();
            min = typed.getMinimum();
            max = typed.getMaximum();
        } else if (property instanceof LongProperty) {
            LongProperty typed = (LongProperty) property;
            value = typed.getValue();
            min = typed.getMinimum();
            max = typed.getMaximum();
        } else {
            return 0.0F;
        }
        double span = max - min;
        if (span <= 0.0) {
            return 0.0F;
        }
        return (float) Math.max(0.0, Math.min(1.0, (value - min) / span));
    }

    private static void setFraction(Property<?> property, float fraction) {
        if (property instanceof FloatProperty) {
            FloatProperty typed = (FloatProperty) property;
            float min = typed.getMinimum();
            float max = typed.getMaximum();
            // Two decimals: finer than that is not reachable with a mouse and looks like noise.
            typed.setValue(Math.round((min + (max - min) * fraction) * 100.0F) / 100.0F);
        } else if (property instanceof IntProperty) {
            IntProperty typed = (IntProperty) property;
            int min = typed.getMinimum();
            int max = typed.getMaximum();
            typed.setValue(Math.round(min + (max - min) * fraction));
        } else if (property instanceof PercentProperty) {
            PercentProperty typed = (PercentProperty) property;
            int min = typed.getMinimum();
            int max = typed.getMaximum();
            typed.setValue(Math.round(min + (max - min) * fraction));
        } else if (property instanceof LongProperty) {
            LongProperty typed = (LongProperty) property;
            long min = typed.getMinimum();
            long max = typed.getMaximum();
            typed.setValue(min + Math.round((max - min) * (double) fraction));
        }
    }

    private static String suffixOf(Module module) {
        String[] suffix = module.getSuffix();
        if (suffix == null || suffix.length == 0 || suffix[0] == null || suffix[0].isEmpty()) {
            return null;
        }
        return suffix[0];
    }

    // ---------------------------------------------------------------- text

    private void draw(FontRenderer font, String text, float x, float y, int color) {
        if (text == null) {
            return;
        }
        if (font != null) {
            font.drawString(text, x, y, color);
        } else {
            this.mc.fontRendererObj.drawString(text, (int) x, (int) y, color);
        }
    }

    private static float width(FontRenderer font, String text) {
        if (text == null) {
            return 0.0F;
        }
        return font != null
                ? FontManager.getStringWidth(font, text)
                : net.minecraft.client.Minecraft.getMinecraft().fontRendererObj.getStringWidth(text);
    }

    private static float height(FontRenderer font) {
        return font != null
                ? FontManager.getHeight(font)
                : net.minecraft.client.Minecraft.getMinecraft().fontRendererObj.FONT_HEIGHT;
    }
}
