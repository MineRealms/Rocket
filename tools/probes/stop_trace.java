net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
client.execute(() -> {
    Object listener = System.getProperties().remove("rocketmod.test.trace");
    if (listener != null) net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(listener);
    java.io.Writer writer = (java.io.Writer) System.getProperties().remove("rocketmod.test.trace.writer");
    try { if (writer != null) writer.close(); } catch (Exception e) { throw new RuntimeException(e); }
});
return "Stopped and flushed main-client frame trace";
