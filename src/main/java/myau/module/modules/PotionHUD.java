package myau.module.modules;

import myau.event.EventTarget;
import myau.events.Render2DEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ColorProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.ui.DraggableHud;
import myau.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Active potion effects as a compact list: name, tier and remaining time, with a bar that drains
 * as the effect runs out. A few rects and strings per active effect - there are at most a handful,
 * so the cost is flat and tiny.
 */
public class PotionHUD extends Module implements DraggableHud {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final String[] TIERS = {"", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
    /** Effects at or below this many ticks blink, the way the vanilla inventory overlay does. */
    private static final int LOW_TICKS = 200;

    private static final int SORT_DURATION = 0;
    private static final int SORT_NAME = 1;
    private static final int ANCHOR_TOP_LEFT = 0;
    private static final int ANCHOR_TOP_RIGHT = 1;
    private static final int ANCHOR_BOTTOM_LEFT = 2;
    private static final int ANCHOR_BOTTOM_RIGHT = 3;

    public final ModeProperty anchor = new ModeProperty("Anchor", ANCHOR_TOP_LEFT,
            new String[]{"Top-Left", "Top-Right", "Bottom-Left", "Bottom-Right"});
    public final ModeProperty colorMode = new ModeProperty("Color-Mode", HudStyle.MODE_HUD, HudStyle.COLOR_MODES);
    public final ColorProperty color = new ColorProperty("Color", 0x4FACFE, () -> this.colorMode.getValue() == HudStyle.MODE_CUSTOM);
    public final BooleanProperty potionColors = new BooleanProperty("Potion-Colors", false);
    public final IntProperty alpha = new IntProperty("Alpha", 255, 40, 255);
    public final FloatProperty scale = new FloatProperty("Scale", 1.0F, 0.5F, 2.0F);
    public final IntProperty offsetX = new IntProperty("Offset-X", 4, -2000, 2000);
    public final IntProperty offsetY = new IntProperty("Offset-Y", 4, -2000, 2000);
    public final IntProperty width = new IntProperty("Width", 108, 70, 220);
    public final BooleanProperty background = new BooleanProperty("Background", true);
    public final IntProperty backgroundAlpha = new IntProperty("Background-Alpha", 90, 0, 255, background::getValue);
    public final FloatProperty radius = new FloatProperty("Radius", 4.0F, 0.0F, 10.0F);
    public final BooleanProperty progressBar = new BooleanProperty("Progress-Bar", true);
    public final BooleanProperty duration = new BooleanProperty("Duration", true);
    public final BooleanProperty hideAmbient = new BooleanProperty("Hide-Ambient", false);
    public final ModeProperty sort = new ModeProperty("Sort", SORT_DURATION, new String[]{"Duration", "Name"});

    /** Longest duration seen per effect id, so the bar has something to drain from. */
    private final java.util.HashMap<Integer, Integer> peakDuration = new java.util.HashMap<>();
    private final List<PotionEffect> shown = new ArrayList<>();

    public PotionHUD() {
        super("PotionHUD", false, false, "Active potion effects with remaining time");
    }

    @Override
    public void onEnabled() {
        this.peakDuration.clear();
    }

    @Override
    public void onDisabled() {
        this.peakDuration.clear();
        this.shown.clear();
    }

    private void collect() {
        this.shown.clear();
        Collection<PotionEffect> effects = mc.thePlayer.getActivePotionEffects();
        if (effects == null || effects.isEmpty()) {
            this.peakDuration.clear();
            return;
        }
        for (PotionEffect effect : effects) {
            if (effect == null || effect.getDuration() <= 0) {
                continue;
            }
            if (this.hideAmbient.getValue() && effect.getIsAmbient()) {
                continue;
            }
            if (effect.getPotionID() < 0 || effect.getPotionID() >= Potion.potionTypes.length
                    || Potion.potionTypes[effect.getPotionID()] == null) {
                continue;
            }
            this.shown.add(effect);
        }
        if (this.sort.getValue() == SORT_NAME) {
            this.shown.sort(Comparator.comparing(PotionHUD::name));
        } else {
            this.shown.sort(Comparator.comparingInt(PotionEffect::getDuration).reversed());
        }
        // Forget effects that ran out so a re-applied one starts its bar full again.
        this.peakDuration.keySet().removeIf(id -> {
            for (PotionEffect effect : this.shown) {
                if (effect.getPotionID() == id) {
                    return false;
                }
            }
            return true;
        });
    }

    private static String name(PotionEffect effect) {
        return I18n.format(Potion.potionTypes[effect.getPotionID()].getName());
    }

    private static String label(PotionEffect effect) {
        int amplifier = effect.getAmplifier();
        String tier = amplifier > 0 && amplifier < TIERS.length ? " " + TIERS[amplifier]
                : amplifier > 0 ? " " + (amplifier + 1) : "";
        return name(effect) + tier;
    }

    private float lineHeight() {
        return HudStyle.fontHeight() + (this.progressBar.getValue() ? 6.0F : 4.0F);
    }

    /** Unscaled panel height for a given number of effects. */
    private float panelHeight(int effects) {
        return 4.0F * 2.0F + effects * this.lineHeight();
    }

