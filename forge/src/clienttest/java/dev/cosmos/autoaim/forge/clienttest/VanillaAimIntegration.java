package dev.cosmos.autoaim.forge.clienttest;

import com.google.gson.GsonBuilder;
import dev.cosmos.autoaim.AutoAim;
import dev.cosmos.autoaim.client.AutoAimClient;
import dev.cosmos.autoaim.client.aim.AimSolution;
import dev.cosmos.autoaim.client.aim.AutoAimController;
import dev.cosmos.autoaim.client.aim.BallisticAimSolver;
import dev.cosmos.autoaim.client.aim.TargetMotion;
import dev.cosmos.autoaim.client.trajectory.BallisticProfile;
import dev.cosmos.autoaim.client.trajectory.WeaponBallistics;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Real vanilla use/release packets, charging, impact knockback and rendered view. */
@Mod.EventBusSubscriber(modid = AutoAim.MOD_ID, value = Dist.CLIENT)
public final class VanillaAimIntegration {
    private static final List<Scenario> SCENARIOS = new ArrayList<>();
    private static final List<Result> RESULTS = new ArrayList<>();
    private static final long STARTED = System.nanoTime();
    private static volatile Result current;
    private static String stage = "boot";
    private static int ticks;
    private static int index;
    private static KeyMapping lockKey;
    private static AutoAimController controller;
    private static CompletableFuture<Void> setup;

