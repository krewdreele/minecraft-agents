package org.minecraftagents.mixin;

import net.minecraft.client.MinecraftClient;
import org.minecraftagents.MinecraftAgentsClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftClient.class)
abstract class ClientInputMixin {
    @Inject(method = "doAttack", at = @At("HEAD"))
    private void minecraftAgentsRecordAttack(CallbackInfoReturnable<Boolean> callback) {
        MinecraftAgentsClient.mouseClicked(true);
    }

    @Inject(method = "doItemUse", at = @At("HEAD"))
    private void minecraftAgentsRecordUse(CallbackInfo callback) {
        MinecraftAgentsClient.mouseClicked(false);
    }
}
