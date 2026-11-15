package dev.cosmos.autoaim.forge.clienttest;

import dev.cosmos.autoaim.AutoAim;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

/** Test instances render off screen and leave the user's other audio unchanged. */
@Mod.EventBusSubscriber(modid = AutoAim.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class BackgroundClientTests {
    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        if (!Boolean.getBoolean("autoaim.clienttest.background")) return;
        event.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            mc.options.pauseOnLostFocus = false;
            mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
            GLFW.glfwHideWindow(mc.getWindow().getWindow());
        });
    }
}
