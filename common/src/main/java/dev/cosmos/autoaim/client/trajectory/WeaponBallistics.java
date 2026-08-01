package dev.cosmos.autoaim.client.trajectory;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ThrowablePotionItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.Optional;

@Environment(EnvType.CLIENT)
public final class WeaponBallistics {
    private static final BallisticProfile ARROW = new BallisticProfile(3.0D, 0.05D, 0.99D, 0.6D, true);
    private static final BallisticProfile CROSSBOW_ARROW = new BallisticProfile(3.15D, 0.05D, 0.99D, 0.6D, false);
    private static final BallisticProfile FIREWORK = new BallisticProfile(1.6D, 0.0D, 1.0D, 1.0D, false);
    private static final BallisticProfile TRIDENT = new BallisticProfile(2.5D, 0.05D, 0.99D, 0.99D, true);
    // Riptide moves the player rather than spawning a projectile. These values
    // model the vanilla impulse and subsequent air movement in the shared path solver.
    private static final double RIPTIDE_AIR_DRAG = 0.91D;
    private static final double RIPTIDE_WATER_DRAG = 0.8D;
    private static final double RIPTIDE_GRAVITY = 0.0784D;
    private static final BallisticProfile FAST_THROWABLE = new BallisticProfile(1.5D, 0.03D, 0.99D, 0.8D, true);
    private static final BallisticProfile THROWABLE_POTION =
            new BallisticProfile(0.5D, 0.05D, 0.99D, 0.8D, true, -20.0F);

    private WeaponBallistics() {
    }

    public static Optional<WeaponContext> findMarkerWeapon(LocalPlayer player) {
        return findHeldWeapon(player, true);
    }

    public static Optional<WeaponContext> findAutoAimWeapon(LocalPlayer player) {
        return findHeldWeapon(player, false);
    }

    private static Optional<WeaponContext> findHeldWeapon(LocalPlayer player, boolean markerMode) {
        if (player.isUsingItem() && isSupportedProjectileItem(player.getUseItem())) {
            return createContext(player, player.getUseItem(), player.getUsedItemHand(), true);
        }

        ItemStack mainHand = player.getMainHandItem();
        if (markerMode && canShowMarkerWhileHeld(mainHand)) {
            return createContext(player, mainHand, InteractionHand.MAIN_HAND, false);
        }

        ItemStack offhand = player.getOffhandItem();
        if (markerMode && canShowMarkerWhileHeld(offhand)) {
            return createContext(player, offhand, InteractionHand.OFF_HAND, false);
        }

        if (markerMode) {
            return Optional.empty();
        }

        if (isSupportedProjectileItem(mainHand)) {
            Optional<WeaponContext> mainHandContext = createContext(
                    player,
                    mainHand,
                    InteractionHand.MAIN_HAND,
                    false
            );
            if (mainHandContext.isPresent()) {
                return mainHandContext;
            }
        }

        if (isSupportedProjectileItem(offhand)) {
            return createContext(player, offhand, InteractionHand.OFF_HAND, false);
        }

        return Optional.empty();
    }

    public static boolean isSupportedProjectileItem(ItemStack stack) {
        return isRangedWeapon(stack) || isThrowableItem(stack);
    }

    private static boolean isRangedWeapon(ItemStack stack) {
        Item item = stack.getItem();
        return item instanceof ProjectileWeaponItem || item instanceof TridentItem;
    }

    private static boolean canShowMarkerWhileHeld(ItemStack stack) {
        return isChargedCrossbow(stack) || isThrowableItem(stack);
    }

    private static boolean isChargedCrossbow(ItemStack stack) {
        return stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack);
    }

    private static boolean isThrowableItem(ItemStack stack) {
        return isFastThrowable(stack) || isThrowablePotion(stack);
    }

    private static boolean isFastThrowable(ItemStack stack) {
        return stack.is(Items.ENDER_PEARL)
                || stack.is(Items.SNOWBALL)
                || stack.is(Items.EGG);
    }

    private static boolean isThrowablePotion(ItemStack stack) {
        return stack.getItem() instanceof ThrowablePotionItem;
    }

    private static Optional<WeaponContext> createContext(
            LocalPlayer player,
            ItemStack stack,
            InteractionHand hand,
            boolean activelyUsing
    ) {
        Item item = stack.getItem();

        if (isFastThrowable(stack)) {
            return Optional.of(new WeaponContext(stack, hand, FAST_THROWABLE));
        }

        if (isThrowablePotion(stack)) {
            return Optional.of(new WeaponContext(stack, hand, THROWABLE_POTION));
        }

        if (item instanceof TridentItem) {
            int riptideLevel = EnchantmentHelper.getRiptide(stack);
            if (activelyUsing && player.getTicksUsingItem() < 10) {
                return Optional.empty();
            }
            if (riptideLevel > 0) {
                if (!activelyUsing || !player.isInWaterOrRain()) {
                    return Optional.empty();
                }
                return Optional.of(new WeaponContext(stack, hand, riptideProfile(riptideLevel)));
            }
            return Optional.of(new WeaponContext(stack, hand, TRIDENT));
        }

        if (!(item instanceof ProjectileWeaponItem)) {
            return Optional.empty();
        }

        boolean chargedCrossbow = isChargedCrossbow(stack);
        if (!chargedCrossbow && !hasProjectile(player, stack)) {
            return Optional.empty();
        }

        if (item instanceof BowItem) {
            double speed = ARROW.speed();
            if (activelyUsing) {
                float power = BowItem.getPowerForTime(player.getTicksUsingItem());
                if (power < 0.1F) {
                    return Optional.empty();
                }
                speed *= power;
            }
            return Optional.of(new WeaponContext(
                    stack,
                    hand,
                    new BallisticProfile(
                            speed,
                            ARROW.gravity(),
                            ARROW.airDrag(),
                            ARROW.waterDrag(),
                            ARROW.inheritsShooterMovement()
                    )
            ));
        }

        if (item instanceof CrossbowItem) {
            boolean firesRocket = CrossbowItem.containsChargedProjectile(stack, Items.FIREWORK_ROCKET)
                    || (!chargedCrossbow && player.getProjectile(stack).is(Items.FIREWORK_ROCKET));
            return Optional.of(new WeaponContext(stack, hand, firesRocket ? FIREWORK : CROSSBOW_ARROW));
        }

        // Modded ProjectileWeaponItem implementations get vanilla-arrow defaults. Keeping
        // this fallback makes ordinary bow-like weapons work without a hard dependency.
        return Optional.of(new WeaponContext(stack, hand, ARROW));
    }

    private static boolean hasProjectile(LocalPlayer player, ItemStack weapon) {
        return player.getAbilities().instabuild || !player.getProjectile(weapon).isEmpty();
    }

    private static BallisticProfile riptideProfile(int riptideLevel) {
        double impulse = 3.0D * (1.0D + riptideLevel) / 4.0D;
        return new BallisticProfile(
                impulse,
                RIPTIDE_GRAVITY,
                RIPTIDE_AIR_DRAG,
                RIPTIDE_WATER_DRAG,
                true
        );
    }
}
