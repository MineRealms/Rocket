"""Capture stationary altitude reference frames; dimension teleports are visual fixtures only."""
import json
import time

from flight_runtime_suite import BASE, ROOT, execute

VIEW = BASE + """
p.stopFallFlying(); com.example.examplemod.transition.FlightTransferCoordinator.logout(p);
boolean space = @H@ >= com.example.examplemod.Config.flightTransitionAltitude;
net.minecraft.server.level.ServerLevel target = space ? s.getLevel(com.example.examplemod.compat.immptl.ImmersivePortalsBackend.SPACE_DIMENSION) : s.overworld();
double y = @H@ - (space ? com.example.examplemod.Config.flightTransitionAltitude-64 : 0);
if (!p.level().dimension().equals(target.dimension()))
    qouteall.imm_ptl.core.api.PortalAPI.teleportEntity(p, target, new net.minecraft.world.phys.Vec3(p.getX(),y,p.getZ()));
p.connection.teleport(p.getX(), y, p.getZ(), 0, @PITCH@);
p.setNoGravity(true); p.getAbilities().flying=true; p.onUpdateAbilities();
p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO); p.hurtMarked=true;
s.overworld().setDayTime(6000);
net.minecraft.client.Minecraft c=net.minecraft.client.Minecraft.getInstance();
c.execute(() -> { c.setScreen(null); c.options.pauseOnLostFocus=false; c.options.hideGui=true;
    c.options.framerateLimit().set(60); c.options.fov().set(70); });
return "visual fixture H=@H@";
"""
CAPTURE = """
net.minecraft.client.Minecraft c=net.minecraft.client.Minecraft.getInstance();
c.execute(() -> net.minecraft.client.Screenshot.grab(c.gameDirectory,"rocket-H-@H@.png",c.getMainRenderTarget(),
    m -> org.slf4j.LoggerFactory.getLogger("ROCKET-Probe").info(m.getString())));
return "queued altitude reference frame";
"""


def main():
    output = ROOT / ".local-cache/validation/p1-visual-frames.json"
    frames = []
    try:
        for altitude in [80, 180, 320, 450, 511, 513, 900, 1500, 2000, 2384, 4000]:
            execute(VIEW.replace("@H@", str(altitude)).replace("@PITCH@", "90" if altitude >= 900 else "60"))
            time.sleep(2)
            metadata = execute((ROOT / "tools/probes/client_state.java").read_text(encoding="utf-8"))
            execute(CAPTURE.replace("@H@", str(altitude)))
            frames.append({"fixtureH": altitude, "client": metadata,
                           "file": f"forge-1.20.1-47.4.29-mdk/run/screenshots/rocket-H-{altitude}.png"})
            print(f"CAPTURE H={altitude}: {metadata}", flush=True)
    finally:
        execute("net.minecraft.client.Minecraft.getInstance().execute(() -> net.minecraft.client.Minecraft.getInstance().options.hideGui=false); return \"HUD restored\";")
        execute(BASE + "p.setNoGravity(false); p.stopFallFlying(); "
                "p.getAbilities().flying=true; p.onUpdateAbilities(); return \"visual fixture restored gravity\";")
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(frames, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
