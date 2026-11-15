package dev.cosmos.autoaim.client.aim;

import dev.cosmos.autoaim.client.AutoAimSettings;
import net.minecraft.world.phys.Vec3;

/** A bounded constant-velocity forecast; uncertain axes have zero velocity. */
public record TargetMotion(Vec3 velocity, boolean grounded, double forecastTicks, double maxLeadDistance,
                           VerticalObservation verticalObservation) {
    public TargetMotion(Vec3 velocity, boolean grounded, double forecastTicks, double maxLeadDistance) {
        this(velocity, grounded, forecastTicks, maxLeadDistance, null);
    }

    public TargetMotion withVerticalObservation(double offset, double measuredAgeTicks) {
        return new TargetMotion(velocity, grounded, forecastTicks, maxLeadDistance,
                new VerticalObservation(offset, Math.max(0, measuredAgeTicks)));
    }
    public static TargetMotion linear(Vec3 velocity, boolean grounded) {
        return new TargetMotion(velocity, grounded, AutoAimSettings.MAX_TARGET_LEAD_TICKS,
                AutoAimSettings.MAX_TARGET_LEAD_DISTANCE);
    }

    public static TargetMotion trackedGun(Vec3 velocity, boolean grounded) {
        return linear(velocity, grounded);
    }

    public Vec3 lead(double flightTicks, double horizontalHitboxOffset) {
        double ticks = Math.max(0, Math.min(flightTicks, forecastTicks));
        double verticalVelocity = grounded && velocity.y < 0 ? 0 : velocity.y;
        Vec3 lead = new Vec3(velocity.x * (ticks + horizontalHitboxOffset),
                verticalVelocity * ticks, velocity.z * (ticks + horizontalHitboxOffset));
        double length = lead.length();
        if (length > maxLeadDistance) lead = lead.scale(maxLeadDistance / length);
        if (verticalObservation != null) {
            // ServerChunkCache sends tracking snapshots before ServerLevel
            // moves entities. TaCZ clips each bullet segment against a whole
            // target tick. This is a simulation-order step, not network age.
            double alignment = verticalObservation.positionOffset;
            if (verticalVelocity != 0) {
                alignment += verticalVelocity * (verticalObservation.measuredAgeTicks + 1 + Math.ceil(ticks) - ticks);
            }
            // Measured alignment has its own one-block bound. It is applied
            // only at a trigger; it never changes continuous head tracking.
            lead = lead.add(0, Math.max(-1, Math.min(1, alignment)), 0);
        }
        return lead;
    }

    public record VerticalObservation(double positionOffset, double measuredAgeTicks) {}
}
