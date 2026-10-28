#!/usr/bin/env sh
# LANLink 启动脚本（Linux / macOS / Android Termux）
set -e
cd "$(dirname "$0")"

if ! command -v node >/dev/null 2>&1; then
  echo "[!] 未检测到 Node.js，请先安装 Node.js 18+"
  echo "    Termux:  pkg install nodejs"
  echo "    Debian:  sudo apt install nodejs"
  echo "    macOS:   brew install node"
  exit 1
fi

exec node src/main.js --open "$@"
