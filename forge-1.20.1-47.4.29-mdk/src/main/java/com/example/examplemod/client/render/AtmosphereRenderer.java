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
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import qouteall.imm_ptl.core.ClientWorldLoader;

public final class AtmosphereRenderer {
    private static float lastSunAngle;
    private AtmosphereRenderer() {}

    public static void resetClock() { lastSunAngle = 0; }

    public static ClientLevel visualLevel() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || level.dimension().equals(Level.OVERWORLD)) return level;
        return ClientWorldLoader.getIsInitialized() ? ClientWorldLoader.getClientWorlds().stream()
                .filter(candidate -> candidate.dimension().equals(Level.OVERWORLD)).findFirst().orElse(null) : null;
    }

    public static Vector3f sunDirection(float partialTick) {
        return sunDirection(partialTick, ClientFlightState.altitude(partialTick));
    }

    public static Vector3f sunDirection(float partialTick, double height) {
        ClientLevel level = visualLevel();
        // Space has a fixed midnight; retain the last Overworld angle if its world is unavailable.
        if (level != null) lastSunAngle = level.getSunAngle(partialTick);
        Vector3f daylight = new Vector3f(-0.22f, 0.88f, 0.42f).normalize();
        return new Vector3f(-Mth.sin(lastSunAngle), Mth.cos(lastSunAngle), 0)
                .lerp(daylight, FlightMath.smoothRange(height, 1240, 1400)).normalize();
    }

    public static float airHeight(double height) {
        if (!Double.isFinite(height)) return 0.05f;
        double t = Mth.clamp((height - 64) / (ClientFlightState.transitionAltitude() - 64), 0, 1);
        return (float) (0.05 + 120 * Math.pow(t, 2.05));
    }

    public static float darkness(double height) {
        return FlightMath.smoothRange(airHeight(height), 8, 90);
    }

    public static void render(PoseStack pose, double height, float partialTick, float opacity) {
        if (!Double.isFinite(height)) return;
        ShaderInstance shader = OrbitShaders.sky();
        Minecraft client = Minecraft.getInstance();
        if (shader == null || client.level == null) return;
        Vector3f sun = sunDirection(partialTick, height);
        ClientLevel clock = visualLevel();
        shader.getUniform("SkyViewMat").set(pose.last().pose());
        shader.getUniform("AirHeight").set(airHeight(height));
        shader.getUniform("SunDirection").set(sun.x, sun.y, sun.z);
        shader.getUniform("SkyOpacity").set(opacity);
        shader.getUniform("MoonPhase").set(clock != null ? (float) clock.getMoonPhase() : 0);
        shader.getUniform("OrbitFade").set(FlightMath.orbitBlend(height, ClientFlightState.transitionAltitude()));
        shader.getUniform("RainWeight").set(clock != null ? clock.getRainLevel(partialTick) : 0);
        Vec3 camera = client.gameRenderer.getMainCamera().getPosition();
        float wind = (client.level.getGameTime() % 240000 + partialTick) * 0.035f;
        shader.getUniform("CloudOffset").set((float) (camera.x % 1000000) + wind, (float) (camera.z % 1000000));
        OrbitalTerrainRenderer.prepareSky(shader, client.gameRenderer.getMainCamera().getPosition(), height);
        RenderSystem.setShader(OrbitShaders::sky);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
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
