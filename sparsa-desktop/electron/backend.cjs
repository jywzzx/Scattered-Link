/**
 * 后端进程管理（主进程侧）
 *
 * 零星 的后端是 LANLink-v2 里那个 Java 进程，新增了 `--api` 无头模式：
 * 不建窗口、只起一个本地 HTTP 服务，把 Engine / Invite / Diagnostics 的能力暴露成 JSON 接口。
 *
 * 三种运行态：
 *   http   —— 成功拉起或连上已有的 Java 后端
 *   mock   —— 没找到后端，前端使用内置假数据（用于纯前端开发与演示）
 *   down   —— 找到后端但启动失败，带上错误原因
 *
 * 与前端只通过一条 IPC 通道交换：{ mode, baseUrl, token, pid, error }。
 */
'use strict'

const { spawn } = require('node:child_process')
const fs = require('node:fs')
const path = require('node:path')
const os = require('node:os')

const PORT_LINE = /SPARSA_API_PORT=(\d+)/
const TOKEN_LINE = /SPARSA_API_TOKEN=(\S+)/

let child = null
let info = {
  mode: 'mock',
  baseUrl: '',
  token: '',
  pid: 0,
  error: '',
  source: ''
}

function exists(p) {
  try {
    return !!p && fs.existsSync(p)
  } catch {
    return false
  }
}

/**
 * 判断一个可执行文件是不是「本项目的 API 后端」。
 *
 * 为什么必须校验：jpackage 生成的启动器把主类写在自己的 .cfg 里。旧版
 * LANLink.exe 的 app.mainclass 是 lanlink.Main（Swing 窗口入口），它不认 --api，
 * 参数被忽略后会直接弹出 Swing 界面 —— 这正是「点重启后端却弹出旧组件」的原因。
 * 只有 mainclass 明确是 lanlink.server.ApiMain 的启动器才允许被拉起。
 */
function entryProblem(target) {
  const lower = target.toLowerCase()
  if (lower.endsWith('.jar')) {
    // jar 无法从外部判断主类，只接受约定名
    return path.basename(target) === 'sparsa-server.jar' ? '' : '不是 API 后端的 jar 文件'
  }
  if (!lower.endsWith('.exe')) return '不是可执行文件'

  // jpackage app-image：<exe 同名>/app/<name>.cfg
  const base = path.basename(target).replace(/\.exe$/i, '')
  const cfg = path.join(path.dirname(target), 'app', `${base}.cfg`)
  if (!exists(cfg)) {
    // 非 jpackage 结构（例如未来换成 graalvm 原生镜像），无法校验，交给启动握手兜底
    return ''
  }
  let text = ''
  try {
    text = fs.readFileSync(cfg, 'utf8')
  } catch {
    return '无法读取启动器配置'
  }
  if (!/app\.mainclass\s*=\s*lanlink\.server\.ApiMain/.test(text)) {
    return '主类不是 lanlink.server.ApiMain，属于旧版界面入口'
  }
  return ''
}

/** 按优先级列出「确认是 API 后端」的可执行文件 */
function candidates() {
  const list = []
  if (process.env.SPARSA_BACKEND) {
    list.push(process.env.SPARSA_BACKEND)
  }

  // 打包后：resources/backend/<exe>，由 build/package.mjs 生成（main-class = ApiMain）
  const resRoot = process.resourcesPath || ''
  if (resRoot) {
    for (const name of ['SparsaBackend.exe', 'sparsa-backend.exe']) {
      list.push(path.join(resRoot, 'backend', name))
    }
  }

  // 开发期不使用 LANLink-v2/dist、build/LANLink —— 那些是旧 Swing 版本的打包产物，
  // 开发期一律走 java -cp build/classes lanlink.server.ApiMain（见 buildCommand）。
  return list.filter(exists)
}

/** 列出被拒绝的入口，供「设置 → 后端探测」显示原因 */
function rejected() {
  const out = []
  const resRoot = process.resourcesPath || ''
  const dev = path.resolve(__dirname, '..', '..', 'LANLink-v2')
  const probe = [
    ...(resRoot ? [path.join(resRoot, 'backend', 'Sparsa.exe'), path.join(resRoot, 'backend', 'LANLink.exe')] : []),
    path.join(dev, 'dist', 'LANLink', 'LANLink.exe'),
    path.join(dev, 'build', 'LANLink', 'LANLink.exe'),
    path.join(dev, 'build', 'LANLink', 'app', 'LANLink.jar')
  ]
  for (const p of probe) {
    if (!exists(p)) continue
    const why = entryProblem(p)
    if (why) out.push({ path: p, reason: why })
  }
  return out
}

/** 用 `java -cp <classes> lanlink.server.ApiMain` 起后端（开发期不走打包产物） */
function javaClassesDir() {
  const dev = path.resolve(__dirname, '..', '..', 'LANLink-v2')
  const dirs = [
    path.join(dev, 'build', 'classes'),
    path.join(dev, 'build', 'classes_check')
  ]
  return dirs.filter((d) => exists(path.join(d, 'lanlink', 'server', 'ApiMain.class')))[0] || ''
}

