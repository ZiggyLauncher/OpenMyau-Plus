package myau.module.modules;

import myau.event.EventTarget;
import myau.events.Render2DEvent;
import myau.events.Render3DEvent;
import myau.mixin.IAccessorRenderManager;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ColorProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.GLU;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Box ESP in the style of the adin client.
 * <p>
 * <b>3D</b> draws the hitbox in the world, <b>2D</b> projects it and draws a flat rectangle on
 * screen, either whole or as corner brackets. Either can carry a fill, a black outline under the
 * stroke so the box stays readable against any background, and a health bar down one side.
 * <p>
 * Line width falls off with distance rather than staying constant: past ten blocks it scales by
 * {@code 10 / distance}, which keeps a far-off box from turning into a solid blob of colour.
 * <p>
 * The projection is captured during the world pass, where the 3D matrices are still current, and
 * consumed during the 2D pass - reading the matrices from the 2D pass would project against the
 * orthographic ones and put every box in the wrong place.
 */
public class ESP extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final int MODE_3D = 0;
    private static final int MODE_2D = 1;
    private static final int TYPE_FULL = 0;
    private static final int TYPE_CORNERED = 1;
    private static final int SIDE_LEFT = 0;
    private static final int SIDE_RIGHT = 1;
    private static final int SIDE_TOP = 2;
    private static final int SIDE_BOTTOM = 3;

    private static final float WORLD_LINE_WIDTH = 2.0F;
    private static final float SCREEN_LINE_WIDTH = 1.0F;
    private static final float SCREEN_OUTLINE_WIDTH = 2.5F;
    private static final int OUTLINE_COLOR = 0xFF000000;
    /** Beyond this the stroke thins with distance, so a far box does not read as a filled blob. */
    private static final double FULL_WIDTH_DISTANCE = 10.0;
    private static final float HEALTH_BAR_THICKNESS = 2.0F;
    private static final float HEALTH_BAR_GAP = 2.0F;

    public final ModeProperty mode = new ModeProperty("mode", MODE_3D, new String[]{"3D", "2D"});
    public final IntProperty distance = new IntProperty("distance", 64, 8, 256);
    public final BooleanProperty self = new BooleanProperty("self", false);
    public final BooleanProperty players = new BooleanProperty("players", true);
    public final BooleanProperty entities = new BooleanProperty("entities", false);
    public final ModeProperty type = new ModeProperty("type", TYPE_FULL, new String[]{"Full", "Cornered"},
            () -> this.mode.getValue() == MODE_2D);
    public final ColorProperty color = new ColorProperty("color", 0xB8DDB0);
    public final BooleanProperty box = new BooleanProperty("box", true);
    public final BooleanProperty fill = new BooleanProperty("fill", true);
    public final BooleanProperty outline = new BooleanProperty("outline", true);
    public final BooleanProperty health = new BooleanProperty("health", false);
    public final IntProperty fillOpacity = new IntProperty("fillOpacity", 25, 0, 100, this.fill::getValue);
    public final ModeProperty healthPosition = new ModeProperty("healthPosition", SIDE_LEFT,
            new String[]{"Left", "Right", "Top", "Bottom"},
            () -> this.mode.getValue() == MODE_2D && this.health.getValue());

    /** One projected target, captured in the world pass for the 2D pass to draw. */
    private static final class Projected {
        float minX;
        float minY;
        float maxX;
        float maxY;
        float healthFraction;
        boolean living;
    }

    /**
     * Reused across frames. These are pooled rather than allocated per target because the screen
     * pass runs every frame for every visible entity, and the garbage from doing otherwise is
     * paid back in collections mid-fight.
     */
    private final List<Projected> projected = new ArrayList<Projected>();
    private int projectedCount;

    private final FloatBuffer modelView = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private final FloatBuffer screen = BufferUtils.createFloatBuffer(4);

    /**
     * Snapshots the matrices needed to project world points, once for the whole frame.
     * <p>
     * Every {@code glGet} forces the driver to finish what it has queued before it can answer, so
     * reading them per corner - twenty-four times per entity - stalls the pipeline over and over.
     * Read once, they cost nothing measurable.
     */
    private void captureMatrices() {
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, this.modelView);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, this.projection);
        GL11.glGetInteger(GL11.GL_VIEWPORT, this.viewport);
    }

    /** A pooled slot, so a steady frame allocates nothing. */
    private Projected next() {
        if (this.projectedCount < this.projected.size()) {
            return this.projected.get(this.projectedCount);
        }
        Projected created = new Projected();
        this.projected.add(created);
        return created;
    }

    /**
     * Projects the eight corners of {@code bounds} and returns their screen-space extent, or false
     * when the box is entirely behind the camera.
     */
    private boolean project(AxisAlignedBB bounds, double viewX, double viewY, double viewZ,
                            float scaleFactor, Projected into) {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        boolean any = false;

        for (int corner = 0; corner < 8; corner++) {
            double x = ((corner & 1) == 0 ? bounds.minX : bounds.maxX) - viewX;
            double y = ((corner & 2) == 0 ? bounds.minY : bounds.maxY) - viewY;
            double z = ((corner & 4) == 0 ? bounds.minZ : bounds.maxZ) - viewZ;

            this.screen.clear();
            if (!GLU.gluProject((float) x, (float) y, (float) z,
                    this.modelView, this.projection, this.viewport, this.screen)) {
                continue;
            }
            float depth = this.screen.get(2);
            if (depth < 0.0F || depth >= 1.0F) {
                continue;
            }
            float screenX = this.screen.get(0) / scaleFactor;
            float screenY = (mc.displayHeight - this.screen.get(1)) / scaleFactor;
            minX = Math.min(minX, screenX);
            minY = Math.min(minY, screenY);
            maxX = Math.max(maxX, screenX);
            maxY = Math.max(maxY, screenY);
            any = true;
        }
        if (!any) {
            return false;
        }
        into.minX = minX;
        into.minY = minY;
        into.maxX = maxX;
        into.maxY = maxY;
        return true;
    }

    public ESP() {
        super("ESP", false, false, "Box ESP, in the world or projected flat on screen");
    }

    @Override
    public void onDisabled() {
        this.projected.clear();
        this.projectedCount = 0;
    }

    private int rgb() {
        Integer value = this.color.getValue();
        return value == null ? 0xB8DDB0 : value & 0xFFFFFF;
    }

    private boolean targeted(Entity entity) {
        if (entity == null || entity.isDead) {
            return false;
        }
        if (entity == mc.thePlayer || entity == mc.getRenderViewEntity()) {
            return this.self.getValue() && mc.gameSettings.thirdPersonView != 0;
        }
        if (entity instanceof EntityPlayer) {
            return this.players.getValue();
        }
        return this.entities.getValue() && entity instanceof EntityLivingBase;
    }

    /** adin's fit: a touch wider than the hitbox at the sides, a touch taller at the top. */
    private static AxisAlignedBB fitted(Entity entity, float partialTicks) {
        double x = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks;
        double y = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks;
        double z = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks;
        AxisAlignedBB bounds = entity.getEntityBoundingBox()
                .offset(x - entity.posX, y - entity.posY, z - entity.posZ);
        if (!(entity instanceof EntityPlayer)) {
            return bounds;
        }
        return new AxisAlignedBB(bounds.minX - 0.12, bounds.minY, bounds.minZ - 0.12,
                bounds.maxX + 0.12, bounds.maxY + 0.06, bounds.maxZ + 0.12);
    }

    /** Stroke scale for a target this far away, so distant boxes stay thin. */
    private static float perspective(double distanceSq) {
        if (distanceSq <= FULL_WIDTH_DISTANCE * FULL_WIDTH_DISTANCE) {
            return 1.0F;
        }
        return (float) (FULL_WIDTH_DISTANCE / Math.sqrt(distanceSq));
    }

    private static float healthFraction(Entity entity) {
        if (!(entity instanceof EntityLivingBase)) {
            return 0.0F;
        }
        EntityLivingBase living = (EntityLivingBase) entity;
        float max = living.getMaxHealth();
        return max <= 0.0F ? 0.0F : Math.max(0.0F, Math.min(1.0F, living.getHealth() / max));
    }

    // ---------------------------------------------------------------- world pass

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        // The pool keeps its objects; only the live count resets.
        this.projectedCount = 0;
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) {
            return;
        }
        boolean flat = this.mode.getValue() == MODE_2D;
        if (!flat && !this.box.getValue() && !this.fill.getValue()) {
            return;
        }

        float partialTicks = event.getPartialTicks();
        double range = this.distance.getValue();
        double rangeSq = range * range;
        int scaleFactor = new ScaledResolution(mc).getScaleFactor();

        IAccessorRenderManager renderManager = (IAccessorRenderManager) mc.getRenderManager();
        double viewX = renderManager.getRenderPosX();
        double viewY = renderManager.getRenderPosY();
        double viewZ = renderManager.getRenderPosZ();

        int rgb = this.rgb();
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        int fillAlpha = Math.round(255 * Math.max(0, Math.min(100, this.fillOpacity.getValue())) / 100.0F);

        if (flat) {
            this.captureMatrices();
        } else {
            RenderUtil.enableRenderState();
        }

        for (int i = 0; i < mc.theWorld.loadedEntityList.size(); i++) {
            Entity entity = mc.theWorld.loadedEntityList.get(i);
            // Distance first: it is three subtractions, while targeted() walks the filters.
            double distanceSq = player.getDistanceSqToEntity(entity);
            if (distanceSq > rangeSq || !this.targeted(entity)) {
                continue;
            }

            AxisAlignedBB bounds = fitted(entity, partialTicks);
            if (flat) {
                // Captured here, where the 3D matrices are still bound; drawn in the 2D pass.
                Projected shot = this.next();
                if (!this.project(bounds, viewX, viewY, viewZ, scaleFactor, shot)) {
                    continue;
                }
                shot.healthFraction = healthFraction(entity);
                shot.living = entity instanceof EntityLivingBase;
                this.projectedCount++;
                continue;
            }

            AxisAlignedBB drawn = new AxisAlignedBB(
                    bounds.minX - viewX, bounds.minY - viewY, bounds.minZ - viewZ,
                    bounds.maxX - viewX, bounds.maxY - viewY, bounds.maxZ - viewZ);
            float scale = perspective(distanceSq);

            if (this.fill.getValue() && fillAlpha > 0) {
                RenderUtil.drawFilledBox(drawn, red, green, blue);
            }
            if (this.box.getValue()) {
                if (this.outline.getValue()) {
                    RenderUtil.drawBoundingBox(drawn, 0, 0, 0, 255,
                            Math.max(1.0F, WORLD_LINE_WIDTH * 2.0F * scale));
                }
                RenderUtil.drawBoundingBox(drawn, red, green, blue, 255,
                        Math.max(0.5F, WORLD_LINE_WIDTH * scale));
            }
        }

        if (!flat) {
            RenderUtil.disableRenderState();
            RenderUtil.resetColor();
        }
    }

    // ---------------------------------------------------------------- screen pass

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (this.mode.getValue() != MODE_2D || this.projectedCount == 0) {
            return;
        }
        int rgb = this.rgb();
        int stroke = 0xFF000000 | rgb;
        int fillAlpha = Math.round(255 * Math.max(0, Math.min(100, this.fillOpacity.getValue())) / 100.0F);
        int fillColor = (fillAlpha << 24) | rgb;
        boolean cornered = this.type.getValue() == TYPE_CORNERED;

        for (int i = 0; i < this.projectedCount; i++) {
            Projected shot = this.projected.get(i);
            float width = shot.maxX - shot.minX;
            float height = shot.maxY - shot.minY;
            if (width <= 0.0F || height <= 0.0F) {
                continue;
            }

            if (this.fill.getValue() && fillAlpha > 0) {
                RenderUtil.drawRect(shot.minX, shot.minY, shot.maxX, shot.maxY, fillColor);
            }
            if (this.box.getValue()) {
                if (this.outline.getValue()) {
                    this.drawFrame(shot, cornered, SCREEN_OUTLINE_WIDTH, OUTLINE_COLOR);
                }
                this.drawFrame(shot, cornered, SCREEN_LINE_WIDTH, stroke);
            }
            if (this.health.getValue() && shot.living) {
                this.drawHealth(shot, stroke);
            }
        }
        RenderUtil.resetColor();
    }

    /** The box itself: four full edges, or just the quarter-length corner brackets. */
    private void drawFrame(Projected shot, boolean cornered, float thickness, int color) {
        float width = shot.maxX - shot.minX;
        float height = shot.maxY - shot.minY;
        if (!cornered) {
            RenderUtil.drawRect(shot.minX, shot.minY, shot.maxX, shot.minY + thickness, color);
            RenderUtil.drawRect(shot.minX, shot.maxY - thickness, shot.maxX, shot.maxY, color);
            RenderUtil.drawRect(shot.minX, shot.minY, shot.minX + thickness, shot.maxY, color);
            RenderUtil.drawRect(shot.maxX - thickness, shot.minY, shot.maxX, shot.maxY, color);
            return;
        }
        float armX = Math.max(thickness, width * 0.25F);
        float armY = Math.max(thickness, height * 0.25F);

        RenderUtil.drawRect(shot.minX, shot.minY, shot.minX + armX, shot.minY + thickness, color);
        RenderUtil.drawRect(shot.minX, shot.minY, shot.minX + thickness, shot.minY + armY, color);

        RenderUtil.drawRect(shot.maxX - armX, shot.minY, shot.maxX, shot.minY + thickness, color);
        RenderUtil.drawRect(shot.maxX - thickness, shot.minY, shot.maxX, shot.minY + armY, color);

        RenderUtil.drawRect(shot.minX, shot.maxY - thickness, shot.minX + armX, shot.maxY, color);
        RenderUtil.drawRect(shot.minX, shot.maxY - armY, shot.minX + thickness, shot.maxY, color);

        RenderUtil.drawRect(shot.maxX - armX, shot.maxY - thickness, shot.maxX, shot.maxY, color);
        RenderUtil.drawRect(shot.maxX - thickness, shot.maxY - armY, shot.maxX, shot.maxY, color);
    }

    /** A bar on the chosen side, filling from the far end towards full health. */
    private void drawHealth(Projected shot, int color) {
        float clearance = this.box.getValue()
                ? (this.outline.getValue() ? SCREEN_OUTLINE_WIDTH : SCREEN_LINE_WIDTH) * 0.5F
                : 0.0F;
        float gap = HEALTH_BAR_GAP + clearance;
        float fraction = shot.healthFraction;
        int background = 0x90000000;

        switch (this.healthPosition.getValue()) {
            case SIDE_RIGHT: {
                float x = shot.maxX + gap;
                RenderUtil.drawRect(x, shot.minY, x + HEALTH_BAR_THICKNESS, shot.maxY, background);
                float top = shot.maxY - (shot.maxY - shot.minY) * fraction;
                RenderUtil.drawRect(x, top, x + HEALTH_BAR_THICKNESS, shot.maxY, color);
                break;
            }
            case SIDE_TOP: {
                float y = shot.minY - gap - HEALTH_BAR_THICKNESS;
                RenderUtil.drawRect(shot.minX, y, shot.maxX, y + HEALTH_BAR_THICKNESS, background);
                RenderUtil.drawRect(shot.minX, y, shot.minX + (shot.maxX - shot.minX) * fraction,
                        y + HEALTH_BAR_THICKNESS, color);
                break;
            }
            case SIDE_BOTTOM: {
                float y = shot.maxY + gap;
                RenderUtil.drawRect(shot.minX, y, shot.maxX, y + HEALTH_BAR_THICKNESS, background);
                RenderUtil.drawRect(shot.minX, y, shot.minX + (shot.maxX - shot.minX) * fraction,
                        y + HEALTH_BAR_THICKNESS, color);
                break;
            }
            case SIDE_LEFT:
            default: {
                float x = shot.minX - gap - HEALTH_BAR_THICKNESS;
                RenderUtil.drawRect(x, shot.minY, x + HEALTH_BAR_THICKNESS, shot.maxY, background);
                float top = shot.maxY - (shot.maxY - shot.minY) * fraction;
                RenderUtil.drawRect(x, top, x + HEALTH_BAR_THICKNESS, shot.maxY, color);
                break;
            }
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }
}
