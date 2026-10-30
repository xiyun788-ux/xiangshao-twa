#!/usr/bin/env bash
# bubblewrap init 的实际命令。
#
# 注意：bubblewrap CLI 的 --packageId / --appName / --host / --themeColor 等参数
# **并不会**被写进 twa-manifest.json（源码里 init() 只用了 args.manifest / args.directory
# 等少数几个），真正的取值全部来自「交互式提问」。因此真正生效的值在
# scripts/init-answers.txt 里按提问顺序给出，这些 flag 仅作可读性/兜底。
set -euo pipefail

bubblewrap init \
  --manifest "https://suike.app.workbuddy.host/manifest.webmanifest?sharecode=24NRJSLYLH" \
  --packageId com.xiangshao.grade3 \
  --appName "英语小花园" \
  --host suike.app.workbuddy.host \
  --themeColor "#4CAF6E" \
  --navigationColor "#4CAF6E" \
  --backgroundColor "#FDF8EE" \
  --shortName "英语小花园" \
  --targetSdkVersion 34 \
  --minSdkVersion 21 \
  --areJavaDependenciesAccepted true
