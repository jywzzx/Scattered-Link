/**
 * 零星 主进程入口。
 *
 * 职责边界：
 *   1. 单实例 + 窗口生命周期
 *   2. 拉起/停止 Java 后端进程（--api 模式）
 *   3. 通过 preload 把「受控的系统能力」交给渲染进程
 *
 * 界面本身完全在渲染进程（Vue），这里不含任何 UI 代码。
 */
'use strict'

const { app, BrowserWindow, Menu, shell } = require('electron')
const path = require('node:path')
const backend = require('./backend.cjs')
const ipc = require('./ipc.cjs')
const windowState = require('./window-state.cjs')

// 只有显式设置 NODE_ENV=development 才去连本地 Vite。
// 早先写成 `|| !app.isPackaged`，导致「未打包」状态下即使已经 npm run build，
// 也只会去连 5173，本地想验证打包后的 dist 反而打不开窗口。
const isDev = process.env.NODE_ENV === 'development'
const DEV_URL = process.env.SPARSA_DEV_URL || 'http://localhost:5173'

let mainWindow = null

function createWindow() {
  const bounds = windowState.restore()
  mainWindow = new BrowserWindow({
    ...bounds,
    minWidth: 980,
    minHeight: 680,
    show: false,
    backgroundColor: '#f5f7fa',
    title: 'Sparsa 零星 · 虚拟局域网',
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
      spellcheck: false
    }
  })

  windowState.track(mainWindow)

  // 首帧渲染完成再显示，避免白屏闪烁
  mainWindow.once('ready-to-show', () => {
    mainWindow.show()
  })

  if (isDev) {
    mainWindow.loadURL(DEV_URL)
    mainWindow.webContents.openDevTools({ mode: 'detach' })
  } else {
    mainWindow.loadFile(path.join(__dirname, '..', 'dist', 'index.html'))
  }

  // 外链一律交给系统浏览器，不在应用内开新窗口
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:\/\//i.test(url)) shell.openExternal(url)
    return { action: 'deny' }
  })

  mainWindow.on('closed', () => {
    mainWindow = null
  })
}

function buildMenu() {
  const template = [
    {
      label: '文件',
      submenu: [
        {
          label: '编辑手机端配置',
          accelerator: 'CmdOrCtrl+P',
          click: () => mainWindow?.webContents.send('sparsa:navigate', '/phone')
        },
        {
          label: '导出诊断信息',
          accelerator: 'CmdOrCtrl+D',
          click: () => mainWindow?.webContents.send('sparsa:navigate', '/diagnostics')
        },
        { type: 'separator' },
        { role: 'quit', label: '退出' }
      ]
    },
    {
      label: '视图',
      submenu: [
        { role: 'reload', label: '重新加载' },
        { role: 'toggleDevTools', label: '开发者工具' },
        { type: 'separator' },
        { role: 'resetZoom', label: '实际大小' },
        { role: 'zoomIn', label: '放大' },
        { role: 'zoomOut', label: '缩小' }
      ]
    },
    {
      label: '帮助',
      submenu: [
        {
          label: '项目主页',
          click: () => shell.openExternal('https://github.com/jywzzx/Sparsa')
        }
      ]
    }
  ]
  Menu.setApplicationMenu(Menu.buildFromTemplate(template))
}

// 单实例：第二次启动只把已有窗口提到前台，不重复拉后端
const gotLock = app.requestSingleInstanceLock()
if (!gotLock) {
  app.quit()
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore()
      mainWindow.focus()
    }
  })

  app.whenReady().then(async () => {
    ipc.register()
    buildMenu()
    createWindow()

    // 后端与窗口并行启动：界面先出来，再用后端状态刷新
    const info = await backend.start()
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send('sparsa:backend-ready', info)
    }
  })

  app.on('window-all-closed', () => {
    backend.stop()
    app.quit()
  })

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })

  app.on('before-quit', () => {
    backend.stop()
  })
}
