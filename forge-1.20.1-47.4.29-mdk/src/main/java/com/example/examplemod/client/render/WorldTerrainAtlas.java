package com.example.examplemod.client.render;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.network.TerrainNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.joml.Vector3f;

import java.util.Arrays;

@Mod.EventBusSubscriber(modid = ExampleMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class WorldTerrainAtlas {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "dynamic/world_terrain_atlas");
    private static final int SIZE = 128, CELL = 256;
    private static final int[] pixels = new int[SIZE * SIZE];
    private static final int[] samples = new int[SIZE * SIZE];
    private static DynamicTexture texture;
    private static boolean dirty;
    private static int centerX, centerZ;
    private static String status = "waiting for server samples";

    private WorldTerrainAtlas() {}

    @Mod.EventBusSubscriber(modid = ExampleMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Setup {
        @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
            TerrainNetwork.setClientReceiver(WorldTerrainAtlas::accept);
        }
    }

    public static int textureId() { ensureTexture(); return texture.getId(); }
    public static Vector3f info() {
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        return new Vector3f((float) (camera.x - centerX), (float) (camera.z - centerZ), SIZE * CELL);
    }
    public static Vector3f origin() { return new Vector3f(centerX, centerZ, 0); }
    public static String status() { return status; }
    public static int sampledCells() {
        int count = 0;
        for (int pixel : pixels) if ((pixel >>> 24) != 0) count++;
        return count;
    }
    public static void invalidate() {
        if (texture != null) Minecraft.getInstance().getTextureManager().release(TEXTURE);
        texture = null; dirty = true;
    }

    public static void accept(TerrainNetwork.Tile tile) {
        if (!tile.valid() || Minecraft.getInstance().level == null) return;
        if (tile.reset() || centerX != tile.originX() || centerZ != tile.originZ()) {
            Arrays.fill(pixels, 0); Arrays.fill(samples, 0);
            centerX = tile.originX(); centerZ = tile.originZ();
        }
        byte[] rgba = tile.rgba();
        int color = (rgba[3] & 255) << 24 | (rgba[2] & 255) << 16 | (rgba[1] & 255) << 8 | rgba[0] & 255;
        samples[tile.startZ() * SIZE + tile.startX()] = color;
        status = tile.status(); dirty = true;
    }

    private static int reconstruct(int x, int z) {
        int fallback = 0;
        for (int step = 1; step <= 8; step *= 2) {
            int x0 = x / step * step, z0 = z / step * step;
            int x1 = Math.min(x0 + step, SIZE - step), z1 = Math.min(z0 + step, SIZE - step);
            int a = samples[z0 * SIZE + x0], b = samples[z0 * SIZE + x1];
            int c = samples[z1 * SIZE + x0], d = samples[z1 * SIZE + x1];
            if ((a >>> 24) != 0 && fallback == 0) fallback = a;
            if ((a >>> 24) == 0 || (b >>> 24) == 0 || (c >>> 24) == 0 || (d >>> 24) == 0) continue;
            float u = (float) (x - x0) / step, v = (float) (z - z0) / step;
            int color = 0;
            // Interpolate genuine neighboring samples at the finest completed level.
            for (int shift = 0; shift <= 24; shift += 8) {
                float top = (a >>> shift & 255) * (1 - u) + (b >>> shift & 255) * u;
                float bottom = (c >>> shift & 255) * (1 - u) + (d >>> shift & 255) * u;
                color |= Math.round(top * (1 - v) + bottom * v) << shift;
            }
            return color;
        }
        return fallback;
    }

    private static void ensureTexture() {
        if (texture == null) {
            texture = new DynamicTexture(SIZE, SIZE, false);
            texture.setFilter(true, false);
            Minecraft.getInstance().getTextureManager().register(TEXTURE, texture);
            dirty = true;
        }
        if (dirty) {
            for (int z = 0; z < SIZE; z++) for (int x = 0; x < SIZE; x++) {
                pixels[z * SIZE + x] = reconstruct(x, z);
                texture.getPixels().setPixelRGBA(x, z, pixels[z * SIZE + x]);
            }
            texture.upload(); dirty = false;
        }
    }

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END && texture != null && dirty) ensureTexture();
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        invalidate(); Arrays.fill(pixels, 0); Arrays.fill(samples, 0); centerX = 0; centerZ = 0;
        status = "waiting for server samples";
    }
}
