package dev.cosmos.autoaim.client.aim;

import dev.cosmos.autoaim.client.AutoAimSettings;
import dev.cosmos.autoaim.client.entity.EntityTargeting;
import dev.cosmos.autoaim.client.melee.MeleeCombat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.Optional;

/**
 * Lightweight melee target acquisition. Unlike projectile auto aim this needs
 * no trajectory simulation: candidates are queried once from a bounded AABB and
 * ordered by their closest bounding-box distance to the player's eyes.
 */
@Environment(EnvType.CLIENT)
public final class MeleeAimController {
    private Entity lockedTarget;

    public void tick(Minecraft minecraft, boolean autoAimHeld) {
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (!autoAimHeld
                || minecraft.screen != null
                || player == null
                || level == null
                || !MeleeCombat.isActive(player)) {
            clearTarget();
            return;
        }

        if (!isValidTarget(player, lockedTarget)) {
            lockedTarget = findNearestTarget(level, player).orElse(null);
        }

        if (lockedTarget != null) {
            aimAt(player, lockedTarget);
        }
    }

    public Optional<Entity> lockedTarget() {
        return Optional.ofNullable(lockedTarget);
    }

    public void clearTarget() {
        lockedTarget = null;
    }

    private static Optional<Entity> findNearestTarget(ClientLevel level, LocalPlayer player) {
        double range = AutoAimSettings.MELEE_LOCK_RANGE;
        double rangeSquared = range * range;
        Vec3 eyePosition = player.getEyePosition();
        AABB searchBox = player.getBoundingBox().inflate(range);

        return level.getEntities(player, searchBox, entity -> EntityTargeting.canLock(player, entity))
                .stream()
                .map(entity -> new Candidate(entity, distanceToBoxSquared(eyePosition, entity.getBoundingBox())))
                .filter(candidate -> candidate.distanceSquared() <= rangeSquared)
                .min(Comparator.comparingDouble(Candidate::distanceSquared)
                        .thenComparingInt(candidate -> candidate.entity().getId()))
                .map(Candidate::entity);
    }

    private static boolean isValidTarget(LocalPlayer player, Entity entity) {
        if (entity == null || !EntityTargeting.canLock(player, entity)) {
            return false;
        }

        double range = AutoAimSettings.MELEE_LOCK_RANGE;
        return distanceToBoxSquared(player.getEyePosition(), entity.getBoundingBox()) <= range * range;
    }

    private static void aimAt(LocalPlayer player, Entity target) {
        Vec3 delta = target.getBoundingBox().getCenter().subtract(player.getEyePosition());
        double horizontalDistance = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        if (horizontalDistance < 1.0E-6D && Math.abs(delta.y) < 1.0E-6D) {
            return;
        }

        float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0D);
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, horizontalDistance));
        player.setYRot(Mth.wrapDegrees(yaw));
        player.setXRot(Mth.clamp(pitch, -90.0F, 90.0F));
    }

    private static double distanceToBoxSquared(Vec3 point, AABB box) {
        double x = Mth.clamp(point.x, box.minX, box.maxX);
        double y = Mth.clamp(point.y, box.minY, box.maxY);
        double z = Mth.clamp(point.z, box.minZ, box.maxZ);
        return point.distanceToSqr(x, y, z);
    }

    private record Candidate(Entity entity, double distanceSquared) {
    }
}
