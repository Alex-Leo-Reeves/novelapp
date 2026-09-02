#!/usr/bin/env python3
import os
from PIL import Image, ImageDraw

def create_vidaa_assets():
    os.makedirs("vidaa-tv/assets", exist_ok=True)
    os.makedirs("site/tv/assets", exist_ok=True)

    # 1. 512x512 icon
    img_512 = Image.new("RGBA", (512, 512), (5, 6, 11, 255))
    draw = ImageDraw.Draw(img_512)
    # Background gradient / rounded box
    draw.rounded_rectangle([32, 32, 480, 480], radius=80, fill=(18, 22, 34, 255), outline=(232, 77, 138, 255), width=6)
    # Inner glow box
    draw.rounded_rectangle([64, 64, 448, 448], radius=60, fill=(12, 14, 22, 255))
    # Draw glowing book shape
    # Left wing
    draw.polygon([(110, 160), (240, 130), (240, 360), (110, 390)], fill=(232, 77, 138, 255))
    # Right wing
    draw.polygon([(272, 130), (402, 160), (402, 390), (272, 360)], fill=(34, 211, 238, 255))
    # Center spine
    draw.line([(256, 120), (256, 370)], fill=(255, 255, 255, 255), width=6)
    # TV badge at bottom
    draw.rounded_rectangle([190, 410, 322, 450], radius=10, fill=(255, 120, 79, 255))

    img_512.save("vidaa-tv/assets/icon.png")
    img_512.save("site/tv/assets/icon.png")

    # 2. Large icon 1024x1024
    img_1024 = img_512.resize((1024, 1024), Image.Resampling.LANCZOS)
    img_1024.save("vidaa-tv/assets/largeIcon.png")
    img_1024.save("site/tv/assets/largeIcon.png")

    # 3. 1920x1080 Splash Banner
    splash = Image.new("RGBA", (1920, 1080), (5, 6, 11, 255))
    draw_splash = ImageDraw.Draw(splash)
    draw_splash.ellipse([760, 340, 1160, 740], fill=(232, 77, 138, 30))
    splash.paste(img_512, (704, 284), img_512)
    splash.save("vidaa-tv/assets/splash.png")
    splash.save("site/tv/assets/splash.png")
    print("VIDAA assets generated successfully!")

if __name__ == "__main__":
    create_vidaa_assets()
