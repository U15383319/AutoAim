package dev.cosmos.autoaim.client.trajectory;

import dev.cosmos.autoaim.client.AutoAimSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BallisticProfileTest {
    @Test
    void matchesTaczFloatLifetimeRounding() {
        double lifeFromTaczFloat = (double) 0.7F;
        BallisticProfile profile = BallisticProfile.kineticBullet(200, 2, 0.15, 0.03, lifeFromTaczFloat)
                .orElseThrow();

        assertEquals(13, profile.maxFlightTicks());
    }

    @Test
    void convertsGunSpeedAndMatchesKineticBulletPhysics() {
        BallisticProfile profile = BallisticProfile.kineticBullet(450.0D, 2.0D, 0.01D, 0.02D, 10.0D)
                .orElseThrow();

        assertEquals(45.0D, profile.speed());
        assertEquals(0.01D, profile.gravity());
        assertEquals(0.98D, profile.airDrag());
        assertEquals(0.6D, profile.waterDrag());
        assertEquals(0.6D, profile.waterGravityMultiplier());
        assertTrue(profile.inheritsShooterMovement());
        assertTrue(profile.interpolatedLaunchPosition());
        assertEquals(199, profile.maxFlightTicks());
    }

    @Test
    void usesAttachmentSpeedAndGlobalSpeedMultiplier() {
        BallisticProfile base = BallisticProfile.kineticBullet(300.0D, 2.0D, 0.0D, 0.01D, 10.0D).orElseThrow();
        BallisticProfile modified = BallisticProfile.kineticBullet(450.0D, 3.0D, 0.0D, 0.01D, 10.0D).orElseThrow();

        assertEquals(30.0D, base.speed());
        assertEquals(67.5D, modified.speed());
    }

    @Test
    void limitsFlightToBulletLifetimeAndModBudget() {
        assertEquals(1, BallisticProfile.kineticBullet(300, 2, 0, 0, 0.01).orElseThrow().maxFlightTicks());
        assertEquals(9, BallisticProfile.kineticBullet(300, 2, 0, 0, 0.5).orElseThrow().maxFlightTicks());
        assertEquals(AutoAimSettings.MAX_FLIGHT_TICKS,
                BallisticProfile.kineticBullet(300, 2, 0, 0, 60).orElseThrow().maxFlightTicks());
    }

    @Test
    void rejectsInvalidOrUnsupportedBulletData() {
        assertTrue(BallisticProfile.kineticBullet(0, 2, 0, 0, 10).isEmpty());
        assertTrue(BallisticProfile.kineticBullet(-300, 2, 0, 0, 10).isEmpty());
        assertTrue(BallisticProfile.kineticBullet(300, -2, 0, 0, 10).isEmpty());
        assertTrue(BallisticProfile.kineticBullet(300, 2, -1, 0, 10).isEmpty());
        assertTrue(BallisticProfile.kineticBullet(300, 2, 0, -0.1, 10).isEmpty());
        assertTrue(BallisticProfile.kineticBullet(300, 2, 0, 1, 10).isEmpty());
        assertTrue(BallisticProfile.kineticBullet(300, 2, 0, 0, 0).isEmpty());
        assertTrue(BallisticProfile.kineticBullet(Double.NaN, 2, 0, 0, 10).isEmpty());
        assertTrue(BallisticProfile.kineticBullet(300, Double.POSITIVE_INFINITY, 0, 0, 10).isEmpty());
        assertTrue(BallisticProfile.kineticBullet(300, 2, Double.NaN, 0, 10).isEmpty());
        assertTrue(BallisticProfile.kineticBullet(300, 2, 0, Double.NaN, 10).isEmpty());
        assertTrue(BallisticProfile.kineticBullet(300, 2, 0, 0, Double.POSITIVE_INFINITY).isEmpty());
    }

    @Test
    void preservesVanillaAndPotionDefaults() {
        BallisticProfile arrow = new BallisticProfile(3, 0.05, 0.99, 0.6, true);
        BallisticProfile potion = new BallisticProfile(0.5, 0.05, 0.99, 0.8, true, -20.0F);

        for (BallisticProfile profile : new BallisticProfile[]{arrow, potion}) {
            assertEquals(1.0D, profile.waterGravityMultiplier());
            assertEquals(AutoAimSettings.MAX_FLIGHT_TICKS, profile.maxFlightTicks());
            assertFalse(profile.interpolatedLaunchPosition());
        }
        assertEquals(0.0F, arrow.verticalLaunchAngleOffsetDegrees());
        assertEquals(-20.0F, potion.verticalLaunchAngleOffsetDegrees());
    }
}
