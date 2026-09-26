package myau.ui;

import myau.Myau;
import myau.module.Module;
import myau.module.modules.ScriptModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Single source of truth for which ClickGui category a module belongs to. Every skin used to
 * carry its own hard-coded list, so a module registered in {@link Myau} but missing from one of
 * them either vanished from that skin or (Raven B3) crashed it. Anything not listed here lands
 * in Misc, and scripts always get their own category.
 */
public final class ModuleCategories {
    public enum Category {
        COMBAT("Combat"),
        MOVEMENT("Movement"),
        RENDER("Render"),
        PLAYER("Player"),
        MISC("Misc"),
        SCRIPTS("Scripts");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    private static final Set<String> COMBAT = set(
            "AimAssist", "AutoClicker", "KillAura", "Wtap", "Velocity", "Reach", "TargetStrafe", "NoHitDelay",
            "AntiFireball", "LagRange", "MoveFix", "ServerLag", "KnockbackDelay", "HitBox", "MoreKB", "Refill",
            "HitSelect", "BackTrack", "Hitflick", "VoidFlick", "TimerRange", "ClickAssits", "Criticals", "BlockHit",
            "SprintReset", "Displace", "TickBase", "Piercing", "Stasis");
    private static final Set<String> MOVEMENT = set(
            "AntiAFK", "Fly", "FastBow", "Speed", "LongJump", "Sprint", "SafeWalk", "Jesus", "Blink", "NoFall",
            "NoSlow", "KeepSprint", "Eagle", "NoJumpDelay", "AntiVoid", "Timer", "VelocityPreserver");
    private static final Set<String> RENDER = set(
            "ESP", "Chams", "FullBright", "Tracers", "NameTags", "Xray", "TargetESP", "TargetHUD", "Indicators",
            "BedESP", "ItemESP", "BreakProgress", "ViewClip", "NoHurtCam", "HUD", "Hotbar", "HotbarText", "ArmorHUD", "PotionHUD", "Keystrokes", "CylinderESP", "HudEditor", "MenuStyle", "CustomMenu", "Notifications",
            "ClickGUI", "ChestESP", "Trajectories", "Radar", "RenderFixes", "FPScounter", "WaterMark", "WaterMark2",
            "HitParticleEffects", "DynamicIsland", "ESP2D", "TeamHealthDisplay", "Statistics", "Animations",
            "BlockOverlay", "Ambience", "Capes", "FreeLook", "ItemPhysics");
    private static final Set<String> PLAYER = set(
            "AutoHeal", "FakeLag", "AutoTool", "ChestStealer", "AutoBedDef", "InvManager", "InvWalk", "Scaffold",
            "AutoBlockIn", "BlockIn", "Clutch", "AutoSwap", "SpeedMine", "FastPlace", "GhostHand", "MCF", "AntiDebuff", "FlagDetector",
            "AutoGapple", "ChestAura", "AutoHeadHitter", "ThrowAura");

    private static final Comparator<Module> BY_NAME = Comparator.comparing(m -> m.getName().toLowerCase(Locale.ROOT));

    private ModuleCategories() {
    }

    public static Category of(Module module) {
        if (module instanceof ScriptModule) {
            return Category.SCRIPTS;
        }
        String name = norm(module.getName());
        if (COMBAT.contains(name)) return Category.COMBAT;
        if (MOVEMENT.contains(name)) return Category.MOVEMENT;
        if (RENDER.contains(name)) return Category.RENDER;
        if (PLAYER.contains(name)) return Category.PLAYER;
        return Category.MISC;
    }

    /**
     * All currently registered modules (built-in and script) in the given category, sorted by name.
     */
    public static List<Module> modules(Category category) {
        if (Myau.moduleManager == null) {
            return Collections.emptyList();
        }
        List<Module> list = new ArrayList<>();
        for (Module module : Myau.moduleManager.allModules()) {
            if (module != null && of(module) == category) {
                list.add(module);
            }
        }
        list.sort(BY_NAME);
        return list;
    }

    private static Set<String> set(String... names) {
        Set<String> s = new HashSet<>();
        for (String n : names) s.add(norm(n));
        return s;
    }

    private static String norm(String s) {
        return s == null ? "" : s.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }
}
