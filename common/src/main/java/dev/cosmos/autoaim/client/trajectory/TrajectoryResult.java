package dev.cosmos.autoaim.client.trajectory;

import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;

public record TrajectoryResult(
        Vec3 endpoint,
        Optional<BlockHitResult> blockHit,
        Optional<EntityHitResult> entityHit,
        List<TrajectorySegment> segments,
        int flightTicks
) {
    public TrajectoryResult {
        segments = List.copyOf(segments);
    }
}
