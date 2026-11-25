package com.example.examplemod.client.render;

import com.example.examplemod.ExampleMod;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;

@Mod.EventBusSubscriber(modid = ExampleMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class OrbitShaders {
    private static ShaderInstance surface;
    private static ShaderInstance sky;

    private OrbitShaders() {}

    public static ShaderInstance surface() { return surface; }
    public static ShaderInstance sky() { return sky; }

    @SubscribeEvent
    public static void register(RegisterShadersEvent event) throws IOException {
        OrbitalTerrainRenderer.invalidateTerrain();
        event.registerShader(new ShaderInstance(event.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "orbit_surface"), DefaultVertexFormat.POSITION_TEX_COLOR),
                shader -> surface = shader);
        event.registerShader(new ShaderInstance(event.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "orbit_sky"), DefaultVertexFormat.POSITION), shader -> sky = shader);
    }
}
