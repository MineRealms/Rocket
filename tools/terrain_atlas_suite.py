"""Validate actual-world atlas progress, cached replay, and GPU texture rebuild."""
import argparse
import json
import time

from flight_runtime_suite import BASE, ROOT, execute
from shaderpack_capture_suite import capture, client, fixture, snapshot


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--x", type=float)
    parser.add_argument("--z", type=float)
    args = parser.parse_args()
    if (args.x is None) != (args.z is None):
        parser.error("Supply both --x and --z to select a previously sampled region")
    report = {"samples": [], "frames": [], "region": {"x": args.x, "z": args.z}}
    started = time.monotonic()
    try:
        if args.x is not None:
            execute(BASE + f'''
com.example.examplemod.terrain.WorldTerrainAtlasService.logout(
    new net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(p));
p.connection.m_9774_({args.x}, p.getY(), {args.z}, 0, 65);
return "selected terrain validation region";
''')
        fixture(1400, 6000, 65)
        while time.monotonic() - started < 300:
            state = snapshot()
            report["samples"].append(state)
            print(f"ATLAS {state['terrainCells']}/16384 represented: {state['terrainStatus']}", flush=True)
            actual = int(state["terrainStatus"].split("samples ", 1)[1].split("/", 1)[0])
            if actual >= 1024:
                break
            time.sleep(10)
        else:
            raise RuntimeError("Fewer than 1024 actual terrain samples arrived within 300 seconds")
        report["coarseReadySeconds"] = time.monotonic() - started
        capture("actual-atlas-32x32-down", report, ROOT / ".local-cache/production/client")
        report["textureRebuild"] = client('''
int before=com.example.examplemod.client.render.WorldTerrainAtlas.sampledCells();
com.example.examplemod.client.render.WorldTerrainAtlas.invalidate();
com.example.examplemod.client.render.WorldTerrainAtlas.textureId();
int after=com.example.examplemod.client.render.WorldTerrainAtlas.sampledCells();
if(before!=after) throw new IllegalStateException("GPU rebuild lost the CPU atlas");
reply.complete("CPU atlas preserved: "+after);
''')
        report["save"] = execute(BASE + '''
s.overworld().getDataStorage().save();
return com.example.examplemod.terrain.WorldTerrainAtlasService.status(p);
''')
        report["result"] = "PASS"
    except Exception as error:
        report.update(result="FAIL", error=str(error))
        raise
    finally:
        output = ROOT / ".local-cache/validation/terrain-atlas.json"
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(f"Report: {output}", flush=True)


if __name__ == "__main__":
    main()
