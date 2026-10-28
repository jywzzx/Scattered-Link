/**
 * 界面冒烟测试（Electron 主进程脚本，不是页面脚本）。
 *
 * 为什么需要它：vite build 通过只代表能编译，不代表界面真的渲染出来了。
 * 这个脚本用隐藏窗口加载打包产物，逐个路由切换，检查：
 *   · 页面是否有实际内容（innerText 长度）
 *   · 预期文案是否出现（漏了就是组件没挂上）
 *   · 控制台是否报错
 *
 * 运行：node_modules/electron/dist/electron.exe scripts/smoke.cjs
 */
'use strict'

const { app, BrowserWindow } = require('electron')
const path = require('node:path')

app.disableHardwareAcceleration()

/** 每个路由：hash、必须出现的关键字、说明 */
const ROUTES = [
  { hash: '#/home', must: ['Sparsa', '创建房间', '加入房间', '本机环境'], name: '首页' },
  { hash: '#/host', must: ['创建房间', '链路设置', '强制统一中继'], name: '创建房间' },
  { hash: '#/join', must: ['加入房间', '邀请码', '粘贴配置'], name: '加入房间' },
  { hash: '#/phone', must: ['手机端配置', '配置模式', '导入前请核对'], name: '手机端配置' },
  { hash: '#/diagnostics', must: ['网络诊断', '运行环境', '启动预检'], name: '网络诊断' },
  { hash: '#/logs', must: ['运行日志', '跟随最新'], name: '运行日志' },
  { hash: '#/settings', must: ['设置', '后端连接', '关于'], name: '设置' }
]

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

function collectConsole(win, errors) {
  win.webContents.on('console-message', (...args) => {
    // Electron 37+ 传单个 details 对象；更早版本是 (event, level, message)
    const d = args[0]
    const level = typeof d?.level === 'string' ? d.level : args[1]
    const message = typeof d?.message === 'string' ? d.message : args[2]
    if (level === 'error' || level === 3 || level === 2) {
      errors.push(String(message).slice(0, 300))
    }
  })
}

app.whenReady().then(async () => {
  const errors = []
  const win = new BrowserWindow({
    show: false,
    width: 1280,
    height: 900,
    webPreferences: { offscreen: false, contextIsolation: true, nodeIntegration: false }
  })
  collectConsole(win, errors)

  const indexPath = process.env.SPARSA_SMOKE_INDEX || path.join(__dirname, '..', 'dist', 'index.html')
  try {
    await win.loadFile(indexPath)
  } catch (e) {
    console.log(JSON.stringify({ fatal: 'loadFile 失败: ' + e.message }, null, 2))
    app.exit(2)
    return
  }

  // 等首屏挂载 + 演示模式异步数据回来
  await sleep(2200)

  const results = []
  for (const r of ROUTES) {
    await win.webContents.executeJavaScript(`location.hash = ${JSON.stringify(r.hash)};`)
    await sleep(700)
    const probe = await win.webContents.executeJavaScript(`(() => {
      const text = document.body.innerText || '';
      return {
        len: text.length,
        menu: document.querySelectorAll('.el-menu-item').length,
        buttons: document.querySelectorAll('.el-button').length,
        inputs: document.querySelectorAll('input,textarea').length,
        text: text
      };
    })()`)

    const missing = r.must.filter((k) => !probe.text.includes(k))
    results.push({
      route: r.hash,
      name: r.name,
      len: probe.len,
      menu: probe.menu,
      buttons: probe.buttons,
      inputs: probe.inputs,
      missing,
      ok: probe.len > 200 && probe.menu >= 7 && missing.length === 0
    })
  }

  const failed = results.filter((x) => !x.ok)
  const summary = {
    routes: results.length,
    passed: results.length - failed.length,
    failed: failed.length,
    consoleErrors: errors.length,
    details: results,
    errorSamples: errors.slice(0, 5)
  }

  console.log(JSON.stringify(summary, null, 2))
  app.exit(failed.length === 0 && errors.length === 0 ? 0 : 1)
})
