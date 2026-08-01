package dev.cosmos.autoaim.client.entity;

import dev.cosmos.autoaim.client.config.AutoAimConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;

@Environment(EnvType.CLIENT)
public final class EntityTargeting {
    private EntityTargeting() {
    }

    public static boolean canProjectileCollide(LocalPlayer shooter, Entity entity) {
        return entity != shooter
                && entity.level() == shooter.level()
                && entity.isAlive()
                && !entity.isSpectator()
                && entity.isPickable()
                && !shooter.isPassengerOfSameVehicle(entity);
    }

    public static boolean canLock(LocalPlayer shooter, Entity entity) {
        return canProjectileCollide(shooter, entity) && !AutoAimConfig.isBlacklisted(entity);
    }
}
