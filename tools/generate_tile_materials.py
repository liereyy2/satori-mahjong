"""Reproducible low-noise resin materials for the rounded native tile geometry."""
from pathlib import Path
import json
import math
import random
from PIL import Image

OUTPUT = Path(__file__).resolve().parents[1] / "src/main/resources/assets/satori_mahjong/textures/block/furniture"
for name, base in (("tile_ivory", (228, 224, 215)), ("tile_blue", (79, 129, 194))):
    rng = random.Random(name)
    image = Image.new("RGB", (128, 128))
    for y in range(128):
        for x in range(128):
            # Subtle resin variation; lighting and satin highlights come from the actual normals.
            highlight = 1.5 * math.exp(-((x-43)/62)**2-((y-31)/70)**2)
            value = highlight + rng.uniform(-.7, .7)
            image.putpixel((x,y), tuple(round(min(255,max(0,c+value))) for c in base))
    image.save(OUTPUT / f"{name}.png")
    (OUTPUT / f"{name}.png.mcmeta").write_text(json.dumps({"texture":{"blur":True,"clamp":True}})+"\n")
print("Generated ivory and light-blue resin materials")
