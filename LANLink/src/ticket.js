'use strict';
/**
 * 连接码编解码。把「公网地址 + 隧道端口 + 认证令牌 + 服务端口」打包成一段可直接发微信的短字符串。
 *   LANL-<base64url>
 *   布局: [ver:1][addr:4|16][tunnelPort:2][token:4][servicePort:2][crc8:1]
 */
const PREFIX = 'LANL-';

function crc8(buf) {
  let crc = 0x00;
  for (let i = 0; i < buf.length; i++) {
    crc ^= buf[i];
    for (let b = 0; b < 8; b++) {
      crc = (crc & 0x80) ? ((crc << 1) ^ 0x07) & 0xff : (crc << 1) & 0xff;
    }
  }
  return crc;
}

function ipv4ToBuf(ip) {
  const parts = ip.split('.');
  if (parts.length !== 4) throw new Error('非法 IPv4: ' + ip);
  const b = Buffer.allocUnsafe(4);
  for (let i = 0; i < 4; i++) {
    const n = Number(parts[i]);
    if (!Number.isInteger(n) || n < 0 || n > 255) throw new Error('非法 IPv4: ' + ip);
    b[i] = n;
  }
  return b;
}

function ipv6ToBuf(ip) {
  let s = ip;
  if (s.includes('::')) {
    const [head, tail] = s.split('::');
    const h = head ? head.split(':') : [];
    const t = tail ? tail.split(':') : [];
    const fill = 8 - h.length - t.length;
    if (fill < 0) throw new Error('非法 IPv6: ' + ip);
    s = [...h, ...Array(fill).fill('0'), ...t].join(':');
  }
  const groups = s.split(':');
  if (groups.length !== 8) throw new Error('非法 IPv6: ' + ip);
  const b = Buffer.allocUnsafe(16);
  for (let i = 0; i < 8; i++) b.writeUInt16BE(parseInt(groups[i] || '0', 16), i * 2);
  return b;
}

function bufToIpv4(b) { return `${b[0]}.${b[1]}.${b[2]}.${b[3]}`; }

function bufToIpv6(b) {
  const g = [];
  for (let i = 0; i < 16; i += 2) g.push(b.readUInt16BE(i).toString(16));
  return g.join(':');
}

function encode({ address, port, token, servicePort }) {
  const isV6 = address.includes(':');
  const addrBuf = isV6 ? ipv6ToBuf(address) : ipv4ToBuf(address);
  const body = Buffer.allocUnsafe(1 + addrBuf.length + 2 + 4 + 2);
  body[0] = isV6 ? 6 : 4;
  addrBuf.copy(body, 1);
  body.writeUInt16BE(port & 0xffff, 1 + addrBuf.length);
  token.copy(body, 1 + addrBuf.length + 2);
  body.writeUInt16BE(servicePort & 0xffff, 1 + addrBuf.length + 6);
  const full = Buffer.concat([body, Buffer.from([crc8(body)])]);
  return PREFIX + full.toString('base64url');
}

function decode(code) {
  if (typeof code !== 'string') throw new Error('连接码为空');
  const s = code.trim();
  if (!s.toUpperCase().startsWith(PREFIX)) throw new Error('连接码格式错误（应以 LANL- 开头）');
  const raw = Buffer.from(s.slice(PREFIX.length), 'base64url');
  if (raw.length < 8) throw new Error('连接码不完整');

  const body = raw.subarray(0, raw.length - 1);
  const crc = raw[raw.length - 1];
  if (crc8(body) !== crc) throw new Error('连接码校验失败（复制不完整？）');

  const ver = body[0];
  if (ver !== 4 && ver !== 6) throw new Error('连接码版本不支持');
  const addrLen = ver === 4 ? 4 : 16;
  if (body.length !== 1 + addrLen + 8) throw new Error('连接码长度异常');

  const address = ver === 4 ? bufToIpv4(body.subarray(1, 1 + addrLen)) : bufToIpv6(body.subarray(1, 1 + addrLen));
  const port = body.readUInt16BE(1 + addrLen);
  const token = Buffer.from(body.subarray(1 + addrLen + 2, 1 + addrLen + 6));
  const servicePort = body.readUInt16BE(1 + addrLen + 6);

  return { address, port, token, servicePort, family: ver === 4 ? 'IPv4' : 'IPv6' };
}

/** "1.2.3.4:5000" / "[2001:db8::1]:5000" 解析 */
function parseEndpoint(str) {
  const s = String(str).trim();
  if (s.startsWith('[')) {
    const i = s.indexOf(']');
    if (i < 0) throw new Error('端点格式错误');
    const address = s.slice(1, i);
    const rest = s.slice(i + 1);
    if (!rest.startsWith(':')) throw new Error('端点缺少端口');
    const port = Number(rest.slice(1));
    if (!Number.isInteger(port) || port <= 0) throw new Error('端口非法');
    return { address, port, family: 'IPv6' };
  }
  const i = s.lastIndexOf(':');
  if (i < 0) throw new Error('端点缺少端口，应为 IP:端口');
  const address = s.slice(0, i);
  const port = Number(s.slice(i + 1));
  if (!address || !Number.isInteger(port) || port <= 0 || port > 65535) throw new Error('端点格式错误');
  return { address, port, family: address.includes(':') ? 'IPv6' : 'IPv4' };
}

function formatEndpoint(ep) {
  return ep.family === 'IPv6' ? `[${ep.address}]:${ep.port}` : `${ep.address}:${ep.port}`;
}

module.exports = { encode, decode, parseEndpoint, formatEndpoint, PREFIX, crc8 };
