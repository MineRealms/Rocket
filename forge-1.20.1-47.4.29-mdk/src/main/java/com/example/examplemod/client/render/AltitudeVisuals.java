package com.example.examplemod.client.render;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.client.flight.ClientFlightState;
import com.example.examplemod.compat.immptl.ImmersivePortalsBackend;
import com.example.examplemod.flight.FlightMath;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

@Mod.EventBusSubscriber(modid = ExampleMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class AltitudeVisuals {
    private static double anchorX;
    private static double anchorZ;
    private static boolean anchored;

    private AltitudeVisuals() {}

    @SubscribeEvent
    public static void registerEffects(RegisterDimensionSpecialEffectsEvent event) {
        event.register(ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), new RocketEffects());
        event.register(ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "space_type"), new RocketEffects());
    }

    private static boolean fusedPass() {
        return PortalRendering.isRendering() && PortalRendering.getRenderingPortal().isFuseView();
    }

    private static boolean spacePass() {
        return Minecraft.getInstance().level != null
                && Minecraft.getInstance().level.dimension().equals(ImmersivePortalsBackend.SPACE_DIMENSION);
    }

    private static boolean supportedPass() {
        var level = Minecraft.getInstance().level;
        return level != null && (level.dimension().equals(Level.OVERWORLD)
                || level.dimension().equals(ImmersivePortalsBackend.SPACE_DIMENSION));
    }

    private static double height(Camera camera) {
        return FlightMath.altitude(camera.getPosition().y, spacePass(), ClientFlightState.transitionAltitude());
    }

    @Mod.EventBusSubscriber(modid = ExampleMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class Viewport {
        private Viewport() {}

        @SubscribeEvent
        public static void fogColor(ViewportEvent.ComputeFogColor event) {
            if (!supportedPass() || event.getCamera().getFluidInCamera() != FogType.NONE) return;
            float weight = AtmosphereRenderer.darkness(height(event.getCamera()));
            event.setRed(Mth.lerp(weight, event.getRed(), 0.055f));
            event.setGreen(Mth.lerp(weight, event.getGreen(), 0.09f));
            event.setBlue(Mth.lerp(weight, event.getBlue(), 0.15f));
        }

        @SubscribeEvent
        public static void fogDistance(ViewportEvent.RenderFog event) {
            if (!supportedPass() || event.getType() != FogType.NONE) return;
            float weight = FlightMath.smoothRange(height(event.getCamera()), 180,
                    ClientFlightState.transitionAltitude() * 0.7);
            if (weight <= 0) return;
            float far = Mth.lerp(weight, event.getFarPlaneDistance(), Math.max(1024, event.getFarPlaneDistance()));
            event.setNearPlaneDistance(Math.max(0.25f, Mth.lerp(weight, event.getNearPlaneDistance(), far * 0.35f)));
            event.setFarPlaneDistance(far);
            event.setCanceled(true);
        }
    }

    @Mod.EventBusSubscriber(modid = ExampleMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class WorldRender {
        private WorldRender() {}

        @SubscribeEvent
        public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { anchored = false; }

        @SubscribeEvent
        public static void planet(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY || !supportedPass() || fusedPass()) return;
            Camera camera = event.getCamera();
            if (camera.getEntity() == null) return;
            double h = height(camera);
            if (!Double.isFinite(h)) return;
            if (!anchored || (!spacePass() && h < 180)) {
                anchorX = camera.getPosition().x;
                anchorZ = camera.getPosition().z;
                anchored = true;
            }
            float alpha = FlightMath.smoothRange(h, 240, 640);
            if (alpha <= 0.005f) return;
            try (RenderState ignored = new RenderState()) {
                OrbitalTerrainRenderer.render(event.getPoseStack(), camera.getPosition(), h, anchorX, anchorZ, alpha);
            }
        }
    }

    private static final class RocketEffects extends DimensionSpecialEffects {
        RocketEffects() { super(128, false, SkyType.NORMAL, false, false); }

        @Override
        public boolean renderSky(ClientLevel level, int ticks, float partialTick, PoseStack pose, Camera camera,
                                 Matrix4f projection, boolean foggy, Runnable setupFog) {
            if (fusedPass()) return true;
            try (RenderState ignored = new RenderState()) {
                AtmosphereRenderer.render(pose, height(camera), partialTick);
            }
            return true;
        }

        @Override
        public boolean renderClouds(ClientLevel level, int ticks, float partialTick, PoseStack pose,
                                    double camX, double camY, double camZ, Matrix4f projection) {
            return fusedPass() || spacePass() || camY > ClientFlightState.transitionAltitude() * 0.4;
        }

        @Override
        public boolean renderSnowAndRain(ClientLevel level, int ticks, float partialTick, LightTexture light,
                                         double camX, double camY, double camZ) {
            return spacePass() || camY > 256;
        }

        @Override
        public boolean tickRain(ClientLevel level, int ticks, Camera camera) {
            return spacePass() || camera.getPosition().y > 256;
        }

        @Override
        public Vec3 getBrightnessDependentFogColor(Vec3 color, float sunHeight) { return color; }

        @Override
        public boolean isFoggyAt(int x, int z) { return false; }
    }

    private static final class RenderState implements AutoCloseable {
        private final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), blend = GL11.glIsEnabled(GL11.GL_BLEND);
        private final boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE), mask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        private final ShaderInstance shader = RenderSystem.getShader();
        private final int texture = RenderSystem.getShaderTexture(0);
        private final int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        private final int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        @Override public void close() {
            if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
            RenderSystem.depthMask(mask);
            RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            RenderSystem.setShader(shader != null ? () -> shader : GameRenderer::getPositionColorShader);
            RenderSystem.setShaderTexture(0, texture);
        }
    }
}
