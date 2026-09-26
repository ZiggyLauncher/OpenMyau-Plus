package myau.config;

import com.google.gson.*;
import myau.Myau;
import myau.mixin.IAccessorMinecraft;
import myau.module.Module;
import myau.module.modules.ClickGUIModule;
import myau.util.ChatUtil;
import myau.property.Property;
import net.minecraft.client.Minecraft;

import java.io.*;
import java.util.ArrayList;

public class Config {
    public static final String DEFAULT_NAME = "default";
    public static final File DIRECTORY = new File("./config/Myau/");
    // Remembers which config the user is "in" between sessions, so a bare `.config save`
    // keeps writing to it instead of silently falling back to default.json after a restart.
    private static final File LAST_CONFIG_FILE = new File(DIRECTORY, "last-config.txt");
    public static Minecraft mc = Minecraft.getMinecraft();
    public static Gson gson = new GsonBuilder().setPrettyPrinting().create();
    public String name;
    public File file;

    /**
     * Name of the config the user is currently "in": the last one explicitly loaded or saved.
     * The startup/shutdown autosave of default.json does not count.
     */
    public static String lastConfig = DEFAULT_NAME;

    public static String normalizeName(String name) {
        if (name == null) {
            return DEFAULT_NAME;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.equals("!") || trimmed.equalsIgnoreCase(DEFAULT_NAME)) {
            return DEFAULT_NAME;
        }
        return trimmed.toLowerCase(java.util.Locale.ROOT).endsWith(".json")
                ? trimmed.substring(0, trimmed.length() - 5)
                : trimmed;
    }

