/**
 * 邀请码与配置文本的前端解析。
 *
 * 为什么要在这里再写一遍：粘贴框需要「边输边给反馈」，每敲一个字都请求后端
 * 不现实。这里的规则与 Java 侧 Invite.normalize / parse、PhoneConfigText.parse
 * 保持等价 —— 只做「确定等价」的全角替换，宁可报错也不擅自改动用户地址。
 *
 * 最终判定仍以后端返回为准：前端解析只用于即时提示与按钮可用性。
 */

/** 全角 → 半角的等价替换（与 Invite.normalize 逐条对应） */
export function normalizeInvite(raw) {
  if (raw == null) return ''
  return String(raw)
    .replace(/：/g, ':')
    .replace(/＃/g, '#')
    .replace(/－/g, '-')
    .replace(/—/g, '-')
    .replace(/﹣/g, '-')
    .replace(/．/g, '.')
    .replace(/\u3000/g, ' ')
    .replace(/\u00A0/g, ' ')
    .trim()
}

const CODE_RE = /([0-9a-zA-Z[]+[0-9a-zA-Z.\-:[\]]*)#([^\s，。,;；]+)/

/**
 * 解析邀请码，形如 113.133.208.7:41273#k7m2q9x3rt5vb6n
 * @returns {{ok: boolean, invite?: object, error?: string}}
 */
export function parseInvite(raw) {
  const s = normalizeInvite(raw)
  if (!s) return { ok: false, error: '缺少邀请码' }

  const m = s.match(CODE_RE)
  if (!m) {
    return { ok: false, error: '格式应为 地址:端口#房间密码，例如 113.133.208.7:41273#k7m2q9x3rt5vb6n' }
  }

  const hostPort = m[1]
  const secret = m[2]
  const idx = hostPort.lastIndexOf(':')
  if (idx <= 0) return { ok: false, error: '缺少端口号' }

  const host = hostPort.slice(0, idx)
  const port = Number(hostPort.slice(idx + 1))
  if (!Number.isInteger(port) || port <= 0 || port > 65535) {
    return { ok: false, error: '端口号无效，应为 1-65535' }
  }
  if (secret.length < 12) {
    return { ok: false, error: `房间密码至少 12 位，当前 ${secret.length} 位` }
  }

  return {
    ok: true,
    invite: {
      host,
      port,
      secret,
      code: `${host}:${port}#${secret}`,
      displayHost: `${host}:${port}`,
      roomName: 'lanlink'
    }
  }
}

const MARK_BEGIN = '─── 配置开始 ───'
const MARK_END = '─── 配置结束 ───'

/** 从搬运文本里抠出 TOML 主体；兼容带标记、纯 TOML、标记被聊天软件破坏三种情况 */
export function extractToml(raw) {
  if (!raw) return ''
  let s = String(raw).replace(/\r\n/g, '\n')
  const b = s.indexOf(MARK_BEGIN)
  const e = s.indexOf(MARK_END)
  if (b >= 0 && e > b) return s.slice(b + MARK_BEGIN.length, e).trim()
  if (b >= 0) return s.slice(b + MARK_BEGIN.length).trim()
  // 没有标记：本身就是 TOML
  if (/^\s*\[/.test(s) || /network_name\s*=/.test(s)) return s.trim()
  return s.trim()
}

/**
 * 解析整段配置文本（扫码/微信得到的内容）。
 * @returns {{ok: boolean, toml: string, ipv4: string, relay: string, secret: string,
 *            networkName: string, problems: string[], mode: string}}
 */
export function parseConfigText(raw) {
  const toml = extractToml(raw)
  const problems = []

  const grab = (re) => {
    const m = toml.match(re)
    return m ? m[1].trim() : ''
  }

  const networkName = grab(/network_name\s*=\s*"([^"]*)"/)
  const secret = grab(/network_secret\s*=\s*"([^"]*)"/)
  const ipv4 = grab(/ipv4\s*=\s*"([^"]*)"/)

  // 初始节点：抓所有 peers 里的 uri
  const peers = [...toml.matchAll(/uri\s*=\s*"([^"]*)"/g)].map((m) => m[1])
  const disableP2p = /disable_p2p\s*=\s*true/.test(toml)
  const mode = disableP2p ? 'relay' : 'direct'

  if (!toml) problems.push('未找到 TOML 内容。确认已完整复制。')
  if (!networkName) problems.push('缺少 network_name')
  else if (networkName !== 'lanlink') {
    problems.push(`网络名称是 ${networkName}，必须是 lanlink 才能与电脑端发现彼此`)
  }
  if (!secret) problems.push('缺少 network_secret')
  else if (secret.length < 12) problems.push('network_secret 少于 12 位')

  const lanOrPublic = peers.find((p) => /^(tcp|udp|ws|wss):\/\/(?!.*(183\.230|weiai|scpsl))/.test(p)) || ''
  const relay = peers[0] || ''

  return { ok: problems.length === 0, toml, ipv4, relay, secret, networkName, peers, mode, problems }
}
