#!/usr/bin/env python3
"""生成 Android launcher 图标：复用站点原图标 icons/icon-512.png，重采样到各密度标准尺寸。

站点原图标是绿色渐变小苗 + 星星的精致圆角图标，用户要求恢复成原来的样子
（之前 TWA/简易壳把它换成了紫底白书/紫底白圆）。

Android 各密度 mipmap 的标称尺寸：hdpi=72, xhdpi=96, xxhdpi=144, xxxhdpi=192。
把 512px 原图标重采样到这些尺寸，避免把所有密度都塞 512px 导致的缩放怪异/模糊。
输出目录：android/app/src/main/res/mipmap-{hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png
"""
import os
import shutil
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "icons", "icon-512.png")
BASE = os.path.join(HERE, "..", "android", "app", "src", "main", "res", "mipmap-{}")
# 密度 -> 标称像素尺寸
DENSITIES = {"hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def main():
    if not os.path.exists(SRC):
        raise SystemExit(f"未找到站点原图标: {SRC}")
    src = Image.open(SRC).convert("RGBA")
    for d, size in DENSITIES.items():
        out = BASE.format(d) + "/ic_launcher.png"
        os.makedirs(os.path.dirname(out), exist_ok=True)
        # LANCZOS 高质量下采样，边缘更锐利
        img = src.resize((size, size), Image.LANCZOS)
        img.save(out, "PNG")
        print("wrote", out, size, "x", size, os.path.getsize(out), "bytes")


if __name__ == "__main__":
    main()
