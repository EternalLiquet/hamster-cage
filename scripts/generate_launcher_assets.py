#!/usr/bin/env python3
"""Package the owner's selected image; never downloads or regenerates the artwork.

Development-only: Python 3 + Pillow. Normal Android builds use committed outputs.
"""
import argparse
import hashlib
import json
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageOps

SOURCE_SHA256 = "6c1904727b647cf36c08eda6d799e4529e925b3720c961df821c50ab108675f6"
ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
BACKGROUND = (13, 16, 23, 255)


def save_image(image, relative):
    path = RES / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, optimize=True)
    return path


def centered(image, side, artwork_side, background=(0, 0, 0, 0)):
    result = Image.new("RGBA", (side, side), background)
    scaled = image.resize((artwork_side, artwork_side), Image.Resampling.LANCZOS)
    result.alpha_composite(scaled, ((side - artwork_side) // 2,) * 2)
    return result


def mask(side, shape):
    result = Image.new("L", (side, side))
    draw = ImageDraw.Draw(result)
    if shape == "circle":
        draw.ellipse((0, 0, side - 1, side - 1), fill=255)
    else:
        # A superellipse gives a reproducible squircle stress fixture.
        import math
        points = []
        for n in range(360):
            t = n * math.pi / 180
            c, s = math.cos(t), math.sin(t)
            points.append(((side - 1) / 2 * (1 + math.copysign(abs(c) ** 0.5, c)),
                           (side - 1) / 2 * (1 + math.copysign(abs(s) ** 0.5, s))))
        draw.polygon(points, fill=255)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Local selected original from the private branding directory")
    args = parser.parse_args()
    if hashlib.sha256(args.source.read_bytes()).hexdigest() != SOURCE_SHA256:
        raise SystemExit("Source is not the owner-selected image; refusing to replace branding")
    source = Image.open(args.source).convert("RGBA")
    if source.size != (1254, 1254):
        raise SystemExit("Unexpected selected source dimensions")

    # 108dp layers at xxxhdpi; the complete art occupies a centered 64dp square.
    # All visual content stays inside Android's central 66dp adaptive safe zone.
    foreground = centered(source, 432, 256)
    files = [save_image(foreground, "drawable-nodpi/ic_launcher_foreground.png")]
    # Preserve the original light shapes (hamster, bow, paws, ring, chart) as alpha;
    # dark original eyes/laptop supply negative-space detail, not a replacement drawing.
    luminance = ImageOps.grayscale(source)
    ink = luminance.point(lambda value: max(0, min(255, (value - 65) * 255 // 70)))
    ink = ImageChops.multiply(ink, source.getchannel("A"))
    mono_source = Image.new("RGBA", source.size, "white")
    mono_source.putalpha(ink)
    monochrome = centered(mono_source, 432, 256)
    files.append(save_image(monochrome, "drawable-nodpi/ic_launcher_monochrome.png"))

    for density, side in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
        legacy = centered(source, side, round(side * 0.90), BACKGROUND)
        legacy.putalpha(mask(side, "squircle"))
        files.append(save_image(legacy, f"mipmap-{density}/ic_launcher.png"))
        rounded = legacy.copy()
        rounded.putalpha(mask(side, "circle"))
        files.append(save_image(rounded, f"mipmap-{density}/ic_launcher_round.png"))

    report = {
        "source_repository": "EternalLiquet/gpt-projects-files",
        "source_commit": "5d597258a8f98eee2d26f147c97f7447a2bfe133",
        "source_path": "hamster-cage/branding/hamster-cage-icon.png",
        "source_sha256": SOURCE_SHA256,
        "layer_dp": 108,
        "artwork_dp": 64,
        "assets": {str(p.relative_to(ROOT)).replace("\\", "/"): hashlib.sha256(p.read_bytes()).hexdigest() for p in files},
    }
    (ROOT / "docs/branding-resources.json").write_text(json.dumps(report, indent=2) + "\n")

    # Review fixtures simulate 72dp masked viewports of the packaged 108dp layers.
    # They are clearly separated from on-device launcher evidence.
    sheet = Image.new("RGBA", (720, 650), (238, 235, 229, 255))
    draw = ImageDraw.Draw(sheet)
    for column, (label, layer, colors) in enumerate([
        ("Selected artwork", foreground, None),
        ("Monochrome / light", monochrome, ((240, 188, 95, 255), (36, 30, 26, 255))),
        ("Monochrome / dark", monochrome, ((36, 30, 26, 255), (240, 188, 95, 255))),
    ]):
        draw.text((column * 240 + 15, 12), label, fill="black")
        for row, shape in enumerate(["circle", "squircle"]):
            # Android layers are inset by 18dp on all sides of the visible viewport.
            visible = layer.crop((72, 72, 360, 360))
            if colors is None:
                bg = Image.new("RGBA", visible.size, BACKGROUND)
                bg.alpha_composite(visible)
            else:
                bg = Image.new("RGBA", visible.size, colors[0])
                tint = Image.new("RGBA", visible.size, colors[1])
                tint.putalpha(visible.getchannel("A"))
                bg.alpha_composite(tint)
            bg.putalpha(mask(288, shape))
            sheet.alpha_composite(bg.resize((192, 192), Image.Resampling.LANCZOS), (column * 240 + 24, row * 270 + 45))
            draw.text((column * 240 + 24, row * 270 + 245), shape, fill="black")
            for i, small in enumerate([24, 36, 48]):
                sheet.alpha_composite(bg.resize((small, small), Image.Resampling.LANCZOS), (column * 240 + 24 + i * 64, 585 - small))
        draw.text((column * 240 + 24, 600), "24 / 36 / 48px", fill="black")
    preview = ROOT / ".delivery/icon-mask-fixtures.png"
    preview.parent.mkdir(exist_ok=True)
    sheet.convert("RGB").save(preview)
    print(f"Packaged {len(files)} derived assets; mask fixture: {preview}")


if __name__ == "__main__":
    main()
