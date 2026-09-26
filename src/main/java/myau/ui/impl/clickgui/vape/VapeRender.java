package myau.ui.impl.clickgui.vape;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.TextureUtil;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Vape's GUI drawing primitives ({@code GuiRenderPrimitives} and {@code ImageRenderer}), in GUI
 * units: one unit is {@code 2 * scale} screen pixels. The shapes come from the same signed
 * distance shaders the original uses, with the same parameter juggling around them, so edges,
 * corner radii and shadows land where they do in Vape.
 */
final class VapeRender {
    private static final String VERTEX = "#version 120\n"
            + "varying vec2 f_Position;\n"
            + "void main() {\n"
            + "    f_Position = gl_Vertex.xy;\n"
            + "    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;\n"
            + "    gl_FrontColor = gl_Color;\n"
            + "    gl_TexCoord[0] = gl_TextureMatrix[0] * gl_MultiTexCoord0;\n"
            + "}\n";

    private static final String ROUNDED_RECT = "#version 120\n"
            + "uniform float u_Radius;\n"
            + "uniform vec4 u_InnerRect;\n"
            + "uniform float u_Spread;\n"
            + "uniform vec4 u_Corners;\n"
            + "varying vec2 f_Position;\n"
            + "void main() {\n"
            + "    vec2 tl = u_InnerRect.xy - f_Position;\n"
            + "    vec2 br = f_Position - u_InnerRect.zw;\n"
            + "    vec2 dis = max(br, tl);\n"
            + "    bool inTopLeft = f_Position.x <= u_InnerRect.x && f_Position.y <= u_InnerRect.y;\n"
            + "    bool inTopRight = f_Position.x >= u_InnerRect.z && f_Position.y <= u_InnerRect.y;\n"
            + "    bool inBottomLeft = f_Position.x <= u_InnerRect.x && f_Position.y >= u_InnerRect.w;\n"
            + "    bool inBottomRight = f_Position.x >= u_InnerRect.z && f_Position.y >= u_InnerRect.w;\n"
            + "    bool inCorner = (inTopLeft && u_Corners.x > 0.5) || (inTopRight && u_Corners.y > 0.5)\n"
            + "        || (inBottomLeft && u_Corners.w > 0.5) || (inBottomRight && u_Corners.z > 0.5);\n"
            + "    float v = length(max(vec2(0.0), dis)) - u_Radius;\n"
            + "    float a = inCorner ? 1.0 - smoothstep(0.0, u_Spread, v) : 1.0;\n"
            + "    gl_FragColor = gl_Color * vec4(1.0, 1.0, 1.0, a);\n"
            + "}\n";

    private static final String ROUNDED_BORDER = "#version 120\n"
            + "uniform vec3 u_Radius;\n"
            + "uniform vec4 u_InnerRect;\n"
            + "varying vec2 f_Position;\n"
            + "void main() {\n"
            + "    vec2 tl = u_InnerRect.xy - f_Position;\n"
            + "    vec2 br = f_Position - u_InnerRect.zw;\n"
            + "    vec2 dis = max(br, tl);\n"
            + "    float v = length(max(vec2(0.0), dis)) - u_Radius.x;\n"
            + "    float a = 1.0 - smoothstep(-u_Radius.y, 0.0, abs(v) - u_Radius.z);\n"
            + "    gl_FragColor = gl_Color * vec4(1.0, 1.0, 1.0, a);\n"
            + "}\n";

