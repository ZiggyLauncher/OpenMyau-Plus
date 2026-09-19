package myau.mixin;

import myau.module.modules.MenuStyle;
import myau.util.RenderUtil;
import myau.util.shader.RoundedUtils;
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
 * and suppresses only the full-width background slab so the pack's arrows, fuel icons, the player
 * preview and any custom widget still render normally on top.
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

    @Redirect(
            method = {"drawScreen"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/inventory/GuiContainer;drawGuiContainerBackgroundLayer(FII)V"
            )
    )
    private void myau$drawBackgroundLayer(GuiContainer screen, float partialTicks, int mouseX, int mouseY) {
        MenuStyle style = MenuStyle.containerStyle();
        if (style == null || (style.skipCreative.getValue() && (Object) this instanceof GuiContainerCreative)) {
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

    @Unique
    private void myau$drawPanel(MenuStyle style) {
        float radius = style.panelRadius.getValue();
        int panelAlpha = style.panelAlpha.getValue();

        RenderUtil.resetColor();
        GlStateManager.enableBlend();
        GlStateManager.disableLighting();

        Color background = new Color(20, 20, 24, Math.max(0, Math.min(255, panelAlpha)));
        if (style.accentBorder.getValue()) {
            Color border = new Color(style.accent(Math.min(200, panelAlpha), 0L), true);
            RoundedUtils.drawRoundOutline(this.guiLeft - 4.0F, this.guiTop - 4.0F,
                    this.xSize + 8.0F, this.ySize + 8.0F, radius, 1.0F, background, border);
        } else {
            RoundedUtils.drawRound(this.guiLeft - 4.0F, this.guiTop - 4.0F,
                    this.xSize + 8.0F, this.ySize + 8.0F, radius, background);
        }

        if (style.slotsVisible() && this.inventorySlots != null && this.inventorySlots.inventorySlots != null) {
            Color slotColor = new Color(255, 255, 255, Math.max(0, Math.min(255, style.slotAlpha.getValue())));
            float slotRadius = style.slotsRounded() ? style.slotRadius.getValue() : 0.0F;
            for (int i = 0; i < this.inventorySlots.inventorySlots.size(); i++) {
                Slot slot = this.inventorySlots.inventorySlots.get(i);
                if (slot == null) {
                    continue;
                }
                RoundedUtils.drawRound(this.guiLeft + slot.xDisplayPosition - 1.0F,
                        this.guiTop + slot.yDisplayPosition - 1.0F, 18.0F, 18.0F, slotRadius, slotColor);
            }
        }

        RenderUtil.resetColor();
    }
}
