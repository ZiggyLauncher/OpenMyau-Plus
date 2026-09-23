package myau.module.modules;

import myau.event.EventTarget;
import myau.events.LoadWorldEvent;
import myau.events.Render3DEvent;
import myau.mixin.IAccessorRenderManager;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ColorProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.RenderUtil;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.player.EntityPlayer;
import org.lwjgl.opengl.GL11;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A smooth cylinder around players instead of a boxy bounding box: no corners, a soft vertical
 * fade, optional rings that drift around the target, and a flash when they take a hit.
 * <p>
 * The circle is trigonometry-free at render time - the unit circle for the chosen segment count is
 * computed once and cached - and every cylinder in view goes into one shared vertex buffer, so a
 * whole lobby costs two draw calls rather than a matrix push and an immediate-mode begin/end each.
 * There are no shaders, framebuffers or render target switches anywhere in this module.
 */
public class CylinderESP extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final int STYLE_OUTLINE = 0;
    private static final int STYLE_FILLED = 1;
    private static final int STYLE_BOTH = 2;

    public final ModeProperty style = new ModeProperty("Style", STYLE_BOTH, new String[]{"Outline", "Filled", "Both"});
    public final ModeProperty colorMode = new ModeProperty("Color-Mode", HudStyle.MODE_HUD, HudStyle.COLOR_MODES);
    public final ColorProperty color = new ColorProperty("Color", 0x4FACFE, () -> this.colorMode.getValue() == HudStyle.MODE_CUSTOM);
    public final BooleanProperty healthColor = new BooleanProperty("Health-Color", false);
    public final IntProperty alpha = new IntProperty("Alpha", 160, 10, 255);
    public final IntProperty segments = new IntProperty("Segments", 40, 8, 64);
    public final FloatProperty radius = new FloatProperty("Radius", 0.75F, 0.2F, 3.0F);
    public final FloatProperty height = new FloatProperty("Height", 1.0F, 0.1F, 2.0F);
    public final FloatProperty lineWidth = new FloatProperty("Line-Width", 1.5F, 0.5F, 4.0F);
    public final BooleanProperty smoothLines = new BooleanProperty("Smooth-Lines", true);
    public final BooleanProperty fade = new BooleanProperty("Fade", true);
    public final BooleanProperty baseRing = new BooleanProperty("Base-Ring", true);
    public final BooleanProperty topRing = new BooleanProperty("Top-Ring", true);
    public final FloatProperty spinSpeed = new FloatProperty("Spin-Speed", 20.0F, 0.0F, 180.0F, () -> this.topRing.getValue());
    public final BooleanProperty bob = new BooleanProperty("Bob", true, () -> this.topRing.getValue());
    public final BooleanProperty hurtFlash = new BooleanProperty("Hurt-Flash", true);
    public final BooleanProperty appearAnimation = new BooleanProperty("Appear-Animation", true);
    public final BooleanProperty throughWalls = new BooleanProperty("Through-Walls", true);
    public final FloatProperty range = new FloatProperty("Range", 64.0F, 8.0F, 128.0F);
    public final BooleanProperty players = new BooleanProperty("Players", true);
    public final BooleanProperty friends = new BooleanProperty("Friends", true);
    public final BooleanProperty enemies = new BooleanProperty("Enemies", true);
    public final BooleanProperty bots = new BooleanProperty("Bots", false);
    public final BooleanProperty self = new BooleanProperty("Self", false);

    /** Scratch for {@link #shade}, so the per-player colour costs no allocation. */
    private final float[] shade = new float[4];
    /** Unit circle cached for the current segment count: {@code [i * 2] = cos, [i * 2 + 1] = sin}. */
    private float[] circle = new float[0];
    private int circleSegments = -1;
    /** Per-player appear/disappear progress, keyed by entity id. */
    private final Map<Integer, Float> presence = new HashMap<>();
    /** Players that dropped out of range and are still fading, keyed the same way. */
    private final Map<Integer, EntityPlayer> fading = new HashMap<>();
    private long lastFrameNanos;
    /** Accumulated spin, so the top ring never jumps when the wall clock wraps. */
    private float spinDegrees;

    public CylinderESP() {
        super("CylinderESP", false, false, "Smooth round ESP cylinder around players");
    }

    @Override
    public void onEnabled() {
        this.presence.clear();
        this.fading.clear();
        this.lastFrameNanos = 0L;
        this.spinDegrees = 0.0F;
    }

    @Override
    public void onDisabled() {
        this.presence.clear();
        this.fading.clear();
    }

    @EventTarget(runWhenDisabled = true)
    public void onLoadWorld(LoadWorldEvent event) {
        this.presence.clear();
        this.fading.clear();
    }

    private float[] circle(int segments) {
        if (this.circleSegments == segments && this.circle.length == (segments + 1) * 2) {
            return this.circle;
        }
        // One extra vertex so loops can close without a modulo in the render path.
        float[] table = new float[(segments + 1) * 2];
        for (int i = 0; i <= segments; i++) {
            double angle = i * 2.0 * Math.PI / segments;
            table[i * 2] = (float) Math.cos(angle);
            table[i * 2 + 1] = (float) Math.sin(angle);
        }
        this.circle = table;
        this.circleSegments = segments;
        return table;
    }

    private boolean shouldRender(EntityPlayer player) {
        if (player.deathTime > 0 || player.isDead) {
            return false;
        }
        if (player == mc.thePlayer || player == mc.getRenderViewEntity()) {
            return this.self.getValue();
        }
        if (mc.getRenderViewEntity().getDistanceToEntity(player) > this.range.getValue()) {
            return false;
        }
        if (!player.ignoreFrustumCheck && !RenderUtil.isInViewFrustum(player.getEntityBoundingBox(), 0.5F)) {
            return false;
        }
        if (TeamUtil.isBot(player)) {
            return this.bots.getValue();
        }
        if (TeamUtil.isFriend(player)) {
            return this.friends.getValue();
        }
        return TeamUtil.isTarget(player) ? this.enemies.getValue() : this.players.getValue();
    }

    private int colorFor(EntityPlayer player, long offset) {
        if (this.healthColor.getValue()) {
            float health = Math.max(0.0F, Math.min(1.0F,
                    (player.getHealth() + player.getAbsorptionAmount()) / Math.max(1.0F, player.getMaxHealth())));
            int rgb = java.awt.Color.HSBtoRGB(health * 0.33F, 0.8F, 1.0F) & 0xFFFFFF;
            return (Math.max(0, Math.min(255, this.alpha.getValue())) << 24) | rgb;
        }
        return HudStyle.color(this.colorMode.getValue(), this.color.getValue(), this.alpha.getValue(), offset);
    }

    /**
     * Advances each player's appear/disappear progress, and appends the ones that are still
     * fading out to {@code visible} so the disappear animation is drawn rather than only counted.
     * <p>
     * A player who left the world is dropped at once - only someone who walked out of range,
     * behind you or behind a wall gets the fade.
     *
     * @return seconds since the previous frame
     */
    private float updatePresence(List<EntityPlayer> visible, float animationSpeed) {
        long now = System.nanoTime();
        float delta = this.lastFrameNanos == 0L ? 0.0F
                : Math.min(0.1F, Math.max(0.0F, (now - this.lastFrameNanos) / 1.0e9F));
        this.lastFrameNanos = now;

        Set<Integer> present = new HashSet<>();
        for (EntityPlayer player : visible) {
            int id = player.getEntityId();
            present.add(id);
            this.fading.remove(id);
            float current = this.presence.getOrDefault(id, this.appearAnimation.getValue() ? 0.0F : 1.0F);
            this.presence.put(id, HudStyle.approach(current, 1.0F, animationSpeed, delta));
        }

        if (this.presence.size() == present.size()) {
            return delta;
        }
        Iterator<Map.Entry<Integer, Float>> iterator = this.presence.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, Float> entry = iterator.next();
            int id = entry.getKey();
            if (present.contains(id)) {
                continue;
            }
            EntityPlayer leaving = this.fading.get(id);
            if (leaving == null) {
                leaving = this.lookup(id);
            }
            float faded = HudStyle.approach(entry.getValue(), 0.0F, animationSpeed, delta);
            if (leaving == null || faded <= 0.02F) {
                iterator.remove();
                this.fading.remove(id);
            } else {
                entry.setValue(faded);
                this.fading.put(id, leaving);
                visible.add(leaving);
            }
        }
        return delta;
    }

    /** The still-loaded player with this entity id, or null once they have left the world. */
    private EntityPlayer lookup(int id) {
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player.getEntityId() == id && !player.isDead) {
                return player;
            }
        }
        return null;
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (mc.theWorld == null || mc.thePlayer == null || mc.getRenderManager() == null || mc.getRenderViewEntity() == null) {
            return;
        }
        List<EntityPlayer> targets = mc.theWorld.playerEntities.stream()
                .filter(this::shouldRender)
                .collect(Collectors.toCollection(java.util.ArrayList::new));
        float delta = this.updatePresence(targets, this.appearAnimation.getValue() ? 9.0F : 1000.0F);
        if (targets.isEmpty()) {
            return;
        }

        float partialTicks = event.getPartialTicks();
        IAccessorRenderManager renderManager = (IAccessorRenderManager) mc.getRenderManager();
        double viewX = renderManager.getRenderPosX();
        double viewY = renderManager.getRenderPosY();
        double viewZ = renderManager.getRenderPosZ();

        int segments = this.segments.getValue();
        float[] circle = this.circle(segments);
        int style = this.style.getValue();
        boolean drawFill = style == STYLE_FILLED || style == STYLE_BOTH;
        boolean drawOutline = style == STYLE_OUTLINE || style == STYLE_BOTH;
        long now = System.currentTimeMillis();
        // Accumulated rather than derived from the clock: deriving it makes the ring jump
        // whenever the wrapped timestamp rolls over at anything but a whole turn.
        this.spinDegrees = (this.spinDegrees + this.spinSpeed.getValue() * delta) % 360.0F;
        float spin = this.spinDegrees;

        RenderUtil.enableRenderState();
        if (!this.throughWalls.getValue()) {
            GlStateManager.enableDepth();
        }
        GL11.glLineWidth(this.lineWidth.getValue());
        if (this.smoothLines.getValue()) {
            GL11.glEnable(GL11.GL_LINE_SMOOTH);
            GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
        }
        GlStateManager.shadeModel(GL11.GL_SMOOTH);

        // Everything is batched into at most two draws. Drawn the obvious way - a matrix push and
        // an immediate-mode begin/end per player - a full lobby is several thousand individual GL
        // calls a frame, which is where this module used to spend its time. Positioning the rings
        // on the CPU costs a handful of multiplications and lets every cylinder share one buffer.
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();
        float bobPhase = (float) Math.sin((now % 4000L) / 4000.0 * Math.PI * 2.0);
        double spinCos = Math.cos(Math.toRadians(spin));
        double spinSin = Math.sin(Math.toRadians(spin));
        boolean faded = this.fade.getValue();

        if (drawFill) {
            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
            for (int index = 0; index < targets.size(); index++) {
                EntityPlayer player = targets.get(index);
                float presence = this.presence.getOrDefault(player.getEntityId(), 1.0F);
                if (presence <= 0.02F) {
                    continue;
                }
                float[] shade = this.shade(player, index, presence);
                float radius = this.radiusOf(player);
                float height = this.heightOf(player, presence);
                double x = this.interpolated(player.lastTickPosX, player.posX, partialTicks) - viewX;
                double y = this.interpolated(player.lastTickPosY, player.posY, partialTicks) - viewY;
                double z = this.interpolated(player.lastTickPosZ, player.posZ, partialTicks) - viewZ;
                float bottomAlpha = shade[3] * 0.55F;
                float topAlpha = faded ? 0.0F : bottomAlpha;

                // Quads rather than a strip: a strip would join one player's cylinder to the next.
                for (int i = 0; i < segments; i++) {
                    float x0 = circle[i * 2] * radius;
                    float z0 = circle[i * 2 + 1] * radius;
                    float x1 = circle[(i + 1) * 2] * radius;
                    float z1 = circle[(i + 1) * 2 + 1] * radius;
                    buffer.pos(x + x0, y, z + z0).color(shade[0], shade[1], shade[2], bottomAlpha).endVertex();
                    buffer.pos(x + x1, y, z + z1).color(shade[0], shade[1], shade[2], bottomAlpha).endVertex();
                    buffer.pos(x + x1, y + height, z + z1).color(shade[0], shade[1], shade[2], topAlpha).endVertex();
                    buffer.pos(x + x0, y + height, z + z0).color(shade[0], shade[1], shade[2], topAlpha).endVertex();
                }
            }
            tessellator.draw();
        }

        if (drawOutline && (this.baseRing.getValue() || this.topRing.getValue())) {
            buffer.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
            for (int index = 0; index < targets.size(); index++) {
                EntityPlayer player = targets.get(index);
                float presence = this.presence.getOrDefault(player.getEntityId(), 1.0F);
                if (presence <= 0.02F) {
                    continue;
                }
                float[] shade = this.shade(player, index, presence);
                float radius = this.radiusOf(player);
                float height = this.heightOf(player, presence);
                double x = this.interpolated(player.lastTickPosX, player.posX, partialTicks) - viewX;
                double y = this.interpolated(player.lastTickPosY, player.posY, partialTicks) - viewY;
                double z = this.interpolated(player.lastTickPosZ, player.posZ, partialTicks) - viewZ;

                if (this.baseRing.getValue()) {
                    this.ring(buffer, circle, segments, radius, x, y, z, 1.0, 0.0,
                            shade[0], shade[1], shade[2], shade[3]);
                }
                if (this.topRing.getValue()) {
                    float bobOffset = this.bob.getValue() ? bobPhase * height * 0.04F : 0.0F;
                    this.ring(buffer, circle, segments, radius * 0.92F, x, y + height + bobOffset, z,
                            spinCos, spinSin, shade[0], shade[1], shade[2], shade[3] * 0.8F);
                }
            }
            tessellator.draw();
        }

        GlStateManager.shadeModel(GL11.GL_FLAT);
        if (this.smoothLines.getValue()) {
            GL11.glDisable(GL11.GL_LINE_SMOOTH);
        }
        GL11.glLineWidth(1.0F);
        RenderUtil.disableRenderState();
        RenderUtil.resetColor();
    }

    /**
     * One ring as line pairs into the shared buffer, rotated on the CPU by {@code (cos, sin)} so
     * the spinning top ring needs no matrix of its own.
     */
    private void ring(WorldRenderer buffer, float[] circle, int segments, float radius,
                      double x, double y, double z, double cos, double sin,
                      float red, float green, float blue, float alpha) {
        for (int i = 0; i < segments; i++) {
            float ax = circle[i * 2] * radius;
            float az = circle[i * 2 + 1] * radius;
            float bx = circle[(i + 1) * 2] * radius;
            float bz = circle[(i + 1) * 2 + 1] * radius;
            buffer.pos(x + ax * cos - az * sin, y, z + ax * sin + az * cos)
                    .color(red, green, blue, alpha).endVertex();
            buffer.pos(x + bx * cos - bz * sin, y, z + bx * sin + bz * cos)
                    .color(red, green, blue, alpha).endVertex();
        }
    }

    /** Interpolated world position for the current frame. */
    private double interpolated(double last, double current, float partialTicks) {
        return last + (current - last) * partialTicks;
    }

    private float radiusOf(EntityPlayer player) {
        float radius = player.width * 0.5F + this.radius.getValue() - 0.3F;
        return radius < 0.1F ? 0.1F : radius;
    }

    /** Grows out of the ground as the cylinder appears. */
    private float heightOf(EntityPlayer player, float presence) {
        return player.height * this.height.getValue() * (0.35F + 0.65F * presence);
    }

    /** {red, green, blue, alpha} for this player, including the hurt flash. */
    private float[] shade(EntityPlayer player, int index, float presence) {
        int argb = this.colorFor(player, index * 120L);
        float red = ((argb >> 16) & 0xFF) / 255.0F;
        float green = ((argb >> 8) & 0xFF) / 255.0F;
        float blue = (argb & 0xFF) / 255.0F;
        float alpha = ((argb >>> 24) & 0xFF) / 255.0F * presence;

        // A hit whitens the cylinder briefly, the way vanilla flashes the model red.
        if (this.hurtFlash.getValue() && player.hurtTime > 0) {
            float flash = player.hurtTime / 10.0F;
            red = red + (1.0F - red) * flash;
            green = green + (1.0F - green) * flash * 0.4F;
            blue = blue + (1.0F - blue) * flash * 0.4F;
        }
        this.shade[0] = red;
        this.shade[1] = green;
        this.shade[2] = blue;
        this.shade[3] = alpha;
        return this.shade;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.style.getModeString()};
    }
}
