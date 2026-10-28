'use strict';
/**
 * 隧道线格式（14 字节定长头，无 JSON、无第三方序列化）
 *   0     type      u8
 *   1-2   streamId  u16 BE
 *   3-6   seq       u32 BE   本端发送序号（包序号，非字节序号）
 *   7-10  ack       u32 BE   本端期望收到的下一个序号
 *   11    flags     u8
 *   12-13 length    u16 BE   payload 长度
 *   14..  payload
 */
const T = Object.freeze({
  HELLO: 1,
  HELLO_ACK: 2,
  DATA: 3,
  ACK: 4,
  SYN: 5,
  FIN: 6,
  RST: 7,
  PING: 8,
  PONG: 9,
});

const HDR = 14;
const MAX_PAYLOAD = 1100; // 保守值，规避 PPPoE/隧道 MTU 1500 下的分片

function encode(type, streamId, seq, ack, flags, payload) {
  const len = payload ? payload.length : 0;
  const b = Buffer.allocUnsafe(HDR + len);
  b[0] = type;
  b.writeUInt16BE(streamId & 0xffff, 1);
  b.writeUInt32BE(seq >>> 0, 3);
  b.writeUInt32BE(ack >>> 0, 7);
  b[11] = flags & 0xff;
  b.writeUInt16BE(len, 12);
  if (len) payload.copy(b, HDR);
  return b;
}

function decode(buf) {
  if (!Buffer.isBuffer(buf) || buf.length < HDR) return null;
  const len = buf.readUInt16BE(12);
  if (buf.length < HDR + len) return null;
  return {
    type: buf[0],
    streamId: buf.readUInt16BE(1),
    seq: buf.readUInt32BE(3),
    ack: buf.readUInt32BE(7),
    flags: buf[11],
    payload: buf.subarray(HDR, HDR + len),
  };
}

function typeName(t) {
  for (const [k, v] of Object.entries(T)) if (v === t) return k;
  return `UNKNOWN(${t})`;
}

module.exports = { T, HDR, MAX_PAYLOAD, encode, decode, typeName };
