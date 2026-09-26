package myau.mixin;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@SideOnly(Side.CLIENT)
@Mixin({EntityPlayerSP.class})
public interface IAccessorEntityPlayerSP {
    /** The double-tap window for sprinting; a movement rehearsal starts from its current value. */
    @Accessor("sprintToggleTimer")
    int getSprintToggleTimer();
}
