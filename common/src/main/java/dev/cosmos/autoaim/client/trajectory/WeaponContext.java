package dev.cosmos.autoaim.client.trajectory;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

public record WeaponContext(
        ItemStack weapon,
        InteractionHand hand,
        BallisticProfile ballistics
) {
}
