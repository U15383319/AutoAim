package dev.cosmos.autoaim.forge.gametest;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.config.common.AmmoConfig;
import com.tacz.guns.entity.EntityKineticBullet;
import com.tacz.guns.resource.modifier.AttachmentCacheProperty;
import dev.cosmos.autoaim.AutoAim;
import dev.cosmos.autoaim.client.aim.BallisticAimSolver;
import dev.cosmos.autoaim.client.aim.TargetMotionTracker;
import dev.cosmos.autoaim.client.trajectory.BallisticProfile;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.gametest.GameTestHolder;
import org.joml.Vector2d;

import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Consumer;

@GameTestHolder(AutoAim.MOD_ID)
public final class TaczAimGameTests {
    private TaczAimGameTests() {
    }

    @GameTestGenerator
    public static Collection<TestFunction> actualGunTests() {
        Collection<TestFunction> tests = new ArrayList<>();
        for (int distance : new int[]{30, 60, 100, 120}) {
            tests.add(AimGameTests.test("tacz_wither_headshot_" + distance,
                    helper -> fire(helper, EntityType.WITHER, distance, Vec3.ZERO)));
            tests.add(AimGameTests.test("tacz_zombie_headshot_" + distance,
                    helper -> fire(helper, EntityType.ZOMBIE, distance, Vec3.ZERO)));
        }
        for (int distance : new int[]{70, 120}) {
            tests.add(AimGameTests.test("tacz_rising_wither_headshot_" + distance,
                    helper -> fire(helper, EntityType.WITHER, distance, new Vec3(0, 3, 0))));
            tests.add(AimGameTests.test("tacz_falling_wither_headshot_" + distance,
                    helper -> fire(helper, EntityType.WITHER, distance, new Vec3(0, -0.3, 0))));
            tests.add(AimGameTests.test("tacz_strafing_wither_headshot_" + distance,
                    helper -> fire(helper, EntityType.WITHER, distance, new Vec3(0, 0, 0.3))));
            tests.add(AimGameTests.test("tacz_pursuing_wither_headshot_" + distance,
                    helper -> fire(helper, EntityType.WITHER, distance, Vec3.ZERO, true)));
            for (EntityType<? extends Mob> type : new EntityType[]{EntityType.ZOMBIE, EntityType.COW,
                    EntityType.ALLAY, EntityType.IRON_GOLEM}) {
                String name = BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
                tests.add(AimGameTests.test("tacz_moving_" + name + "_headshot_" + distance,
                        helper -> fire(helper, type, distance, new Vec3(0, 0.02, 0.3), false, false)));
            }
        }
        return tests;
    }

    private static <T extends Mob> void fire(GameTestHelper helper, EntityType<T> type, int distance, Vec3 motion) {
        fire(helper, type, distance, motion, false);
    }

    private static <T extends Mob> void fire(GameTestHelper helper, EntityType<T> type, int distance,
                                            Vec3 motion, boolean pursuing) {
        fire(helper, type, distance, motion, pursuing, true);
    }

    private static <T extends Mob> void fire(GameTestHelper helper, EntityType<T> type, int distance,
                                            Vec3 motion, boolean pursuing, boolean gravity) {
        Player shooter = AimGameTests.shooter(helper);
        T target = AimGameTests.target(helper, type, distance);
        if (motion.lengthSqr() > 0 || pursuing) {
            // Preserve the real mob's movement physics, while removing random
            // navigation goals. NoAI would disable travel entirely on the server.
            target.setNoAi(false);
            target.removeFreeWill();
            target.setOnGround(false);
            target.setNoGravity(!gravity);
        }
        target.setDeltaMovement(motion);
        Mob prey = pursuing ? AimGameTests.target(helper, EntityType.COW, distance + 6) : null;
        if (prey != null) {
            prey.setPos(prey.position().add(0, -6, 0));
            target.setTarget(prey);
            ((WitherBoss) target).setAlternativeTarget(0, prey.getId());
        }

        TargetMotionTracker tracker = new TargetMotionTracker();
        tracker.observe(target);
        helper.onEachTick(() -> tracker.observe(target));
        // Observe actual world ticks just as the client does before acquiring a
        // lock. No mob class or movement constants are supplied to the tracker.
        helper.runAfterDelay(5, () -> fireAtTarget(helper, shooter, target, prey, distance, tracker));
    }

