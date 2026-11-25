"""Check orbital shader resource reload and window resizing on a visual fixture."""
import json
import time
from datetime import datetime

from flight_runtime_suite import BASE, ROOT, execute
from visual_capture_suite import CAPTURE, VIEW


def client(code):
    return execute("""
net.minecraft.client.Minecraft c = net.minecraft.client.Minecraft.getInstance();
java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
c.execute(() -> {
    try {
        @CODE@
    } catch (Throwable error) { result.completeExceptionally(error); }
});
return result.get(10, java.util.concurrent.TimeUnit.SECONDS);
""".replace("@CODE@", code))


def snapshot():
    return client("""
com.google.gson.JsonObject j = new com.google.gson.JsonObject();
j.addProperty("windowWidth", c.getWindow().getScreenWidth());
j.addProperty("windowHeight", c.getWindow().getScreenHeight());
j.addProperty("framebufferWidth", c.getWindow().getWidth());
j.addProperty("framebufferHeight", c.getWindow().getHeight());
j.addProperty("renderDistance", c.options.renderDistance().get());
j.addProperty("hideGui", c.options.hideGui);
j.addProperty("fullscreen", c.getWindow().isFullscreen());
j.addProperty("h", com.example.examplemod.client.flight.ClientFlightState.altitude(1));
try {
    Class<?> type = Class.forName("com.example.examplemod.client.render.OrbitalTerrainRenderer");
    j.addProperty("sampledCells", ((Number) type.getMethod("sampledCells").invoke(null)).intValue());
} catch (ReflectiveOperationException error) { j.addProperty("sampledCellsUnavailable", error.toString()); }
try {
    Class<?> type = Class.forName("com.example.examplemod.client.render.OrbitShaders");
    Object shader = type.getMethod("surface").invoke(null);
    j.addProperty("shaderPresent", shader != null);
    j.addProperty("skyShaderPresent", type.getMethod("sky").invoke(null) != null);
} catch (ReflectiveOperationException error) {
    j.addProperty("shaderPresent", false); j.addProperty("shaderError", error.toString());
}
result.complete(j.toString());
""")


def wait_state(predicate, description, timeout=20):
    began = time.monotonic()
    while time.monotonic() - began < timeout:
        state = snapshot()
        if predicate(state):
            return state
        time.sleep(0.25)
    raise RuntimeError(f"Timed out waiting for {description}: {state}")


def resize(width, height):
    before = snapshot()
    changed = (before["windowWidth"], before["windowHeight"]) != (width, height)
    client(f"org.lwjgl.glfw.GLFW.glfwSetWindowSize(c.getWindow().getWindow(), {width}, {height}); "
           'result.complete("window resize queued");')
    return wait_state(lambda state: state["windowWidth"] == width and state["windowHeight"] == height
                      and state["framebufferWidth"] > 0 and state["framebufferHeight"] > 0
                      and (not changed or (state["framebufferWidth"], state["framebufferHeight"]) != (
                          before["framebufferWidth"], before["framebufferHeight"])),
                      f"window resize to {width}x{height}")


def reload_resources():
    execute("""
net.minecraft.client.Minecraft c = net.minecraft.client.Minecraft.getInstance();
java.util.concurrent.CompletableFuture<String> completion = new java.util.concurrent.CompletableFuture<>();
System.getProperties().put("rocketmod.test.visualReload", completion);
c.execute(() -> {
    try {
        c.reloadResourcePacks().whenComplete((ignored, error) -> {
            if (error == null) completion.complete("complete");
            else completion.completeExceptionally(error);
        });
    } catch (Throwable error) { completion.completeExceptionally(error); }
});
return "resource reload queued";
""")
    began = time.monotonic()
    while time.monotonic() - began < 90:
        status = execute("""
java.util.concurrent.CompletableFuture<?> completion = (java.util.concurrent.CompletableFuture<?>)
    System.getProperties().get("rocketmod.test.visualReload");
if (completion == null) throw new IllegalStateException("Missing reload future");
if (!completion.isDone()) return "pending";
return completion.join().toString();
""")
        if status == "complete":
            return {"result": "PASS", "elapsedSeconds": time.monotonic() - began}
        time.sleep(0.25)
    raise RuntimeError("Resource reload future did not finish within 90 seconds")


