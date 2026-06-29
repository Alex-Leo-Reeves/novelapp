#!/usr/bin/env python3
import math
import random
import struct
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SITE_DIR = ROOT / "site" / "assets" / "audio"
BUNDLED_DIR = ROOT / "kokoro-assets" / "kokoro" / "ambient"
SAMPLE_RATE = 16_000
DURATION_SECONDS = 18


def envelope(i: int, total: int) -> float:
    fade = min(i / (SAMPLE_RATE * 1.2), (total - i - 1) / (SAMPLE_RATE * 1.2), 1.0)
    return max(0.0, min(1.0, fade))


def write_loop(name: str, generator):
    SITE_DIR.mkdir(parents=True, exist_ok=True)
    BUNDLED_DIR.mkdir(parents=True, exist_ok=True)
    total = SAMPLE_RATE * DURATION_SECONDS
    samples = bytearray()
    random.seed(name)
    for i in range(total):
        t = i / SAMPLE_RATE
        value = max(-1.0, min(1.0, generator(t, i) * envelope(i, total)))
        samples.extend(struct.pack("<h", int(value * 32767)))

    for target_dir in (SITE_DIR, BUNDLED_DIR):
        path = target_dir / f"{name}.wav"
        with wave.open(str(path), "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(SAMPLE_RATE)
            wav.writeframes(samples)


def rain(t, i):
    noise = random.uniform(-1, 1) * 0.16
    drop = 0.0
    if random.random() < 0.018:
        drop = random.uniform(0.1, 0.32)
    rumble = math.sin(2 * math.pi * 42 * t) * 0.025
    return noise + drop + rumble


def battle(t, i):
    pulse = math.sin(2 * math.pi * 1.65 * t)
    drum = 0.24 * max(0, pulse) ** 7
    low = 0.10 * math.sin(2 * math.pi * 58 * t)
    metal = 0.03 * math.sin(2 * math.pi * 330 * t) * (1 if int(t * 2) % 3 == 0 else 0)
    return drum + low + metal


def suspense(t, i):
    drone = 0.12 * math.sin(2 * math.pi * 82 * t)
    wobble = 0.06 * math.sin(2 * math.pi * (111 + 8 * math.sin(t * 0.4)) * t)
    hiss = random.uniform(-1, 1) * 0.025
    return drone + wobble + hiss


def calm(t, i):
    pad = 0.11 * math.sin(2 * math.pi * 196 * t) + 0.08 * math.sin(2 * math.pi * 247 * t)
    shimmer = 0.025 * math.sin(2 * math.pi * 392 * t) * (0.5 + 0.5 * math.sin(t * 0.7))
    return pad + shimmer


def sad(t, i):
    base = 0.10 * math.sin(2 * math.pi * 146.83 * t)
    minor = 0.07 * math.sin(2 * math.pi * 174.61 * t)
    breath = 0.025 * math.sin(2 * math.pi * 0.35 * t)
    return base + minor + breath


def main():
    write_loop("rain", rain)
    write_loop("battle", battle)
    write_loop("suspense", suspense)
    write_loop("calm", calm)
    write_loop("sad", sad)
    print(f"Generated ambient loops in {SITE_DIR} and {BUNDLED_DIR}")


if __name__ == "__main__":
    main()
