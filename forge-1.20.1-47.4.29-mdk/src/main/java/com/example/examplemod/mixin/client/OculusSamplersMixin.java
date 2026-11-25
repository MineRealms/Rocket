package com.example.examplemod.mixin.client;

import com.example.examplemod.client.render.ShaderpackBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.samplers.IrisSamplers", remap = false)
public abstract class OculusSamplersMixin {
    @Inject(method = "addCompositeSamplers", at = @At("TAIL"), remap = false)
    private static void rocketSamplers(@Coerce Object holder, @Coerce Object targets, CallbackInfo ci) {
        ShaderpackBridge.addSamplers(holder);
    }
}
