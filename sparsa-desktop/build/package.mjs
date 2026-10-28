/**
 * 便携包打包脚本。
 *
 * 目标：产出一个「解压 → 双击 Sparsa.exe → 能用」的目录，对方不需要装 Node、Java、VC 运行库，
 * 也不需要自己起任何服务。
 *
 * 组成：
 *   Sparsa.exe                  Electron 运行时（改名后的 electron.exe）
 *   resources/app/              前端产物 + 主进程脚本
 *   resources/backend/          Java 后端（jpackage app-image，自带 JRE）
 *   resources/backend/engine/   EasyTier 内核与 Wintun 驱动
 *
 * 不用 electron-builder 的原因：它要联网拉 winCodeSign / nsis，
 * 而这几步在受限网络里最容易失败；我们只需要「绿色版目录」，手工组装更可控、更快。
 *
 * 用法：node build/package.mjs
 */
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(here, '..')                 // sparsa-desktop
const REPO = path.resolve(ROOT, '..')                 // 仓库根
const JAVA_HOME = process.env.JAVA_HOME || 'E:\\develop\\jdk\\jdk-17'
const BIN = (n) => path.join(JAVA_HOME, 'bin', n + '.exe')

const VERSION = JSON.parse(fs.readFileSync(path.join(ROOT, 'package.json'), 'utf8')).version
const BUILD = path.join(ROOT, 'build-tmp')
const OUT = path.join(ROOT, 'release')
const STAGE = path.join(BUILD, 'stage')
const PKG_DIR = path.join(STAGE, `Sparsa-${VERSION}-win64`)
const ZIP = path.join(OUT, `Sparsa-${VERSION}-win64.zip`)

const log = (...a) => console.log(...a)
const mb = (n) => (n / 1048576).toFixed(1) + ' MB'

/**
 * 统一的子进程调用。
 *
 * 不用 stdio:'inherit'：父进程 stdout 被 shell 重定向到文件时，
 * inherit 会让子进程与父进程共写同一个句柄，在 Windows 上偶发无限等待
 * （实测 jpackage 卡住 8 分钟无任何输出、也没有进程存在）。
 * 改为捕获输出，既能避免这个问题，也便于失败时把 stderr 打出来。
 */
function run(bin, args, label, { timeout = 10 * 60 * 1000 } = {}) {
  const t0 = Date.now()
  const r = spawnSync(bin, args, { encoding: 'utf8', timeout, maxBuffer: 32 * 1024 * 1024 })
  const dt = ((Date.now() - t0) / 1000).toFixed(1)
  if (r.error) throw new Error(`${label} 无法执行：${r.error.message}`)
  if (r.status !== 0) {
    const detail = ((r.stderr || '') + (r.stdout || '')).trim().slice(0, 800)
    throw new Error(`${label} 失败（exit ${r.status}，${dt}s）${detail ? '：\n' + detail : ''}`)
  }
  log(`    ${label} ok ${dt}s`)
  return r.stdout || ''
}

function rm(p) {
  fs.rmSync(p, { recursive: true, force: true })
}

/** 递归复制目录，带真实字节统计 */
function cp(src, dst, filter) {
  fs.mkdirSync(dst, { recursive: true })
  for (const e of fs.readdirSync(src, { withFileTypes: true })) {
    const s = path.join(src, e.name)
    const d = path.join(dst, e.name)
    if (filter && !filter(s, e)) continue
    if (e.isDirectory()) cp(s, d, filter)
    else fs.copyFileSync(s, d)
  }
}

function du(dir) {
  let total = 0
  let files = 0
  const walk = (p) => {
    for (const e of fs.readdirSync(p, { withFileTypes: true })) {
      const f = path.join(p, e.name)
      if (e.isDirectory()) walk(f)
      else {
        total += fs.statSync(f).size
        files++
      }
    }
  }
  walk(dir)
  return { total, files }
}

