/**
 * 前端自检（不依赖浏览器与后端）。
 *
 * 三件事：
 *   1. 纯逻辑模块的真实断言 —— 邀请码解析、配置解析、二维码容量估算
 *   2. 构建产物完整性 —— dist 里的路由 chunk、资源引用是否都在
 *   3. 品牌一致性 —— 不允许再出现旧英文名（命名规则：英文 Sparsa，中文 零星）
 *
 * 运行：npm run verify（需先 npm run build）
 */
import { readFileSync, readdirSync, existsSync } from 'node:fs'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const root = join(here, '..')

let pass = 0
const fails = []

function ok(name, cond, extra = '') {
  if (cond) {
    pass++
  } else {
    fails.push(name + (extra ? ` — ${extra}` : ''))
  }
}

function eq(name, actual, expected) {
  ok(name, JSON.stringify(actual) === JSON.stringify(expected), `实际 ${JSON.stringify(actual)}，期望 ${JSON.stringify(expected)}`)
}

/* ── 1. 邀请码解析 ─────────────────────────────────────── */
const { parseInvite, normalizeInvite, parseConfigText, extractToml } = await import(
  'file://' + join(root, 'src', 'utils', 'invite.js').replaceAll('\\', '/')
)

eq('全角冒号归一化', normalizeInvite('1.2.3.4：11010'), '1.2.3.4:11010')
eq('全角井号归一化', normalizeInvite('a＃b'), 'a#b')
eq('不间断空格归一化', normalizeInvite('a\u00A0b'), 'a b')

const inv = parseInvite('113.133.208.7:41273#k7m2q9x3rt5vb6n')
ok('标准邀请码可解析', inv.ok)
eq('解析出主机', inv.invite?.host, '113.133.208.7')
eq('解析出端口', inv.invite?.port, 41273)
eq('解析出密钥', inv.invite?.secret, 'k7m2q9x3rt5vb6n')

ok('全角邀请码可解析', parseInvite('113.133.208.7：41273＃k7m2q9x3rt5vb6n').ok)
ok('缺端口应报错', !parseInvite('113.133.208.7#k7m2q9x3rt5vb6n').ok)
ok('空输入应报错', !parseInvite('').ok)
ok('密钥过短应报错', !parseInvite('1.2.3.4:11010#short').ok)
ok('端口越界应报错', !parseInvite('1.2.3.4:70000#k7m2q9x3rt5vb6n').ok)
ok('聊天文本中夹带邀请码可抠出', parseInvite('房间号来了 113.133.208.7:41273#k7m2q9x3rt5vb6n 快连').ok)

/* ── 2. 配置文本解析 ──────────────────────────────────── */
const RELAY_TOML = `ipv4 = "10.144.0.9/24"
latency_first = true
disable_p2p = true

[network_identity]
network_name = "lanlink"
network_secret = "k7m2q9x3rt5vb6n"

[[peer]]
uri = "tcp://37.61.44.9:11010"
`

const DIRECT_TOML = `ipv4 = "10.144.0.9/24"

[network_identity]
network_name = "lanlink"
network_secret = "k7m2q9x3rt5vb6n"

[[peer]]
uri = "tcp://192.168.1.24:41273"

[[peer]]
uri = "tcp://183.230.36.171:11010"
`

const relayCfg = parseConfigText(RELAY_TOML)
ok('中继配置识别为 relay', relayCfg.mode === 'relay')
eq('中继配置网络名称', relayCfg.networkName, 'lanlink')
eq('中继配置初始节点', relayCfg.relay, 'tcp://37.61.44.9:11010')
eq('中继配置虚拟 IP', relayCfg.ipv4, '10.144.0.9/24')
ok('中继配置无问题项', relayCfg.problems.length === 0, relayCfg.problems.join('/'))

const directCfg = parseConfigText(DIRECT_TOML)
ok('直连配置识别为 direct', directCfg.mode === 'direct')
ok('直连配置有多个节点', directCfg.peers.length === 2)

