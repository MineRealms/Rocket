package com.example.examplemod.client.flight;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.compat.immptl.ImmersivePortalsBackend;
import com.example.examplemod.flight.FlightMath;
import com.example.examplemod.flight.FlightPhase;
import com.example.examplemod.network.FlightNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.ducks.IEWorldRenderer;

import java.util.HashMap;
import java.util.Map;

@Mod.EventBusSubscriber(modid = ExampleMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientFlightState {
    private static boolean configured;
    private static boolean enabled;
    private static double threshold = 512;
    private static long revision;
    private static FlightNetwork.ServerMessage preparation;
    private static long latestPreparationSequence;
    private static long replySequence;
    private static boolean armed;
    private static boolean locallyReady;
    private static boolean lastReportedReady;
    private static boolean sentReadiness;
    private static int lastReadinessReportTick;
    private static int ticks;
    private static int preparedAt;
    private static int targetFrameTick = -1000;
    private static int debugReadyDelayTicks;
    private static String readiness = "no session";
    private static String serverStatus = "no server configuration";
    private static double serverAltitude;
    private static FlightPhase serverPhase = FlightPhase.GROUND;
    private static Vec3 heldPosition;
    private static Vec3 heldVelocity = Vec3.ZERO;
    private static boolean oldNoGravity;

    private ClientFlightState() {}

    @Mod.EventBusSubscriber(modid = ExampleMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Setup {
        @SubscribeEvent
        public static void setup(FMLClientSetupEvent event) {
            event.enqueueWork(() -> FlightNetwork.setClientHandlers(ClientFlightState::receive, ClientFlightState::canCross));
        }
    }

    public static boolean hasServerConfig() { return configured; }
    public static boolean enabled() { return configured && enabled; }
    public static double transitionAltitude() { return threshold; }
    public static double serverAltitude() { return serverAltitude; }
    public static FlightPhase serverPhase() { return serverPhase; }
    public static String readinessStatus() { return readiness; }
    public static String serverStatus() { return serverStatus; }
    public static boolean isArmed() { return armed && locallyReady; }

    public static double altitude(float partialTick) {
        Player player = Minecraft.getInstance().player;
        if (player == null) return 0;
        return FlightMath.altitude(Mth.lerp(partialTick, player.yo, player.getY()),
                player.level().dimension().equals(ImmersivePortalsBackend.SPACE_DIMENSION), threshold);
    }

    public static FlightPhase phase(float partialTick) {
        Player player = Minecraft.getInstance().player;
        if (player == null) return FlightPhase.GROUND;
        return FlightMath.phase(altitude(partialTick), player.getDeltaMovement().y,
                player.level().dimension().equals(ImmersivePortalsBackend.SPACE_DIMENSION), player.isFallFlying(), threshold);
    }

    /** Debug injection delays real readiness checks; it never authorizes a crossing. */
    public static void setDebugReadyDelayTicks(int delay) { debugReadyDelayTicks = Math.max(0, Math.min(1200, delay)); }

    private static void receive(FlightNetwork.ServerMessage message) {
        if (message.revision() < revision) return;
        if (!configured || message.revision() > revision) {
            clearPreparation();
            revision = message.revision();
            configured = true;
            enabled = message.enabled();
            threshold = message.threshold();
        }
        serverAltitude = message.altitude();
        if (message.phase() >= 0 && message.phase() < FlightPhase.values().length) serverPhase = FlightPhase.values()[message.phase()];
        switch (message.kind()) {
            case CONFIG -> {
                enabled = message.enabled();
                threshold = message.threshold();
                if (!enabled) clearPreparation();
            }
            case PREPARE -> {
                if (message.sequence() < latestPreparationSequence) return;
                boolean fresh = preparation == null || !preparation.id().equals(message.id());
                if (fresh) {
                    clearPreparation();
                    preparedAt = ticks;
                    latestPreparationSequence = message.sequence();
                }
                preparation = message;
                armed = false;
            }
            case ARMED -> {
                if (preparation != null && preparation.id().equals(message.id())) armed = true;
            }
            case CONFIRM -> {
                Player player = Minecraft.getInstance().player;
                if (preparation != null && preparation.id().equals(message.id()) && player != null
                        && player.level().dimension().equals(preparation.target())) {
                    FlightNetwork.reply(new FlightNetwork.ClientReply(message.id(), ++replySequence, revision, locallyReady, true, "client arrived"));
                    armed = false;
                    readiness = "destination confirmed";
                    preparation = null;
                }
            }
            case ABORT -> {
                if (preparation != null && preparation.id().equals(message.id())) clearPreparation();
                readiness = "aborted: " + message.detail();
            }
            case SNAPSHOT -> {
                serverStatus = message.detail();
                if (message.id().equals(FlightNetwork.NONE) && preparation != null
                        && message.sequence() >= latestPreparationSequence) clearPreparation();
            }
        }
    }

    private static boolean canCross(Player player, ResourceKey<Level> destination) {
        return enabled() && armed && locallyReady && preparation != null
                && preparation.source().equals(player.level().dimension()) && preparation.target().equals(destination)
                && inArrivalRegion(player) && ticks - targetFrameTick <= 20;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            Player player = Minecraft.getInstance().player;
            if (player != null && heldPosition != null) {
                if (preparation == null || !player.level().dimension().equals(preparation.source())
                        || !player.isFallFlying() && preparation.source().equals(Level.OVERWORLD)) {
                    releaseHold(false);
                } else {
                    player.setPos(heldPosition.x, heldPosition.y, heldPosition.z);
                    player.setDeltaMovement(Vec3.ZERO);
                }
            }
            return;
        }
        ticks++;
        Player player = Minecraft.getInstance().player;
        if (!enabled() || player == null || preparation == null) return;
        if (player.level().dimension().equals(preparation.target())) return;
        if (!player.level().dimension().equals(preparation.source())
                || !player.isFallFlying() && preparation.source().equals(Level.OVERWORLD)) return;
        if (!canCross(player, preparation.target())) {
            boolean upward = preparation.source().equals(Level.OVERWORLD);
            double distance = upward ? threshold - player.getEyeY() : player.getEyeY() - FlightMath.SPACE_ENTRY_HEIGHT;
            double toward = Math.max(0, player.getDeltaMovement().y * (upward ? 1 : -1));
            if (heldPosition != null || distance <= Math.max(12, toward * toward / 0.3 + toward * 6)) {
                if (heldPosition == null) {
                    heldVelocity = player.getDeltaMovement();
                    oldNoGravity = player.isNoGravity();
                    double safeY = (upward ? threshold - 12 : FlightMath.SPACE_ENTRY_HEIGHT + 12) - player.getEyeHeight();
                    heldPosition = new Vec3(player.getX(), safeY, player.getZ());
                }
                player.setNoGravity(true);
                player.setDeltaMovement(Vec3.ZERO);
                player.setPos(heldPosition.x, heldPosition.y, heldPosition.z);
            }
        } else releaseHold(true);
    }

    @SubscribeEvent
    public static void onWorldRendered(RenderLevelStageEvent event) {
        if (preparation == null || event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        LevelRenderer targetRenderer = ClientWorldLoader.worldRendererMap.get(preparation.target());
        if (targetRenderer != null && event.getLevelRenderer() == targetRenderer) targetFrameTick = ticks;
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || preparation == null || !enabled()) return;
        Player player = Minecraft.getInstance().player;
        if (player == null || !player.level().dimension().equals(preparation.source())) return;
        String result = checkReadiness(player);
        boolean ready = result.startsWith("ready:");
        readiness = result;
        locallyReady = ready;
        if (!sentReadiness || ready != lastReportedReady || ticks - lastReadinessReportTick >= 20) {
            FlightNetwork.reply(new FlightNetwork.ClientReply(preparation.id(), ++replySequence, revision, ready, false, result));
            sentReadiness = true;
            lastReportedReady = ready;
            lastReadinessReportTick = ticks;
        }
    }

    private static boolean inArrivalRegion(Player player) {
        return preparation != null && Math.abs(player.chunkPosition().x - preparation.chunkX()) < preparation.radius()
                && Math.abs(player.chunkPosition().z - preparation.chunkZ()) < preparation.radius();
    }

    private static String checkReadiness(Player player) {
        if (ticks - preparedAt < debugReadyDelayTicks) return "debug readiness delay";
        if (!inArrivalRegion(player)) return "outside arrival region";
        if (!ClientWorldLoader.getIsInitialized()) return "target world unavailable";
        ClientLevel target = ClientWorldLoader.getClientWorlds().stream()
                .filter(level -> level.dimension().equals(preparation.target())).findFirst().orElse(null);
        LevelRenderer renderer = ClientWorldLoader.worldRendererMap.get(preparation.target());
        if (target == null || renderer == null) return "target world or renderer unavailable";
        ViewArea viewArea = ((IEWorldRenderer) renderer).ip_getBuiltChunkStorage();
        if (viewArea == null) return "target view area unavailable";
        if (ticks - targetFrameTick > 20) return "awaiting target render pass";
        Map<Long, ChunkRenderDispatcher.RenderChunk> renderChunks = new HashMap<>();
        for (ChunkRenderDispatcher.RenderChunk renderChunk : viewArea.chunks) {
            renderChunks.put(renderChunk.getOrigin().asLong(), renderChunk);
        }
        int knownEmpty = 0;
        int compiled = 0;
        // This is the bounded arrival envelope, not a claim that the entire remote world is compiled.
        int arrivalSection = Mth.clamp(Mth.floor(preparation.targetY() / 16),
                target.getMinSection(), target.getMaxSection() - 1);
        int firstY = Math.max(target.getMinSection(), arrivalSection - 3);
        int lastY = Math.min(target.getMaxSection() - 1, arrivalSection + 3);
        for (int x = preparation.chunkX() - preparation.radius(); x <= preparation.chunkX() + preparation.radius(); x++) {
            for (int z = preparation.chunkZ() - preparation.radius(); z <= preparation.chunkZ() + preparation.radius(); z++) {
                LevelChunk chunk = target.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk == null) return "awaiting target chunks";
                for (int y = firstY; y <= lastY; y++) {
                    LevelChunkSection section = chunk.getSection(y - target.getMinSection());
                    if (section.hasOnlyAir()) { knownEmpty++; continue; }
                    ChunkRenderDispatcher.RenderChunk renderChunk = renderChunks.get(BlockPos.asLong(x * 16, y * 16, z * 16));
                    if (renderChunk == null || renderChunk.isDirty()
                            || renderChunk.getCompiledChunk() == ChunkRenderDispatcher.CompiledChunk.UNCOMPILED) {
                        return "awaiting arrival meshes";
                    }
                    compiled++;
                }
            }
        }
        return "ready: chunks=25 compiled=" + compiled + " empty=" + knownEmpty;
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clearPreparation();
        configured = false;
        enabled = false;
        revision = 0;
        latestPreparationSequence = 0;
        debugReadyDelayTicks = 0;
    }

    private static void clearPreparation() {
        releaseHold(false);
        preparation = null;
        armed = false;
        locallyReady = false;
        lastReportedReady = false;
        sentReadiness = false;
        replySequence = 0;
        targetFrameTick = -1000;
        readiness = "no session";
    }

    private static void releaseHold(boolean resume) {
        if (heldPosition == null) return;
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            player.setNoGravity(oldNoGravity);
            if (resume) player.setDeltaMovement(heldVelocity);
        }
        heldPosition = null;
        heldVelocity = Vec3.ZERO;
    }
}
