package myau.ui.impl.hud;

import myau.Myau;
import myau.module.Module;
import myau.module.modules.ClickGUIModule;
import myau.module.modules.HudEditor;
import myau.ui.DraggableHud;
import myau.util.RenderUtil;
import myau.util.shader.RoundedUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Lunar-style HUD editor: every {@link DraggableHud} module is outlined and can be dragged around,
 * with edge and centre snapping plus alignment guides.
 * <p>
 * The elements keep drawing themselves through their normal render path, so what you drag is
 * exactly what you get; this screen only paints outlines on top and writes positions back.
 */
public class HudEditorScreen extends GuiScreen {
    private static final int SNAP_DISTANCE = 6;
    private static final int GRABBED_NONE = -1;

    private final List<DraggableHud> elements = new ArrayList<>();
    private int grabbed = GRABBED_NONE;
    private float grabOffsetX;
    private float grabOffsetY;
    /** Guides to draw this frame: {x1, y1, x2, y2} in screen pixels. */
    private final List<float[]> guides = new ArrayList<>();

    private void refresh() {
        this.elements.clear();
        if (Myau.moduleManager == null) {
            return;
        }
        for (Module module : Myau.moduleManager.allModules()) {
            if (module instanceof DraggableHud) {
                this.elements.add((DraggableHud) module);
            }
        }
    }

    @Override
    public void initGui() {
        super.initGui();
        this.refresh();
        this.grabbed = GRABBED_NONE;
    }

    private HudEditor module() {
        Module module = Myau.moduleManager == null ? null : Myau.moduleManager.getModule(HudEditor.class);
        return module instanceof HudEditor ? (HudEditor) module : null;
    }

    private static boolean inside(float[] bounds, int mouseX, int mouseY) {
        return bounds != null
                && mouseX >= bounds[0] && mouseX <= bounds[0] + bounds[2]
                && mouseY >= bounds[1] && mouseY <= bounds[1] + bounds[3];
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        HudEditor editor = this.module();
        ScaledResolution sr = new ScaledResolution(this.mc);

        if (editor == null || editor.dimBackground.getValue()) {
            drawRect(0, 0, sr.getScaledWidth(), sr.getScaledHeight(), new Color(0, 0, 0, 110).getRGB());
        }

        // Dragging is resolved before the outlines so the outline follows the cursor in the same frame.
        if (this.grabbed != GRABBED_NONE && this.grabbed < this.elements.size()) {
            this.dragTo(this.elements.get(this.grabbed), mouseX, mouseY, sr,
                    editor == null || editor.snapping.getValue());
        } else {
            this.guides.clear();
        }

        RenderUtil.enableRenderState();
        for (int i = 0; i < this.elements.size(); i++) {
            DraggableHud element = this.elements.get(i);
            float[] bounds = element.getHudBounds();
            if (bounds == null || bounds[2] <= 0.0F || bounds[3] <= 0.0F) {
                continue;
            }
            boolean enabled = element.isHudEnabled();
            boolean hovered = i == this.grabbed || (this.grabbed == GRABBED_NONE && inside(bounds, mouseX, mouseY));

            int outline = !enabled ? new Color(120, 120, 130, 150).getRGB()
                    : hovered ? new Color(120, 200, 255, 230).getRGB()
                    : new Color(255, 255, 255, 120).getRGB();
            int fill = hovered ? new Color(120, 200, 255, 30).getRGB() : new Color(255, 255, 255, 12).getRGB();

            RoundedUtils.drawRound(bounds[0], bounds[1], bounds[2], bounds[3], 3.0F, new Color(fill, true));
            this.outline(bounds, outline);

            String label = element.getHudName() + (enabled ? "" : " (off)");
            float labelY = bounds[1] - this.mc.fontRendererObj.FONT_HEIGHT - 2.0F;
            if (labelY < 1.0F) {
                labelY = bounds[1] + bounds[3] + 2.0F;
            }
            this.mc.fontRendererObj.drawStringWithShadow(label, bounds[0], labelY, enabled ? 0xFFFFFFFF : 0xFF9A9AA5);
        }

        for (float[] guide : this.guides) {
            RoundedUtils.drawRound(guide[0], guide[1], Math.max(0.5F, guide[2] - guide[0]),
                    Math.max(0.5F, guide[3] - guide[1]), 0.0F, new Color(120, 200, 255, 200));
        }
        RenderUtil.disableRenderState();

        String help = "Drag to move · Right-click to reset · R resets all · ESC to close";
        this.mc.fontRendererObj.drawStringWithShadow(help,
                sr.getScaledWidth() / 2.0F - this.mc.fontRendererObj.getStringWidth(help) / 2.0F,
                sr.getScaledHeight() - 12.0F, 0xFFBBBBBB);
        RenderUtil.resetColor();
    }

