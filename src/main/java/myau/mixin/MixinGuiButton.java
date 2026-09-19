package myau.mixin;

import myau.module.modules.MenuStyle;
import myau.util.RenderUtil;
import myau.util.shader.RoundedUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.Color;

/**
 * Replaces the vanilla button texture with a rounded one in the client accent colour, so every
 * vanilla and mod menu picks up the style without touching a single texture file - a resource
 * pack's button art is simply not drawn while this is on.
 */
@SideOnly(Side.CLIENT)
@Mixin(value = {GuiButton.class}, priority = 9999)
public abstract class MixinGuiButton {
    @Shadow
    public int xPosition;
    @Shadow
    public int yPosition;
    @Shadow
    public int width;
    @Shadow
    public int height;
    @Shadow
    public boolean visible;
    @Shadow
    public boolean enabled;
    @Shadow
    protected boolean hovered;
    @Shadow
    public String displayString;

    @Shadow
    protected abstract void mouseDragged(Minecraft mc, int mouseX, int mouseY);

    /** Per-button hover progress, so the fade is independent for each one. */
    @Unique
    private float myau$hover;
    @Unique
    private long myau$lastFrame;

    @Inject(
            method = {"drawButton"},
            at = {@At("HEAD")},
            cancellable = true
    )
    private void myau$drawStyledButton(Minecraft mc, int mouseX, int mouseY, CallbackInfo callbackInfo) {
        MenuStyle style = MenuStyle.buttonStyle();
        if (style == null || !this.visible) {
            return;
        }
        callbackInfo.cancel();

        this.hovered = mouseX >= this.xPosition && mouseY >= this.yPosition
                && mouseX < this.xPosition + this.width && mouseY < this.yPosition + this.height;

        long now = System.nanoTime();
        float delta = this.myau$lastFrame == 0L ? 0.0F
                : Math.min(0.1F, Math.max(0.0F, (now - this.myau$lastFrame) / 1.0e9F));
        this.myau$lastFrame = now;
        float target = this.hovered && this.enabled ? 1.0F : 0.0F;
        // Frame-rate independent fade, so the hover looks the same at 60 and 500 FPS.
        float factor = 1.0F - (float) Math.exp(-style.hoverSpeed.getValue() * delta);
        this.myau$hover += (target - this.myau$hover) * Math.max(0.0F, Math.min(1.0F, factor));

        int baseAlpha = Math.max(0, Math.min(255, style.buttonAlpha.getValue()));
        int accent = style.accent(baseAlpha, 0L);

        RenderUtil.resetColor();
        GlStateManager.enableBlend();
        GlStateManager.disableLighting();

        float radius = style.buttonRadius.getValue();
        float x = this.xPosition;
        float y = this.yPosition;

        if (!this.enabled) {
            RoundedUtils.drawRound(x, y, this.width, this.height, radius, new Color(40, 40, 46, baseAlpha));
        } else {
            // Idle is a dark plate; hovering fills it with the accent colour.
            Color idle = new Color(26, 26, 32, baseAlpha);
            Color hot = new Color((accent & 0xFFFFFF) | (baseAlpha << 24), true);
            Color fill = new Color(
                    Math.round(idle.getRed() + (hot.getRed() - idle.getRed()) * this.myau$hover),
                    Math.round(idle.getGreen() + (hot.getGreen() - idle.getGreen()) * this.myau$hover),
                    Math.round(idle.getBlue() + (hot.getBlue() - idle.getBlue()) * this.myau$hover),
                    baseAlpha);
            Color border = new Color((accent & 0xFFFFFF)
                    | (Math.max(0, Math.min(255, 60 + (int) (140 * this.myau$hover))) << 24), true);
            RoundedUtils.drawRoundOutline(x, y, this.width, this.height, radius, 1.0F, fill, border);
        }

        this.mouseDragged(mc, mouseX, mouseY);

        int textColor = !this.enabled ? 0xA0A0A0
                : this.myau$hover > 0.5F ? 0x0E0E12
                : 0xFFFFFF;
        mc.fontRendererObj.drawString(this.displayString,
                this.xPosition + this.width / 2 - mc.fontRendererObj.getStringWidth(this.displayString) / 2,
                this.yPosition + (this.height - 8) / 2,
                textColor, this.myau$hover <= 0.5F);

        RenderUtil.resetColor();
    }
}
