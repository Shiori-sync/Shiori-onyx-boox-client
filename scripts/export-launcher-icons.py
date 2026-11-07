#!/usr/bin/env python3
"""Package the supplied Shiori logo as Android launcher resources (requires Pillow)."""

from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
SOURCE = RES / "drawable-nodpi/shiori_logo.png"


def main():
    with Image.open(SOURCE) as source:
        if source.width != source.height:
            raise ValueError("Launcher source must be square")
        # Keep the supplied composition intact at each legacy launcher density.
        for density, size in (
            ("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
            ("xxhdpi", 144), ("xxxhdpi", 192),
        ):
            source.resize((size, size), Image.Resampling.LANCZOS).save(
                RES / f"mipmap-{density}/ic_launcher.png"
            )


if __name__ == "__main__":
    main()
