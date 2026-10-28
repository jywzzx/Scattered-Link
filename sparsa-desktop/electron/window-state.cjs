/**
 * 窗口位置/尺寸记忆。写文件失败不影响启动（例如目录只读）。
 */
'use strict'

const fs = require('node:fs')
const path = require('node:path')
const { app, screen } = require('electron')

const FILE = path.join(app.getPath('userData'), 'window-state.json')
const DEFAULTS = { width: 1180, height: 820 }

function read() {
  try {
    const raw = JSON.parse(fs.readFileSync(FILE, 'utf8'))
    if (!raw || typeof raw.width !== 'number') return { ...DEFAULTS }
    return raw
  } catch {
    return { ...DEFAULTS }
  }
}

/**
 * 按屏幕可用区域收敛尺寸，并保证窗口至少有一部分在屏幕内。
 * 上一版 Java 界面就是把尺寸写死导致标题栏跑到屏幕外，这里显式兜住。
 */
function restore() {
  const saved = read()
  const { workArea } = screen.getPrimaryDisplay()
  const width = Math.min(Math.max(saved.width, 980), workArea.width)
  const height = Math.min(Math.max(saved.height, 680), workArea.height)
  const out = { width, height }

  if (typeof saved.x === 'number' && typeof saved.y === 'number') {
    const withinX = saved.x > workArea.x - width + 120 && saved.x < workArea.x + workArea.width - 120
    const withinY = saved.y >= workArea.y - 10 && saved.y < workArea.y + workArea.height - 120
    if (withinX && withinY) {
      out.x = saved.x
      out.y = saved.y
    }
  }
  return out
}

function track(win) {
  let timer = null
  const save = () => {
    clearTimeout(timer)
    timer = setTimeout(() => {
      try {
        if (win.isDestroyed() || win.isMinimized() || win.isMaximized()) return
        const b = win.getBounds()
        fs.writeFileSync(FILE, JSON.stringify(b), 'utf8')
      } catch {
        /* 忽略：位置记忆不是关键路径 */
      }
    }, 400)
  }
  win.on('resize', save)
  win.on('move', save)
}

module.exports = { restore, track }
