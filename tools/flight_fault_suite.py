"""Delay Ready, force a preparation timeout, and replay replies on a disposable test world."""
import json
from pathlib import Path
import time

from flight_runtime_suite import ARM, BASE, RESTORE, ROOT, START, STATE, execute

RESET = BASE + """
p.stopFallFlying(); com.example.examplemod.transition.FlightTransferCoordinator.logout(p);
p.setNoGravity(false); p.getAbilities().flying = true; p.onUpdateAbilities();
net.minecraft.server.level.ServerLevel ow = s.overworld();
if (!p.level().dimension().equals(net.minecraft.world.level.Level.OVERWORLD))
    qouteall.imm_ptl.core.api.PortalAPI.teleportEntity(p, ow, new net.minecraft.world.phys.Vec3(p.getX(), 422, p.getZ()));
p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO); p.hurtMarked = true;
return "fixture reset, excluded from natural crossing count";
"""


def delay(ticks):
    execute("net.minecraft.client.Minecraft.getInstance().execute(() -> "
            f"com.example.examplemod.client.flight.ClientFlightState.setDebugReadyDelayTicks({ticks})); return \"delay set\";")


def setup(ticks):
    execute(RESET)
    delay(ticks)
    origin = execute(STATE)
    execute(START.replace("@X@", str(origin["x"])).replace("@Z@", str(origin["z"]))
            .replace("@FPS@", "30").replace("@SPEED@", "1.5"))
    time.sleep(1)
    execute(ARM)


def wait_case(name, ticks, target, report):
    setup(ticks)
    began = time.monotonic()
    samples = []
    held = False
    while time.monotonic() - began < 25:
        state = execute(STATE)
        samples.append({"elapsed": time.monotonic() - began, **state})
        held |= state["status"].startswith("HOLDING") and state["noGravity"]
        if state["dimension"] == "rocketmod:space":
            if target != "cross" or not held:
                raise RuntimeError(f"Unexpected crossing or no safety wait: {name}")
            report[name] = {"result": "PASS", "samples": samples}
            print(f"PASS {name}: held safely before real Ready; natural crossing resumed", flush=True)
            return
        if state["y"] > 504:
            raise RuntimeError(f"Unsafe source position during wait: {state}")
        if target == "timeout" and time.monotonic() - began > 10 and held and state["leases"] == 0:
            if state["noGravity"]:
                raise RuntimeError("Temporary no-gravity leaked after timeout")
            report[name] = {"result": "PASS", "samples": samples}
            print(f"PASS {name}: no crossing, lease released and gravity restored", flush=True)
            return
        time.sleep(0.25)
    raise RuntimeError(f"Case timed out: {name}; last={state}")


def main():
    report = {}
    output = ROOT / ".local-cache/validation/p0-faults.json"
    try:
        wait_case("delayedReady120Ticks", 120, "cross", report)
        wait_case("preparationTimeout", 600, "timeout", report)
        setup(600)
        time.sleep(1)
        code = BASE + """
String status = com.example.examplemod.transition.FlightTransferCoordinator.status(p);
java.util.UUID id = java.util.UUID.fromString(status.split("id=")[1].split(";")[0]);
java.lang.reflect.Field rev = com.example.examplemod.transition.FlightTransferCoordinator.class.getDeclaredField("revision");
rev.setAccessible(true); long revision = rev.getLong(null);
com.example.examplemod.network.FlightNetwork.ClientReply first = new com.example.examplemod.network.FlightNetwork.ClientReply(
    id, 1000000, revision, false, false, "first ordered reply");
com.example.examplemod.transition.FlightTransferCoordinator.onReply(p, first);
com.example.examplemod.transition.FlightTransferCoordinator.onReply(p, new com.example.examplemod.network.FlightNetwork.ClientReply(
    id, 1000000, revision, true, false, "duplicate must be rejected"));
if (!com.example.examplemod.transition.FlightTransferCoordinator.status(p).contains("first ordered reply"))
    throw new IllegalStateException("Duplicate sequence changed the session");
com.example.examplemod.transition.FlightTransferCoordinator.logout(p);
p.setDeltaMovement(new net.minecraft.world.phys.Vec3(0, 1, 0));
com.example.examplemod.transition.FlightTransferCoordinator.tick(s);
com.example.examplemod.transition.FlightTransferCoordinator.onReply(p, new com.example.examplemod.network.FlightNetwork.ClientReply(
    id, 1000001, revision, true, true, "old session must be rejected"));
if (com.example.examplemod.transition.FlightTransferCoordinator.canCross(p, com.example.examplemod.compat.immptl.ImmersivePortalsBackend.SPACE_DIMENSION))
    throw new IllegalStateException("Old session opened the gate");
return "PASS: duplicate sequence and old session rejected by real reply handler";
"""
        report["replay"] = execute(code)
        print(report["replay"], flush=True)
        report["result"] = "PASS"
    except Exception as error:
        report["result"] = "FAIL"
        report["error"] = str(error)
        raise
    finally:
        execute(RESTORE)
        execute(RESET)
        report["cleanup"] = execute(STATE)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(f"Report: {output}", flush=True)


if __name__ == "__main__":
    main()
