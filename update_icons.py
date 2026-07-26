import os
import json
from PIL import Image

src_img_path = "/home/masteralex/.gemini/antigravity-ide/brain/7c6b27ac-3320-4858-bd80-3449447b2a1d/icon_abstract_media_1785008333697.png"
base_dir = "/home/masteralex/Desktop/novelapp"

img = Image.open(src_img_path)

# 1. iOS
ios_dir = os.path.join(base_dir, "iosApp/Assets.xcassets/AppIcon.appiconset")
contents_path = os.path.join(ios_dir, "Contents.json")
with open(contents_path, "r") as f:
    contents = json.load(f)

for item in contents.get("images", []):
    size_str = item["size"]
    scale_str = item["scale"]
    w, h = map(float, size_str.split("x"))
    scale = float(scale_str.replace("x", ""))
    target_w = int(w * scale)
    target_h = int(h * scale)
    
    filename = item["filename"]
    out_path = os.path.join(ios_dir, filename)
    
    resized = img.resize((target_w, target_h), Image.Resampling.LANCZOS)
    resized.save(out_path)
    print(f"Saved iOS icon: {out_path} ({target_w}x{target_h})")

# 2. Android ComposeApp
android_drawable_nodpi = os.path.join(base_dir, "composeApp/src/androidMain/res/drawable-nodpi")
os.makedirs(android_drawable_nodpi, exist_ok=True)
android_fg_path = os.path.join(android_drawable_nodpi, "ic_launcher_foreground.png")
resized_android = img.resize((1024, 1024), Image.Resampling.LANCZOS)
resized_android.save(android_fg_path)
print(f"Saved Android foreground: {android_fg_path}")

# Delete old xml
old_xml = os.path.join(base_dir, "composeApp/src/androidMain/res/drawable/ic_launcher_foreground.xml")
if os.path.exists(old_xml):
    os.remove(old_xml)
    print("Deleted old Android foreground xml")

# 3. Android TVApp
tv_drawable_nodpi = os.path.join(base_dir, "tvApp/src/main/res/drawable-nodpi")
os.makedirs(tv_drawable_nodpi, exist_ok=True)
tv_fg_path = os.path.join(tv_drawable_nodpi, "ic_launcher_foreground.png")
resized_android.save(tv_fg_path)
print(f"Saved TV foreground: {tv_fg_path}")

old_tv_xml = os.path.join(base_dir, "tvApp/src/main/res/drawable/ic_launcher_foreground.xml")
if os.path.exists(old_tv_xml):
    os.remove(old_tv_xml)
    print("Deleted old TV foreground xml")

# 4. Desktop
desktop_dir = os.path.join(base_dir, "composeApp/src/desktopMain/resources/icons")
os.makedirs(desktop_dir, exist_ok=True)
desktop_ico = os.path.join(desktop_dir, "novelapp.ico")
resized_desktop = img.resize((256, 256), Image.Resampling.LANCZOS)
resized_desktop.save(desktop_ico, format="ICO")
print(f"Saved Desktop icon: {desktop_ico}")

print("All icons updated successfully!")