function javaExe() {
  if (process.env.SPARSA_JAVA) return process.env.SPARSA_JAVA
  const jdk = 'E:\\develop\\jdk\\jdk-17\\bin\\java.exe'
  if (exists(jdk)) return jdk
  return 'java'
}

function buildCommand() {
  // 逐项校验，跳过「看起来是后端、其实是旧 Swing 启动器」的文件
  for (const target of candidates()) {
    const why = entryProblem(target)
    if (why) continue
    if (target.toLowerCase().endsWith('.jar')) {
      return { cmd: javaExe(), args: ['-jar', target, '--api'], source: target }
    }
    return { cmd: target, args: ['--api'], source: target }
  }

  // 开发期唯一入口：直接跑 API 主类，完全不经过任何 jpackage 启动器
  const classes = javaClassesDir()
  if (classes) {
    return {
      cmd: javaExe(),
      args: ['-Dfile.encoding=UTF-8', '-cp', classes, 'lanlink.server.ApiMain', '--api'],
      source: classes
    }
  }
  return null
}

function setInfo(patch) {
  info = { ...info, ...patch }
  return info
}

/**
 * 拉起后端，最多等 timeoutMs。成功返回 http 模式，否则 mock/down。
 */
function start(timeoutMs = 9000) {
  return new Promise((resolve) => {
    if (child) {
      return resolve(info)
    }
    const built = buildCommand()
    if (!built) {
      setInfo({
        mode: 'mock',
        baseUrl: '',
        token: '',
        pid: 0,
        error: '',
        source: '未找到 Java 后端，使用内置演示数据'
      })
      return resolve(info)
    }

    let settled = false
    const finish = (patch) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      resolve(setInfo(patch))
    }

    /**
     * 超时必须杀掉子进程。
     * 早先只把状态改成 down 而不结束进程，万一拉起的是旧 Swing 启动器
     * （它不打印就绪信号、只弹窗口），那个窗口会一直留在桌面上，
     * 表现为「点了重启后端就冒出一个 Java 界面」。
     */
    const timer = setTimeout(() => {
      killChild()
      finish({
        mode: 'down',
        error: `后端启动超时（${Math.round(timeoutMs / 1000)} 秒内没有就绪信号），已结束该进程`,
        source: built.source
      })
    }, timeoutMs)

    try {
      child = spawn(built.cmd, built.args, {
        cwd: path.dirname(built.source),
        windowsHide: true,
        stdio: ['ignore', 'pipe', 'pipe'],
        env: { ...process.env, SPARSA_HEADLESS: '1' }
      })
    } catch (e) {
      finish({ mode: 'down', error: String(e.message || e), source: built.source })
      return
    }

    let stdout = ''
    child.stdout.on('data', (buf) => {
      stdout += buf.toString('utf8')
      const m = stdout.match(PORT_LINE)
      if (m && !settled) {
        const tokenLine = stdout.match(TOKEN_LINE)
        setInfo({
          mode: 'http',
          baseUrl: `http://127.0.0.1:${m[1]}`,
          token: tokenLine ? tokenLine[1] : '',
          pid: child.pid,
          error: '',
          source: built.source
        })
        finish(info)
      }
    })
    child.stderr.on('data', (buf) => {
      const text = buf.toString('utf8')
      if (!settled && /Exception|错误|Error/.test(text)) {
        setInfo({ error: text.slice(0, 400) })
      }
    })
    child.on('exit', (code) => {
      child = null
      if (!settled) {
        finish({
          mode: 'down',
          error: `后端进程退出（code=${code}）${info.error ? '：' + info.error : ''}`,
          source: built.source
        })
      } else if (info.mode === 'http') {
        setInfo({ mode: 'down', error: `后端进程已退出（code=${code}）`, pid: 0 })
      }
    })
    child.on('error', (e) => {
      finish({ mode: 'down', error: String(e.message || e), source: built.source })
    })
  })
}

/** 结束子进程（不论它是什么）。供超时与停止共用。 */
function killChild() {
  if (!child) return
  try {
    child.kill()
  } catch {
    /* 进程可能已退出 */
  }
  child = null
}

function stop() {
  if (!child) return
  killChild()
  if (info.mode === 'http' || info.mode === 'down') {
    setInfo({ mode: 'mock', pid: 0, baseUrl: '', token: '' })
  }
}

async function restart() {
  stop()
  return start()
}

function current() {
  return info
}

function logDir() {
  return path.join(os.tmpdir(), 'sparsa-desktop')
}

module.exports = {
  start,
  stop,
  restart,
  current,
  logDir,
  candidates,
  rejected,
  entryProblem,
  javaClassesDir,
  // 供自检脚本断言后端入口的选择结果（不用于运行时）
  buildCommand
}
