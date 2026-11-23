net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
client.execute(() -> {
    try {
        Object old = System.getProperties().remove("rocketmod.test.trace");
        if (old != null) net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(old);
        java.io.BufferedWriter writer = new java.io.BufferedWriter(new java.io.FileWriter(
            new java.io.File(client.gameDirectory, "flight-validation-frames.jsonl")));
        System.getProperties().put("rocketmod.test.trace.writer", writer);
        final long[] previous = {System.nanoTime()};
        final long[] sequence = {0};
        java.util.function.Consumer<net.minecraftforge.event.TickEvent.RenderTickEvent> trace = event -> {
            if (event.phase != net.minecraftforge.event.TickEvent.Phase.END || client.player == null) return;
            try {
                long now = System.nanoTime();
                com.google.gson.JsonObject j = new com.google.gson.JsonObject();
                j.addProperty("frame", ++sequence[0]);
                j.addProperty("nanos", now); j.addProperty("frameMs", (now-previous[0])/1000000.0); previous[0]=now;
                j.addProperty("dimension", client.player.level().dimension().location().toString());
                j.addProperty("h", com.example.examplemod.client.flight.ClientFlightState.altitude(event.renderTickTime));
                j.addProperty("vy", client.player.getDeltaMovement().y);
                j.addProperty("yaw", client.player.getYRot()); j.addProperty("pitch", client.player.getXRot());
                j.addProperty("screen", client.screen == null ? "none" : client.screen.getClass().getName());
                j.addProperty("ready", com.example.examplemod.client.flight.ClientFlightState.isArmed());
                writer.write(j.toString()); writer.newLine();
            } catch (Exception e) { throw new RuntimeException(e); }
        };
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.LOWEST,
            false, net.minecraftforge.event.TickEvent.RenderTickEvent.class, trace);
        System.getProperties().put("rocketmod.test.trace", trace);
    } catch (Exception e) { throw new RuntimeException(e); }
});
return "Started main-client frame trace; stop_trace.java closes and flushes";
