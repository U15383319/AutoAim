package dev.cosmos.autoaim.client.aim;

/** Monotonic time when the client last processed a position-codec update. */
public interface PositionPacketTiming {
    long autoaim$getUpdatedAtNanos();
}
