package dev.cosmos.autoaim.client.aim;

import dev.cosmos.autoaim.client.AutoAimSettings;
import dev.cosmos.autoaim.client.trajectory.BallisticProfile;
import dev.cosmos.autoaim.client.trajectory.TrajectoryCalculator;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Environment(EnvType.CLIENT)
public final class BallisticAimSolver {
    private static final double[] TARGET_HEIGHT_FRACTIONS = {0.55D, 0.75D, 0.35D};
    private static final double ROOT_SCAN_STEP = 0.25D;
    private static final double EPSILON = 1.0E-7D;

    private BallisticAimSolver() {
    }

    public static Optional<AimSolution> solve(
            ClientLevel level,
            LocalPlayer shooter,
            Entity target,
            BallisticProfile profile
    ) {
        Vec3 origin = TrajectoryCalculator.launchOrigin(shooter);
        Vec3 inheritedMovement = TrajectoryCalculator.inheritedMovement(shooter, profile);
        AABB targetBox = target.getBoundingBox();

        for (double heightFraction : TARGET_HEIGHT_FRACTIONS) {
            Vec3 baseAimPoint = new Vec3(
                    (targetBox.minX + targetBox.maxX) * 0.5D,
                    Mth.lerp(heightFraction, targetBox.minY, targetBox.maxY),
                    (targetBox.minZ + targetBox.maxZ) * 0.5D
            );

            List<KinematicSolution> seeds = solveArcs(origin, baseAimPoint, inheritedMovement, profile);
            for (KinematicSolution seed : seeds) {
                KinematicSolution refined = seed;
                Vec3 lead = Vec3.ZERO;
                boolean solvable = true;

                // Two refinement passes are enough to lead ordinary walking/running targets
                // without making erratic entity velocity dominate the aim point.
                for (int pass = 0; pass < 2; pass++) {
                    lead = calculateLead(target, refined.flightTicks());
                    List<KinematicSolution> refinedArcs = solveArcs(
                            origin,
                            baseAimPoint.add(lead),
                            inheritedMovement,
                            profile
                    );
                    if (refinedArcs.isEmpty()) {
                        solvable = false;
                        break;
                    }
                    refined = closestInFlightTime(refinedArcs, refined.flightTicks());
                }

                if (!solvable) {
                    continue;
                }

                AABB predictedTargetBox = targetBox.move(lead)
                        .inflate(AutoAimSettings.PROJECTILE_HIT_INFLATION);
                if (canHitTarget(level, shooter, target, profile, refined, predictedTargetBox)) {
                    return Optional.of(new AimSolution(
                            Mth.wrapDegrees(refined.yaw()),
                            Mth.clamp(refined.pitch(), -89.9F, 89.9F),
                            refined.flightTicks()
                    ));
                }
            }
        }

        return Optional.empty();
    }

    private static List<KinematicSolution> solveArcs(
            Vec3 origin,
            Vec3 target,
            Vec3 inheritedMovement,
            BallisticProfile profile
    ) {
        if (origin.distanceTo(target) > AutoAimSettings.MAX_TRAJECTORY_DISTANCE) {
            return List.of();
        }

        List<KinematicSolution> solutions = new ArrayList<>(2);
        double previousTime = 0.05D;
        double previousError = requiredSpeedError(
                origin,
                target,
                inheritedMovement,
                profile,
                previousTime
        );

        for (double time = previousTime + ROOT_SCAN_STEP;
             time <= AutoAimSettings.MAX_FLIGHT_TICKS;
             time += ROOT_SCAN_STEP) {
            double error = requiredSpeedError(origin, target, inheritedMovement, profile, time);
            if (Double.isFinite(previousError) && Double.isFinite(error)
                    && Math.signum(previousError) != Math.signum(error)) {
                double root = bisectRoot(
                        origin,
                        target,
                        inheritedMovement,
                        profile,
                        previousTime,
                        time,
                        previousError
                );
                KinematicSolution solution = createSolution(
                        origin,
                        target,
                        inheritedMovement,
                        profile,
                        root
                );
                if (solution != null && solutions.stream()
                        .noneMatch(existing -> Math.abs(existing.flightTicks() - root) < 0.1D)) {
                    solutions.add(solution);
                    if (solutions.size() == 2) {
                        break;
                    }
                }
            }
            previousTime = time;
            previousError = error;
        }

        solutions.sort(Comparator.comparingDouble(KinematicSolution::flightTicks));
        return solutions;
    }

    private static double bisectRoot(
            Vec3 origin,
            Vec3 target,
            Vec3 inheritedMovement,
            BallisticProfile profile,
            double low,
            double high,
            double lowError
    ) {
        for (int iteration = 0; iteration < 28; iteration++) {
            double middle = (low + high) * 0.5D;
            double middleError = requiredSpeedError(
                    origin,
                    target,
                    inheritedMovement,
                    profile,
                    middle
            );
            if (Math.signum(lowError) == Math.signum(middleError)) {
                low = middle;
                lowError = middleError;
            } else {
                high = middle;
            }
        }
        return (low + high) * 0.5D;
    }

