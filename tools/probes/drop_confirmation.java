java.util.concurrent.CompletableFuture<String> installed = new java.util.concurrent.CompletableFuture<>();
net.minecraft.client.Minecraft c = net.minecraft.client.Minecraft.getInstance();
c.execute(() -> {
    try {
        java.lang.reflect.Field receiver = com.example.examplemod.network.FlightNetwork.class.getDeclaredField("clientReceiver");
        java.lang.reflect.Field gate = com.example.examplemod.network.FlightNetwork.class.getDeclaredField("clientGate");
        receiver.setAccessible(true); gate.setAccessible(true);
        java.util.function.Consumer<com.example.examplemod.network.FlightNetwork.ServerMessage> original =
            (java.util.function.Consumer<com.example.examplemod.network.FlightNetwork.ServerMessage>) receiver.get(null);
        java.util.function.BiPredicate<net.minecraft.world.entity.player.Player, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>> originalGate =
            (java.util.function.BiPredicate<net.minecraft.world.entity.player.Player, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>>) gate.get(null);
        System.getProperties().put("rocketmod.test.receiver", original);
        System.getProperties().put("rocketmod.test.gate", originalGate);
        java.util.concurrent.atomic.AtomicInteger dropped = new java.util.concurrent.atomic.AtomicInteger();
        System.getProperties().put("rocketmod.test.confirmDropped", dropped);
        com.example.examplemod.network.FlightNetwork.setClientHandlers(message -> {
            if (message.kind() == com.example.examplemod.network.FlightNetwork.Kind.CONFIRM) dropped.incrementAndGet();
            else original.accept(message);
        }, originalGate);
        java.util.function.Consumer<net.minecraftforge.event.TickEvent.ClientTickEvent> hover = event -> {
            if (event.phase == net.minecraftforge.event.TickEvent.Phase.END && c.player != null && c.player.isFallFlying()
                    && c.player.level().dimension().equals(com.example.examplemod.compat.immptl.ImmersivePortalsBackend.SPACE_DIMENSION))
                c.player.setDeltaMovement(new net.minecraft.world.phys.Vec3(0, 0.25, 0));
        };
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.LOWEST,
            false, net.minecraftforge.event.TickEvent.ClientTickEvent.class, hover);
        System.getProperties().put("rocketmod.test.confirmHover", hover);
        installed.complete("confirmation drop fixture installed");
    } catch (Throwable error) { installed.completeExceptionally(error); }
});
return installed.get(5, java.util.concurrent.TimeUnit.SECONDS);
