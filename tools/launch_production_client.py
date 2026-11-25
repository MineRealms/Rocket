"""Prepare or launch the installed Forge production client with an offline test user."""

import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import urllib.request
import urllib.parse
import uuid
import zipfile


ROOT = Path(__file__).resolve().parents[1]
PROFILE = "1.20.1-forge-47.4.29"
# SHA512 pins verified against api.modrinth.com/v2/version/{version_id}.
RENDERER_MODS = (
    ("sk9rgfiA", "UTbfe5d1", "embeddium-0.3.31+mc1.20.1.jar",
     "ffbf2da4685260a4d5c14c621708bd20722563f084f042d3dfb0a7b87f048e39299648c854a93939129da0d23a15a91ec628560d601e76074b08e275f6e132e9"),
    ("GchcoXML", "iQ1SwGc3", "oculus-mc1.20.1-1.8.0.jar",
     "1bb4ac77400d6684347988ed298a692c2cb15cf7923693607eb8739b171a20fef7412259e9e157111d9ce21779badab386029956f7d2283a9e611722a373e9d5"),
)


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def digest(path, algorithm="sha1"):
    checksum = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            checksum.update(block)
    return checksum.hexdigest()


def permitted(entry, features):
    allowed = not entry.get("rules")
    for rule in entry.get("rules", []):
        system = rule.get("os", {})
        matches = system.get("name", "windows") == "windows"
        if "arch" in system:
            matches &= bool(re.fullmatch(system["arch"], "amd64"))
        if "version" in system:
            matches &= bool(re.search(system["version"], platform.version()))
        matches &= all(features.get(key, False) == value
                       for key, value in rule.get("features", {}).items())
        if matches:
            allowed = rule["action"] == "allow"
    return allowed


def ensure(artifact, destination, opener, alternatives=()):
    algorithm = "sha512" if artifact.get("sha512") else "sha1"
    expected = artifact.get(algorithm)
    size = artifact.get("size")

    def valid(path):
        return (path.is_file() and (size is None or path.stat().st_size == size)
                and (not expected or digest(path, algorithm) == expected))

    if valid(destination):
        return destination
    destination.parent.mkdir(parents=True, exist_ok=True)
    for candidate in alternatives:
        if valid(candidate):
            shutil.copy2(candidate, destination)
            return destination
    url = artifact.get("url")
    if not url or not expected:
        raise RuntimeError(f"Missing installed/generated artifact: {destination}")
    if not url.startswith("https://"):
        raise RuntimeError(f"Artifact URL must use HTTPS: {url}")
    temporary = destination.with_suffix(destination.suffix + ".download")
    try:
        with opener.open(url, timeout=90) as source, temporary.open("wb") as target:
            shutil.copyfileobj(source, target)
        if not valid(temporary):
            raise RuntimeError(f"Checksum/size mismatch: {url}")
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)
    return destination


def extract_natives(archive, destination, excluded=()):
    with zipfile.ZipFile(archive) as jar:
        for member in jar.infolist():
            relative = Path(member.filename)
            if member.is_dir() or relative.suffix.lower() != ".dll":
                continue
            if any(member.filename.startswith(prefix) for prefix in excluded):
                continue
            target = (destination / relative).resolve()
            if not target.is_relative_to(destination.resolve()):
                raise RuntimeError(f"Unsafe native archive path: {member.filename}")
            target.parent.mkdir(parents=True, exist_ok=True)
            with jar.open(member) as source, target.open("wb") as output:
                shutil.copyfileobj(source, output)


def stage_mods(game):
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    sources = [ROOT / "forge-1.20.1-47.4.29-mdk/build/libs/rocketmod-1.0.0.jar",
               ROOT.parent / "RainJava-work/RosettaRemoteDebugBridge/build/libs/rosetta_remote_debug_bridge-1.0.0.jar"]
    for project, version in [("zf4Szzx2", "Q6Z7eOLL"), ("9s6osm5g", "t8TXrZvZ")]:
        matches = sorted((cache / "caches/modules-2/files-2.1/maven.modrinth" / project / version).glob("*/*.jar"))
        if len(matches) != 1:
            raise RuntimeError(f"Expected one original dependency jar for {project}:{version}: {matches}")
        sources.extend(matches)
    mods = game / "mods"
    mods.mkdir(exist_ok=True)
    record = game / "staged-mods.json"
    previous = read_json(record) if record.exists() else {}
    staged = {}
    for source in sources:
        if not source.is_file():
            raise RuntimeError(f"Build required mod first: {source}")
        target = mods / source.name
        checksum = digest(source)
        if target.exists() and digest(target) != checksum:
            if previous.get(target.name) != digest(target):
                raise RuntimeError(f"Refusing to overwrite unmanaged mod: {target}")
        if not target.exists() or digest(target) != checksum:
            shutil.copy2(source, target)
        staged[target.name] = checksum
    record.write_text(json.dumps(staged, indent=2) + "\n", encoding="utf-8")
    return staged