    private static double requiredSpeedError(
            Vec3 origin,
            Vec3 target,
            Vec3 inheritedMovement,
            BallisticProfile profile,
            double flightTicks
    ) {
        Vec3 requiredOwnVelocity = requiredOwnVelocity(
                origin,
                target,
                inheritedMovement,
                profile,
                flightTicks
        );
        return requiredOwnVelocity.lengthSqr() - profile.speed() * profile.speed();
    }

    private static Vec3 requiredOwnVelocity(
            Vec3 origin,
            Vec3 target,
            Vec3 inheritedMovement,
            BallisticProfile profile,
            double flightTicks
    ) {
        double drag = profile.airDrag();
        double displacementMultiplier;
        double gravityDisplacement;

        if (Math.abs(1.0D - drag) < EPSILON) {
            displacementMultiplier = flightTicks;
            gravityDisplacement = profile.gravity() * flightTicks * (flightTicks - 1.0D) * 0.5D;
        } else {
            displacementMultiplier = (1.0D - Math.pow(drag, flightTicks)) / (1.0D - drag);
            gravityDisplacement = profile.gravity() / (1.0D - drag)
                    * (flightTicks - displacementMultiplier);
        }

        Vec3 displacement = target.subtract(origin);
        Vec3 requiredTotalVelocity = new Vec3(
                displacement.x / displacementMultiplier,
                (displacement.y + gravityDisplacement) / displacementMultiplier,
                displacement.z / displacementMultiplier
        );
        return requiredTotalVelocity.subtract(inheritedMovement);
    }

    private static KinematicSolution createSolution(
            Vec3 origin,
            Vec3 target,
            Vec3 inheritedMovement,
            BallisticProfile profile,
            double flightTicks
    ) {
        Vec3 ownVelocity = requiredOwnVelocity(
                origin,
                target,
                inheritedMovement,
                profile,
                flightTicks
        );
        double horizontalSpeed = Math.sqrt(ownVelocity.x * ownVelocity.x + ownVelocity.z * ownVelocity.z);
        if (horizontalSpeed < EPSILON && Math.abs(ownVelocity.y) < EPSILON) {
            return null;
        }

        float yaw = (float) Math.toDegrees(Math.atan2(-ownVelocity.x, ownVelocity.z));
        float projectilePitch = (float) Math.toDegrees(Math.atan2(-ownVelocity.y, horizontalSpeed));
        float pitch = playerPitchFor(profile, projectilePitch);
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || Math.abs(pitch) >= 90.0F) {
            return null;
        }
        return new KinematicSolution(yaw, pitch, flightTicks);
    }

    private static float playerPitchFor(BallisticProfile profile, float projectilePitch) {
        double offset = Math.toRadians(profile.verticalLaunchAngleOffsetDegrees());
        if (Math.abs(offset) < EPSILON) {
            return projectilePitch;
        }

        double projectilePitchRadians = Math.toRadians(projectilePitch);
        double playerPitchTangent = (Math.tan(projectilePitchRadians) - Math.sin(offset))
                / Math.cos(offset);
        return (float) Math.toDegrees(Math.atan(playerPitchTangent));
    }

    private static Vec3 calculateLead(Entity target, double flightTicks) {
        // Keep all three velocity components so elytra users, flying mobs, falling
        // entities, and other airborne targets receive an appropriate lead.
        Vec3 velocity = target.getDeltaMovement();
        double leadTicks = Math.min(flightTicks, AutoAimSettings.MAX_TARGET_LEAD_TICKS);
        Vec3 lead = velocity.scale(leadTicks);
        double length = lead.length();
        if (length > AutoAimSettings.MAX_TARGET_LEAD_DISTANCE) {
            lead = lead.scale(AutoAimSettings.MAX_TARGET_LEAD_DISTANCE / length);
        }
        return lead;
    }

    private static KinematicSolution closestInFlightTime(
            List<KinematicSolution> solutions,
            double flightTicks
    ) {
        return solutions.stream()
                .min(Comparator.comparingDouble(solution -> Math.abs(solution.flightTicks() - flightTicks)))
                .orElseThrow();
    }

    private static boolean canHitTarget(
            ClientLevel level,
            LocalPlayer shooter,
            Entity target,
            BallisticProfile profile,
            KinematicSolution solution,
            AABB targetBox
    ) {
        var path = TrajectoryCalculator.traceIgnoringEntities(
                level,
                shooter,
                profile,
                solution.pitch(),
                solution.yaw()
        );
        Optional<TrajectoryCalculator.PathIntersection> targetHit =
                TrajectoryCalculator.findIntersection(path.segments(), targetBox);
        if (targetHit.isEmpty()) {
            return false;
        }

        Optional<TrajectoryCalculator.EntityCollision> obstruction =
                TrajectoryCalculator.findFirstEntityCollision(
                        level,
                        shooter,
                        path.segments(),
                        entity -> entity != target
                );
        return obstruction.isEmpty()
                || targetHit.get().distanceFromStart()
                <= obstruction.get().intersection().distanceFromStart() + EPSILON;
    }

    private record KinematicSolution(float yaw, float pitch, double flightTicks) {
    }
}
