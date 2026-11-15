package dev.cosmos.autoaim.client.aim;

import dev.cosmos.autoaim.mixin.client.VecDeltaCodecAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.WeakHashMap;

/** Only sustained, recently observed gun-target movement receives a lead. */
public final class TargetMotionTracker {
    private final Map<Entity, History> histories = new WeakHashMap<>();

    public void observe(Entity entity) {
        History history = histories.computeIfAbsent(entity, ignored -> new History());
        history.observe(entity.tickCount, entity.position());
        if (entity.level().isClientSide && entity.getPositionCodec() instanceof VecDeltaCodecAccessor accessor) {
            long updatedAt = entity.getPositionCodec() instanceof PositionPacketTiming timing
                    ? timing.autoaim$getUpdatedAtNanos() : 0;
            if (updatedAt != history.packetNanos || history.packetPosition == null) {
                history.observePacket(entity.tickCount, accessor.autoaim$getPacketPosition());
                history.packetNanos = updatedAt;
            }
        }
    }

    public TargetMotion motionOf(Entity entity) {
        return motionOf(entity, 0);
    }

    public TargetMotion motionOf(Entity entity, double networkRoundTripTicks) {
        observe(entity);
        History history = histories.get(entity);
        if (!entity.level().isClientSide) return history.snapshot(entity.onGround());
        TargetMotion motion = history.packetSnapshot(entity.onGround(), entity.tickCount, entity.getType().updateInterval());
        if (entity instanceof LivingEntity && history.packetPosition != null && history.packetNanos != 0) {
            double measuredAge = Math.max(0, System.nanoTime() - history.packetNanos) / 50_000_000.0;
            motion = motion.withVerticalObservation(history.packetPosition.y - entity.getY(),
                    measuredAge + Math.max(0, networkRoundTripTicks));
        }
        return motion;
    }

    public void clear() {
        histories.clear();
    }

    static final class History {
        private static final int WINDOW = 3;
        private final ArrayDeque<Vec3> movements = new ArrayDeque<>();
        private final ArrayDeque<PacketSample> packets = new ArrayDeque<>();
        private long lastTick = Long.MIN_VALUE;
        private Vec3 lastPosition;
        private Vec3 packetPosition;
        private long packetTick;
        private long packetNanos;

        void observePacket(long tick, Vec3 position) {
            if (packetPosition != null && position.distanceToSqr(packetPosition) > 16 * 16) packets.clear();
            if (!packets.isEmpty() && packets.getLast().tick == tick) packets.removeLast();
            packets.addLast(new PacketSample(tick, position));
            if (packets.size() > WINDOW + 1) packets.removeFirst();
            packetPosition = position;
            packetTick = tick;
        }

        TargetMotion packetSnapshot(boolean grounded) {
            PacketSample[] samples = packets.toArray(PacketSample[]::new);
            return TargetMotion.trackedGun(new Vec3(packetVelocity(samples, 0),
                    packetVelocity(samples, 1), packetVelocity(samples, 2)), grounded);
        }

        private static double packetVelocity(PacketSample[] samples, int axis) {
            if (samples.length < WINDOW + 1) return 0;
            double lower = Double.NEGATIVE_INFINITY;
            double upper = Double.POSITIVE_INFINITY;
            for (int i = 1; i < samples.length; i++) {
                double interval = Math.max(1, samples[i].tick - samples[i - 1].tick);
                double displacement = component(samples[i].position.subtract(samples[i - 1].position), axis);
                // Observations are timestamped once per client tick, so each
                // interval has one tick of quantization uncertainty. This is
                // a measurement bound, not an assumed age added to the aim.
                double first = displacement / (interval + 1);
                double second = displacement / Math.max(1, interval - 1);
                lower = Math.max(lower, Math.min(first, second) - 0.0005);
                upper = Math.min(upper, Math.max(first, second) + 0.0005);
            }
            // No common velocity explains the observations: stop this axis.
            if (lower > upper) return 0;
            double elapsed = samples[samples.length - 1].tick - samples[0].tick;
            double average = component(samples[samples.length - 1].position.subtract(samples[0].position), axis)
                    / Math.max(1, elapsed);
            double velocity = Math.max(lower, Math.min(upper, average));
            return Math.abs(velocity) < 0.001 ? 0 : velocity;
        }

        TargetMotion packetSnapshot(boolean grounded, long tick, int updateInterval) {
            return packetPosition == null || tick - packetTick > Math.max(3, updateInterval + 1)
                    ? TargetMotion.trackedGun(Vec3.ZERO, grounded) : packetSnapshot(grounded);
        }

        void observe(long tick, Vec3 position) {
            if (tick == lastTick) return;
            if (lastPosition != null) {
                Vec3 movement = position.subtract(lastPosition);
                if (tick != lastTick + 1 || movement.lengthSqr() > 16 * 16) {
                    movements.clear();
                    packets.clear();
                    packetPosition = null;
                } else {
                    movements.addLast(movement);
                    if (movements.size() > WINDOW) movements.removeFirst();
                }
            }
            lastTick = tick;
            lastPosition = position;
        }

        TargetMotion snapshot(boolean grounded) {
            return conservative(movements.toArray(Vec3[]::new), grounded);
        }

        private static TargetMotion conservative(Vec3[] samples, boolean grounded) {
            return TargetMotion.trackedGun(new Vec3(stableVelocity(samples, 0),
                    stableVelocity(samples, 1), stableVelocity(samples, 2)), grounded);
        }

        private static double stableVelocity(Vec3[] samples, int axis) {
            if (samples.length < WINDOW) return 0;
            double latest = component(samples[samples.length - 1], axis);
            double tolerance = Math.max(0.005, Math.abs(latest) * 0.1);
            for (Vec3 sample : samples) {
                double previous = component(sample, axis);
                // Uncertain motion disables this axis. Do not keep a median
                // of old speeds and extrapolate it through a stop or turn.
                if (previous * latest < 0 || Math.abs(previous - latest) > tolerance) return 0;
            }
            return Math.abs(latest) < 0.001 ? 0 : latest;
        }

        private static double component(Vec3 value, int axis) {
            return axis == 0 ? value.x : axis == 1 ? value.y : value.z;
        }

        private record PacketSample(long tick, Vec3 position) {}
    }
}
