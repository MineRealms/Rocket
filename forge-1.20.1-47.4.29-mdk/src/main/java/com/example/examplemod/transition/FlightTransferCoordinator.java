package com.example.examplemod.transition;

import com.example.examplemod.Config;
import com.example.examplemod.compat.immptl.ImmersivePortalsBackend;
import com.example.examplemod.flight.FlightMath;
import com.example.examplemod.flight.FlightPhase;
import com.example.examplemod.network.FlightNetwork;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.api.PortalAPI;
import qouteall.imm_ptl.core.chunk_loading.ChunkLoader;
import qouteall.imm_ptl.core.chunk_loading.DimensionalChunkPos;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class FlightTransferCoordinator {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int RADIUS = 2;
    private static final int MAX_SESSIONS = 8;
    private static final int PREPARE_TIMEOUT = 200;
    private static final int CONFIRM_TIMEOUT = 100;
    private static final double PREPARE_DISTANCE = 160;
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Map<UUID, Long> RETRY_AFTER = new HashMap<>();
    private static final Map<UUID, Long> ARRIVAL_GRACE = new HashMap<>();
    private static long revision;
    private static long nextSequence;
    private static boolean enabled;
    private static double threshold = 512;

    private FlightTransferCoordinator() {}

    public static void configure(MinecraftServer server, boolean newEnabled, double newThreshold) {
        if (revision != 0 && enabled == newEnabled && threshold == newThreshold) return;
        reset("configuration changed");
        enabled = newEnabled;
        threshold = newThreshold;
        revision++;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) sendConfig(player);
    }

    public static void sendConfig(ServerPlayer player) {
        send(player, FlightNetwork.Kind.CONFIG, null, "server configuration");
    }

    public static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Session session = SESSIONS.get(player.getUUID());
            if (session != null) {
                if (!enabled || !eligible(player)) {
                    abort(player.getUUID(), "flight cancelled", now);
                } else if (player.level().dimension().equals(session.target)) {
                    restoreGravity(session);
                    if (!session.crossed) {
                        session.crossed = true;
                        session.crossedTick = now;
                        ARRIVAL_GRACE.put(player.getUUID(), now + 20);
                        LOGGER.info("Flight transfer crossed id={} player={} H={} velocity={}", session.id,
                                player.getGameProfile().getName(), altitude(player), player.getDeltaMovement());
                    }
                    if (session.confirmed || now - session.crossedTick > CONFIRM_TIMEOUT) {
                        complete(session, session.confirmed ? "confirmed" : "recovered at authoritative destination");
                    } else if (now % 10 == 0) {
                        send(player, FlightNetwork.Kind.CONFIRM, session, "confirm destination");
                    }
                } else if (!player.level().dimension().equals(session.source)) {
                    abort(player.getUUID(), "dimension changed externally", now);
                } else if (now - session.started > PREPARE_TIMEOUT) {
                    abort(player.getUUID(), "preparation timeout", now);
                } else if (!insideEnvelope(player, session)) {
                    abort(player.getUUID(), "arrival region changed", now - 35);
                } else if (distanceToBoundary(player) > PREPARE_DISTANCE + 32) {
                    abort(player.getUUID(), "left flight boundary", now);
                } else {
                    boolean ready = session.clientReady && destinationLoaded(server, session);
                    if (ready != session.armed) {
                        session.armed = ready;
                        send(player, ready ? FlightNetwork.Kind.ARMED : FlightNetwork.Kind.PREPARE, session,
                                ready ? "server and client ready" : "waiting for destination");
                    }
                    if (session.armed) {
                        if (session.holding) {
                            Vec3 velocity = session.heldVelocity;
                            restoreGravity(session);
                            player.setDeltaMovement(velocity);
                            player.hurtMarked = true;
                        }
                    } else holdBeforeBoundary(player, session);
                }
            } else if (enabled && eligible(player) && approachingBoundary(player) && distanceToBoundary(player) >= -8
                    && distanceToBoundary(player) <= PREPARE_DISTANCE
                    && now >= RETRY_AFTER.getOrDefault(player.getUUID(), 0L)
                    && now >= ARRIVAL_GRACE.getOrDefault(player.getUUID(), 0L)) {
                if (SESSIONS.size() < MAX_SESSIONS) begin(player, now);
                else preventUnpreparedCrossing(player);
            }
            if (enabled && eligible(player) && approachingBoundary(player)
                    && now >= ARRIVAL_GRACE.getOrDefault(player.getUUID(), 0L)
                    && SESSIONS.get(player.getUUID()) == null) {
                preventUnpreparedCrossing(player);
            }
            if (now % 5 == 0) send(player, FlightNetwork.Kind.SNAPSHOT, SESSIONS.get(player.getUUID()), status(player));
        }
    }

    private static void begin(ServerPlayer player, long now) {
        ResourceKey<Level> source = player.level().dimension();
        ResourceKey<Level> target = source.equals(Level.OVERWORLD) ? ImmersivePortalsBackend.SPACE_DIMENSION : Level.OVERWORLD;
        if (player.getServer().getLevel(target) == null) return;
        ChunkLoader loader = new ChunkLoader(new DimensionalChunkPos(target, player.chunkPosition()), RADIUS);
        Session session = new Session(player, source, target, loader, now, ++nextSequence);
        SESSIONS.put(player.getUUID(), session);
        PortalAPI.addChunkLoaderForPlayer(player, loader);
        send(player, FlightNetwork.Kind.PREPARE, session, "waiting for destination");
        LOGGER.info("Flight transfer preparing id={} seq={} player={} target={} chunks={}", session.id,
                session.sequence, player.getGameProfile().getName(), target.location(), loader.getChunkNum());
        holdBeforeBoundary(player, session);
    }

    public static void onReply(ServerPlayer player, FlightNetwork.ClientReply reply) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || !session.id.equals(reply.id()) || reply.revision() != revision
                || reply.sequence() <= session.lastReplySequence || !enabled) return;
        session.lastReplySequence = reply.sequence();
        session.clientDetail = reply.detail();
        if (reply.confirmed()) {
            if (player.level().dimension().equals(session.target)) session.confirmed = true;
            return;
        }
        if (!player.level().dimension().equals(session.source) || !eligible(player) || !insideEnvelope(player, session)) return;
        session.clientReady = reply.ready();
        LOGGER.info("Flight transfer readiness id={} ready={} detail={}", session.id, reply.ready(), reply.detail());
    }

    public static boolean canCross(ServerPlayer player, ResourceKey<Level> destination) {
        Session session = SESSIONS.get(player.getUUID());
        return enabled && Config.debugElytraSpaceFlight && eligible(player) && session != null
                && session.armed && session.clientReady && session.source.equals(player.level().dimension())
                && session.target.equals(destination) && insideEnvelope(player, session)
                && destinationLoaded(player.getServer(), session);
    }

    private static boolean eligible(ServerPlayer player) {
        return player.isAlive() && !player.isRemoved() && !player.isPassenger() && !player.isSpectator()
                && player.isFallFlying() && (player.level().dimension().equals(Level.OVERWORLD)
                || player.level().dimension().equals(ImmersivePortalsBackend.SPACE_DIMENSION));
    }

    private static boolean insideEnvelope(ServerPlayer player, Session session) {
        return Math.abs(player.chunkPosition().x - session.loader.center.x) < RADIUS
                && Math.abs(player.chunkPosition().z - session.loader.center.z) < RADIUS;
    }

    private static boolean destinationLoaded(MinecraftServer server, Session session) {
        ServerLevel destination = server.getLevel(session.target);
        if (destination == null) return false;
        for (int x = session.loader.center.x - RADIUS; x <= session.loader.center.x + RADIUS; x++) {
            for (int z = session.loader.center.z - RADIUS; z <= session.loader.center.z + RADIUS; z++) {
                if (destination.getChunkSource().getChunkNow(x, z) == null) return false;
            }
        }
        return true;
    }

    private static double distanceToBoundary(ServerPlayer player) {
        if (player.level().dimension().equals(Level.OVERWORLD)) return threshold - player.getEyeY();
        if (player.level().dimension().equals(ImmersivePortalsBackend.SPACE_DIMENSION)) return player.getEyeY() - FlightMath.SPACE_ENTRY_HEIGHT;
        return Double.POSITIVE_INFINITY;
    }

    private static boolean approachingBoundary(ServerPlayer player) {
        return player.getDeltaMovement().y * (player.level().dimension().equals(Level.OVERWORLD) ? 1 : -1) > 0.05;
    }

    private static void holdBeforeBoundary(ServerPlayer player, Session session) {
        double sign = session.source.equals(Level.OVERWORLD) ? 1 : -1;
        double towardSpeed = Math.max(0, player.getDeltaMovement().y * sign);
        double brakingDistance = Math.max(12, towardSpeed * towardSpeed / 0.3 + towardSpeed * 6);
        if (session.holding || distanceToBoundary(player) <= brakingDistance) {
            if (!session.holding) {
                session.oldNoGravity = player.isNoGravity();
                session.heldVelocity = player.getDeltaMovement();
                session.holding = true;
                double boundary = sign > 0 ? threshold : FlightMath.SPACE_ENTRY_HEIGHT;
                double safeY = boundary - sign * 12 - player.getEyeHeight();
                player.connection.teleport(player.getX(), safeY, player.getZ(), player.getYRot(), player.getXRot());
                player.startFallFlying();
            }
            player.setNoGravity(true);
            player.setDeltaMovement(Vec3.ZERO);
            player.hurtMarked = true;
            player.fallDistance = 0;
        }
    }

    private static void preventUnpreparedCrossing(ServerPlayer player) {
        if (distanceToBoundary(player) > 12) return;
        boolean source = player.level().dimension().equals(Level.OVERWORLD);
        double safeY = (source ? threshold - 16 : FlightMath.SPACE_ENTRY_HEIGHT + 16) - player.getEyeHeight();
        player.connection.teleport(player.getX(), safeY, player.getZ(), player.getYRot(), player.getXRot());
        player.setDeltaMovement(new Vec3(0, source ? -0.1 : 0.1, 0));
        player.startFallFlying();
        player.hurtMarked = true;
    }

    private static void restoreGravity(Session session) {
        if (session.holding) {
            session.player.setNoGravity(session.oldNoGravity);
            session.holding = false;
        }
    }

    public static void abort(UUID playerId, String reason, long now) {
        Session session = SESSIONS.remove(playerId);
        if (session == null) return;
        restoreGravity(session);
        PortalAPI.removeChunkLoaderForPlayer(session.player, session.loader);
        RETRY_AFTER.put(playerId, now + 40);
        if (!session.player.hasDisconnected()) send(session.player, FlightNetwork.Kind.ABORT, session, reason);
        LOGGER.info("Flight transfer aborted id={} reason={}", session.id, reason);
    }

    public static void logout(ServerPlayer player) {
        abort(player.getUUID(), "logout", 0);
        RETRY_AFTER.remove(player.getUUID());
        ARRIVAL_GRACE.remove(player.getUUID());
    }

    private static void complete(Session session, String reason) {
        SESSIONS.remove(session.player.getUUID());
        restoreGravity(session);
        PortalAPI.removeChunkLoaderForPlayer(session.player, session.loader);
        send(session.player, FlightNetwork.Kind.CONFIRM, session, reason);
        LOGGER.info("Flight transfer complete id={} result={}", session.id, reason);
    }

    public static void reset(String reason) {
        for (UUID playerId : java.util.Set.copyOf(SESSIONS.keySet())) abort(playerId, reason, 0);
        RETRY_AFTER.clear();
        ARRIVAL_GRACE.clear();
    }

    public static int leaseCount() { return SESSIONS.size(); }

    public static String status(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null) return "IDLE; sessions=" + SESSIONS.size();
        return (session.crossed ? "CROSSING" : session.armed ? "READY" : session.holding ? "HOLDING" : "PREPARING")
                + "; id=" + session.id + "; client=" + session.clientDetail;
    }

    public static double altitude(ServerPlayer player) {
        return FlightMath.altitude(player.getY(), player.level().dimension().equals(ImmersivePortalsBackend.SPACE_DIMENSION), threshold);
    }

    private static void send(ServerPlayer player, FlightNetwork.Kind kind, Session session, String detail) {
        boolean space = player.level().dimension().equals(ImmersivePortalsBackend.SPACE_DIMENSION);
        FlightPhase phase = FlightMath.phase(altitude(player), player.getDeltaMovement().y, space, player.isFallFlying(), threshold);
        FlightNetwork.send(player, new FlightNetwork.ServerMessage(kind,
                session == null ? FlightNetwork.NONE : session.id, session == null ? ++nextSequence : session.sequence,
                revision, enabled, threshold, session == null ? player.level().dimension() : session.source,
                session == null ? player.level().dimension() : session.target,
                session == null ? player.chunkPosition().x : session.loader.center.x,
                session == null ? player.chunkPosition().z : session.loader.center.z, RADIUS,
                session == null ? player.getY() : session.target.equals(Level.OVERWORLD) ? threshold : FlightMath.SPACE_ENTRY_HEIGHT,
                altitude(player), phase.ordinal(), detail.length() > 256 ? detail.substring(0, 256) : detail));
    }

    private static final class Session {
        final UUID id = UUID.randomUUID();
        final ServerPlayer player;
        final ResourceKey<Level> source;
        final ResourceKey<Level> target;
        final ChunkLoader loader;
        final long started;
        final long sequence;
        long lastReplySequence;
        long crossedTick;
        boolean clientReady;
        boolean armed;
        boolean crossed;
        boolean confirmed;
        boolean holding;
        boolean oldNoGravity;
        Vec3 heldVelocity = Vec3.ZERO;
        String clientDetail = "awaiting client";

        Session(ServerPlayer player, ResourceKey<Level> source, ResourceKey<Level> target,
                ChunkLoader loader, long started, long sequence) {
            this.player = player;
            this.source = source;
            this.target = target;
            this.loader = loader;
            this.started = started;
            this.sequence = sequence;
        }
    }
}
