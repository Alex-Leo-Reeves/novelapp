import os
import shutil
from PIL import Image

src_img_path = "/home/masteralex/.gemini/antigravity-ide/brain/7c6b27ac-3320-4858-bd80-3449447b2a1d/icon_abstract_media_1785008333697.png"
base_dir = "/home/masteralex/Desktop/novelapp"

img = Image.open(src_img_path).convert("RGBA")

# 1. Android Adaptive Icon (Foreground)
# The image should be padded so the main content fits in the 66% safe zone.
android_drawable_nodpi = os.path.join(base_dir, "composeApp/src/androidMain/res/drawable-nodpi")
os.makedirs(android_drawable_nodpi, exist_ok=True)
android_fg_path = os.path.join(android_drawable_nodpi, "ic_launcher_foreground.png")

# Create a transparent 1024x1024 canvas
canvas = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
# Resize original to 682x682 (66% of 1024)
safe_size = int(1024 * 0.66)
resized_safe = img.resize((safe_size, safe_size), Image.Resampling.LANCZOS)
# Paste it in the center
offset = (1024 - safe_size) // 2
canvas.paste(resized_safe, (offset, offset))
canvas.save(android_fg_path)
print(f"Saved adaptive foreground: {android_fg_path}")

# 2. Android Legacy/Round Icons (mipmap)
sizes = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192
}

for density, size in sizes.items():
    mipmap_dir = os.path.join(base_dir, f"composeApp/src/androidMain/res/mipmap-{density}")
    os.makedirs(mipmap_dir, exist_ok=True)
    
    # Standard icon (usually square or whatever shape the image is)
    ic_path = os.path.join(mipmap_dir, "ic_launcher.png")
    img_resized = img.resize((size, size), Image.Resampling.LANCZOS)
    img_resized.save(ic_path)
    
    # Round icon (apply circular mask)
    ic_round_path = os.path.join(mipmap_dir, "ic_launcher_round.png")
    mask = Image.new('L', (size, size), 0)
    from PIL import ImageDraw
    draw = ImageDraw.Draw(mask)
    draw.ellipse((0, 0, size, size), fill=255)
    
    round_img = img_resized.copy()
    round_img.putalpha(mask)
    round_img.save(ic_round_path)
    print(f"Saved {density} icons ({size}x{size})")

# 3. TV App adaptive icon
tv_drawable_nodpi = os.path.join(base_dir, "tvApp/src/main/res/drawable-nodpi")
os.makedirs(tv_drawable_nodpi, exist_ok=True)
tv_fg_path = os.path.join(tv_drawable_nodpi, "ic_launcher_foreground.png")
canvas.save(tv_fg_path)
print(f"Saved TV adaptive foreground")

print("Icon fix complete.")
