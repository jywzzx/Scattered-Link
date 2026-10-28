"""用真实内核验证 TOML 配置是否被正确解析。

内核是常驻进程，不会自行退出，因此必须读取固定行数后主动结束。
把输出写到文件：本机 PowerShell 不回显 stdout，bash 又缺 coreutils。
"""
import subprocess
import sys
import time

CORE = r"engine\easytier-core.exe"


def run(cfg, want_lines=80, timeout=25):
    p = subprocess.Popen([CORE, "-c", cfg, "--no-tun"],
                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    lines = []
    start = time.time()
    try:
        while len(lines) < want_lines and time.time() - start < timeout:
            line = p.stdout.readline()
            if not line:
                break
            lines.append(line.decode("utf-8", "replace").rstrip())
            if len(lines) >= 20 and "connecting to peer" in lines[-1]:
                # 已经读到连接动作，再读几行即可收工
                if len(lines) >= want_lines:
                    break
    finally:
        try:
            p.kill()
        except Exception:
            pass
    return lines


def main():
    out = []
    for cfg in ("build\\_v_flags.toml", "build\\_emit_relay.toml"):
        out.append("========== " + cfg + " ==========")
        out.extend(run(cfg))
        out.append("")
    with open("build\\_kernel_verify.txt", "w", encoding="utf-8") as f:
        f.write("\n".join(out))
    print("done", len(out))


if __name__ == "__main__":
    sys.exit(main())
