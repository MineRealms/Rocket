net.minecraft.server.MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayers().get(0);
if (!player.level().dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return "Start in Overworld";
boolean originalGravity = player.isNoGravity();
try {
    for (int i = 0; i < 100; i++) {
        player.stopFallFlying();
        com.example.examplemod.transition.FlightTransferCoordinator.logout(player);
        player.setNoGravity(false);
        player.setPos(player.getX(), com.example.examplemod.Config.flightTransitionAltitude - 6, player.getZ());
        player.startFallFlying();
        player.setDeltaMovement(new net.minecraft.world.phys.Vec3(0, 1, 0));
        com.example.examplemod.transition.FlightTransferCoordinator.tick(server);
        if (com.example.examplemod.transition.FlightTransferCoordinator.leaseCount() != 1)
            throw new IllegalStateException("Preparation did not acquire one bounded lease at iteration " + i);
        com.example.examplemod.transition.FlightTransferCoordinator.onReply(player,
            new com.example.examplemod.network.FlightNetwork.ClientReply(java.util.UUID.randomUUID(),
                Long.MAX_VALUE, Long.MAX_VALUE, true, true, "stale/invalid reply"));
        if (com.example.examplemod.transition.FlightTransferCoordinator.canCross(player,
                com.example.examplemod.compat.immptl.ImmersivePortalsBackend.SPACE_DIMENSION))
            throw new IllegalStateException("Invalid reply opened server gate");
        com.example.examplemod.transition.FlightTransferCoordinator.logout(player);
        if (com.example.examplemod.transition.FlightTransferCoordinator.leaseCount() != 0 || player.isNoGravity())
            throw new IllegalStateException("Lease or temporary gravity state leaked at iteration " + i);
    }
    return "PASS: 100 prepare/hold/invalid-reply/cancel cycles; leases=0 and gravity restored";
} finally {
    com.example.examplemod.transition.FlightTransferCoordinator.logout(player);
    player.stopFallFlying();
    player.setNoGravity(originalGravity);
    player.getAbilities().flying = true;
    player.onUpdateAbilities();
    player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
    player.hurtMarked = true;
}
