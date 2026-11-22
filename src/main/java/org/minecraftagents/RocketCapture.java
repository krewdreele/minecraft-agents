package org.minecraftagents;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import org.minecraftagents.tools.AgentRocket;

/** Captures only the in-game framebuffer after the requested viewpoint has rendered. */
public final class RocketCapture {
    private static Entity previousCamera;
    private static Perspective previousPerspective;
    private static boolean previousHud;
    private static MinecraftClient client;

    private RocketCapture() {
    }

    public static void afterRender() {
        var game = MinecraftClient.getInstance();
        var request = AgentRocket.capture();
        if (request == null || game.world == null || game.player == null
                || !game.player.getUuid().equals(request.player())
                || !game.world.getRegistryKey().equals(request.dimension())) {
            restore();
            return;
        }
        if (game.currentScreen != null || game.isPaused()) {
            return;
        }
        Entity camera = game.player;
        if (!request.goal()) {
            camera = game.getCameraEntity();
            if (camera == null || camera.isRemoved() || !camera.getUuid().equals(request.actor())) {
                camera = null;
                for (Entity entity : game.world.getEntities()) {
                    if (entity.getUuid().equals(request.actor())) {
                        camera = entity;
                        break;
                    }
                }
            }
            if (camera == null) {
                return;
            }
        }
        if (client == null) {
            client = game;
            previousCamera = game.getCameraEntity();
            previousPerspective = game.options.getPerspective();
            previousHud = game.options.hudHidden;
        }
        if (game.getCameraEntity() != camera || !game.options.hudHidden
                || game.options.getPerspective() != Perspective.FIRST_PERSON) {
            game.options.hudHidden = true;
            game.options.setPerspective(Perspective.FIRST_PERSON);
            game.setCameraEntity(camera);
            return;
        }
        if (request.frame().isDone()) {
            return;
        }
        if (request.goal() && !seesTarget(game, request)) {
            return;
        }
        try {
            request.frame().complete(rgbFrame(game));
        } catch (RuntimeException failure) {
            request.frame().completeExceptionally(failure);
        }
    }

    private static boolean seesTarget(MinecraftClient game, AgentRocket.Capture request) {
        if (request.targetEntity() != null) {
            return game.crosshairTarget instanceof EntityHitResult hit && hit.getEntity().getUuid().equals(request.targetEntity());
        }
        return game.crosshairTarget instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(request.target());
    }

    private static byte[] rgbFrame(MinecraftClient game) {
        int size = AgentRocket.IMAGE_SIZE;
        byte[] rgb = new byte[size * size * 3];
        try (var screenshot = ScreenshotRecorder.takeScreenshot(game.getFramebuffer());
             var resized = new NativeImage(size, size, false)) {
            screenshot.resizeSubRectTo(0, 0, screenshot.getWidth(), screenshot.getHeight(), resized);
            int offset = 0;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int abgr = resized.getColor(x, y);
                    rgb[offset++] = (byte) abgr;
                    rgb[offset++] = (byte) (abgr >>> 8);
                    rgb[offset++] = (byte) (abgr >>> 16);
                }
            }
        }
        return rgb;
    }

    private static void restore() {
        if (client == null) {
            return;
        }
        client.options.hudHidden = previousHud;
        client.options.setPerspective(previousPerspective);
        if (client.player != null) {
            Entity camera = previousCamera != null && !previousCamera.isRemoved()
                    && previousCamera.getWorld() == client.world ? previousCamera : client.player;
            client.setCameraEntity(camera);
        }
        previousCamera = null;
        client = null;
    }
}