    private static final String SHADOW = "#version 120\n"
            + "uniform vec4 u_InnerRect;\n"
            + "uniform float u_Spread;\n"
            + "uniform vec4 u_Color;\n"
            + "uniform float u_CornerRadius;\n"
            + "varying vec2 f_Position;\n"
            + "float gi(float x) {\n"
            + "    float i6 = 1.0 / 6.0;\n"
            + "    float i4 = 1.0 / 4.0;\n"
            + "    float i3 = 1.0 / 3.0;\n"
            + "    if (x > 1.5) return 0.0;\n"
            + "    if (x < -1.5) return 1.0;\n"
            + "    float x2 = x * x;\n"
            + "    float x3 = x2 * x;\n"
            + "    if (x > 0.5) return 0.5625 - (x3 * i6 - 3.0 * x2 * i4 + 1.125 * x);\n"
            + "    if (x > -0.5) return 0.5 - (0.75 * x - x3 * i3);\n"
            + "    return 0.4375 + (-x3 * i6 - 3.0 * x2 * i4 - 1.125 * x);\n"
            + "}\n"
            + "float lineShadow(vec2 border, float pos, float sigma) {\n"
            + "    float pos1 = ((border.x - pos) / sigma) * 1.5;\n"
            + "    float pos2 = ((pos - border.y) / sigma) * 1.5;\n"
            + "    return 1.0 - abs(gi(pos1) - gi(pos2));\n"
            + "}\n"
            + "float distToRoundedRect(vec2 p, vec4 rect, float r) {\n"
            + "    vec2 d = abs(p - rect.xy - rect.zw * 0.5) - rect.zw * 0.5 + vec2(r);\n"
            + "    return length(max(d, 0.0)) - r;\n"
            + "}\n"
            + "void main() {\n"
            + "    float sigma = u_Spread;\n"
            + "    vec4 rect = u_InnerRect;\n"
            + "    float lineV = lineShadow(vec2(rect.x, rect.x + rect.z), f_Position.x, sigma);\n"
            + "    float lineH = lineShadow(vec2(rect.y, rect.y + rect.w), f_Position.y, sigma);\n"
            + "    vec4 result = u_Color;\n"
            + "    result.a *= lineV * lineH;\n"
            + "    float dist = distToRoundedRect(f_Position, rect, u_CornerRadius);\n"
            + "    float aaWidth = 0.1 * sigma;\n"
            + "    result.a *= smoothstep(-aaWidth, aaWidth, dist);\n"
            + "    gl_FragColor = result;\n"
            + "}\n";

    private static final String CAPSULE = "#version 120\n"
            + "uniform vec2 u_A;\n"
            + "uniform vec2 u_B;\n"
            + "uniform float u_Radius;\n"
            + "varying vec2 f_Position;\n"
            + "void main() {\n"
            + "    vec2 pa = f_Position - u_A;\n"
            + "    vec2 ba = u_B - u_A;\n"
            + "    float h = clamp(dot(pa, ba) / max(dot(ba, ba), 0.000001), 0.0, 1.0);\n"
            + "    float d = length(pa - ba * h) - u_Radius;\n"
            + "    float w = fwidth(d);\n"
            + "    float a = 1.0 - smoothstep(-0.5 * w, 0.5 * w, d);\n"
            + "    gl_FragColor = gl_Color * vec4(1.0, 1.0, 1.0, a);\n"
            + "}\n";

    private static final String GRADIENT_CAPSULE = "#version 120\n"
            + "uniform vec2 u_A;\n"
            + "uniform vec2 u_B;\n"
            + "uniform float u_Radius;\n"
            + "uniform vec4 u_StartHSBA;\n"
            + "uniform vec4 u_EndHSBA;\n"
            + "varying vec2 f_Position;\n"
            + "vec3 hsb2rgb(float h, float s, float b) {\n"
            + "    vec3 rgb = clamp(abs(mod(h * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);\n"
            + "    return b * mix(vec3(1.0), rgb, s);\n"
            + "}\n"
            + "void main() {\n"
            + "    vec2 pa = f_Position - u_A;\n"
            + "    vec2 ba = u_B - u_A;\n"
            + "    float h = clamp(dot(pa, ba) / max(dot(ba, ba), 0.000001), 0.0, 1.0);\n"
            + "    float d = length(pa - ba * h) - u_Radius;\n"
            + "    float w = fwidth(d);\n"
            + "    float alpha = 1.0 - smoothstep(-0.5 * w, 0.5 * w, d);\n"
            + "    vec4 hsba = mix(u_StartHSBA, u_EndHSBA, h);\n"
            + "    gl_FragColor = vec4(hsb2rgb(hsba.x, hsba.y, hsba.z), hsba.w * alpha);\n"
            + "}\n";

    private static final String CIRCLE = "#version 120\n"
            + "uniform float u_Radius;\n"
            + "uniform float u_Feather;\n"
            + "uniform vec2 u_CenterPos;\n"
            + "varying vec2 f_Position;\n"
            + "void main() {\n"
            + "    float v = length(f_Position - u_CenterPos);\n"
            + "    float a = 1.0 - smoothstep(u_Radius - u_Feather, u_Radius, v);\n"
            + "    gl_FragColor = gl_Color * vec4(1.0, 1.0, 1.0, a);\n"
            + "}\n";

