package org.minecraftagents.mixin;

import net.minecraft.client.render.GameRenderer;
import org.minecraftagents.RocketCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void minecraftAgentsCaptureRocketFrame(float tickDelta, long startTime, boolean tick, CallbackInfo callback) {
        RocketCapture.afterRender();
    }
}
