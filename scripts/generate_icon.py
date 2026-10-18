"""Generate the original crescent app icon. Optional development dependency: Pillow."""
from pathlib import Path
from PIL import Image, ImageDraw

image = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
draw = ImageDraw.Draw(image)
draw.rounded_rectangle((0, 0, 255, 255), radius=58, fill="#193F3B")
draw.ellipse((52, 45, 200, 193), fill="#E5EBCB")
draw.ellipse((99, 23, 219, 143), fill="#193F3B")
draw.ellipse((185, 166, 199, 180), fill="#AAC8A5")
target = Path(__file__).resolve().parents[1] / "src" / "CustomWindDown.App" / "Assets" / "app.ico"
target.parent.mkdir(parents=True, exist_ok=True)
image.save(target, sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
print(target)