    private static final String TEXT = "#version 120\n"
            + "uniform sampler2D u_Texture;\n"
            + "void main() {\n"
            + "    float alpha = texture2D(u_Texture, gl_TexCoord[0].xy).a;\n"
            + "    alpha = max(0.0, alpha + 0.25 * (1.0 - alpha));\n"
            + "    float smoothen = smoothstep(0.2, 0.8, alpha);\n"
            + "    alpha = mix(alpha, 0.0, 1.0 - smoothen);\n"
            + "    gl_FragColor = vec4(gl_Color.rgb, alpha * gl_Color.a);\n"
            + "}\n";

    private static Program roundedRect;
    private static Program roundedBorder;
    private static Program shadow;
    private static Program capsule;
    private static Program gradientCapsule;
    private static Program circle;
    private static Program text;
    private static boolean compiled;

    private static final Map<String, Texture> TEXTURES = new HashMap<String, Texture>();
    private static final Deque<int[]> SCISSORS = new ArrayDeque<int[]>();

    /** Screen pixels per GUI unit for the frame being drawn. */
    static double unit = 2.0;

    private VapeRender() {
    }

    // ------------------------------------------------------------------ frame setup

    /**
     * Switches the matrix from Minecraft's scaled GUI coordinates to Vape units. {@code
     * scaleFactor} is Minecraft's GUI scale factor.
     */
    static void begin(int scaleFactor) {
        compile();
        unit = 2.0 * VapeTheme.scale();
        GlStateManager.pushMatrix();
        double factor = unit / scaleFactor;
        GlStateManager.scale(factor, factor, 1.0);
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableAlpha();
        GlStateManager.disableCull();
        GlStateManager.disableDepth();
        GlStateManager.disableLighting();
    }

    static void end() {
        while (!SCISSORS.isEmpty()) {
            popScissor();
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.enableCull();
        GlStateManager.enableDepth();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.popMatrix();
    }

    static double mouseX() {
        return org.lwjgl.input.Mouse.getX() / unit;
    }

    static double mouseY() {
        return (Display.getHeight() - org.lwjgl.input.Mouse.getY() - 1) / unit;
    }

    static double screenWidth() {
        return Display.getWidth() / unit;
    }

    static double screenHeight() {
        return Display.getHeight() / unit;
    }

    private static void compile() {
        if (compiled) {
            return;
        }
        compiled = true;
        roundedRect = Program.create(ROUNDED_RECT);
        roundedBorder = Program.create(ROUNDED_BORDER);
        shadow = Program.create(SHADOW);
        capsule = Program.create(CAPSULE);
        gradientCapsule = Program.create(GRADIENT_CAPSULE);
        circle = Program.create(CIRCLE);
        text = Program.create(TEXT);
    }

    static Program textProgram() {
        compile();
        return text;
    }

    // ------------------------------------------------------------------ plain shapes

    private static void color(Color color) {
        GlStateManager.color(color.getRed() / 255.0F, color.getGreen() / 255.0F, color.getBlue() / 255.0F,
                color.getAlpha() / 255.0F);
    }

    private static void quad(double left, double top, double right, double bottom) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2d(left, top);
        GL11.glVertex2d(left, bottom);
        GL11.glVertex2d(right, bottom);
        GL11.glVertex2d(right, top);
        GL11.glEnd();
    }

    /** A plain rectangle ({@code C}). */
    static void rect(double x, double y, double width, double height, Color color) {
        GlStateManager.disableTexture2D();
        color(color);
        quad(x, y, x + width, y + height);
        GlStateManager.enableTexture2D();
    }

    static void triangle(double x1, double y1, double x2, double y2, double x3, double y3, Color color) {
        GlStateManager.disableTexture2D();
        color(color);
        GL11.glBegin(GL11.GL_TRIANGLES);
        GL11.glVertex2d(x2, y2);
        GL11.glVertex2d(x1, y1);
        GL11.glVertex2d(x3, y3);
        GL11.glEnd();
        GlStateManager.enableTexture2D();
    }

    // ------------------------------------------------------------------ rounded shapes

    /** {@code d}: a small-radius rounded rectangle. */
    static void rounded(double x, double y, double width, double height, Color color) {
        rounded(x, y, width, height, color, false, 1.5F, 1.0F);
    }

