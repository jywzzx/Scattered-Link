'use strict';
/**
 * 极简 STUN 客户端（RFC 5389 Binding Request），仅用于：
 *   1) 获取本机在公网侧的公网 IP:Port（即本端 UDP socket 的 NAT 映射）
 *   2) 粗略判断 NAT 是否可打洞（两次不同服务器返回的映射端口是否一致）
 * 不依赖任何第三方 SDK，纯协议实现。
 */
const crypto = require('node:crypto');

const MAGIC = 0x2112a442;
const ATTR_MAPPED = 0x0001;
const ATTR_XOR_MAPPED = 0x0020;

function isStun(buf) {
  return Buffer.isBuffer(buf) && buf.length >= 20 && buf.readUInt32BE(4) === MAGIC;
}

function buildRequest(txid) {
  const b = Buffer.alloc(20);
  b.writeUInt16BE(0x0001, 0);
  b.writeUInt16BE(0, 2);
  b.writeUInt32BE(MAGIC, 4);
  txid.copy(b, 8);
  return b;
}

function parse(buf) {
  const type = buf.readUInt16BE(0);
  const len = buf.readUInt16BE(2);
  const txid = buf.subarray(8, 20);
  const attrs = new Map();
  let off = 20;
  const end = Math.min(buf.length, 20 + len);
  while (off + 4 <= end) {
    const t = buf.readUInt16BE(off);
    const l = buf.readUInt16BE(off + 2);
    if (off + 4 + l > end) break;
    attrs.set(t, buf.subarray(off + 4, off + 4 + l));
    off += 4 + l + ((4 - (l % 4)) % 4);
  }
  return { type, txid, attrs };
}

function extractMapped(msg) {
  let xored = true;
  let v = msg.attrs.get(ATTR_XOR_MAPPED);
  if (!v) { v = msg.attrs.get(ATTR_MAPPED); xored = false; }
  if (!v || v.length < 8) return null;

  const family = v[1];
  let port = v.readUInt16BE(2);
  if (xored) port ^= (MAGIC >>> 16);

  if (family === 0x01 && v.length >= 8) {
    let ip = v.readUInt32BE(4);
    if (xored) ip ^= MAGIC;
    const address = `${(ip >>> 24) & 255}.${(ip >>> 16) & 255}.${(ip >>> 8) & 255}.${ip & 255}`;
    return { address, port, family: 'IPv4' };
  }
  if (family === 0x02 && v.length >= 20) {
    const raw = Buffer.from(v.subarray(4, 20));
    if (xored) {
      const mask = Buffer.concat([Buffer.from([0x21, 0x12, 0xa4, 0x42]), msg.txid]);
      for (let i = 0; i < 16; i++) raw[i] ^= mask[i];
    }
    const parts = [];
    for (let i = 0; i < 16; i += 2) parts.push(raw.readUInt16BE(i).toString(16));
    return { address: parts.join(':'), port, family: 'IPv6' };
  }
  return null;
}

/** 默认公共 STUN 服务器（国内优先；可用环境变量 LANLINK_STUN 覆盖，留空则不探测） */
const DEFAULT_SERVERS = [
  { host: 'stun.miwifi.com', port: 3478 },
  { host: 'stun.hitv.com', port: 3478 },
  { host: 'stun.cloudflare.com', port: 3478 },
];

function serversFromEnv() {
  const raw = process.env.LANLINK_STUN;
  if (raw === undefined) return DEFAULT_SERVERS.slice();
  if (raw.trim() === '') return [];
  return raw.split(',').map((s) => {
    const [host, port] = s.trim().split(':');
    return { host, port: Number(port) || 3478 };
  }).filter((s) => s.host);
}

module.exports = { isStun, buildRequest, parse, extractMapped, serversFromEnv, DEFAULT_SERVERS };
