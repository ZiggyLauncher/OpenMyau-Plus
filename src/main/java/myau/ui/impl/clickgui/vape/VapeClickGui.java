package myau.ui.impl.clickgui.vape;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import myau.Myau;
import myau.config.Config;
import myau.module.Module;
import myau.module.modules.ClickGUIModule;
import myau.property.Property;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ColorProperty;
import myau.property.properties.ItemListProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.TextProperty;
import myau.ui.ModuleCategories;
import myau.mixin.IAccessorEntityRenderer;
import myau.util.KeyBindUtil;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.ShaderGroup;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Vape v4 click GUI in its default "Frames" style: the main "GUI" frame with a button per
 * category, a floating window per open category with its modules and their settings folded in,
 * a search bar pinned to the top of the screen, and the settings page behind the gear.
 * <p>
 * Everything is drawn in Vape's own units (two screen pixels per unit at scale 1, 2.4 at the
 * automatic 1080p scale) with its shaders, textures and font, so it lines up with the original
 * piece for piece. The ported classes keep the names of the Vape classes they come from in
 * their docs.
 */
public final class VapeClickGui extends GuiScreen {
    private static final File STATE_FILE = new File("./config/Myau/", "clickgui_vape.txt");
    private static final ResourceLocation BLUR_SHADER = new ResourceLocation("shaders/post/blur.json");
    private static final String BLUR_SHADER_NAME = BLUR_SHADER.toString();
    private static VapeClickGui instance;

    final List<VFrame> frames = new ArrayList<VFrame>();
    private final Map<ModuleCategories.Category, CategoryFrame> categoryFrames =
            new EnumMap<ModuleCategories.Category, CategoryFrame>(ModuleCategories.Category.class);
    private MainFrame main;
    private SearchFrame search;
    private SettingsFrame settings;
    private ListFrame friendsFrame;
    private ListFrame profilesFrame;
    ListSource friends;
    ListSource profiles;

    /** Vape's "edit hidden modules" mode ({@code ClientSettings.moduleSearchActive}). */
    boolean editingHidden;
    /** The module row waiting for a key to bind. */
    ModuleRow binding;
    DropdownRow openDropdown;
    TextEditable focused;
    private ListRow openList;
    private ListFrame openListFrame;
    private final Set<String> hiddenModules = new HashSet<String>();
    private boolean built;
    /** Whether this GUI put the background blur shader on. */
    private boolean blurring;
    private JsonObject savedFrames;

    public static VapeClickGui getInstance() {
        if (instance == null) {
            instance = new VapeClickGui();
        }
        return instance;
    }

    private VapeClickGui() {
    }

    // ------------------------------------------------------------------ building

    private static String iconFor(ModuleCategories.Category category) {
        switch (category) {
            case COMBAT:
                return "combat";
            case MOVEMENT:
                return "newblatant";
            case RENDER:
                return "render";
            case PLAYER:
                return "utility";
            case SCRIPTS:
                return "newmacros";
            default:
                return "world";
        }
    }

    private void build() {
        this.built = true;
        this.loadState();
        this.search = new SearchFrame(this);
        this.main = new MainFrame(this);
        this.settings = new SettingsFrame(this);
        this.frames.add(this.search);
        this.frames.add(this.main);
        this.frames.add(this.settings);
        for (ModuleCategories.Category category : ModuleCategories.Category.values()) {
            CategoryFrame frame = new CategoryFrame(this, category, iconFor(category));
            this.categoryFrames.put(category, frame);
            this.frames.add(frame);
        }
        this.friends = new FriendsSource();
        this.profiles = new ProfilesSource();
        this.profilesFrame = new ListFrame(this, this.profiles, null, "Profiles");
        this.friendsFrame = new ListFrame(this, this.friends, null, "Friends");
        this.frames.add(this.profilesFrame);
        this.frames.add(this.friendsFrame);
        this.buildSettingsRows();
        this.buildMenu();
        this.applySavedFrames();
    }