    static {
        for (Item item : new Item[]{Items.BOW, Items.TRIDENT}) {
            for (int range : new int[]{8, 24, 40, 64}) SCENARIOS.add(new Scenario(item, range, false));
        }
        for (int range : new int[]{8, 24, 40}) SCENARIOS.add(new Scenario(Items.CROSSBOW, range, false));
        for (int range : new int[]{8, 24}) SCENARIOS.add(new Scenario(Items.CROSSBOW, range, true));
        SCENARIOS.add(new Scenario(Items.TRIDENT, 24, false, true));
        SCENARIOS.add(new Scenario(Items.BOW, 8, false, false, 12));
        SCENARIOS.add(new Scenario(Items.BOW, 24, false, false, 18));
        String selected = System.getProperty("autoaim.vanillatest.case", "all");
        if (selected.equals("trident")) SCENARIOS.removeIf(scenario -> scenario.item != Items.TRIDENT);
        if (selected.equals("unlocked")) {
            SCENARIOS.clear();
            for (Item item : new Item[]{Items.BOW, Items.TRIDENT, Items.CROSSBOW})
                SCENARIOS.add(new Scenario(item, 8, false, false, 35, false));
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("autoaim.vanillatest.enabled") || event.phase != TickEvent.Phase.END || stage.equals("done")) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if ((System.nanoTime() - STARTED) / 1.0E9 > 420) throw new IllegalStateException("Vanilla test timed out in " + stage);
            if (stage.equals("boot")) {
                if (mc.screen instanceof AccessibilityOnboardingScreen) { mc.screen.onClose(); return; }
                if (!(mc.screen instanceof TitleScreen)) return;
                mc.options.pauseOnLostFocus = false;
                mc.options.enableVsync().set(false);
                mc.options.framerateLimit().set(60);
                mc.options.renderDistance().set(8);
                var key = AutoAimClient.class.getDeclaredField("HOLD_AUTO_AIM");
                key.setAccessible(true);
                lockKey = (KeyMapping) key.get(null);
                var field = AutoAimClient.class.getDeclaredField("PROJECTILE_AIM_CONTROLLER");
                field.setAccessible(true);
                controller = (AutoAimController) field.get(null);
                stage = "world";
                mc.createWorldOpenFlows().loadLevel(mc.screen, "autoaim-test");
                return;
            }
            if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || mc.screen != null) return;
            mc.setWindowActive(true);
            if (stage.equals("world") || stage.equals("next")) {
                if (index == SCENARIOS.size()) { finish(mc, null); return; }
                lockKey.setDown(false);
                mc.options.keyUse.setDown(false);
                current = new Result(SCENARIOS.get(index));
                setup = mc.getSingleplayerServer().submitAsync(() -> setup(mc, current));
                stage = "setup";
                ticks = 0;
                return;
            }
            if (stage.equals("setup")) {
                if (!setup.isDone()) return;
                setup.join();
                var target = mc.level.getEntity(current.targetId);
                if (target == null || !mc.player.getMainHandItem().is(current.scenario.item)
                        || (current.scenario.item == Items.CROSSBOW && !CrossbowItem.isCharged(mc.player.getMainHandItem()))) {
                    if (++ticks > 400) throw new IllegalStateException("Test equipment did not sync");
                    return;
                }
                BallisticAimSolver.solve(mc.level, mc.player, target, profile(current.scenario), false,
                        TargetMotion.linear(Vec3.ZERO, true)).ifPresent(aim -> {
                    mc.player.setYRot(aim.yaw()); mc.player.setXRot(aim.pitch());
                    mc.player.yRotO = aim.yaw(); mc.player.xRotO = aim.pitch();
                });
                lockKey.setDown(current.scenario.lockEnabled);
                stage = "settle";
                ticks = 0;
                return;
            }
            lockKey.setDown(current.scenario.lockEnabled);
            if (stage.equals("settle")) {
                if (++ticks < 35) return;
                current.pitchBeforeUse = mc.player.getXRot();
                if (current.scenario.item != Items.CROSSBOW) mc.options.keyUse.setDown(true);
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                if (current.scenario.item == Items.CROSSBOW)
                    current.viewRestoreError = Math.abs(mc.player.getXRot() - current.pitchBeforeUse);
                stage = current.scenario.item == Items.CROSSBOW ? "after_shot" : "charge";
                ticks = 0;
                return;
            }
            if (stage.equals("charge")) {
                current.maxChargeTicks = Math.max(current.maxChargeTicks, mc.player.getTicksUsingItem());
                if (current.scenario.mouseDisturbance && mc.player.getTicksUsingItem() >= 1
                        && mc.player.getTicksUsingItem() < 10) mc.player.turn(0, ticks % 2 == 0 ? 48 : -48);
                if (++ticks < current.scenario.releaseTicks) return;
                mc.options.keyUse.setDown(false);
                float viewBeforeRelease = mc.player.getXRot();
                current.pitchAtRelease = viewBeforeRelease;
                mc.gameMode.releaseUsingItem(mc.player);
                current.viewRestoreError = Math.abs(mc.player.getXRot() - viewBeforeRelease);
                stage = "after_shot";
                ticks = 0;
                return;
            }
            if (stage.equals("after_shot") && ++ticks >= 65) {
                evaluate(current);
                RESULTS.add(current);
                AutoAim.LOGGER.info("VANILLA AIM RESULT {} range={} kill={} chargeExcursion={} postHitError={} hit={} passed={}",
                        current.weapon, current.scenario.range, current.scenario.kill, current.chargeExcursion,
                        current.maxPostHitError, current.hit, current.passed);
                Result completed = current;
                mc.getSingleplayerServer().execute(() -> {
                    var target = mc.getSingleplayerServer().overworld().getEntity(completed.targetUuid);
                    if (target != null) target.discard();
                });
                lockKey.setDown(false);
                index++;
                stage = "next";
            }
        } catch (Throwable error) { finish(mc, error); }
    }

    private static void setup(Minecraft mc, Result result) {
        var server = mc.getSingleplayerServer();
        var level = server.overworld();
        ServerPlayer player = server.getPlayerList().getPlayer(mc.player.getUUID());
        result.playerUuid = player.getUUID();
        player.stopUsingItem();
        player.setGameMode(GameType.CREATIVE);
        player.teleportTo(level, 4.5, 200, 126.5, -90, 0);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.setNoGravity(true);
        player.setDeltaMovement(Vec3.ZERO);
        player.getInventory().clearContent();
        player.getInventory().selected = 0;
        ItemStack weapon = new ItemStack(result.scenario.item);
        if (result.scenario.item == Items.CROSSBOW) {
            ListTag arrows = new ListTag();
            arrows.add(new ItemStack(Items.ARROW).save(new CompoundTag()));
            weapon.getOrCreateTag().put("ChargedProjectiles", arrows);
            CrossbowItem.setCharged(weapon, true);
        }
        player.setItemSlot(EquipmentSlot.MAINHAND, weapon);
        player.getInventory().setItem(1, new ItemStack(Items.ARROW, 64));
        player.inventoryMenu.broadcastChanges();
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        level.setDayTime(6000);
        if (index == 0) for (int x = -1; x <= 82; x++) for (int z = 120; z <= 134; z++)
            level.setBlock(new BlockPos(x, 199, z), Blocks.STONE.defaultBlockState(), 2);
        var target = EntityType.ZOMBIE.create(level);
        target.setPos(4.5 + result.scenario.range, 200, 126.5);
        target.removeFreeWill();
        target.setPersistenceRequired();
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
        target.setHealth(result.scenario.kill ? 1 : 200);
        target.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        level.addFreshEntity(target);
        result.targetId = target.getId();
        result.targetUuid = target.getUUID();
    }

    @SubscribeEvent
    public static void sample(TickEvent.RenderTickEvent event) {
        if (!Boolean.getBoolean("autoaim.vanillatest.enabled") || event.phase != TickEvent.Phase.END
                || current == null || !(stage.equals("settle") || stage.equals("charge") || stage.equals("after_shot"))) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        var target = mc.level.getEntity(current.targetId);
        Double reference = null;
        if (target != null && target.isAlive()) {
            reference = BallisticAimSolver.solve(mc.level, mc.player, target, profile(current.scenario), false,
                    TargetMotion.linear(Vec3.ZERO, target.onGround())).map(aim -> (double) aim.pitch()).orElse(null);
        } else reference = (double) current.pitchBeforeUse;
        AimSolution solution = controller.aimSolution(mc.player).orElse(null);
        double speed = WeaponBallistics.findAutoAimWeapon(mc.player).map(context -> context.ballistics().speed()).orElse(0.0);
        current.frames.add(new Frame(stage, ticks, mc.player.getTicksUsingItem(), mc.player.isUsingItem(),
                mc.player.getXRot(), mc.gameRenderer.getMainCamera().getXRot(),
                solution == null ? null : (double) solution.pitch(), solution == null ? null : solution.flightTicks(),
                speed, target == null ? null : target.getDeltaMovement().y, target != null && target.onGround(),
                target == null ? null : target.getY(), reference, current.hit, AutoAimClient.shouldSuppressMouseTurn(mc.player)));
    }

    @SubscribeEvent
    public static void centralProjectile(EntityJoinLevelEvent event) {
        Result result = current;
        if (!Boolean.getBoolean("autoaim.vanillatest.enabled") || result == null || event.getLevel().isClientSide()
                || !(event.getEntity() instanceof AbstractArrow arrow) || arrow.getOwner() == null
                || !arrow.getOwner().getUUID().equals(result.playerUuid)) return;
        // Remove only random spread in this fixture; use the actual server
        // angle and native projectile, so input/rotation ordering is exercised.
        var owner = arrow.getOwner();
        result.serverLaunchPitch = owner.getXRot();
        result.serverChargeTicks = owner instanceof LivingEntity living ? living.getTicksUsingItem() : 0;
        double speed = result.scenario.item == Items.BOW ? 3 * BowItem.getPowerForTime(result.serverChargeTicks)
                : profile(result.scenario).speed();
        arrow.setDeltaMovement(Vec3.directionFromRotation(owner.getXRot(), owner.getYRot()).scale(speed));
    }

    @SubscribeEvent
    public static void impact(LivingHurtEvent event) {
        Result result = current;
        if (!Boolean.getBoolean("autoaim.vanillatest.enabled") || result == null || event.getEntity().level().isClientSide()
                || !event.getEntity().getUUID().equals(result.targetUuid)
                || !(event.getSource().getDirectEntity() instanceof AbstractArrow arrow)
                || arrow.getOwner() == null || !arrow.getOwner().getUUID().equals(result.playerUuid)) return;
        result.hit = true;
    }

    private static void evaluate(Result result) {
        var charge = result.frames.stream().filter(frame -> frame.stage.equals("charge")).toList();
        if (!charge.isEmpty()) {
            result.unlockedChargeFrames = (int) charge.stream().filter(frame -> frame.chargeTicks >= 1 && !frame.mouseSuppressed).count();
            double low = charge.stream().mapToDouble(frame -> frame.cameraPitch).min().orElseThrow();
            double high = charge.stream().mapToDouble(frame -> frame.cameraPitch).max().orElseThrow();
            double first = charge.get(0).cameraPitch;
            double last = charge.get(charge.size() - 1).cameraPitch;
            result.chargeExcursion = Math.max(0, Math.max(high - Math.max(first, last), Math.min(first, last) - low));
        }
        result.maxPostHitError = result.frames.stream().filter(frame -> frame.hit && frame.referencePitch != null)
                .mapToDouble(frame -> Math.abs(frame.cameraPitch - frame.referencePitch)).max().orElse(0);
        result.manualViewDrift = result.frames.stream().filter(frame -> !frame.stage.equals("settle"))
                .mapToDouble(frame -> Math.abs(frame.cameraPitch - result.pitchBeforeUse)).max().orElse(0);
        result.passed = result.scenario.item == Items.CROSSBOW
                ? result.hit && result.maxPostHitError < 5 && result.viewRestoreError < 0.0001
                : result.hit && result.maxChargeTicks >= Math.min(20, result.scenario.releaseTicks - 1)
                && result.chargeExcursion < 5 && (!result.scenario.lockEnabled || result.unlockedChargeFrames == 0)
                && result.viewRestoreError < 0.0001;
        // With V released, the camera must stay where the player aimed even
        // while the struck target moves. Following that target would be a bug.
        if (!result.scenario.lockEnabled)
            result.passed = result.hit && result.manualViewDrift < 0.0001 && result.viewRestoreError < 0.0001;
        if (result.scenario.releaseTicks < 20 && result.scenario.item == Items.BOW)
            result.passed &= Math.abs(result.serverLaunchPitch - result.pitchAtRelease) > 0.5;
    }

    private static BallisticProfile profile(Scenario scenario) {
        return new BallisticProfile(scenario.item == Items.BOW ? 3 : scenario.item == Items.CROSSBOW ? 3.15 : 2.5,
                0.05, 0.99, scenario.item == Items.TRIDENT ? 0.99 : 0.6, scenario.item != Items.CROSSBOW);
    }

    private static void finish(Minecraft mc, Throwable error) {
        stage = "done";
        if (lockKey != null) lockKey.setDown(false);
        mc.options.keyUse.setDown(false);
        try {
            Path path = Path.of(System.getProperty("autoaim.clienttest.report"));
            Files.createDirectories(path.getParent());
            boolean visible = GLFW.glfwGetWindowAttrib(mc.getWindow().getWindow(), GLFW.GLFW_VISIBLE) != 0;
            double volume = mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).get();
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("results", RESULTS,
                    "windowVisible", visible, "masterVolume", volume,
                    "error", error == null ? "" : error.toString(), "passed", error == null && !visible && volume == 0
                            && RESULTS.size() == SCENARIOS.size()
                            && RESULTS.stream().allMatch(result -> result.passed))));
        } catch (Exception failure) { AutoAim.LOGGER.error("Cannot write vanilla report", failure); }
        if (error != null) AutoAim.LOGGER.error("VANILLA AIM TEST FAILED", error);
        mc.stop();
    }

    private record Scenario(Item item, int range, boolean kill, boolean mouseDisturbance, int releaseTicks, boolean lockEnabled) {
        private Scenario(Item item, int range, boolean kill) { this(item, range, kill, false, 35, true); }
        private Scenario(Item item, int range, boolean kill, boolean mouseDisturbance) { this(item, range, kill, mouseDisturbance, 35, true); }
        private Scenario(Item item, int range, boolean kill, boolean mouseDisturbance, int releaseTicks) { this(item, range, kill, mouseDisturbance, releaseTicks, true); }
    }
    private record Frame(String stage, int tick, int chargeTicks, boolean usingItem, double playerPitch,
                         double cameraPitch, Double solverPitch, Double flightTicks, double speed,
                         Double targetVelocityY, boolean targetGrounded, Double targetY, Double referencePitch, boolean hit,
                         boolean mouseSuppressed) {}
    private static final class Result {
        final transient Scenario scenario;
        final String weapon;
        final int distance;
        final boolean lethal;
        final boolean lockEnabled;
        final int releaseTicks;
        final List<Frame> frames = new ArrayList<>();
        volatile UUID playerUuid;
        volatile UUID targetUuid;
        volatile int targetId;
        volatile boolean hit;
        float pitchBeforeUse;
        int maxChargeTicks;
        int unlockedChargeFrames;
        volatile float serverLaunchPitch;
        volatile int serverChargeTicks;
        float pitchAtRelease;
        double viewRestoreError;
        double chargeExcursion;
        double maxPostHitError;
        double manualViewDrift;
        boolean passed;
        Result(Scenario scenario) {
            this.scenario = scenario; weapon = scenario.item.toString(); distance = scenario.range; lethal = scenario.kill;
            lockEnabled = scenario.lockEnabled; releaseTicks = scenario.releaseTicks;
        }
    }
}