/* ── 1. 编译 Java 并打 jar（只含 core + server，不含 Swing 界面） ── */
function buildBackendJar() {
  log('\n[1/5] 编译 Java 后端')
  const javaRoot = path.join(REPO, 'LANLink-v2')
  const srcRoot = path.join(javaRoot, 'src', 'main', 'java')
  const classes = path.join(BUILD, 'classes')
  rm(classes)

  const sources = []
  const walk = (p) => {
    for (const e of fs.readdirSync(p, { withFileTypes: true })) {
      const f = path.join(p, e.name)
      if (e.isDirectory()) walk(f)
      else if (e.name.endsWith('.java')) sources.push(f)
    }
  }
  walk(srcRoot)

  // 不能写成 @sources.txt：javac 读参数文件用的是系统 ANSI 编码，
  // 仓库路径里有中文，会直接 MalformedInputException。
  // 只有十几个源文件，直接作为命令行参数传即可（远低于 32k 上限）。
  const zxing = path.join(javaRoot, 'lib', 'zxing-core-3.5.3.jar')
  run(BIN('javac'), ['-encoding', 'UTF-8', '-cp', zxing, '-d', classes, ...sources], 'javac 编译')

  // 打 jar：排除 Swing 界面与 Main，减少体积并避免误加载
  const jarStage = path.join(BUILD, 'jar-stage')
  rm(jarStage)
  for (const pkg of ['core', 'server']) {
    cp(path.join(classes, 'lanlink', pkg), path.join(jarStage, 'lanlink', pkg))
  }

  // jpackage 会把 --input 目录里的**所有文件**复制进 app/，
  // 所以 jar 必须单独放一个只有它的目录，否则 .class 会散落在 app/ 下
  const jarInput = path.join(BUILD, 'jar-input')
  rm(jarInput)
  fs.mkdirSync(jarInput, { recursive: true })

  const jarFile = path.join(jarInput, 'sparsa-server.jar')
  run(BIN('jar'), ['--create', '--file', jarFile, '-C', jarStage, '.'], 'jar 打包')
  log('    jar ' + mb(fs.statSync(jarFile).size) + ' · ' + sources.length + ' 个源文件')
  return jarFile
}

/* ── 2. jpackage 生成自带 JRE 的后端 app-image ── */
function buildBackendImage(jarFile) {
  log('\n[2/5] jpackage 生成后端（自带 JRE）')
  const dest = path.join(BUILD, 'backend')
  rm(dest)
  fs.mkdirSync(dest, { recursive: true })

  run(
    BIN('jpackage'),
    [
      '--type', 'app-image',
      '--name', 'SparsaBackend',
      '--app-version', VERSION,
      '--vendor', 'Sparsa',
      '--description', 'Sparsa 零星 本地服务',
      '--input', path.join(BUILD, 'jar-input'),
      '--main-jar', 'sparsa-server.jar',
      '--main-class', 'lanlink.server.ApiMain',
      '--dest', dest,
      // 必须显式列出：jlink 只打包这里点名的模块，而 jdeps 探测不到
      // ApiServer 通过反射/服务加载使用的 jdk.httpserver。
      // 少一个模块的后果是运行期 NoClassDefFoundError: com/sun/net/httpserver/HttpServer，
      // 界面表现为「后端启动即退出、一直是演示模式」。
      '--add-modules',
      'java.base,java.logging,java.naming,java.net.http,java.management,jdk.httpserver,java.desktop,jdk.unsupported',
      '--java-options', '-Dfile.encoding=UTF-8',
      '--java-options', '-XX:+UseSerialGC',
      '--java-options', '-Xmx256m'
    ],
    'jpackage 生成后端'
  )

  const img = path.join(dest, 'SparsaBackend')
  log('    后端镜像 ' + mb(du(img).total))

  // 内核与驱动：Engine.locateCore() 会从 app/ 的上一级找 engine/
  const engineSrc = path.join(REPO, 'LANLink-v2', 'engine')
  cp(engineSrc, path.join(img, 'engine'))
  log('    已并入 engine/（' + mb(du(path.join(img, 'engine')).total) + '）')
  return img
}

/* ── 3. 组装便携目录 ── */
function assemble(backendImg) {
  log('\n[3/5] 组装便携目录')
  rm(STAGE)
  fs.mkdirSync(PKG_DIR, { recursive: true })

  // 3.1 Electron 运行时
  const electronDist = path.join(ROOT, 'node_modules', 'electron', 'dist')
  cp(electronDist, PKG_DIR, (s, e) => {
    // 默认应用与调试符号不需要
    if (e.name === 'default_app.asar') return false
    if (e.name.endsWith('.pdb')) return false
    return true
  })
  fs.renameSync(path.join(PKG_DIR, 'electron.exe'), path.join(PKG_DIR, 'Sparsa.exe'))
  log('    Electron 运行时已就位，可执行文件改为 Sparsa.exe')

  // 3.2 前端产物 + 主进程脚本
  const app = path.join(PKG_DIR, 'resources', 'app')
  fs.mkdirSync(app, { recursive: true })
  cp(path.join(ROOT, 'dist'), path.join(app, 'dist'))
  cp(path.join(ROOT, 'electron'), path.join(app, 'electron'))

  // 生产环境的 package.json：只要 main 与身份信息，不带 devDependencies
  fs.writeFileSync(
    path.join(app, 'package.json'),
    JSON.stringify(
      {
        name: 'Sparsa',
        productName: 'Sparsa',
        version: VERSION,
        description: 'Sparsa 零星 · 虚拟局域网',
        main: 'electron/main.cjs',
        author: 'Sparsa',
        license: 'MIT'
      },
      null,
      2
    ) + '\n',
    'utf8'
  )

  // 3.3 后端
  cp(backendImg, path.join(PKG_DIR, 'resources', 'backend'))
  log('    后端已并入 resources/backend')

  // 3.4 顶层说明
  fs.writeFileSync(path.join(PKG_DIR, '1-先看这里.txt'), QUICKSTART(VERSION), 'utf8')
  fs.writeFileSync(path.join(PKG_DIR, 'LICENSE-EasyTier.txt'), EASYTIER_LICENSE(), 'utf8')
}

