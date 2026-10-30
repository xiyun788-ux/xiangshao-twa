#!/usr/bin/env python3
"""
用伪终端(pty)驱动交互式命令行工具。

为什么需要它：
  bubblewrap init 内部用 inquirer 提问，inquirer **必须是真正的 TTY** 才会
  逐条处理输入 —— 用管道喂进去时，整块输入会被当成一个字符流，回车(\r)也
  不会触发「提交」，结果就是第一个问题就把所有答案吃进去然后卡死。
  （GitHub Actions 里表现为：init 步骤一直 in_progress，直到 6 小时超时。）

做法：
  1. pty.fork() 出一个带终端的子进程，跑目标命令；
  2. 持续读取子进程输出；
  3. 按 PROMPTS 的顺序，等到对应的提示语出现，就回一条答案（回车即用默认值）。

用法：python3 scripts/run-init.py bubblewrap init --manifest <url>
"""
import os
import pty
import select
import sys
import time

# (提示语, 回答)。回答为 '' 表示直接回车，沿用默认值。
# 顺序与 bubblewrap init 的提问顺序严格一致（manifest 无 shortcuts，故跳过该问）。
PROMPTS = [
    ("Domain:", ""),
    # startUrl 必须是「相对域名的路径」，bubblewrap 会拼成 https://{host}{startUrl}。
    # 本站托管要求首个请求带 sharecode（不带就 403），之后靠 session cookie。
    ("URL path:", "/?sharecode=24NRJSLYLH"),
    ("Application name:", "英语小花园"),
    ("Short name:", ""),
    ("Application ID:", "com.xiangshao.grade3"),
    ("Starting version code for the new app version:", ""),
    ("Display mode:", ""),
    ("Orientation:", ""),
    ("Status bar color:", ""),
    ("Splash screen color:", ""),
    # 图标走 raw.githubusercontent（仓库里已提交），不依赖线上站点的哈希路径，
    # 也不需要 sharecode。
    ("Icon URL:", "https://raw.githubusercontent.com/xiyun788-ux/xiangshao-twa/main/icons/icon-192.png"),
    ("Maskable icon URL:", "https://raw.githubusercontent.com/xiyun788-ux/xiangshao-twa/main/icons/icon-512.png"),
    ("Monochrome icon URL:", ""),
    ("Include support for Play Billing?", "n"),
    ("Request geolocation permission?", "n"),
    ("Key store location:", ""),
    ("Key name:", "xiangshao"),
]

PROMPT_TIMEOUT = 120  # 单个提示语的等待上限（秒）
TOTAL_TIMEOUT = 900


def main():
    cmd = sys.argv[1:]
    if not cmd:
        print("usage: run-init.py <command> [args...]", file=sys.stderr)
        return 2

    pid, fd = pty.fork()
    if pid == 0:
        os.environ["TERM"] = "xterm"
        os.execvp(cmd[0], cmd)
        os._exit(127)

    idx = 0
    seen = b""
    last_out = time.time()
    start = time.time()

    while True:
        if time.time() - start > TOTAL_TIMEOUT:
            print("\n[run-init] 总超时，强制结束", file=sys.stderr)
            try:
                os.kill(pid, 9)
            except OSError:
                pass
            return 124

        r, _, _ = select.select([fd], [], [], 0.4)
        if r:
            try:
                data = os.read(fd, 4096)
            except OSError:
                break
            if not data:
                break
            sys.stdout.buffer.write(data)
            sys.stdout.buffer.flush()
            seen += data
            last_out = time.time()
            continue

        # 没有新输出了：看看是不是在等某个提示语
        if idx < len(PROMPTS):
            marker, answer = PROMPTS[idx]
            if marker.encode() in seen[-4000:]:
                payload = answer.encode() + b"\r"
                os.write(fd, payload)
                print(f"\n[run-init] -> {marker} = {answer!r}", file=sys.stderr)
                seen = b""  # 清掉，避免同一提示语被重复匹配
                idx += 1
                last_out = time.time()
                continue
            if time.time() - last_out > PROMPT_TIMEOUT:
                print(f"\n[run-init] 等待提示语 {marker!r} 超时，仍尝试回答", file=sys.stderr)
                os.write(fd, answer.encode() + b"\r")
                seen = b""
                idx += 1
                last_out = time.time()
                continue
        else:
            # 所有问题都答完了，等子进程自己结束
            if time.time() - last_out > 300:
                print("\n[run-init] 长时间无输出，强制结束", file=sys.stderr)
                try:
                    os.kill(pid, 9)
                except OSError:
                    pass
                break

    _, status = os.waitpid(pid, 0)
    code = os.waitstatus_to_exitcode(status) if hasattr(os, "waitstatus_to_exitcode") else status
    print(f"\n[run-init] 子进程退出码: {code}", file=sys.stderr)
    return code


if __name__ == "__main__":
    sys.exit(main())
