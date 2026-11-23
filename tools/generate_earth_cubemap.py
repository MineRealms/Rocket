"""Convert the credited Earth map into six nearest-filtered Minecraft cube faces."""
import argparse
import math
from pathlib import Path

from PIL import Image


def direction(face, u, v):
    x, y = u * 2 - 1, 1 - v * 2
    return {"top": (x, 1, -y), "bottom": (x, -1, y),
            "north": (x, y, -1), "south": (-x, y, 1),
            "east": (1, y, x), "west": (-1, y, -x)}[face]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    args = parser.parse_args()
    source = Image.open(args.source).convert("RGB")
    output = Path(__file__).resolve().parents[1] / "forge-1.20.1-47.4.29-mdk/src/main/resources/assets/rocketmod/textures/environment"
    output.mkdir(parents=True, exist_ok=True)
    size = 128
    for face in ("top", "bottom", "north", "south", "east", "west"):
        result = Image.new("RGB", (size, size))
        for py in range(size):
            for px in range(size):
                dx, dy, dz = direction(face, (px + 0.5) / size, (py + 0.5) / size)
                # Put recognizable equatorial land on the cube's top face.
                ex, ey, ez = dy, -dz, dx
                longitude = math.atan2(ez, ex)
                latitude = math.atan2(ey, math.hypot(ex, ez))
                sx = int((longitude / math.tau + 0.5) * source.width) % source.width
                sy = min(source.height - 1, max(0, int((0.5 - latitude / math.pi) * source.height)))
                result.putpixel((px, py), source.getpixel((sx, sy)))
        result.save(output / f"earth_{face}.png", optimize=True)
        print(face, result.size)


if __name__ == "__main__":
    main()
