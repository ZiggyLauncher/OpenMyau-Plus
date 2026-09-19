package myau.ui;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import myau.Myau;
import myau.font.FontProcess;
import myau.module.Module;
import myau.module.modules.ClickGUIModule;
import myau.ui.components.BindComponent;
import myau.ui.components.CategoryComponent;
import myau.ui.components.ModuleComponent;
import myau.util.KeyBindUtil;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import myau.font.CFontRenderer;

import java.awt.*;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.List;

public class ClickGui extends GuiScreen {
    CFontRenderer fontRenderer = FontProcess.getFont("sans");
    private static ClickGui instance;
    private final File configFile = new File("./config/OpenMyau-plus/", "clickgui.txt");
    private final ArrayList<CategoryComponent> categoryList;

    public ClickGui() {
        instance = this;


        this.categoryList = new ArrayList<>();
        int topOffset = 5;
        for (ModuleCategories.Category category : ModuleCategories.Category.values()) {
            CategoryComponent component = new CategoryComponent(category.getLabel(), ModuleCategories.modules(category));
            component.setY(topOffset);
            categoryList.add(component);
            topOffset += 20;
        }

        loadPositions();
    }

    public static ClickGui getInstance() {
        if (instance == null) {
            instance = new ClickGui();
        }
        return instance;
    }

    public static void resetInstance() {
        instance = null;
    }

    public void initGui() {
        super.initGui();
    }

    public void drawScreen(int x, int y, float p) {
        drawRect(0, 0, this.width, this.height, new Color(0, 0, 0, 100).getRGB());

        fontRenderer.drawStringWithShadow("Myau+ " + Myau.version, 4, this.height - 3 - fontRenderer.FONT_HEIGHT * 2, new Color(60, 162, 253).getRGB());
        fontRenderer.drawStringWithShadow("dev, Ziggy", 4, this.height - 3 - fontRenderer.FONT_HEIGHT, new Color(60, 162, 253).getRGB());

        for (CategoryComponent category : categoryList) {
            category.render(this.mc.fontRendererObj);
            category.handleDrag(x, y);

            for (Component module : category.getModules()) {
                module.update(x, y);
            }
        }

        int wheel = Mouse.getDWheel();
        if (wheel != 0) {
            int scrollDir = wheel > 0 ? 1 : -1;
            for (CategoryComponent category : categoryList) {
                category.onScroll(x, y, scrollDir);
            }
        }
    }

    public void mouseClicked(int x, int y, int mouseButton) {
        BindComponent binding = bindingComponent();
        if (binding != null) {
            // Any non-left button becomes the bind; a left click elsewhere falls through and
            // lets the component cancel itself.
            if (KeyBindUtil.isBindableMouseButton(mouseButton)) {
                binding.bindMouse(mouseButton);
                return;
            }
        } else if (clickGuiModule() != null && clickGuiModule().isCloseMouseButton(mouseButton)) {
            clickGuiModule().setEnabled(false);
            return;
        }
        for (CategoryComponent category : categoryList) {
            if (category.insideArea(x, y) && !category.isHovered(x, y) && !category.mousePressed(x, y) && mouseButton == 0) {
                category.mousePressed(true);
                category.xx = x - category.getX();
                category.yy = y - category.getY();
            }

            if (category.mousePressed(x, y) && mouseButton == 0) {
                category.setOpened(!category.isOpened());
            }

            if (category.isHovered(x, y) && mouseButton == 0) {
                category.setPin(!category.isPin());
            }

            // Only the clipped module list can be clicked; scrolled-out modules must never react.
            if (category.isInsideContent(x, y)) {
                for (Component c : category.getModules()) {
                    c.mouseDown(x, y, mouseButton);
                }
            }
        }
    }

