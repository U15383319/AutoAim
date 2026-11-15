package dev.cosmos.autoaim.client.compat;

import dev.architectury.platform.Platform;
import dev.cosmos.autoaim.AutoAim;
import dev.cosmos.autoaim.client.trajectory.BallisticProfile;
import dev.cosmos.autoaim.client.trajectory.WeaponContext;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Optional TaCZ 1.2.0 bridge. Only public TaCZ APIs are resolved, after the
 * loader confirms its presence; neither platform needs a TaCZ dependency.
 */
@Environment(EnvType.CLIENT)
public final class TaczCompat {
    private static LocalPlayer cachedPlayer;
    private static ItemStack cachedStack;
    private static int cachedTick;
    private static Optional<WeaponContext> cachedContext = Optional.empty();
    private static boolean warnedReadFailure;

    private TaczCompat() {
    }

    public static boolean isGun(ItemStack stack) {
        Api api = ApiHolder.INSTANCE;
        return api != null && api.gunType.isInstance(stack.getItem());
    }

    public static void clearCache() {
        cachedPlayer = null;
        cachedStack = null;
        cachedContext = Optional.empty();
    }

    public static Optional<WeaponContext> findWeapon(LocalPlayer player, ItemStack stack, InteractionHand hand) {
        // TaCZ currently only fires guns from the main hand.
        if (hand != InteractionHand.MAIN_HAND || !isGun(stack)) {
            return Optional.empty();
        }
        if (cachedPlayer == player && cachedStack == stack && cachedTick == player.tickCount) {
            return cachedContext;
        }

        cachedPlayer = player;
        cachedStack = stack;
        cachedTick = player.tickCount;
        cachedContext = Optional.empty();
        try {
            cachedContext = ApiHolder.INSTANCE.readProfile(player, stack)
                    .map(profile -> new WeaponContext(stack, hand, profile, true));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            if (!warnedReadFailure) {
                warnedReadFailure = true;
                AutoAim.LOGGER.warn("Unable to read TaCZ gun ballistics; vanilla aiming remains available", exception);
            }
        }
        return cachedContext;
    }

    static Api loadApi(boolean modLoaded, ClassLoader loader) {
        if (!modLoaded) {
            return null;
        }
        try {
            Api api = new Api(loader);
            AutoAim.LOGGER.info("Enabled optional TaCZ gun aiming compatibility (1.2.0 API)");
            return api;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            AutoAim.LOGGER.warn("TaCZ aiming compatibility is unavailable; vanilla aiming remains available", exception);
            return null;
        }
    }

    private static final class ApiHolder {
        private static final Api INSTANCE = loadApi(Platform.isModLoaded("tacz"), TaczCompat.class.getClassLoader());
    }

    static final class Api {
        private final Class<?> gunType;
        private final Class<?> operatorType;
        private final Method gunId;
        private final Method clientGunIndex;
        private final Method gunData;
        private final Method bulletData;
        private final Method speed;
        private final Method gravity;
        private final Method friction;
        private final Method life;
        private final Method cacheProperty;
        private final Method cachedValue;
        private final Field speedMultiplier;
        private final Method configValue;

        private Api(ClassLoader loader) throws ReflectiveOperationException {
            gunType = Class.forName("com.tacz.guns.api.item.IGun", false, loader);
            operatorType = Class.forName("com.tacz.guns.api.entity.IGunOperator", false, loader);
            Class<?> timelessApi = Class.forName("com.tacz.guns.api.TimelessAPI", false, loader);
            Class<?> indexType = Class.forName("com.tacz.guns.client.resource.index.ClientGunIndex", false, loader);
            Class<?> ammoConfig = Class.forName("com.tacz.guns.config.common.AmmoConfig", false, loader);

            gunId = gunType.getMethod("getGunId", ItemStack.class);
            clientGunIndex = timelessApi.getMethod("getClientGunIndex", ResourceLocation.class);
            gunData = indexType.getMethod("getGunData");
            bulletData = gunData.getReturnType().getMethod("getBulletData");
            Class<?> bulletType = bulletData.getReturnType();
            speed = bulletType.getMethod("getSpeed");
            gravity = bulletType.getMethod("getGravity");
            friction = bulletType.getMethod("getFriction");
            life = bulletType.getMethod("getLifeSecond");
            cacheProperty = operatorType.getMethod("getCacheProperty");
            cachedValue = cacheProperty.getReturnType().getMethod("getCache", String.class);
            speedMultiplier = ammoConfig.getField("GLOBAL_BULLET_SPEED_MODIFIER");
            configValue = speedMultiplier.getType().getMethod("get");
        }

        private Optional<BallisticProfile> readProfile(LocalPlayer player, ItemStack stack)
                throws ReflectiveOperationException {
            Optional<?> index = (Optional<?>) clientGunIndex.invoke(null, gunId.invoke(stack.getItem(), stack));
            if (index.isEmpty()) {
                // Gun packs may still be syncing or reloading. Retry next tick.
                return Optional.empty();
            }
            Object data = gunData.invoke(index.get());
            if (data == null) {
                return Optional.empty();
            }
            Object bullet = bulletData.invoke(data);
            if (bullet == null) {
                return Optional.empty();
            }

            double speedPerSecond = ((Number) speed.invoke(bullet)).doubleValue();
            if (operatorType.isInstance(player)) {
                Object cache = cacheProperty.invoke(player);
                if (cache != null) {
                    try {
                        Object value = cachedValue.invoke(cache, "ammo_speed");
                        if (value instanceof Number number) {
                            speedPerSecond = number.doubleValue();
                        }
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                        // Like TaCZ's own rangefinder, fall back to base speed
                        // until the attachment cache has been populated.
                    }
                }
            }
            double multiplier = ((Number) configValue.invoke(speedMultiplier.get(null))).doubleValue();
            return BallisticProfile.kineticBullet(
                    speedPerSecond, multiplier,
                    ((Number) gravity.invoke(bullet)).doubleValue(),
                    ((Number) friction.invoke(bullet)).doubleValue(),
                    ((Number) life.invoke(bullet)).doubleValue()
            );
        }
    }
}
