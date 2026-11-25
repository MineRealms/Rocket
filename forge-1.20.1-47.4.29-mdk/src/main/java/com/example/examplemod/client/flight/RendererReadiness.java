package com.example.examplemod.client.flight;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;

/** Optional Embeddium adapter; unknown renderer states keep the crossing gate closed. */
final class RendererReadiness {
    private static Method worldRenderer, manager, section, built, disposed, pending, building;
    private static boolean unavailable;

    private RendererReadiness() {}

    static boolean usesEmbeddium() { return ModList.get().isLoaded("embeddium"); }

    static String waitReason(LevelRenderer renderer, int x, int y, int z) {
        if (unavailable) return "Embeddium readiness adapter unavailable";
        try {
            if (worldRenderer == null) {
                worldRenderer = Class.forName("me.jellysquid.mods.sodium.client.world.WorldRendererExtended")
                        .getMethod("sodium$getWorldRenderer");
                manager = Class.forName("qouteall.imm_ptl.core.compat.mixin.sodium.IESodiumWorldRenderer")
                        .getMethod("ip_getRenderSectionManager");
                Class<?> managerType = Class.forName("me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager");
                section = managerType.getDeclaredMethod("getRenderSection", int.class, int.class, int.class);
                section.setAccessible(true);
                Class<?> sectionType = Class.forName("me.jellysquid.mods.sodium.client.render.chunk.RenderSection");
                built = sectionType.getMethod("isBuilt");
                disposed = sectionType.getMethod("isDisposed");
                pending = sectionType.getMethod("getPendingUpdate");
                building = sectionType.getMethod("getBuildCancellationToken");
            }
            Object targetRenderer = worldRenderer.invoke(renderer);
            Object targetManager = targetRenderer == null ? null : manager.invoke(targetRenderer);
            if (targetManager == null) return "awaiting Embeddium target renderer";
            Object targetSection = section.invoke(targetManager, x, y, z);
            if (targetSection == null || !(boolean) built.invoke(targetSection)
                    || (boolean) disposed.invoke(targetSection) || pending.invoke(targetSection) != null
                    || building.invoke(targetSection) != null) return "awaiting Embeddium arrival meshes";
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            unavailable = true;
            org.slf4j.LoggerFactory.getLogger(RendererReadiness.class).error("Cannot inspect target Embeddium meshes", error);
            return "Embeddium readiness adapter unavailable";
        }
    }
}
