if (!net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) return "Integrated client only";
net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
client.execute(() -> net.minecraft.client.Screenshot.grab(client.gameDirectory, client.getMainRenderTarget(),
    message -> org.slf4j.LoggerFactory.getLogger("ROCKET-Probe").info(message.getString())));
return "Queued Minecraft framebuffer screenshot on render thread";
