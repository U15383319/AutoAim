package dev.cosmos.autoaim.client.trajectory;

public record BallisticProfile(
        double speed,
        double gravity,
        double airDrag,
        double waterDrag,
        boolean inheritsShooterMovement
) {
}
