package dev.cosmos.autoaim.client.aim;

/**
 * Smooths view rotation using elapsed time, so tracking behaves consistently
 * across frame rates. Large corrections also have a bounded angular speed.
 */
public final class AimSmoothing {
    private static final double RESPONSE_PER_SECOND = 24.0D;
    private static final double MAX_DEGREES_PER_SECOND = 540.0D;
    private static final double MAX_FRAME_SECONDS = 0.05D;

    private AimSmoothing() {
    }

    public static Rotation step(
            float currentYaw,
            float currentPitch,
            float targetYaw,
            float targetPitch,
            double elapsedSeconds
    ) {
        double seconds = Math.max(0.0D, Math.min(elapsedSeconds, MAX_FRAME_SECONDS));
        double yawDelta = wrapDegrees(targetYaw - currentYaw);
        double pitchDelta = clampPitch(targetPitch) - currentPitch;
        double distance = Math.hypot(yawDelta, pitchDelta);
        if (distance == 0.0D || seconds == 0.0D) {
            return new Rotation(currentYaw, currentPitch);
        }

        double blend = -Math.expm1(-RESPONSE_PER_SECOND * seconds);
        double scale = Math.min(blend, MAX_DEGREES_PER_SECOND * seconds / distance);
        return new Rotation(
                currentYaw + (float) (yawDelta * scale),
                clampPitch(currentPitch + (float) (pitchDelta * scale))
        );
    }

    private static float wrapDegrees(float angle) {
        float wrapped = angle % 360.0F;
        if (wrapped >= 180.0F) {
            wrapped -= 360.0F;
        }
        if (wrapped < -180.0F) {
            wrapped += 360.0F;
        }
        return wrapped;
    }

    private static float clampPitch(float pitch) {
        return Math.max(-90.0F, Math.min(pitch, 90.0F));
    }

    public record Rotation(float yaw, float pitch) {
    }
}
