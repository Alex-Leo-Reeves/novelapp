"""Generate the Android (phone) + Android TV launcher icons from the new app icon.

Source: Gemini-generated icon (rounded-square frame, dark background,
neon play button + film strips). Frame bounds measured from the source
image: x 403..972, y 98..668 (570x570), corner radius ~123px.

Outputs:
  composeApp/src/androidMain/res/
    mipmap-{mdpi..xxxhdpi}/ios_app_icon.png   <- manifest icon (rounded square)
    mipmap-{mdpi..xxxhdpi}/ic_launcher.png    <- splash/legacy (rounded square)
    mipmap-{mdpi..xxxhdpi}/ic_launcher_round.png <- circular variant
    drawable-nodpi/ic_launcher_foreground.png <- adaptive icon foreground (66% safe zone)
  tvApp/src/main/res/
    drawable-nodpi/ic_tv_launcher.png         <- manifest icon (replaces vector)
    drawable-nodpi/ic_launcher_foreground.png <- adaptive foreground
"""

import os
import shutil
from PIL import Image, ImageDraw

src_img_path = "/home/masteralex/Gemini_Generated_Image_cw47x7cw47x7cw47.jpeg"
base_dir = "/home/masteralex/Desktop/novelapp"

# Bounding box of the icon artwork in the source image, with 2px padding,
# plus the rounded-corner radius of the metallic frame (measured: 123 + 2).
CROP_BOX = (401, 96, 975, 670)  # 574 x 574
FRAME_RADIUS = 125
MASTER_SIZE = 1024

SS = 4  # supersampling factor for smooth alpha masks


def rounded_mask(size, radius):
    """Antialiased rounded-rectangle alpha mask."""
    big = Image.new("L", (size * SS, size * SS), 0)
    ImageDraw.Draw(big).rounded_rectangle(
        (0, 0, size * SS - 1, size * SS - 1), radius=radius * SS, fill=255
    )
    return big.resize((size, size), Image.Resampling.LANCZOS)


def circle_mask(size):
    """Antialiased circular alpha mask."""
    big = Image.new("L", (size * SS, size * SS), 0)
    ImageDraw.Draw(big).ellipse((0, 0, size * SS - 1, size * SS - 1), fill=255)
    return big.resize((size, size), Image.Resampling.LANCZOS)


def resized(img, size):
    return img.resize((size, size), Image.Resampling.LANCZOS)


# --- Master: rounded-square RGBA icon at 1024x1024 ---
src = Image.open(src_img_path).convert("RGB").crop(CROP_BOX)
master = resized(src, MASTER_SIZE).convert("RGBA")
master.putalpha(
    rounded_mask(MASTER_SIZE, round(FRAME_RADIUS * MASTER_SIZE / (CROP_BOX[2] - CROP_BOX[0])))
)
print(f"Master icon: {master.size}")

# --- 1. Android phone (composeApp) ---
densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

for density, size in densities.items():
    mipmap_dir = os.path.join(
        base_dir, f"composeApp/src/androidMain/res/mipmap-{density}"
    )
    os.makedirs(mipmap_dir, exist_ok=True)

    square = resized(master, size)
    square.save(os.path.join(mipmap_dir, "ios_app_icon.png"))
    square.save(os.path.join(mipmap_dir, "ic_launcher.png"))

    round_img = square.copy()
    round_img.putalpha(circle_mask(size))
    round_img.save(os.path.join(mipmap_dir, "ic_launcher_round.png"))
    print(f"Saved {density} mipmaps ({size}x{size})")

# Adaptive icon foreground: full content must fit the 66% safe zone
safe_size = int(MASTER_SIZE * 0.66)
canvas = Image.new("RGBA", (MASTER_SIZE, MASTER_SIZE), (0, 0, 0, 0))
offset = (MASTER_SIZE - safe_size) // 2
canvas.paste(resized(master, safe_size), (offset, offset))

for res_dir in (
    os.path.join(base_dir, "composeApp/src/androidMain/res/drawable-nodpi"),
    os.path.join(base_dir, "tvApp/src/main/res/drawable-nodpi"),
):
    os.makedirs(res_dir, exist_ok=True)
    canvas.save(os.path.join(res_dir, "ic_launcher_foreground.png"))
print("Saved adaptive icon foregrounds (66% safe zone)")

# --- 2. Android TV (tvApp) ---
tv_res = os.path.join(base_dir, "tvApp/src/main/res")

# Replace the hand-drawn vector launcher icon with the new artwork
old_tv_icon = os.path.join(tv_res, "drawable/ic_tv_launcher.xml")
if os.path.exists(old_tv_icon):
    os.remove(old_tv_icon)
    print("Deleted old TV vector icon: drawable/ic_tv_launcher.xml")

resized(master, 512).save(os.path.join(tv_res, "drawable-nodpi/ic_tv_launcher.png"))
print("Saved TV launcher icon: drawable-nodpi/ic_tv_launcher.png (512x512)")

# Keep a copy of the source artwork in the repo for future regeneration
marketing_src = os.path.join(base_dir, "marketing/source/app_icon_source.jpeg")
os.makedirs(os.path.dirname(marketing_src), exist_ok=True)
if not os.path.exists(marketing_src):
    shutil.copy2(src_img_path, marketing_src)
    print(f"Copied source artwork: {marketing_src}")

print("All Android + TV icons updated successfully!")
