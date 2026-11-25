"""Validate the release jar on the isolated Forge server through the TCP delay fixture."""
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
j.addProperty("uuid", p.getUUID().toString());
j.addProperty("x", p.getX()); j.addProperty("y", p.getY()); j.addProperty("z", p.getZ());
j.addProperty("h", com.example.examplemod.transition.FlightTransferCoordinator.altitude(p));
j.addProperty("vy", p.getDeltaMovement().y); j.addProperty("gliding", p.isFallFlying());
j.addProperty("latency", p.latency);
j.addProperty("players", s.getPlayerList().getPlayerCount());
j.addProperty("leases", com.example.examplemod.transition.FlightTransferCoordinator.leaseCount());
j.addProperty("portals", com.example.examplemod.compat.immptl.ImmersivePortalsBackend.getOwnedPortalCount(s));
j.addProperty("status", com.example.examplemod.transition.FlightTransferCoordinator.status(p));
return j.toString();
"""
SETUP = BASE + """
p.stopFallFlying(); com.example.examplemod.transition.FlightTransferCoordinator.logout(p);
p.setNoGravity(false); p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
net.minecraft.world.item.ItemStack elytra = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ELYTRA);
elytra.getOrCreateTag().putBoolean("Unbreakable", true);
p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, elytra);
boolean up = p.level().dimension().equals(net.minecraft.world.level.Level.OVERWORLD);
net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> source = p.level().dimension();
java.util.UUID playerId = p.getUUID();
Object previous = System.getProperties().remove("rocketmod.production.arrivalListener");
if (previous != null) net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(previous);
System.getProperties().remove("rocketmod.production.arrival");
java.util.function.Consumer<net.minecraftforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent> arrival = event -> {
    if (!event.getEntity().m_20148_().equals(playerId) || !event.getFrom().equals(source)
            || event.getTo().equals(source)) return;
    com.google.gson.JsonObject j = new com.google.gson.JsonObject();
    j.addProperty("dimension", p.level().dimension().location().toString());
    j.addProperty("uuid", p.getUUID().toString());
    j.addProperty("h", com.example.examplemod.transition.FlightTransferCoordinator.altitude(p));
    j.addProperty("vy", p.getDeltaMovement().y); j.addProperty("gliding", p.isFallFlying());
    j.addProperty("latency", p.latency);
    System.getProperties().put("rocketmod.production.arrival", j.toString());
    // Hold only after the natural crossing is recorded; bridge compilation can otherwise
    // outlast the boost and allow an unobserved second crossing before the next leg.
    p.stopFallFlying(); p.getAbilities().flying = true; p.onUpdateAbilities();
    p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO); p.hurtMarked = true;
    p.connection.m_9829_(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(p));
};
net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.LOWEST,
    false, net.minecraftforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent.class, arrival);
System.getProperties().put("rocketmod.production.arrivalListener", arrival);
double boundary = up ? com.example.examplemod.Config.flightTransitionAltitude : 64;
p.connection.m_9774_(@X@.0, boundary + (up ? -40 : 40), 4096.0, 0.0F, up ? -85.0F : 85.0F);
p.getAbilities().flying = true; p.onUpdateAbilities();
p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO); p.hurtMarked = true;
return "same-dimension setup; no dimension API";
"""
BOOST = BASE + """
p.getAbilities().flying = false; p.onUpdateAbilities(); p.setOnGround(false); p.startFallFlying();
net.minecraft.world.item.ItemStack firework = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.FIREWORK_ROCKET);
firework.getOrCreateTagElement("Fireworks").putByte("Flight", (byte) 3);
p.serverLevel().addFreshEntity(new net.minecraft.world.entity.projectile.FireworkRocketEntity(p.level(), firework, p));
return "vanilla Elytra firework; no forced client velocity";
"""


def execute(code, client=False):
    side = "client" if client else "server"
    response = request("exec", {"code": code}, port=48792 if client else 48791,
                       token_file=ROOT / f".local-cache/production/{side}/RosettaRemoteDebugBridge/remote-token.txt")
    if not response.get("ok"):
        raise RuntimeError(json.dumps(response, ensure_ascii=False))
    result = response["result"]["result"].rsplit("  [", 1)[0]
    return json.loads(result) if result.startswith("{") else result


def main():
    report = {"started": datetime.now().isoformat(), "delayPerDirectionMs": [50, 100],
              "legs": [], "samples": []}
    output = ROOT / ".local-cache/validation/p0-production-latency.json"
    origin = execute(STATE)
    if origin["dimension"] != "minecraft:overworld" or origin["players"] != 1:
        raise RuntimeError("Requires one player in the isolated production test server Overworld")
    report["origin"] = origin
    try:
        execute("net.minecraft.client.Minecraft c = net.minecraft.client.Minecraft.getInstance(); "
                "c.execute(() -> { c.options.pauseOnLostFocus = false; c.setScreen(null); "
                "c.options.framerateLimit().set(60); }); return \"client ready\";", client=True)
        for leg in range(4):
            expected_source = "minecraft:overworld" if leg % 2 == 0 else "rocketmod:space"
            source = execute(STATE)
            if source["dimension"] != expected_source:
                raise RuntimeError(f"Release leg {leg} started in {source['dimension']}, expected {expected_source}")
            execute(SETUP.replace("@X@", str(4096 + (leg // 2) * 512)))
            time.sleep(1.5)
            execute(BOOST)
            began = time.monotonic()
            target = "rocketmod:space" if leg % 2 == 0 else "minecraft:overworld"
            boosted = False
            while time.monotonic() - began < 30:
                state = execute(STATE)
                report["samples"].append({"leg": leg, "elapsed": time.monotonic() - began, **state})
                if state["uuid"] != origin["uuid"] or state["portals"] != 2 or state["players"] != 1:
                    raise RuntimeError("Identity, player count, or portal count changed")
                if state["dimension"] == target:
                    arrival = execute('Object arrival = System.getProperties().get("rocketmod.production.arrival"); '
                                      'if (arrival == null) throw new IllegalStateException("Missing natural arrival event"); '
                                      'return arrival.toString();')
                    if arrival["dimension"] != target or arrival["uuid"] != origin["uuid"]:
                        raise RuntimeError("Natural arrival event does not match the target player")
                    if not arrival["gliding"]:
                        raise RuntimeError("Elytra state lost across release-jar crossing")
                    report["legs"].append({"leg": leg, "arrival": arrival, "postCrossingFixtureHold": True,
                                           "heldState": state})
                    print(f"PASS release crossing {leg+1}/4: {target}, RTT={arrival['latency']}ms", flush=True)
                    break
                if time.monotonic() - began > 3 and state["status"].startswith("READY") and not boosted:
                    execute(BOOST)
                    boosted = True
                time.sleep(0.25)
            else:
                raise RuntimeError(f"Release crossing {leg} timed out: {state}")
            time.sleep(0.75)
        report["result"] = "PASS"
    except Exception as error:
        report["result"] = "FAIL"
        report["error"] = str(error)
        raise
    finally:
        execute(BASE + """
Object listener = System.getProperties().remove("rocketmod.production.arrivalListener");
if (listener != null) net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(listener);
System.getProperties().remove("rocketmod.production.arrival");
p.stopFallFlying(); com.example.examplemod.transition.FlightTransferCoordinator.logout(p);
p.setNoGravity(false); p.getAbilities().flying = true; p.onUpdateAbilities();
p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO); p.hurtMarked = true;
return "production fixture cleanup";
""")
        report["cleanup"] = execute(STATE)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(f"Report: {output}", flush=True)


if __name__ == "__main__":
    main()
