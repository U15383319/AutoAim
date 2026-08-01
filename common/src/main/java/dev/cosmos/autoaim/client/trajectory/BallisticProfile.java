package dev.cosmos.autoaim.client.trajectory;

public record BallisticProfile(
        double speed,
        double gravity,
        double airDrag,
        double waterDrag,
        boolean inheritsShooterMovement,
        float verticalLaunchAngleOffsetDegrees
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
}
