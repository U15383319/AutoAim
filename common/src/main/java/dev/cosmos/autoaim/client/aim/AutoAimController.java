package dev.cosmos.autoaim.client.aim;

import dev.cosmos.autoaim.client.AutoAimSettings;
import dev.cosmos.autoaim.client.entity.EntityTargeting;
import dev.cosmos.autoaim.client.trajectory.TrajectoryCalculator;
import dev.cosmos.autoaim.client.trajectory.TrajectoryResult;
import dev.cosmos.autoaim.client.trajectory.TrajectorySegment;
import dev.cosmos.autoaim.client.trajectory.WeaponBallistics;
import dev.cosmos.autoaim.client.trajectory.WeaponContext;
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
import java.util.List;
import java.util.Optional;

@Environment(EnvType.CLIENT)
public final class AutoAimController {
    private static final double PROXIMITY_BAND_SIZE = 0.5D;
    private static final double EPSILON = 1.0E-7D;

    private Entity lockedTarget;
    private LocalPlayer aimingPlayer;
    private AimSolution aimSolution;

    public void tick(Minecraft minecraft, boolean aimKeyHeld) {
        aimSolution = null;
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (!aimKeyHeld || minecraft.screen != null || player == null || level == null || player.isSpectator()) {
            clearTarget();
            return;
        }

        Optional<WeaponContext> weapon = WeaponBallistics.findAutoAimWeapon(player);
        if (weapon.isEmpty()) {
            // A bow's first few draw ticks and a trident's pre-throw wind-up do not
            // yet have a usable trajectory. Keep an existing lock so it can resume
            // as soon as the charge becomes valid.
            if (player.isUsingItem() && WeaponBallistics.isSupportedProjectileItem(player.getUseItem())) {
                return;
            }
            clearTarget();
            return;
        }

        WeaponContext context = weapon.get();
        if (!isValidTarget(player, lockedTarget)) {
            TrajectoryResult currentTrajectory = TrajectoryCalculator.trace(
                    level,
                    player,
                    context.ballistics(),
                    player.getXRot(),
                    player.getYRot()
            );
            lockedTarget = findTrajectoryTarget(level, player, currentTrajectory).orElse(null);
        }
        if (lockedTarget == null) {
            return;
        }

        aimingPlayer = player;
        aimSolution = BallisticAimSolver.solve(
                level,
                player,
                lockedTarget,
                context.ballistics()
        ).orElse(null);
    }

    public void clearTarget() {
        lockedTarget = null;
        aimingPlayer = null;
        aimSolution = null;
    }

    public Optional<Entity> lockedTarget() {
        return Optional.ofNullable(lockedTarget);
    }

    public Optional<AimSolution> aimSolution(LocalPlayer player) {
        if (player != aimingPlayer
                || aimSolution == null
                || !isValidTarget(player, lockedTarget)
                || WeaponBallistics.findAutoAimWeapon(player).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(aimSolution);
    }

    private static Optional<Entity> findTrajectoryTarget(
            ClientLevel level,
            LocalPlayer player,
            TrajectoryResult trajectory
    ) {
        List<TrajectorySegment> segments = trajectory.segments();
        if (segments.isEmpty()) {
            return Optional.empty();
        }

        double radius = AutoAimSettings.TARGET_SEARCH_RADIUS;
        double radiusSquared = radius * radius;
        AABB searchBounds = TrajectoryCalculator.trajectoryBounds(segments).inflate(radius);

        // One spatial query gathers every possible target. The following work is
        // only O(entity count * sampled segment count), with no further world scans.
        return level.getEntities(
                        player,
                        searchBounds,
                        entity -> isValidTarget(player, entity)
                ).stream()
                .map(entity -> calculateProximity(entity, segments))
                .filter(proximity -> proximity.distanceSquared() <= radiusSquared)
                .min(Comparator
                        .comparingInt(PathProximity::distanceBand)
                        .thenComparingDouble(PathProximity::distanceFromStart)
                        .thenComparingDouble(PathProximity::distanceSquared)
                        .thenComparingInt(proximity -> proximity.entity().getId()))
                .map(PathProximity::entity);
    }

    private static PathProximity calculateProximity(
        Entity entity,
        List<TrajectorySegment> segments
    ) {
        AABB entityBox = entity.getBoundingBox();
        Vec3 center = entityBox.getCenter();
        double bestDistanceSquared = Double.POSITIVE_INFINITY;
        double bestDistanceFromStart = Double.POSITIVE_INFINITY;

        for (TrajectorySegment segment : segments) {
            Optional<Vec3> directHit = entityBox.contains(segment.start())
                    ? Optional.of(segment.start())
                    : entityBox.clip(segment.start(), segment.end());
            if (directHit.isPresent()) {
                return new PathProximity(
                        entity,
                        0.0D,
                        segment.distanceFromStart() + segment.start().distanceTo(directHit.get())
                );
            }

            Vec3 segmentVector = segment.end().subtract(segment.start());
            double lengthSquared = segmentVector.lengthSqr();
            double parameter = lengthSquared < EPSILON
                    ? 0.0D
                    : Mth.clamp(center.subtract(segment.start()).dot(segmentVector) / lengthSquared, 0.0D, 1.0D);
            Vec3 nearestPathPoint = segment.start().add(segmentVector.scale(parameter));
            double distanceSquared = distanceToBoxSquared(nearestPathPoint, entityBox);
            double distanceFromStart = segment.distanceFromStart() + segment.length() * parameter;

            if (distanceSquared < bestDistanceSquared - EPSILON
                    || (Math.abs(distanceSquared - bestDistanceSquared) <= EPSILON
                    && distanceFromStart < bestDistanceFromStart)) {
                bestDistanceSquared = distanceSquared;
                bestDistanceFromStart = distanceFromStart;
            }
        }

        return new PathProximity(entity, bestDistanceSquared, bestDistanceFromStart);
    }

    private static boolean isValidTarget(LocalPlayer player, Entity entity) {
        if (entity == null || !EntityTargeting.canLock(player, entity)) {
            return false;
        }
        double maximumDistance = AutoAimSettings.MAX_TRAJECTORY_DISTANCE
                + AutoAimSettings.TARGET_SEARCH_RADIUS;
        return player.distanceToSqr(entity) <= maximumDistance * maximumDistance;
    }

    private static double distanceToBoxSquared(Vec3 point, AABB box) {
        double dx = Math.max(Math.max(box.minX - point.x, 0.0D), point.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - point.y, 0.0D), point.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - point.z, 0.0D), point.z - box.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    private record PathProximity(
            Entity entity,
            double distanceSquared,
            double distanceFromStart
    ) {
        private int distanceBand() {
            return (int) Math.floor(Math.sqrt(distanceSquared) / PROXIMITY_BAND_SIZE);
        }
    }
}
