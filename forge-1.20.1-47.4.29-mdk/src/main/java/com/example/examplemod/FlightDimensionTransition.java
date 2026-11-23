package com.example.examplemod;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import qouteall.imm_ptl.core.api.PortalAPI;
import org.slf4j.Logger;

import java.util.List;

@Mod.EventBusSubscriber(modid = ExampleMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class FlightDimensionTransition {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceKey<Level> SPACE_DIMENSION = ResourceKey.create(
            Registries.DIMENSION,
            new ResourceLocation(ExampleMod.MODID, "space")
    );
    private static final double SPACE_ENTRY_HEIGHT = 64.0;

    private FlightDimensionTransition() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        // Transfer after all worlds have ticked, outside their entity iteration.
        if (event.phase != TickEvent.Phase.END || event.getServer().getLevel(SPACE_DIMENSION) == null) {
            return;
        }

        for (ServerPlayer player : List.copyOf(event.getServer().getPlayerList().getPlayers())) {
            if (!player.isAlive() || player.isRemoved() || player.isChangingDimension()
                    || !player.level().dimension().equals(Level.OVERWORLD)) {
                continue;
            }

            double altitude = player.getY();
            if (onVirtualAltitudeChanged(player, altitude)) {
                LOGGER.info("Auto flight transition: {} from Overworld Y={} to rocketmod:space",
                        player.getGameProfile().getName(), altitude);
            }
        }
    }

    /**
     * Checks the shared altitude threshold. The server tick supplies actual player Y;
     * future rocket flight code can also supply virtual altitude through this API.
     * Being outside the Overworld prevents repeat transfers after entering space.
     */
    public static boolean onVirtualAltitudeChanged(ServerPlayer pilot, double virtualAltitude) {
        if (!Double.isFinite(virtualAltitude)
                || virtualAltitude < Config.flightTransitionAltitude
                || pilot.level().dimension() != Level.OVERWORLD) {
            return false;
        }

        ServerLevel source = (ServerLevel) pilot.level();
        ServerLevel space = source.getServer().getLevel(SPACE_DIMENSION);
        if (space == null) {
            pilot.sendSystemMessage(Component.literal("Rocket space dimension is not available."));
            return false;
        }

        double relativeAltitude = virtualAltitude - Config.flightTransitionAltitude;
        double targetY = Math.max(
                space.getMinBuildHeight() + 1.0,
                Math.min(space.getMaxBuildHeight() - 2.0, SPACE_ENTRY_HEIGHT + relativeAltitude)
        );
        Vec3 target = new Vec3(pilot.getX(), targetY, pilot.getZ());
        PortalAPI.teleportEntity(pilot, space, target);
        return true;
    }

    private static boolean returnToOverworld(ServerPlayer player) {
        if (player.level().dimension() != SPACE_DIMENSION) {
            return false;
        }

        ServerLevel overworld = player.getServer().getLevel(Level.OVERWORLD);
        if (overworld == null) {
            return false;
        }

        BlockPos spawn = overworld.getSharedSpawnPos();
        PortalAPI.teleportEntity(player, overworld, Vec3.atBottomCenterOf(spawn).add(0.0, 1.0, 0.0));
        return true;
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("rocketmod")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("flight")
                        .then(Commands.literal("altitude")
                                .then(Commands.argument("virtualY", DoubleArgumentType.doubleArg(0.0))
                                        .executes(context -> {
                                            ServerPlayer player = context.getSource().getPlayerOrException();
                                            double altitude = DoubleArgumentType.getDouble(context, "virtualY");
                                            boolean transitioned = onVirtualAltitudeChanged(player, altitude);
                                            if (transitioned) {
                                                context.getSource().sendSuccess(
                                                        () -> Component.literal(String.format(
                                                                "Crossed the %.1f block flight threshold; entering rocketmod:space.",
                                                                Config.flightTransitionAltitude
                                                        )),
                                                        false
                                                );
                                                return 1;
                                            }

                                            context.getSource().sendFailure(Component.literal(
                                                    altitude < Config.flightTransitionAltitude
                                                            ? String.format("Transition starts at virtual Y %.1f.", Config.flightTransitionAltitude)
                                                            : "You are already outside the Overworld or the space dimension is unavailable."
                                            ));
                                            return 0;
                                        })))
                        .then(Commands.literal("return")
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayerOrException();
                                    if (!returnToOverworld(player)) {
                                        context.getSource().sendFailure(Component.literal(
                                                "You are not in rocketmod:space, or the Overworld is unavailable."
                                        ));
                                        return 0;
                                    }
                                    context.getSource().sendSuccess(
                                            () -> Component.literal("Returned to the Overworld spawn."),
                                            false
                                    );
                                    return 1;
                                }))));
    }
}
