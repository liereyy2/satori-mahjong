"""Reproducible native furniture materials and models. Tile faces use generate_tile_textures.mjs."""
from pathlib import Path
import json
import math
import random
from PIL import Image

ROOT = Path(__file__).resolve().parents[1] / "src/main/resources/assets/satori_mahjong"
TEXTURES = ROOT / "textures/block/furniture"
TEXTURES.mkdir(parents=True, exist_ok=True)
COLORS = {
    "red": (153, 32, 34),
    "felt": (24, 71, 58), "walnut": (56, 35, 27), "leather": (37, 49, 44),
    "brass": (174, 145, 97), "steel": (36, 42, 43), "ivory": (235, 230, 210),
    "jade": (24, 97, 77), "screen": (8, 21, 24), "light": (113, 209, 184),
}
for name, base in COLORS.items():
    rng = random.Random(name)
    image = Image.new("RGB", (64, 64))
    for y in range(64):
        for x in range(64):
            noise = rng.uniform(-2, 2)
            if name == "walnut":
                noise += 8 * math.sin(x * .7 + math.sin(y * .11)) + 3 * math.sin(x * 2.7)
            elif name == "felt":
                noise += 2 * ((x + y) % 2)
            elif name == "brass":
                noise += 9 * math.sin(y / 63 * math.pi) + rng.uniform(-2, 2)
            image.putpixel((x, y), tuple(max(0, min(255, int(c + noise))) for c in base))
    image.save(TEXTURES / f"{name}.png")

def element(a: list[float], b: list[float], material: str) -> dict:
    return {"from": a, "to": b, "faces": {side: {"texture": f"#{material}"} for side in ("up", "down", "north", "south", "east", "west")}}

def model(name: str, boxes: list[dict]) -> None:
    data = {"parent": "minecraft:block/block", "textures": {key: f"satori_mahjong:block/furniture/{key}" for key in COLORS}, "elements": boxes}
    data["textures"]["particle"] = "satori_mahjong:block/furniture/walnut"
    (ROOT / f"models/block/{name}.json").write_text(json.dumps(data, indent=2) + "\n")

model("table", [element([2, .5, 2], [14, 2, 14], "steel"), element([4, 2, 4], [12, 10.8, 12], "walnut"), element([3.8, 9.9, 3.8], [12.2, 10.2, 12.2], "brass")])
model("table_surface", [element([0, 10.8, 0], [16, 11.3, 16], "steel")])
(ROOT / "blockstates/table.json").write_text(json.dumps({"variants": {f"part={part}": {"model": "satori_mahjong:block/" + ("table" if part == 4 else "table_surface")} for part in range(9)}}, indent=2) + "\n")
chair = [element([2, .3, 2], [14, 1, 14], "steel"), element([6.6, 1, 6.6], [9.4, 6, 9.4], "brass"),
         element([1, 6, 1], [15, 7.5, 15], "walnut"), element([1.5, 7.5, 1], [14.5, 9.6, 14], "leather"),
         element([2, 9.6, 1.5], [14, 10, 13.5], "leather"),
         element([1, 8, 13], [15, 22, 15.5], "walnut"), element([2, 10, 12], [14, 21.5, 13.2], "leather"),
         element([2.5, 21.5, 12.4], [13.5, 23, 15], "leather")]
for x in [1, 14]:
    chair += [element([x, 7, 3], [x + 1, 13, 4], "brass"), element([x, 7, 11], [x + 1, 13, 12], "brass"),
              element([x - .4, 12.5, 2], [x + 1.4, 14, 13], "leather")]
for x in [3, 5.5, 8, 10.5, 13]:
    chair.append(element([x, 11, 11.94], [x + .12, 20.5, 12.04], "brass"))
model("chair", chair)
(ROOT / "blockstates/chair.json").write_text(json.dumps({"variants": {f"facing={name}": {"model": "satori_mahjong:block/chair", "y": angle} for name, angle in [("north", 0), ("east", 90), ("south", 180), ("west", 270)]}}, indent=2) + "\n")

print("Generated furniture models and materials")
