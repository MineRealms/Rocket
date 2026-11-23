net.minecraft.client.Minecraft c = net.minecraft.client.Minecraft.getInstance();
c.execute(() -> {
    Object hover = System.getProperties().remove("rocketmod.test.confirmHover");
    if (hover != null) net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(hover);
    Object receiver = System.getProperties().remove("rocketmod.test.receiver");
    Object gate = System.getProperties().remove("rocketmod.test.gate");
    if (receiver != null && gate != null) com.example.examplemod.network.FlightNetwork.setClientHandlers(
        (java.util.function.Consumer<com.example.examplemod.network.FlightNetwork.ServerMessage>) receiver,
        (java.util.function.BiPredicate<net.minecraft.world.entity.player.Player, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>>) gate);
});
return "original network handlers restored";
