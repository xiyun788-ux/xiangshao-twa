#!/usr/bin/env python3
"""
生成 Android 传统 launcher 图标 PNG（用于 API < 26 的设备，API 26+ 用自适应图标 XML）。
纯标准库实现，零外部依赖：紫底 + 白色圆形，保证即使没有矢量图标也能正常显示。
输出目录：android/app/src/main/res/mipmap-{hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png
"""
import os
import struct
import zlib

# 包内相对路径（脚本放在仓库 scripts/ 下，工程在 ../android）
HERE = os.path.dirname(os.path.abspath(__file__))
BASE = os.path.join(HERE, "..", "android", "app", "src", "main", "res", "mipmap-{}")
SIZES = {"hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

BG = (124, 58, 237, 255)   # 主题紫 #7C3AED
FG = (255, 255, 255, 255)   # 白色


def make_png(path, size):
    cx = cy = size // 2
    r = int(size * 0.34)
    raw = bytearray()
    for y in range(size):
        raw.append(0)  # 每行 filter byte
        for x in range(size):
            dx, dy = x - cx, y - cy
            if dx * dx + dy * dy <= r * r:
                raw += bytes(FG)
            else:
                raw += bytes(BG)

    def chunk(typ, data):
        c = struct.pack(">I", len(data)) + typ + data
        return c + zlib.crc32(typ + data).to_bytes(4, "big")

    sig = b"\x89PNG\r\n\x1a\n"
    ihdr = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)  # 8-bit RGBA
    idat = zlib.compress(bytes(raw), 9)
    png = sig + chunk(b"IHDR", ihdr) + chunk(b"IDAT", idat) + chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)


def main():
    for d, s in SIZES.items():
        out = BASE.format(d) + "/ic_launcher.png"
        make_png(out, s)
        print("wrote", out, s, "x", s)


if __name__ == "__main__":
    main()
