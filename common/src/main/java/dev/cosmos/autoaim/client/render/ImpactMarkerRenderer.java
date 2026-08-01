package dev.cosmos.autoaim.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.cosmos.autoaim.client.AutoAimClient;
import dev.cosmos.autoaim.client.config.AutoAimConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

@Environment(EnvType.CLIENT)
public final class ImpactMarkerRenderer {
    private ImpactMarkerRenderer() {
    }

    public static void render(PoseStack poseStack, Camera camera) {
        Optional<BlockPos> markedBlock = AutoAimClient.markedBlock();
        if (markedBlock.isEmpty()) {
            return;
        }

        double pulse = 0.5D + 0.5D * Math.sin(Util.getMillis() * 0.009D);
        int color = AutoAimConfig.glowColor();
        float red = ((color >> 16) & 0xFF) / 255.0F;
        float green = ((color >> 8) & 0xFF) / 255.0F;
        float blue = (color & 0xFF) / 255.0F;
        float fillAlpha = (float) (0.08D + pulse * 0.18D);
        float lineAlpha = (float) (0.55D + pulse * 0.40D);

        AABB blockBox = new AABB(markedBlock.get()).inflate(0.003D);
        AABB pulseBox = blockBox.inflate(0.025D + pulse * 0.035D);
        Vec3 cameraPosition = camera.getPosition();

        poseStack.pushPose();
        poseStack.translate(-cameraPosition.x, -cameraPosition.y, -cameraPosition.z);

        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer fill = buffers.getBuffer(RenderType.debugFilledBox());
        LevelRenderer.addChainedFilledBoxVertices(
                poseStack,
                fill,
                blockBox.minX,
                blockBox.minY,
                blockBox.minZ,
                blockBox.maxX,
                blockBox.maxY,
                blockBox.maxZ,
                red,
                green,
                blue,
                fillAlpha
        );
        buffers.endBatch(RenderType.debugFilledBox());

        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(poseStack, lines, blockBox, red, green, blue, lineAlpha);
        LevelRenderer.renderLineBox(
                poseStack,
                lines,
                pulseBox,
                red,
                green,
                blue,
                lineAlpha * 0.55F
        );
        buffers.endBatch(RenderType.lines());

        poseStack.popPose();
    }
}
