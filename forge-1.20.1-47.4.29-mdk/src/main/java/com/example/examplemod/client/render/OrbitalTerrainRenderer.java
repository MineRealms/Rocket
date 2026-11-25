package com.example.examplemod.client.render;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.client.flight.ClientFlightState;
import com.example.examplemod.flight.FlightMath;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import qouteall.imm_ptl.core.ClientWorldLoader;

import java.util.Arrays;
import java.util.Comparator;

@Mod.EventBusSubscriber(modid = ExampleMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class OrbitalTerrainRenderer {
    private static final int MAP_SIZE = 256;
    private static final int CELL_SIZE = 4;
    private static final int SAMPLES_PER_TICK = 1024;
    private static final double HALF_WORLD = 8192;
    private static final ResourceLocation TERRAIN = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "dynamic/orbital_terrain");
    private static final ResourceLocation[] EARTH = Arrays.stream(new String[]{"top", "bottom", "north", "south", "east", "west"})
            .map(face -> ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "textures/environment/earth_" + face + ".png"))
            .toArray(ResourceLocation[]::new);
    private static final Integer[] SAMPLE_ORDER = sampleOrder();
    private static DynamicTexture terrain;
    private static boolean terrainInvalid;
    private static int anchorX;
    private static int anchorZ;
    private static int cursor;
    private static int knownSamples;
    private static final boolean[] known = new boolean[MAP_SIZE * MAP_SIZE];

    private OrbitalTerrainRenderer() {}

    public static int sampledCells() { return knownSamples; }
    public static void invalidateTerrain() { terrainInvalid = true; }

    public static int terrainTexture() {
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        ensureTerrain(camera.x, camera.z);
        return terrain.getId();
    }

    public static int earthTexture(int face) {
        return Minecraft.getInstance().getTextureManager().getTexture(EARTH[face]).getId();
    }

    public static Vector3f mapInfo() {
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        ensureTerrain(camera.x, camera.z);
        return new Vector3f((float) (camera.x - anchorX), (float) (camera.z - anchorZ), MAP_SIZE * CELL_SIZE);
    }

    public static void prepareSky(ShaderInstance shader, Vec3 camera, double height) {
        ensureTerrain(camera.x, camera.z);
        RenderSystem.setShaderTexture(0, TERRAIN);
        shader.getUniform("MapWidth").set((float) MAP_SIZE * CELL_SIZE);
        shader.getUniform("MapTexel").set(1.0f / MAP_SIZE);
        shader.getUniform("MapOffset").set((float) (camera.x - anchorX), (float) (camera.z - anchorZ));
        shader.getUniform("NearAltitude").set((float) Math.max(1, height - 64));
        shader.getUniform("TerrainBlend").set(FlightMath.smoothRange(height, 240, 640));
    }

    public static void render(PoseStack pose, Vec3 camera, double height, double centerX, double centerZ, float alpha,
                              float partialTick) {
        ShaderInstance shader = OrbitShaders.surface();
        if (shader == null) return;
        double threshold = ClientFlightState.transitionAltitude();
        float orbit = FlightMath.orbitBlend(height, threshold);
        if (orbit <= 0.001f) return;
        double altitude = Math.max(1, height - 64) + orbit * 20000;
        double scale = 80 / (HALF_WORLD * 3 + altitude);
        pose.pushPose();
        pose.scale((float) scale, (float) scale, (float) scale);
        pose.translate(centerX - camera.x, -altitude, centerZ - camera.z);
        RenderSystem.setShader(OrbitShaders::surface);
        shader.getUniform("VisualAltitude").set((float) height);
        Vector3f eye = new Vector3f((float) (camera.x - centerX), (float) altitude, (float) (camera.z - centerZ));
        eye.rotateX((float) Math.toRadians(-28 * orbit)).rotateZ((float) Math.toRadians(32 * orbit));
        shader.getUniform("EyePosition").set(eye.x, eye.y, eye.z);
        Vector3f sun = AtmosphereRenderer.sunDirection(partialTick);
        sun.rotateX((float) Math.toRadians(-28 * orbit)).rotateZ((float) Math.toRadians(32 * orbit));
        shader.getUniform("SunDirection").set(sun.x, sun.y, sun.z);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableCull();
        pose.mulPose(Axis.XP.rotationDegrees(28 * orbit));
        pose.mulPose(Axis.ZP.rotationDegrees(-32 * orbit));
        shader.getUniform("SurfacePoseMat").set(pose.last().pose());
        cube(pose.last().pose(), alpha * orbit, shader);
        pose.popPose();
    }

    private static void cube(Matrix4f matrix, float alpha, ShaderInstance shader) {
        double w = HALF_WORLD, bottom = -w * 2;
        double[][][] faces = {
                {{-w, 0, -w}, {-w, 0, w}, {w, 0, w}, {w, 0, -w}},
                {{-w, bottom, w}, {-w, bottom, -w}, {w, bottom, -w}, {w, bottom, w}},
                {{-w, 0, -w}, {w, 0, -w}, {w, bottom, -w}, {-w, bottom, -w}},
                {{w, 0, w}, {-w, 0, w}, {-w, bottom, w}, {w, bottom, w}},
                {{w, 0, -w}, {w, 0, w}, {w, bottom, w}, {w, bottom, -w}},
                {{-w, 0, w}, {-w, 0, -w}, {-w, bottom, -w}, {-w, bottom, w}}};
        float[][] normals = {{0, 1, 0}, {0, -1, 0}, {0, 0, -1}, {0, 0, 1}, {1, 0, 0}, {-1, 0, 0}};
        for (int face = 0; face < faces.length; face++) {
            shader.getUniform("FaceNormal").set(normals[face][0], normals[face][1], normals[face][2]);
            RenderSystem.setShaderTexture(0, EARTH[face]);
            BufferBuilder buffer = Tesselator.getInstance().getBuilder();
            buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            quad(buffer, matrix, faces[face], 1, alpha, face >= 2);
            BufferUploader.drawWithShader(buffer.end());
        }
    }

    private static void quad(BufferBuilder buffer, Matrix4f matrix, double[][] points, float light, float alpha, boolean side) {
        for (int i = 0; i < 4; i++) {
            double[] p = points[i];
            float u = i >= 2 ? 1 : 0, v = i == 1 || i == 2 ? 1 : 0;
            if (side) { u = i == 1 || i == 2 ? 1 : 0; v = i >= 2 ? 1 : 0; }
            buffer.vertex((float) p[0], (float) p[1], (float) p[2]).uv(u, v)
                    .color(light, light, light, alpha).endVertex();
        }
    }

    private static void ensureTerrain(double x, double z) {
        if (terrainInvalid) {
            if (terrain != null) Minecraft.getInstance().getTextureManager().release(TERRAIN);
            terrain = null;
            terrainInvalid = false;
        }
        if (terrain != null && Math.abs(x - anchorX) < 128 && Math.abs(z - anchorZ) < 128) return;
        int nextX = Mth.floor(x / 128) * 128, nextZ = Mth.floor(z / 128) * 128;
        if (terrain == null) {
            terrain = new DynamicTexture(MAP_SIZE, MAP_SIZE, false);
            terrain.setFilter(false, false);
            Minecraft.getInstance().getTextureManager().register(TERRAIN, terrain);
        }
        anchorX = nextX;
        anchorZ = nextZ;
        cursor = 0;
        knownSamples = 0;
        Arrays.fill(known, false);
        NativeImage image = terrain.getPixels();
        for (int px = 0; px < MAP_SIZE; px++) for (int pz = 0; pz < MAP_SIZE; pz++) image.setPixelRGBA(px, pz, 0);
        terrain.upload();
    }

    @SubscribeEvent
    public static void sample(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || terrain == null || terrainInvalid || Minecraft.getInstance().level == null) return;
        ClientLevel level = Minecraft.getInstance().level.dimension().equals(Level.OVERWORLD) ? Minecraft.getInstance().level
                : ClientWorldLoader.getIsInitialized() ? ClientWorldLoader.getClientWorlds().stream()
                .filter(candidate -> candidate.dimension().equals(Level.OVERWORLD)).findFirst().orElse(null) : null;
        if (level == null) return;
        NativeImage image = terrain.getPixels();
        boolean dirty = false;
        for (int count = 0; count < SAMPLES_PER_TICK; count++) {
            int index = SAMPLE_ORDER[cursor++ % SAMPLE_ORDER.length], px = index % MAP_SIZE, pz = index / MAP_SIZE;
            int x = anchorX + (px - MAP_SIZE / 2) * CELL_SIZE, z = anchorZ + (pz - MAP_SIZE / 2) * CELL_SIZE;
            if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) continue;
            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
            if (y < level.getMinBuildHeight()) continue;
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            int color = state.getMapColor(level, pos).col;
            if (state.is(Blocks.GRASS_BLOCK) || color == 0) color = BiomeColors.getAverageGrassColor(level, pos);
            else if (state.is(BlockTags.LEAVES)) color = BiomeColors.getAverageFoliageColor(level, pos);
            else if (!state.getFluidState().isEmpty()) color = BiomeColors.getAverageWaterColor(level, pos);
            int r = color >> 16 & 255, g = color >> 8 & 255, b = color & 255;
            // Alpha stores height; zero means that the client has no column data.
            int encodedHeight = Mth.clamp(1 + (y + 64) * 254 / 384, 1, 255);
            image.setPixelRGBA(px, pz, encodedHeight << 24 | b << 16 | g << 8 | r);
            if (!known[index]) { known[index] = true; knownSamples++; }
            dirty = true;
        }
        if (dirty) terrain.upload();
    }

    private static Integer[] sampleOrder() {
        Integer[] order = new Integer[MAP_SIZE * MAP_SIZE];
        Arrays.setAll(order, index -> index);
        Arrays.sort(order, Comparator.comparingInt(index -> {
            int x = index % MAP_SIZE - MAP_SIZE / 2, z = index / MAP_SIZE - MAP_SIZE / 2;
            return x * x + z * z;
        }));
        return order;
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        if (terrain != null) Minecraft.getInstance().getTextureManager().release(TERRAIN);
        terrain = null;
        terrainInvalid = false;
        knownSamples = 0;
    }
}
