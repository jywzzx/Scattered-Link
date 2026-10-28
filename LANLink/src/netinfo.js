'use strict';
const os = require('node:os');
const stun = require('./stun');

const PRIVATE_V4 = [
  [/^10\./, 'LAN'],
  [/^192\.168\./, 'LAN'],
  [/^172\.(1[6-9]|2\d|3[01])\./, 'LAN'],
  [/^169\.254\./, 'LINK-LOCAL'],
  [/^100\.(6[4-9]|[7-9]\d|1[0-1]\d|12[0-7])\./, 'CGNAT'],
  [/^127\./, 'LOOPBACK'],
];

const VIRTUAL_IFACE = /vmware|virtualbox|vbox|hyper-?v|vethernet|radmin|hamachi|tap-|tun\d|tailscale|zerotier|docker|wsl|npcap/i;
const PHYSICAL_IFACE = /^(wlan|wi-?fi|ethernet|eth\d|en\d|wlp|eno|enp)/i;

/**
 * 给网卡打分，越小越"像"用户真正在用的那块。
 * 目的：不要让 VMware / Radmin / 热点适配器抢走"本机 IP"的展示位。
 */
function ifaceScore(name, address) {
  let s = 0;
  if (PHYSICAL_IFACE.test(name)) s -= 2;
  if (VIRTUAL_IFACE.test(name)) s += 4;
  if (/\.1$/.test(address)) s += 2; // 网关型地址（如 192.168.137.1 热点）通常不是本机主地址
  return s;
}

/** 返回 IPv4 地址类型：LAN / CGNAT / LINK-LOCAL / PUBLIC / LOOPBACK */
function classifyIpv4(ip) {
  for (const [re, kind] of PRIVATE_V4) if (re.test(ip)) return kind;
  return 'PUBLIC';
}

function collectLocal() {
  const ifaces = os.networkInterfaces();
  const ipv4 = [];
  const ipv6 = [];

  for (const [name, list] of Object.entries(ifaces)) {
    for (const a of list || []) {
      if (a.family === 'IPv4') {
        if (a.internal) continue;
        ipv4.push({
          iface: name,
          address: a.address,
          netmask: a.netmask,
          kind: classifyIpv4(a.address),
          mac: a.mac,
          virtual: VIRTUAL_IFACE.test(name),
          score: ifaceScore(name, a.address),
        });
      } else if (a.family === 'IPv6') {
        if (a.internal) continue;
        const isLinkLocal = /^fe80:/i.test(a.address);
        const isUniqueLocal = /^f[cd][0-9a-f]{2}:/i.test(a.address);
        ipv6.push({
          iface: name,
          address: a.address.split('%')[0],
          kind: isLinkLocal ? 'LINK-LOCAL' : isUniqueLocal ? 'ULA' : 'GLOBAL',
        });
      }
    }
  }

  const byKind = { LAN: 0, PUBLIC: 1, CGNAT: 2, 'LINK-LOCAL': 3, LOOPBACK: 9 };
  ipv4.sort((a, b) => {
    const ka = byKind[a.kind] ?? 5;
    const kb = byKind[b.kind] ?? 5;
    if (ka !== kb) return ka - kb;
    return a.score - b.score;
  });
  ipv6.sort((a, b) => (a.kind === 'GLOBAL' ? 0 : 1) - (b.kind === 'GLOBAL' ? 0 : 1));

  return {
    hostname: os.hostname(),
    platform: process.platform,
    primaryLanIp: (ipv4.find((x) => x.kind === 'LAN') || ipv4[0] || {}).address || '127.0.0.1',
    ipv4,
    ipv6,
    globalIpv6: (ipv6.find((x) => x.kind === 'GLOBAL') || {}).address || null,
  };
}

/**
 * 通过隧道 socket 探测公网映射。
 * 判定规则：向最多 3 个不同 STUN 服务器各查 2 轮；
 *  - 至少 2 个服务器应答，且所有映射端口相同 => 锥型 NAT（可打洞）
 *  - 至少 2 个服务器应答，端口有差异       => 对称型 NAT（打洞基本无望）
 *  - 只有 1 个服务器应答                    => 无法判定（不能凭单点承诺能打通）
 * 宁可误判成"打不通"，也不要错误承诺。
 */
async function probePublic(tunnel) {
  const servers = stun.serversFromEnv();
  if (!servers.length) {
    return { ok: false, reason: 'STUN 探测已禁用', mapped: null, natType: 'UNKNOWN' };
  }

  const groups = await Promise.all(servers.slice(0, 3).map(async (server) => {
    const hits = [];
    for (let round = 0; round < 2; round++) {
      const m = await tunnel.probeStun(server);
      if (m) hits.push(m);
    }
    return { server, hits };
  }));

  const answered = groups.filter((g) => g.hits.length > 0);
  if (!answered.length) {
    return { ok: false, reason: '未收到 STUN 响应（可能被防火墙/代理拦截）', mapped: null, natType: 'UNKNOWN' };
  }

  const all = answered.flatMap((g) => g.hits);
  const ports = new Set(all.map((h) => h.port));
  let natType = 'UNKNOWN';
  let note = '';
  if (answered.length >= 2) {
    natType = ports.size === 1 ? 'CONE' : 'SYMMETRIC';
  } else {
    note = `只有 ${answered[0].server.host} 应答，无法判定 NAT 类型`;
  }

  return {
    ok: true,
    mapped: all[0],
    natType,
    note,
    all,
    serversAnswered: answered.length,
    distinctPorts: ports.size,
  };
}

module.exports = { collectLocal, classifyIpv4, probePublic };
