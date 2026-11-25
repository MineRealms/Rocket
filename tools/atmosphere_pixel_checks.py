"""Check sky brightness progression, cross-boundary pixels and produce review sheets."""
import json

from PIL import Image, ImageChops, ImageDraw, ImageStat

from flight_runtime_suite import ROOT


def main():
    folder = ROOT / ".local-cache/validation"
    captures = json.loads((folder / "p1-atmosphere.json").read_text(encoding="utf-8"))
    report = {"zenith": [], "boundaries": {}}
    for direction in ("up", "horizon", "down"):
        frames = [frame for frame in captures["frames"] if frame["direction"] == direction]
        sheet = Image.new("RGB", (960, 4 * 204), "#151719")
        draw = ImageDraw.Draw(sheet)
        for index, frame in enumerate(frames):
            with Image.open(ROOT / frame["file"]) as source:
                rgb = source.convert("RGB")
                if direction == "up":
                    roi = rgb.crop((rgb.width // 4, rgb.height // 2, rgb.width * 3 // 4, rgb.height * 3 // 4))
                    report["zenith"].append({"h": frame["altitude"], "mean": sum(ImageStat.Stat(roi).mean) / 3})
                rgb.thumbnail((320, 180))
                x, y = index % 3 * 320, index // 3 * 204
                sheet.paste(rgb, (x, y + 24))
                draw.text((x + 8, y + 6), f"H {frame['altitude']} {direction}", fill="white")
        sheet.save(folder / f"p1-atmosphere-{direction}.jpg", quality=92)
        before = next(frame for frame in frames if frame["altitude"] == 1599)
        after = next(frame for frame in frames if frame["altitude"] == 1601)
        with Image.open(ROOT / before["file"]) as a, Image.open(ROOT / after["file"]) as b:
            report["boundaries"][direction] = sum(ImageStat.Stat(ImageChops.difference(a.convert("RGB"), b.convert("RGB"))).mean) / 3
        if report["boundaries"][direction] > 8:
            raise RuntimeError(f"Visible boundary discontinuity in {direction}")
    low = next(frame["mean"] for frame in report["zenith"] if frame["h"] == 400)
    high = next(frame["mean"] for frame in report["zenith"] if frame["h"] == 1599)
    if high >= low * 0.25:
        raise RuntimeError("Sky does not become sufficiently darker")
    report["result"] = "PASS"
    (folder / "p1-atmosphere-pixels.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