    /** The main frame's rows ({@code populateDefaultMenu}). */
    private void buildMenu() {
        this.main.components.clear();
        this.main.add(new MenuRows.Divider(VapeTheme.HOVER));
        for (ModuleCategories.Category category : ModuleCategories.Category.values()) {
            final CategoryFrame frame = this.categoryFrames.get(category);
            if (category == ModuleCategories.Category.SCRIPTS && ModuleCategories.modules(category).isEmpty()) {
                continue;
            }
            this.main.add(new MenuRows.NavButton(category.getLabel(), frame.icon, 0, frame, new Runnable() {
                @Override
                public void run() {
                    VapeClickGui.this.toggleFrame(frame);
                }
            }));
        }
        this.main.add(new MenuRows.Spacer(2.0));
        this.main.add(new MenuRows.Label("  MISC", 0.625));
        this.main.add(new MenuRows.Spacer(2.0));
        this.main.add(new MenuRows.Divider(VapeTheme.HOVER));
        this.main.add(new MenuRows.NavButton("Friends", null, 0, this.friendsFrame, new Runnable() {
            @Override
            public void run() {
                VapeClickGui.this.toggleFrame(VapeClickGui.this.friendsFrame);
            }
        }));
        this.main.add(new MenuRows.NavButton("Profiles", null, 0, this.profilesFrame, new Runnable() {
            @Override
            public void run() {
                VapeClickGui.this.toggleFrame(VapeClickGui.this.profilesFrame);
            }
        }));
    }

    private void buildSettingsRows() {
        this.settings.components.clear();
        ClickGUIModule module = VapeTheme.module();
        if (module == null) {
            return;
        }
        for (Property<?> property : properties(module)) {
            ValueRow row = this.createValueRow(property, null);
            if (row != null) {
                row.background = VapeTheme.FRAME;
                this.settings.add(row);
            }
        }
    }

    private static List<Property<?>> properties(Module module) {
        if (Myau.propertyManager == null) {
            return Collections.emptyList();
        }
        List<Property<?>> list = Myau.propertyManager.properties.get(module);
        return list == null ? Collections.<Property<?>>emptyList() : list;
    }

    /** A module row followed by its settings ({@code buildValueComponents}). */
    ModuleRow addModuleRow(VFrame frame, Module module) {
        ModuleRow row = new ModuleRow(this, module);
        frame.add(row);
        for (Property<?> property : properties(module)) {
            ValueRow value = this.createValueRow(property, row);
            if (value != null) {
                frame.add(value);
                row.values.add(value);
            }
        }
        return row;
    }

    /** {@code ValueComponentFactory.createValueComponent}. */
    private ValueRow createValueRow(Property<?> property, ModuleRow owner) {
        if (property instanceof BooleanProperty) {
            return new ToggleRow(this, (BooleanProperty) property, owner);
        }
        if (SliderRow.handles(property)) {
            return new SliderRow(this, property, owner);
        }
        if (property instanceof ModeProperty) {
            return new DropdownRow(this, (ModeProperty) property, owner);
        }
        if (property instanceof ColorProperty) {
            return new ColorRow(this, (ColorProperty) property, owner);
        }
        if (property instanceof ItemListProperty) {
            return new ListRow(this, (ItemListProperty) property, owner);
        }
        if (property instanceof TextProperty) {
            return new TextRow(this, (TextProperty) property, owner);
        }
        return null;
    }

    // ------------------------------------------------------------------ screen

    @Override
    public void initGui() {
        super.initGui();
        Keyboard.enableRepeatEvents(true);
        if (!this.built) {
            this.build();
        } else {
            for (CategoryFrame frame : this.categoryFrames.values()) {
                frame.rebuild();
            }
            this.buildMenu();
        }
        CategoryFrame scripts = this.categoryFrames.get(ModuleCategories.Category.SCRIPTS);
        if (scripts != null && ModuleCategories.modules(ModuleCategories.Category.SCRIPTS).isEmpty()) {
            // No scripts loaded: the frame has no button to close it with, so keep it shut.
            scripts.visible = false;
        }
        this.binding = null;
        this.openDropdown = null;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        ScaledResolution resolution = new ScaledResolution(this.mc);
        ClickGUIModule module = VapeTheme.module();
        this.updateBackgroundBlur(module == null || module.vapeBlur.getValue());

        VapeRender.begin(resolution.getScaleFactor());
        try {
            double mx = VapeRender.mouseX();
            double my = VapeRender.mouseY();
            this.syncFrames();
            for (VFrame frame : new ArrayList<VFrame>(this.frames)) {
                if (frame.visible) {
                    frame.update(mx, my);
                }
            }
            for (VFrame frame : this.frames) {
                if (frame.visible) {
                    frame.layout();
                }
            }
            this.updateHover(mx, my);
            for (VFrame frame : new ArrayList<VFrame>(this.frames)) {
                if (frame.visible) {
                    frame.render(mx, my);
                }
            }
            if (this.openDropdown != null) {
                this.openDropdown.renderPopup(mx, my);
            }
        } finally {
            VapeRender.end();
        }
    }

