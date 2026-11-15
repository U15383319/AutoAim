package dev.cosmos.autoaim.client.aim;

import dev.cosmos.autoaim.client.trajectory.BallisticProfile;
import dev.cosmos.autoaim.client.trajectory.TrajectoryCalculator;
import dev.cosmos.autoaim.client.trajectory.TrajectorySegment;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BallisticAimSolverTest {
    @Test
    void standingTargetDoesNotReceiveFalseDownwardLead() {
        Vec3 lead = TargetMotion.linear(new Vec3(0, -0.0784000015258789, 0), true).lead(8, 0);

        assertEquals(Vec3.ZERO, lead);
    }

    @Test
    void groundedMovingTargetKeepsHorizontalLead() {
        Vec3 lead = TargetMotion.linear(new Vec3(0.1, -0.0784, 0.2), true).lead(8, 0);

        assertEquals(0.8, lead.x, 1.0E-10);
        assertEquals(0.0, lead.y, 1.0E-10);
        assertEquals(1.6, lead.z, 1.0E-10);
    }

    @Test
    void fallingTargetKeepsItsVerticalLead() {
        Vec3 lead = TargetMotion.linear(new Vec3(0.1, -0.2, 0), false).lead(5, 0);

        assertEquals(0.5, lead.x, 1.0E-10);
        assertEquals(-1.0, lead.y, 1.0E-10);
    }

    @Test
    void upwardJumpIsNotSuppressedWhileGroundFlagIsStillSet() {
        Vec3 lead = TargetMotion.linear(new Vec3(0, 0.42, 0), true).lead(2, 0);

        assertEquals(0.84, lead.y, 1.0E-10);
    }

    @Test
    void leadDistanceRemainsBounded() {
        Vec3 lead = TargetMotion.linear(new Vec3(10, 0, 0), false).lead(100, 0);

        assertEquals(8.0, lead.length(), 1.0E-10);
    }

    @Test
    void leadTimeRemainsBounded() {
        Vec3 lead = TargetMotion.linear(new Vec3(0.1, 0, 0), false).lead(100, 0);

        assertEquals(3.0, lead.x, 1.0E-10);
    }

    @Test
    void bulletDoesNotDropWithinItsFirstTick() throws ReflectiveOperationException {
        BallisticProfile bullet = BallisticProfile.kineticBullet(200, 2, 0.15, 0.03, 0.7).orElseThrow();
        List<?> arcs = solveArcs(new Vec3(0, 0, 8), bullet);

        assertFalse(arcs.isEmpty());
        assertEquals(0.0, value(arcs.get(0), "pitch"), 1.0E-6);
        assertEquals(0.4, value(arcs.get(0), "flightTicks"), 1.0E-8);
    }

    @Test
    void dragFreeBulletAlsoFollowsStraightFirstTick() throws ReflectiveOperationException {
        BallisticProfile bullet = BallisticProfile.kineticBullet(200, 2, 0.15, 0, 0.7).orElseThrow();
        List<?> arcs = solveArcs(new Vec3(0, 0, 10), bullet);

        assertFalse(arcs.isEmpty());
        assertEquals(0.0, value(arcs.get(0), "pitch"), 1.0E-6);
    }

    @Test
    void longRangePistolSolutionsMatchIndependentTickSimulation() throws ReflectiveOperationException {
        BallisticProfile bullet = BallisticProfile.kineticBullet(200, 2, 0.15, 0.03, 0.7).orElseThrow();
        for (double distance : new double[]{30, 60, 100, 120}) {
            Vec3 target = new Vec3(0, 0, distance);
            List<?> arcs = solveArcs(target, bullet);
            assertFalse(arcs.isEmpty(), "No pistol solution at " + distance + " blocks");
            Object arc = arcs.get(0);
            double yaw = Math.toRadians(value(arc, "yaw"));
            double pitch = Math.toRadians(value(arc, "pitch"));
            Vec3 velocity = new Vec3(
                    -Math.sin(yaw) * Math.cos(pitch),
                    -Math.sin(pitch),
                    Math.cos(yaw) * Math.cos(pitch)
            ).scale(bullet.speed());
            Vec3 position = Vec3.ZERO;
            double flightTicks = value(arc, "flightTicks");
            int ticks = (int) Math.floor(flightTicks);
            for (int tick = 0; tick < ticks; tick++) {
                position = position.add(velocity);
                velocity = velocity.scale(bullet.airDrag()).add(0, -bullet.gravity(), 0);
            }
            position = position.add(velocity.scale(flightTicks - ticks));
            assertTrue(position.distanceTo(target) < 1.0E-4,
                    "Pistol trajectory must reach the selected point at " + distance + " blocks: " + position);
        }
    }

    @Test
    void gunTargetsUseExactEyePositionWithoutTorsoFallbacks() {
        AABB box = new AABB(-0.3, 0, 30, 0.3, 1.8, 30.6);
        Vec3 eyes = new Vec3(0, 1.62, 30.3);

        assertEquals(List.of(eyes), BallisticAimSolver.targetAimPoints(box, eyes, true));
    }

    @Test
    void gunTargetsRespectLowerEyePositions() {
        AABB proneBox = new AABB(-0.3, 0, 30, 0.3, 0.6, 30.6);
        Vec3 proneEyes = new Vec3(0, 0.4, 30.3);

        assertEquals(List.of(proneEyes), BallisticAimSolver.targetAimPoints(proneBox, proneEyes, true));
    }

    @Test
    void nonLivingGunTargetsUseBoxCenter() {
        AABB box = new AABB(-1, 0, 29, 1, 2, 31);

        assertEquals(List.of(box.getCenter()), BallisticAimSolver.targetAimPoints(box, null, true));
    }

    @Test
    void otherProjectilesRetainTheirBodyAimPoints() {
        AABB box = new AABB(-0.3, 0, 30, 0.3, 2, 30.6);
        Vec3 eyes = new Vec3(0, 1.8, 30.3);
        List<Vec3> points = BallisticAimSolver.targetAimPoints(box, eyes, false);

        assertEquals(3, points.size());
        assertEquals(1.1, points.get(0).y, 1.0E-8);
        assertEquals(1.5, points.get(1).y, 1.0E-8);
        assertEquals(0.7, points.get(2).y, 1.0E-8);
    }

    @Test
    void torsoIntersectionDoesNotValidateEyeShot() {
        AABB box = new AABB(-0.3, 0, 30, 0.3, 1.8, 30.6);
        Vec3 eyes = new Vec3(0, 1.62, 30.3);
        List<TrajectorySegment> torsoPath = List.of(
                new TrajectorySegment(new Vec3(0, 0.9, 0), new Vec3(0, 0.9, 31), 0)
        );

        assertTrue(TrajectoryCalculator.findIntersection(torsoPath,
                BallisticAimSolver.targetHitBounds(box, eyes, false)).isPresent());
        assertTrue(TrajectoryCalculator.findIntersection(torsoPath,
                BallisticAimSolver.targetHitBounds(box, eyes, true)).isEmpty());
    }

    @Test
    void ballisticArcAimsAtSelectedEyeHeight() throws ReflectiveOperationException {
        AABB box = new AABB(-0.3, 0, 30, 0.3, 1.8, 30.6);
        Vec3 eyes = new Vec3(0, 1.62, 30.3);
        Vec3 point = BallisticAimSolver.targetAimPoints(box, eyes, true).get(0);
        BallisticProfile bullet = BallisticProfile.kineticBullet(600, 2, 0, 0, 10).orElseThrow();
        List<?> arcs = solveArcs(point, bullet);

        assertFalse(arcs.isEmpty());
        assertEquals(-Math.toDegrees(Math.atan2(1.62, 30.3)), value(arcs.get(0), "pitch"), 1.0E-5D);
    }

    @Test
    void findsNearbyTargetBeforeOldMinimumFlightTime() throws ReflectiveOperationException {
        BallisticProfile bullet = BallisticProfile.kineticBullet(600, 2, 0, 0, 10).orElseThrow();
        List<?> arcs = solveArcs(new Vec3(0, 0, 0.3), bullet);

        assertFalse(arcs.isEmpty(), "A fast bullet must still aim at a nearby target");
        assertEquals(0.005D, value(arcs.get(0), "flightTicks"), 1.0E-8D);
        assertEquals(0.0D, value(arcs.get(0), "yaw"), 1.0E-6D);
        assertEquals(0.0D, value(arcs.get(0), "pitch"), 1.0E-6D);
    }

    @Test
    void cannotAimBeyondBulletLifetime() throws ReflectiveOperationException {
        BallisticProfile bullet = BallisticProfile.kineticBullet(300, 2, 0, 0, 0.1).orElseThrow();
        assertTrue(solveArcs(new Vec3(0, 0, 60), bullet).isEmpty());
    }

    @Test
    void includesSolutionExactlyAtFlightLimit() throws ReflectiveOperationException {
        BallisticProfile bullet = BallisticProfile.kineticBullet(300, 2, 0, 0, 0.1).orElseThrow();
        List<?> arcs = solveArcs(new Vec3(0, 0, 30), bullet);

        assertFalse(arcs.isEmpty());
        assertEquals(1.0D, value(arcs.get(0), "flightTicks"), 1.0E-8D);
    }

    @Test
    void vanillaArrowsStillCompensateForDrop() throws ReflectiveOperationException {
        BallisticProfile arrow = new BallisticProfile(3, 0.05, 0.99, 0.6, true);
        List<?> arcs = solveArcs(new Vec3(0, 0, 30), arrow);

        assertFalse(arcs.isEmpty());
        assertTrue(value(arcs.get(0), "pitch") < 0.0D, "Aim above a distant target to compensate for gravity");
        assertTrue(value(arcs.get(0), "flightTicks") > 10.0D, "Drag slows the arrow");
    }

    private static List<?> solveArcs(Vec3 target, BallisticProfile profile) throws ReflectiveOperationException {
        Method solve = BallisticAimSolver.class.getDeclaredMethod(
                "solveArcs", Vec3.class, Vec3.class, Vec3.class, BallisticProfile.class
        );
        solve.setAccessible(true);
        return (List<?>) solve.invoke(null, Vec3.ZERO, target, Vec3.ZERO, profile);
    }

    private static double value(Object arc, String component) throws ReflectiveOperationException {
        Method accessor = arc.getClass().getDeclaredMethod(component);
        accessor.setAccessible(true);
        return ((Number) accessor.invoke(arc)).doubleValue();
    }
}
