package dev.cosmos.autoaim.client.aim;

import dev.cosmos.autoaim.client.AutoAimSettings;
import dev.cosmos.autoaim.client.trajectory.BallisticProfile;
import dev.cosmos.autoaim.client.trajectory.TrajectoryCalculator;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class BallisticAimSolver {
    private static final double[] BODY_HEIGHT_FRACTIONS = {0.55D, 0.75D, 0.35D};
    private static final double EYE_HIT_TOLERANCE = 0.1D;
    private static final double ROOT_SCAN_STEP = 0.25D;
    private static final double EPSILON = 1.0E-7D;

    private BallisticAimSolver() {
    }

    public static Optional<AimSolution> solve(
            Level level,
            LivingEntity shooter,
            Entity target,
            BallisticProfile profile
    ) {
        return solve(level, shooter, target, profile, false);
    }

    public static Optional<AimSolution> solve(
            Level level,
            LivingEntity shooter,
            Entity target,
            BallisticProfile profile,
            boolean aimAtEyes
    ) {
        return solve(level, shooter, target, profile, aimAtEyes,
                TargetMotion.linear(target.getDeltaMovement(), target.onGround()));
    }

    public static Optional<AimSolution> solve(
            Level level,
            LivingEntity shooter,
            Entity target,
            BallisticProfile profile,
            boolean aimAtEyes,
            TargetMotion motion
    ) {
        Vec3 origin = TrajectoryCalculator.launchOrigin(shooter, profile);
        Vec3 inheritedMovement = TrajectoryCalculator.inheritedMovement(shooter, profile);
        AABB targetBox = target.getBoundingBox();

        Vec3 eyePosition = target instanceof LivingEntity ? target.getEyePosition() : null;
        for (Vec3 baseAimPoint : targetAimPoints(targetBox, eyePosition, aimAtEyes)) {
            List<KinematicSolution> seeds = solveArcs(origin, baseAimPoint, inheritedMovement, profile);
            for (KinematicSolution seed : seeds) {
                KinematicSolution refined = seed;
                Vec3 lead = Vec3.ZERO;
                boolean solvable = true;

                // Two refinement passes are enough to lead ordinary walking/running targets
                // without making erratic entity velocity dominate the aim point.
                for (int pass = 0; pass < 2; pass++) {
                    // The client already displays an interpolated pose. Do not
                    // rewind it again or add an assumed network delay.
                    lead = motion.lead(refined.flightTicks(),
                            level.isClientSide ? 0 : profile.horizontalHitboxMotionOffset());
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

                AABB predictedTargetBox = targetHitBounds(targetBox, eyePosition, aimAtEyes).move(lead);
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

    static List<Vec3> targetAimPoints(AABB targetBox, Vec3 eyePosition, boolean aimAtEyes) {
        if (aimAtEyes) {
            // getEyePosition is also the origin of Minecraft's debug view ray.
            // Non-living targets such as vehicles and crystals use their center.
            return List.of(eyePosition != null ? eyePosition : targetBox.getCenter());
        }

        List<Vec3> points = new ArrayList<>(BODY_HEIGHT_FRACTIONS.length);
        for (double fraction : BODY_HEIGHT_FRACTIONS) {
            points.add(new Vec3(
                    (targetBox.minX + targetBox.maxX) * 0.5D,
                    Mth.lerp(fraction, targetBox.minY, targetBox.maxY),
                    (targetBox.minZ + targetBox.maxZ) * 0.5D
            ));
        }
        return points;
    }

    static AABB targetHitBounds(AABB targetBox, Vec3 eyePosition, boolean aimAtEyes) {
        if (aimAtEyes && eyePosition != null) {
            // Reaching the torso is not enough to validate an eye-level shot.
            return new AABB(eyePosition, eyePosition).inflate(EYE_HIT_TOLERANCE);
        }
        return targetBox.inflate(AutoAimSettings.PROJECTILE_HIT_INFLATION);
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
        // Fast bullets can reach nearby targets in much less than 0.05 ticks.
        double previousTime = 1.0E-5D;
        double previousError = requiredSpeedError(
                origin,
                target,
                inheritedMovement,
                profile,
                previousTime
        );

        while (previousTime < profile.maxFlightTicks()) {
            double time = Math.min(previousTime + ROOT_SCAN_STEP, profile.maxFlightTicks());
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
        double completeTicks = Math.floor(flightTicks);
        double fraction = flightTicks - completeTicks;
        double displacementMultiplier;
        double gravityDisplacement;

        // Interpolate within the final simulated tick, just as collision tracing
        // does. Extending the geometric series to a fractional exponent bends
        // that segment and even applies gravity before the first tick ends.
        if (Math.abs(1.0D - drag) < EPSILON) {
            displacementMultiplier = flightTicks;
            gravityDisplacement = profile.gravity()
                    * (completeTicks * (completeTicks - 1.0D) * 0.5D + fraction * completeTicks);
        } else {
            double dragPower = Math.pow(drag, completeTicks);
            displacementMultiplier = (1.0D - dragPower) / (1.0D - drag) + fraction * dragPower;
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

    private static KinematicSolution closestInFlightTime(
            List<KinematicSolution> solutions,
            double flightTicks
    ) {
        return solutions.stream()
                .min(Comparator.comparingDouble(solution -> Math.abs(solution.flightTicks() - flightTicks)))
                .orElseThrow();
    }

    private static boolean canHitTarget(
            Level level,
            LivingEntity shooter,
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
