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
import net.minecraft.client.renderer.GameRenderer;
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
import qouteall.imm_ptl.core.ClientWorldLoader;

import java.util.Arrays;
import java.util.Comparator;

@Mod.EventBusSubscriber(modid = ExampleMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class OrbitalTerrainRenderer {
    private static final int MAP_SIZE = 64;
    private static final int CELL_SIZE = 16;
    private static final int SAMPLES_PER_TICK = 128;
    private static final double HALF_WORLD = 8192;
    private static final ResourceLocation TERRAIN = new ResourceLocation(ExampleMod.MODID, "dynamic/orbital_terrain");
    private static final ResourceLocation[] EARTH = Arrays.stream(new String[]{"top", "bottom", "north", "south", "east", "west"})
            .map(face -> new ResourceLocation(ExampleMod.MODID, "textures/environment/earth_" + face + ".png"))
            .toArray(ResourceLocation[]::new);
    private static final Integer[] SAMPLE_ORDER = sampleOrder();
    private static DynamicTexture terrain;
    private static int anchorX;
    private static int anchorZ;
    private static int cursor;
    private static int knownSamples;
    private static final boolean[] known = new boolean[MAP_SIZE * MAP_SIZE];

    private OrbitalTerrainRenderer() {}

    public static int sampledCells() { return knownSamples; }

    public static void render(PoseStack pose, Vec3 camera, double height, double centerX, double centerZ, float alpha) {
        ensureTerrain(centerX, centerZ);
        double threshold = ClientFlightState.transitionAltitude();
        float orbit = FlightMath.orbitBlend(height, threshold);
        double altitude = Math.max(1, height - 64) + orbit * 20000;
        double scale = 80 / (HALF_WORLD * 3 + altitude);
        pose.pushPose();
        pose.scale((float) scale, (float) scale, (float) scale);
        pose.translate(centerX - camera.x, -altitude, centerZ - camera.z);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableCull();
        pose.mulPose(Axis.XP.rotationDegrees(12 * orbit));
        pose.mulPose(Axis.ZP.rotationDegrees(-16 * orbit));
        if (orbit > 0.001f) {
            cube(pose.last().pose(), alpha);
        }
        if (orbit < 0.999f) {
            flatGround(pose.last().pose(), alpha * (1 - orbit));
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            RenderSystem.setShaderTexture(0, TERRAIN);
            BufferBuilder buffer = Tesselator.getInstance().getBuilder();
            buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            double half = MAP_SIZE * CELL_SIZE / 2.0;
            quad(buffer, pose.last().pose(), new double[][]{{-half, 0, -half}, {-half, 0, half},
                    {half, 0, half}, {half, 0, -half}}, 1, alpha * (1 - orbit), false);
            BufferUploader.drawWithShader(buffer.end());
        }
        pose.popPose();
    }

    private static void flatGround(Matrix4f matrix, float alpha) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        double w = HALF_WORLD;
        for (double[] point : new double[][]{{-w, -1, -w}, {-w, -1, w}, {w, -1, w}, {w, -1, -w}}) {
            buffer.vertex(matrix, (float) point[0], (float) point[1], (float) point[2])
                    .color(84 / 255f, 114 / 255f, 71 / 255f, alpha).endVertex();
        }
        BufferUploader.drawWithShader(buffer.end());
    }

    private static void cube(Matrix4f matrix, float alpha) {
        double w = HALF_WORLD, bottom = -w * 2;
        double[][][] faces = {
                {{-w, 0, -w}, {-w, 0, w}, {w, 0, w}, {w, 0, -w}},
                {{-w, bottom, w}, {-w, bottom, -w}, {w, bottom, -w}, {w, bottom, w}},
                {{-w, 0, -w}, {w, 0, -w}, {w, bottom, -w}, {-w, bottom, -w}},
                {{w, 0, w}, {-w, 0, w}, {-w, bottom, w}, {w, bottom, w}},
                {{w, 0, -w}, {w, 0, w}, {w, bottom, w}, {w, bottom, -w}},
                {{-w, 0, w}, {-w, 0, -w}, {-w, bottom, -w}, {-w, bottom, w}}};
        float[] light = {1, 0.45f, 0.65f, 0.85f, 0.85f, 0.60f};
        for (int face = 0; face < faces.length; face++) {
            RenderSystem.setShaderTexture(0, EARTH[face]);
            BufferBuilder buffer = Tesselator.getInstance().getBuilder();
            buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            quad(buffer, matrix, faces[face], light[face], alpha, face >= 2);
            BufferUploader.drawWithShader(buffer.end());
        }
    }

    private static void quad(BufferBuilder buffer, Matrix4f matrix, double[][] points, float light, float alpha, boolean side) {
        for (int i = 0; i < 4; i++) {
            double[] p = points[i];
            float u = i >= 2 ? 1 : 0, v = i == 1 || i == 2 ? 1 : 0;
            if (side) { u = i == 1 || i == 2 ? 1 : 0; v = i >= 2 ? 1 : 0; }
            buffer.vertex(matrix, (float) p[0], (float) p[1], (float) p[2]).uv(u, v)
                    .color(light, light, light, alpha).endVertex();
        }
    }

    private static void ensureTerrain(double x, double z) {
        int nextX = Mth.floor(x / CELL_SIZE) * CELL_SIZE, nextZ = Mth.floor(z / CELL_SIZE) * CELL_SIZE;
        if (terrain != null && nextX == anchorX && nextZ == anchorZ) return;
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
        for (int px = 0; px < MAP_SIZE; px++) for (int pz = 0; pz < MAP_SIZE; pz++) image.setPixelRGBA(px, pz, 0xff477254);
        terrain.upload();
    }

    @SubscribeEvent
    public static void sample(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || terrain == null || Minecraft.getInstance().level == null) return;
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
            float shade = Mth.clamp(0.82f + (y - 64) / 320f, 0.72f, 1.05f);
            int r = Math.min(255, (int) ((color >> 16 & 255) * shade));
            int g = Math.min(255, (int) ((color >> 8 & 255) * shade));
            int b = Math.min(255, (int) ((color & 255) * shade));
            image.setPixelRGBA(px, pz, 0xff000000 | b << 16 | g << 8 | r);
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
        knownSamples = 0;
    }
}
