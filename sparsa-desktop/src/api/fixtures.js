/**
 * 后端 DTO 形状的唯一出处（与 Java 侧 lanlink.server.dto 一一对应）。
 *
 * 这里的假数据不是随手编的字符串，字段名、取值范围都对齐
 * Engine.peersDetailed() / StunInfo / Diagnostics.preflight() 的真实输出，
 * 这样接上真后端时前端一行不用改。
 */

export const SHAPES = {
  peer: {
    ipv4: '10.144.0.1',
    hostname: 'sparsa-host',
    cost: 'Local',
    latMs: '-',
    lossRate: '0.00%',
    tunnel: '-',
    natType: 'PortRestricted'
  }
}

export const SYSTEM_INFO = {
  appVersion: '1.0.0',
  apiVersion: 'v1',
  os: 'Windows 11 23H2 (22631)',
  hostname: 'DESKTOP-SPARSA',
  admin: true,
  javaVersion: '17.0.12',
  corePath: 'D:\\LANLink-v2\\engine\\easytier-core.exe',
  coreFound: true,
  cliFound: true,
  engineReady: false
}

export const NETWORK_INFO = {
  lanIp: '192.168.1.24',
  ipv4: [
    { iface: 'WLAN', addr: '192.168.1.24' },
    { iface: '以太网', addr: '10.8.0.6' }
  ],
  virtualNicUp: false,
  publicIp: '116.237.233.55',
  nat: { udp: 'PortRestrictedCone', tcp: 'PortRestrictedCone' },
  portMappingOk: false,
  hardNat: false,
  publicIpChanged: false
}

export const PREFLIGHT = {
  ok: true,
  items: [
    { key: 'admin', label: '管理员权限', ok: true, detail: '已提权，可创建虚拟网卡' },
    { key: 'core', label: '内核文件', ok: true, detail: 'easytier-core.exe 就位' },
    { key: 'cli', label: '内核工具', ok: true, detail: 'easytier-cli.exe 就位' },
    { key: 'wintun', label: 'Wintun 驱动', ok: true, detail: 'wintun.dll 就位' },
    { key: 'port', label: '监听端口', ok: true, detail: '随机端口可用' },
    { key: 'firewall', label: '防火墙规则', ok: true, detail: '入站规则已添加' }
  ]
}

export const PUBLIC_NODES = {
  nodes: [
    'tcp://183.230.36.171:11010',
    'tcp://easytier.weiai.org.cn:11010',
    'tcp://ros.scpsl.com.cn:11010'
  ],
  recommended: 'tcp://183.230.36.171:11010',
  probedAt: '2026-10-28T12:00:00+08:00'
}

export const MEMBERS_IDLE = { members: [], count: 0, onlineCount: 0 }

export const MEMBERS_HOSTING = {
  count: 2,
  onlineCount: 2,
  members: [
    {
      ipv4: '10.144.0.1',
      hostname: 'sparsa-host',
      cost: 'Local',
      latMs: '-',
      lossRate: '0.00%',
      tunnel: '-',
      natType: 'PortRestrictedCone',
      self: true,
      reachable: null,
      pingMs: null
    },
    {
      ipv4: '10.144.0.37',
      hostname: 'sparsa-studio',
      cost: 'P2P',
      latMs: '0.44',
      lossRate: '0.00%',
      tunnel: 'udp',
      natType: 'PortRestrictedCone',
      self: false,
      reachable: true,
      pingMs: 3
    }
  ]
}

export const MEMBERS_RELAY = {
  count: 2,
  onlineCount: 2,
  members: [
    {
      ipv4: '10.144.0.1',
      hostname: 'sparsa-host',
      cost: 'Local',
      latMs: '-',
      lossRate: '0.00%',
      tunnel: '-',
      natType: 'Symmetric',
      self: true,
      reachable: null,
      pingMs: null
    },
    {
      ipv4: '10.144.0.112',
      hostname: 'sparsa-laptop',
      cost: 'Relay(udp)',
      latMs: '182.3',
      lossRate: '0.12%',
      tunnel: 'udp',
      natType: 'Symmetric',
      self: false,
      reachable: true,
      pingMs: 186
    }
  ]
}

/** 手机端配置卡片用的提示项（level: info / warn / error） */
export const PHONE_TIPS_DIRECT = [
  {
    level: 'info',
    text: '手机端只有导入配置文件一个入口。扫码后全选复制，粘贴到记事本。保存为 sparsa.toml 后在 EasyTier 中导入。'
  },
  {
    level: 'info',
    text: '本模式写入三条路径：局域网地址、公网地址、社区公共节点。同一路由器下局域网地址最快。'
  },
  {
    level: 'warn',
    text: '导入后核对网络名称必须是 lanlink。名称不一致时，其余参数正确也无法发现对方。'
  },
  {
    level: 'info',
    text: '虚拟 IPv4 填 10.144.0.9/24，与电脑端处于同一网段。'
  }
]

export const PHONE_TIPS_RELAY = [
  {
    level: 'info',
    text: '强制统一中继：配置中只有一个初始节点。必须与电脑端填写的节点完全一致。'
  },
  {
    level: 'error',
    text: '公共节点彼此独立。两端各挂不同节点时，双方均显示在线，但互相不可见。'
  },
  {
    level: 'warn',
    text: '默认关闭 DHCP，虚拟 IPv4 需手填 10.144.0.9/24。地址没有唯一性保证，多台手机会冲突。'
  },
  {
    level: 'info',
    text: '代价：延迟高于直连。该节点失效时需更换节点并重新导入配置。'
  }
]

export const DIAGNOSTICS_TEXT = `时间: ${new Date().toISOString().replace('T', ' ').slice(0, 19)}
公网 IP  : 116.237.233.55
局域网 IP: 192.168.1.24
NAT 类型 : UDP=PortRestrictedCone  TCP=PortRestrictedCone
端口映射 : 未生效
监听端口 : 41273
中继节点 : tcp://183.230.36.171:11010
强制中继 : 否
内核实际 : 强制中继=否 挂公共节点=是 中继=tcp://183.230.36.171:11010
内核节点 : tcp://183.230.36.171:11010, tcp://easytier.weiai.org.cn:11010
本机虚拟IP: 10.144.0.1
邀请码   : 116.237.233.55:41273#k7m2q9x3rt5vb6n
已见成员 : 10.144.0.1(Local), 10.144.0.37(P2P 0.44ms)
内核日志 : %LOCALAPPDATA%\\LANLink\\logs\\engine-20261028-120000.log`

export const LOG_SAMPLE = [
  '[12:00:01] === 零星 启动 ===',
  '[12:00:01] 预检：管理员权限 OK / 内核 OK / Wintun OK',
  '[12:00:02] 探测公网 IP 116.237.233.55',
  '[12:00:02] NAT 类型 UDP=PortRestrictedCone TCP=PortRestrictedCone',
  '[12:00:03] 正在创建房间，监听端口 41273',
  '[12:00:03] 内核命令: easytier-core.exe --network-name lanlink -i 10.144.0.1/24 -l tcp://0.0.0.0:41273',
  '[12:00:07] 内核就绪，RPC 127.0.0.1:52311',
  '[12:00:09] 成员接入：10.144.0.37 (P2P)',
  '[12:00:09] 连通性自检通过，可在游戏中填写 10.144.0.37'
]
