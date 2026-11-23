"""Exercise real IP crossings on a disposable integrated-server world through Rosetta."""
import argparse
from datetime import datetime
import json
from pathlib import Path
import time

from flight_probe import request

ROOT = Path(__file__).resolve().parents[1]
BASE = """
net.minecraft.server.MinecraftServer s = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
net.minecraft.server.level.ServerPlayer p = s.getPlayerList().getPlayers().get(0);
"""
STATE = BASE + """
com.google.gson.JsonObject j = new com.google.gson.JsonObject();
j.addProperty("dimension", p.level().dimension().location().toString());
j.addProperty("y", p.getY()); j.addProperty("x", p.getX()); j.addProperty("z", p.getZ());
j.addProperty("h", com.example.examplemod.transition.FlightTransferCoordinator.altitude(p));
j.addProperty("velocityY", p.getDeltaMovement().y);
j.addProperty("gliding", p.isFallFlying()); j.addProperty("noGravity", p.isNoGravity());
j.addProperty("uuid", p.getUUID().toString());
j.addProperty("leases", com.example.examplemod.compat.immptl.ImmersivePortalsBackend.getChunkLeaseCount());
j.addProperty("portals", com.example.examplemod.compat.immptl.ImmersivePortalsBackend.getOwnedPortalCount(s));
j.addProperty("status", com.example.examplemod.transition.FlightTransferCoordinator.status(p));
return j.toString();
"""
START = BASE + """
p.stopFallFlying();
com.example.examplemod.transition.FlightTransferCoordinator.logout(p);
p.setNoGravity(false);
boolean up = p.level().dimension().equals(net.minecraft.world.level.Level.OVERWORLD);
double boundary = up ? com.example.examplemod.Config.flightTransitionAltitude : 64.0;
p.teleportTo(p.serverLevel(), @X@, boundary + (up ? -90 : 90), @Z@, 0, up ? -85 : 85);
p.getAbilities().flying = false; p.onUpdateAbilities();
p.setOnGround(false); p.startFallFlying();
p.setDeltaMovement(new net.minecraft.world.phys.Vec3(0, up ? 0.6 : -0.6, 0)); p.hurtMarked = true;
net.minecraft.client.Minecraft c = net.minecraft.client.Minecraft.getInstance();
c.execute(() -> { c.options.pauseOnLostFocus = false; c.options.framerateLimit().set(@FPS@); c.setScreen(null); });
return "same-dimension flight setup";
"""
KICK = BASE + """
if (com.example.examplemod.transition.FlightTransferCoordinator.status(p).startsWith("READY")) {
    boolean up = p.level().dimension().equals(net.minecraft.world.level.Level.OVERWORLD);
    p.setDeltaMovement(new net.minecraft.world.phys.Vec3(0, up ? @SPEED@ : -@SPEED@, 0)); p.hurtMarked = true;
}
return "velocity pulse; no dimension teleport";
"""
RESTORE = BASE + """
net.minecraft.client.Minecraft c = net.minecraft.client.Minecraft.getInstance();
c.execute(() -> { com.example.examplemod.client.flight.ClientFlightState.setDebugReadyDelayTicks(0);
    c.options.framerateLimit().set(120); });
p.stopFallFlying(); com.example.examplemod.transition.FlightTransferCoordinator.logout(p);
p.setNoGravity(false); p.getAbilities().flying = true; p.onUpdateAbilities();
p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO); p.hurtMarked = true;
return "suite cleanup";
"""


def execute(code):
    response = request("exec", {"code": code})
    if not response.get("ok"):
        raise RuntimeError(json.dumps(response, ensure_ascii=False))
    result = response["result"]["result"].rsplit("  [", 1)[0]
    return json.loads(result) if result.startswith("{") else result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rounds", type=int, default=20)
    parser.add_argument("--output", type=Path, default=ROOT / ".local-cache/validation/round-trips.json")
    args = parser.parse_args()
    report = {"started": datetime.now().isoformat(), "legs": [], "samples": []}
    origin = execute(STATE)
    report["origin"] = origin
    if origin["dimension"] != "minecraft:overworld":
        raise RuntimeError("Start the suite in the Overworld of a disposable test world")
    try:
        for leg in range(args.rounds * 2):
            speed = (0.75, 1.5, 3.0)[(leg // 2) % 3]
            fps = 10 if (leg // 2) % 5 == 4 else 120
            # Four new observation regions exercise cold target chunks during the run.
            offset = (leg // 10) * 256
            start = START.replace("@X@", str(origin["x"] + offset)).replace("@Z@", str(origin["z"]))
            execute(start.replace("@FPS@", str(fps)))
            source = execute(STATE)
            target = "rocketmod:space" if leg % 2 == 0 else "minecraft:overworld"
            began = time.monotonic()
            while time.monotonic() - began < 35:
                state = execute(STATE)
                report["samples"].append({"leg": leg, "elapsed": time.monotonic() - began, **state})
                if state["uuid"] != origin["uuid"] or state["portals"] != 2:
                    raise RuntimeError("Player identity or portal pair changed")
                if state["dimension"] == target:
                    if not state["gliding"]:
                        raise RuntimeError("Elytra state lost during crossing")
                    report["legs"].append({"leg": leg, "speedInput": speed, "fpsCap": fps,
                        "elapsed": time.monotonic() - began, "source": source, "arrival": state})
                    print(f"PASS {leg + 1}/{args.rounds * 2}: {target} H={state['h']:.2f} vy={state['velocityY']:.3f}", flush=True)
                    time.sleep(0.7)
                    break
                execute(KICK.replace("@SPEED@", str(speed)))
                time.sleep(0.25)
            else:
                raise RuntimeError(f"Crossing {leg} timed out: {state}")
        report["result"] = "PASS"
    except Exception as error:
        report["result"] = "FAIL"
        report["error"] = str(error)
        raise
    finally:
        execute(RESTORE)
        time.sleep(0.5)
        report["cleanup"] = execute(STATE)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"Report: {args.output}", flush=True)


if __name__ == "__main__":
    main()
