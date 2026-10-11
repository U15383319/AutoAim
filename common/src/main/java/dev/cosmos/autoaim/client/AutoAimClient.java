package dev.cosmos.autoaim.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.registry.client.keymappings.KeyMappingRegistry;
import dev.cosmos.autoaim.client.aim.AimSmoothing;
import dev.cosmos.autoaim.client.aim.AimSolution;
import dev.cosmos.autoaim.client.aim.AutoAimController;
import dev.cosmos.autoaim.client.aim.MeleeAimController;
import dev.cosmos.autoaim.client.config.AutoAimConfig;
import dev.cosmos.autoaim.client.melee.MeleeCombat;
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
    private static final AutoAimController PROJECTILE_AIM_CONTROLLER = new AutoAimController();
    private static final MeleeAimController MELEE_AIM_CONTROLLER = new MeleeAimController();

    private static boolean markerEnabled;
    private static Optional<BlockPos> markedBlock = Optional.empty();
    private static Entity markedEntity;
    private static long previousAimFrameNanos;

    private AutoAimClient() {
    }

    public static void init() {
        AutoAimConfig.load();
        markerEnabled = AutoAimConfig.markerEnabled();
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

    /** Called once per frame, after mouse processing and before camera rendering. */
    public static void onRenderFrame(Minecraft minecraft) {
        if (!canAutoAim(minecraft)) {
            PROJECTILE_AIM_CONTROLLER.clearTarget();
            MELEE_AIM_CONTROLLER.clearTarget();
            previousAimFrameNanos = 0L;
            return;
        }

        Optional<AimSolution> solution = currentAimSolution(minecraft);
        if (solution.isEmpty()) {
            previousAimFrameNanos = 0L;
            return;
        }

        long now = System.nanoTime();
        double elapsedSeconds = previousAimFrameNanos == 0L
                ? 0.0D
                : (now - previousAimFrameNanos) / 1.0E9D;
        previousAimFrameNanos = now;

        LocalPlayer player = minecraft.player;
        AimSolution aim = solution.get();
        AimSmoothing.Rotation rotation = AimSmoothing.step(
                player.getYRot(), player.getXRot(), aim.yaw(), aim.pitch(), elapsedSeconds
        );
        player.setYRot(rotation.yaw());
        player.setXRot(rotation.pitch());
        // The view is already smoothed per frame. Keep vanilla's previous-tick
        // angles in sync so interpolation (including riding) cannot pull it back.
        player.yRotO = rotation.yaw();
        player.xRotO = rotation.pitch();
    }

    public static boolean shouldSuppressMouseTurn(Entity entity) {
        Minecraft minecraft = Minecraft.getInstance();
        return entity == minecraft.player
                && canAutoAim(minecraft)
                && currentAimSolution(minecraft).isPresent();
    }

    private static boolean canAutoAim(Minecraft minecraft) {
        return HOLD_AUTO_AIM.isDown()
                && minecraft.screen == null
                && !minecraft.isPaused()
                && minecraft.isWindowActive()
                && minecraft.player != null
                && minecraft.level != null
                && minecraft.player.isAlive()
                && !minecraft.player.isSpectator();
    }

    private static Optional<AimSolution> currentAimSolution(Minecraft minecraft) {
        return MeleeCombat.isActive(minecraft.player)
                ? MELEE_AIM_CONTROLLER.aimSolution(minecraft.player)
                : PROJECTILE_AIM_CONTROLLER.aimSolution(minecraft.player);
    }

    private static void onClientTick(Minecraft minecraft) {
        while (TOGGLE_MARKER.consumeClick()) {
            markerEnabled = !markerEnabled;
            AutoAimConfig.setMarkerEnabled(markerEnabled);
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

        boolean meleeMode = MeleeCombat.isActive(minecraft.player);
        boolean aimKeyHeld = canAutoAim(minecraft);
        if (meleeMode) {
            PROJECTILE_AIM_CONTROLLER.clearTarget();
            MELEE_AIM_CONTROLLER.tick(minecraft, aimKeyHeld);
        } else {
            MELEE_AIM_CONTROLLER.clearTarget();
            PROJECTILE_AIM_CONTROLLER.tick(minecraft, aimKeyHeld);
        }
        updateMarker(minecraft, meleeMode);
    }

    private static void updateMarker(Minecraft minecraft, boolean meleeMode) {
        LocalPlayer player = minecraft.player;
        if (!markerEnabled || player == null || minecraft.level == null) {
            clearMarker();
            return;
        }

        if (meleeMode) {
            updateMeleeMarker(minecraft);
            return;
        }

        Optional<Entity> lockedTarget = PROJECTILE_AIM_CONTROLLER.lockedTarget();
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

    private static void updateMeleeMarker(Minecraft minecraft) {
        Optional<Entity> attackableTarget = MeleeCombat.pickAttackableEntity(minecraft);
        Optional<Entity> lockedTarget = MELEE_AIM_CONTROLLER.lockedTarget();

        if (lockedTarget.isPresent()) {
            Entity locked = lockedTarget.get();
            markedEntity = attackableTarget
                    .filter(hit -> MeleeCombat.isSameTarget(hit, locked))
                    .map(hit -> glowEntityFor(locked))
                    .orElse(null);
            markedBlock = Optional.empty();
            return;
        }

        markedEntity = attackableTarget.map(AutoAimClient::glowEntityFor).orElse(null);
        markedBlock = Optional.empty();
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
