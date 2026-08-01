package dev.cosmos.autoaim.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.registry.client.keymappings.KeyMappingRegistry;
import dev.cosmos.autoaim.client.aim.AutoAimController;
import dev.cosmos.autoaim.client.config.AutoAimConfig;
import dev.cosmos.autoaim.client.trajectory.TrajectoryCalculator;
import dev.cosmos.autoaim.client.trajectory.TrajectoryResult;
import dev.cosmos.autoaim.client.trajectory.WeaponBallistics;
import dev.cosmos.autoaim.client.trajectory.WeaponContext;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.EnderDragonPart;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;

@Environment(EnvType.CLIENT)
public final class AutoAimClient {
    private static final String KEY_CATEGORY = "key.categories.autoaim";
    private static final KeyMapping TOGGLE_MARKER = new KeyMapping(
            "key.autoaim.toggle_marker",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            KEY_CATEGORY
    );
    private static final KeyMapping HOLD_AUTO_AIM = new KeyMapping(
            "key.autoaim.hold_auto_aim",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            KEY_CATEGORY
    );
    private static final AutoAimController AIM_CONTROLLER = new AutoAimController();

    private static boolean markerEnabled;
    private static Optional<BlockPos> markedBlock = Optional.empty();
    private static Entity markedEntity;

    private AutoAimClient() {
    }

    public static void init() {
        AutoAimConfig.load();
        KeyMappingRegistry.register(TOGGLE_MARKER);
        KeyMappingRegistry.register(HOLD_AUTO_AIM);
        ClientTickEvent.CLIENT_POST.register(AutoAimClient::onClientTick);
    }

    public static Optional<BlockPos> markedBlock() {
        return markedBlock;
    }

    public static boolean isMarkedEntity(Entity entity) {
        return markedEntity == entity;
    }

    private static void onClientTick(Minecraft minecraft) {
        while (TOGGLE_MARKER.consumeClick()) {
            markerEnabled = !markerEnabled;
            if (!markerEnabled) {
                clearMarker();
            }
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.translatable(markerEnabled
                                ? "message.autoaim.marker_enabled"
                                : "message.autoaim.marker_disabled"),
                        true
                );
            }
        }

        AIM_CONTROLLER.tick(minecraft, HOLD_AUTO_AIM.isDown());
        updateMarker(minecraft);
    }

    private static void updateMarker(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (!markerEnabled || player == null || minecraft.level == null) {
            clearMarker();
            return;
        }

        Optional<Entity> lockedTarget = AIM_CONTROLLER.lockedTarget();
        if (lockedTarget.isPresent()) {
            markedEntity = glowEntityFor(lockedTarget.get());
            markedBlock = Optional.empty();
            return;
        }

        Optional<WeaponContext> weapon = WeaponBallistics.findMarkerWeapon(player);
        if (weapon.isEmpty()) {
            clearMarker();
            return;
        }

        TrajectoryResult trajectory = TrajectoryCalculator.trace(
                minecraft.level,
                player,
                weapon.get().ballistics(),
                player.getXRot(),
                player.getYRot()
        );
        markedEntity = trajectory.entityHit()
                .map(hit -> glowEntityFor(hit.getEntity()))
                .orElse(null);
        markedBlock = markedEntity == null
                ? trajectory.blockHit().map(hit -> hit.getBlockPos().immutable())
                : Optional.empty();
    }

    private static Entity glowEntityFor(Entity collisionEntity) {
        if (collisionEntity instanceof EnderDragonPart dragonPart) {
            return dragonPart.parentMob;
        }
        return collisionEntity;
    }

    private static void clearMarker() {
        markedBlock = Optional.empty();
        markedEntity = null;
    }
}
