package myau.ui.impl.clickgui.vape;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.TextureUtil;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Vape's text renderer ({@code LegacySmoothFontRenderer} over Proxima Nova), sized the way it
 * sizes fonts:
 * <ul>
 *     <li>a font requested at scale {@code s} is baked at {@code (int) ((int) (16 * s) * guiScale)}
 *     pixels, minus one for Proxima;</li>
 *     <li>the bake height is chosen so a capital H is 0.6 of that;</li>
 *     <li>baked pixels are drawn at {@code 0.6 / guiScale} GUI units each, and a line is
 *     {@code size * 0.6 / guiScale} units tall, with the ascent below the given y.</li>
 * </ul>
 * The original packs its glyphs with stb_truetype at 2x2 oversampling; here Java2D fills the
 * same outlines at twice the size, unhinted, which gives the same shapes and advances.
 */
final class VapeFont {
    /** hhea ascent and descent of proxima.ttf, in font units. */
    private static final float ASCENT = 1823.0F;
    private static final float DESCENT = -448.0F;
    private static final float UNITS_PER_EM = 2048.0F;
    /** Height of the capital H (OS/2 cap height), in font units. */
    private static final float CAP_HEIGHT = 1366.0F;
    private static final int OVERSAMPLE = 2;
    private static final int PADDING = 2;

    private static final Map<Integer, VapeFont> CACHE = new HashMap<Integer, VapeFont>();
    private static Font baseFont;
    private static boolean baseLoaded;
    private static double cachedScale = -1.0;

    private final int size;
    private final Glyph[] glyphs = new Glyph[256];
    private final float ascentPixels;
    private final Map<String, Double> widths = new HashMap<String, Double>();
    private int textureId = -1;

    /** The renderer {@code getFontRenderer(scale)} would hand out at the current GUI scale. */
    static VapeFont get(double scale) {
        double guiScale = VapeTheme.scale();
        if (guiScale != cachedScale) {
            for (VapeFont font : CACHE.values()) {
                font.destroy();
            }
            CACHE.clear();
            cachedScale = guiScale;
        }
        int requested = (int) (16.0 * scale);
        requested = (int) ((double) requested * guiScale);
        VapeFont font = CACHE.get(requested);
        if (font == null) {
            font = new VapeFont(requested - 1);
            CACHE.put(requested, font);
        }
        return font;
    }

    private VapeFont(int size) {
        this.size = Math.max(1, size);
        float capRatio = CAP_HEIGHT / (ASCENT - DESCENT);
        float pixelHeight = this.size * (0.6F / capRatio);
        float pixelsPerUnit = pixelHeight / (ASCENT - DESCENT);
        this.ascentPixels = ASCENT * pixelsPerUnit;
        this.bake(pixelsPerUnit * UNITS_PER_EM);
    }

    private static Font baseFont() {
        if (!baseLoaded) {
            baseLoaded = true;
            InputStream stream = VapeFont.class.getResourceAsStream("/assets/myau/vape/proxima.ttf");
            try {
                if (stream != null) {
                    baseFont = Font.createFont(Font.TRUETYPE_FONT, stream);
                }
            } catch (Exception exception) {
                System.err.println("[Vape GUI] could not load proxima.ttf: " + exception);
            } finally {
                try {
                    if (stream != null) {
                        stream.close();
                    }
                } catch (Exception ignored) {
                }
            }
            if (baseFont == null) {
                baseFont = new Font("SansSerif", Font.PLAIN, 1);
            }
        }
        return baseFont;
    }

