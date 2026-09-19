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
import myau.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.item.ItemStack;

/**
 * The selected item's name above the hotbar, fading in when you switch slots and out again after
 * a moment - the vanilla tooltip, restyled to match the custom hotbar. One string and one rounded
 * rect while it is visible, nothing at all when it isn't.
 */
public class HotbarText extends Module implements DraggableHud {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty colorMode = new ModeProperty("Color-Mode", HudStyle.MODE_HUD, HudStyle.COLOR_MODES);
    public final ColorProperty color = new ColorProperty("Color", 0xFFFFFF, () -> this.colorMode.getValue() == HudStyle.MODE_CUSTOM);
    public final BooleanProperty rarityColors = new BooleanProperty("Rarity-Colors", true);
    public final IntProperty alpha = new IntProperty("Alpha", 255, 40, 255);
    public final FloatProperty scale = new FloatProperty("Scale", 1.0F, 0.5F, 2.0F);
    public final IntProperty offsetX = new IntProperty("Offset-X", 0, -2000, 2000);
    public final IntProperty offsetY = new IntProperty("Offset-Y", 0, -2000, 2000);
    public final IntProperty holdTime = new IntProperty("Hold-Time", 1600, 200, 6000);
    public final IntProperty fadeTime = new IntProperty("Fade-Time", 350, 50, 1500);
    public final BooleanProperty background = new BooleanProperty("Background", true);
    public final IntProperty backgroundAlpha = new IntProperty("Background-Alpha", 90, 0, 255, background::getValue);
    public final FloatProperty radius = new FloatProperty("Radius", 4.0F, 0.0F, 10.0F);
    public final BooleanProperty stackCount = new BooleanProperty("Stack-Count", true);
    public final BooleanProperty durability = new BooleanProperty("Durability", true);
    public final BooleanProperty shadow = new BooleanProperty("Shadow", true);
    public final BooleanProperty hideVanilla = new BooleanProperty("Hide-Vanilla", true);

    private int lastSlot = -1;
    private ItemStack lastStack;
    private String text = "";
    private long shownAt;

    public HotbarText() {
        super("HotbarText", false, false, "Item name above the hotbar when you switch slots");
    }

    @Override
    public void onEnabled() {
        this.lastSlot = -1;
        this.lastStack = null;
        this.text = "";
        this.shownAt = 0L;
    }

    /** True while this module is drawing its own name, so the vanilla one can be suppressed. */
    public boolean shouldHideVanilla() {
        return this.isEnabled() && this.hideVanilla.getValue();
    }

    private String describe(ItemStack stack) {
        StringBuilder builder = new StringBuilder(stack.getDisplayName());
        if (this.stackCount.getValue() && stack.stackSize > 1) {
            builder.append(" §7x").append(stack.stackSize);
        }
        if (this.durability.getValue() && stack.isItemStackDamageable()) {
            int left = stack.getMaxDamage() - stack.getItemDamage();
            builder.append(" §8[").append(left).append('/').append(stack.getMaxDamage()).append(']');
        }
        return builder.toString();
    }

    /** Baseline Y with a zero offset: just above the hotbar and its XP bar, like the vanilla name. */
    private float anchorY(ScaledResolution sr) {
        return sr.getScaledHeight() - 22.0F - 2.0F - 22.0F;
    }

    @Override
    public String getHudName() {
        return "Hotbar Text";
    }

    @Override
    public float[] getHudBounds() {
        if (mc.thePlayer == null) {
            return null;
        }
        // Editor shows the current name, or a sample so an empty hand is still grabbable.
        String sample = this.text.isEmpty() ? "Item Name" : this.text;
        float scale = this.scale.getValue();
        float width = HudStyle.stringWidth(sample) * scale;
        float height = HudStyle.fontHeight() * scale;
        ScaledResolution sr = new ScaledResolution(mc);
        float centerX = sr.getScaledWidth() / 2.0F + this.offsetX.getValue();
        return new float[]{centerX - width / 2.0F, this.anchorY(sr) + this.offsetY.getValue(), width, height};
    }

