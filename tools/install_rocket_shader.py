"""Install the local ROCKET extension into a separately copied RocketFlow pack."""
import argparse
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE = ROOT / ".local-cache/shaderpacks/RocketFlow_ROCKET"
DEFAULT_DEST = ROOT / ".local-cache/production/client/shaderpacks/RocketFlow_ROCKET"
MARKER = "// ROCKET_SHADER_EXTENSION"


def replace_once(text, anchor, replacement, label):
    if text.count(anchor) != 1:
        raise ValueError(f"Unsupported RocketFlow layout: {label} anchor occurs {text.count(anchor)} times")
    return text.replace(anchor, replacement, 1)


def patches(pack):
    common_path = pack / "shaders/lib/common.glsl"
    deferred_path = pack / "shaders/program/deferred1.glsl"
    composite_path = pack / "shaders/program/composite5.glsl"
    common = common_path.read_text(encoding="utf-8")
    deferred = deferred_path.read_text(encoding="utf-8")
    composite = composite_path.read_text(encoding="utf-8")
    if MARKER not in common:
        anchor = '    #include "/lib/uniforms.glsl"'
        common = replace_once(common, anchor, anchor + "\n" + MARKER + "\n"
            "    uniform int rocketWorldTime;\n    uniform int rocketMoonPhase;\n"
            "    #ifdef OVERWORLD\n        #define moonPhase rocketMoonPhase\n    #endif", "uniforms")
        anchor = "    #if SHADOW_QUALITY == -1\n      float timeAngle = worldTime / 24000.0;"
        common = replace_once(common, anchor,
            "    #if defined OVERWORLD\n      float timeAngle = float(rocketWorldTime) / 24000.0;\n"
            "    #elif SHADOW_QUALITY == -1\n      float timeAngle = worldTime / 24000.0;", "Overworld clock")
    if MARKER not in deferred:
        deferred = replace_once(deferred, "//Program//\nvoid main() {\n    vec3 color =", MARKER + "\n"
            '#ifdef OVERWORLD\n#include "/lib/rocket/rocket_atmosphere.glsl"\n#endif\n\n'
            "//Program//\nvoid main() {\n    vec3 color =", "fragment entry")
        anchor = "            color = mix(color, clouds.rgb, clouds.a);"
        deferred = replace_once(deferred, anchor,
            "            #ifdef OVERWORLD\n"
            "                if (isEyeInWater == 0) clouds.a *= 1.0 - smoothstep(180.0, 800.0, rocketAltitude);\n"
            "            #endif\n" + anchor, "cloud fade")
        anchor = "    /*DRAWBUFFERS:054*/"
        deferred = replace_once(deferred, anchor,
            "    #ifdef OVERWORLD\n"
            "        if (z0 >= 0.99999 && skyFade >= 0.99999 && isEyeInWater == 0) {\n"
            "            vec3 rocketRay = normalize(mat3(gbufferModelViewInverse) * nViewPos);\n"
            "            vec3 rocketSun = normalize(mat3(gbufferModelViewInverse) * sunVec);\n"
            "            color = rocketAtmosphere(color, rocketRay, rocketSun);\n"
            "        }\n    #endif\n\n" + anchor, "final sky composite")
    deferred = deferred.replace(
        "                clouds.a *= 1.0 - smoothstep(180.0, 800.0, rocketAltitude);",
        "                if (isEyeInWater == 0) clouds.a *= 1.0 - smoothstep(180.0, 800.0, rocketAltitude);")
    if "uniform float rocketAltitude;" not in common:
        common = replace_once(common, "    uniform int rocketWorldTime;",
                              "    uniform float rocketAltitude;\n    uniform int rocketWorldTime;", "altitude uniform")
    if "uniform int rocketSpacePass;" not in common:
        common = replace_once(common, "    uniform int rocketMoonPhase;",
                              "    uniform int rocketMoonPhase;\n    uniform int rocketSpacePass;", "space-pass uniform")
    deferred = deferred.replace('#ifdef OVERWORLD\n#include "/lib/rocket/rocket_atmosphere.glsl"\n#endif\n\n', "")
    for ray, sun in [("normalize(mat3(gbufferModelViewInverse) * nViewPos)",
                      "normalize(mat3(gbufferModelViewInverse) * sunVec)"),
                     ("rocketViewRay(texCoord)", "rocketSunVector()")]:
        deferred = deferred.replace(
            "    #ifdef OVERWORLD\n"
            "        if (z0 >= 0.99999 && skyFade >= 0.99999 && isEyeInWater == 0) {\n"
            f"            vec3 rocketRay = {ray};\n            vec3 rocketSun = {sun};\n"
            "            color = rocketAtmosphere(color, rocketRay, rocketSun);\n"
            "        }\n    #endif\n\n", "")
    composite = composite.replace('#ifdef OVERWORLD\n#include "/lib/rocket/rocket_atmosphere.glsl"\n#endif\n\n',
                                 '#include "/lib/rocket/rocket_atmosphere.glsl"\n\n')
    if MARKER not in composite:
        composite = replace_once(composite, "//Program//\nvoid main() {\n    vec3 color = texture2D(colortex0, texCoord).rgb;", MARKER + "\n"
            '#include "/lib/rocket/rocket_atmosphere.glsl"\n\n'
            "//Program//\nvoid main() {\n    vec3 color = texture2D(colortex0, texCoord).rgb;", "late composite entry")
        composite = replace_once(composite, "    DoBSLTonemap(color);",
            "    if (texture2D(depthtex0, texCoord).r >= 0.999999 && isEyeInWater == 0) {\n"
            "        color = rocketAtmosphere(color, rocketViewRay(texCoord), rocketSunVector());\n"
            "    }\n\n    DoBSLTonemap(color);", "late atmosphere composite")
    composite = composite.replace(
        "    #ifdef OVERWORLD\n        if (texture2D(depthtex0, texCoord).r >= 0.999999 && isEyeInWater == 0) {\n"
        "            color = rocketAtmosphere(color, rocketViewRay(texCoord), rocketSunVector());\n"
        "        }\n    #endif\n\n    DoBSLTonemap(color);",
        "    if (texture2D(depthtex0, texCoord).r >= 0.999999 && isEyeInWater == 0) {\n"
        "        color = rocketAtmosphere(color, rocketViewRay(texCoord), rocketSunVector());\n"
        "    }\n\n    DoBSLTonemap(color);")
    return {common_path: common, deferred_path: deferred, composite_path: composite}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--destination", type=Path, default=DEFAULT_DEST)
    args = parser.parse_args()
    pack, destination = args.source.resolve(), args.destination.resolve()
    cache = (ROOT / ".local-cache").resolve()
    if not pack.is_relative_to(cache) or not destination.is_relative_to(cache):
        parser.error("Source and destination must be local .local-cache copies; the original pack is never patched")
    changes = patches(pack)
    for path, contents in changes.items():
        path.write_text(contents, encoding="utf-8", newline="\n")
    overrides = ROOT / "shaderpack-extension/overrides"
    shutil.copytree(overrides, pack, dirs_exist_ok=True)
    if pack != destination:
        shutil.copytree(pack, destination, dirs_exist_ok=True)
    print(f"Installed ROCKET atmosphere: {destination}")
    for path in changes:
        print(path.relative_to(pack))
    print("shaders/lib/rocket/rocket_atmosphere.glsl")


if __name__ == "__main__":
    main()
