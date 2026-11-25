package com.example.examplemod.network;

import com.example.examplemod.ExampleMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.function.Consumer;

public final class TerrainNetwork {
    private static final String VERSION = "1";
    private static volatile Consumer<Tile> clientReceiver = tile -> {};
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "terrain"), () -> VERSION,
            VERSION::equals, VERSION::equals);

    private TerrainNetwork() {}

    public static void register() {
        CHANNEL.messageBuilder(Tile.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Tile::encode).decoder(Tile::decode)
                .consumerMainThread((message, context) -> clientReceiver.accept(message)).add();
    }

    public static void setClientReceiver(Consumer<Tile> receiver) { clientReceiver = receiver; }

    public static void send(net.minecraft.server.level.ServerPlayer player, Tile tile) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), tile);
    }

    public record Tile(int originX, int originZ, int width, int cellSize, int startX, int startZ,
                       int tileWidth, int tileHeight, byte[] rgba, String status, boolean reset) {
        public boolean valid() {
            return width == 128 && cellSize == 256 && originX % cellSize == 0 && originZ % cellSize == 0
                    && startX >= 0 && startZ >= 0 && tileWidth >= 1 && tileHeight >= 1
                    && tileWidth <= 16 && tileHeight <= 16 && startX + tileWidth <= width
                    && startZ + tileHeight <= width && rgba != null && rgba.length == tileWidth * tileHeight * 4
                    && status != null && status.length() <= 128
                    && (!reset || startX == 0 && startZ == 0 && tileWidth == 1 && tileHeight == 1);
        }
        void encode(FriendlyByteBuf buffer) {
            buffer.writeInt(originX); buffer.writeInt(originZ); buffer.writeVarInt(width);
            buffer.writeVarInt(cellSize); buffer.writeVarInt(startX); buffer.writeVarInt(startZ);
            buffer.writeVarInt(tileWidth); buffer.writeVarInt(tileHeight);
            buffer.writeByteArray(rgba); buffer.writeUtf(status, 128); buffer.writeBoolean(reset);
        }

        static Tile decode(FriendlyByteBuf buffer) {
            int ox = buffer.readInt(), oz = buffer.readInt(), width = buffer.readVarInt();
            int cell = buffer.readVarInt(), sx = buffer.readVarInt(), sz = buffer.readVarInt();
            int tw = buffer.readVarInt(), th = buffer.readVarInt();
            byte[] pixels = buffer.readByteArray(16 * 16 * 4);
            String status = buffer.readUtf(128);
            Tile tile = new Tile(ox, oz, width, cell, sx, sz, tw, th, pixels, status, buffer.readBoolean());
            if (!tile.valid()) throw new IllegalArgumentException("Invalid terrain tile layout");
            return tile;
        }
    }
}
