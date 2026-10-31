#!/usr/bin/env python3
"""生成 Android launcher 图标：直接复用站点原图标 icons/icon-512.png，复制到各密度 mipmap 目录。

站点原图标是绿色渐变小苗 + 星星的精致圆角图标，用户要求恢复成原来的样子
（之前 TWA/简易壳把它换成了紫底白书/紫底白圆）。

为什么直接复制 512px 而不重采样到 72/96/144/192：
  Android 加载 launcher 图标时，始终按「启动器槽位的标准 dp 尺寸」把位图**缩小**绘制；
  四个密度目录里放的是同一张 512px 图，无论设备选哪个密度桶，结果都是把 512 缩到槽位尺寸，
  显示清晰、与原尺寸版无可见差异。这样脚本零外部依赖（纯标准库），CI 必然能跑通。

输出目录：android/app/src/main/res/mipmap-{hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png
"""
import os
import shutil

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "icons", "icon-512.png")
BASE = os.path.join(HERE, "..", "android", "app", "src", "main", "res", "mipmap-{}")
DENSITIES = ["hdpi", "xhdpi", "xxhdpi", "xxxhdpi"]


def main():
    if not os.path.exists(SRC):
        raise SystemExit(f"未找到站点原图标: {SRC}")
    for d in DENSITIES:
        out = BASE.format(d) + "/ic_launcher.png"
        os.makedirs(os.path.dirname(out), exist_ok=True)
        shutil.copy(SRC, out)
        print("wrote", out, os.path.getsize(out), "bytes")


if __name__ == "__main__":
    main()
