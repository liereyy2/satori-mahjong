"""Measure the circle and question-mark ink centers in a native Minecraft screenshot."""
from __future__ import annotations

import argparse
import json
from collections import deque
from pathlib import Path

from PIL import Image


def bounds(points: set[tuple[int, int]]) -> tuple[int, int, int, int]:
    return min(x for x, _ in points), min(y for _, y in points), max(x for x, _ in points) + 1, max(y for _, y in points) + 1


def center(box: tuple[int, int, int, int]) -> tuple[float, float]:
    return (box[0] + box[2]) / 2, (box[1] + box[3]) / 2


def verify(path: Path, scale: int) -> dict:
    with Image.open(path) as source:
        image = source.convert('RGB')
    left, top = image.width - 35 * scale, 8 * scale
    colors = {(243, 235, 220), (117, 230, 208)}
    ink = {(x, y) for x in range(24 * scale) for y in range(24 * scale)
           if image.getpixel((left + x, top + y)) in colors}
    components = []
    while ink:
        seed = ink.pop()
        found, queue = {seed}, deque([seed])
        while queue:
            x, y = queue.popleft()
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    p = x + dx, y + dy
                    if p in ink:
                        ink.remove(p)
                        found.add(p)
                        queue.append(p)
        components.append(found)
    assert components, 'Help icon ink not found; use the native table entry capture.'
    ring = max(components, key=len)
    ring_box = bounds(ring)
    cx, cy = center(ring_box)
    radius = min(ring_box[2] - ring_box[0], ring_box[3] - ring_box[1]) * .375
    glyph = {p for component in components if component is not ring for p in component
             if (p[0] + .5 - cx) ** 2 + (p[1] + .5 - cy) ** 2 < radius ** 2}
    assert glyph, 'Question-mark ink missing.'
    glyph_box = bounds(glyph)
    gx, gy = center(glyph_box)
    assert (gx, gy) == (cx, cy), {'circle': ring_box, 'question': glyph_box}
    assert (glyph_box[2] - glyph_box[0], glyph_box[3] - glyph_box[1]) == (7 * scale, 11 * scale)
    return {'screenshot': str(path), 'circle_bounds': ring_box, 'question_bounds': glyph_box,
            'center': (cx, cy), 'center_error_px': (gx - cx, gy - cy)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('screenshot', type=Path)
    parser.add_argument('--gui-scale', type=int, default=2)
    args = parser.parse_args()
    print(json.dumps(verify(args.screenshot, args.gui_scale), indent=2))


if __name__ == '__main__':
    main()
