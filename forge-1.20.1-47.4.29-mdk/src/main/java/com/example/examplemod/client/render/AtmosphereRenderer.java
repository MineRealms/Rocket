package com.example.examplemod.client.render;

import com.example.examplemod.client.flight.ClientFlightState;
import com.example.examplemod.flight.FlightMath;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public final class AtmosphereRenderer {
    private AtmosphereRenderer() {}

    public static float airHeight(double height) {
        if (!Double.isFinite(height)) return 0.05f;
        double t = Mth.clamp((height - 64) / (ClientFlightState.transitionAltitude() - 64), 0, 1);
        return (float) (0.05 + 120 * Math.pow(t, 2.05));
    }

    public static float darkness(double height) {
        return FlightMath.smoothRange(airHeight(height), 8, 90);
    }

    public static void render(PoseStack pose, double height, float partialTick) {
        if (!Double.isFinite(height)) return;
        ShaderInstance shader = OrbitShaders.sky();
        Minecraft client = Minecraft.getInstance();
        if (shader == null || client.level == null) return;
        double angle = (client.level.getDayTime() % 24000 + partialTick) / 24000.0 * Math.PI * 2;
        float sx = (float) -Math.cos(angle), sy = (float) Math.sin(angle);
        shader.getUniform("SkyViewMat").set(pose.last().pose());
        shader.getUniform("AirHeight").set(airHeight(height));
        shader.getUniform("SunDirection").set(sx, sy, 0.12f);
        shader.getUniform("OrbitFade").set(FlightMath.orbitBlend(height, ClientFlightState.transitionAltitude()));
        shader.getUniform("RainWeight").set(client.level.getRainLevel(partialTick));
        Vec3 camera = client.gameRenderer.getMainCamera().getPosition();
        float wind = (client.level.getGameTime() % 240000 + partialTick) * 0.035f;
        shader.getUniform("CloudOffset").set((float) (camera.x % 1000000) + wind, (float) (camera.z % 1000000));
        OrbitalTerrainRenderer.prepareSky(shader, client.gameRenderer.getMainCamera().getPosition(), height);
        RenderSystem.setShader(OrbitShaders::sky);
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        float w = 64;
        float[][][] faces = {
                {{-w,w,-w},{-w,w,w},{w,w,w},{w,w,-w}},
                {{-w,-w,w},{-w,-w,-w},{w,-w,-w},{w,-w,w}},
                {{-w,w,-w},{w,w,-w},{w,-w,-w},{-w,-w,-w}},
                {{w,w,w},{-w,w,w},{-w,-w,w},{w,-w,w}},
                {{w,w,-w},{w,w,w},{w,-w,w},{w,-w,-w}},
                {{-w,w,w},{-w,w,-w},{-w,-w,-w},{-w,-w,w}}};
        for (float[][] face : faces) for (float[] vertex : face)
            buffer.vertex(vertex[0], vertex[1], vertex[2]).endVertex();
        BufferUploader.drawWithShader(buffer.end());
    }
}
