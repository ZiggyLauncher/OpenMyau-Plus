package myau.module.modules;

import myau.event.EventTarget;
import myau.events.Render2DEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ColorProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.ui.DraggableHud;
import myau.util.KeyBindUtil;
import myau.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.settings.KeyBinding;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * WASD / mouse / space overlay that lights up with the client accent colour, with live CPS on the
 * mouse keys. Eight rounded rects and a few strings per frame; the press animation is a single
 * float per key, so this is effectively free.
 */
public class Keystrokes extends Module implements DraggableHud {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int KEY_W = 0;
    private static final int KEY_A = 1;
    private static final int KEY_S = 2;
    private static final int KEY_D = 3;
    private static final int KEY_LMB = 4;
    private static final int KEY_RMB = 5;
    private static final int KEY_SPACE = 6;
    private static final int KEY_SNEAK = 7;
    private static final int KEY_COUNT = 8;

    private static final int ANCHOR_LEFT = 0;
    private static final int ANCHOR_RIGHT = 1;

    public final ModeProperty anchor = new ModeProperty("Anchor", ANCHOR_LEFT, new String[]{"Bottom-Left", "Bottom-Right"});
    public final ModeProperty colorMode = new ModeProperty("Color-Mode", HudStyle.MODE_HUD, HudStyle.COLOR_MODES);
    public final ColorProperty color = new ColorProperty("Color", 0x4FACFE, () -> this.colorMode.getValue() == HudStyle.MODE_CUSTOM);
    public final IntProperty alpha = new IntProperty("Alpha", 255, 40, 255);
    public final FloatProperty scale = new FloatProperty("Scale", 1.0F, 0.5F, 2.0F);
    public final IntProperty offsetX = new IntProperty("Offset-X", 6, -2000, 2000);
    public final IntProperty offsetY = new IntProperty("Offset-Y", 60, -2000, 2000);
    public final IntProperty size = new IntProperty("Key-Size", 20, 12, 32);
    public final IntProperty gap = new IntProperty("Gap", 2, 0, 8);
    public final FloatProperty radius = new FloatProperty("Radius", 4.0F, 0.0F, 10.0F);
    public final IntProperty idleAlpha = new IntProperty("Idle-Alpha", 80, 0, 255);
    public final FloatProperty animationSpeed = new FloatProperty("Animation-Speed", 14.0F, 2.0F, 30.0F);
    public final BooleanProperty showMouse = new BooleanProperty("Mouse-Keys", true);
    public final BooleanProperty showCps = new BooleanProperty("Show-CPS", true, showMouse::getValue);
    public final BooleanProperty showSpace = new BooleanProperty("Space", true);
    public final BooleanProperty showSneak = new BooleanProperty("Sneak", false);
    /** Count clicks the client sends itself (AutoClicker, ClickAssist) as well as physical presses. */
    public final BooleanProperty countInjected = new BooleanProperty("Count-Injected", true);

    private final float[] press = new float[KEY_COUNT];
    /** Decaying flash per key, so every registered click is visible even while the button is held. */
    private final float[] pulse = new float[KEY_COUNT];
    private final boolean[] wasDown = new boolean[KEY_COUNT];
    private final Deque<Long> leftClicks = new ArrayDeque<>();
    private final Deque<Long> rightClicks = new ArrayDeque<>();
    private final long[] lastFrame = new long[1];
    /** Clicks the client injected since the last frame, picked up by the render pass. */
    private final java.util.concurrent.atomic.AtomicInteger injectedLeft = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger injectedRight = new java.util.concurrent.atomic.AtomicInteger();

    public Keystrokes() {
        super("Keystrokes", false, false, "WASD, mouse and space overlay with CPS");
    }

    @Override
    public void onEnabled() {
        java.util.Arrays.fill(this.press, 0.0F);
        java.util.Arrays.fill(this.pulse, 0.0F);
        java.util.Arrays.fill(this.wasDown, false);
        this.leftClicks.clear();
        this.rightClicks.clear();
        this.injectedLeft.set(0);
        this.injectedRight.set(0);
        this.lastFrame[0] = 0L;
    }

    /**
     * Called by the modules that send clicks of their own (AutoClicker, ClickAssist) so the
     * overlay shows the clicks that actually reach the server, not just the ones your finger
     * makes. Counted on the next frame, so this is safe to call from any thread.
     */
    public static void notifyInjectedClick(boolean left) {
        if (myau.Myau.moduleManager == null) {
            return;
        }
        Keystrokes keystrokes = (Keystrokes) myau.Myau.moduleManager.modules.get(Keystrokes.class);
        if (keystrokes == null || !keystrokes.isEnabled() || !keystrokes.countInjected.getValue()) {
            return;
        }
        (left ? keystrokes.injectedLeft : keystrokes.injectedRight).incrementAndGet();
    }