    /** {@code e}. */
    static void rounded(double x, double y, double width, double height, Color color, boolean shadow,
                        float radius, float spread) {
        rounded(x, y, width, height, color, shadow, radius, spread, 8.0F, VapeTheme.SHADOW, 15);
    }

    /** {@code p}: a rounded rectangle with optional drop shadow and per-corner rounding. */
    static void rounded(double x, double y, double width, double height, Color color, boolean dropShadow,
                        float radius, float spread, float shadowSpread, Color shadowColor, int corners) {
        if (width == 0.0 || height == 0.0) {
            return;
        }
        if (roundedRect == null || radius == 0.0F) {
            rect(x, y, width, height, color);
            return;
        }
        if (dropShadow) {
            shadow(x, y + 0.5, width, height - 1.5, shadowSpread, radius, shadowColor);
        }
        float edge = radius <= 0.0F ? 0.0F : 0.5F;
        float padded = radius + edge * 2.0F;
        float innerRadius = Math.max(0.0F, padded - spread);
        if (padded > 0.0F) {
            x -= spread - 0.5;
            y -= spread;
            height += spread;
            width += spread;
        }
        roundedRect.use();
        roundedRect.uniform1("u_Radius", innerRadius);
        roundedRect.uniform4("u_InnerRect", (float) x + padded, (float) y + padded,
                (float) (x + width) - padded, (float) (y + height) - padded);
        roundedRect.uniform1("u_Spread", spread);
        roundedRect.uniform4("u_Corners", (corners & 1) != 0 ? 1.0F : 0.0F, (corners & 2) != 0 ? 1.0F : 0.0F,
                (corners & 4) != 0 ? 1.0F : 0.0F, (corners & 8) != 0 ? 1.0F : 0.0F);
        GlStateManager.disableTexture2D();
        color(color);
        quad(x + edge, y + edge, x + width - edge, y + height - edge);
        GlStateManager.enableTexture2D();
        Program.release();
    }

    /** {@code P}: a rounded outline of the given thickness and edge softness. */
    static void border(double x, double y, double width, double height, Color color, float radius,
                       float thickness, float feather) {
        if (roundedBorder == null) {
            return;
        }
        float inset = radius + thickness;
        float left = (float) x + inset;
        y -= feather;
        float top = (float) y + inset;
        x -= feather - 0.5;
        width += feather;
        float right = (float) (x + width) - inset;
        height += feather * 1.5;
        float bottom = (float) (y + height) - inset;
        roundedBorder.use();
        roundedBorder.uniform4("u_InnerRect", left, top, right, bottom);
        roundedBorder.uniform3("u_Radius", radius, feather, thickness);
        GlStateManager.disableTexture2D();
        color(color);
        quad(x + 0.5, y + 0.5, x + width - 0.5, y + height - 0.5);
        GlStateManager.enableTexture2D();
        Program.release();
    }

    /** {@code g}: a soft shadow around a rounded rectangle. */
    static void shadow(double x, double y, double width, double height, float spread, float radius, Color color) {
        if (shadow == null || width == 0.0 || height == 0.0) {
            return;
        }
        y -= 1.0;
        height += 1.0;
        shadow.use();
        shadow.uniform4("u_InnerRect", (float) x, (float) y, (float) width, (float) height);
        shadow.uniform1("u_Spread", spread);
        shadow.uniform4("u_Color", color.getRed() / 255.0F, color.getGreen() / 255.0F, color.getBlue() / 255.0F,
                color.getAlpha() / 255.0F);
        shadow.uniform1("u_CornerRadius", radius);
        GlStateManager.disableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        quad(x - spread, y - spread, x + width + spread, y + height + spread);
        GlStateManager.enableTexture2D();
        Program.release();
    }

    /** {@code j}: a pill whose ends are fully round. */
    static void capsule(double x, double y, double width, double height, Color color) {
        if (width <= 0.0 || height <= 0.0) {
            return;
        }
        if (capsule == null) {
            rect(x, y, width, height, color);
            return;
        }
        capsule.use();
        capsule.uniform2("u_A", (float) (x + height / 2.0), (float) (y + height / 2.0));
        capsule.uniform2("u_B", (float) (x + width - height / 2.0), (float) (y + height / 2.0));
        capsule.uniform1("u_Radius", Math.max((float) height / 2.0F - 0.5F, 0.25F));
        GlStateManager.disableTexture2D();
        color(color);
        quad(x - 0.5, y - 0.5, x + width + 0.5, y + height + 0.5);
        GlStateManager.enableTexture2D();
        Program.release();
    }

