package dev.cosmos.autoaim.forge;

import dev.cosmos.autoaim.AutoAim;
import net.minecraftforge.fml.common.Mod;

@Mod(AutoAim.MOD_ID)
public final class AutoAimForge {
    public AutoAimForge() {
        AutoAim.init();
    }
}
