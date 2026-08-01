package dev.cosmos.autoaim.client.trajectory;

import net.minecraft.world.phys.Vec3;

public record TrajectorySegment(Vec3 start, Vec3 end, double distanceFromStart) {
    public double length() {
        return start.distanceTo(end);
    }
}