    /** {@code F}: a pill shaded from one HSBA colour to another along its length. */
    static void gradientCapsule(double x, double y, double width, double height, float[] start, float[] end) {
        if (width <= 0.0 || height <= 0.0 || gradientCapsule == null) {
            return;
        }
        gradientCapsule.use();
        gradientCapsule.uniform2("u_A", (float) (x + height / 2.0), (float) (y + height / 2.0));
        gradientCapsule.uniform2("u_B", (float) (x + width - height / 2.0), (float) (y + height / 2.0));
        gradientCapsule.uniform1("u_Radius", Math.max((float) height / 2.0F - 0.5F, 0.25F));
        gradientCapsule.uniform4("u_StartHSBA", start[0], start[1], start[2], start[3]);
        gradientCapsule.uniform4("u_EndHSBA", end[0], end[1], end[2], end[3]);
        GlStateManager.disableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        quad(x - 0.5, y - 0.5, x + width + 0.5, y + height + 0.5);
        GlStateManager.enableTexture2D();
        Program.release();
    }

    /**
     * {@code Y}: a filled circle of diameter {@code size} whose top-left corner is at (x, y), with
     * an anti-aliased rim {@code feather} wide.
     */
    static void circle(double x, double y, double size, double feather, Color color) {
        if (circle == null) {
            rect(x, y, size, size, color);
            return;
        }
        double left = x - feather / 2.0;
        double top = y - feather / 2.0;
        double diameter = size + feather;
        circle.use();
        circle.uniform1("u_Radius", (float) (diameter / 2.0));
        circle.uniform1("u_Feather", (float) feather);
        circle.uniform2("u_CenterPos", (float) (left + diameter / 2.0), (float) (top + diameter / 2.0));
        GlStateManager.disableTexture2D();
        color(color);
        quad(left, top, left + diameter, top + diameter);
        GlStateManager.enableTexture2D();
        Program.release();
    }

    // ------------------------------------------------------------------ images

