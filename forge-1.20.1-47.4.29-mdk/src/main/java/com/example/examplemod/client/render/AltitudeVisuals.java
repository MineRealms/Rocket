package com.example.examplemod.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.example.examplemod.ExampleMod;
import com.example.examplemod.client.flight.ClientFlightState;
import com.example.examplemod.compat.immptl.ImmersivePortalsBackend;
import com.example.examplemod.flight.FlightMath;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import qouteall.imm_ptl.core.ClientWorldLoader;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.Random;

@Mod.EventBusSubscriber(modid = ExampleMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class AltitudeVisuals {
    private static final ResourceLocation OVERWORLD_TYPE = new ResourceLocation("minecraft", "overworld");
    private static final ResourceLocation SPACE_TYPE = new ResourceLocation("rocketmod", "space_type");
    private static final double PLANET_RADIUS = 1536;
    private static double anchorX;
    private static double anchorZ;
    private static boolean anchored;

    private AltitudeVisuals() {}

    private static boolean supportedLevel() {
        var level = Minecraft.getInstance().level;
        return level != null && (level.dimension().equals(Level.OVERWORLD)
                || level.dimension().equals(ImmersivePortalsBackend.SPACE_DIMENSION));
    }

    @SubscribeEvent
    public static void registerEffects(RegisterDimensionSpecialEffectsEvent event) {
        event.register(OVERWORLD_TYPE, new RocketEffects(true));
        event.register(SPACE_TYPE, new RocketEffects(false));
    }

    @Mod.EventBusSubscriber(modid = ExampleMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class Viewport {
        private Viewport() {}

        @SubscribeEvent
        public static void fogColor(ViewportEvent.ComputeFogColor event) {
            Camera camera = event.getCamera();
            if (camera.getEntity() == null || !supportedLevel() || camera.getFluidInCamera() != FogType.NONE) return;
            float atmo = atmosphere(ClientFlightState.altitude((float) event.getPartialTick()));
            event.setRed(Mth.lerp(atmo, event.getRed(), 0.018f));
            event.setGreen(Mth.lerp(atmo, event.getGreen(), 0.025f));
            event.setBlue(Mth.lerp(atmo, event.getBlue(), 0.055f));
        }

        @SubscribeEvent
        public static void fogDistance(ViewportEvent.RenderFog event) {
            Camera camera = event.getCamera();
            if (camera.getEntity() == null || !supportedLevel() || event.getType() != FogType.NONE) return;
            float atmo = atmosphere(ClientFlightState.altitude((float) event.getPartialTick()));
            event.scaleFarPlaneDistance(Mth.lerp(atmo, 0.42f, 1.0f));
            event.setNearPlaneDistance(Mth.lerp(atmo, 0.0f, event.getNearPlaneDistance()));
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
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) return;
            Camera camera = event.getCamera();
            if (camera.getEntity() == null) return;
            var passDimension = ClientWorldLoader.worldRendererMap.entrySet().stream()
                    .filter(entry -> entry.getValue() == event.getLevelRenderer())
                    .map(java.util.Map.Entry::getKey).findFirst()
                    .orElseGet(() -> camera.getEntity().level().dimension());
            boolean space = passDimension.equals(ImmersivePortalsBackend.SPACE_DIMENSION);
            double h = virtualAltitude(camera.getPosition().y, space);
            if (!anchored || (!space && h < 128)) {
                anchorX = camera.getPosition().x;
                anchorZ = camera.getPosition().z;
                anchored = true;
            }
            float alpha = FlightMath.smoothRange(h, 128, 520);
            if (!passDimension.equals(Level.OVERWORLD) && !passDimension.equals(ImmersivePortalsBackend.SPACE_DIMENSION)) return;
            try (RenderState ignored = new RenderState()) {
                drawSky(event.getPoseStack(), atmosphere(h), event.getRenderTick() + event.getPartialTick());
                if (alpha > 0.005f) drawPlanet(event.getPoseStack(), camera.getPosition(), space, alpha);
            }
        }
    }

    private static final class RocketEffects extends DimensionSpecialEffects {
        private final boolean overworld;

        private RocketEffects(boolean overworld) {
            super(128.0f, !overworld, SkyType.NORMAL, false, false);
            this.overworld = overworld;
        }

        @Override
        public boolean renderSky(ClientLevel level, int ticks, float partialTick, PoseStack pose, Camera camera,
                                 Matrix4f projection, boolean foggy, Runnable setupFog) {
            double h = virtualAltitude(camera.getPosition().y,
                    level.dimension().equals(ImmersivePortalsBackend.SPACE_DIMENSION));
            // Keep the native sun, moon and day/night sky. AFTER_SKY adds the fading space layer.
            return !overworld;
        }

        @Override
        public boolean renderClouds(ClientLevel level, int ticks, float partialTick, PoseStack pose,
                                    double camX, double camY, double camZ, Matrix4f projection) {
            double h = virtualAltitude(camY, level.dimension().equals(ImmersivePortalsBackend.SPACE_DIMENSION));
            float opacity = 1.0f - atmosphere(h);
            if (overworld && h < 160) return false;
            try (RenderState ignored = new RenderState()) {
            if (opacity > 0.005f)
                drawClouds(pose, camX, camY, camZ, level.getCloudColor(partialTick), opacity, ticks + partialTick,
                        level.dimension().equals(ImmersivePortalsBackend.SPACE_DIMENSION));
            }
            return true;
        }

        @Override
        public Vec3 getBrightnessDependentFogColor(Vec3 color, float sunHeight) {
            return overworld ? color : new Vec3(color.x * 0.12, color.y * 0.15, color.z * 0.26);
        }

        @Override
        public boolean isFoggyAt(int x, int z) { return false; }
    }

    private static double virtualAltitude(double y, boolean space) {
        double threshold = ClientFlightState.hasServerConfig() ? ClientFlightState.transitionAltitude() : 512.0;
        return FlightMath.altitude(y, space, threshold);
    }

    private static float atmosphere(double y, boolean space) { return atmosphere(virtualAltitude(y, space)); }
    private static float atmosphere(double altitude) { return FlightMath.smoothRange(altitude, 80.0, 520.0); }

    private static void drawSky(PoseStack pose, float darkness, float time) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        Matrix4f m = pose.last().pose();
        float radius = 100;
        for (int lat = 0; lat < 12; lat++) for (int lon = 0; lon < 32; lon++) {
            float p0 = (float) (-Math.PI / 2 + Math.PI * lat / 12), p1 = (float) (-Math.PI / 2 + Math.PI * (lat + 1) / 12);
            float t0 = (float) (Math.PI * 2 * lon / 32), t1 = (float) (Math.PI * 2 * (lon + 1) / 32);
            Vec3 a = sphere(radius, p0, t0), c = sphere(radius, p1, t0), d = sphere(radius, p1, t1), e = sphere(radius, p0, t1);
            float c0 = 0.18f * (1 - darkness), c1 = 0.42f * (1 - darkness);
            skyVertex(b, m, a, darkness, c0); skyVertex(b, m, c, darkness, c1); skyVertex(b, m, d, darkness, c1);
            skyVertex(b, m, a, darkness, c0); skyVertex(b, m, d, darkness, c1); skyVertex(b, m, e, darkness, c0);
        }
        if (darkness > 0.03f) {
            Random random = new Random(0x524f434b45544cL);
            float alpha = Mth.clamp((darkness - 0.12f) / 0.88f, 0, 1);
            for (int i = 0; i < 850; i++) {
                float lat = (random.nextFloat() - 0.5f) * (float) Math.PI, lon = random.nextFloat() * (float) (Math.PI * 2);
                Vec3 p = sphere(90, lat, lon), normal = p.normalize();
                float size = i % 37 == 0 ? 0.14f : 0.065f;
                float twinkle = 0.72f + 0.28f * (float) Math.sin(time * 0.035f + i * 2.71);
                float a = alpha * twinkle;
                Vec3 right = normal.cross(Math.abs(normal.y) > 0.9 ? new Vec3(1,0,0) : new Vec3(0,1,0)).normalize().scale(size);
                Vec3 up = right.cross(normal).normalize().scale(size);
                Vec3 v0=p.subtract(right).subtract(up), v1=p.add(right).subtract(up);
                Vec3 v2=p.add(right).add(up), v3=p.subtract(right).add(up);
                vertex(b,m,v0,1,0.97f,0.9f,a); vertex(b,m,v1,1,0.97f,0.9f,a); vertex(b,m,v2,1,0.97f,0.9f,a);
                vertex(b,m,v0,1,0.97f,0.9f,a); vertex(b,m,v2,1,0.97f,0.9f,a); vertex(b,m,v3,1,0.97f,0.9f,a);
            }
        }
        BufferUploader.drawWithShader(b.end());
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static void skyVertex(BufferBuilder b, Matrix4f m, Vec3 p, float darkness, float blue) {
        vertex(b, m, p, 0.006f, 0.009f, 0.018f, darkness);
    }

    private static void drawClouds(PoseStack pose, double x, double y, double z, Vec3 color, float alpha, float time, boolean space) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc(); RenderSystem.disableCull();
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        Matrix4f m = pose.last().pose();
        double offset = space ? (ClientFlightState.hasServerConfig() ? ClientFlightState.transitionAltitude() : 512.0) - 64.0 : 0.0;
        float cy = (float) (128.0 - offset-y), shift = (time * 0.025f) % 128, span = 768;
        int grid = 12;
        for (int ix=-grid/2; ix<grid/2; ix++) for (int iz=-grid/2; iz<grid/2; iz++) {
            long seed = (long)(Math.floor(x/128)+ix)*341873128712L ^ (long)(Math.floor(z/128)+iz)*132897987541L;
            Random random = new Random(seed);
            if (random.nextFloat()<0.34f) continue;
            float x0=(float)(Math.floor(x/128)*128-x)+ix*span/grid-shift;
            float z0=(float)(Math.floor(z/128)*128-z)+iz*span/grid;
            float w=span/grid*(0.45f+random.nextFloat()*0.55f), d=span/grid*(0.45f+random.nextFloat()*0.55f);
            float a=alpha*(0.22f+random.nextFloat()*0.18f), r=(float)color.x, g=(float)color.y, bl=(float)color.z;
            vertex(b,m,new Vec3(x0,cy,z0),r,g,bl,a); vertex(b,m,new Vec3(x0,cy,z0+d),r,g,bl,a);
            vertex(b,m,new Vec3(x0+w,cy,z0+d),r,g,bl,a); vertex(b,m,new Vec3(x0+w,cy,z0),r,g,bl,a);
        }
        BufferUploader.drawWithShader(b.end());
        RenderSystem.enableCull(); RenderSystem.disableBlend();
    }

    private static void drawPlanet(PoseStack pose, Vec3 camera, boolean space, float alpha) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableCull();
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        Matrix4f m = pose.last().pose();
        double altitude = virtualAltitude(camera.y, space) - 64;
        double scale = 80 / (PLANET_RADIUS + Math.max(1, altitude));
        double centerY = -PLANET_RADIUS;
        pose.pushPose();
        // Render the distant proxy inside the sky frustum while preserving its angular size.
        pose.scale((float) scale, (float) scale, (float) scale);
        pose.translate(anchorX-camera.x,-altitude,anchorZ-camera.z);
        m=pose.last().pose();
        int latitudes = 40, longitudes = 80;
        for (int lat = 0; lat < latitudes; lat++) for (int lon = 0; lon < longitudes; lon++) {
            float p0=(float)(-Math.PI/2+Math.PI*lat/latitudes), p1=(float)(-Math.PI/2+Math.PI*(lat+1)/latitudes);
            float t0=(float)(Math.PI*2*lon/longitudes), t1=(float)(Math.PI*2*(lon+1)/longitudes);
            Vec3 a=planetPoint(p0,t0,centerY), c=planetPoint(p1,t0,centerY);
            Vec3 d=planetPoint(p1,t1,centerY), e=planetPoint(p0,t1,centerY);
            planetVertex(b,m,a,p0,t0,alpha); planetVertex(b,m,c,p1,t0,alpha); planetVertex(b,m,d,p1,t1,alpha);
            planetVertex(b,m,a,p0,t0,alpha); planetVertex(b,m,d,p1,t1,alpha); planetVertex(b,m,e,p0,t1,alpha);
        }
        BufferUploader.drawWithShader(b.end());
        pose.popPose();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static Vec3 planetPoint(float latitude, float longitude, double centerY) {
        float c=(float)Math.cos(latitude);
        return new Vec3(PLANET_RADIUS*c*(float)Math.cos(longitude), centerY+PLANET_RADIUS*(float)Math.sin(latitude),
                PLANET_RADIUS*c*(float)Math.sin(longitude));
    }

    private static void planetVertex(BufferBuilder b, Matrix4f m, Vec3 point, float latitude, float longitude, float alpha) {
        double noise=Math.sin(longitude*3.1+Math.sin(latitude*7))*Math.cos(latitude*4.2-longitude*1.7)
                +0.42*Math.sin(longitude*8.3+latitude*5.4);
        boolean land=noise>0.34+0.14*Math.sin(latitude*2);
        float r=land?0.25f:0.035f, g=land?0.38f:0.16f, blue=land?0.20f:0.42f;
        float polar=Mth.clamp(((float) Math.abs(point.z / PLANET_RADIUS)-0.88f)*8,0,1);
        r=Mth.lerp(polar,r,0.78f); g=Mth.lerp(polar,g,0.87f); blue=Mth.lerp(polar,blue,0.92f);
        float light=0.45f + 0.55f * Math.max(0, (float) ((point.x*0.45 + (point.y+PLANET_RADIUS)*0.75 + point.z*0.4) / PLANET_RADIUS));
        r*=light; g*=light; blue*=light;
        vertex(b,m,point,r,g,blue,alpha);
    }

    private static Vec3 sphere(float r, float latitude, float longitude) {
        float c=(float)Math.cos(latitude);
        return new Vec3(r*c*(float)Math.cos(longitude),r*(float)Math.sin(latitude),r*c*(float)Math.sin(longitude));
    }

    private static void vertex(BufferBuilder b, Matrix4f matrix, Vec3 p, float r, float g, float blue, float a) {
        b.vertex(matrix,(float)p.x,(float)p.y,(float)p.z).color(r,g,blue,a).endVertex();
    }

    private static final class RenderState implements AutoCloseable {
        private final boolean depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST), blend=GL11.glIsEnabled(GL11.GL_BLEND);
        private final boolean cull=GL11.glIsEnabled(GL11.GL_CULL_FACE), mask=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        private final net.minecraft.client.renderer.ShaderInstance shader=RenderSystem.getShader();
        @Override public void close() {
            if(depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if(blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            if(cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
            RenderSystem.depthMask(mask);
            RenderSystem.setShader(() -> shader);
        }
    }
}
