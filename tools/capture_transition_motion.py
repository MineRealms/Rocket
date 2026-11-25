"""Record rendered frames around a natural Elytra/IP ascent in the test save."""
import json
import time

from flight_runtime_suite import ARM, BASE, RESTORE, ROOT, START, STATE, execute

INSTALL = """
net.minecraft.client.Minecraft c = net.minecraft.client.Minecraft.getInstance();
c.execute(() -> {
    java.io.File folder = new java.io.File(c.gameDirectory, "screenshots/p1-motion-up");
    folder.mkdirs();
    c.options.hideGui=true; c.options.framerateLimit().set(30);
    com.google.gson.JsonArray frames = new com.google.gson.JsonArray();
    System.getProperties().put("rocketmod.test.motion.frames", frames);
    java.util.function.Consumer<net.minecraftforge.client.event.ViewportEvent.ComputeCameraAngles> camera = event -> event.setPitch(70);
    net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.LOWEST,
        false, net.minecraftforge.client.event.ViewportEvent.ComputeCameraAngles.class, camera);
    System.getProperties().put("rocketmod.test.motion.camera", camera);
    java.util.function.Consumer<net.minecraftforge.event.TickEvent.RenderTickEvent> capture = event -> {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END || c.player == null || frames.size() >= 120) return;
        double h = com.example.examplemod.client.flight.ClientFlightState.altitude(event.renderTickTime);
        if (frames.size() == 0 && h < com.example.examplemod.client.flight.ClientFlightState.transitionAltitude() - 42) return;
        com.google.gson.JsonObject frame = new com.google.gson.JsonObject();
        frame.addProperty("index", frames.size()); frame.addProperty("h", h);
        frame.addProperty("dimension", c.player.level().dimension().location().toString());
        frame.addProperty("screen", c.screen == null ? "none" : c.screen.getClass().getName());
        String name = String.format(java.util.Locale.ROOT, "p1-motion-up/%04d.png", frames.size());
        net.minecraft.client.Screenshot.grab(c.gameDirectory, name, c.getMainRenderTarget(), message -> {});
        frames.add(frame);
    };
    net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.LOWEST,
        false, net.minecraftforge.event.TickEvent.RenderTickEvent.class, capture);
    System.getProperties().put("rocketmod.test.motion.capture", capture);
});
return "motion capture installed";
"""
METADATA = """
net.minecraft.client.Minecraft c = net.minecraft.client.Minecraft.getInstance();
java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
c.execute(() -> {
    com.google.gson.JsonObject j = new com.google.gson.JsonObject();
    j.add("frames", (com.google.gson.JsonArray) System.getProperties().get("rocketmod.test.motion.frames"));
    result.complete(j.toString());
});
return result.get(10, java.util.concurrent.TimeUnit.SECONDS);
"""
CLEANUP = """
net.minecraft.client.Minecraft c = net.minecraft.client.Minecraft.getInstance();
c.execute(() -> {
    for (String key : new String[]{"rocketmod.test.motion.camera", "rocketmod.test.motion.capture"}) {
        Object listener = System.getProperties().remove(key);
        if (listener != null) net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(listener);
    }
    System.getProperties().remove("rocketmod.test.motion.frames");
    c.options.hideGui=false;
});
return "motion capture removed";
"""


def main():
    report = {}
    try:
        state = execute(STATE)
        if state["dimension"] != "minecraft:overworld":
            raise RuntimeError("Start in the Overworld test save")
        execute(START.replace("up ? -90 : 90", "up ? -40 : 40")
                .replace("@X@", str(state["x"])).replace("@Z@", str(state["z"]))
                .replace("@SPEED@", "1.5").replace("@FPS@", "30"))
        execute("net.minecraft.client.Minecraft.getInstance().execute(() -> { "
                "Object driver = System.getProperties().remove(\"rocketmod.test.driver\"); "
                "if (driver != null) net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(driver); "
                "}); return \"motion capture uses vanilla Elytra and fireworks, no velocity driver\";")
        time.sleep(1)
        execute(INSTALL)
        execute(ARM)
        began = time.monotonic()
        while time.monotonic() - began < 25:
            report = execute(METADATA)
            if len(report["frames"]) >= 120:
                break
            time.sleep(0.5)
        else:
            raise RuntimeError("Motion capture did not complete")
        dimensions = {frame["dimension"] for frame in report["frames"]}
        if dimensions != {"minecraft:overworld", "rocketmod:space"}:
            raise RuntimeError(f"Capture missed natural boundary: {dimensions}")
        if any(frame["screen"] != "none" for frame in report["frames"]):
            raise RuntimeError("A screen interrupted the flight")
        report["result"] = "PASS"
        print("PASS: 120 rendered frames include a natural Overworld -> space crossing", flush=True)
    except Exception as error:
        report["result"] = "FAIL"
        report["error"] = str(error)
        raise
    finally:
        execute(CLEANUP)
        execute(RESTORE)
        output = ROOT / ".local-cache/validation/p1-motion.json"
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
