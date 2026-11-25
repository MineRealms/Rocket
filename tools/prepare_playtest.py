"""Restore the isolated validation player and optionally show the Minecraft window."""
import argparse
import time

from flight_runtime_suite import BASE, execute


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--show", action="store_true")
    args = parser.parse_args()
    execute("""
net.minecraft.client.Minecraft c = net.minecraft.client.Minecraft.getInstance();
c.execute(() -> {
    if (c.player != null && !c.player.isAlive()) c.player.respawn();
    c.options.pauseOnLostFocus = false;
    c.setScreen(null);
});
return "respawn requested if needed";
""")
    for _ in range(15):
        if execute(BASE + "return Boolean.toString(p.isAlive());") == "true":
            break
        time.sleep(0.25)
    else:
        raise RuntimeError("Validation player did not respawn")
    print(execute(BASE + """
p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
p.stopFallFlying();
com.example.examplemod.transition.FlightTransferCoordinator.logout(p);
p.setNoGravity(false);
p.getAbilities().flying = true; p.onUpdateAbilities();
net.minecraft.core.BlockPos spawn = s.overworld().getSharedSpawnPos();
int y = s.overworld().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
    spawn.getX(), spawn.getZ());
qouteall.imm_ptl.core.api.PortalAPI.teleportEntity(p, s.overworld(),
    new net.minecraft.world.phys.Vec3(spawn.getX() + 0.5, y + 5, spawn.getZ() + 0.5));
p.connection.teleport(spawn.getX() + 0.5, y + 5, spawn.getZ() + 0.5, 0, -10);
s.overworld().setDayTime(6000);
p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
p.fallDistance = 0; p.setHealth(p.getMaxHealth());
p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(p));
net.minecraft.world.item.ItemStack elytra = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ELYTRA);
elytra.getOrCreateTag().putBoolean("Unbreakable", true);
p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, elytra);
net.minecraft.world.item.ItemStack rockets = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.FIREWORK_ROCKET, 64);
rockets.getOrCreateTagElement("Fireworks").putByte("Flight", (byte) 3);
p.getInventory().setItem(0, rockets); p.getInventory().selected = 0;
com.example.examplemod.Config.debugElytraSpaceFlight = true;
com.example.examplemod.compat.immptl.ImmersivePortalsBackend.reconcile(s);
net.minecraft.client.Minecraft c = net.minecraft.client.Minecraft.getInstance();
c.execute(() -> {
    for (String key : new String[]{"rocketmod.test.driver", "rocketmod.test.trace", "rocketmod.test.motion.camera",
            "rocketmod.test.motion.capture"}) {
        Object listener = System.getProperties().remove(key);
        if (listener != null) net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(listener);
    }
    Object writer = System.getProperties().remove("rocketmod.test.trace.writer");
    if (writer instanceof java.io.Closeable) {
        try { ((java.io.Closeable) writer).close(); }
        catch (java.io.IOException error) { throw new RuntimeException(error); }
    }
    System.getProperties().remove("rocketmod.test.motion.frames");
    com.example.examplemod.client.flight.ClientFlightState.setDebugReadyDelayTicks(0);
    c.options.framerateLimit().set(120); c.options.hideGui = false; c.setScreen(null);
});
return "playtest ready: Overworld, creative, Elytra, 64 rockets, boundary Y="
    + com.example.examplemod.Config.flightTransitionAltitude;
"""))
    if args.show:
        print(execute("""
net.minecraft.client.Minecraft c = net.minecraft.client.Minecraft.getInstance();
java.util.concurrent.CompletableFuture<String> visible = new java.util.concurrent.CompletableFuture<>();
c.execute(() -> {
    org.lwjgl.glfw.GLFW.glfwSetWindowSize(c.getWindow().getWindow(), 1280, 720);
    org.lwjgl.glfw.GLFW.glfwShowWindow(c.getWindow().getWindow());
    org.lwjgl.glfw.GLFW.glfwFocusWindow(c.getWindow().getWindow());
    visible.complete("Minecraft window visible=" + (org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(
        c.getWindow().getWindow(), org.lwjgl.glfw.GLFW.GLFW_VISIBLE) == org.lwjgl.glfw.GLFW.GLFW_TRUE));
});
return visible.get(10, java.util.concurrent.TimeUnit.SECONDS);
"""))


if __name__ == "__main__":
    main()
