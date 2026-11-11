"""Bake a small antialiased Noto CJK atlas for the world-space control panel."""
from pathlib import Path
import json
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1] / "src/main/resources/assets/satori_mahjong"
GLYPHS = "0123456789-東南西北局余本场供托SATORICH 第川麻将血战到底缺万筒条已和定中换牌·"
CHARS = "".join(dict.fromkeys(GLYPHS))
CELL, COLUMNS, SIZE = 128, 8, 88
font = ImageFont.truetype("/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc", SIZE, index=2)
atlas = Image.new("RGBA", (CELL * COLUMNS, CELL * ((len(CHARS) + COLUMNS - 1) // COLUMNS)))
draw = ImageDraw.Draw(atlas)
metrics = {}
for i, char in enumerate(CHARS):
    x, y = (i % COLUMNS) * CELL, (i // COLUMNS) * CELL
    box = font.getbbox(char)
    draw.text((x + CELL / 2 - (box[0] + box[2]) / 2,
               y + CELL / 2 - (box[1] + box[3]) / 2), char, font=font, fill="white")
    metrics[char] = {"index": i, "advance": font.getlength(char) / SIZE}
output = ROOT / "textures/gui/center-glyphs.png"
output.parent.mkdir(parents=True, exist_ok=True)
atlas.save(output)
output.with_suffix(".png.mcmeta").write_text(json.dumps({"texture": {"blur": True, "clamp": True}}) + "\n")
output.with_suffix(".json").write_text(json.dumps({"cell": CELL, "columns": COLUMNS,
    "width": atlas.width, "height": atlas.height, "size": SIZE, "glyphs": metrics}, ensure_ascii=False, indent=2) + "\n")
print(f"Generated {len(CHARS)} glyphs at {atlas.width}×{atlas.height}")