def capture(label):
    filename = f"rocket-H-{label}.png"
    path = ROOT / "forge-1.20.1-47.4.29-mdk/run/screenshots" / filename
    execute(CAPTURE.replace("@H@", label))
    began = time.monotonic()
    while time.monotonic() - began < 15:
        if path.is_file() and path.stat().st_size > 0:
            return {"file": str(path.relative_to(ROOT)), "bytes": path.stat().st_size}
        time.sleep(0.25)
    raise RuntimeError(f"Screenshot was not written: {path}")


def main():
    report = {"started": datetime.now().isoformat(), "fixtures": []}
    output = ROOT / ".local-cache/validation/p1-reload-resize.json"
    original = snapshot()
    report["original"] = original
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    try:
        if original["fullscreen"]:
            raise RuntimeError("Run this suite in a window so original dimensions can be restored")
        execute(BASE + "p.setGameMode(net.minecraft.world.level.GameType.CREATIVE); "
                'return "safe creative visual fixture";')
        threshold = float(execute(BASE + "return Double.toString(com.example.examplemod.Config.flightTransitionAltitude);"))
        for altitude in (900, int(threshold + 1872)):
            fixture = {"h": altitude, "captures": []}
            report["fixtures"].append(fixture)
            execute(VIEW.replace("@H@", str(altitude)).replace("@PITCH@", "75"))
            time.sleep(2)
            before = wait_state(lambda state: state.get("sampledCells", 1) > 0,
                                "loaded terrain samples", timeout=30)
            fixture["beforeReload"] = before
            fixture["reload"] = reload_resources()
            after = wait_state(lambda state: state["shaderPresent"] and state.get("skyShaderPresent", False)
                               and state.get("sampledCells", 1) > 0,
                               "shader and loaded terrain after reload", timeout=30)
            fixture["afterReload"] = after
            previous = after
            for width, height in ((1280, 720), (960, 540)):
                state = resize(width, height)
                if (previous["windowWidth"], previous["windowHeight"]) != (width, height):
                    if (state["framebufferWidth"], state["framebufferHeight"]) == (
                            previous["framebufferWidth"], previous["framebufferHeight"]):
                        raise RuntimeError("Window resize did not change framebuffer dimensions")
                if not state["shaderPresent"] or not state.get("skyShaderPresent", False):
                    raise RuntimeError("Orbital shader missing after resize")
                time.sleep(1)
                fixture["captures"].append({"requestedSize": [width, height], "state": state,
                    **capture(f"{altitude}-reload-{width}x{height}-{stamp}")})
                previous = state
        report["result"] = "PASS"
    except Exception as error:
        report["result"] = "FAIL"
        report["error"] = str(error)
        raise
    finally:
        cleanup_errors = []
        try:
            if not original["fullscreen"]:
                resize(original["windowWidth"], original["windowHeight"])
        except Exception as error:
            cleanup_errors.append(f"restore dimensions: {error}")
        try:
            client('c.options.hideGui=false; result.complete("HUD restored");')
        except Exception as error:
            cleanup_errors.append(f"restore HUD: {error}")
        try:
            execute(BASE + "p.setNoGravity(false); p.stopFallFlying(); "
                    "p.getAbilities().flying=true; p.onUpdateAbilities(); "
                    "p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO); "
                    'return "gravity and creative flight restored";')
            report["playerCleanup"] = execute(BASE + """
com.google.gson.JsonObject j = new com.google.gson.JsonObject();
j.addProperty("noGravity", p.isNoGravity());
j.addProperty("creativeFlying", p.getAbilities().flying);
return j.toString();
""")
            if report["playerCleanup"]["noGravity"] or not report["playerCleanup"]["creativeFlying"]:
                raise RuntimeError("Player gravity or creative flight was not restored")
        except Exception as error:
            cleanup_errors.append(f"restore player: {error}")
        try:
            report["cleanup"] = snapshot()
            if report["cleanup"]["hideGui"]:
                raise RuntimeError("HUD was not restored")
        except Exception as error:
            cleanup_errors.append(f"verify cleanup: {error}")
        if cleanup_errors:
            report["result"] = "FAIL"
            report["cleanupErrors"] = cleanup_errors
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(f"Report: {output}", flush=True)
        if cleanup_errors:
            raise RuntimeError("; ".join(cleanup_errors))


if __name__ == "__main__":
    main()
