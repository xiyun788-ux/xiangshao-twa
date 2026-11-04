#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CI 静态守门：在 gradle 打包之前，校验「真正会被打进 APK 的载荷」的结构性不变量。

背景（QA 指出的防复发缺口）：
  build-apk.yml 原来把「改前端必须 build:h5 → 清截图 → 同步 → 跑 verify → versionCode+1」
  只写在注释里，workflow 的 steps 里没有任何守门；且 verify_apk_payload.mjs 依赖
  本机 Chrome，在 ubuntu-latest 上不可执行。结果是「注释警告的复发源」毫无自动化拦截。
  本脚本纯 Python、零第三方依赖、不需要浏览器/Node，专门补上这一层。

仓库真实布局（重要）：
  本仓库根 = apk-build/，顶层只有 .github / android / icons / scripts。
  前端源码 xiangshao/ **不在本仓库**，CI 只能打包已同步好的 assets/www/。
  因此涉及「源码改动是否重建」的判断无法在 CI 内完成 —— 由本地
  verify_apk_payload.mjs + verify_native_inject.mjs 负责，提交前必须 PASS。
  本脚本守的是仓库内产物的不变量，尤其是两次真实白屏事故的根因：
    P0 原生层 evaluateJavascript 注入 JS 改写 DOM → 整页 display:none；
    P1 登录页图标未随产物同步 → 404。
"""
from pathlib import Path
import re
import subprocess
import sys

REPO = Path(__file__).resolve().parents[1]
WWW = REPO / 'android/app/src/main/assets/www'
JAVA = REPO / 'android/app/src/main/java/com/xiangshao/grade3/MainActivity.java'
GRADLE = REPO / 'android/app/build.gradle'

errors = []


def check(name, ok, detail=''):
    print(('  PASS  ' if ok else '  FAIL  ') + name + (('  — ' + detail) if detail else ''))
    if not ok:
        errors.append(name + (': ' + detail if detail else ''))


def git(*args):
    try:
        r = subprocess.run(['git', *args], cwd=REPO, capture_output=True, text=True, timeout=20)
        return r.stdout if r.returncode == 0 else None
    except Exception:
        return None


print('=== APK 载荷静态守门（CI / 无需浏览器）===')

# ── P0：原生层注入 JS 必须是纯只读 ──────────────────────────────────
java = JAVA.read_text(encoding='utf-8')
m = re.search(r'evaluateJavascript\(\s*([\s\S]*?)\s*,\s*(?:null|value\s*->)', java)
check('MainActivity.java 含 evaluateJavascript 注入调用', bool(m))
if m:
    js = ''.join(
        x.replace('\\"', '"').replace('\\n', '\n').replace('\\\\', '\\')
        for x in re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))
    )
    forbidden = ['.style.display=', 'querySelectorAll(', '.removeChild(', '.remove(',
                  'setAttribute(', 'innerHTML=', 'outerHTML=', 'insertAdjacentHTML']
    hits = [f for f in forbidden if f in js]
    check('注入 JS 纯只读（无任何 DOM 写操作）', not hits, ','.join(hits) or '无')
    check('注入 JS 含 <html>/#app 可见性断言',
          'getComputedStyle' in js and 'getBoundingClientRect' in js)
    print('         注入 JS =', js[:160] + ('...' if len(js) > 160 else ''))

# ── 产物结构 ────────────────────────────────────────────────────────
html = (WWW / 'index.html').read_text(encoding='utf-8')
check('index.html 无外链 script', not re.search(r'<script[^>]+\bsrc\s*=', html, re.I))
check('index.html 无外链 stylesheet',
      not re.search(r'<link[^>]+rel=["\']?stylesheet["\']?[^>]*\bhref\s*=', html, re.I))
check('index.html 含行内 module（单文件化）',
      bool(re.search(r'<script[^>]*type=["\']?module', html, re.I)))
check('index.html 含前端原生壳判断（InstallHint 已重建同步）', 'XiangshaoApp' in html)
check('index.html 含离线阴影覆盖（导航栏 + 3s 延迟 body:after）',
      '[class*=uni-page-head-shadow]:after' in html and 'body:after{animation-name:none' in html)

icons = {p.stem for p in (WWW / 'static/icons').glob('*.svg')}
mp3 = list((WWW / 'static/audio').rglob('*.mp3'))
shots = list(WWW.rglob('_verify_*.png'))
chunks = list((WWW / 'assets').glob('*')) if (WWW / 'assets').exists() else []
check('icons = 26（含 check / exit）',
      len(icons) == 26 and {'check', 'exit'} <= icons, str(len(icons)))
check('音频 = 512 条', len(mp3) == 512, str(len(mp3)))
check('无验证截图污染', not shots, str(len(shots)))
check('无旧外部 chunk 残留', not chunks, str(len(chunks)))

# ── versionCode：载荷/原生壳变更时必须递增，否则用户无法覆盖升级 ─────
def parse_version_code(text):
    # 必须去掉 // 注释：build.gradle 注释里有「versionCode 5」会抢先命中
    # （实测把当前 6 误读成 5，导致递增守门失真）。
    code = '\n'.join(line.split('//')[0] for line in text.splitlines())
    return re.search(r'\bversionCode\s+(\d+)', code)

vc = parse_version_code(GRADLE.read_text(encoding='utf-8'))
check('build.gradle 可解析 versionCode', bool(vc))
if vc:
    cur = int(vc.group(1))
    prev_txt = git('show', 'HEAD^:android/app/build.gradle')
    prev = parse_version_code(prev_txt) if prev_txt else None
    changed = git('diff', '--name-only', 'HEAD^', 'HEAD') or ''
    payload_touched = ('assets/www/' in changed) or ('MainActivity.java' in changed)
    if prev and payload_touched:
        check('载荷/壳已变更 → versionCode 严格递增', cur > int(prev.group(1)),
              '%d > %d' % (cur, int(prev.group(1))))
    else:
        print('  SKIP  versionCode 递增检查（无 HEAD^ 基线或本次未改载荷）')
    print('         当前 versionCode =', cur)

print()
if errors:
    print('GUARD FAIL — %d 项不通过：' % len(errors))
    for e in errors:
        print('   -', e)
    sys.exit(1)
print('GUARD PASS — APK 载荷结构性不变量全部成立，允许继续打包。')
sys.exit(0)
