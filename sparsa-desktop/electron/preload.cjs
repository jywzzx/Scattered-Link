/**
 * 预加载脚本：唯一的「渲染进程 ↔ 主进程」桥梁。
 *
 * 只暴露必要的能力，不暴露 ipcRenderer 本体 —— 否则渲染进程可以随意调用任意通道。
 * 浏览器里直接打开（npm run dev）时 window.sparsa 为 undefined，前端会自动降级。
 */
'use strict'

const { contextBridge, ipcRenderer } = require('electron')

const invoke = (channel, payload) => ipcRenderer.invoke(channel, payload)

contextBridge.exposeInMainWorld('sparsa', {
  isDesktop: true,

  app: {
    info: () => invoke('sparsa:app:info')
  },

  backend: {
    info: () => invoke('sparsa:backend:info'),
    restart: () => invoke('sparsa:backend:restart'),
    probe: () => invoke('sparsa:backend:probe'),
    onReady: (cb) => {
      const handler = (_e, info) => cb(info)
      ipcRenderer.on('sparsa:backend-ready', handler)
      return () => ipcRenderer.removeListener('sparsa:backend-ready', handler)
    }
  },

  clipboard: {
    write: (text) => invoke('sparsa:clipboard:write', text),
    read: () => invoke('sparsa:clipboard:read')
  },

  dialog: {
    saveText: (opts) => invoke('sparsa:dialog:save-text', opts),
    openText: (opts) => invoke('sparsa:dialog:open-text', opts)
  },

  shell: {
    openExternal: (url) => invoke('sparsa:shell:open-external', url),
    showItem: (p) => invoke('sparsa:shell:show-item', p)
  },

  onNavigate: (cb) => {
    const handler = (_e, route) => cb(route)
    ipcRenderer.on('sparsa:navigate', handler)
    return () => ipcRenderer.removeListener('sparsa:navigate', handler)
  }
})
