"""Capture release-client day/night and orbit fixtures with or without Oculus shaders."""
import argparse
import json
import time
from pathlib import Path

from flight_runtime_suite import BASE, ROOT, execute
from visual_capture_suite import VIEW


def client(code):
    return execute("""
net.minecraft.client.Minecraft c = net.minecraft.client.Minecraft.getInstance();
java.util.concurrent.CompletableFuture<String> reply = new java.util.concurrent.CompletableFuture<>();
c.execute(() -> { try { @CODE@ } catch(Throwable error) { reply.completeExceptionally(error); } });
return reply.get(45, java.util.concurrent.TimeUnit.SECONDS);
""".replace("@CODE@", code))


def snapshot():
    return client("""
Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
com.google.gson.JsonObject j = new com.google.gson.JsonObject();
j.addProperty("pack", iris.getMethod("getCurrentPackName").invoke(null).toString());
j.addProperty("active", com.example.examplemod.client.render.ShaderpackBridge.isActive());
j.addProperty("uniformRegistrations", com.example.examplemod.client.render.ShaderpackBridge.uniformRegistrations());
j.addProperty("altitude", com.example.examplemod.client.render.ShaderpackBridge.altitude());
j.addProperty("dimension", c.level.dimension().location().toString());
j.addProperty("dayTime", c.level.getDayTime());
j.addProperty("shaderError", iris.getMethod("getStoredError").invoke(null).toString());
j.addProperty("screen", c.screen == null ? "none" : c.screen.getClass().getName());
j.addProperty("terrainCells", com.example.examplemod.client.render.WorldTerrainAtlas.sampledCells());
j.addProperty("terrainStatus", com.example.examplemod.client.render.WorldTerrainAtlas.status());
reply.complete(j.toString());
""")


def toggle(enabled):
    execute("""
net.minecraft.client.Minecraft c = net.minecraft.client.Minecraft.getInstance();
c.execute(() -> {
    try {
        Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
        Object config = iris.getMethod("getIrisConfig").invoke(null);
        config.getClass().getMethod("setShadersEnabled", boolean.class).invoke(config, @ENABLED@);
        config.getClass().getMethod("save").invoke(config);
        iris.getMethod("reload").invoke(null);
        System.setProperty("rocketmod.test.shaderReload", "done");
    } catch(Exception error) { System.setProperty("rocketmod.test.shaderReload", error.toString()); }
});
return "reload queued";
""".replace("@ENABLED@", str(enabled).lower()))
    began = time.monotonic()
    while time.monotonic() - began < 90:
        result = execute('return System.getProperty("rocketmod.test.shaderReload", "pending");')
        if result == "done":
            execute('System.clearProperty("rocketmod.test.shaderReload"); return "cleared";')
            break
        if result != "pending":
            raise RuntimeError(result)
        time.sleep(0.5)
    else:
        raise RuntimeError("Oculus reload did not finish")
    state = snapshot()
    if state["active"] != enabled or state["shaderError"] != "Optional.empty":
        raise RuntimeError(f"Oculus mode did not activate cleanly: {state}")


def fixture(altitude, day_time, pitch):
    code = VIEW.replace("@H@", str(altitude)).replace("@PITCH@", str(pitch))
    code = code.replace("p.connection.teleport(", "p.connection.m_9774_(")
    code = code.replace("p.getX()", "p.m_20185_()").replace("p.getZ()", "p.m_20189_()")
    code = code.replace("s.overworld().setDayTime(6000);", f"s.overworld().setDayTime({day_time});")
    execute(code)
    execute(BASE + "s.overworld().setWeatherParameters(6000, 0, false, false); "
            'return "clear weather fixture";')
    time.sleep(3)


def capture(label, report, game):
    filename = f"rocket-shader-{label}.png"
    path = game / "screenshots" / filename
    stamp = path.stat().st_mtime_ns if path.exists() else 0
    client("""
net.minecraft.client.Screenshot.m_92295_(c.gameDirectory, "@FILE@", c.getMainRenderTarget(), message -> {});
reply.complete("queued");
""".replace("@FILE@", filename))
    began = time.monotonic()
    while time.monotonic() - began < 15:
        if path.exists() and path.stat().st_mtime_ns > stamp:
            from PIL import Image, ImageStat, UnidentifiedImageError
            try:
                with Image.open(path).convert("RGB") as frame:
                    mean = ImageStat.Stat(frame).mean
                    extrema = frame.getextrema()
            except (UnidentifiedImageError, OSError):
                time.sleep(0.2)
                continue
            state = snapshot()
            report["frames"].append({"label": label, "file": str(path.relative_to(ROOT)),
                                      "meanRgb": mean, "extrema": extrema, "client": state})
            if ("ground-" in label and max(mean) < 0.1) or state["shaderError"] != "Optional.empty":
                raise RuntimeError(f"Blank or failed shader frame: {label}: {state}")
            print(f"CAPTURE {label}: {state['pack']} H={state['altitude']:.2f}", flush=True)
            return
        time.sleep(0.2)
    raise RuntimeError(f"Screenshot missing: {path}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=["on", "off"], default="on")
    parser.add_argument("--orbit", action="store_true")
    parser.add_argument("--prefix", default="current")
    args = parser.parse_args()
    game = ROOT / ".local-cache/production/client"
    report = {"mode": args.mode, "frames": []}
    try:
        execute(BASE + 'p.setGameMode(net.minecraft.world.level.GameType.CREATIVE); return "safe creative fixture";')
        toggle(args.mode == "on")
        for time_name, ticks in [("noon", 6000), ("sunset", 12000), ("night", 13000),
                                 ("midnight", 18000), ("dawn", 23000)]:
            fixture(114, ticks, -12)
            capture(f"{args.prefix}-{args.mode}-ground-{time_name}", report, game)
        if args.orbit:
            for altitude in [400, 800, 1200, 1599, 1601, 2200, 3000, 3472]:
                for direction, pitch in [("horizon", 0), ("down", 65)]:
                    fixture(altitude, 6000, pitch)
                    capture(f"{args.prefix}-{args.mode}-{altitude}-{direction}", report, game)
        fixture(1900, 18000, -50)
        capture(f"{args.prefix}-{args.mode}-space-midnight", report, game)
        fixture(114, 18000, -35)
        capture(f"{args.prefix}-{args.mode}-returned-midnight", report, game)
        report["result"] = "PASS"
    except Exception as error:
        report["result"] = "FAIL"
        report["error"] = str(error)
        raise
    finally:
        execute(BASE + 'p.setNoGravity(false); p.getAbilities().flying=true; p.onUpdateAbilities(); '
                'net.minecraft.client.Minecraft c=net.minecraft.client.Minecraft.getInstance(); '
                'c.execute(() -> c.options.hideGui=false); return "HUD and gravity restored";')
        output = ROOT / f".local-cache/validation/shader-{args.prefix}-{args.mode}.json"
        output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
