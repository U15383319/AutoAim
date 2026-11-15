package dev.cosmos.autoaim.client.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** Collision rules shared by client prediction and server-side GameTests. */
public final class ProjectileCollision {
    private ProjectileCollision() {
    }

    public static boolean canHit(LivingEntity shooter, Entity entity) {
        return entity != shooter
                && entity.level() == shooter.level()
                && entity.isAlive()
                && !entity.isSpectator()
                && entity.isPickable()
                && !shooter.isPassengerOfSameVehicle(entity);
    }
}
