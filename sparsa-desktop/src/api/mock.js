/**
 * 演示模式（mock）：后端未启动时让界面完整可用。
 *
 * 存在的意义不是「画假页面」，而是：
 *   1. 前端可以脱离 Java 单独开发、单独验收；
 *   2. UI 的每个状态（未连接/建房中/有成员/中继/错误）都能被复现，不用真的组网；
 *   3. 接上真后端后，这个文件整体删掉即可，业务代码零改动。
 *
 * 内部维护一个极小的状态机，模拟内核行为：建房 → 5 秒后有人接入 → 断开。
 */
import * as F from './fixtures'
import { parseInvite, parseConfigText } from '@/utils/invite'

const PORT = 41273
const HOST_IP = '10.144.0.1'
const JOINER_IP = '10.144.0.37'
const RELAY_JOINER_IP = '10.144.0.112'

const state = {
  phase: 'idle', // idle | hosting | connected
  role: null,
  myIp: '',
  listenPort: 0,
  invite: null,
  relayUri: '',
  noP2p: false,
  usePublicNodes: true,
  members: [],
  logs: [...F.LOG_SAMPLE],
  timers: []
}

function log(line) {
  const t = new Date()
  const hh = String(t.getHours()).padStart(2, '0')
  const mm = String(t.getMinutes()).padStart(2, '0')
  const ss = String(t.getSeconds()).padStart(2, '0')
  state.logs.push(`[${hh}:${mm}:${ss}] ${line}`)
  if (state.logs.length > 500) state.logs.splice(0, state.logs.length - 500)
}

function clearTimers() {
  state.timers.forEach(clearTimeout)
  state.timers = []
}

function reset() {
  clearTimers()
  state.phase = 'idle'
  state.role = null
  state.myIp = ''
  state.listenPort = 0
  state.invite = null
  state.members = []
}

function active() {
  return state.phase !== 'idle'
}

function sessionDto() {
  const kernel = {
    noP2p: state.noP2p,
    usePublicNodes: state.usePublicNodes,
    relayUri: state.relayUri,
    peers: state.noP2p
      ? [state.relayUri].filter(Boolean)
      : [...(state.usePublicNodes ? F.PUBLIC_NODES.nodes : []), ...(state.relayUri ? [state.relayUri] : [])]
  }
  // 模拟「界面改了参数但内核没重启」的不一致：只有建房之后才可能出现
  const divergence =
    state.phase === 'hosting' && typeof state.uiNoP2p === 'boolean' && state.uiNoP2p !== state.noP2p
      ? '界面已勾选「强制统一中继」，但内核仍在直连模式运行。请停止后重新创建房间。'
      : null

  return {
    active: active(),
    role: state.role,
    myIp: state.myIp,
    listenPort: state.listenPort,
    invite: state.invite,
    relayUri: state.relayUri,
    noP2p: state.noP2p,
    usePublicNodes: state.usePublicNodes,
    kernel,
    divergence,
    publicIp: F.NETWORK_INFO.publicIp,
    nat: F.NETWORK_INFO.nat,
    portMappingOk: F.NETWORK_INFO.portMappingOk,
    hardNat: F.NETWORK_INFO.hardNat,
    startedAt: state.startedAt || null,
    warnings: []
  }
}