const wrapped = `───── Sparsa 零星 配置 v1 ─────
mode: relay

─── 配置开始 ───
${RELAY_TOML}
─── 配置结束 ───
`
ok('带标记文本能抠出 TOML', extractToml(wrapped).includes('network_secret'))
eq('带标记文本解析出的节点', parseConfigText(wrapped).relay, 'tcp://37.61.44.9:11010')

const badName = parseConfigText(RELAY_TOML.replace('"lanlink"', '"other"'))
ok('网络名称不符要报问题', !badName.ok && badName.problems.some((p) => p.includes('lanlink')))

const noSecret = parseConfigText(RELAY_TOML.replace(/network_secret.*\n/, ''))
ok('缺密钥要报问题', !noSecret.ok)

/* ── 3. 二维码容量 ───────────────────────────────────── */
const { qrCost, pickQrLevel, qrFits, SAFE_COST } = await import(
  'file://' + join(root, 'src', 'utils', 'qr.js').replaceAll('\\', '/')
)

eq('ASCII 成本', qrCost('abcd'), 4)
eq('中文按 3 计', qrCost('零星'), 6)
eq('混合成本', qrCost('a零'), 4)
ok('短文本用最高纠错', pickQrLevel(100) === 'H')
ok('中等文本降为 M', pickQrLevel(400) === 'M')
ok('长文本降为 L', pickQrLevel(1000) === 'L')
ok('极长文本超限', !qrFits('零'.repeat(600)))
ok('正常配置在限内', qrFits(wrapped))
ok('上限常量合理', SAFE_COST > 500 && SAFE_COST < 2000)

/* ── 4. 构建产物 ─────────────────────────────────────── */
const dist = join(root, 'dist')
ok('dist 存在', existsSync(dist))
const indexHtml = existsSync(join(dist, 'index.html')) ? readFileSync(join(dist, 'index.html'), 'utf8') : ''
ok('index.html 存在', indexHtml.length > 0)
ok('入口脚本已注入', /assets\/index-[\w-]+\.js/.test(indexHtml))
ok('样式已注入', /assets\/index-[\w-]+\.css/.test(indexHtml))

const assets = existsSync(join(dist, 'assets')) ? readdirSync(join(dist, 'assets')) : []
for (const view of ['HomeView', 'HostView', 'JoinView', 'PhoneView', 'DiagnosticsView', 'LogsView', 'SettingsView']) {
  ok(`路由 chunk 存在：${view}`, assets.some((f) => f.startsWith(view) && f.endsWith('.js')))
}
for (const comp of ['QrCanvas', 'MemberTable', 'CopyField', 'TipList']) {
  ok(`组件 chunk 存在：${comp}`, assets.some((f) => f.startsWith(comp) && f.endsWith('.js')))
}

/* ── 5. 品牌一致性 ───────────────────────────────────── */
const scanDirs = ['src', 'electron']
const banned = [/lingxing/i, /LingXing/]
const offenders = []

function walk(dir) {
  for (const name of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, name.name)
    if (name.isDirectory()) walk(p)
    else if (/\.(js|mjs|cjs|vue|json|scss|html)$/.test(name.name)) {
      const text = readFileSync(p, 'utf8')
      for (const re of banned) {
        if (re.test(text)) offenders.push(p.replace(root, ''))
      }
    }
  }
}
for (const d of scanDirs) {
  if (existsSync(join(root, d))) walk(join(root, d))
}
ok('无旧英文名残留', offenders.length === 0, offenders.join(', '))

const brand = readFileSync(join(root, 'src', 'config', 'brand.js'), 'utf8')
ok('品牌名为 Sparsa', /name:\s*'Sparsa'/.test(brand))
ok('中文名保留零星', /nameZh:\s*'零星'/.test(brand))