    @Override
    public void onDisabled() {
        this.leftClicks.clear();
        this.rightClicks.clear();
    }

    /** True while the bind is physically held, GUI screens included (so the overlay never sticks). */
    private static boolean down(KeyBinding binding) {
        int code = binding.getKeyCode();
        return code != 0 && KeyBindUtil.isKeyDown(code);
    }

    /** Unscaled {width, height} of the whole block for the current settings. */
    private float[] panelSize() {
        float keySize = this.size.getValue();
        float gap = this.gap.getValue();
        float height = keySize * 2.0F + gap;
        if (this.showMouse.getValue()) {
            height += keySize + gap;
        }
        if (this.showSpace.getValue()) {
            height += keySize * 0.7F + gap;
        }
        if (this.showSneak.getValue()) {
            height += keySize * 0.7F + gap;
        }
        return new float[]{keySize * 3.0F + gap * 2.0F, height};
    }

    /** Top-left corner in scaled screen pixels; offsets measure inward from the anchored corner. */
    private float[] position(ScaledResolution sr, float width, float height) {
        float scale = this.scale.getValue();
        float x = this.anchor.getValue() == ANCHOR_RIGHT
                ? sr.getScaledWidth() - width * scale - this.offsetX.getValue()
                : this.offsetX.getValue();
        return new float[]{x, sr.getScaledHeight() - height * scale - this.offsetY.getValue()};
    }

    @Override
    public String getHudName() {
        return "Keystrokes";
    }

    @Override
    public float[] getHudBounds() {
        float[] size = this.panelSize();
        float scale = this.scale.getValue();
        float[] pos = this.position(new ScaledResolution(mc), size[0], size[1]);
        return new float[]{pos[0], pos[1], size[0] * scale, size[1] * scale};
    }

    @Override
    public void setHudPosition(float x, float y) {
        float[] bounds = this.getHudBounds();
        ScaledResolution sr = new ScaledResolution(mc);
        this.offsetX.setValue(Math.round(this.anchor.getValue() == ANCHOR_RIGHT
                ? sr.getScaledWidth() - bounds[2] - x
                : x));
        this.offsetY.setValue(Math.round(sr.getScaledHeight() - bounds[3] - y));
    }

    @Override
    public void resetHudPosition() {
        this.offsetX.setValue(6);
        this.offsetY.setValue(60);
    }

    @Override
    public boolean isHudEnabled() {
        return this.isEnabled();
    }

    private static int cps(Deque<Long> clicks, long now) {
        while (!clicks.isEmpty() && now - clicks.peekFirst() > 1000L) {
            clicks.pollFirst();
        }
        return clicks.size();
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (mc.thePlayer == null || mc.gameSettings.showDebugInfo) {
            return;
        }

        long now = System.currentTimeMillis();
        float delta = HudStyle.delta(this.lastFrame);
        float speed = this.animationSpeed.getValue();

        boolean[] held = new boolean[KEY_COUNT];
        held[KEY_W] = down(mc.gameSettings.keyBindForward);
        held[KEY_A] = down(mc.gameSettings.keyBindLeft);
        held[KEY_S] = down(mc.gameSettings.keyBindBack);
        held[KEY_D] = down(mc.gameSettings.keyBindRight);
        held[KEY_LMB] = down(mc.gameSettings.keyBindAttack);
        held[KEY_RMB] = down(mc.gameSettings.keyBindUseItem);
        held[KEY_SPACE] = down(mc.gameSettings.keyBindJump);
        held[KEY_SNEAK] = down(mc.gameSettings.keyBindSneak);

        // Clicks the client injected since the last frame count exactly like physical ones.
        int injectedLeft = this.injectedLeft.getAndSet(0);
        int injectedRight = this.injectedRight.getAndSet(0);
        for (int i = 0; i < injectedLeft; i++) {
            this.leftClicks.addLast(now);
        }
        for (int i = 0; i < injectedRight; i++) {
            this.rightClicks.addLast(now);
        }
        if (injectedLeft > 0) {
            this.pulse[KEY_LMB] = 1.0F;
        }
        if (injectedRight > 0) {
            this.pulse[KEY_RMB] = 1.0F;
        }

        for (int i = 0; i < KEY_COUNT; i++) {
            if (held[i] && !this.wasDown[i]) {
                if (i == KEY_LMB) {
                    this.leftClicks.addLast(now);
                    this.pulse[i] = 1.0F;
                } else if (i == KEY_RMB) {
                    this.rightClicks.addLast(now);
                    this.pulse[i] = 1.0F;
                }
            }
            this.wasDown[i] = held[i];
            this.press[i] = HudStyle.approach(this.press[i], held[i] ? 1.0F : 0.0F, speed, delta);
            this.pulse[i] = HudStyle.approach(this.pulse[i], 0.0F, speed * 1.6F, delta);
        }
        // Bound the click history even if the module renders for a long time without clicks.
        cps(this.leftClicks, now);
        cps(this.rightClicks, now);

        float[] size = this.panelSize();
        float keySize = this.size.getValue();
        float gap = this.gap.getValue();
        float rowWidth = size[0];
        float height = size[1];

        float scale = this.scale.getValue();
        ScaledResolution sr = new ScaledResolution(mc);
        float[] pos = this.position(sr, rowWidth, height);
        float x = pos[0];
        float y = pos[1];

        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0.0F);
        GlStateManager.scale(scale, scale, 1.0F);