def stage_oculus(game, opener):
    from install_rocket_shader import DEFAULT_SOURCE, DEFAULT_DEST

    if not (DEFAULT_SOURCE / "shaders/lib/common.glsl").is_file():
        original = ROOT.parent / "AssimpLoader/build/character-sync-owner2/shaderpacks/RocketFlow_Local"
        if not (original / "shaders/lib/common.glsl").is_file():
            raise RuntimeError(f"RocketFlow source missing: {original}; local copy missing: {DEFAULT_SOURCE}")
        if DEFAULT_SOURCE.exists():
            raise RuntimeError(f"Incomplete local RocketFlow copy: {DEFAULT_SOURCE}")
        shutil.copytree(original, DEFAULT_SOURCE)
    mods = game / "mods"
    mods.mkdir(parents=True, exist_ok=True)
    expected_names = {entry[2] for entry in RENDERER_MODS}
    conflicts = [path.name for path in mods.glob("*.jar")
                 if path.name.lower().startswith(("oculus", "embeddium", "rubidium"))
                 and path.name not in expected_names]
    if conflicts:
        raise RuntimeError(f"Conflicting renderer jars in {mods}: {conflicts}")
    staged = {}
    for project, version, filename, checksum in RENDERER_MODS:
        artifact = {"sha512": checksum,
                    "url": f"https://cdn.modrinth.com/data/{project}/versions/{version}/"
                           + urllib.parse.quote(filename)}
        cached = ROOT / ".local-cache/renderer-mods" / filename
        ensure(artifact, cached, opener)
        target = mods / filename
        ensure(artifact, target, opener, [cached])
        staged[filename] = checksum
    subprocess.run([sys.executable, str(ROOT / "tools/install_rocket_shader.py"),
                    "--source", str(DEFAULT_SOURCE), "--destination", str(DEFAULT_DEST)], check=True)
    config = game / "config/oculus.properties"
    config.parent.mkdir(parents=True, exist_ok=True)
    lines = config.read_text(encoding="utf-8").splitlines() if config.exists() else []
    settings = {"shaderPack": "RocketFlow_ROCKET", "enableShaders": "true"}
    lines = [line for line in lines if line.partition("=")[0].strip() not in settings]
    lines.extend(f"{key}={value}" for key, value in settings.items())
    config.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return staged


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--stage-mods", action="store_true")
    parser.add_argument("--oculus", action="store_true",
                        help="Install pinned Oculus 1.8.0/Embeddium 0.3.31 and enable local RocketFlow_ROCKET")
    parser.add_argument("--validation-bridge", action="store_true", help="Enable a loopback Rosetta client probe on port 48792")
    parser.add_argument("--java", type=Path, default=Path(r"C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot\bin\java.exe"))
    parser.add_argument("--proxy", help="Optional HTTP proxy, e.g. http://127.0.0.1:7890")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--server", help="Quick-play multiplayer address, e.g. 127.0.0.1:25572")
    mode.add_argument("--world", help="Existing save folder name for quick-play singleplayer")
    parser.add_argument("--jvm-arg", action="append", default=[])
    args = parser.parse_args()
    if os.name != "nt":
        parser.error("This launcher resolves Windows x64 libraries and requires Windows.")
    java_version = subprocess.run([str(args.java), "-version"], capture_output=True, text=True, check=True)
    if not re.search(r'version "17[.]', java_version.stderr):
        parser.error("Forge 1.20.1 requires Java 17.")
    game = ROOT / ".local-cache/production/client"
    libraries = game / "libraries"
    natives = game / "natives"
    natives.mkdir(exist_ok=True)
    vanilla_cache = ROOT / ".local-cache/forge-tool-cache/minecraft_tasks/1.20.1"
    parent = read_json(vanilla_cache / "version.json")
    profile = read_json(game / f"versions/{PROFILE}/{PROFILE}.json")
    if profile.get("inheritsFrom") != parent["id"]:
        raise RuntimeError("Forge profile does not inherit the cached vanilla version")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler(
        {"http": args.proxy, "https": args.proxy} if args.proxy else {}))
    features = {"has_custom_resolution": True, "has_quick_plays_support": True,
                "is_quick_play_multiplayer": bool(args.server),
                "is_quick_play_singleplayer": bool(args.world)}
    # Forge coordinates replace inherited versions of the same artifact/classifier.
    selected = {}
    for library in parent["libraries"] + profile["libraries"]:
        if permitted(library, features):
            parts = library["name"].split(":")
            selected[tuple(parts[:2] + parts[3:])] = library
    artifacts = []
    native_archives = []
    for library in selected.values():
        downloads = library.get("downloads", {})
        artifact = downloads.get("artifact")
        if artifact:
            # Mojang lists all Windows architectures under the same OS rule.
            name = library["name"]
            if "natives-windows-" in name:
                continue
            path = libraries / artifact["path"]
            artifacts.append((artifact, path))
            if "natives-windows" in name:
                native_archives.append((path, library.get("extract", {}).get("exclude", [])))
        classifier = library.get("natives", {}).get("windows")
        if classifier:
            artifact = downloads["classifiers"][classifier.replace("${arch}", "64")]
            path = libraries / artifact["path"]
            artifacts.append((artifact, path))
            native_archives.append((path, library.get("extract", {}).get("exclude", [])))
    server_libraries = ROOT / ".local-cache/production/server/libraries"
    vanilla_libraries = Path(os.environ["APPDATA"]) / ".minecraft/libraries"
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        futures = [pool.submit(ensure, artifact, path, opener,
                               [server_libraries / path.relative_to(libraries),
                                vanilla_libraries / path.relative_to(libraries)])
                   for artifact, path in artifacts]
        for future in futures:
            future.result()
    for archive, excluded in native_archives:
        extract_natives(archive, natives, excluded)
    client = game / "versions/1.20.1/1.20.1.jar"
    ensure(parent["downloads"]["client"], client, opener, [vanilla_cache / "client.jar"])
    assets = ROOT / ".local-cache/minecraft-assets"
    index = assets / f'indexes/{parent["assetIndex"]["id"]}.json'
    ensure(parent["assetIndex"], index, opener)
    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
        futures = []
        for asset in read_json(index)["objects"].values():
            checksum = asset["hash"]
            info = {"sha1": checksum, "size": asset["size"],
                    "url": f"https://resources.download.minecraft.net/{checksum[:2]}/{checksum}"}
            futures.append(pool.submit(ensure, info, assets / f"objects/{checksum[:2]}/{checksum}", opener))
        for future in futures:
            future.result()
    staged = stage_mods(game) if args.stage_mods else {}
    if args.oculus:
        staged.update(stage_oculus(game, opener))
    if args.validation_bridge:
        bootstrap = game / "RosettaRemoteDebugBridge/client/RocketValidationBridge.java"
        bootstrap.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / "tools/probes/RocketValidationBridge.java", bootstrap)
        args.jvm_arg.extend(["-Drocket.validation.bridge=true", "-Drosetta.remote.port=48792"])
    username = "RocketTest"
    offline_uuid = uuid.UUID(bytes=hashlib.md5(f"OfflinePlayer:{username}".encode()).digest(), version=3)
    variables = {"auth_player_name": username, "version_name": PROFILE,
                 "game_directory": str(game), "assets_root": str(assets),
                 "assets_index_name": parent["assetIndex"]["id"],
                 "auth_uuid": offline_uuid.hex, "auth_access_token": "0",
                 "clientid": "", "auth_xuid": "", "user_type": "legacy",
                 "version_type": "release", "natives_directory": str(natives),
                 "launcher_name": "rocket-production-validation", "launcher_version": "1",
                 "library_directory": str(libraries), "classpath_separator": ";",
                 # CommonClientLaunchHandler locates SRG/extra jars and merges them
                 # with ForgeClientLaunchHandler's patched client jar as minecraft.
                 # Adding them here creates a second automatic module named client.
                 "classpath": ";".join(str(path) for _, path in artifacts),
                 "resolution_width": "1280", "resolution_height": "720",
                 "quickPlayPath": str(game / "quickPlay.json"),
                 "quickPlayMultiplayer": args.server or "", "quickPlaySingleplayer": args.world or ""}

    def expand(value):
        return re.sub(r"\$\{([^}]+)\}", lambda match: variables[match[1]], value)

    def arguments(kind):
        result = []
        for entry in parent["arguments"][kind] + profile["arguments"].get(kind, []):
            if isinstance(entry, dict):
                if not permitted(entry, features):
                    continue
                entry = entry["value"]
            result.extend(expand(item) for item in (entry if isinstance(entry, list) else [entry]))
        return result

    jvm_args = ["-Xmx4G"] + args.jvm_arg + arguments("jvm")
    game_args = arguments("game")
    all_args = jvm_args + [profile["mainClass"]] + game_args
    # Java argument files avoid Windows' command-line length limit.
    argument_file = game / "production-client.args"
    argument_file.write_text("\n".join('"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'
                                       for value in all_args) + "\n", encoding="utf-8")
    command = [str(args.java), "@" + str(argument_file)]
    record = {"command": command, "cwd": str(game), "profile": PROFILE,
              "mainClass": profile["mainClass"], "jvmArgs": jvm_args,
              "gameArgs": game_args, "stagedMods": staged,
              "libraryCount": len(artifacts), "nativeArchives": len(native_archives)}
    if not args.prepare_only:
        log = game / "production-client-console.log"
        with log.open("ab", buffering=0) as output:
            process = subprocess.Popen(command, cwd=game, stdout=output, stderr=subprocess.STDOUT,
                                       creationflags=subprocess.CREATE_NEW_PROCESS_GROUP)
        record.update(pid=process.pid, consoleLog=str(log))
    (game / "production-launch.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in record.items() if key not in ("jvmArgs", "gameArgs")}, indent=2))


if __name__ == "__main__":
    main()