/* ── 6. 视图绑定的正确性 ─────────────────────────────── */
// v-model 绑到 const reactive 对象时，Vue 编译成 `opts = $event` 的赋值语句，
// const 不允许赋值，运行时报 Assignment to constant variable，控件点了会弹回。
// 这类问题编译期不报错、只有交互时才暴露，因此固定成断言。
const viewsDir = join(root, 'src', 'views')
const viewFiles = existsSync(viewsDir) ? readdirSync(viewsDir).filter((f) => f.endsWith('.vue')) : []
const badVModel = []
for (const f of viewFiles) {
  const text = readFileSync(join(viewsDir, f), 'utf8')
  // 跳过注释行，否则「说明为什么不能这样写」的注释会被自己判成违规
  const code = text
    .split('\n')
    .filter((line) => {
      const t = line.trim()
      return !(t.startsWith('*') || t.startsWith('//') || t.startsWith('/*') || t.startsWith('<!--'))
    })
    .join('\n')
  if (/v-model="(opts|relayOpts|formData|params)"/.test(code)) badVModel.push(f)
}
ok('视图未把 v-model 绑到 reactive 对象', badVModel.length === 0, badVModel.join(', '))

/* ── 6b. 文案规范：禁用标点与中文口号 ───────────────── */
// 规范要求：不用省略号制造语气、不用分号、不用破折号、不写中文口号。
// 这些只能靠人读，容易在后续改动里回流，因此做成静态检查。
const copyBad = /[…；]|——/
const copyOffenders = []
function scanCopy(dir) {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name)
    if (e.isDirectory()) {
      scanCopy(p)
      continue
    }
    if (!/\.(vue|js)$/.test(e.name)) continue
    const lines = readFileSync(p, 'utf8').split('\n')
    lines.forEach((line, i) => {
      const t = line.trim()
      // 注释与正则定义不算文案
      if (t.startsWith('*') || t.startsWith('//') || t.startsWith('/*')) return
      if (t.includes('CODE_RE') || t.includes('RegExp')) return
      if (copyBad.test(line) && /[\u4e00-\u9fff]/.test(line)) {
        copyOffenders.push(`${p.replace(root, '')}:${i + 1}`)
      }
    })
  }
}
scanCopy(join(root, 'src'))
ok('文案不含省略号、分号、破折号', copyOffenders.length === 0, copyOffenders.slice(0, 4).join(', '))

const brandSrc = readFileSync(join(root, 'src', 'config', 'brand.js'), 'utf8')
ok('中文口号已删除', !brandSrc.includes('散落的设备'))
ok('英文短句保留', brandSrc.includes('Scattered devices'))

/* ── 7. 后端入口选择（防止回退到旧 Swing 启动器） ───── */
// 曾经的缺陷：候选列表里含 LANLink-v2/dist/LANLink/LANLink.exe，
// 它的 app.mainclass 是 lanlink.Main（Swing 窗口），不认 --api，
// 于是「重启后端」会弹出旧界面。这里把这条件固定成断言。
const requireCjs = createRequire(import.meta.url)
const backend = requireCjs(join(root, 'electron', 'backend.cjs'))

const picked = backend.buildCommand()
ok('能选出后端入口', !!picked)
ok(
  '候选列表不含 LANLink.exe',
  !backend.candidates().some((p) => /LANLink\.exe$/i.test(p)),
  backend.candidates().join(', ')
)
ok(
  '入口命令不是 LANLink.exe',
  !/LANLink\.exe$/i.test(String(picked?.cmd || '')),
  String(picked?.cmd)
)
ok(
  '入口是 ApiMain 或 API 后端',
  /lanlink\.server\.ApiMain/.test([picked?.cmd, ...(picked?.args || [])].join(' ')) ||
    /SparsaBackend\.exe$/i.test(String(picked?.cmd || '')),
  [picked?.cmd, ...(picked?.args || [])].join(' ')
)
// 本机若存在旧 Swing 打包产物，它必须被判为不可用
const oldLauncher = join(root, '..', 'LANLink-v2', 'dist', 'LANLink', 'LANLink.exe')
if (existsSync(oldLauncher)) {
  ok('旧 Swing 启动器被判为不可用', backend.entryProblem(oldLauncher) !== '', '期望返回拒绝原因')
} else {
  ok('本机无旧启动器，跳过该项', true)
}

/* ── 汇总 ────────────────────────────────────────────── */
console.log(`pass ${pass} / fail ${fails.length}`)
if (fails.length) {
  for (const f of fails) console.log('  FAIL ' + f)
  process.exit(1)
}