        float cursorY = 0.0F;
        this.key("W", KEY_W, keySize + gap, cursorY, keySize, keySize, null);
        cursorY += keySize + gap;
        this.key("A", KEY_A, 0.0F, cursorY, keySize, keySize, null);
        this.key("S", KEY_S, keySize + gap, cursorY, keySize, keySize, null);
        this.key("D", KEY_D, (keySize + gap) * 2.0F, cursorY, keySize, keySize, null);
        cursorY += keySize + gap;

        if (this.showMouse.getValue()) {
            float half = (rowWidth - gap) / 2.0F;
            String left = this.showCps.getValue() ? cps(this.leftClicks, now) + " CPS" : "LMB";
            String right = this.showCps.getValue() ? cps(this.rightClicks, now) + " CPS" : "RMB";
            this.key(this.showCps.getValue() ? "LMB" : null, KEY_LMB, 0.0F, cursorY, half, keySize, left);
            this.key(this.showCps.getValue() ? "RMB" : null, KEY_RMB, half + gap, cursorY, half, keySize, right);
            cursorY += keySize + gap;
        }
        if (this.showSpace.getValue()) {
            this.key("____", KEY_SPACE, 0.0F, cursorY, rowWidth, keySize * 0.7F, null);
            cursorY += keySize * 0.7F + gap;
        }
        if (this.showSneak.getValue()) {
            this.key("SNEAK", KEY_SNEAK, 0.0F, cursorY, rowWidth, keySize * 0.7F, null);
        }

        GlStateManager.popMatrix();
        RenderUtil.resetColor();
    }

    /**
     * @param label   main text, or null to use {@code subLabel} alone, centred
     * @param subLabel smaller second line (CPS), or null
     */
    private void key(String label, int index, float x, float y, float width, float height, String subLabel) {
        float pressed = this.press[index];
        int accent = HudStyle.color(this.colorMode.getValue(), this.color.getValue(), this.alpha.getValue(), index * 90L);

        int idle = Math.max(0, Math.min(255, this.idleAlpha.getValue()));
        // A click flash lifts the box briefly, which is what makes injected clicks visible while
        // the physical button stays down.
        float lit = Math.max(0.0F, Math.min(1.0F, pressed + this.pulse[index] * 0.6F));
        int backgroundAlpha = (int) (idle + (200 - idle) * lit);
        int background = (Math.max(0, Math.min(255, backgroundAlpha)) << 24)
                | (lit > 0.02F ? accent & 0xFFFFFF : 0x101014);
        HudStyle.rect(x, y, width, height, this.radius.getValue(), background);

        // Text fades from the accent colour to near-black as the key lights up behind it.
        int textAlpha = Math.max(0, Math.min(255, this.alpha.getValue()));
        int text = lit > 0.5F
                ? (textAlpha << 24) | 0x101014
                : (textAlpha << 24) | (accent & 0xFFFFFF);

        float centerX = x + width / 2.0F;
        if (label != null && subLabel != null) {
            float lineHeight = HudStyle.fontHeight();
            HudStyle.drawCentered(label, centerX, y + height / 2.0F - lineHeight + 1.0F, text, false);
            HudStyle.drawCentered(subLabel, centerX, y + height / 2.0F + 1.0F, text, false);
        } else {
            String shown = label != null ? label : subLabel;
            if (shown != null) {
                HudStyle.drawCentered(shown, centerX, y + (height - HudStyle.fontHeight()) / 2.0F + 1.0F, text, false);
            }
        }
    }
}
