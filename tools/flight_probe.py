"""Local Rosetta bridge client for repeatable ROCKET runtime checks."""
import argparse
import json
from pathlib import Path
import socket

ROOT = Path(__file__).resolve().parents[1]
TOKEN = ROOT / "forge-1.20.1-47.4.29-mdk/run/RosettaRemoteDebugBridge/remote-token.txt"
STATE = """
net.minecraft.server.MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
java.util.List<String> states = new java.util.ArrayList<>();
for (net.minecraft.server.level.ServerPlayer p : server.getPlayerList().getPlayers()) {
    states.add(p.getGameProfile().getName() + " dimension=" + p.level().dimension().location()
        + " position=" + p.position() + " velocity=" + p.getDeltaMovement()
        + " gliding=" + p.isFallFlying() + " yaw=" + p.getYRot() + " pitch=" + p.getXRot()
        + " altitude=" + com.example.examplemod.transition.FlightTransferCoordinator.altitude(p)
        + " transfer=" + com.example.examplemod.transition.FlightTransferCoordinator.status(p));
}
return "debug=" + com.example.examplemod.Config.debugElytraSpaceFlight
    + " portals=" + com.example.examplemod.compat.immptl.ImmersivePortalsBackend.getOwnedPortalCount(server)
    + " leases=" + com.example.examplemod.compat.immptl.ImmersivePortalsBackend.getChunkLeaseCount()
    + " players=" + states;
"""


def request(command, args, port=48790, token_file=TOKEN):
    payload = {"token": Path(token_file).read_text(encoding="utf-8").strip(), "cmd": command, "args": args}
    with socket.create_connection(("127.0.0.1", port), timeout=10) as connection:
        connection.settimeout(65)
        connection.sendall((json.dumps(payload) + "\n").encode("utf-8"))
        with connection.makefile("rb") as stream:
            return json.loads(stream.readline(64 * 1024 * 1024))


def call(command, args, port, token_file=TOKEN):
    response = request(command, args, port, token_file)
    print(json.dumps(response, ensure_ascii=False, indent=2))
    return 0 if response.get("ok") else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["ping", "state", "console", "exec"])
    parser.add_argument("value", nargs="?")
    parser.add_argument("--port", type=int, default=48790)
    parser.add_argument("--token-file", type=Path, default=TOKEN)
    arguments = parser.parse_args()
    command = arguments.command
    payload = {}
    if command == "state":
        command, payload = "exec", {"code": STATE}
    elif command == "console":
        if not arguments.value:
            parser.error("console requires a command")
        payload = {"command": arguments.value}
    elif command == "exec":
        if not arguments.value:
            parser.error("exec requires a Java method-body file")
        payload = {"code": Path(arguments.value).read_text(encoding="utf-8")}
    raise SystemExit(call(command, payload, arguments.port, arguments.token_file))
