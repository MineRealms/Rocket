"""Verify unmounted non-Elytra descent, below-boundary recovery, and void protection."""
import json
import time

from flight_runtime_suite import BASE, ROOT, execute

STATE = BASE + """
com.google.gson.JsonObject j = new com.google.gson.JsonObject();
j.addProperty("dimension", p.level().dimension().location().toString());
j.addProperty("y", p.getY()); j.addProperty("health", p.getHealth()); j.addProperty("alive", p.isAlive());
j.addProperty("gliding", p.isFallFlying());
j.addProperty("leases", com.example.examplemod.transition.FlightTransferCoordinator.leaseCount());
j.addProperty("status", com.example.examplemod.transition.FlightTransferCoordinator.status(p));
return j.toString();
"""
SETUP = BASE + """
p.stopFallFlying(); com.example.examplemod.transition.FlightTransferCoordinator.logout(p);
p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.item.ItemStack.EMPTY);
p.setHealth(p.getMaxHealth()); p.invulnerableTime = 0; p.setNoGravity(false);
p.getAbilities().flying = false; p.onUpdateAbilities();
net.minecraft.server.level.ServerLevel space = s.getLevel(com.example.examplemod.compat.immptl.ImmersivePortalsBackend.SPACE_DIMENSION);
qouteall.imm_ptl.core.api.PortalAPI.teleportEntity(p, space, new net.minecraft.world.phys.Vec3(p.getX(), @Y@, p.getZ()));
p.setDeltaMovement(new net.minecraft.world.phys.Vec3(0, -0.8, 0)); p.hurtMarked = true;
@EXTRA@
return "return fixture without Elytra, no command return";
"""


def main():
    report = {}
    try:
        for name, y, enabled in [("ordinaryFall", 120, True), ("missedPortal", 32, True),
                                 ("debugDisabled", 32, False), ("voidDamage", -80, False)]:
            execute(BASE + f"com.example.examplemod.Config.debugElytraSpaceFlight = {str(enabled).lower()}; "
                    "com.example.examplemod.compat.immptl.ImmersivePortalsBackend.reconcile(s); return \"config fixture\";")
            extra = ""
            if name == "voidDamage":
                extra = "if (p.hurt(p.damageSources().fellOutOfWorld(), 100f)) throw new IllegalStateException(\"Space void damage was not cancelled\");"
            execute(SETUP.replace("@Y@", str(y)).replace("@EXTRA@", extra))
            samples = []
            began = time.monotonic()
            while time.monotonic() - began < 15:
                state = execute(STATE)
                samples.append(state)
                if not state["alive"] or state["health"] < 20:
                    raise RuntimeError(f"Space return damaged or killed player: {name}: {state}")
                if state["dimension"] == "minecraft:overworld":
                    report[name] = {"result": "PASS", "samples": samples}
                    print(f"PASS {name}: alive, health={state['health']}, Overworld Y={state['y']:.2f}", flush=True)
                    execute(BASE + "p.setGameMode(net.minecraft.world.level.GameType.CREATIVE); "
                            "p.getAbilities().flying=true; p.onUpdateAbilities(); p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO); return \"safe fixture\";")
                    break
                time.sleep(0.25)
            else:
                raise RuntimeError(f"Space return timed out: {name}: {samples[-1]}")
        report["result"] = "PASS"
    except Exception as error:
        report["result"] = "FAIL"
        report["error"] = str(error)
        raise
    finally:
        execute(BASE + """
com.example.examplemod.Config.debugElytraSpaceFlight = true;
com.example.examplemod.compat.immptl.ImmersivePortalsBackend.reconcile(s);
p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
p.setNoGravity(false); p.getAbilities().flying=true; p.onUpdateAbilities();
net.minecraft.world.item.ItemStack elytra = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ELYTRA);
elytra.getOrCreateTag().putBoolean("Unbreakable", true);
p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, elytra);
return "restored creative mode, Elytra and debug flag";
""")
        report["cleanup"] = execute(STATE)
        output = ROOT / ".local-cache/validation/space-return.json"
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
