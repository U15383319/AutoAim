package dev.cosmos.autoaim.fabric;

import dev.cosmos.autoaim.AutoAim;
import net.fabricmc.api.ModInitializer;

public final class AutoAimFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        AutoAim.init();
    }
}
