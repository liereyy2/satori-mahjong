"""Verify native rendered indicator pixels, including side/far lamps hidden by old quad sorting."""
import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image


def verify(directory: Path, prefix: str) -> list[dict]:
    results = []
    for seat, name in [(1, "electric-opponent-indicator"), (2, "electric-indicator-2"), (3, "electric-indicator-3")]:
        files = [max(directory.glob(prefix + name + "-" + phase + ".png"), key=lambda p: p.stat().st_mtime)
                 for phase in ["on", "off"]]
        on, off = [np.asarray(Image.open(p).convert("RGB")) for p in files]
        assert on.shape == off.shape
        height, width, _ = on.shape
        # Bound the real blue center panel, independent of native window resolution.
        region = np.zeros((height, width), dtype=bool)
        region[int(height * .15):int(height * .55), int(width * .35):int(width * .65)] = True
        blue = region & (on[:, :, 0] == 13) & (on[:, :, 1] == 26) & (on[:, :, 2] == 48)
        y, x = np.where(blue)
        assert len(x) > 50, "Missing native center panel"
        left, right, top, bottom = int(x.min()) - 12, int(x.max()) + 12, int(y.min()) - 12, int(y.max()) + 12
        masks = []
        for pixels in [on, off]:
            part = pixels[top:bottom + 1, left:right + 1]
            masks.append((part[:, :, 0] > 230) & (part[:, :, 1] > 170)
                         & (part[:, :, 1] < 230) & (part[:, :, 2] < 115))
        lit = masks[0] & ~masks[1]
        y, x = np.where(lit)
        assert len(x) >= 8, f"Seat {seat}: render state changed but no visible lamp pixels"
        cx, cy = (right - left) / 2, (bottom - top) / 2
        assert {1: x.mean() > cx, 2: y.mean() < cy, 3: x.mean() < cx}[seat], f"Seat {seat}: wrong side lit"
        results.append({"seat": seat, "visible_changed_pixels": len(x), "on": str(files[0]), "off": str(files[1])})
    return results


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    parser.add_argument("--prefix", default="*_")
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    report = {"passed": True, "lamps": verify(args.directory, args.prefix)}
    args.report.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report))
