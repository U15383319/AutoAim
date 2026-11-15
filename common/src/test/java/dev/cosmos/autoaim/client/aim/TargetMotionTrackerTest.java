package dev.cosmos.autoaim.client.aim;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TargetMotionTrackerTest {
    @Test
    void noHistoryOrInsufficientSamplesMeansNoPrediction() {
        TargetMotionTracker.History history = new TargetMotionTracker.History();
        history.observePacket(0, Vec3.ZERO);
        history.observePacket(3, new Vec3(0.3, 0.6, 0));
        assertVector(Vec3.ZERO, history.packetSnapshot(false).lead(7, 0));
    }

    @Test
    void stableSparseMovementUsesOnlyActualFractionalFlightTime() {
        TargetMotionTracker.History history = packetHistory(new Vec3(0.05, -0.09, 0.08));
        assertVector(new Vec3(0.225, -0.405, 0.36), history.packetSnapshot(false).lead(4.5, 0));
    }

    @Test
    void oneTickSchedulingJitterDoesNotDisableConsistentWalking() {
        TargetMotionTracker.History history = packetHistory(new Vec3(0, -0.15, 0));
        history.observePacket(16, new Vec3(0, -2.25, 0));
        // Ground truth remains -0.15 blocks/tick. Scheduling noise may reduce
        // confidence, but must keep seven-tick error inside a headshot margin.
        assertEquals(-1.05, history.packetSnapshot(false).lead(7, 0).y, 0.15);
    }

    @Test
    void incompatiblePacketVelocitiesDoNotFallbackToAHistoricalMedian() {
        TargetMotionTracker.History history = packetHistory(new Vec3(0, -0.15, 0));
        history.observePacket(15, new Vec3(0, -1.85, 0));
        assertVector(Vec3.ZERO, history.packetSnapshot(false).lead(7, 0));
    }

    @Test
    void renderedInterpolationPauseDoesNotChangeStablePacketVelocity() {
        TargetMotionTracker.History history = packetHistory(new Vec3(0, -0.09, 0.08));
        history.observe(12, new Vec3(0, -1.08, 0.96));
        history.observe(13, new Vec3(0, -1.08, 0.96));
        assertVector(new Vec3(0, -0.45, 0.4), history.packetSnapshot(false, 13, 3).lead(5, 0));
    }

    @Test
    void stalePacketAfterAStopDoesNotKeepLeadingOldMovement() {
        TargetMotionTracker.History history = packetHistory(new Vec3(0.1, 0, 0));
        assertVector(Vec3.ZERO, history.packetSnapshot(false, 17, 3).lead(7, 0));
    }

    @Test
    void directionChangeDisablesLeadImmediatelyInsteadOfFollowingOldDirection() {
        TargetMotionTracker.History history = packetHistory(new Vec3(0.1, 0, 0));
        history.observePacket(15, new Vec3(0.9, 0, 0));
        assertVector(Vec3.ZERO, history.packetSnapshot(false).lead(7, 0));
    }

    @Test
    void changingVerticalMotionDoesNotDisableStableHorizontalMotion() {
        TargetMotionTracker.History history = packetHistory(new Vec3(0.1, 0.1, 0));
        history.observePacket(15, new Vec3(1.5, 1.05, 0));
        assertVector(new Vec3(0.5, 0, 0), history.packetSnapshot(false).lead(5, 0));
    }

    @Test
    void jumpingAndFallingDoNotExtrapolateAnEstimatedAcceleration() {
        TargetMotionTracker.History history = new TargetMotionTracker.History();
        Vec3 position = Vec3.ZERO;
        history.observe(0, position);
        double speed = 0.42;
        for (int tick = 1; tick <= 9; tick++) {
            position = position.add(0, speed, 0);
            history.observe(tick, position);
            speed = (speed - 0.08) * 0.98;
        }
        assertVector(Vec3.ZERO, history.snapshot(false).lead(8, 0));
    }

    @Test
    void deceleratingRiseStopsPredictingUpwardBeforeTheApex() {
        TargetMotionTracker.History history = new TargetMotionTracker.History();
        Vec3 position = Vec3.ZERO;
        history.observe(0, position);
        double speed = 0.8;
        for (int tick = 1; tick <= 5; tick++) {
            speed *= 0.72;
            position = position.add(0, speed, 0);
            history.observe(tick, position);
        }
        assertVector(Vec3.ZERO, history.snapshot(false).lead(8, 0));
    }

    @Test
    void stableMovementDoesNotExtrapolateBeyondGunDistanceAndTimeCaps() {
        assertEquals(8, TargetMotion.trackedGun(new Vec3(0.5, 0, 0), false).lead(100, 0).length(), 1.0E-8);
        assertEquals(1.5, TargetMotion.trackedGun(new Vec3(0.05, 0, 0), false).lead(100, 0).x, 1.0E-8);
    }

    @Test
    void vanillaLinearLeadDoesNotDependOnGunObservationConfidence() {
        assertEquals(3, TargetMotion.linear(new Vec3(0.1, 0, 0), false).lead(30, 0).x, 1.0E-8);
    }

    @Test
    void firingUsesMeasuredSampleAgeInsteadOfAFixedTwoTickDelay() {
        TargetMotion motion = TargetMotion.trackedGun(new Vec3(0, -0.1, 0), false);
        assertEquals(-0.6, motion.withVerticalObservation(-0.2, 0).lead(2.25, 0).y, 1.0E-8);
        assertEquals(-0.75, motion.withVerticalObservation(-0.2, 1.5).lead(2.25, 0).y, 1.0E-8);
    }

    @Test
    void uncertainVerticalMotionUsesObservedHeightWithoutInventingFutureMovement() {
        TargetMotion motion = TargetMotion.trackedGun(new Vec3(0.05, 0, 0), false)
                .withVerticalObservation(-0.3, 3);
        assertVector(new Vec3(0.25, -0.3, 0), motion.lead(5, 0));
    }

    @Test
    void landingUsesTheConfirmedGroundHeightInsteadOfTheStillInterpolatingHeight() {
        TargetMotion motion = TargetMotion.trackedGun(Vec3.ZERO, true)
                .withVerticalObservation(-0.4, 2);
        assertVector(new Vec3(0, -0.4, 0), motion.lead(7, 0));
    }

    @Test
    void oldOrDelayedPacketsCannotCauseAnUnboundedTriggerCorrection() {
        TargetMotion motion = TargetMotion.trackedGun(new Vec3(0, -0.3, 0), false)
                .withVerticalObservation(-100, 100);
        assertTrue(motion.lead(30, 0).length() <= 9);
    }

    @Test
    void steadyFastTargetsAreNotIncorrectlyCappedToOneBlock() {
        TargetMotionTracker.History history = packetHistory(new Vec3(0, 0, 0.3));
        assertVector(new Vec3(0, 0, 1.95), history.packetSnapshot(false).lead(6.5, 0));
    }

    @Test
    void stationaryPositionsProduceNoLead() {
        TargetMotionTracker.History history = new TargetMotionTracker.History();
        for (int tick = 0; tick <= 8; tick++) history.observe(tick, new Vec3(10, 20, 30));
        assertVector(Vec3.ZERO, history.snapshot(false).lead(15, -4.5));
    }

    @Test
    void teleportAndMissingTicksResetMovementHistory() {
        TargetMotionTracker.History history = walkingHistory();
        history.observe(9, new Vec3(100, 0, 0));
        assertVector(Vec3.ZERO, history.snapshot(false).lead(7, 0));
        history.observe(10, new Vec3(100.1, 0, 0));
        history.observe(15, new Vec3(101, 0, 0));
        assertVector(Vec3.ZERO, history.snapshot(false).lead(7, 0));
    }

    @Test
    void repeatedReadsInOneTickDoNotCreateMovementSamples() {
        TargetMotionTracker.History history = walkingHistory();
        TargetMotion before = history.snapshot(false);
        history.observe(8, new Vec3(0.8, 0, 0));
        assertEquals(before, history.snapshot(false));
    }

    @Test
    void suddenStopDoesNotContinueLeadingOldMotion() {
        TargetMotionTracker.History history = walkingHistory();
        history.observe(9, new Vec3(0.8, 0, 0));
        assertVector(Vec3.ZERO, history.snapshot(false).lead(7, 0));
    }

    private static TargetMotionTracker.History packetHistory(Vec3 velocity) {
        TargetMotionTracker.History history = new TargetMotionTracker.History();
        for (int tick = 0; tick <= 12; tick += 3) history.observePacket(tick, velocity.scale(tick));
        return history;
    }

    private static TargetMotionTracker.History walkingHistory() {
        TargetMotionTracker.History history = new TargetMotionTracker.History();
        for (int tick = 0; tick <= 8; tick++) history.observe(tick, new Vec3(tick * 0.1, 0, 0));
        return history;
    }

    private static void assertVector(Vec3 expected, Vec3 actual) {
        assertTrue(expected.distanceTo(actual) < 1.0E-8, () -> expected + " != " + actual);
    }
}
