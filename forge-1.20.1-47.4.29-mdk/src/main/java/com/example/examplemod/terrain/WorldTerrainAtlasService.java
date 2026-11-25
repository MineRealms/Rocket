package com.example.examplemod.terrain;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.Config;
import com.example.examplemod.compat.immptl.ImmersivePortalsBackend;
import com.example.examplemod.network.TerrainNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Sparse samples of actual FULL chunks. Each sample represents one 256-block LOD cell. */
@Mod.EventBusSubscriber(modid = ExampleMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class WorldTerrainAtlasService {
    private static final int SIZE = 128, CELL = 256, HALF = SIZE / 2;
    private static final List<Sample> ORDER = sampleOrder();
    private static final Map<UUID, Session> SESSIONS = new LinkedHashMap<>();
    private static final TicketType<ChunkPos> TICKET = TicketType.create("rocket_terrain_atlas", Comparator.comparingLong(ChunkPos::toLong));
    private static Pending pending;
    private static int roundRobin;

    private WorldTerrainAtlasService() {}

    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        SESSIONS.entrySet().removeIf(entry -> {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            return player == null || !eligible(player);
        });
        if (pending != null && !SESSIONS.containsValue(pending.session)) releasePending();
        if (pending != null && server.getTickCount() - pending.started > 600) {
            retry(pending.session, pending.sample, pending.attempt, server.getTickCount());
            releasePending();
        }
        if (pending != null) {
            LevelChunk ready = pending.session.level.getChunkSource().getChunkNow(pending.chunk.x, pending.chunk.z);
            if (ready != null) completePending(server, ready);
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!eligible(player)) continue;
            ServerLevel level = server.overworld();
            Session session = SESSIONS.get(player.getUUID());
            if (session == null || session.level != level || Math.abs(player.getX() - session.centerX) > 4096
                    || Math.abs(player.getZ() - session.centerZ) > 4096) {
                if (pending != null && pending.session == session) releasePending();
                session = new Session((int) Math.floor(player.getX() / CELL) * CELL,
                        (int) Math.floor(player.getZ() / CELL) * CELL, level);
                SESSIONS.put(player.getUUID(), session);
                sendTile(player, session, 0, 0, 1, 0);
            }
        }
        // One request globally and at most five new generation requests per second.
        if (pending != null || server.getTickCount() % 4 != 0 || SESSIONS.isEmpty()) return;
        var sessions = new ArrayList<>(SESSIONS.entrySet());
        int tileBudget = 16;
        for (int attempt = 0; attempt < sessions.size() && tileBudget > 0; attempt++) {
            var entry = sessions.get(Math.floorMod(roundRobin++, sessions.size()));
            Session session = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) continue;
            // Cached cells are replayed progressively, with at most sixteen bounded tiles this tick.
            while (tileBudget-- > 0) {
                Retry request = !session.retries.isEmpty() && session.retries.peek().afterTick <= server.getTickCount()
                        ? session.retries.removeFirst() : session.cursor < ORDER.size()
                        ? new Retry(ORDER.get(session.cursor++), 1, 0) : null;
                if (request == null) break;
                Sample sample = request.sample;
                int x = session.centerX + (sample.x - HALF) * CELL;
                int z = session.centerZ + (sample.z - HALF) * CELL;
                LevelChunk loaded = session.level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (loaded != null) { capture(player, session, sample, x, z, loaded); continue; }
                int cached = data(session.level).get(x, z);
                if (cached != 0) { session.captured++; sendTile(player, session, sample.x, sample.z, sample.span, cached); continue; }
                ChunkPos pos = new ChunkPos(x >> 4, z >> 4);
                try {
                    // Tickets drive asynchronous generation; getChunkFuture waits on this thread in 1.20.1.
                    session.level.getChunkSource().addRegionTicket(TICKET, pos, 0, pos);
                    pending = new Pending(entry.getKey(), session, sample, x, z, pos, server.getTickCount(), request.attempt);
                } catch (RuntimeException failure) {
                    session.level.getChunkSource().removeRegionTicket(TICKET, pos, 0, pos);
                    retry(session, sample, request.attempt, server.getTickCount());
                }
                return;
            }
        }
    }

    private static boolean eligible(ServerPlayer player) {
        return Config.generateOrbitalTerrain && player.isAlive() && (player.level().dimension().equals(Level.OVERWORLD) && player.getY() >= 300
                || player.level().dimension().equals(ImmersivePortalsBackend.SPACE_DIMENSION));
    }

    private static void completePending(MinecraftServer server, LevelChunk chunk) {
        Pending work = pending;
        try {
            ServerPlayer player = server.getPlayerList().getPlayer(work.player);
            if (player != null && SESSIONS.get(work.player) == work.session)
                capture(player, work.session, work.sample, work.x, work.z, chunk);
            else retry(work.session, work.sample, work.attempt, server.getTickCount());
        } catch (RuntimeException failure) { retry(work.session, work.sample, work.attempt, server.getTickCount()); }
        finally { releasePending(); }
    }

    private static void retry(Session session, Sample sample, int attempt, long now) {
        session.failed++;
        if (attempt < 3 && session.retries.size() < 64) session.retries.addLast(new Retry(sample, attempt + 1, now + 40));
    }

    private static void releasePending() {
        if (pending == null) return;
        pending.session.level.getChunkSource().removeRegionTicket(TICKET, pending.chunk, 0, pending.chunk);
        pending = null;
    }

    private static void capture(ServerPlayer player, Session session, Sample sample, int x, int z, LevelChunk chunk) {
        ServerLevel level = session.level;
        int red = 0, green = 0, blue = 0, elevation = 0;
        for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
            int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, dx, dz);
            BlockPos pos = new BlockPos(x + dx, y, z + dz);
            BlockState state = chunk.getBlockState(pos);
            int color = state.getMapColor(level, pos).col;
            var biome = level.getBiome(pos).value();
            if (state.is(Blocks.GRASS_BLOCK)) color = biome.getGrassColor(pos.getX(), pos.getZ());
            else if (state.is(BlockTags.LEAVES)) color = biome.getFoliageColor();
            else if (!state.getFluidState().isEmpty()) color = biome.getWaterColor();
            red += color >> 16 & 255; green += color >> 8 & 255; blue += color & 255; elevation += y;
        }
        int alpha = Math.max(1, Math.min(255, 1 + (elevation / 256 + 64) * 254 / 384));
        int argb = alpha << 24 | red / 256 << 16 | green / 256 << 8 | blue / 256;
        data(level).put(x, z, argb);
        session.captured++;
        sendTile(player, session, sample.x, sample.z, sample.span, argb);
    }

    private static void sendTile(ServerPlayer player, Session session, int x, int z, int span, int argb) {
        byte[] rgba = new byte[span * span * 4];
        for (int i = 0; i < rgba.length; i += 4) {
            rgba[i] = (byte) (argb >> 16); rgba[i + 1] = (byte) (argb >> 8);
            rgba[i + 2] = (byte) argb; rgba[i + 3] = (byte) (argb >>> 24);
        }
        TerrainNetwork.send(player, new TerrainNetwork.Tile(session.centerX, session.centerZ, SIZE, CELL,
                x, z, span, span, rgba, session.status(), argb == 0));
    }

    private static AtlasData data(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(AtlasData::load, AtlasData::new, "rocket_terrain_atlas_v2");
    }

    public static String status(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        return session == null ? "inactive (Overworld Y>=300 or space required)" : session.status();
    }

    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("rocketmod").requires(source -> source.hasPermission(2))
                .then(Commands.literal("terrain").then(Commands.literal("status").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    context.getSource().sendSuccess(() -> Component.literal(status(player)), false); return 1;
                })).then(Commands.literal("refresh").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    data(player.server.overworld()).clear();
                    // Refresh all viewers of this shared source to avoid a completed cursor retaining stale data.
                    SESSIONS.clear();
                    releasePending();
                    context.getSource().sendSuccess(() -> Component.literal("Terrain cache cleared; active atlas will resample actual chunks."), false);
                    return 1;
                }))));
    }

    @SubscribeEvent public static void blockChanged(BlockEvent.BreakEvent event) { invalidateColumn(event); }
    @SubscribeEvent public static void blockPlaced(BlockEvent.EntityPlaceEvent event) { invalidateColumn(event); }
    private static void invalidateColumn(BlockEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        int x = Math.floorDiv(event.getPos().getX(), CELL) * CELL;
        int z = Math.floorDiv(event.getPos().getZ(), CELL) * CELL;
        data(level).remove(x, z);
        for (Session session : SESSIONS.values()) {
            int px = (x - session.centerX) / CELL + HALF, pz = (z - session.centerZ) / CELL + HALF;
            if (session.level == level && px >= 0 && pz >= 0 && px < SIZE && pz < SIZE && session.retries.size() < 64)
                session.retries.addLast(new Retry(new Sample(px, pz, 1), 1, level.getServer().getTickCount() + 1));
        }
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        SESSIONS.remove(event.getEntity().getUUID());
        if (pending != null && pending.player.equals(event.getEntity().getUUID())) releasePending();
    }
    @SubscribeEvent public static void stop(ServerStoppingEvent event) { releasePending(); SESSIONS.clear(); roundRobin = 0; }

    private static List<Sample> sampleOrder() {
        List<Sample> order = new ArrayList<>();
        for (int span = 8; span >= 1; span /= 2) {
            List<Sample> stage = new ArrayList<>();
            for (int z = 0; z < SIZE; z += span) for (int x = 0; x < SIZE; x += span) {
                // Keep parent sample points; the refinement samples only newly exposed cells.
                if (span < 8 && x % (span * 2) == 0 && z % (span * 2) == 0) continue;
                stage.add(new Sample(x, z, span));
            }
            stage.sort(Comparator.comparingInt(sample -> (sample.x - HALF) * (sample.x - HALF) + (sample.z - HALF) * (sample.z - HALF)));
            order.addAll(stage);
        }
        return List.copyOf(order);
    }

    private record Sample(int x, int z, int span) {}
    private record Retry(Sample sample, int attempt, long afterTick) {}
    private record Pending(UUID player, Session session, Sample sample, int x, int z, ChunkPos chunk, long started, int attempt) {}
    private static final class Session {
        final int centerX, centerZ;
        final ServerLevel level;
        final ArrayDeque<Retry> retries = new ArrayDeque<>();
        int cursor, captured, failed;
        Session(int x, int z, ServerLevel level) { centerX = x; centerZ = z; this.level = level; }
        String status() { return "actual FULL chunk samples " + captured + "/" + ORDER.size() + "; failed=" + failed; }
    }

    private static final class AtlasData extends SavedData {
        private final Map<Long, Cached> samples = new LinkedHashMap<>(256, 0.75f, true);
        int get(int x, int z) { Cached cached = samples.get(key(x, z)); return cached == null ? 0 : cached.color; }
        void put(int x, int z, int value) {
            if (samples.size() >= 65536) samples.remove(samples.keySet().iterator().next());
            samples.put(key(x, z), new Cached(value, System.currentTimeMillis())); setDirty();
        }
        void remove(int x, int z) { if (samples.remove(key(x, z)) != null) setDirty(); }
        void clear() { samples.clear(); setDirty(); }
        @Override public CompoundTag save(CompoundTag tag) {
            ListTag list = new ListTag();
            samples.forEach((key, value) -> {
                CompoundTag item = new CompoundTag(); item.putLong("p", key); item.putInt("v", value.color);
                item.putLong("t", value.time); list.add(item);
            });
            tag.putInt("format", 2); tag.put("samples", list); return tag;
        }
        static AtlasData load(CompoundTag tag) {
            AtlasData data = new AtlasData();
            if (tag.getInt("format") != 2) return data;
            ListTag list = tag.getList("samples", Tag.TAG_COMPOUND);
            for (int i = 0; i < Math.min(65536, list.size()); i++) {
                CompoundTag item = list.getCompound(i);
                data.samples.put(item.getLong("p"), new Cached(item.getInt("v"), item.getLong("t")));
            }
            return data;
        }
        private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
        private record Cached(int color, long time) {}
    }
}
