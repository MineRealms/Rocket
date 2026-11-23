"""Summarize real client frame continuity at natural dimension changes."""
import argparse
import json
from pathlib import Path
import statistics


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    frames = [json.loads(line) for line in args.trace.read_text(encoding="utf-8").splitlines() if line]
    crossings = []
    for before, after in zip(frames, frames[1:]):
        if before["dimension"] == after["dimension"]:
            continue
        crossings.append({"from": before["dimension"], "to": after["dimension"], "frame": after["frame"],
                          "beforeH": before["h"], "afterH": after["h"], "deltaH": after["h"]-before["h"],
                          "beforeVy": before["vy"], "afterVy": after["vy"],
                          "yawDelta": after["yaw"]-before["yaw"], "pitchDelta": after["pitch"]-before["pitch"],
                          "frameMs": after["frameMs"], "screen": after["screen"]})
    times = sorted(frame["frameMs"] for frame in frames)
    report = {"frames": len(frames), "crossings": crossings,
              "maxCrossingHeightStep": max((abs(x["deltaH"]) for x in crossings), default=0),
              "maxCrossingYawStep": max((abs(x["yawDelta"]) for x in crossings), default=0),
              "maxCrossingPitchStep": max((abs(x["pitchDelta"]) for x in crossings), default=0),
              "frameTimeMedianMs": statistics.median(times), "frameTimeP95Ms": times[int((len(times)-1)*0.95)],
              "loadingScreens": sorted({frame["screen"] for frame in frames if frame["screen"] != "none"})}
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
