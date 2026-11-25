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
    qouteall.imm_ptl.core.api.PortalAPI.teleportEntity(p, ow, new net.minecraft.world.phys.Vec3(p.getX(), com.example.examplemod.Config.flightTransitionAltitude - 90, p.getZ()));
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
    execute(START.replace("up ? -90 : 90", "up ? -40 : 40")
            .replace("@X@", str(origin["x"])).replace("@Z@", str(origin["z"]))
            .replace("@FPS@", "30").replace("@SPEED@", "1.5"))
    execute("net.minecraft.client.Minecraft.getInstance().execute(() -> { "
            "Object driver = System.getProperties().remove(\"rocketmod.test.driver\"); "
            "if (driver != null) net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(driver); "
            "}); return \"natural recovery, no velocity driver\";")
    time.sleep(1)
    execute(ARM)


def wait_case(name, ticks, target, report):
    setup(ticks)
    threshold = float(execute(BASE + "return Double.toString(com.example.examplemod.Config.flightTransitionAltitude);"))
    began = time.monotonic()
    samples = []
    report[name] = {"result": "RUNNING", "samples": samples}
    held = False
    boosted = False
    while time.monotonic() - began < 25:
        state = execute(STATE)
        samples.append({"elapsed": time.monotonic() - began, **state})
        held |= state["status"].startswith("HOLDING") and state["noGravity"]
        if held and target == "cross" and state["clientArmed"] and not state["noGravity"] and not boosted:
            execute(BASE + """
net.minecraft.world.item.ItemStack firework = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.FIREWORK_ROCKET);
firework.getOrCreateTagElement("Fireworks").putByte("Flight", (byte) 3);
p.serverLevel().addFreshEntity(new net.minecraft.world.entity.projectile.FireworkRocketEntity(p.level(), firework, p));
return "fresh vanilla firework after readiness wait exhausted the original boost";
""")
            boosted = True
        if state["dimension"] == "rocketmod:space":
            if target != "cross" or not held:
                raise RuntimeError(f"Unexpected crossing or no safety wait: {name}")
            held_samples = [sample["y"] for sample in samples if sample["status"].startswith("HOLDING")]
            if len(held_samples) > 1 and max(held_samples) - min(held_samples) > 1.5:
                raise RuntimeError("Waiting position drifted by more than 1.5 blocks")
            report[name] = {"result": "PASS", "freshVanillaFirework": boosted, "samples": samples}
            print(f"PASS {name}: stable safety hold, Ready recovery, vanilla propulsion crossing", flush=True)
            return
        if not state["clientArmed"] and state["y"] > threshold - 8:
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
java.lang.reflect.Field sessions = com.example.examplemod.transition.FlightTransferCoordinator.class.getDeclaredField("SESSIONS");
sessions.setAccessible(true); Object session = ((java.util.Map<?,?>) sessions.get(null)).get(p.getUUID());
java.lang.reflect.Field last = session.getClass().getDeclaredField("lastReplySequence"); last.setAccessible(true);
long next = last.getLong(session) + 1;
com.example.examplemod.network.FlightNetwork.ClientReply first = new com.example.examplemod.network.FlightNetwork.ClientReply(
    id, next, revision, false, false, "first ordered reply");
com.example.examplemod.transition.FlightTransferCoordinator.onReply(p, first);
com.example.examplemod.transition.FlightTransferCoordinator.onReply(p, new com.example.examplemod.network.FlightNetwork.ClientReply(
    id, next, revision, true, false, "duplicate must be rejected"));
if (!com.example.examplemod.transition.FlightTransferCoordinator.status(p).contains("first ordered reply"))
    throw new IllegalStateException("Duplicate sequence changed the session");
com.example.examplemod.transition.FlightTransferCoordinator.logout(p);
p.setDeltaMovement(new net.minecraft.world.phys.Vec3(0, 1, 0));
com.example.examplemod.transition.FlightTransferCoordinator.tick(s);
com.example.examplemod.transition.FlightTransferCoordinator.onReply(p, new com.example.examplemod.network.FlightNetwork.ClientReply(
    id, next + 1, revision, true, true, "old session must be rejected"));
if (com.example.examplemod.transition.FlightTransferCoordinator.canCross(p, com.example.examplemod.compat.immptl.ImmersivePortalsBackend.SPACE_DIMENSION))
    throw new IllegalStateException("Old session opened the gate");
return "PASS: duplicate sequence and old session rejected by real reply handler";
"""
        report["replay"] = execute(code)
        print(report["replay"], flush=True)
        execute((ROOT / "tools/probes/drop_confirmation.java").read_text(encoding="utf-8"))
        setup(0)
        began = time.monotonic()
        samples = []
        while time.monotonic() - began < 20:
            state = execute(STATE)
            samples.append(state)
            if state["dimension"] == "rocketmod:space" and state["leases"] == 0 and not state["clientArmed"]:
                dropped = execute("return ((java.util.concurrent.atomic.AtomicInteger) System.getProperties().get(\"rocketmod.test.confirmDropped\")).get();")
                if int(dropped) < 1:
                    raise RuntimeError("Confirmation drop injection did not observe any messages")
                report["confirmationLoss"] = {"result": "PASS", "dropped": int(dropped), "samples": samples}
                print(f"PASS confirmationLoss: {dropped} messages dropped, server and client recovered", flush=True)
                break
            time.sleep(0.25)
        else:
            raise RuntimeError(f"Confirmation-loss recovery timed out: {state}")
        report["result"] = "PASS"
    except Exception as error:
        report["result"] = "FAIL"
        report["error"] = str(error)
        raise
    finally:
        execute((ROOT / "tools/probes/restore_confirmation.java").read_text(encoding="utf-8"))
        execute(RESTORE)
        execute(RESET)
        report["cleanup"] = execute(STATE)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(f"Report: {output}", flush=True)


if __name__ == "__main__":
    main()
