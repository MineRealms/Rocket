package com.example.examplemod.mixin.client;

import com.example.examplemod.client.render.ShaderpackBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.CommonUniforms", remap = false)
public abstract class OculusUniformsMixin {
    @Inject(method = "generalCommonUniforms", at = @At("TAIL"), remap = false)
    private static void rocketUniforms(@Coerce Object holder, @Coerce Object notifier,
                                      @Coerce Object directives, CallbackInfo ci) {
        ShaderpackBridge.addUniforms(holder);
    }
}
