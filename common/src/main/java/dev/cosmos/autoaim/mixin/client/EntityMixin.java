package dev.cosmos.autoaim.mixin.client;

import dev.cosmos.autoaim.client.AutoAimClient;
import dev.cosmos.autoaim.client.config.AutoAimConfig;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void autoaim$preventMouseFightingLock(double yaw, double pitch, CallbackInfo callbackInfo) {
        if (AutoAimClient.shouldSuppressMouseTurn((Entity) (Object) this)) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
    private void autoaim$useConfiguredGlowColor(CallbackInfoReturnable<Integer> callbackInfo) {
        Entity self = (Entity) (Object) this;
        if (AutoAimClient.isMarkedEntity(self)) {
            callbackInfo.setReturnValue(AutoAimConfig.glowColor());
        }
    }
}
