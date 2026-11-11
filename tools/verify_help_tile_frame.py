"""Check visible tile-to-frame spacing in native rulebook screenshots."""
from __future__ import annotations

import argparse
import json
from collections import deque
from pathlib import Path

from PIL import Image

FRAME = (119, 224, 200)
BODY = (244, 238, 219)


def bounds(points: set[tuple[int, int]]) -> tuple[int, int, int, int]:
    return (
        min(x for x, _ in points), min(y for _, y in points),
        max(x for x, _ in points) + 1, max(y for _, y in points) + 1,
    )


def verify(path: Path) -> dict:
    with Image.open(path) as source:
        image = source.convert('RGB')
    pixels = image.load()
    ink = {(x, y) for y in range(image.height) for x in range(image.width)
           if pixels[x, y] == FRAME}
    frames = []
    while ink:
        seed = ink.pop()
        component, queue = {seed}, deque([seed])
        while queue:
            x, y = queue.popleft()
            for point in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                if point in ink:
                    ink.remove(point)
                    component.add(point)
                    queue.append(point)
        left, top, right, bottom = bounds(component)
        width, height = right - left, bottom - top
        # Selected tabs share this ink; tile frames are upright or quarter-turned.
        if min(width, height) < 10 or max(width, height) > 2 * min(width, height):
            continue
        assert all(pixels[x, top] == FRAME and pixels[x, bottom - 1] == FRAME
                   for x in range(left, right)), 'Clipped horizontal frame edge'
        assert all(pixels[left, y] == FRAME and pixels[right - 1, y] == FRAME
                   for y in range(top, bottom)), 'Clipped vertical frame edge'
        body = {(x, y) for y in range(top, bottom) for x in range(left, right)
                if pixels[x, y] == BODY}
        assert body, 'Visible ivory tile body not found inside the frame'
        body_left, body_top, body_right, body_bottom = bounds(body)
        margins = {'left': body_left - left, 'right': right - body_right,
                   'top': body_top - top, 'bottom': bottom - body_bottom}
        assert margins['left'] == margins['right'], f'{path}: {margins}'
        assert margins['top'] == margins['bottom'], f'{path}: {margins}'
        assert min(margins.values()) > 0, f'{path}: frame touches the tile body'
        frames.append({'frame': (left, top, right, bottom),
                       'body': (body_left, body_top, body_right, body_bottom),
                       'margins_px': margins})
    assert frames, f'{path}: winning-tile highlight not found'
    return {'screenshot': str(path), 'frames': sorted(frames, key=lambda f: f['frame'])}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('screenshots', type=Path, nargs='+')
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    report = {'passed': True, 'screenshots': [verify(path) for path in args.screenshots]}
    text = json.dumps(report, ensure_ascii=False, indent=2)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(text + '\n')
    print(text)


if __name__ == '__main__':
    main()
