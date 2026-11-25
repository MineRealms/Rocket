"""Compare the same orbital view with Overworld and Space clocks set to noon and midnight."""
import json
import time

from flight_runtime_suite import BASE, ROOT, execute
from shaderpack_capture_suite import capture, client, fixture, snapshot, toggle


def set_space_time(day_time):
    execute(BASE + f'''\
net.minecraft.server.level.ServerLevel space=s.getLevel(com.example.examplemod.compat.immptl.ImmersivePortalsBackend.SPACE_DIMENSION);
s.overworld().setDayTime({day_time}L);
space.setDayTime({day_time}L);
return "space time="+space.getDayTime()+"; overworld time="+s.overworld().getDayTime();
''')
    time.sleep(2)


def main():
    report = {"frames": []}
    game = ROOT / ".local-cache/production/client"
    try:
        execute(BASE + 'p.setGameMode(net.minecraft.world.level.GameType.CREATIVE); return "creative test fixture";')
        client('''
c.execute(() -> { c.options.hideGui=true; c.options.pauseOnLostFocus=false;
    c.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
    reply.complete("orbit capture view ready"); });
''')
        fixture(1900, 6000, 58)
        toggle(True)
        set_space_time(6000)
        report["serverDayState"] = execute(BASE + '''
return Long.toString(s.overworld().getDayTime());
''')
        report["dayState"] = snapshot()
        capture("orbital-light-space-day", report, game)
        set_space_time(18000)
        report["serverNightState"] = execute(BASE + '''
return Long.toString(s.overworld().getDayTime());
''')
        report["nightState"] = snapshot()
        capture("orbital-light-space-night", report, game)
        from PIL import Image
        import numpy as np
        day = np.asarray(Image.open(game / "screenshots/rocket-shader-orbital-light-space-day.png").convert("RGB"))
        night = np.asarray(Image.open(game / "screenshots/rocket-shader-orbital-light-space-night.png").convert("RGB"))
        terrain_day = day[day.shape[0] // 3:, day.shape[1] // 4: 3 * day.shape[1] // 4]
        terrain_night = night[night.shape[0] // 3:, night.shape[1] // 4: 3 * night.shape[1] // 4]
        report["terrainMedianRgb"] = {"spaceDay": np.median(terrain_day, axis=(0, 1)).tolist(),
                                     "spaceNight": np.median(terrain_night, axis=(0, 1)).tolist()}
        report["terrainMedianDelta"] = float(np.abs(np.median(terrain_day, axis=(0, 1))
                                                      - np.median(terrain_night, axis=(0, 1))).mean())
        if report["dayState"]["shaderError"] != "Optional.empty" or report["nightState"]["shaderError"] != "Optional.empty":
            raise RuntimeError("Oculus shader compilation failed")
        if report["terrainMedianDelta"] > 4.0:
            raise RuntimeError(f"Space time still changes orbital terrain brightness: {report['terrainMedianDelta']}")
        report["result"] = "PASS"
    except Exception as error:
        report.update(result="FAIL", error=str(error))
        raise
    finally:
        try:
            set_space_time(6000)
            client('c.options.hideGui=false; reply.complete("HUD restored");')
            execute(BASE + 'p.setNoGravity(false); p.getAbilities().flying=true; p.onUpdateAbilities(); return "flight restored";')
        except Exception as cleanup_error:
            report["cleanupError"] = str(cleanup_error)
        output = ROOT / ".local-cache/validation/orbital-light.json"
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(f"Report: {output}", flush=True)


if __name__ == "__main__":
    main()
