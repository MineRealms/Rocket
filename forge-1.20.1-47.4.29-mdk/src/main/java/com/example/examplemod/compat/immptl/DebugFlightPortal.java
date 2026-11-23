package com.example.examplemod.compat.immptl;

import com.example.examplemod.Config;
import com.example.examplemod.network.FlightNetwork;
import com.example.examplemod.transition.FlightTransferCoordinator;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import qouteall.imm_ptl.core.portal.Portal;

/** A debug-only flight boundary, evaluated by IP on both sides of the connection. */
public final class DebugFlightPortal extends Portal {
    public DebugFlightPortal(EntityType<?> type, Level level) {
        super(type, level);
    }

    @Override
    public boolean canTeleportEntity(Entity entity) {
        // The client follows server-synced portal existence, not its local COMMON config.
        return (level().isClientSide || Config.debugElytraSpaceFlight)
                && entity instanceof Player player
                && player.isAlive()
                && !player.isSpectator()
                && (level().dimension().equals(ImmersivePortalsBackend.SPACE_DIMENSION) || player.isFallFlying())
                && !player.isPassenger()
                && (level().isClientSide ? FlightNetwork.clientCanCross(player, dimensionTo)
                    : player instanceof ServerPlayer serverPlayer && FlightTransferCoordinator.canCross(serverPlayer, dimensionTo))
                && super.canTeleportEntity(entity);
    }
}