    public void mouseReleased(int x, int y, int mouseButton) {
        Iterator<CategoryComponent> iterator = categoryList.iterator();

        CategoryComponent categoryComponent;
        while (iterator.hasNext()) {
            categoryComponent = iterator.next();
            if (mouseButton == 0) {
                categoryComponent.mousePressed(false);
            }
        }

        iterator = categoryList.iterator();

        while (true) {
            do {
                do {
                    if (!iterator.hasNext()) {
                        return;
                    }

                    categoryComponent = iterator.next();
                } while (!categoryComponent.isOpened());
            } while (categoryComponent.getModules().isEmpty());

            for (Component component : categoryComponent.getModules()) {
                component.mouseReleased(x, y, mouseButton);
            }
        }
    }

    public void keyTyped(char typedChar, int key) {
        BindComponent binding = bindingComponent();
        if (binding != null) {
            binding.keyTyped(typedChar, key);
            return;
        }
        ClickGUIModule clickGUIModule = clickGuiModule();
        if (key == Keyboard.KEY_ESCAPE || (clickGUIModule != null && clickGUIModule.isCloseKey(key))) {
            this.mc.displayGuiScreen(null);
        } else {
            Iterator<CategoryComponent> btnCat = categoryList.iterator();

            while (true) {
                CategoryComponent cat;
                do {
                    do {
                        if (!btnCat.hasNext()) {
                            return;
                        }

                        cat = btnCat.next();
                    } while (!cat.isOpened());
                } while (cat.getModules().isEmpty());

                for (Component component : cat.getModules()) {
                    component.keyTyped(typedChar, key);
                }
            }
        }
    }

    public void onGuiClosed() {
        savePositions();
        Module clickGUIModule = Myau.moduleManager.getModule("ClickGUI");
        if (clickGUIModule instanceof ClickGUIModule
                && ((ClickGUIModule) clickGUIModule).isSwitchingGuiStyle()) {
            return;
        }
        if (clickGUIModule != null) {
            clickGUIModule.setEnabled(false);
        }
    }

    public boolean doesGuiPauseGame() {
        return false;
    }

    private void savePositions() {
        File parent = configFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        JsonObject json = new JsonObject();
        for (CategoryComponent cat : categoryList) {
            JsonObject pos = new JsonObject();
            pos.addProperty("x", cat.getX());
            pos.addProperty("y", cat.getY());
            pos.addProperty("open", cat.isOpened());
            json.add(cat.getName(), pos);
        }
        try (FileWriter writer = new FileWriter(configFile)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(json, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadPositions() {
        if (!configFile.exists()) return;
        try (FileReader reader = new FileReader(configFile)) {
            JsonElement parsed = new JsonParser().parse(reader);
            if (parsed == null || !parsed.isJsonObject()) return;
            JsonObject json = parsed.getAsJsonObject();
            for (CategoryComponent cat : categoryList) {
                if (json.has(cat.getName()) && json.get(cat.getName()).isJsonObject()) {
                    JsonObject pos = json.getAsJsonObject(cat.getName());
                    if (pos.has("x")) cat.setX(pos.get("x").getAsInt());
                    if (pos.has("y")) cat.setY(pos.get("y").getAsInt());
                    if (pos.has("open")) cat.setOpened(pos.get("open").getAsBoolean());
                }
            }
        } catch (Exception e) {
            // A truncated/corrupt layout file must never keep the GUI from opening.
            e.printStackTrace();
        }
    }

    private ClickGUIModule clickGuiModule() {
        Module module = Myau.moduleManager.getModule("ClickGUI");
        return module instanceof ClickGUIModule ? (ClickGUIModule) module : null;
    }

    private BindComponent bindingComponent() {
        for (CategoryComponent category : categoryList) {
            for (Component component : category.getModules()) {
                if (component instanceof ModuleComponent) {
                    for (Component setting : ((ModuleComponent) component).getSettings()) {
                        if (setting instanceof BindComponent && ((BindComponent) setting).isBinding()) {
                            return (BindComponent) setting;
                        }
                    }
                }
            }
        }
        return null;
    }
}
