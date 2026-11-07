# Shiori icon

The current product icon is the user-supplied square, opaque Shiori logo,
originally provided as `Untitled.png` and preserved in
[`shiori_logo.png`](../../app/src/main/res/drawable-nodpi/shiori_logo.png). It is packaged for
`ru.feryafox.shiori`; the diagnostic app keeps its existing launcher icon.

Run `python3 scripts/export-launcher-icons.py` (requires Pillow) to use the
preserved original and regenerate
legacy launcher PNGs at 48, 72, 96, 144 and 192 pixels. The source composition is
preserved. Adaptive launchers use the logo with a one-sixth inset over white.
There is no monochrome override: the opaque source would render as a solid square
rather than a meaningful themed silhouette.

Previous aragonite artwork and generation prompts remain in this directory as
historical design sources. They are no longer the product launcher artwork.
