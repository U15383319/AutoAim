package dev.cosmos.autoaim.forge.clienttest;

import com.google.gson.GsonBuilder;
import com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.entity.EntityKineticBullet;
import dev.cosmos.autoaim.AutoAim;
import dev.cosmos.autoaim.client.AutoAimClient;
import dev.cosmos.autoaim.client.compat.TaczCompat;
import dev.cosmos.autoaim.client.aim.AutoAimController;
import dev.cosmos.autoaim.client.aim.BallisticAimSolver;
import dev.cosmos.autoaim.client.aim.TargetMotion;
import dev.cosmos.autoaim.client.trajectory.TrajectoryCalculator;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Development-only test of the real client lock, TaCZ trigger, packets and server bullet. */
@Mod.EventBusSubscriber(modid = AutoAim.MOD_ID, value = Dist.CLIENT)
public final class TaczClientIntegration {
    private static final List<Scenario> SCENARIOS = new ArrayList<>();
    private static final List<Result> RESULTS = new ArrayList<>();
    private static final long STARTED = System.nanoTime();
    private static String stage = "boot";
    private static int ticks;
    private static int index;
    private static KeyMapping lockKey;
    private static CompletableFuture<Void> setup;
    private static volatile Result current;

    static {
        for (String gun : new String[]{"deagle_golden", "m4a1", "ai_awp"}) {
            for (EntityType<? extends Mob> type : List.<EntityType<? extends Mob>>of(EntityType.WITHER, EntityType.ZOMBIE)) {
                for (int distance : new int[]{60, 120}) {
                    SCENARIOS.add(new Scenario(gun, type, distance, Vec3.ZERO));
                }
            }
        }
        for (EntityType<? extends Mob> type : List.<EntityType<? extends Mob>>of(EntityType.WITHER, EntityType.ZOMBIE)) {
            for (int distance : new int[]{70, 120}) {
                SCENARIOS.add(new Scenario("deagle_golden", type, distance, new Vec3(0, 0, 0.12)));
                SCENARIOS.add(new Scenario("deagle_golden", type, distance, new Vec3(0, 0.02, 0.08)));
                SCENARIOS.add(new Scenario("deagle_golden", type, distance, new Vec3(0, -0.15, 0.08)));
            }
        }
        for (int distance : new int[]{60, 120}) {
            for (Movement movement : new Movement[]{Movement.WALK, Movement.JUMP, Movement.FALL, Movement.TURN}) {
                SCENARIOS.add(new Scenario("deagle_golden", EntityType.ZOMBIE, distance, Vec3.ZERO, movement));
            }
        }
        String selected = System.getProperty("autoaim.clienttest.case", "all");
        if (selected.equals("vanilla")) {
            SCENARIOS.clear();
        } else if (selected.equals("natural")) {
            SCENARIOS.removeIf(scenario -> scenario.movement == Movement.FIXED);
        } else if (selected.equals("accuracy")) {
            SCENARIOS.removeIf(scenario -> scenario.movement != Movement.FIXED);
        } else if (!selected.equals("all")) {
            Scenario scenario = SCENARIOS.get(Integer.parseInt(selected));
            SCENARIOS.clear();
            SCENARIOS.add(scenario);
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("autoaim.clienttest.enabled")) return;
        if (event.phase != TickEvent.Phase.END || stage.equals("done")) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        try {
            if ((System.nanoTime() - STARTED) / 1.0E9 > 360) {
                throw new IllegalStateException("Client integration timed out in " + stage);
            }
            if (stage.equals("boot")) {
                if (mc.screen instanceof AccessibilityOnboardingScreen) {
                    mc.screen.onClose();
                    return;
                }
                if (mc.screen instanceof TitleScreen) {
                    mc.options.pauseOnLostFocus = false;
                    mc.options.enableVsync().set(false);
                    mc.options.framerateLimit().set(60);
                    mc.options.renderDistance().set(8);
                    var field = AutoAimClient.class.getDeclaredField("HOLD_AUTO_AIM");
                    field.setAccessible(true);
                    lockKey = (KeyMapping) field.get(null);
                    stage = "world";
                    AutoAim.LOGGER.info("CLIENT AIM TEST: loading isolated world");
                    mc.createWorldOpenFlows().loadLevel(mc.screen, "autoaim-test");
                }
                return;
            }
            if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || mc.screen != null) {
                return;
            }
            mc.setWindowActive(true);
            if (stage.equals("world") || stage.equals("next")) {
                if (index == SCENARIOS.size()) {
                    finish(mc, null);
                    return;
                }
                lockKey.setDown(false);
                Scenario scenario = SCENARIOS.get(index);
                current = new Result(scenario.gun, BuiltInRegistries.ENTITY_TYPE.getKey(scenario.type).toString(), scenario.distance, scenario.motion);
                current.movement = scenario.movement;
                current.requirement = scenario.movement == Movement.FIXED ? "actual_server_headshot" : "bounded_camera_tracking";
                stage = "setup";
                ticks = 0;
                setup = mc.getSingleplayerServer().submitAsync(() -> setupScenario(mc, scenario));
                return;
            }
            if (stage.equals("setup")) {
                if (!setup.isDone()) return;
                setup.join();
                var target = mc.level.getEntity(current.targetId);
                if (target == null || TaczCompat.findWeapon(mc.player, mc.player.getMainHandItem(),
                        net.minecraft.world.InteractionHand.MAIN_HAND).isEmpty()) {
                    if (++ticks > 600) throw new IllegalStateException("Target/gun did not sync");
                    return;
                }
                Vec3 direction = target.getEyePosition().subtract(mc.player.getEyePosition());
                mc.player.setYRot((float) -Math.toDegrees(Math.atan2(direction.x, direction.z)));
                mc.player.setXRot((float) -Math.toDegrees(Math.atan2(direction.y, direction.horizontalDistance())));
                var initialProfile = TaczCompat.findWeapon(mc.player, mc.player.getMainHandItem(),
                        net.minecraft.world.InteractionHand.MAIN_HAND).orElseThrow().ballistics();
                BallisticAimSolver.solve(mc.level, mc.player, target, initialProfile, true,
                        TargetMotion.trackedGun(Vec3.ZERO, target.onGround())).ifPresent(aim -> {
                    mc.player.setYRot(aim.yaw());
                    mc.player.setXRot(aim.pitch());
                });
                IClientPlayerGunOperator.fromLocalPlayer(mc.player).aim(true);
                lockKey.setDown(true);
                stage = "aim";
                ticks = 0;
                return;
            }
            if (stage.equals("aim")) {
                lockKey.setDown(true);
                if (ticks >= 10) sampleTracking(mc);
                if (++ticks < 50) return;
                if (!AutoAimClient.hasActiveTaczLock()) {
                    var controllerField = AutoAimClient.class.getDeclaredField("PROJECTILE_AIM_CONTROLLER");
                    controllerField.setAccessible(true);
                    var controller = (dev.cosmos.autoaim.client.aim.AutoAimController) controllerField.get(null);
                    AutoAim.LOGGER.error("CLIENT LOCK DIAGNOSTIC: key={} active={} paused={} player={} rotation={},{} target={} solution={} gun={}",
                            lockKey.isDown(), mc.isWindowActive(), mc.isPaused(), mc.player.position(), mc.player.getYRot(), mc.player.getXRot(),
                            controller.lockedTarget(), controller.aimSolution(mc.player), mc.player.getMainHandItem());
                    Screenshot.grab(mc.gameDirectory, "failed-lock.png", mc.getMainRenderTarget(), ignored -> {});
                    throw new IllegalStateException("No active client lock for " + current.gun + " at " + current.distance);
                }
                current.clientPitch = mc.player.getXRot();
                current.clientYaw = mc.player.getYRot();
                current.profile = TaczCompat.findWeapon(mc.player, mc.player.getMainHandItem(),
                        net.minecraft.world.InteractionHand.MAIN_HAND).orElseThrow().ballistics().toString();
                mc.getSingleplayerServer().execute(() -> {
                    ServerPlayer player = mc.getSingleplayerServer().getPlayerList().getPlayer(current.playerUuid);
                    Map<?, Float> inaccuracy = IGunOperator.fromLivingEntity(player).getCacheProperty().getCache("inaccuracy");
                    inaccuracy.replaceAll((key, value) -> 0F);
                });
                stage = "trigger";
                ticks = 0;
                return;
            }
            if (stage.equals("trigger")) {
                sampleTracking(mc);
                if (++ticks < 3) return;
                ShootResult fired = IClientPlayerGunOperator.fromLocalPlayer(mc.player).shoot();
                if (fired != ShootResult.SUCCESS) {
                    throw new IllegalStateException("Client trigger rejected: " + fired);
                }
                var trackedTarget = mc.level.getEntity(current.targetId);
                var controllerField = AutoAimClient.class.getDeclaredField("PROJECTILE_AIM_CONTROLLER");
                controllerField.setAccessible(true);
                var trackerField = dev.cosmos.autoaim.client.aim.AutoAimController.class.getDeclaredField("motionTracker");
                trackerField.setAccessible(true);
                var tracker = (dev.cosmos.autoaim.client.aim.TargetMotionTracker) trackerField.get(controllerField.get(null));
                var playerInfo = mc.player.connection.getPlayerInfo(mc.player.getUUID());
                current.networkRoundTripMillis = playerInfo == null ? 0 : Math.max(0, playerInfo.getLatency());
                current.motionModel = tracker.motionOf(trackedTarget, current.networkRoundTripMillis / 50.0D).toString();
                var historiesField = dev.cosmos.autoaim.client.aim.TargetMotionTracker.class.getDeclaredField("histories");
                historiesField.setAccessible(true);
                var history = ((Map<?, ?>) historiesField.get(tracker)).get(trackedTarget);
                var packetsField = history.getClass().getDeclaredField("packets");
                packetsField.setAccessible(true);
                current.packetHistory = packetsField.get(history).toString();
                current.clientTargetAtShot = trackedTarget.getEyePosition().toString();
                if (current.gun.equals("deagle_golden") && current.distance == 120) {
                    Screenshot.grab(mc.gameDirectory, "aim-" + index + ".png", mc.getMainRenderTarget(), ignored -> {});
                }
                AutoAim.LOGGER.info("CLIENT AIM TEST: fired {} target={} distance={} clientPitch={}",
                        current.gun, current.target, current.distance, current.clientPitch);
                stage = "impact";
                ticks = 0;
                return;
            }
            if (stage.equals("impact") && ++ticks >= 25) {
                current.passed = current.movement == Movement.FIXED ? current.hit && current.headshot
                        && (current.motion.y >= 0 || current.minTargetY < 199.5)
                        && (current.motion.y <= 0 || current.maxTargetY > 200.2)
                        : current.trackingSamples >= 30 && current.maxTrackingError <= 1.5
                        && switch (current.movement) {
                            case JUMP -> current.maxTargetY > 200.2;
                            case FALL -> current.minTargetY < 199;
                            case WALK, TURN -> current.maxTargetZ - current.minTargetZ > 0.2;
                            default -> false;
                        };
                RESULTS.add(current);
                AutoAim.LOGGER.info("CLIENT AIM TEST RESULT: {}", new GsonBuilder().create().toJson(current));
                Result completed = current;
                mc.getSingleplayerServer().execute(() -> {
                    var target = mc.getSingleplayerServer().overworld().getEntity(completed.targetUuid);
                    if (target != null) target.discard();
                });
                lockKey.setDown(false);
                index++;
                stage = "next";
            } else if (stage.equals("impact")) {
                sampleTracking(mc);
            }
        } catch (Throwable error) {
            finish(mc, error);
        }
    }

    private static void setupScenario(Minecraft mc, Scenario scenario) {
        var server = mc.getSingleplayerServer();
        var level = server.overworld();
        ServerPlayer player = server.getPlayerList().getPlayer(mc.player.getUUID());
        current.playerUuid = player.getUUID();
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        level.setDayTime(6000);
        if (current.targetUuid != null) {
            var previous = level.getEntity(current.targetUuid);
            if (previous != null) previous.discard();
        }
        player.setGameMode(GameType.CREATIVE);
        player.teleportTo(level, 4.5, 200, 126.5, -90, 0);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.setNoGravity(true);
        player.setDeltaMovement(Vec3.ZERO);
        ItemStack gun = new ItemStack(BuiltInRegistries.ITEM.get(new ResourceLocation("tacz", "modern_kinetic_gun")));
        IGun item = IGun.getIGunOrNull(gun);
        item.setGunId(gun, new ResourceLocation("tacz", scenario.gun));
        item.setFireMode(gun, FireMode.SEMI);
        item.setCurrentAmmoCount(gun, 20);
        item.setBulletInBarrel(gun, true);
        player.getInventory().selected = 0;
        player.setItemSlot(EquipmentSlot.MAINHAND, gun);
        player.inventoryMenu.broadcastChanges();
        IGunOperator.fromLivingEntity(player).draw(player::getMainHandItem);
        Mob target = scenario.type.create(level);
        target.setPos(4.5 + scenario.distance, 200, 126.5);
        target.setNoAi(scenario.motion.lengthSqr() == 0);
        if (scenario.movement == Movement.FIXED) {
            target.removeFreeWill();
            target.setNoGravity(true);
        } else {
            target.setNoAi(false);
            for (int x = -1; x <= 145; x++) {
                for (int z = 112; z <= 140; z++) {
                    level.setBlock(new BlockPos(x, 199, z), Blocks.STONE.defaultBlockState(), 2);
                }
            }
            target.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        }
        target.setPersistenceRequired();
        if (target instanceof WitherBoss wither) wither.setInvulnerableTicks(0);
        level.addFreshEntity(target);
        current.targetId = target.getId();
        current.targetUuid = target.getUUID();
    }

    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent event) {
        Result result = current;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (event.phase != TickEvent.Phase.END || result == null || result.targetUuid == null || server == null) return;
        var target = server.overworld().getEntity(result.targetUuid);
        if (target != null) {
            result.serverTicks++;
            result.minTargetY = Math.min(result.minTargetY, target.getY());
            result.maxTargetY = Math.max(result.maxTargetY, target.getY());
            result.minTargetZ = Math.min(result.minTargetZ, target.getZ());
            result.maxTargetZ = Math.max(result.maxTargetZ, target.getZ());
            result.serverTarget = target.getEyePosition().toString();
            if (result.shotServerTick >= 0 && result.serverTicks - result.shotServerTick <= 12) {
                var player = server.getPlayerList().getPlayer(result.playerUuid);
                var box = com.tacz.guns.util.HitboxHelper.getFixedBoundingBox(target, player);
                double eyes = target.getEyeY();
                result.hitboxSamples.add(new HitboxSample(result.serverTicks - result.shotServerTick,
                        eyes, target.getY() - target.yOld, box.minY, box.maxY,
                        box.minY < eyes + 0.25 && box.maxY > eyes - 0.25));
            }
            if (result.movement == Movement.FIXED && result.motion.lengthSqr() > 0) target.setDeltaMovement(result.motion);
            if (result.movement != Movement.FIXED && target instanceof Mob mob) {
                if (result.serverTicks == 35) mob.getNavigation().moveTo(mob.getX(), 200, 137, 1);
                if (result.serverTicks == 50) {
                    if (result.movement == Movement.JUMP) mob.getJumpControl().jump();
                    if (result.movement == Movement.FALL) {
                        BlockPos below = mob.blockPosition().below();
                        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++)
                            server.overworld().setBlock(below.offset(x, 0, z), Blocks.AIR.defaultBlockState(), 2);
                    }
                    if (result.movement == Movement.TURN) {
                        mob.getNavigation().stop();
                        mob.setDeltaMovement(Vec3.ZERO);
                        mob.getNavigation().moveTo(mob.getX(), 200, 115, 1);
                    }
                }
            }
        }
    }

    private static void sampleTracking(Minecraft mc) throws ReflectiveOperationException {
        var target = mc.level.getEntity(current.targetId);
        var field = AutoAimClient.class.getDeclaredField("PROJECTILE_AIM_CONTROLLER");
        field.setAccessible(true);
        var controller = (AutoAimController) field.get(null);
        var solution = controller.aimSolution(mc.player);
        if (target == null || solution.isEmpty()) return;
        var profile = TaczCompat.findWeapon(mc.player, mc.player.getMainHandItem(),
                net.minecraft.world.InteractionHand.MAIN_HAND).orElseThrow().ballistics();
        var path = TrajectoryCalculator.traceIgnoringEntities(mc.level, mc.player, profile,
                mc.player.getXRot(), mc.player.getYRot());
        Vec3 eyes = target.getEyePosition();
        double distance = Double.POSITIVE_INFINITY;
        for (var segment : path.segments()) {
            Vec3 direction = segment.end().subtract(segment.start());
            double fraction = direction.lengthSqr() < 1.0E-10 ? 0
                    : Math.max(0, Math.min(1, eyes.subtract(segment.start()).dot(direction) / direction.lengthSqr()));
            distance = Math.min(distance, eyes.distanceTo(segment.start().add(direction.scale(fraction))));
        }
        if (stage.equals("impact")) {
            current.maxPostShotTrackingError = Math.max(current.maxPostShotTrackingError, distance);
        } else {
            current.trackingSamples++;
            current.maxTrackingError = Math.max(current.maxTrackingError, distance);
        }
    }

    @SubscribeEvent
    public static void bulletSpawn(EntityJoinLevelEvent event) {
        Result result = current;
        if (result == null || event.getLevel().isClientSide() || !(event.getEntity() instanceof EntityKineticBullet bullet)
                || bullet.getOwner() == null || !bullet.getOwner().getUUID().equals(result.playerUuid)) return;
        result.serverPitch = bullet.getOwner().getXRot();
        result.serverYaw = bullet.getOwner().getYRot();
        result.bulletVelocity = bullet.getDeltaMovement().toString();
        result.shotServerTick = result.serverTicks;
        var target = ((net.minecraft.server.level.ServerLevel) event.getLevel()).getEntity(result.targetUuid);
        if (target != null) {
            result.serverTargetAtShot = target.getEyePosition().toString();
            result.serverHitboxAtShot = com.tacz.guns.util.HitboxHelper.getFixedBoundingBox(target, bullet.getOwner()).toString();
        }
    }

    @SubscribeEvent
    public static void hurt(EntityHurtByGunEvent.Pre event) {
        Result result = current;
        if (result != null && event.getLogicalSide().isServer()
                && event.getHurtEntity().getUUID().equals(result.targetUuid)) {
            result.hit = true;
            result.headshot = event.isHeadShot();
            result.headAtImpact = event.getHurtEntity().getEyePosition().toString();
            var bullet = event.getBullet();
            result.impactPoint = com.tacz.guns.util.HitboxHelper.getFixedBoundingBox(event.getHurtEntity(), event.getAttacker())
                    .clip(bullet.position(), bullet.position().add(bullet.getDeltaMovement())).map(Object::toString).orElse("none");
        }
    }

    private static void finish(Minecraft mc, Throwable error) {
        stage = "done";
        if (lockKey != null) lockKey.setDown(false);
        try {
            Path report = Path.of(System.getProperty("autoaim.clienttest.report"));
            Files.createDirectories(report.getParent());
            var data = Map.of("results", RESULTS, "error", error == null ? "" : error.toString(),
                    "passed", error == null && RESULTS.stream().allMatch(result -> result.passed));
            Files.writeString(report, new GsonBuilder().setPrettyPrinting().create().toJson(data));
        } catch (Exception exception) {
            AutoAim.LOGGER.error("Cannot write client aim report", exception);
        }
        if (error != null) AutoAim.LOGGER.error("CLIENT AIM TEST FAILED", error);
        else AutoAim.LOGGER.info("CLIENT AIM TEST COMPLETE: {} / {} passed", RESULTS.stream().filter(r -> r.passed).count(), RESULTS.size());
        mc.stop();
    }

    private enum Movement { FIXED, WALK, JUMP, FALL, TURN }

    private record HitboxSample(int ticksAfterShot, double eyeY, double movementY,
                                double minY, double maxY, boolean overlapsHeadBand) {}

    private record Scenario(String gun, EntityType<? extends Mob> type, int distance, Vec3 motion, Movement movement) {
        private Scenario(String gun, EntityType<? extends Mob> type, int distance, Vec3 motion) {
            this(gun, type, distance, motion, Movement.FIXED);
        }
    }

    private static final class Result {
        final String gun;
        final String target;
        final int distance;
        final Vec3 motion;
        volatile UUID playerUuid;
        volatile UUID targetUuid;
        volatile int targetId;
        volatile boolean hit;
        volatile boolean headshot;
        volatile boolean passed;
        volatile float clientPitch;
        volatile float clientYaw;
        volatile float serverPitch;
        volatile float serverYaw;
        volatile String profile;
        volatile String bulletVelocity;
        volatile String serverTarget;
        volatile String serverTargetAtShot;
        volatile String serverHitboxAtShot;
        volatile String clientTargetAtShot;
        volatile String motionModel;
        int networkRoundTripMillis;
        volatile String packetHistory;
        volatile String headAtImpact;
        volatile String impactPoint;
        Movement movement = Movement.FIXED;
        String requirement;
        volatile int serverTicks;
        int trackingSamples;
        double maxTrackingError;
        double maxPostShotTrackingError;
        volatile int shotServerTick = -1;
        final List<HitboxSample> hitboxSamples = new ArrayList<>();
        volatile double minTargetY = 200;
        volatile double maxTargetY = 200;
        volatile double minTargetZ = 126.5;
        volatile double maxTargetZ = 126.5;

        Result(String gun, String target, int distance, Vec3 motion) {
            this.gun = gun;
            this.target = target;
            this.distance = distance;
            this.motion = motion;
        }
    }
}