function profileDto(query) {
  const mode = query.mode || (state.noP2p ? 'relay' : 'direct')
  const relay = query.relayUri ?? state.relayUri
  const secret = state.invite?.secret || 'k7m2q9x3rt5vb6n'
  const lanIp = F.NETWORK_INFO.lanIp

  const directPeers = [
    `tcp://${lanIp}:${PORT}`,
    `tcp://${F.NETWORK_INFO.publicIp}:${PORT}`,
    ...F.PUBLIC_NODES.nodes
  ]
  const relayPeers = [relay || 'tcp://183.230.36.171:11010']

  const peers = mode === 'relay' ? relayPeers : directPeers

  const toml = [
    'instance_name = "sparsa-phone"',
    'instance_id = "9f2c1a77-3c4e-4a1b-9d55-0e7b6c8a2f10"',
    'ipv4 = "10.144.0.9"',
    'dhcp = false',
    '',
    '[network_identity]',
    'network_name = "lanlink"',
    `network_secret = "${secret}"`,
    '',
    ...peers.flatMap((uri) => ['[[peer]]', `uri = "${uri}"`, '']),
    '[flags]',
    ...(mode === 'relay'
      ? [
          'latency_first = true',
          'disable_p2p = true',
          'disable_udp_hole_punching = true',
          'disable_tcp_hole_punching = true'
        ]
      : ['latency_first = true'])
  ].join('\n')

  return {
    mode,
    /** 扫码 / 微信粘贴用的整段文本（带说明与分隔标记，可反向解析） */
    carryText: [
      '───── Sparsa 零星 配置 v1 ─────',
      `mode: ${mode}`,
      'ipv4: 10.144.0.9',
      `secret: ${secret}`,
      '',
      '使用方法：',
      '1. 全选复制这段文字',
      '2. 手机记事本粘贴，另存为 sparsa.toml',
      '3. 在 EasyTier → 导入配置文件 选择它',
      '',
      '─── 配置开始 ───',
      toml,
      '─── 配置结束 ───'
    ].join('\n'),
    /** 纯 TOML，保存成文件时用它 */
    toml,
    tips: mode === 'relay' ? F.PHONE_TIPS_RELAY : F.PHONE_TIPS_DIRECT,
    meta: {
      networkName: 'lanlink',
      secret,
      initialPeer: peers[0],
      peerCount: peers.length,
      lanIp,
      myIp: '10.144.0.9',
      secondIp: '10.144.0.10'
    },
    divergence: sessionDto().divergence,
    ipCollisionHint: '虚拟 IPv4 由配置里的固定地址决定，多台手机都用 .9 时会撞车。多台手机请依次改为 .9 / .10 / .11。'
  }
}

/* ── 路由表 ───────────────────────────────────────────── */

