package dev.cosmos.autoaim.mixin.client;

import dev.cosmos.autoaim.client.aim.PositionPacketTiming;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VecDeltaCodec.class)
public abstract class VecDeltaCodecTimingMixin implements PositionPacketTiming {
    @Unique
    private long autoaim$updatedAtNanos;

    @Inject(method = "setBase", at = @At("TAIL"))
    private void autoaim$recordPositionUpdate(Vec3 position, CallbackInfo callbackInfo) {
        autoaim$updatedAtNanos = System.nanoTime();
    }

    @Override
    public long autoaim$getUpdatedAtNanos() {
        return autoaim$updatedAtNanos;
    }
}
