from __future__ import annotations

from pathlib import Path
from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
MASTER = ROOT / "branding" / "aegis-icon-master.png"
DESKTOP_RESOURCES = ROOT / "app-desktop" / "desktop-main" / "src" / "main" / "resources" / "icons"
WINDOWS_PACKAGE = ROOT / "app-desktop" / "desktop-main" / "src" / "main" / "package" / "windows"
LINUX_PACKAGE = ROOT / "app-desktop" / "desktop-main" / "src" / "main" / "package" / "linux"
ANDROID_RES = ROOT / "app-android" / "src" / "main" / "res"


def crop_visible(image: Image.Image) -> Image.Image:
    rgba = image.convert("RGBA")
    alpha = rgba.getchannel("A")
    bounds = alpha.getbbox()
    if bounds is None:
        raise ValueError("The icon master has no visible pixels")
    return rgba.crop(bounds)


def fitted_icon(source: Image.Image, size: int, coverage: float, background=None) -> Image.Image:
    canvas = Image.new("RGBA", (size, size), background or (0, 0, 0, 0))
    target = max(1, round(size * coverage))
    scale = min(target / source.width, target / source.height)
    resized = source.resize(
        (max(1, round(source.width * scale)), max(1, round(source.height * scale))),
        Image.Resampling.LANCZOS,
    )
    left = (size - resized.width) // 2
    top = (size - resized.height) // 2
    canvas.alpha_composite(resized, (left, top))
    return canvas


def save_png(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, "PNG", optimize=True)


def main() -> None:
    source = crop_visible(Image.open(MASTER))

    desktop = fitted_icon(source, 512, 0.86)
    save_png(desktop, DESKTOP_RESOURCES / "aegis-app-icon.png")
    save_png(fitted_icon(source, 128, 0.90), DESKTOP_RESOURCES / "aegis-tray-icon.png")
    save_png(desktop, LINUX_PACKAGE / "aegis.png")

    ico_source = fitted_icon(source, 256, 0.88)
    WINDOWS_PACKAGE.mkdir(parents=True, exist_ok=True)
    ico_source.save(
        WINDOWS_PACKAGE / "aegis.ico",
        format="ICO",
        sizes=[(16, 16), (20, 20), (24, 24), (32, 32), (40, 40), (48, 48), (64, 64), (128, 128), (256, 256)],
    )

    adaptive = fitted_icon(source, 432, 0.64)
    save_png(adaptive, ANDROID_RES / "drawable-nodpi" / "aegis_icon_foreground.png")

    density_sizes = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    for folder, size in density_sizes.items():
        legacy = fitted_icon(source, size, 0.76, background=(0, 0, 0, 255))
        save_png(legacy, ANDROID_RES / folder / "ic_launcher.png")
        save_png(legacy, ANDROID_RES / folder / "ic_launcher_round.png")


if __name__ == "__main__":
    main()
