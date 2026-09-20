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
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;

/**
 * Armour pieces (and optionally the held item) with durability bars, in the same rounded
 * translucent style as the custom hotbar. Six item draws and a few rects per frame - no blur,
 * no framebuffers, nothing that scales with the world.
 */
public class ArmorHUD extends Module implements DraggableHud {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int SLOT = 20;
    private static final float PADDING = 3.0F;
    /** Room reserved above a slot for its durability number, so the text stays inside the panel. */
    private static final float TEXT_BAND = 10.0F;

    private static final int LAYOUT_HORIZONTAL = 0;

    public final ModeProperty layout = new ModeProperty("Layout", LAYOUT_HORIZONTAL, new String[]{"Horizontal", "Vertical"});
    public final ModeProperty colorMode = new ModeProperty("Color-Mode", HudStyle.MODE_HUD, HudStyle.COLOR_MODES);
    public final ColorProperty color = new ColorProperty("Color", 0x4FACFE, () -> this.colorMode.getValue() == HudStyle.MODE_CUSTOM);
    public final IntProperty alpha = new IntProperty("Alpha", 255, 40, 255);
    public final FloatProperty scale = new FloatProperty("Scale", 1.0F, 0.5F, 2.0F);
    public final IntProperty offsetX = new IntProperty("Offset-X", 0, -2000, 2000);
    public final IntProperty offsetY = new IntProperty("Offset-Y", 0, -2000, 2000);
    public final BooleanProperty background = new BooleanProperty("Background", true);
    public final IntProperty backgroundAlpha = new IntProperty("Background-Alpha", 90, 0, 255, background::getValue);
    public final FloatProperty radius = new FloatProperty("Radius", 6.0F, 0.0F, 12.0F);
    public final BooleanProperty heldItem = new BooleanProperty("Held-Item", false);
    public final BooleanProperty durabilityBar = new BooleanProperty("Durability-Bar", true);
    public final BooleanProperty durabilityText = new BooleanProperty("Durability-Text", false);
    public final BooleanProperty stackCount = new BooleanProperty("Stack-Count", true);
    public final BooleanProperty hideEmpty = new BooleanProperty("Hide-Empty", true);

    private final ItemStack[] visible = new ItemStack[5];

    public ArmorHUD() {
        super("ArmorHUD", false, false, "Armour pieces with durability above the hotbar");
    }

    /**
     * @return the items to draw, in display order, into {@link #visible}; count returned.
     */
    private int collect() {
        int count = 0;
        // inventory.armorInventory is boots..helmet; show helmet first.
        for (int i = 3; i >= 0; i--) {
            ItemStack stack = mc.thePlayer.inventory.armorInventory[i];
            if (stack == null && this.hideEmpty.getValue()) {
                continue;
            }
            visible[count++] = stack;
        }
        if (this.heldItem.getValue()) {
            ItemStack held = mc.thePlayer.inventory.getCurrentItem();
            if (held != null || !this.hideEmpty.getValue()) {
                visible[count++] = held;
            }
        }
        for (int i = count; i < visible.length; i++) {
            visible[i] = null;
        }
        return count;
    }

    /** Height of one item row: the slot, plus its own number band in the vertical layout. */
    private float band() {
        return this.durabilityText.getValue() ? TEXT_BAND : 0.0F;
    }

    /** Unscaled panel size for the given item count. */
    private float[] panelSize(int count) {
        boolean horizontal = this.layout.getValue() == LAYOUT_HORIZONTAL;
        float band = this.band();
        return new float[]{
                horizontal ? count * SLOT + PADDING * 2.0F : SLOT + PADDING * 2.0F,
                horizontal ? SLOT + band + PADDING * 2.0F : count * (SLOT + band) + PADDING * 2.0F
        };
    }

    /** Where the panel sits with zero offsets: centred just above the hotbar and its XP bar. */
    private float[] anchor(ScaledResolution sr, float width, float height) {
        float scale = this.scale.getValue();
        return new float[]{
                sr.getScaledWidth() / 2.0F - width * scale / 2.0F,
                sr.getScaledHeight() - 22.0F - 2.0F - height * scale - 12.0F
        };
    }

    @Override
    public String getHudName() {
        return "Armor HUD";
    }

    @Override
    public float[] getHudBounds() {
        if (mc.thePlayer == null) {
            return null;
        }
        int count = this.collect();
        if (count == 0) {
            // Nothing equipped: show the editor a full-size placeholder so it can still be moved.
            count = this.heldItem.getValue() ? 5 : 4;
        }
        float[] size = this.panelSize(count);
        float[] anchor = this.anchor(new ScaledResolution(mc), size[0], size[1]);
        float scale = this.scale.getValue();
        return new float[]{
                anchor[0] + this.offsetX.getValue(),
                anchor[1] + this.offsetY.getValue(),
                size[0] * scale,
                size[1] * scale
        };
    }

