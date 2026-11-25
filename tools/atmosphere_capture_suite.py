"""Capture sky in all directions across the entire atmosphere and the IP boundary."""
import json
import time

from flight_runtime_suite import BASE, ROOT, execute
from visual_capture_suite import CAPTURE, VIEW
from visual_reload_suite import snapshot


def main():
    report = {"frames": []}
    output = ROOT / ".local-cache/validation/p1-atmosphere.json"
    try:
        execute(BASE + "p.setGameMode(net.minecraft.world.level.GameType.CREATIVE); return \"creative fixture\";")
        for altitude in [120, 400, 800, 1200, 1599, 1601, 2200, 3000, 3472, 5000]:
            for direction, pitch in [("up", -70), ("horizon", 0), ("down", 60)]:
                execute(VIEW.replace("@H@", str(altitude)).replace("@PITCH@", str(pitch)))
                time.sleep(3 if direction == "up" else 0.7)
                label = f"atmosphere-{altitude}-{direction}"
                execute(CAPTURE.replace("@H@", label))
                state = snapshot()
                report["frames"].append({"altitude": altitude, "direction": direction, "client": state,
                    "file": f"forge-1.20.1-47.4.29-mdk/run/screenshots/rocket-H-{label}.png"})
                if not state["shaderPresent"] or not state.get("skyShaderPresent", False):
                    raise RuntimeError("Surface or atmosphere shader unavailable")
                print(f"CAPTURE H={altitude} {direction}", flush=True)
        report["result"] = "PASS"
    except Exception as error:
        report["result"] = "FAIL"
        report["error"] = str(error)
        raise
    finally:
        execute(BASE + "p.setNoGravity(false); p.getAbilities().flying=true; p.onUpdateAbilities(); "
                "net.minecraft.client.Minecraft c=net.minecraft.client.Minecraft.getInstance(); "
                "c.execute(() -> c.options.hideGui=false); return \"fixture cleanup\";")
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
