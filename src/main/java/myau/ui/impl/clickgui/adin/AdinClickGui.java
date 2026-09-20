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
import java.util.List;

/**
 * A click GUI in the style of the adin client: one dark rounded window, a narrow sidebar whose
 * selection is an accent pill that slides and squashes between categories, a list of module rows
 * carrying their descriptions, and a settings overlay that reveals row by row over the list.
 * <p>
 * Layout and hit testing are the same pass. Every frame {@link #rebuild()} produces the list of
 * rectangles that will be drawn, and clicks, drags and scrolls resolve against that same list, so
 * a drawn row and its clickable area cannot disagree.
 * <p>
 * The window never scales below 1:1. Shrinking it would not shrink the bitmap fonts with it, so on
 * a small scaled resolution it is clipped and scrolled rather than squashed.
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

    private static final int ROW_HEIGHT = 40;
    private static final int ROW_STRIDE = 46;
    private static final int ROW_RADIUS = 6;
    private static final int LABEL_INSET = 10;
    private static final int DESCRIPTION_LINES = 2;
    private static final int DESCRIPTION_PITCH = 9;
    private static final int GEAR_SIZE = 16;

    private static final int OVERLAY_PADDING = 6;
    private static final int OVERLAY_RADIUS = 6;
    private static final int OVERLAY_HEADER = 20;
    private static final int OVERLAY_REVEAL_MILLIS = 180;
    private static final int ROW_STAGGER_MILLIS = 22;

    private static final int SETTING_ROW_HEIGHT = 22;
    private static final int SETTING_ROW_STRIDE = 24;
    private static final int BIND_WIDTH = 42;
    private static final int BIND_HEIGHT = 16;
    private static final int CONTROL_HEIGHT = 16;

    private static final float SCROLL_STEP = 28.0F;
    /** Seconds for the scroll to cover most of the remaining distance, frame rate independent. */
    private static final float SCROLL_SPEED = 18.0F;

    private static AdinClickGui instance;

    private enum RowKind {
        MODULE, SETTING_BOOLEAN, SETTING_SLIDER, SETTING_MODE, SETTING_COLOR, SETTING_TEXT, KEYBIND
    }

    private static final class Row {
        RowKind kind;
        Module module;
        Property<?> property;
        int index;
        float x;
        float y;
        float width;
        float height;

        boolean contains(int mouseX, int mouseY) {
            return mouseX >= this.x && mouseX < this.x + this.width
                    && mouseY >= this.y && mouseY < this.y + this.height;
        }
    }

    private final List<Row> rows = new ArrayList<Row>();
    private final AdinTransition pillY = new AdinTransition(0.0F, 210, AdinTransition.EASE_OUT_EXPO);

    private ModuleCategories.Category selected = ModuleCategories.Category.COMBAT;
    private boolean pillPlaced;

    private Module openModule;
    private long overlayOpenedAt;

    private float panelX;
    private float panelY;
    private int panelWidth;
    private int panelHeight;
    private int contentExtent;
    private float valueColumn = 24.0F;

    private float scroll;
    private float targetScroll;
    private long lastFrameNanos;
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
        AdinControls.reset();
        this.dragging = null;
        this.binding = null;
        this.pillPlaced = false;
        this.lastFrameNanos = 0L;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ---------------------------------------------------------------- fonts

    private static FontRenderer titleFont() {
        return FontManager.productSans18;
    }

    private static FontRenderer bodyFont() {
        return FontManager.productSans16;
    }

    private static FontRenderer smallFont() {
        return FontManager.productSans12 != null ? FontManager.productSans12 : FontManager.productSans16;
    }

    // ---------------------------------------------------------------- layout

    private void computePanel() {
        ScaledResolution sr = new ScaledResolution(this.mc);
        this.panelWidth = Math.max(170, Math.min(BASE_WIDTH, sr.getScaledWidth() - SCREEN_MARGIN * 2));
        this.panelHeight = Math.max(130, Math.min(BASE_HEIGHT, sr.getScaledHeight() - SCREEN_MARGIN * 2));
        this.panelX = (sr.getScaledWidth() - this.panelWidth) / 2;
        this.panelY = (sr.getScaledHeight() - this.panelHeight) / 2;
    }

    private float contentX() {
        return this.panelX + SIDEBAR_WIDTH;
    }

    private float contentY() {
        return this.panelY + TOP_BAR_HEIGHT;
    }

    private float contentWidth() {
        return this.panelWidth - SIDEBAR_WIDTH;
    }

    private float contentBottom() {
        return this.panelY + this.panelHeight;
    }

    private int viewportHeight() {
        return (int) (this.contentBottom() - this.contentY() - PADDING);
    }

    private float overlayX() {
        return this.contentX() + OVERLAY_PADDING;
    }

    private float overlayY() {
        return this.contentY() + OVERLAY_PADDING;
    }

    private float overlayWidth() {
        return this.contentWidth() - OVERLAY_PADDING * 2;
    }

    private float overlayHeight() {
        return this.contentBottom() - this.overlayY() - OVERLAY_PADDING;
    }

    private float overlayListY() {
        return this.overlayY() + OVERLAY_HEADER;
    }

    private void rebuild() {
        this.computePanel();
        this.rows.clear();

        if (this.openModule != null) {
            this.rebuildSettings();
            return;
        }

        float rowX = this.contentX() + PADDING;
        float rowWidth = Math.max(60.0F, this.contentWidth() - PADDING * 2);
        float y = this.contentY() + PADDING - this.scroll;
        int index = 0;

        for (Module module : ModuleCategories.modules(this.selected)) {
            if (module == null || module.isHidden()) {
                continue;
            }
            this.rows.add(row(RowKind.MODULE, module, null, index++, rowX, y, rowWidth, ROW_HEIGHT));
            y += ROW_STRIDE;
        }
        this.contentExtent = (int) Math.max(0.0F, (y + this.scroll) - (this.contentY() + PADDING));
    }

    private void rebuildSettings() {
        float rowX = this.overlayX() + OVERLAY_PADDING;
        float rowWidth = Math.max(60.0F, this.overlayWidth() - OVERLAY_PADDING * 2);
        float y = this.overlayListY() - this.scroll;
        int index = 0;

        FontRenderer font = bodyFont();
        float widestValue = 24.0F;

        for (Property<?> property : properties(this.openModule)) {
            if (property == null || !property.isVisible()) {
                continue;
            }
            RowKind kind = kindOf(property);
            this.rows.add(row(kind, this.openModule, property, index++, rowX, y, rowWidth, SETTING_ROW_HEIGHT));
            y += SETTING_ROW_STRIDE;
            if (kind == RowKind.SETTING_SLIDER) {
                widestValue = Math.max(widestValue, AdinControls.width(font, boundsText(property)) + 8.0F);
            }
        }
        this.rows.add(row(RowKind.KEYBIND, this.openModule, null, index, rowX, y, rowWidth, SETTING_ROW_HEIGHT));
        y += SETTING_ROW_STRIDE;

        // Every slider shares one value column, so their tracks all end in line.
        this.valueColumn = widestValue;
        this.contentExtent = (int) Math.max(0.0F, (y + this.scroll) - this.overlayListY());
    }

    private static Row row(RowKind kind, Module module, Property<?> property, int index,
                           float x, float y, float width, float height) {
        Row created = new Row();
        created.kind = kind;
        created.module = module;
        created.property = property;
        created.index = index;
        created.x = x;
        created.y = y;
        created.width = width;
        created.height = height;
        return created;
    }

    private static List<Property<?>> properties(Module module) {
        if (Myau.propertyManager == null || module == null) {
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
        return RowKind.SETTING_TEXT;
    }

    // Sub-rectangles inside a row, shared by drawing and hit testing.
    private static float toggleX(Row row) {
        return row.x + row.width - LABEL_INSET - AdinControls.TOGGLE_WIDTH;
    }

    private static float gearX(Row row) {
        return toggleX(row) - 6.0F - GEAR_SIZE;
    }

    private static float controlX(Row row) {
        return row.x + Math.max(64.0F, row.width * 0.42F);
    }

    private static float controlWidth(Row row) {
        return Math.max(30.0F, row.x + row.width - LABEL_INSET - controlX(row));
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        long now = System.nanoTime();
        float delta = this.lastFrameNanos == 0L ? 0.0F
                : Math.min(0.1F, Math.max(0.0F, (now - this.lastFrameNanos) / 1.0e9F));
        this.lastFrameNanos = now;

        this.drawDefaultBackground();
        this.rebuild();
        // Dragging is applied before the layout is drawn, so the knob lands under the cursor on
        // this frame rather than the next one.
        if (this.dragging != null) {
            this.applyDrag(mouseX);
            this.rebuild();
        }
        this.updateScroll(delta);

        int accent = AdinTheme.accent();

        RoundedUtils.drawRound(this.panelX, this.panelY, this.panelWidth, this.panelHeight,
                RADIUS, AdinTheme.color(AdinTheme.MAIN));
        RoundedUtils.drawRoundedRectRise(this.panelX, this.panelY, SIDEBAR_WIDTH, this.panelHeight,
                RADIUS, AdinTheme.SIDEBAR, true, false, false, true);

        this.drawBrand(accent);
        this.drawSidebar(accent, mouseX, mouseY);

        if (this.openModule == null) {
            this.drawModuleList(accent, mouseX, mouseY);
        } else {
            this.drawOverlay(accent, mouseX, mouseY);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawBrand(int accent) {
        FontRenderer font = titleFont();
        String name = "Ziggy";
        String suffix = ".client";
        float textY = this.panelY + (TOP_BAR_HEIGHT - AdinControls.height(font)) / 2.0F;
        float x = this.panelX + SIDEBAR_INSET;
        AdinControls.drawText(font, name, x, textY, AdinTheme.TEXT);
        AdinControls.drawText(font, suffix, x + AdinControls.width(font, name), textY, accent);
    }

    private void drawSidebar(int accent, int mouseX, int mouseY) {
        ModuleCategories.Category[] categories = ModuleCategories.Category.values();
        float tabX = this.panelX + SIDEBAR_INSET;
        float tabWidth = SIDEBAR_WIDTH - SIDEBAR_INSET * 2;
        float firstY = this.contentY() + PADDING;

        int selectedIndex = 0;
        for (int i = 0; i < categories.length; i++) {
            if (categories[i] == this.selected) {
                selectedIndex = i;
            }
        }
        float targetY = firstY + selectedIndex * TAB_STRIDE;
        if (!this.pillPlaced) {
            this.pillY.snap(targetY);
            this.pillPlaced = true;
        }
        this.pillY.set(targetY);

        float pillTop = this.pillY.value();
        float pillHeight = TAB_HEIGHT * (1.0F + PILL_STRETCH * this.pillY.flight());
        RoundedUtils.drawRound(tabX, pillTop - (pillHeight - TAB_HEIGHT) * 0.5F, tabWidth, pillHeight,
                PILL_RADIUS, AdinTheme.color(accent));

        FontRenderer font = bodyFont();
        for (int i = 0; i < categories.length; i++) {
            float tabY = firstY + i * TAB_STRIDE;
            float centre = tabY + TAB_HEIGHT * 0.5F;
            boolean onPill = centre >= pillTop && centre < pillTop + TAB_HEIGHT;
            boolean hovered = mouseX >= tabX && mouseX < tabX + tabWidth
                    && mouseY >= tabY && mouseY < tabY + TAB_HEIGHT;
            int textColor = onPill ? AdinTheme.ON_ACCENT : (hovered ? AdinTheme.TEXT : AdinTheme.DIM);
            AdinControls.drawText(font, categories[i].getLabel(), tabX + 9.0F,
                    tabY + (TAB_HEIGHT - AdinControls.height(font)) / 2.0F, textColor);
        }
    }

    private void drawModuleList(int accent, int mouseX, int mouseY) {
        float clipY = this.contentY();
        float clipHeight = this.contentBottom() - clipY;
        AdinControls.scissor(this.contentX(), clipY, this.contentWidth(), clipHeight);

        FontRenderer nameFont = bodyFont();
        FontRenderer descriptionFont = smallFont();

        for (int i = 0; i < this.rows.size(); i++) {
            Row row = this.rows.get(i);
            if (row.y + row.height < clipY || row.y > clipY + clipHeight) {
                continue;
            }
            boolean hovered = row.contains(mouseX, mouseY) && this.inViewport(mouseY);
            RoundedUtils.drawRound(row.x, row.y, row.width, row.height, ROW_RADIUS,
                    AdinTheme.color(hovered
                            ? AdinTheme.lerp(AdinTheme.ROW, AdinTheme.CONTROL, 0.5F)
                            : AdinTheme.ROW));

            float textRight = gearX(row) - 6.0F;
            float available = textRight - row.x - LABEL_INSET;
            AdinControls.drawText(nameFont,
                    AdinControls.fit(row.module.getName(), available, nameFont),
                    row.x + LABEL_INSET, row.y + 6.0F,
                    row.module.isEnabled() ? AdinTheme.TEXT : AdinTheme.MUTED);

            String[] lines = wrap(row.module.getDescription(), available, descriptionFont);
            for (int line = 0; line < lines.length; line++) {
                AdinControls.drawText(descriptionFont, lines[line], row.x + LABEL_INSET,
                        row.y + 19.0F + line * DESCRIPTION_PITCH, AdinTheme.MUTED);
            }

            float gear = gearX(row);
            this.drawGear(gear, row.y + (row.height - GEAR_SIZE) / 2.0F, accent,
                    mouseX >= gear && mouseX < gear + GEAR_SIZE
                            && mouseY >= row.y && mouseY < row.y + row.height);

            AdinControls.drawToggle(row.module, toggleX(row),
                    row.y + (row.height - AdinControls.TOGGLE_HEIGHT) / 2.0F,
                    AdinControls.TOGGLE_WIDTH, AdinControls.TOGGLE_HEIGHT,
                    row.module.isEnabled(), accent);
        }
        AdinControls.releaseScissor();
    }

    /** A gear built from rects rather than a texture, so there is no atlas to ship or bind. */
    private void drawGear(float x, float y, int accent, boolean hovered) {
        int color = hovered ? accent : AdinTheme.DIM;
        float centreX = x + GEAR_SIZE / 2.0F;
        float centreY = y + GEAR_SIZE / 2.0F;
        RoundedUtils.drawRound(centreX - 1.0F, centreY - 5.0F, 2.0F, 10.0F, 1.0F, AdinTheme.color(color));
        RoundedUtils.drawRound(centreX - 5.0F, centreY - 1.0F, 10.0F, 2.0F, 1.0F, AdinTheme.color(color));
        RoundedUtils.drawRound(centreX - 4.0F, centreY - 4.0F, 8.0F, 8.0F, 4.0F, AdinTheme.color(color));
        RoundedUtils.drawRound(centreX - 1.5F, centreY - 1.5F, 3.0F, 3.0F, 1.5F,
                AdinTheme.color(AdinTheme.ROW));
    }

    private void drawOverlay(int accent, int mouseX, int mouseY) {
        RoundedUtils.drawRound(this.overlayX(), this.overlayY(), this.overlayWidth(), this.overlayHeight(),
                OVERLAY_RADIUS, AdinTheme.color(AdinTheme.OVERLAY));

        FontRenderer font = bodyFont();
        float backX = this.overlayX() + OVERLAY_PADDING;
        boolean backHovered = mouseX >= backX - 2 && mouseX < backX + 14
                && mouseY >= this.overlayY() && mouseY < this.overlayY() + OVERLAY_HEADER;
        float headerTextY = this.overlayY() + (OVERLAY_HEADER - AdinControls.height(font)) / 2.0F;
        AdinControls.drawText(font, "<", backX, headerTextY, backHovered ? accent : AdinTheme.DIM);
        AdinControls.drawText(font, this.openModule.getName(), backX + 12.0F, headerTextY, AdinTheme.TEXT);

        float clipY = this.overlayListY();
        float clipHeight = this.overlayY() + this.overlayHeight() - clipY - OVERLAY_PADDING;
        AdinControls.scissor(this.overlayX(), clipY, this.overlayWidth(), clipHeight);

        long elapsed = System.currentTimeMillis() - this.overlayOpenedAt;
        for (int i = 0; i < this.rows.size(); i++) {
            Row row = this.rows.get(i);
            if (row.y + row.height < clipY || row.y > clipY + clipHeight) {
                continue;
            }
            // Staggered reveal: each row fades and slides in just after the one above it.
            float reveal = Math.max(0.0F, Math.min(1.0F,
                    (elapsed - row.index * (long) ROW_STAGGER_MILLIS) / (float) OVERLAY_REVEAL_MILLIS));
            if (reveal <= 0.0F) {
                continue;
            }
            this.drawSettingRow(row, row.y + (1.0F - reveal) * 6.0F, reveal, accent, mouseX, mouseY);
        }
        AdinControls.releaseScissor();
    }

    private void drawSettingRow(Row row, float y, float reveal, int accent, int mouseX, int mouseY) {
        FontRenderer font = bodyFont();
        int labelColor = AdinTheme.alpha(AdinTheme.TEXT, reveal);
        int mutedColor = AdinTheme.alpha(AdinTheme.DIM, reveal);
        int fadedAccent = AdinTheme.alpha(accent, reveal);

        float controlX = controlX(row);
        float controlWidth = controlWidth(row);
        float centreY = y + row.height * 0.5F;
        String label = row.kind == RowKind.KEYBIND ? "Bind" : row.property.getName();
        AdinControls.drawText(font,
                AdinControls.fit(label, controlX - row.x - LABEL_INSET - 4.0F, font),
                row.x + LABEL_INSET, centreY - AdinControls.height(font) * 0.5F, labelColor);

        switch (row.kind) {
            case SETTING_BOOLEAN: {
                BooleanProperty property = (BooleanProperty) row.property;
                AdinControls.drawToggle(property,
                        row.x + row.width - LABEL_INSET - AdinControls.TOGGLE_WIDTH,
                        centreY - AdinControls.TOGGLE_HEIGHT / 2.0F,
                        AdinControls.TOGGLE_WIDTH, AdinControls.TOGGLE_HEIGHT,
                        property.getValue(), fadedAccent);
                break;
            }
            case SETTING_SLIDER: {
                AdinControls.drawSlider(row.property, controlX, y, controlWidth, row.height,
                        fractionOf(row.property), row.property.getValuePrompt(), this.valueColumn,
                        fadedAccent, font, this.dragging == row.property);
                break;
            }
            case SETTING_MODE: {
                ModeProperty property = (ModeProperty) row.property;
                AdinControls.drawSegmented(property, controlX, centreY - CONTROL_HEIGHT / 2.0F,
                        controlWidth, CONTROL_HEIGHT, property.getModes(), property.getValue(), font);
                break;
            }
            case SETTING_COLOR: {
                this.drawColorControl(row, controlX, controlWidth, centreY, reveal);
                break;
            }
            case KEYBIND: {
                boolean listening = this.binding == row.module;
                String value = listening ? "..." : KeyBindUtil.getKeyName(row.module.getKey());
                AdinControls.drawField(row.x + row.width - LABEL_INSET - BIND_WIDTH,
                        centreY - BIND_HEIGHT / 2.0F, BIND_WIDTH, BIND_HEIGHT, value,
                        listening ? fadedAccent : labelColor, font);
                break;
            }
            case SETTING_TEXT:
            default: {
                String value = AdinControls.fit(String.valueOf(row.property.getValuePrompt()), controlWidth, font);
                AdinControls.drawText(font, value,
                        row.x + row.width - LABEL_INSET - AdinControls.width(font, value),
                        centreY - AdinControls.height(font) * 0.5F, mutedColor);
                break;
            }
        }
    }

    /** A hue strip with a swatch: one drag covers every hue at full saturation. */
    private void drawColorControl(Row row, float controlX, float controlWidth, float centreY, float reveal) {
        ColorProperty property = (ColorProperty) row.property;
        int rgb = property.getValue() == null ? 0 : property.getValue();
        float trackWidth = colorTrackWidth(controlWidth);

        int steps = Math.max(1, (int) (trackWidth / 2.0F));
        float stepWidth = trackWidth / steps;
        float trackY = centreY - AdinControls.TRACK_HEIGHT * 0.5F;
        for (int i = 0; i < steps; i++) {
            int hue = Color.HSBtoRGB(i / (float) steps, 0.85F, 1.0F);
            RoundedUtils.drawRound(controlX + i * stepWidth, trackY, stepWidth + 0.5F,
                    AdinControls.TRACK_HEIGHT, 0.0F,
                    AdinTheme.color(AdinTheme.alpha(0xFF000000 | (hue & 0xFFFFFF), reveal)));
        }

        float[] hsb = Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
        float knobX = controlX + (trackWidth - AdinControls.KNOB_WIDTH) * hsb[0];
        RoundedUtils.drawRound(knobX, centreY - AdinControls.KNOB_HEIGHT * 0.5F,
                AdinControls.KNOB_WIDTH, AdinControls.KNOB_HEIGHT, 2.0F,
                AdinTheme.color(AdinTheme.alpha(0xFF000000 | (rgb & 0xFFFFFF), reveal)));

        RoundedUtils.drawRound(row.x + row.width - LABEL_INSET - 16.0F, centreY - 5.0F, 16.0F, 10.0F,
                3.0F, AdinTheme.color(AdinTheme.alpha(0xFF000000 | (rgb & 0xFFFFFF), reveal)));
    }

    private static float colorTrackWidth(float controlWidth) {
        return Math.max(10.0F, controlWidth - 22.0F);
    }

    /** Wraps a description onto at most {@link #DESCRIPTION_LINES} lines. */
    private static String[] wrap(String text, float available, FontRenderer font) {
        if (text == null || text.isEmpty() || available <= 0.0F) {
            return new String[0];
        }
        List<String> lines = new ArrayList<String>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (AdinControls.width(font, candidate) <= available || line.length() == 0) {
                line.setLength(0);
                line.append(candidate);
            } else {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
                if (lines.size() == DESCRIPTION_LINES) {
                    break;
                }
            }
        }
        if (lines.size() < DESCRIPTION_LINES && line.length() > 0) {
            lines.add(line.toString());
        }
        while (lines.size() > DESCRIPTION_LINES) {
            lines.remove(lines.size() - 1);
        }
        if (!lines.isEmpty()) {
            int last = lines.size() - 1;
            lines.set(last, AdinControls.fit(lines.get(last), available, font));
        }
        return lines.toArray(new String[0]);
    }

    // ---------------------------------------------------------------- input

    private boolean inViewport(int mouseY) {
        return mouseY >= this.contentY() && mouseY < this.contentBottom();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        this.rebuild();

        if (this.binding != null) {
            if (KeyBindUtil.isBindableMouseButton(button)) {
                this.binding.setKey(KeyBindUtil.mouseButtonToKey(button));
            }
            this.binding = null;
            return;
        }

        if (this.clickSidebar(mouseX, mouseY)) {
            return;
        }

        if (this.openModule != null) {
            float backX = this.overlayX() + OVERLAY_PADDING;
            if (mouseX >= backX - 2 && mouseX < backX + 14
                    && mouseY >= this.overlayY() && mouseY < this.overlayY() + OVERLAY_HEADER) {
                this.closeOverlay();
                return;
            }
        }

        if (this.inViewport(mouseY) && this.clickRows(mouseX, mouseY, button)) {
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
        float tabX = this.panelX + SIDEBAR_INSET;
        float tabWidth = SIDEBAR_WIDTH - SIDEBAR_INSET * 2;
        float firstY = this.contentY() + PADDING;
        for (int i = 0; i < categories.length; i++) {
            float tabY = firstY + i * TAB_STRIDE;
            if (mouseX >= tabX && mouseX < tabX + tabWidth && mouseY >= tabY && mouseY < tabY + TAB_HEIGHT) {
                if (this.selected != categories[i]) {
                    this.selected = categories[i];
                    this.openModule = null;
                    this.scroll = 0.0F;
                    this.targetScroll = 0.0F;
                }
                return true;
            }
        }
        return false;
    }

    private boolean clickRows(int mouseX, int mouseY, int button) {
        for (int i = 0; i < this.rows.size(); i++) {
            Row row = this.rows.get(i);
            if (!row.contains(mouseX, mouseY)) {
                continue;
            }
            switch (row.kind) {
                case MODULE: {
                    float gear = gearX(row);
                    if (mouseX >= toggleX(row)) {
                        row.module.toggle();
                    } else if (mouseX >= gear && mouseX < gear + GEAR_SIZE) {
                        this.openOverlay(row.module);
                    } else if (button == 1) {
                        this.openOverlay(row.module);
                    } else {
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
                case SETTING_SLIDER:
                case SETTING_COLOR: {
                    this.dragging = row.property;
                    this.applyDrag(mouseX);
                    return true;
                }
                case SETTING_MODE: {
                    ModeProperty property = (ModeProperty) row.property;
                    String[] modes = property.getModes();
                    if (modes.length > 0) {
                        float controlX = controlX(row);
                        float controlWidth = controlWidth(row);
                        int picked;
                        if (mouseX >= controlX && mouseX < controlX + controlWidth) {
                            picked = (int) ((mouseX - controlX) / (controlWidth / modes.length));
                            picked = Math.max(0, Math.min(modes.length - 1, picked));
                        } else {
                            picked = (property.getValue() + (button == 1 ? modes.length - 1 : 1)) % modes.length;
                        }
                        property.setValue(picked);
                        row.module.verifyValue(property.getName());
                    }
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

    private void openOverlay(Module module) {
        this.openModule = module;
        this.overlayOpenedAt = System.currentTimeMillis();
        this.scroll = 0.0F;
        this.targetScroll = 0.0F;
    }

    private void closeOverlay() {
        this.openModule = null;
        this.dragging = null;
        this.binding = null;
        this.scroll = 0.0F;
        this.targetScroll = 0.0F;
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        this.dragging = null;
        super.mouseReleased(mouseX, mouseY, state);
    }

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
        float controlX = controlX(row);
        float controlWidth = controlWidth(row);

        if (row.kind == RowKind.SETTING_COLOR) {
            float trackWidth = colorTrackWidth(controlWidth);
            float fraction = Math.max(0.0F, Math.min(1.0F, (mouseX - controlX) / trackWidth));
            ((ColorProperty) row.property).setValue(Color.HSBtoRGB(fraction, 0.85F, 1.0F) & 0xFFFFFF);
        } else {
            float fraction = AdinControls.sliderFraction(mouseX, controlX, controlWidth, this.valueColumn);
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
        // Mouse input can reach a freshly opened screen before its first frame, so the extents
        // have to be measured here rather than assumed from the last draw.
        this.rebuild();
        int overflow = this.contentExtent - this.viewportHeight();
        if (overflow <= 0) {
            this.targetScroll = 0.0F;
            return;
        }
        this.targetScroll -= Math.signum(wheel) * SCROLL_STEP;
        this.targetScroll = Math.max(0.0F, Math.min(overflow, this.targetScroll));
    }

    /** Frame-rate independent, so scrolling feels the same at 60 and at 300 FPS. */
    private void updateScroll(float delta) {
        int overflow = Math.max(0, this.contentExtent - this.viewportHeight());
        this.targetScroll = Math.max(0.0F, Math.min(overflow, this.targetScroll));
        float factor = 1.0F - (float) Math.exp(-SCROLL_SPEED * delta);
        this.scroll += (this.targetScroll - this.scroll) * Math.max(0.0F, Math.min(1.0F, factor));
        if (Math.abs(this.targetScroll - this.scroll) < 0.25F) {
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
        if (keyCode == Keyboard.KEY_ESCAPE && this.openModule != null) {
            this.closeOverlay();
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

    /** The widest value this slider can show, used to size the shared value column. */
    private static String boundsText(Property<?> property) {
        if (property instanceof FloatProperty) {
            FloatProperty typed = (FloatProperty) property;
            return String.valueOf(Math.max(Math.abs(typed.getMinimum()), Math.abs(typed.getMaximum())));
        }
        if (property instanceof IntProperty) {
            IntProperty typed = (IntProperty) property;
            return String.valueOf(Math.max(Math.abs(typed.getMinimum()), Math.abs(typed.getMaximum())));
        }
        if (property instanceof PercentProperty) {
            PercentProperty typed = (PercentProperty) property;
            return String.valueOf(Math.max(Math.abs(typed.getMinimum()), Math.abs(typed.getMaximum())));
        }
        if (property instanceof LongProperty) {
            LongProperty typed = (LongProperty) property;
            return String.valueOf(Math.max(Math.abs(typed.getMinimum()), Math.abs(typed.getMaximum())));
        }
        return "000";
    }

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
}