    @Override
    public void setHudPosition(float x, float y) {
        float[] bounds = this.getHudBounds();
        if (bounds == null) {
            return;
        }
        ScaledResolution sr = new ScaledResolution(mc);
        this.offsetX.setValue(Math.round(x + bounds[2] / 2.0F - sr.getScaledWidth() / 2.0F));
        this.offsetY.setValue(Math.round(y - this.anchorY(sr)));
    }

    @Override
    public void resetHudPosition() {
        this.offsetX.setValue(0);
        this.offsetY.setValue(0);
    }

    @Override
    public boolean isHudEnabled() {
        return this.isEnabled();
    }

    /** The chat colour vanilla uses for the item's rarity, as RGB. */
    private static int rarityColor(ItemStack stack) {
        switch (stack.getRarity()) {
            case UNCOMMON:
                return 0xFFFF55;
            case RARE:
                return 0x55FFFF;
            case EPIC:
                return 0xFF55FF;
            case COMMON:
            default:
                return 0xFFFFFF;
        }
    }

    /** Same idea as the vanilla overlay: a new name shows when the slot or the item changes. */
    private void update() {
        int slot = mc.thePlayer.inventory.currentItem;
        ItemStack stack = mc.thePlayer.inventory.getCurrentItem();
        boolean changed = slot != this.lastSlot
                || (stack == null) != (this.lastStack == null)
                || (stack != null && this.lastStack != null && !ItemStack.areItemStacksEqual(stack, this.lastStack));
        if (!changed) {
            return;
        }
        this.lastSlot = slot;
        this.lastStack = stack == null ? null : stack.copy();
        if (stack == null) {
            this.text = "";
            this.shownAt = 0L;
            return;
        }
        this.text = this.describe(stack);
        this.shownAt = System.currentTimeMillis();
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (mc.thePlayer == null || mc.gameSettings.showDebugInfo || mc.playerController.isSpectator()) {
            return;
        }
        this.update();
        if (this.text.isEmpty() || this.shownAt == 0L) {
            return;
        }

        long elapsed = System.currentTimeMillis() - this.shownAt;
        int hold = this.holdTime.getValue();
        int fade = this.fadeTime.getValue();
        if (elapsed > hold + fade) {
            return;
        }
        float opacity = elapsed <= fade
                ? elapsed / (float) fade                       // fade in
                : elapsed >= hold ? 1.0F - (elapsed - hold) / (float) fade : 1.0F;
        opacity = Math.max(0.0F, Math.min(1.0F, opacity));
        if (opacity <= 0.01F) {
            return;
        }

        int textAlpha = (int) (Math.max(0, Math.min(255, this.alpha.getValue())) * opacity);
        if (textAlpha <= 2) {
            return;
        }
        int argb = this.rarityColors.getValue() && this.lastStack != null
                ? (textAlpha << 24) | rarityColor(this.lastStack)
                : (HudStyle.color(this.colorMode.getValue(), this.color.getValue(), textAlpha, 0L));

        float scale = this.scale.getValue();
        ScaledResolution sr = new ScaledResolution(mc);
        float textWidth = HudStyle.stringWidth(this.text);
        float lineHeight = HudStyle.fontHeight();
        float padding = 4.0F;
        // Just above the hotbar and its XP bar, like the vanilla item name.
        float baseY = this.anchorY(sr) + this.offsetY.getValue();

        GlStateManager.pushMatrix();
        GlStateManager.translate(sr.getScaledWidth() / 2.0F + this.offsetX.getValue(), baseY, 0.0F);
        GlStateManager.scale(scale, scale, 1.0F);
        GlStateManager.enableBlend();

        if (this.background.getValue()) {
            int backdrop = (int) (this.backgroundAlpha.getValue() * opacity);
            HudStyle.panel(-textWidth / 2.0F - padding, -padding / 2.0F,
                    textWidth + padding * 2.0F, lineHeight + padding, this.radius.getValue(), backdrop);
        }
        HudStyle.drawCentered(this.text, 0.0F, 0.0F, argb, this.shadow.getValue());

        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
        RenderUtil.resetColor();
    }
}