    /**
     * "Blur background", done the way Vape does it on this version
     * ({@code ShaderGroupRenderStateManager}): Minecraft's own {@code blur.json} post-process
     * shader runs over the world while the GUI is open, and the GUI draws crisp on top.
     * <p>
     * This replaces a masked offscreen blur that painted the whole screen white on setups where
     * that framebuffer pass does not work (OptiFine). The vanilla shader pipeline cannot do that:
     * where post shaders are unavailable - no framebuffers, anaglyph 3D, an OptiFine shader pack,
     * or another shader already running - the blur is simply skipped and the game shows through.
     */
    private void updateBackgroundBlur(boolean wanted) {
        if (!wanted) {
            this.stopBackgroundBlur();
            return;
        }
        if (this.blurring || this.mc.theWorld == null || this.mc.entityRenderer == null) {
            return;
        }
        if (!OpenGlHelper.shadersSupported || !OpenGlHelper.isFramebufferEnabled()
                || this.mc.gameSettings.anaglyph || this.mc.entityRenderer.isShaderActive() || optifineShaderPack()) {
            return;
        }
        try {
            ((IAccessorEntityRenderer) this.mc.entityRenderer).callLoadShader(BLUR_SHADER);
            ShaderGroup group = this.mc.entityRenderer.getShaderGroup();
            this.blurring = group != null && BLUR_SHADER_NAME.equals(group.getShaderGroupName());
        } catch (Throwable throwable) {
            this.blurring = false;
        }
    }

    /** Takes the blur off again - only if the running shader is still ours. */
    private void stopBackgroundBlur() {
        if (!this.blurring) {
            return;
        }
        this.blurring = false;
        try {
            ShaderGroup group = this.mc.entityRenderer.getShaderGroup();
            if (group != null && BLUR_SHADER_NAME.equals(group.getShaderGroupName())) {
                this.mc.entityRenderer.stopUseShader();
            }
        } catch (Throwable ignored) {
        }
    }

