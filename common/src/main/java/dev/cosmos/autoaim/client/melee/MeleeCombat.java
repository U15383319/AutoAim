package dev.cosmos.autoaim.client.melee;

import dev.cosmos.autoaim.client.trajectory.WeaponBallistics;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.phys.EntityHitResult;

import java.util.Optional;

/**
 * Identifies the vanilla melee mode and delegates the final reach/occlusion test
 * to Minecraft's own crosshair picking implementation.
 */
@Environment(EnvType.CLIENT)
public final class MeleeCombat {
    private MeleeCombat() {
    }

    public static boolean isActive(LocalPlayer player) {
        // An actively used trident must remain in projectile/Riptide mode instead
        // of being treated as melee.
        if (player == null || player.isUsingItem()) {
            return false;
        }

        ItemStack mainHand = player.getMainHandItem();
        if (isMeleeWeapon(mainHand)) {
            return true;
        }

        // Follow the existing hand priority: a supported main-hand ranged item
        // takes precedence over a melee item carried in the offhand.
        return !WeaponBallistics.isSupportedProjectileItem(mainHand)
                && isMeleeWeapon(player.getOffhandItem());
    }

    public static boolean isMeleeWeapon(ItemStack stack) {
        Item item = stack.getItem();
        return item instanceof SwordItem
                || item instanceof AxeItem
                || item instanceof TridentItem;
    }

    /**
     * Re-runs the vanilla pick after auto aim has changed the view direction.
     * GameRenderer#pick applies the current game mode's reach, block occlusion,
     * and the same entity collision rules used by normal attacks.
     */
    public static Optional<Entity> pickAttackableEntity(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null || minecraft.gameMode == null) {
            return Optional.empty();
        }

        minecraft.gameRenderer.pick(1.0F);
        if (minecraft.hitResult instanceof EntityHitResult entityHitResult) {
            return Optional.of(entityHitResult.getEntity());
        }
        return Optional.empty();
    }

    /**
     * Treats the Ender Dragon and all of its sibling hit-box parts as one target.
     */
    public static boolean isSameTarget(Entity first, Entity second) {
        Entity firstOwner = targetOwner(first);
        Entity secondOwner = targetOwner(second);
        return firstOwner == secondOwner
                || firstOwner.is(secondOwner)
                || secondOwner.is(firstOwner);
    }

    private static Entity targetOwner(Entity entity) {
        return entity instanceof EnderDragonPart dragonPart
                ? dragonPart.parentMob
                : entity;
    }
}