    private static void fireAtTarget(GameTestHelper helper, Player shooter, Mob target, Mob prey,
                                     int distance, TargetMotionTracker tracker) {
        ResourceLocation gunId = new ResourceLocation("tacz", "deagle_golden");
        ItemStack gun = new ItemStack(BuiltInRegistries.ITEM.get(new ResourceLocation("tacz", "modern_kinetic_gun")));
        IGun item = IGun.getIGunOrNull(gun);
        helper.assertTrue(item != null, "Official TaCZ gun item must exist");
        item.setGunId(gun, gunId);
        item.setFireMode(gun, FireMode.SEMI);
        shooter.setItemSlot(EquipmentSlot.MAINHAND, gun);
        var index = TimelessAPI.getCommonGunIndex(gunId).orElseThrow();
        var gunData = index.getGunData();
        var bulletData = index.getBulletData();
        AttachmentCacheProperty cache = new AttachmentCacheProperty();
        cache.eval(gun, gunData);
        IGunOperator.fromLivingEntity(shooter).updateCacheProperty(cache);
        float speed = cache.getCache("ammo_speed");
        BallisticProfile profile = BallisticProfile.kineticBullet(speed, AmmoConfig.GLOBAL_BULLET_SPEED_MODIFIER.get(),
                bulletData.getGravity(), bulletData.getFriction(), bulletData.getLifeSecond()).orElseThrow();
        var aim = BallisticAimSolver.solve(helper.getLevel(), shooter, target, profile, true, tracker.motionOf(target))
                .orElseThrow(() -> new GameTestAssertException("No gun solution for " + target.getType() + " at " + distance));
        float initialHealth = target.getHealth();
        Vec3 initialEyes = target.getEyePosition();
        EntityKineticBullet bullet = new EntityKineticBullet(helper.getLevel(), shooter, gun,
                gunData.getAmmoId(), gunId, item.getGunDisplayId(gun), false, gunData, bulletData);

        boolean[] impact = {false};
        boolean[] headshot = {false};
        Consumer<EntityHurtByGunEvent.Pre> listener = event -> {
            if (event.getBullet() == bullet && event.getHurtEntity() == target) {
                impact[0] = true;
                headshot[0] = event.isHeadShot();
            }
        };
        MinecraftForge.EVENT_BUS.addListener(listener);
        bullet.shootFromRotation(shooter, aim.pitch(), aim.yaw(), 0, (float) profile.speed(), new Vector2d());
        helper.getLevel().addFreshEntity(bullet);
        AutoAim.LOGGER.info("GameTest firing {} range={} eye={} velocity={} pitch={} flightTicks={}",
                target.getType(), distance, initialEyes, target.getDeltaMovement(), aim.pitch(), aim.flightTicks());

        helper.runAfterDelay(20, () -> {
            MinecraftForge.EVENT_BUS.unregister(listener);
            AutoAim.LOGGER.info("GameTest impact {} range={} hit={} headshot={} health={} -> {} eye={} -> {}",
                    target.getType(), distance, impact[0], headshot[0], initialHealth, target.getHealth(), initialEyes, target.getEyePosition());
            AutoAim.LOGGER.info("GameTest bullet range={} tickCount={} alive={} position={} velocity={}",
                    distance, bullet.tickCount, bullet.isAlive(), bullet.position(), bullet.getDeltaMovement());
            try {
                helper.assertTrue(impact[0], "Actual TaCZ bullet missed " + target.getType() + " at " + distance + " blocks");
                helper.assertTrue(headshot[0], "Actual TaCZ hit was not a headshot: " + target.getType() + " at " + distance);
                helper.assertTrue(target.getHealth() < initialHealth, "Actual TaCZ bullet must inflict damage");
                helper.succeed();
            } finally {
                target.discard();
                bullet.discard();
                if (prey != null) {
                    prey.discard();
                }
            }
        });
    }
}
