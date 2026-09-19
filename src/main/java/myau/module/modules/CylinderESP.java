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
import net.minecraft.entity.player.EntityPlayer;
import org.lwjgl.opengl.GL11;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A smooth cylinder around players instead of a boxy bounding box: no corners, a soft vertical
 * fade, optional rings that drift around the target, and a flash when they take a hit.
 * <p>
 * The circle is trigonometry-free at render time - the unit circle for the chosen segment count is
 * computed once and cached, so a cylinder is {@code segments * 2} vertices of plain immediate-mode
 * geometry. Even with a full lobby in range that is a few thousand vertices per frame, which is
 * nothing next to the world itself, and there are no shaders, framebuffers or render target
 * switches anywhere in this module.
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

    /** Unit circle cached for the current segment count: {@code [i * 2] = cos, [i * 2 + 1] = sin}. */
    private float[] circle = new float[0];
    private int circleSegments = -1;
    /** Per-player appear/disappear progress, keyed by entity id. */
    private final Map<Integer, Float> presence = new HashMap<>();
    private long lastFrameNanos;

    public CylinderESP() {
        super("CylinderESP", false, false, "Smooth round ESP cylinder around players");
    }

    @Override
    public void onEnabled() {
        this.presence.clear();
        this.lastFrameNanos = 0L;
    }

    @Override
    public void onDisabled() {
        this.presence.clear();
    }

    @EventTarget(runWhenDisabled = true)
    public void onLoadWorld(LoadWorldEvent event) {
        this.presence.clear();
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
     * Advances each player's appear/disappear progress and drops the ones that have faded out.
     *
     * @return seconds since the previous frame
     */
    private float updatePresence(List<EntityPlayer> visible, float animationSpeed) {
        long now = System.nanoTime();
        float delta = this.lastFrameNanos == 0L ? 0.0F
                : Math.min(0.1F, Math.max(0.0F, (now - this.lastFrameNanos) / 1.0e9F));
        this.lastFrameNanos = now;

        for (EntityPlayer player : visible) {
            int id = player.getEntityId();
            float current = this.presence.getOrDefault(id, this.appearAnimation.getValue() ? 0.0F : 1.0F);
            this.presence.put(id, HudStyle.approach(current, 1.0F, animationSpeed, delta));
        }
        if (this.presence.size() > visible.size()) {
            Iterator<Map.Entry<Integer, Float>> iterator = this.presence.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Integer, Float> entry = iterator.next();
                boolean stillVisible = false;
                for (EntityPlayer player : visible) {
                    if (player.getEntityId() == entry.getKey()) {
                        stillVisible = true;
                        break;
                    }
                }
                if (stillVisible) {
                    continue;
                }
                float faded = HudStyle.approach(entry.getValue(), 0.0F, animationSpeed, delta);
                if (faded <= 0.02F) {
                    iterator.remove();
                } else {
                    entry.setValue(faded);
                }
            }
        }
        return delta;
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (mc.theWorld == null || mc.thePlayer == null || mc.getRenderManager() == null || mc.getRenderViewEntity() == null) {
            return;
        }
        List<EntityPlayer> targets = mc.theWorld.playerEntities.stream()
                .filter(this::shouldRender)
                .collect(Collectors.toList());
        this.updatePresence(targets, this.appearAnimation.getValue() ? 9.0F : 1000.0F);
        if (targets.isEmpty() && this.presence.isEmpty()) {
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
        float spin = this.spinSpeed.getValue() == 0.0F ? 0.0F
                : (now % 360000L) / 1000.0F * this.spinSpeed.getValue() % 360.0F;

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

        for (int index = 0; index < targets.size(); index++) {
            EntityPlayer player = targets.get(index);
            float presence = this.presence.getOrDefault(player.getEntityId(), 1.0F);
            if (presence <= 0.02F) {
                continue;
            }

            double x = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTicks - viewX;
            double y = player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTicks - viewY;
            double z = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTicks - viewZ;

            int argb = this.colorFor(player, index * 120L);
            float r = ((argb >> 16) & 0xFF) / 255.0F;
            float g = ((argb >> 8) & 0xFF) / 255.0F;
            float b = (argb & 0xFF) / 255.0F;
            float a = ((argb >>> 24) & 0xFF) / 255.0F * presence;

            // A hit whitens the cylinder briefly, the way vanilla flashes the model red.
            if (this.hurtFlash.getValue() && player.hurtTime > 0) {
                float flash = player.hurtTime / 10.0F;
                r = r + (1.0F - r) * flash;
                g = g + (1.0F - g) * flash * 0.4F;
                b = b + (1.0F - b) * flash * 0.4F;
            }

            float cylinderRadius = player.width * 0.5F + this.radius.getValue() - 0.3F;
            if (cylinderRadius < 0.1F) {
                cylinderRadius = 0.1F;
            }
            // Grow out of the ground as the cylinder appears.
            float cylinderHeight = player.height * this.height.getValue() * (0.35F + 0.65F * presence);

            GlStateManager.pushMatrix();
            GlStateManager.translate(x, y, z);

            if (drawFill) {
                // Side wall, bright at the base and transparent towards the top.
                GL11.glBegin(GL11.GL_QUAD_STRIP);
                for (int i = 0; i <= segments; i++) {
                    float cos = circle[i * 2] * cylinderRadius;
                    float sin = circle[i * 2 + 1] * cylinderRadius;
                    GL11.glColor4f(r, g, b, a * 0.55F);
                    GL11.glVertex3f(cos, 0.0F, sin);
                    GL11.glColor4f(r, g, b, this.fade.getValue() ? 0.0F : a * 0.55F);
                    GL11.glVertex3f(cos, cylinderHeight, sin);
                }
                GL11.glEnd();
            }

            if (drawOutline) {
                if (this.baseRing.getValue()) {
                    GL11.glColor4f(r, g, b, a);
                    this.ring(circle, segments, cylinderRadius, 0.0F);
                }
                if (this.topRing.getValue()) {
                    float bobOffset = this.bob.getValue()
                            ? (float) Math.sin((now % 4000L) / 4000.0 * Math.PI * 2.0) * cylinderHeight * 0.04F
                            : 0.0F;
                    GlStateManager.pushMatrix();
                    GlStateManager.translate(0.0F, cylinderHeight + bobOffset, 0.0F);
                    GlStateManager.rotate(spin, 0.0F, 1.0F, 0.0F);
                    GL11.glColor4f(r, g, b, a * 0.8F);
                    this.ring(circle, segments, cylinderRadius * 0.92F, 0.0F);
                    GlStateManager.popMatrix();
                }
            }

            GlStateManager.popMatrix();
        }

        GlStateManager.shadeModel(GL11.GL_FLAT);
        if (this.smoothLines.getValue()) {
            GL11.glDisable(GL11.GL_LINE_SMOOTH);
        }
        GL11.glLineWidth(1.0F);
        RenderUtil.disableRenderState();
        RenderUtil.resetColor();
    }

    private void ring(float[] circle, int segments, float radius, float y) {
        GL11.glBegin(GL11.GL_LINE_STRIP);
        for (int i = 0; i <= segments; i++) {
            GL11.glVertex3f(circle[i * 2] * radius, y, circle[i * 2 + 1] * radius);
        }
        GL11.glEnd();
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.style.getModeString()};
    }
}
