"""Verify four natural Elytra crossings with the production Oculus pipeline active."""
from datetime import datetime
import json
import time

from flight_runtime_suite import BASE, ROOT, execute
from production_flight_suite import BOOST, SETUP, STATE
from shaderpack_capture_suite import capture, client, fixture, snapshot


def main():
    game = ROOT / ".local-cache/production/client"
    report = {"started": datetime.now().isoformat(), "legs": [], "frames": []}
    fixture(114, 18000, -35)
    client('c.options.hideGui = true; reply.complete("capture ready");')
    origin = execute(STATE)
    if not snapshot()["active"]:
        raise RuntimeError("The Oculus shaderpack must be active")
    capture("flight-night-before", report, game)
    try:
        for leg in range(4):
            target = "rocketmod:space" if leg % 2 == 0 else "minecraft:overworld"
            source = execute(STATE)
            expected_source = "minecraft:overworld" if leg % 2 == 0 else "rocketmod:space"
            if source["dimension"] != expected_source:
                raise RuntimeError(f"Leg {leg} starts in the wrong dimension: {source}")
            client('net.minecraft.client.player.LocalPlayer local=c.player; '
                   'local.m_20242_(false); reply.complete("flight fixture released");')
            execute(SETUP.replace("@X@", str(4096 + (leg // 2) * 512)))
            time.sleep(1.5)
            execute(BOOST)
            began = time.monotonic()
            boosted = False
            while time.monotonic() - began < 40:
                state = execute(STATE)
                if state["uuid"] != origin["uuid"] or state["portals"] != 2 or state["players"] != 1:
                    raise RuntimeError(f"Crossing identity or boundary count changed: {state}")
                if state["dimension"] == target:
                    arrival = execute('Object a=System.getProperties().get("rocketmod.production.arrival"); '
                                      'if(a==null) throw new IllegalStateException("Missing natural crossing"); '
                                      'return a.toString();')
                    if not arrival["gliding"] or arrival["dimension"] != target:
                        raise RuntimeError(f"Elytra state or target lost: {arrival}")
                    client('''
net.minecraft.client.player.LocalPlayer local=c.player;
local.m_36321_(); local.m_150110_().f_35935_=true;
local.m_20242_(true); local.m_20256_(net.minecraft.world.phys.Vec3.ZERO);
reply.complete("client held after recorded crossing");
''')
                    execute(BASE + '''
p.stopFallFlying(); p.getAbilities().flying=true; p.onUpdateAbilities();
p.setNoGravity(true); p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO); p.hurtMarked=true;
return "server held after recorded crossing";
''')
                    pipeline = snapshot()
                    if pipeline["shaderError"] != "Optional.empty" or not pipeline["active"]:
                        raise RuntimeError(f"Oculus pipeline failed: {pipeline}")
                    report["legs"].append({"leg": leg, "arrival": arrival, "held": state,
                                           "pipeline": pipeline, "postEventFixtureHold": True})
                    capture(f"flight-leg-{leg + 1}", report, game)
                    print(f"PASS Oculus crossing {leg + 1}/4: {target}", flush=True)
                    break
                if time.monotonic() - began > 3 and state["status"].startswith("READY") and not boosted:
                    execute(BOOST)
                    boosted = True
                time.sleep(0.25)
            else:
                raise RuntimeError(f"Crossing timed out: {state}")
        fixture(114, 18000, -35)
        capture("flight-night-after-return", report, game)
        report["result"] = "PASS"
    except Exception as error:
        report.update(result="FAIL", error=str(error))
        raise
    finally:
        execute(BASE + '''
Object listener=System.getProperties().remove("rocketmod.production.arrivalListener");
if(listener!=null) net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(listener);
System.getProperties().remove("rocketmod.production.arrival");
p.stopFallFlying(); com.example.examplemod.transition.FlightTransferCoordinator.logout(p);
p.setNoGravity(false); p.getAbilities().flying=true; p.onUpdateAbilities();
p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO); p.hurtMarked=true;
return "flight fixture released";
''')
        client('net.minecraft.client.player.LocalPlayer local=c.player; local.m_20242_(false); '
               'c.options.hideGui=false; reply.complete("HUD restored");')
        report["cleanup"] = execute(STATE)
        output = ROOT / ".local-cache/validation/oculus-flight.json"
        output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(f"Report: {output}", flush=True)


if __name__ == "__main__":
    main()