    private void outline(float[] bounds, int color) {
        Color awt = new Color(color, true);
        float thickness = 0.75F;
        RoundedUtils.drawRound(bounds[0], bounds[1], bounds[2], thickness, 0.0F, awt);
        RoundedUtils.drawRound(bounds[0], bounds[1] + bounds[3] - thickness, bounds[2], thickness, 0.0F, awt);
        RoundedUtils.drawRound(bounds[0], bounds[1], thickness, bounds[3], 0.0F, awt);
        RoundedUtils.drawRound(bounds[0] + bounds[2] - thickness, bounds[1], thickness, bounds[3], 0.0F, awt);
    }

    /**
     * Moves the grabbed element under the cursor, snapping its edges and centre to the screen's
     * edges and centre lines, and to the other elements' edges.
     */
    private void dragTo(DraggableHud element, int mouseX, int mouseY, ScaledResolution sr, boolean snap) {
        this.guides.clear();
        float[] bounds = element.getHudBounds();
        if (bounds == null) {
            return;
        }
        float x = mouseX - this.grabOffsetX;
        float y = mouseY - this.grabOffsetY;
        float width = bounds[2];
        float height = bounds[3];

        if (snap) {
            float screenWidth = sr.getScaledWidth();
            float screenHeight = sr.getScaledHeight();

            Float snappedX = this.snap(x, width, new float[]{0.0F, screenWidth / 2.0F - width / 2.0F, screenWidth - width});
            if (snappedX != null) {
                x = snappedX;
            }
            Float snappedY = this.snap(y, height, new float[]{0.0F, screenHeight / 2.0F - height / 2.0F, screenHeight - height});
            if (snappedY != null) {
                y = snappedY;
            }

            // Align with the other elements' left/top edges.
            for (DraggableHud other : this.elements) {
                if (other == element) {
                    continue;
                }
                float[] otherBounds = other.getHudBounds();
                if (otherBounds == null) {
                    continue;
                }
                if (Math.abs(otherBounds[0] - x) <= SNAP_DISTANCE) {
                    x = otherBounds[0];
                    this.guides.add(new float[]{x, 0.0F, x + 0.5F, screenHeight});
                }
                if (Math.abs(otherBounds[1] - y) <= SNAP_DISTANCE) {
                    y = otherBounds[1];
                    this.guides.add(new float[]{0.0F, y, screenWidth, y + 0.5F});
                }
            }
            if (Math.abs(x + width / 2.0F - screenWidth / 2.0F) <= SNAP_DISTANCE) {
                this.guides.add(new float[]{screenWidth / 2.0F, 0.0F, screenWidth / 2.0F + 0.5F, screenHeight});
            }
            if (Math.abs(y + height / 2.0F - screenHeight / 2.0F) <= SNAP_DISTANCE) {
                this.guides.add(new float[]{0.0F, screenHeight / 2.0F, screenWidth, screenHeight / 2.0F + 0.5F});
            }
        }

        // Never let an element be dragged fully off screen.
        x = Math.max(-width + 8.0F, Math.min(sr.getScaledWidth() - 8.0F, x));
        y = Math.max(0.0F, Math.min(sr.getScaledHeight() - 8.0F, y));
        element.setHudPosition(x, y);
    }

    private Float snap(float value, float size, float[] candidates) {
        for (float candidate : candidates) {
            if (Math.abs(value - candidate) <= SNAP_DISTANCE) {
                return candidate;
            }
        }
        return null;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        // Topmost first, so overlapping elements pick the one drawn last.
        for (int i = this.elements.size() - 1; i >= 0; i--) {
            DraggableHud element = this.elements.get(i);
            float[] bounds = element.getHudBounds();
            if (!inside(bounds, mouseX, mouseY)) {
                continue;
            }
            if (mouseButton == 0) {
                this.grabbed = i;
                this.grabOffsetX = mouseX - bounds[0];
                this.grabOffsetY = mouseY - bounds[1];
            } else if (mouseButton == 1) {
                element.resetHudPosition();
            }
            return;
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (state == 0) {
            this.grabbed = GRABBED_NONE;
            this.guides.clear();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) {
            this.mc.displayGuiScreen(null);
            return;
        }
        if (keyCode == 19) { // R
            for (DraggableHud element : this.elements) {
                element.resetHudPosition();
            }
            return;
        }
        // Don't let the ClickGUI bind fall through and open two screens at once.
        Module clickGui = Myau.moduleManager == null ? null : Myau.moduleManager.getModule(ClickGUIModule.class);
        if (clickGui != null && clickGui.getKey() == keyCode) {
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void onGuiClosed() {
        this.grabbed = GRABBED_NONE;
        this.guides.clear();
        HudEditor editor = this.module();
        if (editor != null && editor.isEnabled()) {
            editor.setEnabled(false);
        }
        // Persist the new layout immediately, so a crash can't lose it.
        myau.config.Config.autoSave();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
