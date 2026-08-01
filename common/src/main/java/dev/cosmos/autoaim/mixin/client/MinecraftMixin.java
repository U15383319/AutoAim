package dev.cosmos.autoaim.mixin.client;

import dev.cosmos.autoaim.client.AutoAimClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
    private void autoaim$makeTrajectoryCollisionGlow(
            Entity entity,
            CallbackInfoReturnable<Boolean> callbackInfo
    ) {
        if (AutoAimClient.isMarkedEntity(entity)) {
            callbackInfo.setReturnValue(true);
        }
    }
}