    @Override
    public void setHudPosition(float x, float y) {
        float[] bounds = this.getHudBounds();
        if (bounds == null) {
            return;
        }
        float[] size = new float[]{bounds[2] / this.scale.getValue(), bounds[3] / this.scale.getValue()};
        float[] anchor = this.anchor(new ScaledResolution(mc), size[0], size[1]);
        this.offsetX.setValue(Math.round(x - anchor[0]));
        this.offsetY.setValue(Math.round(y - anchor[1]));
    }

    @Override
    public void resetHudPosition() {
        this.offsetX.setValue(0);
        this.offsetY.setValue(0);
    }

    @Override
    public boolean isHudEnabled() {
        return this.isEnabled();
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (mc.thePlayer == null || mc.gameSettings.showDebugInfo || mc.playerController.isSpectator()) {
            return;
        }
        int count = this.collect();
        if (count == 0) {
            return;
        }

        boolean horizontal = this.layout.getValue() == LAYOUT_HORIZONTAL;
        float[] size = this.panelSize(count);
        float width = size[0];
        float height = size[1];

        ScaledResolution sr = new ScaledResolution(mc);
        float[] anchor = this.anchor(sr, width, height);
        float scale = this.scale.getValue();

        GlStateManager.pushMatrix();
        GlStateManager.translate(anchor[0] + this.offsetX.getValue(), anchor[1] + this.offsetY.getValue(), 0.0F);
        GlStateManager.scale(scale, scale, 1.0F);

        if (this.background.getValue()) {
            HudStyle.panel(0.0F, 0.0F, width, height, this.radius.getValue(), this.backgroundAlpha.getValue());
        }

        // Items first: item rendering leaves its own GL state, so the bars are drawn afterwards.
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        for (int i = 0; i < count; i++) {
            ItemStack stack = visible[i];
            if (stack == null) {
                continue;
            }
            int x = (int) (this.slotX(i, horizontal) + 2.0F);
            int y = (int) (this.slotY(i, horizontal) + 2.0F);
            RenderUtil.renderItemAndEffectIntoGui3D(stack, x, y);
            if (this.stackCount.getValue()) {
                mc.getRenderItem().renderItemOverlays(mc.fontRendererObj, stack, x, y);
            }
        }
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableRescaleNormal();

        for (int i = 0; i < count; i++) {
            ItemStack stack = visible[i];
            if (stack == null || !stack.isItemStackDamageable()) {
                continue;
            }
            float x = this.slotX(i, horizontal);
            float y = this.slotY(i, horizontal);
            float remaining = 1.0F - (float) stack.getItemDamage() / Math.max(1.0F, stack.getMaxDamage());
            remaining = Math.max(0.0F, Math.min(1.0F, remaining));

            if (this.durabilityBar.getValue()) {
                float barWidth = SLOT - 4.0F;
                float barX = x + 2.0F;
                float barY = y + SLOT - 3.0F;
                HudStyle.rect(barX, barY, barWidth, 1.5F, 0.75F, 0x66000000);
                int fill = this.colorMode.getValue() == HudStyle.MODE_CUSTOM
                        ? durabilityColor(remaining, this.alpha.getValue())
                        : HudStyle.color(this.colorMode.getValue(), this.color.getValue(), this.alpha.getValue(), i * 120L);
                HudStyle.rect(barX, barY, Math.max(0.75F, barWidth * remaining), 1.5F, 0.75F, fill);
            }
            if (this.durabilityText.getValue()) {
                String text = String.valueOf(stack.getMaxDamage() - stack.getItemDamage());
                // Centred in the band reserved above the slot rather than floating off the panel.
                HudStyle.drawCentered(text, x + SLOT / 2.0F,
                        y - (TEXT_BAND + HudStyle.fontHeight()) / 2.0F,
                        durabilityColor(remaining, this.alpha.getValue()), true);
            }
        }

        GlStateManager.popMatrix();
        RenderUtil.resetColor();
    }

    /** Top-left of item {@code i} inside the panel, in unscaled panel space. */
    private float slotX(int i, boolean horizontal) {
        return PADDING + (horizontal ? i * SLOT : 0.0F);
    }

    private float slotY(int i, boolean horizontal) {
        float band = this.band();
        return PADDING + band + (horizontal ? 0.0F : i * (SLOT + band));
    }

    /** Green when fresh, through yellow, to red when nearly broken. */
    private static int durabilityColor(float remaining, int alpha) {
        float hue = remaining * 0.33F;
        return (Math.max(0, Math.min(255, alpha)) << 24) | (java.awt.Color.HSBtoRGB(hue, 0.85F, 1.0F) & 0xFFFFFF);
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.layout.getModeString()};
    }
}
