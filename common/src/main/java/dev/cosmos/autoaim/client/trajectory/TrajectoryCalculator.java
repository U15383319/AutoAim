package dev.cosmos.autoaim.client.trajectory;

import dev.cosmos.autoaim.client.AutoAimSettings;
import dev.cosmos.autoaim.client.entity.EntityTargeting;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

@Environment(EnvType.CLIENT)
public final class TrajectoryCalculator {
    private static final double EPSILON = 1.0E-7D;
    private static final double ENTITY_QUERY_INFLATION = 1.0D;

    private TrajectoryCalculator() {
    }

    /**
     * Traces the complete projectile path and resolves the first block or entity
     * collision. Entity candidates are queried once for the whole path instead of
     * once per simulated tick.
     */
    public static TrajectoryResult trace(
            ClientLevel level,
            LocalPlayer shooter,
            BallisticProfile profile,
            float pitch,
            float yaw
    ) {
        TrajectoryResult blockPath = traceIgnoringEntities(level, shooter, profile, pitch, yaw);
        Optional<EntityCollision> entityCollision = findFirstEntityCollision(
                level,
                shooter,
                blockPath.segments(),
                entity -> true
        );
        if (entityCollision.isEmpty()) {
            return blockPath;
        }

        EntityCollision collision = entityCollision.get();
        PathIntersection intersection = collision.intersection();
        return new TrajectoryResult(
                intersection.location(),
                Optional.empty(),
                Optional.of(new EntityHitResult(collision.entity(), intersection.location())),
                truncateSegments(blockPath.segments(), intersection),
                intersection.segmentIndex() + 1
        );
    }

    /**
     * Produces the sampled ballistic path up to the first block or the configured
     * range limit. This form is also used to validate a prospective aim solution.
     */
    public static TrajectoryResult traceIgnoringEntities(
            ClientLevel level,
            LocalPlayer shooter,
            BallisticProfile profile,
            float pitch,
            float yaw
    ) {
        Vec3 position = launchOrigin(shooter);
        Vec3 velocity = initialVelocity(shooter, profile, pitch, yaw);
        double traveled = 0.0D;
        List<TrajectorySegment> segments = new ArrayList<>();

        for (int tick = 0; tick < AutoAimSettings.MAX_FLIGHT_TICKS; tick++) {
            Vec3 next = position.add(velocity);
            double segmentLength = position.distanceTo(next);
            boolean reachedLimit = traveled + segmentLength > AutoAimSettings.MAX_TRAJECTORY_DISTANCE;
            if (reachedLimit && segmentLength > EPSILON) {
                double remaining = AutoAimSettings.MAX_TRAJECTORY_DISTANCE - traveled;
                next = position.add(next.subtract(position).scale(Math.max(0.0D, remaining / segmentLength)));
            }

            BlockHitResult blockHit = clipBlocks(level, shooter, position, next);
            if (blockHit.getType() == HitResult.Type.BLOCK) {
                Vec3 hitLocation = blockHit.getLocation();
                segments.add(new TrajectorySegment(position, hitLocation, traveled));
                return new TrajectoryResult(
                        hitLocation,
                        Optional.of(blockHit),
                        Optional.empty(),
                        segments,
                        tick + 1
                );
            }

            segments.add(new TrajectorySegment(position, next, traveled));
            if (reachedLimit) {
                return new TrajectoryResult(
                        next,
                        Optional.empty(),
                        Optional.empty(),
                        segments,
                        tick + 1
                );
            }

            traveled += segmentLength;
            position = next;
            double drag = dragAt(level, position, profile);
            velocity = velocity.scale(drag).add(0.0D, -profile.gravity(), 0.0D);
        }

        return new TrajectoryResult(
                position,
                Optional.empty(),
                Optional.empty(),
                segments,
                AutoAimSettings.MAX_FLIGHT_TICKS
        );
    }

    public static Optional<EntityCollision> findFirstEntityCollision(
            ClientLevel level,
            LocalPlayer shooter,
            List<TrajectorySegment> segments,
            Predicate<Entity> additionalFilter
    ) {
        if (segments.isEmpty()) {
            return Optional.empty();
        }

        AABB queryBounds = trajectoryBounds(segments).inflate(ENTITY_QUERY_INFLATION);
        EntityCollision closest = null;
        for (Entity entity : level.getEntities(
                shooter,
                queryBounds,
                candidate -> EntityTargeting.canProjectileCollide(shooter, candidate)
                        && additionalFilter.test(candidate)
        )) {
            Optional<PathIntersection> intersection = findIntersection(
                    segments,
                    entity.getBoundingBox().inflate(AutoAimSettings.PROJECTILE_HIT_INFLATION)
            );
            if (intersection.isPresent()
                    && (closest == null
                    || intersection.get().distanceFromStart()
                    < closest.intersection().distanceFromStart())) {
                closest = new EntityCollision(entity, intersection.get());
            }
        }
        return Optional.ofNullable(closest);
    }

