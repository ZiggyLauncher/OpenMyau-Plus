package myau.mixin;

import myau.module.modules.MenuStyle;
import net.minecraft.client.gui.Gui;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops the container's background slab while {@link MenuStyle} draws its own panel.
 * <p>
 * Only full-width draws are suppressed, which is what every container uses for its background
 * (one slab for an inventory, two stacked for a chest). Progress arrows, flames, bubbles and any
 * other decoration a screen or resource pack draws are narrower, so they are untouched and end up
 * on top of the rounded panel.
 */
@SideOnly(Side.CLIENT)
@Mixin(value = {Gui.class}, priority = 9999)
public abstract class MixinGui {
    @Inject(
            method = {"drawTexturedModalRect(IIIIII)V"},
            at = {@At("HEAD")},
            cancellable = true
    )
    private void myau$skipContainerSlab(int x, int y, int textureX, int textureY, int width, int height, CallbackInfo callbackInfo) {
        if (!MenuStyle.skipBackgroundSlab) {
            return;
        }
        // A background slab spans (nearly) the whole container width and is more than a single row tall.
        if (width >= MenuStyle.slabWidth - 8 && height > 16) {
            callbackInfo.cancel();
        }
    }
}