/* ── 4. 压缩 ── */
function zipUp() {
  log('\n[4/5] 压缩')
  fs.mkdirSync(OUT, { recursive: true })
  rm(ZIP)
  const ps = path.join(BUILD, 'zip.ps1')
  // 脚本内容必须是纯 ASCII：Windows PowerShell 5.1 按系统 ANSI 读取 .ps1，
  // 中文路径写在脚本里会被读成乱码并报 DirectoryNotFoundException。
  // 路径改由命令行参数传入（argv 走 Unicode，不受影响）。
  fs.writeFileSync(
    ps,
    `param([string]$Src, [string]$Dst)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory($Src, $Dst, [System.IO.Compression.CompressionLevel]::Optimal, $true)
`,
    'ascii'
  )
  run(
    'powershell.exe',
    ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ps, STAGE, ZIP],
    '压缩',
    { timeout: 30 * 60 * 1000 }
  )
  log('    ' + path.basename(ZIP) + ' ' + mb(fs.statSync(ZIP).size))
}

/* ── 5. 汇总 ── */
function report() {
  log('\n[5/5] 结果')
  const all = du(PKG_DIR)
  log('  目录 ' + path.basename(PKG_DIR))
  log('    解压后 ' + mb(all.total) + ' / ' + all.files + ' 个文件')
  for (const sub of ['resources/app', 'resources/backend']) {
    const d = du(path.join(PKG_DIR, sub))
    log('    ' + sub.padEnd(20) + mb(d.total).padStart(9))
  }
  const top = fs
    .readdirSync(PKG_DIR)
    .filter((f) => f.endsWith('.exe') || f.endsWith('.txt'))
    .sort()
  log('  顶层文件: ' + top.join(', '))
  log('  压缩包: ' + ZIP)
}

const QUICKSTART = (v) => `Sparsa 零星 ${v} · 使用说明
================================

一、怎么用

1. 把整个文件夹解压到硬盘（不要直接在压缩包里双击运行）
2. 双击 Sparsa.exe
3. 系统询问管理员权限时选「是」（创建虚拟网卡需要）
4. 房主点「创建房间」，把邀请码发给对方；对方点「加入房间」粘贴邀请码

不需要安装 Java、Node.js 或任何运行库，也不用自己启动任何服务。
后端服务（SparsaBackend.exe）由主程序自动拉起，退出时自动关闭。

二、如果被安全软件拦截

虚拟网卡驱动（wintun.dll）是最常见的拦截对象，请将整个文件夹加入白名单。
若提示缺少 WinDivert64.sys / Packet.dll，同样是被拦截或未完整解压。

三、目录里都是什么

Sparsa.exe                     主程序，双击这个
resources\\app\\                界面（改不了，删了会白屏）
resources\\backend\\            本地服务，自带 Java 运行环境
resources\\backend\\engine\\     EasyTier 内核与虚拟网卡驱动

四、数据与日志

配置与窗口位置：%APPDATA%\\Sparsa
运行日志：      %LOCALAPPDATA%\\LANLink\\logs

五、卸载

删除整个文件夹即可。虚拟网卡驱动会在退出时自动卸载；
如需彻底清理，可在「设备管理器 → 网络适配器」中删除名为 wintun 的适配器。
`

const EASYTIER_LICENSE = () => `本程序内置 EasyTier v2.6.4 内核（easytier-core.exe / easytier-cli.exe）。
EasyTier 采用 Apache License 2.0 授权，详见 https://github.com/EasyTier/EasyTier

本程序界面与业务代码采用 MIT 授权。
Electron、Chromium 的授权信息见 resources 目录下的 LICENSE 与 LICENSES.chromium.html。
`

/* ── run ── */
fs.mkdirSync(BUILD, { recursive: true })
log('Sparsa 便携包构建 · 版本 ' + VERSION)
const jar = buildBackendJar()
const img = buildBackendImage(jar)
assemble(img)
zipUp()
report()
