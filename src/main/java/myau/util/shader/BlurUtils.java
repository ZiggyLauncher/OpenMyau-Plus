package myau.util.shader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.shader.Framebuffer;
import myau.util.RenderUtil;

public class BlurUtils {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static Framebuffer stencilFrameBufferBlur = new Framebuffer(1, 1, false);
    private static Framebuffer stencilFrameBufferBloom = new Framebuffer(1, 1, false);
    private static boolean warned;

    public static void prepareBlur() {
        stencilFrameBufferBlur = RenderUtil.createFrameBuffer(stencilFrameBufferBlur);
        stencilFrameBufferBlur.framebufferClear();
        stencilFrameBufferBlur.bindFramebuffer(false);
    }

    public static void prepareBloom() {
        stencilFrameBufferBloom = RenderUtil.createFrameBuffer(stencilFrameBufferBloom);
        stencilFrameBufferBloom.framebufferClear();
        stencilFrameBufferBloom.bindFramebuffer(false);
    }

    public static void blurEnd(int passes, float radius) {
        try {
            stencilFrameBufferBlur.unbindFramebuffer();
            KawaseBlur.renderBlur(stencilFrameBufferBlur.framebufferTexture, passes, radius);
        } catch (Exception e) {
            warnOnce(e);
        } finally {
            restoreMinecraftFramebuffer();
        }
    }

    public static void bloomEnd(int passes, float radius) {
        try {
            stencilFrameBufferBloom.unbindFramebuffer();
            KawaseBloom.renderBlur(stencilFrameBufferBloom.framebufferTexture, passes, radius);
        } catch (Exception e) {
            warnOnce(e);
        } finally {
            restoreMinecraftFramebuffer();
        }
    }

    public static void prepareRiseBloom() {
        RiseBloomShader.prepareBloom();
    }

    public static void riseBloomEnd(int radius, float compression) {
        try {
            RiseBloomShader.bloomEnd(radius, compression);
        } catch (Exception e) {
            warnOnce(e);
        } finally {
            restoreMinecraftFramebuffer();
        }
    }

    /**
     * unbindFramebuffer() drops back to FBO 0 (the window), not Minecraft's own framebuffer. If a
     * shader pass bails out (no shader support, OptiFine, a GL error) without rebinding it, every
     * later draw call of the frame lands off-screen and the player sees a blank/white screen.
     */
    private static void restoreMinecraftFramebuffer() {
        Framebuffer framebuffer = mc.getFramebuffer();
        if (framebuffer != null) {
            framebuffer.bindFramebuffer(true);
        }
    }

    private static void warnOnce(Exception e) {
        if (!warned) {
            warned = true;
            System.err.println("[Myau+] Blur/bloom pass failed, continuing without it: " + e);
            e.printStackTrace();
        }
    }
}
