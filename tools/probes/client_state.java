net.minecraft.client.Minecraft c = net.minecraft.client.Minecraft.getInstance();
java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
c.execute(() -> {
    com.google.gson.JsonObject j = new com.google.gson.JsonObject();
    j.addProperty("dimension", c.level.dimension().location().toString());
    j.addProperty("effects", c.level.effects().getClass().getName());
    j.addProperty("h", com.example.examplemod.client.flight.ClientFlightState.altitude(1));
    j.addProperty("serverH", com.example.examplemod.client.flight.ClientFlightState.serverAltitude());
    j.addProperty("phase", com.example.examplemod.client.flight.ClientFlightState.phase(1).toString());
    j.addProperty("ready", com.example.examplemod.client.flight.ClientFlightState.readinessStatus());
    j.addProperty("serverStatus", com.example.examplemod.client.flight.ClientFlightState.serverStatus());
    j.addProperty("armed", com.example.examplemod.client.flight.ClientFlightState.isArmed());
    j.addProperty("screen", c.screen == null ? "none" : c.screen.getClass().getName());
    j.addProperty("fps", c.getFps());
    result.complete(j.toString());
});
return result.get(10, java.util.concurrent.TimeUnit.SECONDS);