    public static void setCurrent(String name) {
        lastConfig = normalizeName(name);
        try {
            DIRECTORY.mkdirs();
            java.nio.file.Files.write(LAST_CONFIG_FILE.toPath(), lastConfig.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            ((IAccessorMinecraft) mc).getLogger().warn("Failed to remember current config: " + e.getMessage());
        }
    }

    public static void restoreCurrent() {
        try {
            if (LAST_CONFIG_FILE.isFile()) {
                String remembered = new String(java.nio.file.Files.readAllBytes(LAST_CONFIG_FILE.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                String name = normalizeName(remembered);
                // Only trust it if that config still exists on disk.
                if (name.equals(DEFAULT_NAME) || new File(DIRECTORY, name + ".json").isFile()) {
                    lastConfig = name;
                    return;
                }
            }
        } catch (Exception e) {
            ((IAccessorMinecraft) mc).getLogger().warn("Failed to read remembered config: " + e.getMessage());
        }
        lastConfig = DEFAULT_NAME;
    }

    /**
     * Opens the config the user is currently in (see {@link #lastConfig}).
     */
    public static Config current() {
        return new Config(lastConfig, false);
    }

    public Config(String name, boolean newConfig) {
        this.name = normalizeName(name);
        this.file = new File(DIRECTORY, String.format("%s.json", this.name));
        try {
            file.getParentFile().mkdirs();
            if (newConfig) {
                ((IAccessorMinecraft) mc).getLogger().info(String.format("Created: %s", this.file.getName()));
            }
        } catch (Exception e) {
            ((IAccessorMinecraft) mc).getLogger().error(e.getMessage());
        }
    }

    public void load() {
        load(true);
    }

    /**
     * @param markCurrent false for the startup load of default.json, which restores the last
     *                    session's state but is not the config the user chose to be in.
     */
    public void load(boolean markCurrent) {
        try {

            if (!file.exists()) {
                ChatUtil.sendFormatted(String.format("%sConfig file not found (&c&o%s&r). Creating default config...&r", Myau.clientName, file.getName()));
                save(markCurrent);
                return;
            }

            JsonElement parsed;
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                parsed = new JsonParser().parse(reader);
            }
            if (parsed == null || !parsed.isJsonObject()) {
                ChatUtil.sendFormatted(String.format("%sInvalid config format (&c&o%s&r)&r", Myau.clientName, file.getName()));
                return;
            }

            JsonObject jsonObject = parsed.getAsJsonObject();
            migrateBlockIn(jsonObject);
            for (Module module : Myau.moduleManager.allModules()) {
                JsonElement moduleObj = jsonObject.get(module.getName());
                if (moduleObj != null && moduleObj.isJsonObject()) {
                    JsonObject object = moduleObj.getAsJsonObject();

                    ArrayList<Property<?>> list = Myau.propertyManager.properties.get(module);
                    if (list != null) {
                        for (Property<?> property : list) {
                            if (object.has(property.getName())) {
                                try {
                                    property.read(object);
                                } catch (Exception e) {
                                    ((IAccessorMinecraft) mc).getLogger().warn(String.format("Failed to load property %s for module %s", property.getName(), module.getName()));
                                }
                            }
                        }
                    }

                    if (object.has("toggled") && !(module instanceof ClickGUIModule)) {
                        JsonElement toggled = object.get("toggled");
                        if (toggled != null && toggled.isJsonPrimitive()) {
                            module.setEnabled(toggled.getAsBoolean());
                        }
                    }

                    if (object.has("key")) {
                        JsonElement key = object.get("key");
                        if (key != null && key.isJsonPrimitive()) {
                            module.setKey(key.getAsInt());
                        }
                    }

                    if (object.has("hidden")) {
                        JsonElement hidden = object.get("hidden");
                        if (hidden != null && hidden.isJsonPrimitive()) {
                            module.setHidden(hidden.getAsBoolean());
                        }
                    }
                }
            }
            if (markCurrent) {
                setCurrent(this.name);
            }
            ChatUtil.sendFormatted(String.format("%sConfig has been loaded (&a&o%s&r)&r", Myau.clientName, file.getName()));
        } catch (FileNotFoundException e) {
            ChatUtil.sendFormatted(String.format("%sConfig file not found (&c&o%s&r)&r", Myau.clientName, file.getName()));
        } catch (JsonSyntaxException e) {
            ChatUtil.sendFormatted(String.format("%sConfig has invalid JSON syntax (&c&o%s&r)&r", Myau.clientName, file.getName()));
            ((IAccessorMinecraft) mc).getLogger().error("JSON Syntax Error: " + e.getMessage());
        } catch (Exception e) {
            ((IAccessorMinecraft) mc).getLogger().error("Error loading config: " + e.getMessage());
            ChatUtil.sendFormatted(String.format("%sConfig couldn't be loaded (&c&o%s&r)&r", Myau.clientName, file.getName()));
        }
    }

    /**
     * Up to 2.6 the block-in module was called Clutch; that name now belongs to the fall saver.
     * A config written before the rename keeps block-in's settings, bind and on/off state under
     * "Clutch" (recognisable by its aim-speed), so they are moved to BlockIn rather than being
     * applied to the new module.
     */
    private static void migrateBlockIn(JsonObject root) {
        JsonElement clutch = root.get("Clutch");
        if (clutch == null || !clutch.isJsonObject() || root.has("BlockIn")) {
            return;
        }
        if (clutch.getAsJsonObject().has("aim-speed")) {
            root.remove("Clutch");
            root.add("BlockIn", clutch);
        }
    }

    public void save() {
        save(true, false);
    }

    public void save(boolean markCurrent) {
        save(markCurrent, false);
    }

    /**
     * Writes the live module state to default.json without touching the "current" config or the
     * chat. Called when quitting, on every world change and periodically, so nothing depends on a
     * JVM shutdown hook that runs after the world (and module state) is already gone - or never
     * runs at all when the process is killed.
     */
    public static void autoSave() {
        if (Myau.moduleManager == null || Myau.propertyManager == null) {
            return;
        }
        new Config(DEFAULT_NAME, false).save(false, true);
    }

    /**
     * @param markCurrent false for background autosaves that must not change which config a bare
     *                    {@code .config save} writes to.
     * @param quiet       no chat feedback (autosaves)
     */
    public void save(boolean markCurrent, boolean quiet) {
        try {
            if (!file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }

            JsonObject object = new JsonObject();
            for (Module module : Myau.moduleManager.allModules()) {
                JsonObject moduleObject = new JsonObject();
                // "Enabled" for the ClickGUI module just means the screen is open right now.
                moduleObject.addProperty("toggled", !(module instanceof ClickGUIModule) && module.isEnabled());
                moduleObject.addProperty("key", module.getKey());
                moduleObject.addProperty("hidden", module.isHidden());

                ArrayList<Property<?>> list = Myau.propertyManager.properties.get(module);
                if (list != null) {
                    for (Property<?> property : list) {
                        try {
                            property.write(moduleObject);
                        } catch (Exception e) {
                            ((IAccessorMinecraft) mc).getLogger().warn(String.format("Failed to save property %s for module %s", property.getName(), module.getName()));
                        }
                    }
                }
                object.add(module.getName(), moduleObject);
            }

            // Write beside the target and swap it in, so a kill mid-write can't leave a truncated
            // config that fails to parse (and loses everything) on the next start.
            File temp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (PrintWriter printWriter = new PrintWriter(new FileWriter(temp))) {
                printWriter.println(gson.toJson(object));
            }
            try {
                java.nio.file.Files.move(temp.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            if (markCurrent) {
                setCurrent(this.name);
            }
            if (!quiet) {
                ChatUtil.sendFormatted(String.format("%sConfig has been saved (&a&o%s&r)&r", Myau.clientName, file.getName()));
            }
        } catch (Exception e) {
            ((IAccessorMinecraft) mc).getLogger().error("Error saving config: " + e.getMessage());
            if (!quiet) {
                ChatUtil.sendFormatted(String.format("%sConfig couldn't be saved (&c&o%s&r)&r", Myau.clientName, file.getName()));
            }
        }
    }
}