    /** OptiFine shader packs replace the post-process pipeline; Vape leaves the blur off there too. */
    private static boolean optifineShaderPack() {
        for (String name : new String[]{"Config", "net.optifine.Config"}) {
            try {
                Object shaders = Class.forName(name).getMethod("isShaders").invoke(null);
                if (shaders instanceof Boolean) {
                    return (Boolean) shaders;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** Keeps the settings page, the main frame and the list editor in step. */
    private void syncFrames() {
        if (this.openList != null) {
            ListRow row = this.openList;
            boolean stillThere = row.frame != null && row.frame.visible && !row.frame.collapsed
                    && row.frame.components.contains(row) && row.isShown();
            if (!stillThere) {
                this.closeListEditor();
            }
        }
        if (this.openDropdown != null) {
            DropdownRow row = this.openDropdown;
            if (row.frame == null || !row.frame.visible || !row.frame.components.contains(row) || !row.frame.isShown(row)) {
                this.openDropdown = null;
            }
        }
        if (this.binding != null && (this.binding.frame == null || !this.binding.frame.visible)) {
            this.binding = null;
        }
    }

    private VFrame topFrameAt(double mouseX, double mouseY) {
        for (int i = this.frames.size() - 1; i >= 0; i--) {
            VFrame frame = this.frames.get(i);
            if (frame.visible && frame.contains(mouseX, mouseY)) {
                return frame;
            }
        }
        return null;
    }

    private void updateHover(double mouseX, double mouseY) {
        boolean overPopup = this.openDropdown != null && this.openDropdown.popupContains(mouseX, mouseY);
        VFrame top = overPopup ? null : this.topFrameAt(mouseX, mouseY);
        for (VFrame frame : this.frames) {
            if (frame.visible) {
                frame.updateHover(mouseX, mouseY, frame == top);
            }
        }
    }

    void bringToFront(VFrame frame) {
        if (this.frames.remove(frame)) {
            this.frames.add(frame);
        }
        if (this.openListFrame != null && this.openList != null && this.openList.frame == frame) {
            // The list editor stays on top of the frame it belongs to.
            this.frames.remove(this.openListFrame);
            this.frames.add(this.openListFrame);
        }
    }

    // ------------------------------------------------------------------ frames

    void toggleFrame(VFrame frame) {
        frame.visible = !frame.visible;
        if (frame.visible) {
            this.positionIfNeeded(frame);
            this.bringToFront(frame);
        }
        this.saveState();
    }

    /**
     * First-time placement ({@code positionFrameIfNeeded}): the first free spot scanning right
     * from (32, 32), so category windows open side by side.
     */
    private void positionIfNeeded(VFrame frame) {
        if (frame.positioned) {
            return;
        }
        double candidateX = 32.0;
        double candidateY = 32.0;
        double screenWidth = VapeRender.screenWidth();
        for (int guard = 0; guard < 200000; guard++) {
            boolean overlaps = false;
            for (VFrame other : this.frames) {
                if (other == frame || !other.visible || other == this.search) {
                    continue;
                }
                other.layout();
                if (VFrame.inside(candidateX, candidateY, other.x - 2.0, other.y - 4.0, other.width + 4.0, other.height() + 8.0)) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
                break;
            }
            candidateX += 2.0;
            if (candidateX + frame.width > screenWidth) {
                candidateX = 32.0;
                candidateY += 2.0;
            }
        }
        frame.x = candidateX;
        frame.y = candidateY;
        frame.positioned = true;
    }

    void openSettings() {
        this.closePopups();
        this.settings.x = this.main.x;
        this.settings.y = this.main.y;
        this.settings.visible = true;
        this.main.visible = false;
        this.bringToFront(this.settings);
    }

    void closeSettings() {
        this.closePopups();
        this.main.x = this.settings.x;
        this.main.y = this.settings.y;
        this.main.visible = true;
        this.settings.visible = false;
        this.bringToFront(this.main);
        this.saveState();
    }

    void closeListFrame(ListFrame frame) {
        if (frame.anchor != null) {
            this.closeListEditor();
        } else {
            frame.visible = false;
            this.saveState();
        }
    }

    boolean isOpenListEditor(ListRow row) {
        return this.openList == row;
    }

    void toggleListEditor(ListRow row) {
        if (this.openList == row) {
            this.closeListEditor();
            return;
        }
        this.closeListEditor();
        ListFrame frame = new ListFrame(this, row.source, row, "sidecar_" + row.hashCode());
        row.editor = frame;
        frame.visible = true;
        frame.follow();
        this.openList = row;
        this.openListFrame = frame;
        this.frames.add(frame);
    }

    private void closeListEditor() {
        if (this.openListFrame != null) {
            this.frames.remove(this.openListFrame);
            if (this.focused != null && this.openListFrame.components.contains(this.focused)) {
                this.focus(null);
            }
        }
        if (this.openList != null) {
            this.openList.editor = null;
        }
        this.openList = null;
        this.openListFrame = null;
    }

    void setOpenDropdown(DropdownRow row) {
        this.openDropdown = row;
    }

    /** Closes anything floating over the frames: open dropdowns and list editors. */
    void closePopups() {
        this.openDropdown = null;
        this.closeListEditor();
    }

    void focus(TextEditable editable) {
        if (this.focused == editable) {
            return;
        }
        TextEditable previous = this.focused;
        this.focused = editable;
        if (previous != null) {
            previous.focusLost();
        }
    }

    boolean isHiddenInGui(Module module) {
        return this.hiddenModules.contains(module.getName().toLowerCase(Locale.ROOT));
    }

    /** Hiding a module from the GUI also turns it off, as in the original. */
    void setHiddenInGui(Module module, boolean hidden) {
        String key = module.getName().toLowerCase(Locale.ROOT);
        if (hidden) {
            this.hiddenModules.add(key);
            if (module.isEnabled()) {
                module.setEnabled(false);
            }
        } else {
            this.hiddenModules.remove(key);
        }
        this.saveState();
    }

    // ------------------------------------------------------------------ input

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        double mx = VapeRender.mouseX();
        double my = VapeRender.mouseY();
        if (this.binding != null) {
            ModuleRow row = this.binding;
            this.binding = null;
            if (button == 0) {
                // The left button cancels and clears, like the original's bind capture.
                row.module.setKey(KeyBindUtil.NONE);
            } else if (KeyBindUtil.isBindableMouseButton(button)) {
                row.module.setKey(KeyBindUtil.mouseButtonToKey(button));
            }
            return;
        }
        if (this.openDropdown != null) {
            this.openDropdown.popupClicked(mx, my);
            return;
        }
        this.focus(null);
        VFrame top = this.topFrameAt(mx, my);
        if (top != null) {
            if (top != this.search) {
                this.bringToFront(top);
            }
            top.mouseClicked(mx, my, button);
            return;
        }
        ClickGUIModule module = VapeTheme.module();
        if (module != null && module.isCloseMouseButton(button)) {
            this.close();
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        double mx = VapeRender.mouseX();
        double my = VapeRender.mouseY();
        for (VFrame frame : new ArrayList<VFrame>(this.frames)) {
            if (frame.visible) {
                frame.mouseReleased(mx, my, state);
            }
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }
        double mx = VapeRender.mouseX();
        double my = VapeRender.mouseY();
        if (this.openDropdown != null && this.openDropdown.popupContains(mx, my)) {
            this.openDropdown.popupScrolled(wheel);
            return;
        }
        VFrame top = this.topFrameAt(mx, my);
        if (top != null) {
            top.layout();
            top.scrollWheel(wheel);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (this.binding != null) {
            ModuleRow row = this.binding;
            this.binding = null;
            row.module.setKey(KeyBindUtil.isUnbindKey(keyCode) ? KeyBindUtil.NONE : keyCode);
            return;
        }
        if (this.focused != null) {
            this.focused.editKey(typedChar, keyCode);
            return;
        }
        if (keyCode == Keyboard.KEY_F && isCtrlKeyDown()) {
            this.search.focusInput();
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE && this.openDropdown != null) {
            this.openDropdown = null;
            return;
        }
        ClickGUIModule module = VapeTheme.module();
        if (module != null ? module.isCloseKey(keyCode) : keyCode == Keyboard.KEY_ESCAPE) {
            this.close();
        }
    }

    private void close() {
        ClickGUIModule module = VapeTheme.module();
        if (module != null && module.isEnabled()) {
            module.setEnabled(false);
            return;
        }
        this.mc.displayGuiScreen(null);
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        Keyboard.enableRepeatEvents(false);
        this.focus(null);
        this.binding = null;
        this.closePopups();
        this.stopBackgroundBlur();
        this.saveState();
        ClickGUIModule module = VapeTheme.module();
        if (module != null && !module.isSwitchingGuiStyle()) {
            module.setEnabled(false);
        }
    }

    // ------------------------------------------------------------------ state

    private boolean savingEnabled() {
        ClickGUIModule module = VapeTheme.module();
        return module == null || module.saveGuiState.getValue();
    }

    void saveState() {
        if (!this.built || !this.savingEnabled()) {
            return;
        }
        JsonObject root = new JsonObject();
        JsonObject frames = new JsonObject();
        for (VFrame frame : this.frames) {
            if (frame instanceof ListFrame && ((ListFrame) frame).anchor != null || frame == this.search) {
                continue;
            }
            JsonObject state = new JsonObject();
            state.addProperty("x", frame.x);
            state.addProperty("y", frame.y);
            state.addProperty("visible", frame == this.main || frame != this.settings && frame.visible);
            state.addProperty("collapsed", frame.collapsed);
            state.addProperty("positioned", frame.positioned);
            frames.add(frame.name(), state);
        }
        root.add("frames", frames);
        JsonArray hidden = new JsonArray();
        for (String name : this.hiddenModules) {
            hidden.add(new com.google.gson.JsonPrimitive(name));
        }
        root.add("hidden", hidden);
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try {
            STATE_FILE.getParentFile().mkdirs();
            FileWriter writer = new FileWriter(STATE_FILE);
            try {
                gson.toJson(root, writer);
            } finally {
                writer.close();
            }
        } catch (IOException exception) {
            exception.printStackTrace();
        }
    }

    private void loadState() {
        if (!this.savingEnabled() || !STATE_FILE.exists()) {
            return;
        }
        try {
            FileReader reader = new FileReader(STATE_FILE);
            JsonElement parsed;
            try {
                parsed = new JsonParser().parse(reader);
            } finally {
                reader.close();
            }
            if (parsed == null || !parsed.isJsonObject()) {
                return;
            }
            JsonObject root = parsed.getAsJsonObject();
            if (root.has("frames") && root.get("frames").isJsonObject()) {
                this.savedFrames = root.getAsJsonObject("frames");
            }
            if (root.has("hidden") && root.get("hidden").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("hidden")) {
                    if (element.isJsonPrimitive()) {
                        this.hiddenModules.add(element.getAsString().toLowerCase(Locale.ROOT));
                    }
                }
            }
        } catch (Exception exception) {
            // A damaged layout file must never keep the GUI from opening.
            exception.printStackTrace();
        }
    }

    private void applySavedFrames() {
        if (this.savedFrames == null) {
            return;
        }
        for (VFrame frame : this.frames) {
            JsonElement element = this.savedFrames.get(frame.name());
            if (element == null || !element.isJsonObject() || frame == this.search) {
                continue;
            }
            JsonObject state = element.getAsJsonObject();
            try {
                if (state.has("x")) {
                    frame.x = Math.floor(state.get("x").getAsDouble());
                }
                if (state.has("y")) {
                    frame.y = Math.floor(state.get("y").getAsDouble());
                }
                if (state.has("collapsed")) {
                    frame.collapsed = state.get("collapsed").getAsBoolean();
                }
                if (state.has("positioned")) {
                    frame.positioned = frame.positioned || state.get("positioned").getAsBoolean();
                }
                if (state.has("visible") && frame != this.main && frame != this.settings) {
                    frame.visible = state.get("visible").getAsBoolean();
                }
            } catch (Exception ignored) {
            }
        }
        this.savedFrames = null;
    }

    // ------------------------------------------------------------------ list sources

    /** Myau+'s friends list. */
    private static final class FriendsSource implements ListSource {
        @Override
        public String title() {
            return "Friends";
        }

        @Override
        public String icon() {
            return "newfriends";
        }

        @Override
        public boolean blocked() {
            return false;
        }

        @Override
        public List<String> entries() {
            return Myau.friendManager == null ? Collections.<String>emptyList()
                    : new ArrayList<String>(Myau.friendManager.players);
        }

        @Override
        public void add(String entry) {
            if (Myau.friendManager != null && !entry.contains(" ")) {
                Myau.friendManager.add(entry);
            }
        }

        @Override
        public void remove(String entry) {
            if (Myau.friendManager != null) {
                Myau.friendManager.remove(entry);
            }
        }

        @Override
        public boolean removable() {
            return true;
        }

        @Override
        public boolean selected(String entry) {
            return true;
        }

        @Override
        public void clicked(String entry) {
        }
    }

    /**
     * Configs in config/Myau: the one in use is marked, clicking another loads it and typing a
     * new name saves the current settings under it. Files are never deleted from here.
     */
    private static final class ProfilesSource implements ListSource {
        private static final Set<String> NOT_CONFIGS = new HashSet<String>(Arrays.asList("menu"));
        private List<String> cached = new ArrayList<String>();
        private long cachedAt;

        @Override
        public String title() {
            return "Profiles";
        }

        @Override
        public String icon() {
            return "newprofiles";
        }

        @Override
        public boolean blocked() {
            return false;
        }

        @Override
        public List<String> entries() {
            long now = System.currentTimeMillis();
            if (now - this.cachedAt < 1000L) {
                return this.cached;
            }
            this.cachedAt = now;
            List<String> names = new ArrayList<String>();
            File[] files = Config.DIRECTORY.listFiles();
            if (files != null) {
                Arrays.sort(files, (first, second) -> Long.compare(second.lastModified(), first.lastModified()));
                for (File file : files) {
                    String name = file.getName();
                    if (!file.isFile() || !name.toLowerCase(Locale.ROOT).endsWith(".json")) {
                        continue;
                    }
                    String base = name.substring(0, name.length() - 5);
                    if (!NOT_CONFIGS.contains(base.toLowerCase(Locale.ROOT))) {
                        names.add(base);
                    }
                }
            }
            this.cached = names;
            return names;
        }

        @Override
        public void add(String entry) {
            String name = entry.trim();
            if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) {
                return;
            }
            new Config(name, true).save();
            this.cachedAt = 0L;
        }

        @Override
        public void remove(String entry) {
        }

        @Override
        public boolean removable() {
            return false;
        }

        @Override
        public boolean selected(String entry) {
            return Config.normalizeName(entry).equals(Config.lastConfig);
        }

        @Override
        public void clicked(String entry) {
            new Config(entry, false).load();
            this.cachedAt = 0L;
        }
    }
}
