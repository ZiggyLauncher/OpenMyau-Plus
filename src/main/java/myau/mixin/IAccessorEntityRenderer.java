package myau.mixin;

import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@SideOnly(Side.CLIENT)
@Mixin({EntityRenderer.class})
public interface IAccessorEntityRenderer {
    @Invoker
    void callSetupCameraTransform(float float1, int integer);

    /** Vanilla's own post-process loader (the one spectator and Super Secret Settings use). */
    @Invoker
    void callLoadShader(ResourceLocation location);
}
