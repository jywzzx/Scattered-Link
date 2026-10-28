/**
 * 主进程 IPC 注册表。
 *
 * 渲染进程不允许直接碰 Node / 文件系统，所有系统能力都从这里过一道。
 * 通道命名统一 `sparsa:<域>:<动作>`。
 */
'use strict'

const { ipcMain, dialog, shell, clipboard, app, BrowserWindow } = require('electron')
const fs = require('node:fs/promises')
const path = require('node:path')
const backend = require('./backend.cjs')

function ownerWindow(e) {
  return BrowserWindow.fromWebContents(e.sender) || BrowserWindow.getFocusedWindow()
}

function register() {
  ipcMain.handle('sparsa:app:info', () => ({
    name: '零星',
    version: app.getVersion(),
    electron: process.versions.electron,
    chrome: process.versions.chrome,
    node: process.versions.node,
    platform: process.platform,
    arch: process.arch
  }))

  ipcMain.handle('sparsa:backend:info', () => backend.current())
  ipcMain.handle('sparsa:backend:restart', () => backend.restart())
  ipcMain.handle('sparsa:backend:probe', () => ({
    candidates: backend.candidates(),
    // 被拒绝的入口：通常是旧 Swing 版本的启动器，界面上要能说清为什么不用它
    rejected: backend.rejected(),
    classesDir: backend.javaClassesDir(),
    info: backend.current()
  }))

  ipcMain.handle('sparsa:clipboard:write', (_e, text) => {
    clipboard.writeText(String(text ?? ''))
    return true
  })
  ipcMain.handle('sparsa:clipboard:read', () => clipboard.readText())

  ipcMain.handle('sparsa:dialog:save-text', async (e, { content, defaultName, filters }) => {
    const win = ownerWindow(e)
    const { canceled, filePath } = await dialog.showSaveDialog(win, {
      title: '保存',
      defaultPath: defaultName || 'lanlink.toml',
      filters: filters || [{ name: '配置文件', extensions: ['toml', 'txt'] }]
    })
    if (canceled || !filePath) return { canceled: true }
    await fs.writeFile(filePath, String(content ?? ''), 'utf8')
    return { canceled: false, filePath }
  })

  ipcMain.handle('sparsa:dialog:open-text', async (e, { filters } = {}) => {
    const win = ownerWindow(e)
    const { canceled, filePaths } = await dialog.showOpenDialog(win, {
      title: '导入配置文件',
      properties: ['openFile'],
      filters: filters || [{ name: '配置文件', extensions: ['toml', 'txt', 'conf'] }]
    })
    if (canceled || !filePaths?.length) return { canceled: true }
    const content = await fs.readFile(filePaths[0], 'utf8')
    return { canceled: false, filePath: filePaths[0], content }
  })

  ipcMain.handle('sparsa:shell:open-external', (_e, url) => {
    if (/^https?:\/\//i.test(String(url))) return shell.openExternal(url)
    return false
  })

  ipcMain.handle('sparsa:shell:show-item', (_e, p) => {
    if (p) shell.showItemInFolder(path.resolve(String(p)))
    return true
  })
}

module.exports = { register }
