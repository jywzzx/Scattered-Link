/**
 * 编译 Java 后端到 LANLink-v2/build/classes。
 *
 * 用途：Electron 主进程（electron/backend.cjs）在开发模式下会去
 * LANLink-v2/build/classes 里找 lanlink/server/ApiMain.class，
 * 找到就直接用它拉起后端。所以本地联调前要跑一次这个脚本。
 *
 * 不需要在 IDEA 里启动任何服务，也不需要手动开终端跑 java。
 *
 * 注意：不写 @sources.txt 参数文件 —— javac 读参数文件用的是系统 ANSI 编码，
 * 仓库路径含中文时会 MalformedInputException；源文件直接作为命令行参数传即可。
 *
 * 用法：npm run backend:build
 */
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(here, '..')
const JAVA_ROOT = path.resolve(ROOT, '..', 'LANLink-v2')
const SRC = path.join(JAVA_ROOT, 'src', 'main', 'java')
const OUT = path.join(JAVA_ROOT, 'build', 'classes')
const ZXING = path.join(JAVA_ROOT, 'lib', 'zxing-core-3.5.3.jar')

const JAVA_HOME = process.env.JAVA_HOME || 'E:\\develop\\jdk\\jdk-17'
const JAVAC = path.join(JAVA_HOME, 'bin', 'javac.exe')

function collect(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) collect(p, out)
    else if (e.name.endsWith('.java')) out.push(p)
  }
  return out
}

if (!fs.existsSync(JAVAC)) {
  console.error(`找不到 javac：${JAVAC}`)
  console.error('请把 JAVA_HOME 指向 JDK 17 的安装目录后重试。')
  process.exit(1)
}

const sources = collect(SRC)
console.log(`源文件 ${sources.length} 个`)
console.log(`输出目录 ${OUT}`)

fs.mkdirSync(OUT, { recursive: true })

const r = spawnSync(JAVAC, ['-encoding', 'UTF-8', '-cp', ZXING, '-d', OUT, ...sources], {
  encoding: 'utf8'
})

if (r.status !== 0) {
  const detail = ((r.stderr || '') + (r.stdout || '')).trim()
  console.error('编译失败：')
  console.error(detail || `（javac 退出码 ${r.status}，无输出）`)
  process.exit(r.status || 1)
}

const marker = path.join(OUT, 'lanlink', 'server', 'ApiMain.class')
if (!fs.existsSync(marker)) {
  console.error('编译完成，但未找到 lanlink/server/ApiMain.class')
  process.exit(1)
}

console.log('编译通过，后端就绪：' + path.relative(path.resolve(ROOT, '..'), marker))
