package com.example.examplemod;

import com.example.examplemod.compat.immptl.ImmersivePortalsBackend;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.api.PortalAPI;

@Mod.EventBusSubscriber(modid = ExampleMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class FlightDimensionTransition {
    private static final Logger LOGGER = LogUtils.getLogger();

    private FlightDimensionTransition() {}

    @SubscribeEvent
    public static void onDimensionChanged(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && (event.getFrom().equals(ImmersivePortalsBackend.SPACE_DIMENSION)
                || event.getTo().equals(ImmersivePortalsBackend.SPACE_DIMENSION))) {
            LOGGER.info("Flight crossing: player={} from={} to={} position={} velocity={} gliding={}",
                    player.getGameProfile().getName(), event.getFrom().location(), event.getTo().location(),
                    player.position(), player.getDeltaMovement(), player.isFallFlying());
        }
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("rocketmod")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("flight")
                        .then(Commands.literal("status").executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            ImmersivePortalsBackend.reconcile(player.getServer());
                            context.getSource().sendSuccess(() -> Component.literal(String.format(
                                    "debugElytraSpaceFlight=%s; boundary=%.1f <-> 64; dimension=%s; Y=%.2f; gliding=%s; portals=%d",
                                    Config.debugElytraSpaceFlight, Config.flightTransitionAltitude,
                                    player.level().dimension().location(), player.getY(), player.isFallFlying(),
                                    ImmersivePortalsBackend.getOwnedPortalCount(player.getServer()))), false);
                            return 1;
                        }))
                        .then(Commands.literal("kit").executes(context -> giveKit(context.getSource().getPlayerOrException())))
                        .then(Commands.literal("launch").executes(context -> launch(context.getSource().getPlayerOrException())))
                        .then(Commands.literal("return").executes(context -> returnToOverworld(context.getSource().getPlayerOrException())))));
    }

    private static boolean requireDebug(ServerPlayer player) {
        if (!Config.debugElytraSpaceFlight) {
            player.sendSystemMessage(Component.literal("Debug Elytra flight is disabled in rocketmod-common.toml. Rocket flight is not implemented yet."));
            return false;
        }
        return true;
    }

    private static int giveKit(ServerPlayer player) {
        if (!requireDebug(player)) return 0;
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.isEmpty() && !chest.is(Items.ELYTRA)) {
            player.sendSystemMessage(Component.literal("Empty your chest equipment slot before requesting the flight kit."));
            return 0;
        }
        if (chest.is(Items.ELYTRA) && !ElytraItem.isFlyEnabled(chest)) {
            player.sendSystemMessage(Component.literal("Repair or remove your damaged Elytra before requesting the flight kit."));
            return 0;
        }
        if (chest.isEmpty()) {
            ItemStack elytra = new ItemStack(Items.ELYTRA);
            elytra.getOrCreateTag().putBoolean("Unbreakable", true);
            player.setItemSlot(EquipmentSlot.CHEST, elytra);
        }
        ItemStack rockets = new ItemStack(Items.FIREWORK_ROCKET, 64);
        rockets.getOrCreateTagElement("Fireworks").putByte("Flight", (byte) 3);
        if (!player.getInventory().add(rockets)) player.drop(rockets, false);
        player.sendSystemMessage(Component.literal("Flight kit ready. Glide and use fireworks to climb through Y=" + Config.flightTransitionAltitude + ". Glide down through space Y=64 to return."));
        return 1;
    }

    private static int launch(ServerPlayer player) {
        if (!requireDebug(player)) return 0;
        if (!player.level().dimension().equals(Level.OVERWORLD) || player.isPassenger() || player.isSpectator()
                || !player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)
                || !ElytraItem.isFlyEnabled(player.getItemBySlot(EquipmentSlot.CHEST))) {
            player.sendSystemMessage(Component.literal("Launch requires an unmounted player wearing Elytra in the Overworld. Use /rocketmod flight kit first."));
            return 0;
        }
        int groundY = player.serverLevel().getHeight(Heightmap.Types.MOTION_BLOCKING, player.getBlockX(), player.getBlockZ());
        double launchY = Math.min(Config.flightTransitionAltitude - 32, Math.max(groundY + 24, player.getY()));
        player.teleportTo(player.serverLevel(), player.getX(), launchY, player.getZ(), player.getYRot(), -55);
        player.getAbilities().flying = false;
        player.onUpdateAbilities();
        player.setOnGround(false);
        player.startFallFlying();
        player.setDeltaMovement(player.getLookAngle().scale(1.5));
        player.hurtMarked = true;
        player.fallDistance = 0;
        return 1;
    }

    private static int returnToOverworld(ServerPlayer player) {
        if (!player.level().dimension().equals(ImmersivePortalsBackend.SPACE_DIMENSION)) return 0;
        ServerLevel overworld = player.getServer().getLevel(Level.OVERWORLD);
        if (overworld == null) return 0;
        BlockPos spawn = overworld.getSharedSpawnPos();
        int groundY = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING, spawn.getX(), spawn.getZ());
        PortalAPI.teleportEntity(player, overworld, new Vec3(spawn.getX() + 0.5, groundY + 1, spawn.getZ() + 0.5));
        player.setDeltaMovement(Vec3.ZERO);
        player.hurtMarked = true;
        player.fallDistance = 0;
        player.sendSystemMessage(Component.literal("Emergency return to Overworld spawn."));
        return 1;
    }
}
