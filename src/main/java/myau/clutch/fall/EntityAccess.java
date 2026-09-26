package myau.clutch.fall;

import myau.mixin.IAccessorEntityLivingBase;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.potion.PotionEffect;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * The two private fields the rehearsals need from a living entity. In game the mixin accessors
 * reach them; where mixins are not applied - a headless test - plain reflection does, by the
 * deobfuscated name first and the SRG name second.
 */
final class EntityAccess {
    private static Field jumpTicks;
    private static Field activePotions;

    private EntityAccess() {
    }

    static int getJumpTicks(EntityLivingBase entity) {
        if (entity instanceof IAccessorEntityLivingBase) {
            return ((IAccessorEntityLivingBase) entity).getJumpTicks();
        }
        try {
            return jumpTicksField().getInt(entity);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static void setJumpTicks(EntityLivingBase entity, int ticks) {
        if (entity instanceof IAccessorEntityLivingBase) {
            ((IAccessorEntityLivingBase) entity).setJumpTicks(ticks);
            return;
        }
        try {
            jumpTicksField().setInt(entity, ticks);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<Integer, PotionEffect> activePotions(EntityLivingBase entity) {
        if (entity instanceof IAccessorEntityLivingBase) {
            return ((IAccessorEntityLivingBase) entity).getActivePotionsMap();
        }
        try {
            return (Map<Integer, PotionEffect>) activePotionsField().get(entity);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Field jumpTicksField() {
        if (jumpTicks == null) {
            jumpTicks = field("jumpTicks", "field_70773_bE");
        }
        return jumpTicks;
    }

    private static Field activePotionsField() {
        if (activePotions == null) {
            activePotions = field("activePotionsMap", "field_70713_bf");
        }
        return activePotions;
    }

    private static Field field(String... names) {
        for (String name : names) {
            try {
                Field field = EntityLivingBase.class.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Try the next name.
            }
        }
        throw new IllegalStateException("No field " + names[0] + " on EntityLivingBase");
    }
}