    /**
     * Top-left corner in scaled screen pixels for the current anchor and offsets.
     * The offsets are always measured inward from the anchored corner.
     */
    private float[] position(ScaledResolution sr, float panelWidth, float panelHeight) {
        float scale = this.scale.getValue();
        switch (this.anchor.getValue()) {
            case ANCHOR_TOP_RIGHT:
                return new float[]{sr.getScaledWidth() - panelWidth * scale - this.offsetX.getValue(), this.offsetY.getValue()};
            case ANCHOR_BOTTOM_LEFT:
                return new float[]{this.offsetX.getValue(), sr.getScaledHeight() - panelHeight * scale - this.offsetY.getValue()};
            case ANCHOR_BOTTOM_RIGHT:
                return new float[]{
                        sr.getScaledWidth() - panelWidth * scale - this.offsetX.getValue(),
                        sr.getScaledHeight() - panelHeight * scale - this.offsetY.getValue()
                };
            case ANCHOR_TOP_LEFT:
            default:
                return new float[]{this.offsetX.getValue(), this.offsetY.getValue()};
        }
    }

    @Override
    public String getHudName() {
        return "Potion HUD";
    }

    @Override
    public float[] getHudBounds() {
        if (mc.thePlayer == null) {
            return null;
        }
        this.collect();
        // Keep a one-line placeholder when nothing is active, so it stays grabbable in the editor.
        int effects = Math.max(1, this.shown.size());
        float panelWidth = this.width.getValue();
        float panelHeight = this.panelHeight(effects);
        float scale = this.scale.getValue();
        float[] pos = this.position(new ScaledResolution(mc), panelWidth, panelHeight);
        return new float[]{pos[0], pos[1], panelWidth * scale, panelHeight * scale};
    }

    @Override
    public void setHudPosition(float x, float y) {
        float[] bounds = this.getHudBounds();
        if (bounds == null) {
            return;
        }
        ScaledResolution sr = new ScaledResolution(mc);
        switch (this.anchor.getValue()) {
            case ANCHOR_TOP_RIGHT:
                this.offsetX.setValue(Math.round(sr.getScaledWidth() - bounds[2] - x));
                this.offsetY.setValue(Math.round(y));
                break;
            case ANCHOR_BOTTOM_LEFT:
                this.offsetX.setValue(Math.round(x));
                this.offsetY.setValue(Math.round(sr.getScaledHeight() - bounds[3] - y));
                break;
            case ANCHOR_BOTTOM_RIGHT:
                this.offsetX.setValue(Math.round(sr.getScaledWidth() - bounds[2] - x));
                this.offsetY.setValue(Math.round(sr.getScaledHeight() - bounds[3] - y));
                break;
            case ANCHOR_TOP_LEFT:
            default:
                this.offsetX.setValue(Math.round(x));
                this.offsetY.setValue(Math.round(y));
                break;
        }
    }

    @Override
    public void resetHudPosition() {
        this.offsetX.setValue(4);
        this.offsetY.setValue(4);
    }

    @Override
    public boolean isHudEnabled() {
        return this.isEnabled();
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (mc.thePlayer == null || mc.gameSettings.showDebugInfo) {
            return;
        }
        this.collect();
        if (this.shown.isEmpty()) {
            return;
        }

        float scale = this.scale.getValue();
        float padding = 4.0F;
        float lineHeight = this.lineHeight();
        float panelWidth = this.width.getValue();
        float panelHeight = this.panelHeight(this.shown.size());

        ScaledResolution sr = new ScaledResolution(mc);
        float[] pos = this.position(sr, panelWidth, panelHeight);
        float x = pos[0];
        float y = pos[1];

        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0.0F);
        GlStateManager.scale(scale, scale, 1.0F);

        if (this.background.getValue()) {
            HudStyle.panel(0.0F, 0.0F, panelWidth, panelHeight, this.radius.getValue(), this.backgroundAlpha.getValue());
        }

        long now = System.currentTimeMillis();
        for (int i = 0; i < this.shown.size(); i++) {
            PotionEffect effect = this.shown.get(i);
            float lineY = padding + i * lineHeight;
            int id = effect.getPotionID();
            int ticks = effect.getDuration();

            int peak = Math.max(this.peakDuration.getOrDefault(id, ticks), ticks);
            this.peakDuration.put(id, peak);

            int argb = this.potionColors.getValue()
                    ? (Math.max(0, Math.min(255, this.alpha.getValue())) << 24) | (Potion.potionTypes[id].getLiquidColor() & 0xFFFFFF)
                    : HudStyle.color(this.colorMode.getValue(), this.color.getValue(), this.alpha.getValue(), i * 120L);

            // Blink the last ten seconds, like the vanilla effect overlay.
            if (ticks <= LOW_TICKS && (now / 400L) % 2L == 0L) {
                argb = (argb & 0x00FFFFFF) | (Math.max(0, (argb >>> 24) / 3) << 24);
            }

            HudStyle.drawString(label(effect), padding, lineY, argb, true);
            if (this.duration.getValue()) {
                String time = Potion.getDurationString(effect);
                HudStyle.drawString(time, panelWidth - padding - HudStyle.stringWidth(time), lineY, 0xFFBBBBBB, true);
            }
            if (this.progressBar.getValue()) {
                float barY = lineY + HudStyle.fontHeight() + 1.0F;
                float barWidth = panelWidth - padding * 2.0F;
                float progress = peak > 0 ? Math.max(0.0F, Math.min(1.0F, (float) ticks / peak)) : 1.0F;
                HudStyle.rect(padding, barY, barWidth, 1.5F, 0.75F, 0x55000000);
                HudStyle.rect(padding, barY, Math.max(0.75F, barWidth * progress), 1.5F, 0.75F, argb);
            }
        }

        GlStateManager.popMatrix();
        RenderUtil.resetColor();
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.anchor.getModeString()};
    }
}
