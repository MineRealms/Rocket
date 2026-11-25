"""Capture ISS-style boundary continuity, horizon, and ground-night regression fixtures."""
import json
import time

from flight_runtime_suite import BASE, ROOT, execute
from shaderpack_capture_suite import capture, client, fixture, snapshot


def main():
    report = {"frames": [], "scope": "ISS near orbit only"}
    game = ROOT / ".local-cache/production/client"
    try:
        execute(BASE + 'p.setGameMode(net.minecraft.world.level.GameType.CREATIVE); return "creative fixture";')
        client('''
net.minecraft.client.Options options=c.options;
for(java.lang.reflect.Method m:options.getClass().getMethods())
    if(m.getParameterCount()==1 && m.getParameterTypes()[0]==net.minecraft.client.CameraType.class)
        m.invoke(options, net.minecraft.client.CameraType.FIRST_PERSON);
reply.complete("first person");
''')
        for label, altitude, clock, pitch in [
            ("ground-day", 114, 6000, -12),
            ("ground-night", 114, 18000, -35),
            ("ascent-400", 400, 6000, -35),
            ("ascent-800", 800, 6000, -35),
            ("ascent-1200", 1200, 6000, -35),
            ("ascent-1400-down", 1400, 6000, 58),
            ("before-sky", 1590, 6000, -45),
            ("after-sky", 1610, 6000, -45),
            ("before-horizon", 1590, 6000, 12),
            ("after-horizon", 1610, 6000, 12),
            ("space-down", 1700, 6000, 65),
            ("higher-still-near-orbit", 3400, 6000, 12),
            ("space-night", 1700, 18000, -45),
            ("returned-night", 114, 18000, -35),
        ]:
            fixture(altitude, clock, pitch)
            capture("iss-" + label, report, game)
        from PIL import Image
        import numpy as np
        for label in ["before-sky", "after-sky"]:
            frame = np.asarray(Image.open(game / "screenshots" / f"rocket-shader-iss-{label}.png").convert("RGB"))
            region = frame[:220]
            report[label] = {"medianRgb": np.median(region, axis=(0, 1)).tolist(),
                             "brightPixels": int(np.sum(region.max(axis=2) > 60))}
            if region.mean() > 12 or np.sum(region.max(axis=2) > 60) < 25:
                raise RuntimeError(f"ISS sky is gray or has no visible stars: {label}")
        report["pipeline"] = snapshot()
        report["result"] = "PASS"
    except Exception as error:
        report.update(result="FAIL", error=str(error))
        raise
    finally:
        execute(BASE + 'p.setNoGravity(false); p.getAbilities().flying=true; p.onUpdateAbilities(); '
                'return "gravity restored";')
        client('c.options.hideGui=false; reply.complete("HUD restored");')
        output = ROOT / ".local-cache/validation/iss-visual.json"
        output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(f"Report: {output}", flush=True)


if __name__ == "__main__":
    main()