    /**
     * {@code ImageRenderer.drawImage}: a white icon tinted with {@code color}. When the requested
     * box is square the width follows the image's aspect ratio instead, anchored at the left.
     */
    static void image(Color color, double x, double y, String name, double width, double height) {
        Texture texture = texture(name);
        if (texture == null) {
            return;
        }
        if (width == height) {
            width *= (double) texture.width / (double) texture.height;
        }
        GlStateManager.enableTexture2D();
        GlStateManager.bindTexture(texture.id);
        color(color);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0.0F, 0.0F);
        GL11.glVertex2d(x, y);
        GL11.glTexCoord2f(0.0F, 1.0F);
        GL11.glVertex2d(x, y + height);
        GL11.glTexCoord2f(1.0F, 1.0F);
        GL11.glVertex2d(x + width, y + height);
        GL11.glTexCoord2f(1.0F, 0.0F);
        GL11.glVertex2d(x + width, y);
        GL11.glEnd();
    }

    /** {@code GuiRenderPrimitives.F}: an icon whose nominal box is centred on (cx, cy). */
    static void icon(String name, double centerX, double centerY, double width, double height, Color color) {
        image(color, centerX - width / 2.0, centerY - height / 2.0, name, width, height);
    }

    static double imageWidth(String name) {
        Texture texture = texture(name);
        return texture == null ? 0.0 : texture.width;
    }

    static double imageHeight(String name) {
        Texture texture = texture(name);
        return texture == null ? 0.0 : texture.height;
    }

    private static Texture texture(String name) {
        if (TEXTURES.containsKey(name)) {
            return TEXTURES.get(name);
        }
        Texture texture = null;
        InputStream stream = VapeRender.class.getResourceAsStream("/assets/myau/vape/textures/" + name + ".png");
        if (stream != null) {
            try {
                BufferedImage image = ImageIO.read(stream);
                int id = TextureUtil.glGenTextures();
                TextureUtil.uploadTextureImageAllocate(id, image, true, true);
                // The logo is drawn at about a third of its size; mipmaps keep it from shimmering,
                // which is why the original loads just these two with them.
                if ("vapelogo".equals(name) || "v4".equals(name)) {
                    generateMipmaps(id);
                }
                texture = new Texture(id, image.getWidth(), image.getHeight());
            } catch (Exception ignored) {
            } finally {
                try {
                    stream.close();
                } catch (Exception ignored) {
                }
            }
        }
        TEXTURES.put(name, texture);
        return texture;
    }

    private static void generateMipmaps(int id) {
        try {
            GlStateManager.bindTexture(id);
            if (GLContext.getCapabilities().OpenGL30) {
                GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            } else if (GLContext.getCapabilities().GL_EXT_framebuffer_object) {
                EXTFramebufferObject.glGenerateMipmapEXT(GL11.GL_TEXTURE_2D);
            } else {
                return;
            }
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ clipping

    /** Restricts drawing to a rectangle, intersected with any clip already active. */
    static void pushScissor(double x, double y, double width, double height) {
        int left = (int) Math.floor(x * unit);
        int right = (int) Math.ceil((x + width) * unit);
        int top = (int) Math.floor(y * unit);
        int bottom = (int) Math.ceil((y + height) * unit);
        int[] parent = SCISSORS.peek();
        if (parent != null) {
            left = Math.max(left, parent[0]);
            top = Math.max(top, parent[1]);
            right = Math.min(right, parent[2]);
            bottom = Math.min(bottom, parent[3]);
        }
        int[] clip = {left, top, Math.max(left, right), Math.max(top, bottom)};
        SCISSORS.push(clip);
        apply(clip);
    }

    static void popScissor() {
        SCISSORS.poll();
        int[] clip = SCISSORS.peek();
        if (clip == null) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        } else {
            apply(clip);
        }
    }

    private static void apply(int[] clip) {
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(clip[0], Display.getHeight() - clip[3], clip[2] - clip[0], clip[3] - clip[1]);
    }

    // ------------------------------------------------------------------ support

    private static final class Texture {
        final int id;
        final int width;
        final int height;

        Texture(int id, int width, int height) {
            this.id = id;
            this.width = width;
            this.height = height;
        }
    }

    static final class Program {
        private final int id;
        private final Map<String, Integer> locations = new HashMap<String, Integer>();

        private Program(int id) {
            this.id = id;
        }

        static Program create(String fragmentSource) {
            try {
                int vertex = shader(GL20.GL_VERTEX_SHADER, VERTEX);
                int fragment = shader(GL20.GL_FRAGMENT_SHADER, fragmentSource);
                if (vertex == 0 || fragment == 0) {
                    return null;
                }
                int program = GL20.glCreateProgram();
                GL20.glAttachShader(program, vertex);
                GL20.glAttachShader(program, fragment);
                GL20.glLinkProgram(program);
                GL20.glDeleteShader(vertex);
                GL20.glDeleteShader(fragment);
                if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                    System.err.println("[Vape GUI] shader link failed: " + GL20.glGetProgramInfoLog(program, 1024));
                    GL20.glDeleteProgram(program);
                    return null;
                }
                return new Program(program);
            } catch (Throwable throwable) {
                System.err.println("[Vape GUI] shaders unavailable: " + throwable);
                return null;
            }
        }

        private static int shader(int type, String source) {
            int shader = GL20.glCreateShader(type);
            GL20.glShaderSource(shader, source);
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                System.err.println("[Vape GUI] shader compile failed: " + GL20.glGetShaderInfoLog(shader, 1024));
                GL20.glDeleteShader(shader);
                return 0;
            }
            return shader;
        }

        void use() {
            GL20.glUseProgram(this.id);
        }

        static void release() {
            GL20.glUseProgram(0);
        }

        private int location(String name) {
            Integer location = this.locations.get(name);
            if (location == null) {
                location = GL20.glGetUniformLocation(this.id, name);
                this.locations.put(name, location);
            }
            return location;
        }

        void uniform1(String name, float value) {
            GL20.glUniform1f(this.location(name), value);
        }

        void uniform1i(String name, int value) {
            GL20.glUniform1i(this.location(name), value);
        }

        void uniform2(String name, float x, float y) {
            GL20.glUniform2f(this.location(name), x, y);
        }

        void uniform3(String name, float x, float y, float z) {
            GL20.glUniform3f(this.location(name), x, y, z);
        }

        void uniform4(String name, float x, float y, float z, float w) {
            GL20.glUniform4f(this.location(name), x, y, z, w);
        }
    }
}
