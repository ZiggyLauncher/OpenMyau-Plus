package myau.mixin;

import myau.module.modules.MenuStyle;
import myau.util.RenderUtil;
import myau.util.shader.RoundedUtils;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.awt.Color;

/**
 * Draws the rounded container panel underneath whatever the screen (or a resource pack) paints,
 * suppresses only the full-width background slab, and replaces the vanilla square hover highlight
 * with one that matches the slot shape.
 * <p>
 * Slots are drawn inset inside their 18px cell so neighbouring slots never touch - drawing them
 * at full cell size is what turns a rounded grid back into one flat rectangle.
 */
@SideOnly(Side.CLIENT)
@Mixin(value = {GuiContainer.class}, priority = 9999)
public abstract class MixinGuiContainer {
    @Shadow
    protected int xSize;
    @Shadow
    protected int ySize;
    @Shadow
    protected int guiLeft;
    @Shadow
    protected int guiTop;
    @Shadow
    public Container inventorySlots;

    @Shadow
    protected abstract void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY);

    /** The style to use for this screen, or null when it should stay vanilla. */
    @Unique
    private MenuStyle myau$style() {
        MenuStyle style = MenuStyle.containerStyle();
        if (style == null) {
            return null;
        }
        if (style.skipCreative.getValue() && (Object) this instanceof GuiContainerCreative) {
            return null;
        }
        return style;
    }

    @Redirect(
            method = {"drawScreen"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/inventory/GuiContainer;drawGuiContainerBackgroundLayer(FII)V"
            )
    )
    private void myau$drawBackgroundLayer(GuiContainer screen, float partialTicks, int mouseX, int mouseY) {
        MenuStyle style = this.myau$style();
        if (style == null) {
            this.drawGuiContainerBackgroundLayer(partialTicks, mouseX, mouseY);
            return;
        }

        this.myau$drawPanel(style);

        // The screen still draws everything else it wants on top of our panel.
        MenuStyle.skipBackgroundSlab = true;
        MenuStyle.slabWidth = this.xSize;
        try {
            this.drawGuiContainerBackgroundLayer(partialTicks, mouseX, mouseY);
        } finally {
            MenuStyle.skipBackgroundSlab = false;
        }
    }

    /**
     * Vanilla highlights the hovered slot with a flat white square. That reads as a bug next to
     * rounded slots, so it is redrawn with the slot's own geometry instead.
     * <p>
     * This runs inside the matrix {@code drawScreen} already translated by guiLeft/guiTop, so the
     * incoming coordinates are slot-local and are used as-is.
     */
    @Redirect(
            method = {"drawScreen"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/inventory/GuiContainer;drawGradientRect(IIIIII)V"
            )
    )
    private void myau$drawSlotHighlight(GuiContainer screen, int left, int top, int right, int bottom,
                                        int startColor, int endColor) {
        MenuStyle style = this.myau$style();
        if (style == null || style.highlight.getValue() == MenuStyle.HIGHLIGHT_VANILLA) {
            // Not a @Shadow of drawGradientRect: it is declared on Gui rather than on the target,
            // so the annotation processor writes no refmap entry for it and the shadow fails to
            // apply once obfuscated. The one call site this redirect replaces always passes the
            // same colour twice, so the static drawRect reproduces it exactly.
            Gui.drawRect(left, top, right, bottom, startColor);
            return;
        }

        float gap = style.slotGap.getValue();
        float size = 18.0F - gap * 2.0F;
        float radius = style.slotsRounded() ? Math.min(style.slotRadius.getValue(), size * 0.5F) : 0.0F;
        int alpha = Math.max(0, Math.min(255, style.highlightAlpha.getValue()));
        int rgb = style.highlight.getValue() == MenuStyle.HIGHLIGHT_ACCENT
                ? style.accent(alpha, 0L) & 0xFFFFFF
                : 0xFFFFFF;

        RenderUtil.resetColor();
        GlStateManager.enableBlend();
        GlStateManager.disableLighting();
        RoundedUtils.drawRound(left - 1.0F + gap, top - 1.0F + gap, size, size, radius,
                new Color(rgb | (alpha << 24), true));
        RenderUtil.resetColor();
    }

    @Unique
    private void myau$drawPanel(MenuStyle style) {
        float radius = style.panelRadius.getValue();
        int panelAlpha = Math.max(0, Math.min(255, style.panelAlpha.getValue()));

        RenderUtil.resetColor();
        GlStateManager.enableBlend();
        GlStateManager.disableLighting();

        Color background = new Color(16, 16, 20, panelAlpha);
        if (style.accentBorder.getValue()) {
            Color border = new Color(style.accent(Math.min(200, panelAlpha), 0L), true);
            RoundedUtils.drawRoundOutline(this.guiLeft - 5.0F, this.guiTop - 5.0F,
                    this.xSize + 10.0F, this.ySize + 10.0F, radius, 1.0F, background, border);
        } else {
            RoundedUtils.drawRound(this.guiLeft - 5.0F, this.guiTop - 5.0F,
                    this.xSize + 10.0F, this.ySize + 10.0F, radius, background);
        }

        if (style.slotsVisible() && this.inventorySlots != null && this.inventorySlots.inventorySlots != null) {
            int slotAlpha = Math.max(0, Math.min(255, style.slotAlpha.getValue()));
            float gap = style.slotGap.getValue();
            // Inset inside the 18px cell: at full size neighbouring slots touch and the grid
            // collapses into one flat slab, which is the whole reason the rounding is there.
            float size = 18.0F - gap * 2.0F;
            float slotRadius = style.slotsRounded() ? Math.min(style.slotRadius.getValue(), size * 0.5F) : 0.0F;
            Color fill = new Color(255, 255, 255, slotAlpha);
            Color border = new Color(255, 255, 255, Math.min(255, slotAlpha + 22));

            for (int i = 0; i < this.inventorySlots.inventorySlots.size(); i++) {
                Slot slot = this.inventorySlots.inventorySlots.get(i);
                if (slot == null) {
                    continue;
                }
                float x = this.guiLeft + slot.xDisplayPosition - 1.0F + gap;
                float y = this.guiTop + slot.yDisplayPosition - 1.0F + gap;
                if (style.slotBorder.getValue()) {
                    RoundedUtils.drawRoundOutline(x, y, size, size, slotRadius, 1.0F, fill, border);
                } else {
                    RoundedUtils.drawRound(x, y, size, size, slotRadius, fill);
                }
            }
        }

        RenderUtil.resetColor();
    }
}
