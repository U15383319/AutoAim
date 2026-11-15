package dev.cosmos.autoaim.mixin.client;

import dev.cosmos.autoaim.client.AutoAimClient;
import dev.cosmos.autoaim.client.trajectory.WeaponBallistics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
    @Unique private AutoAimClient.VanillaShotRotation autoaim$releaseRotation;
    @Unique private AutoAimClient.VanillaShotRotation autoaim$crossbowRotation;

    @Inject(method = "releaseUsingItem", at = @At("HEAD"))
    private void autoaim$prepareRelease(Player player, CallbackInfo callbackInfo) {
        Minecraft mc = Minecraft.getInstance();
        autoaim$releaseRotation = player == mc.player ? WeaponBallistics.findReleaseShot(mc.player)
                .map(weapon -> AutoAimClient.beginVanillaShot(mc, weapon, true)).orElse(null) : null;
    }

    @Inject(method = "releaseUsingItem", at = @At("RETURN"))
    private void autoaim$finishRelease(Player player, CallbackInfo callbackInfo) {
        AutoAimClient.endVanillaShot(Minecraft.getInstance(), autoaim$releaseRotation);
        autoaim$releaseRotation = null;
    }

    @Inject(method = "useItem", at = @At("HEAD"))
    private void autoaim$prepareCrossbow(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> callbackInfo) {
        Minecraft mc = Minecraft.getInstance();
        // Capture the loaded projectile/profile before client-side use clears
        // the crossbow tag. Vanilla itself sends PosRot before the use packet.
        autoaim$crossbowRotation = player == mc.player ? WeaponBallistics.findCrossbowShot(mc.player, hand)
                .map(weapon -> AutoAimClient.beginVanillaShot(mc, weapon, false)).orElse(null) : null;
    }

    @Inject(method = "useItem", at = @At("RETURN"))
    private void autoaim$finishCrossbow(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> callbackInfo) {
        AutoAimClient.endVanillaShot(Minecraft.getInstance(), autoaim$crossbowRotation);
        autoaim$crossbowRotation = null;
    }
}