const routes = {
  'GET /system/info': () => ({ ...F.SYSTEM_INFO, engineReady: active() }),

  'GET /system/preflight': () => F.PREFLIGHT,

  'GET /system/network': () => ({
    ...F.NETWORK_INFO,
    virtualNicUp: active()
  }),

  'GET /system/public-nodes': () => F.PUBLIC_NODES,

  'POST /system/firewall': () => ({ ok: true, detail: '入站规则已存在' }),

  'GET /session': () => sessionDto(),

  'POST /session/host': (body) => {
    reset()
    state.phase = 'hosting'
    state.role = 'HOST'
    state.myIp = HOST_IP
    state.listenPort = PORT
    state.relayUri = body?.relayUri || ''
    state.noP2p = !!body?.noP2p
    state.uiNoP2p = state.noP2p
    state.usePublicNodes = body?.usePublicNodes !== false
    state.startedAt = new Date().toISOString()
    state.invite = {
      host: F.NETWORK_INFO.publicIp,
      port: PORT,
      secret: 'k7m2q9x3rt5vb6n',
      code: `${F.NETWORK_INFO.publicIp}:${PORT}#k7m2q9x3rt5vb6n`,
      displayHost: `${F.NETWORK_INFO.publicIp}:${PORT}`,
      roomName: 'lanlink'
    }
    state.members = [F.MEMBERS_HOSTING.members[0]]
    log('正在创建房间，监听端口 ' + PORT)
    log('内核命令: easytier-core.exe --network-name lanlink -i 10.144.0.1/24')
    state.timers.push(
      setTimeout(() => {
        if (state.phase !== 'hosting') return
        log('内核就绪，RPC 127.0.0.1:52311')
        state.members = F.MEMBERS_HOSTING.members
        log('成员接入：10.144.0.37 (P2P)')
      }, 4000)
    )
    return sessionDto()
  },

  'POST /session/join': (body) => {
    reset()
    const inv = body?.invite || (body?.code ? parseInvite(body.code).invite : null)
    if (!inv) throw new Error('邀请码无效')
    state.phase = 'connected'
    state.role = 'JOINER'
    state.myIp = JOINER_IP
    state.listenPort = 42001
    state.relayUri = body?.relayUri || ''
    state.noP2p = !!body?.noP2p
    state.usePublicNodes = body?.usePublicNodes !== false
    state.startedAt = new Date().toISOString()
    state.invite = inv
    state.members = [
      { ...F.MEMBERS_HOSTING.members[0], ipv4: HOST_IP, hostname: 'sparsa-host', self: false },
      { ...F.MEMBERS_HOSTING.members[1], ipv4: JOINER_IP, hostname: 'sparsa-studio', self: true, cost: 'Local' }
    ]
    log('正在连接 ' + inv.displayHost)
    log('本机虚拟 IP: ' + JOINER_IP)
    state.timers.push(
      setTimeout(() => {
        if (state.phase !== 'connected') return
        log('链路建立，已看到主机 ' + HOST_IP)
      }, 3000)
    )
    return sessionDto()
  },

  'DELETE /session': () => {
    reset()
    log('虚拟局域网连接已停止')
    return { ok: true }
  },

  'POST /session/ping': (body) => {
    const want = String(body?.ip || '')
    if (state.members.length < 2) {
      return { ok: false, ms: null, detail: '暂无其他成员接入，请等待对方使用邀请码连接后重试。' }
    }
    const other = state.members.find((m) => !m.self && (!want || m.ipv4 === want))
    if (!other) {
      return {
        ok: false,
        ms: null,
        target: want,
        detail: '该地址不在当前成员列表中，请刷新后重试。'
      }
    }
    const ms = state.noP2p ? 186 : 3
    return { ok: true, ms, target: other.ipv4, detail: `与 ${other.ipv4} 连通（${ms} ms）` }
  },

  'POST /session/public-ip': () => ({
    changed: false,
    publicIp: F.NETWORK_INFO.publicIp,
    invite: state.invite
  }),

  'GET /members': () => {
    const list = state.members || []
    return {
      members: list,
      count: list.length,
      onlineCount: list.length
    }
  },

  'GET /logs': (body, query) => {
    const n = Number(query?.tail || 200)
    return { lines: state.logs.slice(-n) }
  },

  'GET /phone/profile': (body, query) => profileDto(query || {}),

  'POST /config/parse': (body) => {
    const parsed = parseConfigText(body?.text)
    return {
      ok: parsed.ok,
      mode: parsed.mode,
      toml: parsed.toml,
      ipv4: parsed.ipv4,
      relay: parsed.relay,
      secret: parsed.secret,
      networkName: parsed.networkName,
      problems: parsed.problems,
      invite: parsed.relay
        ? {
            host: parsed.relay.replace(/^\w+:\/\//, '').split(':')[0],
            port: Number(parsed.relay.split(':').pop()),
            secret: parsed.secret,
            displayHost: parsed.relay.replace(/^\w+:\/\//, ''),
            code: `${parsed.relay.replace(/^\w+:\/\//, '')}#${parsed.secret}`
          }
        : null
    }
  },

  'POST /invite/parse': (body) => {
    const r = parseInvite(body?.text)
    if (!r.ok) throw new Error(r.error)
    return { invite: r.invite }
  },

  'GET /diagnostics': () => ({ text: F.DIAGNOSTICS_TEXT })
}

/** 记录「界面上勾了什么」，用于演示参数漂移告警 */
export function mockNoteUiIntent(patch) {
  Object.assign(state, patch)
}

export async function handle(method, path, { body, query } = {}) {
  const key = `${method} ${path}`
  const fn = routes[key]
  if (!fn) throw new Error(`演示模式未实现接口：${key}`)

  // 模拟真实网络往返，避免界面闪现
  const delay = 60 + Math.random() * 120
  await new Promise((r) => setTimeout(r, delay))
  return fn(body, query)
}

export function mockReset() {
  reset()
}
