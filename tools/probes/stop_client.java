net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
client.execute(() -> client.stop());
return "Requested normal client shutdown and world save";
