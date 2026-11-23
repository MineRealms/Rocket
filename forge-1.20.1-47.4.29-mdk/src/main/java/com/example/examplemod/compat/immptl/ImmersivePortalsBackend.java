package com.example.examplemod.compat.immptl;

import com.example.examplemod.Config;
import com.example.examplemod.ExampleMod;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.api.PortalAPI;
import qouteall.imm_ptl.core.chunk_loading.ChunkLoader;
import qouteall.imm_ptl.core.chunk_loading.DimensionalChunkPos;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = ExampleMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ImmersivePortalsBackend {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final ResourceKey<Level> SPACE_DIMENSION = ResourceKey.create(
            Registries.DIMENSION, new ResourceLocation(ExampleMod.MODID, "space"));
    public static final double SPACE_ENTRY_HEIGHT = 64.0;
    private static final String PORTAL_TAG = "rocketmod:debug_elytra_boundary";
    private static final double PORTAL_SIZE = 60_000_000.0;
    private static final double PRELOAD_DISTANCE = 128.0;
    private static final int PRELOAD_RADIUS = 2;
    private static final Map<UUID, ChunkLease> CHUNK_LEASES = new HashMap<>();
    private static double installedAltitude = Double.NaN;
    private static boolean installedEnabled;

    private ImmersivePortalsBackend() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        CHUNK_LEASES.clear();
        removeOwnedPortals(event.getServer());
        installedAltitude = Double.NaN;
        reconcile(event.getServer());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        boolean configChanged = installedEnabled != Config.debugElytraSpaceFlight
                || Double.compare(installedAltitude, Config.flightTransitionAltitude) != 0;
        if (configChanged || server.getTickCount() % 20 == 0) {
            reconcile(server);
            updateChunkLeases(server);
        }
    }

    public static void reconcile(MinecraftServer server) {
        boolean enabled = Config.debugElytraSpaceFlight;
        double altitude = Config.flightTransitionAltitude;
        int expectedCount = enabled ? 2 : 0;
        if (installedEnabled == enabled && Double.compare(installedAltitude, altitude) == 0
                && getOwnedPortalCount(server) == expectedCount) {
            return;
        }

        removeOwnedPortals(server);
        releaseAllChunkLeases();
        installedEnabled = enabled;
        installedAltitude = altitude;
        if (!enabled) {
            LOGGER.info("Debug elytra flight disabled; removed ROCKET flight boundaries.");
            return;
        }

        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        ServerLevel space = server.getLevel(SPACE_DIMENSION);
        if (overworld == null || space == null) {
            LOGGER.warn("Cannot install ROCKET flight boundaries: Overworld or space dimension is missing.");
            return;
        }

        createBoundary(overworld, space, altitude, SPACE_ENTRY_HEIGHT, false);
        createBoundary(space, overworld, SPACE_ENTRY_HEIGHT, altitude, true);
        LOGGER.info("Debug elytra boundaries ready: Overworld Y={} <-> rocketmod:space Y={}; scale=1, rotation=0",
                altitude, SPACE_ENTRY_HEIGHT);
    }

    private static void createBoundary(ServerLevel source, ServerLevel destination,
                                       double sourceY, double targetY, boolean normalUp) {
        DebugFlightPortal portal = DebugFlightPortalRegistry.DEBUG_FLIGHT_PORTAL.get().create(source);
        if (portal == null) {
            throw new IllegalStateException("Could not create ROCKET debug flight portal");
        }
        portal.setOriginPos(new Vec3(0.0, sourceY, 0.0));
        portal.setOrientationAndSize(
                normalUp ? new Vec3(0, 0, 1) : new Vec3(1, 0, 0),
                normalUp ? new Vec3(1, 0, 0) : new Vec3(0, 0, 1),
                PORTAL_SIZE, PORTAL_SIZE);
        PortalAPI.setPortalTransformation(portal, destination.dimension(),
                new Vec3(0.0, targetY, 0.0), null, 1.0);
        portal.portalTag = PORTAL_TAG;
        portal.teleportChangesScale = false;
        portal.setInteractable(false);
        // Global portals are serialized/synced by IP, independently of normal entity chunk tracking.
        PortalAPI.addGlobalPortal(source, portal);
        portal.updateCache();
    }

    public static int getOwnedPortalCount(MinecraftServer server) {
        int count = 0;
        for (ServerLevel level : server.getAllLevels()) {
            count += (int) GlobalPortalStorage.get(level).data.stream()
                    .filter(ImmersivePortalsBackend::isOwnedPortal).count();
        }
        return count;
    }

    private static boolean isOwnedPortal(Portal portal) {
        return portal instanceof DebugFlightPortal && PORTAL_TAG.equals(portal.portalTag);
    }

    private static void removeOwnedPortals(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            GlobalPortalStorage storage = GlobalPortalStorage.get(level);
            if (storage.data.stream().anyMatch(ImmersivePortalsBackend::isOwnedPortal)) {
                storage.removePortals(ImmersivePortalsBackend::isOwnedPortal);
            }
        }
    }

    private static void updateChunkLeases(MinecraftServer server) {
        Set<UUID> active = new HashSet<>();
        if (Config.debugElytraSpaceFlight && getOwnedPortalCount(server) == 2) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                ResourceKey<Level> target = preloadTarget(player);
                if (target == null) {
                    continue;
                }
                UUID playerId = player.getUUID();
                active.add(playerId);
                ChunkPos chunk = player.chunkPosition();
                DimensionalChunkPos center = new DimensionalChunkPos(target, chunk);
                ChunkLease old = CHUNK_LEASES.get(playerId);
                if (old != null && old.player() == player && old.loader().center.equals(center)) {
                    continue;
                }
                releaseChunkLease(playerId);
                ChunkLoader loader = new ChunkLoader(center, PRELOAD_RADIUS);
                PortalAPI.addChunkLoaderForPlayer(player, loader);
                CHUNK_LEASES.put(playerId, new ChunkLease(player, loader));
            }
        }
        for (UUID playerId : Set.copyOf(CHUNK_LEASES.keySet())) {
            if (!active.contains(playerId)) {
                releaseChunkLease(playerId);
            }
        }
    }

    private static ResourceKey<Level> preloadTarget(ServerPlayer player) {
        if (!player.isAlive() || !player.isFallFlying() || player.isPassenger() || player.isSpectator()) {
            return null;
        }
        if (player.level().dimension().equals(Level.OVERWORLD)
                && Math.abs(player.getY() - Config.flightTransitionAltitude) <= PRELOAD_DISTANCE) {
            return SPACE_DIMENSION;
        }
        if (player.level().dimension().equals(SPACE_DIMENSION)
                && Math.abs(player.getY() - SPACE_ENTRY_HEIGHT) <= PRELOAD_DISTANCE) {
            return Level.OVERWORLD;
        }
        return null;
    }

    public static int getChunkLeaseCount() {
        return CHUNK_LEASES.size();
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        releaseChunkLease(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && (event.getFrom().equals(SPACE_DIMENSION) || event.getTo().equals(SPACE_DIMENSION))) {
            LOGGER.info("ROCKET dimension change: player={}, {} -> {}, pos={}, velocity={}, fallFlying={}",
                    player.getGameProfile().getName(), event.getFrom().location(), event.getTo().location(),
                    player.position(), player.getDeltaMovement(), player.isFallFlying());
            releaseChunkLease(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        releaseAllChunkLeases();
        removeOwnedPortals(event.getServer());
        installedAltitude = Double.NaN;
        installedEnabled = false;
    }

    private static void releaseChunkLease(UUID playerId) {
        ChunkLease lease = CHUNK_LEASES.remove(playerId);
        if (lease != null) {
            PortalAPI.removeChunkLoaderForPlayer(lease.player(), lease.loader());
        }
    }

    private static void releaseAllChunkLeases() {
        for (UUID playerId : Set.copyOf(CHUNK_LEASES.keySet())) {
            releaseChunkLease(playerId);
        }
    }

    private record ChunkLease(ServerPlayer player, ChunkLoader loader) {
    }
}
