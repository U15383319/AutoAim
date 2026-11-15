package dev.cosmos.autoaim.client.trajectory;

import dev.cosmos.autoaim.client.AutoAimSettings;

import java.util.Optional;

public record BallisticProfile(
        double speed,
        double gravity,
        double airDrag,
        double waterDrag,
        boolean inheritsShooterMovement,
        float verticalLaunchAngleOffsetDegrees,
        double waterGravityMultiplier,
        int maxFlightTicks,
        boolean interpolatedLaunchPosition,
        double horizontalHitboxMotionOffset
) {
    public BallisticProfile(
            double speed,
            double gravity,
            double airDrag,
            double waterDrag,
            boolean inheritsShooterMovement
    ) {
        this(speed, gravity, airDrag, waterDrag, inheritsShooterMovement, 0.0F);
    }

    public BallisticProfile(
            double speed,
            double gravity,
            double airDrag,
            double waterDrag,
            boolean inheritsShooterMovement,
            float verticalLaunchAngleOffsetDegrees
    ) {
        this(speed, gravity, airDrag, waterDrag, inheritsShooterMovement,
                verticalLaunchAngleOffsetDegrees, 1.0D, AutoAimSettings.MAX_FLIGHT_TICKS, false, 0.0D);
    }

    public static Optional<BallisticProfile> kineticBullet(
            double speedPerSecond,
            double speedMultiplier,
            double gravity,
            double friction,
            double lifeSeconds
    ) {
        double speed = speedPerSecond * speedMultiplier / 20.0D;
        if (!Double.isFinite(speedPerSecond) || speedPerSecond <= 0.0D
                || !Double.isFinite(speedMultiplier) || speedMultiplier <= 0.0D
                || !Double.isFinite(speed) || speed <= 0.0D
                || !Double.isFinite(gravity) || gravity < 0.0D
                || !Double.isFinite(friction) || friction < 0.0D || friction >= 1.0D
                || !Double.isFinite(lifeSeconds) || lifeSeconds <= 0.0D) {
            return Optional.empty();
        }

        // TaCZ moves the bullet before applying drag/gravity and discards it
        // when tickCount reaches life - 1. Water uses 0.4 friction and 0.6 gravity.
        int lifeTicks = (int) Math.min((float) lifeSeconds * 20.0F, Integer.MAX_VALUE);
        int flightTicks = Math.min(AutoAimSettings.MAX_FLIGHT_TICKS, Math.max(1, lifeTicks - 1));
        return Optional.of(new BallisticProfile(
                speed, gravity, 1.0D - friction, 0.6D, true,
                0.0F, 0.6D, flightTicks, true, -4.5D
        ));
    }
}