    public static Optional<PathIntersection> findIntersection(
            List<TrajectorySegment> segments,
            AABB box
    ) {
        for (int index = 0; index < segments.size(); index++) {
            TrajectorySegment segment = segments.get(index);
            Optional<Vec3> clipped = box.contains(segment.start())
                    ? Optional.of(segment.start())
                    : box.clip(segment.start(), segment.end());
            if (clipped.isPresent()) {
                Vec3 location = clipped.get();
                return Optional.of(new PathIntersection(
                        location,
                        segment.distanceFromStart() + segment.start().distanceTo(location),
                        index
                ));
            }
        }
        return Optional.empty();
    }

    public static AABB trajectoryBounds(List<TrajectorySegment> segments) {
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("A trajectory must contain at least one segment");
        }
        TrajectorySegment first = segments.get(0);
        AABB bounds = new AABB(first.start(), first.end());
        for (int index = 1; index < segments.size(); index++) {
            TrajectorySegment segment = segments.get(index);
            bounds = bounds.minmax(new AABB(segment.start(), segment.end()));
        }
        return bounds;
    }

    public static Vec3 launchOrigin(LocalPlayer shooter) {
        return new Vec3(shooter.getX(), shooter.getEyeY() - 0.1D, shooter.getZ());
    }

    public static Vec3 inheritedMovement(LocalPlayer shooter, BallisticProfile profile) {
        if (!profile.inheritsShooterMovement()) {
            return Vec3.ZERO;
        }
        Vec3 movement = shooter.getDeltaMovement();
        return new Vec3(movement.x, shooter.onGround() ? 0.0D : movement.y, movement.z);
    }

    public static Vec3 initialVelocity(
            LocalPlayer shooter,
            BallisticProfile profile,
            float pitch,
            float yaw
    ) {
        return launchDirection(profile, pitch, yaw)
                .scale(profile.speed())
                .add(inheritedMovement(shooter, profile));
    }

    private static Vec3 launchDirection(BallisticProfile profile, float pitch, float yaw) {
        float verticalOffset = profile.verticalLaunchAngleOffsetDegrees();
        if (Math.abs(verticalOffset) < EPSILON) {
            return Vec3.directionFromRotation(pitch, yaw);
        }

        // Projectile#shootFromRotation applies its angle offset only to the
        // vertical component before normalizing the complete direction vector.
        float pitchRadians = pitch * Mth.DEG_TO_RAD;
        float yawRadians = yaw * Mth.DEG_TO_RAD;
        double horizontalScale = Mth.cos(pitchRadians);
        return new Vec3(
                -Mth.sin(yawRadians) * horizontalScale,
                -Mth.sin((pitch + verticalOffset) * Mth.DEG_TO_RAD),
                Mth.cos(yawRadians) * horizontalScale
        ).normalize();
    }

    public static BlockHitResult clipBlocks(
            ClientLevel level,
            LocalPlayer shooter,
            Vec3 from,
            Vec3 to
    ) {
        return level.clip(new ClipContext(
                from,
                to,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                shooter
        ));
    }

    public static double dragAt(ClientLevel level, Vec3 position, BallisticProfile profile) {
        boolean inWater = level.getFluidState(BlockPos.containing(position)).is(FluidTags.WATER);
        return inWater ? profile.waterDrag() : profile.airDrag();
    }

    private static List<TrajectorySegment> truncateSegments(
            List<TrajectorySegment> segments,
            PathIntersection intersection
    ) {
        List<TrajectorySegment> truncated = new ArrayList<>(intersection.segmentIndex() + 1);
        truncated.addAll(segments.subList(0, intersection.segmentIndex()));
        TrajectorySegment hitSegment = segments.get(intersection.segmentIndex());
        truncated.add(new TrajectorySegment(
                hitSegment.start(),
                intersection.location(),
                hitSegment.distanceFromStart()
        ));
        return truncated;
    }

    public record PathIntersection(Vec3 location, double distanceFromStart, int segmentIndex) {
    }

    public record EntityCollision(Entity entity, PathIntersection intersection) {
    }
}
