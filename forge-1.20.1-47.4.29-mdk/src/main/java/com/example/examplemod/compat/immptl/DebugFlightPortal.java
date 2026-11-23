package com.example.examplemod.compat.immptl;

import com.example.examplemod.Config;
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
                && player.isFallFlying()
                && !player.isPassenger()
                && super.canTeleportEntity(entity);
    }
}
