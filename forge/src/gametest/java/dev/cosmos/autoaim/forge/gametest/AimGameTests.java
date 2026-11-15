package dev.cosmos.autoaim.forge.gametest;

import dev.cosmos.autoaim.AutoAim;
import dev.cosmos.autoaim.client.aim.AimSolution;
import dev.cosmos.autoaim.client.aim.BallisticAimSolver;
import dev.cosmos.autoaim.client.trajectory.BallisticProfile;
import dev.cosmos.autoaim.client.trajectory.TrajectoryCalculator;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GlobalTestReporter;
import net.minecraft.gametest.framework.JUnitLikeTestReporter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.io.File;
import javax.xml.parsers.ParserConfigurationException;

@GameTestHolder(AutoAim.MOD_ID)
public final class AimGameTests {
    private static final Set<Long> FORCED_CHUNKS = new HashSet<>();
    private AimGameTests() {
    }

    @BeforeBatch(batch = "autoaim")
    public static void configureReport(ServerLevel level) throws ParserConfigurationException {
        String destination = System.getProperty("autoaim.gametest.report");
        if (destination != null) {
            File report = new File(destination);
            report.getParentFile().mkdirs();
            GlobalTestReporter.replaceWith(new JUnitLikeTestReporter(report));
        }
    }

    @GameTestGenerator
    public static Collection<TestFunction> worldTests() {
        Collection<TestFunction> tests = new ArrayList<>();
        for (int distance : new int[]{4, 30, 60, 100, 120}) {
            tests.add(test("wither_eye_path_" + distance, helper -> {
                Player shooter = shooter(helper);
                WitherBoss target = target(helper, EntityType.WITHER, distance);
                BallisticProfile profile = BallisticProfile.kineticBullet(200, 2, 0.15, 0.03, 0.7).orElseThrow();
                AimSolution aim = BallisticAimSolver.solve(helper.getLevel(), shooter, target, profile, true)
                        .orElseThrow(() -> new GameTestAssertException("No Wither eye solution at " + distance));
                var path = TrajectoryCalculator.traceIgnoringEntities(helper.getLevel(), shooter, profile, aim.pitch(), aim.yaw());
                AABB eyeRegion = new AABB(target.getEyePosition(), target.getEyePosition()).inflate(0.1);
                helper.assertTrue(TrajectoryCalculator.findIntersection(path.segments(), eyeRegion).isPresent(),
                        "Predicted bullet misses the real Wither's eye position");
                AutoAim.LOGGER.info("GameTest Wither range={} eyeHeight={} pitch={} flightTicks={}",
                        distance, target.getEyeHeight(), aim.pitch(), aim.flightTicks());
                target.discard();
                helper.succeed();
            }));
        }
        tests.add(test("wither_eye_blocked", helper -> {
            Player shooter = shooter(helper);
            WitherBoss target = target(helper, EntityType.WITHER, 30);
            BlockPos base = helper.absolutePos(new BlockPos(18, 0, 6));
            for (int y = 0; y < 12; y++) {
                for (int z = -2; z <= 2; z++) {
                    helper.getLevel().setBlockAndUpdate(base.offset(0, y, z), Blocks.STONE.defaultBlockState());
                }
            }
            BallisticProfile profile = BallisticProfile.kineticBullet(200, 2, 0.15, 0.03, 0.7).orElseThrow();
            helper.assertTrue(BallisticAimSolver.solve(helper.getLevel(), shooter, target, profile, true).isEmpty(),
                    "A solid wall must prevent eye targeting");
            target.discard();
            helper.succeed();
        }));
        return tests;
    }

    static TestFunction test(String name, java.util.function.Consumer<GameTestHelper> body) {
        return new TestFunction("autoaim", "autoaim:" + name, "autoaim:range", 200, 0, true, helper -> {
            BlockPos start = helper.absolutePos(new BlockPos(0, 0, 0));
            BlockPos end = helper.absolutePos(new BlockPos(136, 0, 12));
            for (int x = (start.getX() >> 4) - 1; x <= (end.getX() >> 4) + 1; x++) {
                for (int z = (start.getZ() >> 4) - 1; z <= (end.getZ() >> 4) + 1; z++) {
                    long chunk = ChunkPos.asLong(x, z);
                    if (!helper.getLevel().getForcedChunks().contains(chunk)) {
                        helper.getLevel().setChunkForced(x, z, true);
                        FORCED_CHUNKS.add(chunk);
                    }
                }
            }
            helper.startSequence().thenWaitUntil(() -> {
                for (int x = start.getX() >> 4; x <= end.getX() >> 4; x++) {
                    for (int z = start.getZ() >> 4; z <= end.getZ() >> 4; z++) {
                        helper.assertTrue(helper.getLevel().isPositionEntityTicking(new BlockPos(x << 4, start.getY(), z << 4)),
                                "Waiting for the entire shooting range to tick entities");
                    }
                }
            }).thenExecute(() -> body.accept(helper));
        });
    }

    @AfterBatch(batch = "autoaim")
    public static void releaseChunks(ServerLevel level) {
        for (long chunk : FORCED_CHUNKS) {
            ChunkPos position = new ChunkPos(chunk);
            level.setChunkForced(position.x, position.z, false);
        }
        FORCED_CHUNKS.clear();
    }

    static Player shooter(GameTestHelper helper) {
        Player shooter = helper.makeMockPlayer();
        Vec3 position = helper.absoluteVec(new Vec3(4.5, 3, 6.5));
        shooter.setPos(position);
        shooter.xOld = position.x;
        shooter.yOld = position.y;
        shooter.zOld = position.z;
        shooter.setDeltaMovement(Vec3.ZERO);
        shooter.setOnGround(true);
        return shooter;
    }

    static <T extends Mob> T target(GameTestHelper helper, EntityType<T> type, int distance) {
        T target = helper.spawn(type, new Vec3(4.5 + distance, 3, 6.5));
        target.setNoAi(true);
        target.setNoGravity(true);
        target.setPersistenceRequired();
        target.setDeltaMovement(Vec3.ZERO);
        if (target instanceof WitherBoss wither) {
            wither.setInvulnerableTicks(0);
        }
        return target;
    }
}
