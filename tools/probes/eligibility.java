net.minecraft.server.MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayers().get(0);
boolean originalDebug = com.example.examplemod.Config.debugElytraSpaceFlight;
boolean originalGliding = player.isFallFlying();
try {
    com.example.examplemod.Config.debugElytraSpaceFlight = true;
    com.example.examplemod.compat.immptl.ImmersivePortalsBackend.reconcile(server);
    com.example.examplemod.compat.immptl.ImmersivePortalsBackend.reconcile(server);
    if (com.example.examplemod.compat.immptl.ImmersivePortalsBackend.getOwnedPortalCount(server) != 2)
        throw new IllegalStateException("Expected exactly two portals after repeated reconciliation");
    qouteall.imm_ptl.core.portal.Portal portal = qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage.get(server.overworld()).data.stream()
        .filter(p -> p instanceof com.example.examplemod.compat.immptl.DebugFlightPortal).findFirst().orElseThrow();
    player.stopFallFlying();
    if (portal.canTeleportEntity(player)) throw new IllegalStateException("Non-gliding player accepted");
    player.startFallFlying();
    if (portal.canTeleportEntity(player)) throw new IllegalStateException("Unprepared gliding player accepted");
    net.minecraft.world.phys.Vec3 position = new net.minecraft.world.phys.Vec3(125, com.example.examplemod.Config.flightTransitionAltitude + 3, -250);
    net.minecraft.world.phys.Vec3 mapped = portal.transformPoint(position);
    if (mapped.distanceTo(new net.minecraft.world.phys.Vec3(125, 67, -250)) > 0.000001)
        throw new IllegalStateException("Incorrect coordinate mapping: " + mapped);
    com.example.examplemod.Config.debugElytraSpaceFlight = false;
    if (portal.canTeleportEntity(player)) throw new IllegalStateException("Disabled flag accepted gliding player");
    com.example.examplemod.compat.immptl.ImmersivePortalsBackend.reconcile(server);
    if (com.example.examplemod.compat.immptl.ImmersivePortalsBackend.getOwnedPortalCount(server) != 0
            || com.example.examplemod.compat.immptl.ImmersivePortalsBackend.getChunkLeaseCount() != 0)
        throw new IllegalStateException("Disable failed to clean portals and leases");
    return "PASS: idempotent pair, non-gliding denied, unprepared gliding denied, mapping, flag denial, cleanup";
} finally {
    com.example.examplemod.Config.debugElytraSpaceFlight = originalDebug;
    if (originalGliding) player.startFallFlying(); else player.stopFallFlying();
    com.example.examplemod.compat.immptl.ImmersivePortalsBackend.reconcile(server);
}