    private void bake(float emPixels) {
        Font font = baseFont().deriveFont(emPixels * OVERSAMPLE);
        FontRenderContext context = new FontRenderContext(new AffineTransform(), true, true);

        // Lay the glyphs out in rows first to learn the atlas size.
        int atlasWidth = 512;
        int cursorX = 0;
        int cursorY = 0;
        int rowHeight = 0;
        GlyphVector[] vectors = new GlyphVector[256];
        Rectangle[] boxes = new Rectangle[256];
        int[] cellX = new int[256];
        int[] cellY = new int[256];
        for (int c = 32; c < 256; c++) {
            if (c >= 127 && c < 160) {
                continue;
            }
            GlyphVector vector = font.createGlyphVector(context, String.valueOf((char) c));
            Rectangle2D bounds = vector.getOutline().getBounds2D();
            Rectangle box = new Rectangle(
                    (int) Math.floor(bounds.getMinX()) - PADDING, (int) Math.floor(bounds.getMinY()) - PADDING,
                    (int) Math.ceil(bounds.getWidth()) + PADDING * 2 + 1, (int) Math.ceil(bounds.getHeight()) + PADDING * 2 + 1);
            if (bounds.isEmpty()) {
                box = new Rectangle(0, 0, 1, 1);
            }
            if (cursorX + box.width > atlasWidth) {
                cursorX = 0;
                cursorY += rowHeight;
                rowHeight = 0;
            }
            vectors[c] = vector;
            boxes[c] = box;
            cellX[c] = cursorX;
            cellY[c] = cursorY;
            cursorX += box.width;
            rowHeight = Math.max(rowHeight, box.height);
        }
        int atlasHeight = 64;
        while (atlasHeight < cursorY + rowHeight) {
            atlasHeight *= 2;
        }

        BufferedImage atlas = new BufferedImage(atlasWidth, atlasHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = atlas.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setColor(Color.WHITE);
        for (int c = 32; c < 256; c++) {
            GlyphVector vector = vectors[c];
            if (vector == null) {
                continue;
            }
            Rectangle box = boxes[c];
            float originX = cellX[c] - box.x;
            float originY = cellY[c] - box.y;
            graphics.fill(vector.getOutline(originX, originY));
            float advance = (float) vector.getGlyphMetrics(0).getAdvanceX() / OVERSAMPLE;
            this.glyphs[c] = new Glyph(
                    (float) box.x / OVERSAMPLE, (float) box.y / OVERSAMPLE,
                    (float) (box.x + box.width) / OVERSAMPLE, (float) (box.y + box.height) / OVERSAMPLE,
                    (float) cellX[c] / atlasWidth, (float) cellY[c] / atlasHeight,
                    (float) (cellX[c] + box.width) / atlasWidth, (float) (cellY[c] + box.height) / atlasHeight,
                    advance);
        }
        graphics.dispose();

        this.textureId = TextureUtil.glGenTextures();
        TextureUtil.uploadTextureImageAllocate(this.textureId, atlas, true, true);
    }

    private void destroy() {
        if (this.textureId != -1) {
            TextureUtil.deleteTexture(this.textureId);
            this.textureId = -1;
        }
    }

    /** GUI units per baked pixel. */
    private static double pixelScale() {
        double guiScale = VapeTheme.scale();
        double scale = 0.6;
        if (guiScale != 1.0) {
            scale *= 1.0 / guiScale;
        }
        return scale;
    }

    private Glyph glyph(char c) {
        Glyph glyph = c < 256 ? this.glyphs[c] : null;
        return glyph != null ? glyph : this.glyphs[' '];
    }

    /** Line height ({@code d(String)}). */
    double height() {
        return this.size * pixelScale();
    }

    /** Text width ({@code N(String)}). */
    double width(String text) {
        Double cached = this.widths.get(text);
        if (cached != null) {
            return cached;
        }
        if (this.widths.size() > 4096) {
            // Values that change (slider numbers, typed text) would otherwise pile up here.
            this.widths.clear();
        }
        double scale = pixelScale();
        double width = 0.0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§') {
                i++;
                continue;
            }
            Glyph glyph = this.glyph(c);
            if (glyph != null) {
                width += glyph.advance * scale;
            }
        }
        this.widths.put(text, width);
        return width;
    }

    /** Draws text with its top at y ({@code d(String, x, y, Color)}). */
    void draw(String text, double x, double y, Color color) {
        if (text == null || text.isEmpty() || this.textureId == -1) {
            return;
        }
        double scale = pixelScale();
        double baseline = y + this.ascentPixels * scale;
        int alpha = color.getAlpha() == 0 ? 255 : color.getAlpha();

        VapeRender.Program program = VapeRender.textProgram();
        if (program != null) {
            program.use();
            program.uniform1i("u_Texture", 0);
        }
        GlStateManager.enableTexture2D();
        GlStateManager.bindTexture(this.textureId);
        GlStateManager.color(color.getRed() / 255.0F, color.getGreen() / 255.0F, color.getBlue() / 255.0F, alpha / 255.0F);
        GL11.glBegin(GL11.GL_QUADS);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§') {
                i++;
                continue;
            }
            Glyph glyph = this.glyph(c);
            if (glyph == null) {
                continue;
            }
            double left = x + glyph.left * scale;
            double top = baseline + glyph.top * scale;
            double right = x + glyph.right * scale;
            double bottom = baseline + glyph.bottom * scale;
            GL11.glTexCoord2f(glyph.u0, glyph.v0);
            GL11.glVertex2d(left, top);
            GL11.glTexCoord2f(glyph.u0, glyph.v1);
            GL11.glVertex2d(left, bottom);
            GL11.glTexCoord2f(glyph.u1, glyph.v1);
            GL11.glVertex2d(right, bottom);
            GL11.glTexCoord2f(glyph.u1, glyph.v0);
            GL11.glVertex2d(right, top);
            x += glyph.advance * scale;
        }
        GL11.glEnd();
        if (program != null) {
            VapeRender.Program.release();
        }
    }

    private static final class Glyph {
        final float left;
        final float top;
        final float right;
        final float bottom;
        final float u0;
        final float v0;
        final float u1;
        final float v1;
        final float advance;

        Glyph(float left, float top, float right, float bottom, float u0, float v0, float u1, float v1, float advance) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.u0 = u0;
            this.v0 = v0;
            this.u1 = u1;
            this.v1 = v1;
            this.advance = advance;
        }
    }
}
