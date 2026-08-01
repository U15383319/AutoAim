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
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.Optional;

@Environment(EnvType.CLIENT)
public final class WeaponBallistics {
    private static final BallisticProfile ARROW = new BallisticProfile(3.0D, 0.05D, 0.99D, 0.6D, true);
    private static final BallisticProfile CROSSBOW_ARROW = new BallisticProfile(3.15D, 0.05D, 0.99D, 0.6D, false);
    private static final BallisticProfile FIREWORK = new BallisticProfile(1.6D, 0.0D, 1.0D, 1.0D, false);
    private static final BallisticProfile TRIDENT = new BallisticProfile(2.5D, 0.05D, 0.99D, 0.99D, true);

    private WeaponBallistics() {
    }

    /**
     * Finds the weapon whose trajectory should currently be used. Active item use wins,
     * followed by the main hand and then the offhand.
     */
    public static Optional<WeaponContext> findHeldWeapon(LocalPlayer player, boolean requireCharging) {
        if (player.isUsingItem() && isRangedWeapon(player.getUseItem())) {
            return createContext(player, player.getUseItem(), player.getUsedItemHand(), true);
        }

        ItemStack mainHand = player.getMainHandItem();
        if (requireCharging && isChargedCrossbow(mainHand)) {
            return createContext(player, mainHand, InteractionHand.MAIN_HAND, false);
        }

        ItemStack offhand = player.getOffhandItem();
        if (requireCharging && isChargedCrossbow(offhand)) {
            return createContext(player, offhand, InteractionHand.OFF_HAND, false);
        }

        if (requireCharging) {
            return Optional.empty();
        }

        if (isRangedWeapon(mainHand)) {
            return createContext(player, mainHand, InteractionHand.MAIN_HAND, false);
        }

        if (isRangedWeapon(offhand)) {
            return createContext(player, offhand, InteractionHand.OFF_HAND, false);
        }

        return Optional.empty();
    }

    public static boolean isRangedWeapon(ItemStack stack) {
        Item item = stack.getItem();
        return item instanceof ProjectileWeaponItem || item instanceof TridentItem;
    }

    private static boolean isChargedCrossbow(ItemStack stack) {
        return stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack);
    }

    private static Optional<WeaponContext> createContext(
            LocalPlayer player,
            ItemStack stack,
            InteractionHand hand,
            boolean activelyUsing
    ) {
        Item item = stack.getItem();

        if (item instanceof TridentItem) {
            if (EnchantmentHelper.getRiptide(stack) > 0 || (activelyUsing && player.getTicksUsingItem() < 10)) {
                return Optional.empty();
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
}
