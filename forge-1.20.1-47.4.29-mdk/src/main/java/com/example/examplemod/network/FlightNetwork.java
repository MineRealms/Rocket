package com.example.examplemod.network;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.transition.FlightTransferCoordinator;
import net.minecraft.core.registries.Registries;
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
import java.util.function.BiPredicate;
import java.util.function.Consumer;

public final class FlightNetwork {
    private static final String VERSION = "2";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "flight"), () -> VERSION, VERSION::equals, VERSION::equals);
    public static final UUID NONE = new UUID(0, 0);
    public static final long MAX_REPLY_SEQUENCE = 512;
    private static volatile Consumer<ServerMessage> clientReceiver = message -> {};
    private static volatile BiPredicate<Player, ResourceKey<Level>> clientGate = (player, destination) -> false;

    private FlightNetwork() {}

    public static void register() {
        CHANNEL.messageBuilder(ServerMessage.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ServerMessage::encode).decoder(ServerMessage::decode)
                .consumerMainThread((message, context) -> clientReceiver.accept(message)).add();
        CHANNEL.messageBuilder(ClientReply.class, 1, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ClientReply::encode).decoder(ClientReply::decode)
                .consumerMainThread((message, context) -> {
                    ServerPlayer sender = context.get().getSender();
                    if (sender != null) FlightTransferCoordinator.onReply(sender, message);
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
