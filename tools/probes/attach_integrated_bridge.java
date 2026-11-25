net.minecraft.server.MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
if (server == null) throw new IllegalStateException("No integrated server is running");
java.lang.reflect.Field instanceField = com.rosetta.remotedebugbridge.net.RemoteBridge.class.getDeclaredField("instance");
instanceField.setAccessible(true);
Object bridge = instanceField.get(null);
java.lang.reflect.Field serverField = bridge.getClass().getDeclaredField("mcServer");
java.lang.reflect.Field threadField = bridge.getClass().getDeclaredField("serverThread");
serverField.setAccessible(true); threadField.setAccessible(true);
server.execute(() -> {
    try {
        serverField.set(bridge, server);
        threadField.set(bridge, Thread.currentThread());
    } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
});
return "Integrated bridge attachment queued on the server thread";
