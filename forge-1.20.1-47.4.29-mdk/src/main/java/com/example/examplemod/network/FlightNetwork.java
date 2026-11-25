package com.example.examplemod.network;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.transition.FlightTransferCoordinator;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.UUID;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

public final class FlightNetwork {
    private static final String VERSION = "2";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "flight"), () -> VERSION, VERSION::equals, VERSION::equals);
    public static final UUID NONE = new UUID(0, 0);
    public static final long MAX_REPLY_SEQUENCE = 512;
    private static final int REPLY_BURST = 16;
    private static final double REPLIES_PER_SECOND = 8.0;
    private static final Map<Connection, ReplyBudget> REPLY_BUDGETS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile Consumer<ServerMessage> clientReceiver = message -> {};
    private static volatile BiPredicate<Player, ResourceKey<Level>> clientGate = (player, destination) -> false;

    private FlightNetwork() {}

    public static void register() {
        CHANNEL.messageBuilder(ServerMessage.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ServerMessage::encode).decoder(ServerMessage::decode)
                .consumerMainThread((message, context) -> clientReceiver.accept(message)).add();
        CHANNEL.messageBuilder(ClientReply.class, 1, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ClientReply::encode).decoder(ClientReply::decode)
                .consumerNetworkThread((message, context) -> {
                    var networkContext = context.get();
                    if (networkContext.getSender() != null && allowReply(networkContext.getNetworkManager())) {
                        networkContext.enqueueWork(() -> {
                            ServerPlayer sender = networkContext.getSender();
                            if (sender != null) FlightTransferCoordinator.onReply(sender, message);
                        });
                    }
                    networkContext.setPacketHandled(true);
                }).add();
    }

    public static void setClientHandlers(Consumer<ServerMessage> receiver,
                                         BiPredicate<Player, ResourceKey<Level>> gate) {
        clientReceiver = receiver;
        clientGate = gate;
    }

    public static boolean clientCanCross(Player player, ResourceKey<Level> destination) {
        return clientGate.test(player, destination);
    }

    public static void send(ServerPlayer player, ServerMessage message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    public static void reply(ClientReply message) { CHANNEL.sendToServer(message); }

    private static boolean allowReply(Connection connection) {
        long now = System.nanoTime();
        synchronized (REPLY_BUDGETS) {
            ReplyBudget budget = REPLY_BUDGETS.computeIfAbsent(connection, ignored -> new ReplyBudget(now));
            budget.tokens = Math.min(REPLY_BURST,
                    budget.tokens + (now - budget.lastNanos) * REPLIES_PER_SECOND / 1_000_000_000.0);
            budget.lastNanos = now;
            if (budget.tokens < 1.0) return false;
            budget.tokens -= 1.0;
            return true;
        }
    }

    private static final class ReplyBudget {
        double tokens = REPLY_BURST;
        long lastNanos;

        ReplyBudget(long now) { lastNanos = now; }
    }

    public enum Kind { CONFIG, PREPARE, ARMED, CONFIRM, ABORT, SNAPSHOT }

    public record ServerMessage(Kind kind, UUID id, long sequence, long revision, boolean enabled,
                                double threshold, ResourceKey<Level> source, ResourceKey<Level> target,
                                int chunkX, int chunkZ, int radius, double targetY, double altitude,
                                int phase, String detail) {
        void encode(FriendlyByteBuf buffer) {
            buffer.writeEnum(kind);
            buffer.writeUUID(id);
            buffer.writeVarLong(sequence);
            buffer.writeVarLong(revision);
            buffer.writeBoolean(enabled);
            buffer.writeDouble(threshold);
            buffer.writeResourceLocation(source.location());
            buffer.writeResourceLocation(target.location());
            buffer.writeInt(chunkX);
            buffer.writeInt(chunkZ);
            buffer.writeVarInt(radius);
            buffer.writeDouble(targetY);
            buffer.writeDouble(altitude);
            buffer.writeVarInt(phase);
            buffer.writeUtf(detail, 256);
        }

        static ServerMessage decode(FriendlyByteBuf buffer) {
            return new ServerMessage(buffer.readEnum(Kind.class), buffer.readUUID(), buffer.readVarLong(),
                    buffer.readVarLong(), buffer.readBoolean(), buffer.readDouble(),
                    ResourceKey.create(Registries.DIMENSION, buffer.readResourceLocation()),
                    ResourceKey.create(Registries.DIMENSION, buffer.readResourceLocation()), buffer.readInt(),
                    buffer.readInt(), buffer.readVarInt(), buffer.readDouble(), buffer.readDouble(),
                    buffer.readVarInt(), buffer.readUtf(256));
        }
    }

    public record ClientReply(UUID id, long sequence, long revision, boolean ready, boolean confirmed, String detail) {
        void encode(FriendlyByteBuf buffer) {
            buffer.writeUUID(id);
            buffer.writeVarLong(sequence);
            buffer.writeVarLong(revision);
            buffer.writeBoolean(ready);
            buffer.writeBoolean(confirmed);
            buffer.writeUtf(detail, 256);
        }

        static ClientReply decode(FriendlyByteBuf buffer) {
            return new ClientReply(buffer.readUUID(), buffer.readVarLong(), buffer.readVarLong(),
                    buffer.readBoolean(), buffer.readBoolean(), buffer.readUtf(256));
        }
    }
}
