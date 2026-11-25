package com.example.examplemod.compat.immptl;

import com.example.examplemod.Config;
import com.example.examplemod.ExampleMod;
import com.example.examplemod.transition.FlightTransferCoordinator;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.api.PortalAPI;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;

import java.util.HashMap;
import java.util.Map;

@Mod.EventBusSubscriber(modid = ExampleMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ImmersivePortalsBackend {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final ResourceKey<Level> SPACE_DIMENSION = ResourceKey.create(
            Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "space"));
    public static final double SPACE_ENTRY_HEIGHT = 64.0;
    private static final String PORTAL_TAG = "rocketmod:debug_elytra_boundary";
    private static final double PORTAL_SIZE = 60_000_000.0;
    private static double installedAltitude = Double.NaN;
    private static boolean installedEnabled;
    private static int nextInstallAttempt;
    private static final Map<java.util.UUID, Long> RETURN_LANDINGS = new HashMap<>();

    private ImmersivePortalsBackend() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        FlightTransferCoordinator.reset("server started");
        RETURN_LANDINGS.clear();
        removeOwnedPortals(event.getServer());
        installedAltitude = Double.NaN;
        nextInstallAttempt = 0;
        reconcile(event.getServer());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        long now = server.getTickCount();
        RETURN_LANDINGS.entrySet().removeIf(entry -> {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            return player == null || !player.isAlive() || !player.level().dimension().equals(Level.OVERWORLD)
                    || now > entry.getValue();
        });
        recoverLowSpacePlayers(server);
        boolean configChanged = installedEnabled != Config.debugElytraSpaceFlight
                || Double.compare(installedAltitude, Config.flightTransitionAltitude) != 0;
        if (configChanged || server.getTickCount() % 20 == 0) {
            reconcile(server);
        }
        FlightTransferCoordinator.tick(server);
    }

    private static void recoverLowSpacePlayers(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        for (ServerPlayer player : java.util.List.copyOf(server.getPlayerList().getPlayers())) {
            if (!player.isAlive() || !player.level().dimension().equals(SPACE_DIMENSION)
                    || player.getY() >= SPACE_ENTRY_HEIGHT - 24) continue;
            FlightTransferCoordinator.logout(player);
            player.stopRiding();
            BlockPos spawn = overworld.getSharedSpawnPos();
            int safeY = overworld.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    spawn.getX(), spawn.getZ());
            PortalAPI.teleportEntity(player, overworld,
                    new Vec3(spawn.getX() + 0.5, Math.max(safeY, overworld.getMinBuildHeight()) + 1.0, spawn.getZ() + 0.5));
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0;
            player.connection.send(new ClientboundSetEntityMotionPacket(player));
            LOGGER.warn("Recovered player {} below space return boundary", player.getGameProfile().getName());
        }
    }

    @SubscribeEvent
    public static void preventSpaceVoidDamage(LivingAttackEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && player.level().dimension().equals(SPACE_DIMENSION)
                && event.getSource().is(DamageTypes.FELL_OUT_OF_WORLD)) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void protectReturnLanding(LivingFallEvent event) {
        if (event.getDistance() > 0.0F && event.getEntity() instanceof ServerPlayer player
                && player.level().dimension().equals(Level.OVERWORLD)
                && RETURN_LANDINGS.remove(player.getUUID()) != null) {
            event.setDistance(0);
            event.setDamageMultiplier(0);
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

        if (enabled && server.getTickCount() < nextInstallAttempt) return;
        removeOwnedPortals(server);
        FlightTransferCoordinator.reset("flight boundaries changed");
        if (!enabled) {
            FlightTransferCoordinator.configure(server, false, altitude);
            installedEnabled = false;
            installedAltitude = altitude;
            LOGGER.info("Debug elytra flight disabled; removed ROCKET flight boundaries.");
            return;
        }

        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        ServerLevel space = server.getLevel(SPACE_DIMENSION);
        if (overworld == null || space == null) {
            nextInstallAttempt = server.getTickCount() + 100;
            FlightTransferCoordinator.configure(server, false, altitude);
            LOGGER.warn("Cannot install ROCKET flight boundaries: Overworld or space dimension is missing.");
            return;
        }

        if (!createBoundary(overworld, space, altitude, SPACE_ENTRY_HEIGHT, false)
                || !createBoundary(space, overworld, SPACE_ENTRY_HEIGHT, altitude, true)) {
            removeOwnedPortals(server);
            nextInstallAttempt = server.getTickCount() + 100;
            FlightTransferCoordinator.configure(server, false, altitude);
            return;
        }
        installedEnabled = true;
        installedAltitude = altitude;
        nextInstallAttempt = 0;
        FlightTransferCoordinator.configure(server, true, altitude);
        LOGGER.info("Debug elytra boundaries ready: Overworld Y={} <-> rocketmod:space Y={}; scale=1, rotation=0",
                altitude, SPACE_ENTRY_HEIGHT);
    }

    private static boolean createBoundary(ServerLevel source, ServerLevel destination,
                                       double sourceY, double targetY, boolean normalUp) {
        DebugFlightPortal portal = DebugFlightPortalRegistry.DEBUG_FLIGHT_PORTAL.get().create(source);
        if (portal == null) {
            LOGGER.error("Could not create ROCKET debug flight portal in {}", source.dimension().location());
            return false;
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
        // Share the outer sky/depth; IP otherwise replaces a view-distance-sized rectangle of sky.
        portal.fuseView = true;
        // The atmosphere is one continuous full-screen view. Rendering the remote world's sky
        // here reintroduces a rectangular sky/cloud window, especially with Oculus pipelines.
        portal.setIsVisible(false);
        portal.setInteractable(false);
        // Global portals are serialized/synced by IP, independently of normal entity chunk tracking.
        PortalAPI.addGlobalPortal(source, portal);
        portal.updateCache();
        return true;
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

    public static int getChunkLeaseCount() {
        return FlightTransferCoordinator.leaseCount();
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            FlightTransferCoordinator.logout(player);
            RETURN_LANDINGS.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) FlightTransferCoordinator.sendConfig(player);
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && (event.getFrom().equals(SPACE_DIMENSION) || event.getTo().equals(SPACE_DIMENSION))) {
            player.fallDistance = 0;
            if (event.getFrom().equals(SPACE_DIMENSION) && event.getTo().equals(Level.OVERWORLD) && !player.isFallFlying()) {
                // The portal can preserve the old onGround bit for one tick. Keep the
                // protection until the actual LivingFallEvent, with an expiry to avoid
                // carrying it into an unrelated later fall.
                RETURN_LANDINGS.put(player.getUUID(), (long) player.getServer().getTickCount() + 1200L);
                player.setOnGround(false);
            }
            LOGGER.info("ROCKET dimension change: player={}, {} -> {}, pos={}, velocity={}, fallFlying={}",
                    player.getGameProfile().getName(), event.getFrom().location(), event.getTo().location(),
                    player.position(), player.getDeltaMovement(), player.isFallFlying());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        FlightTransferCoordinator.reset("server stopping");
        RETURN_LANDINGS.clear();
        removeOwnedPortals(event.getServer());
        installedAltitude = Double.NaN;
        installedEnabled = false;
    }

}
