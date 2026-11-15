package dev.cosmos.autoaim.forge.mixin.client;

import dev.cosmos.autoaim.client.AutoAimClient;
import net.minecraft.client.Minecraft;
import net.minecraftforge.network.simple.SimpleChannel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TaCZ sends shots asynchronously and reads the server player's last rotation. */
@Mixin(value = SimpleChannel.class, remap = false)
public abstract class SimpleChannelMixin {
    @Shadow(remap = false)
    public abstract void sendToServer(Object message);

    @Inject(method = "sendToServer", at = @At("HEAD"), cancellable = true, remap = false)
    private void autoaim$syncRotationBeforeTaczShot(Object message, CallbackInfo callbackInfo) {
        if (!AutoAimClient.hasActiveTaczLock()
                || !message.getClass().getName().equals("com.tacz.guns.network.message.ClientMessagePlayerShoot")) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            // Keep rotation and the shot adjacent on the main thread. Waiting
            // for the next normal movement packet leaves a stale server angle.
            callbackInfo.cancel();
            minecraft.execute(() -> sendToServer(message));
            return;
        }
        AutoAimClient.syncTaczShotRotation(minecraft);
    }
}
