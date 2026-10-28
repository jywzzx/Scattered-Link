@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

where node >nul 2>nul
if errorlevel 1 (
  echo.
  echo   [!] 未检测到 Node.js
  echo       请先安装 Node.js 18 或更高版本: https://nodejs.org
  echo.
  pause
  exit /b 1
)

echo.
echo   正在启动 LANLink ...
echo.

node src\main.js --open %*
if errorlevel 1 pause
