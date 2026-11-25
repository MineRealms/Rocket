"""Capture matching surface views before and after the terrain projection fix."""
import argparse
import json
import time

from flight_runtime_suite import BASE, ROOT, execute
from shaderpack_capture_suite import capture, client, fixture, snapshot, toggle


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phase", choices=["before", "after"], required=True)
    args = parser.parse_args()
    report = {"phase": args.phase, "frames": []}
    game = ROOT / ".local-cache/production/client"
    try:
        fixture(114, 6000, 75)
        execute(BASE + '''
com.example.examplemod.terrain.WorldTerrainAtlasService.logout(
    new net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(p));
p.connection.m_9774_(554.758, 114, 275.291, 35, 75);
return "same sampled region";
''')
        client('''
c.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
reply.complete("first person");
''')
        time.sleep(5)
        toggle(True)
        for altitude, pitch, view in [(400, 75, "down"), (800, 75, "down"), (1100, 75, "down"),
                                      (1400, 75, "down"), (1700, 75, "down"),
                                      (1700, 12, "horizon"), (3400, 75, "down")]:
            fixture(altitude, 6000, pitch)
            execute(BASE + f'''p.connection.m_9774_(p.getX(), p.getY(), p.getZ(), 35, {pitch});
return "matching camera";''')
            time.sleep(1)
            state = snapshot()
            if not state["active"] or state["shaderError"] != "Optional.empty":
                raise RuntimeError(f"Shader pipeline failed: {state}")
            capture(f"surface-{args.phase}-{altitude}-{view}", report, game)
        report["result"] = "PASS"
    except Exception as error:
        report.update(result="FAIL", error=str(error))
        raise
    finally:
        execute(BASE + 'p.setNoGravity(false); p.getAbilities().flying=true; p.onUpdateAbilities(); '
                'return "fixture released";')
        client('c.options.hideGui=false; reply.complete("HUD restored");')
        output = ROOT / f".local-cache/validation/terrain-projection-{args.phase}.json"
        output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(f"Report: {output}", flush=True)


if __name__ == "__main__":
    main()
