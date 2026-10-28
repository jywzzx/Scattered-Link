'use strict';
const dgram = require('node:dgram');
const { EventEmitter } = require('node:events');
const { T, encode, decode } = require('./proto');
const { ReliableStream } = require('./rstream');
const { hmac, randomBytes, safeEqual, Logger } = require('./util');
const stun = require('./stun');

const HELLO_INTERVAL = 300;
const HELLO_MAX = 25;
const KEEPALIVE = 8000;
const PEER_TIMEOUT = 30000;
const MAX_SLOTS = 15;

const HELLO_TAG = Buffer.from('lanlink-hello');
const ACK_TAG = Buffer.from('lanlink-ack');

function mac8(key, ...parts) {
  return hmac(key, Buffer.concat(parts)).subarray(0, 8);
}

function keyOf(rinfo) {
  return `${rinfo.address}|${rinfo.port}`;
}

/**
 * UDP 隧道：握手鉴权 + 多 peer 复用 + 每流可靠传输。
 * streamId 高 4 位为 peer 槽位，低 12 位为流计数器（host 用偶数，guest 用奇数）。
 */
class Tunnel extends EventEmitter {
  constructor(opts = {}) {
    super();
    this.role = opts.role || 'guest';
    this.port = opts.port || 0;
    this.token = opts.token || randomBytes(4);
    this.log = opts.logger || new Logger('tunnel');
    this.nickname = opts.nickname || '';

    this.s4 = null;
    this.s6 = null;
    this.streams = new Map(); // id -> { stream, peer }
    this.peers = new Map();   // addr|port -> peer
    this.defaultPeer = null;

    this.streamSeq = this.role === 'host' ? 0 : 1;
    this.slotSeq = 0;
    this.started = false;
    this.destroyed = false;
    this.established = false;
    this.rxBytes = 0;
    this.txBytes = 0;

    this._helloTimer = null;
    this._keepTimer = null;
    this._sweepTimer = null;
    this._pendingEndpoints = [];
    this._stunPending = new Map();
  }

  /* ---------------- 生命周期 ---------------- */

  async start() {
    if (this.started) return this.localPort;
    this.s4 = await this._bind('udp4');
    this.localPort = this.s4.address().port;
    // IPv6 复用同一个端口号，保证「一个端口号」对两种协议都成立
    if (this.port === 0) this.port = this.localPort;
    try {
      this.s6 = await this._bind('udp6');
    } catch (e) {
      this.log.debug('IPv6 socket 不可用:', e.message);
      this.s6 = null;
    }
    this.started = true;
    this._sweepTimer = setInterval(() => this._sweep(), 5000);
    this._sweepTimer.unref?.();
    return this.localPort;
  }

  _bind(type) {
    return new Promise((resolve, reject) => {
      const sock = dgram.createSocket({ type, ipv6Only: type === 'udp6' });
      const onErr = (e) => { sock.close(); reject(e); };
      sock.once('error', onErr);
      sock.on('message', (msg, rinfo) => this._onPacket(msg, rinfo, sock));
      sock.bind(this.port, () => {
        sock.off('error', onErr);
        sock.on('error', (e) => this.log.warn('socket error:', e.message));
        try { if (sock.setRecvBufferSize) sock.setRecvBufferSize(2 * 1024 * 1024); } catch (_) {}
        resolve(sock);
      });
    });
  }

  /* ---------------- 握手 ---------------- */

  /** guest: 向候选端点发起握手 */
  connect(endpoints) {
    if (this.role !== 'guest') throw new Error('only guest can connect');
    this._pendingEndpoints = endpoints.filter(Boolean);
    // 预登记对端，否则收到 HELLO_ACK 时无法匹配来源而丢弃
    for (const ep of this._pendingEndpoints) {
      const key = `${ep.address}|${ep.port}`;
      if (!this.peers.has(key)) {
        this.peers.set(key, {
          key,
          address: ep.address,
          port: ep.port,
          family: ep.family || 'IPv4',
          lastSeen: Date.now(),
          slot: 0,
        });
      }
    }
    this.clientNonce = randomBytes(16);
    this._helloAttempt = 0;

    const hello = Buffer.concat([this.clientNonce, mac8(this.token, HELLO_TAG, this.clientNonce)]);
    const pkt = encode(T.HELLO, 0, 0, 0, 0, hello);

    return new Promise((resolve, reject) => {
      this._resolveConnect = resolve;
      this._rejectConnect = reject;
      const fire = () => {
        if (this.destroyed) return;
        for (const ep of this._pendingEndpoints) this._rawSend(ep, pkt);
        if (++this._helloAttempt >= HELLO_MAX) {
          clearInterval(this._helloTimer);
          this._helloTimer = null;
          reject(new Error('握手超时：对方不可达或被 NAT 拦截'));
        }
      };
      fire();
      this._helloTimer = setInterval(fire, HELLO_INTERVAL);
      this._helloTimer.unref?.();
    });
  }

  _onPacket(msg, rinfo, sock) {
    if (this.destroyed) return;
    this.rxBytes += msg.length;

    if (stun.isStun(msg)) { this._onStun(msg); return; }

    const pkt = decode(msg);
    if (!pkt) return;

    const key = keyOf(rinfo);
    let peer = this.peers.get(key);
    // IPv6 文本形式可能被系统展开，导致 key 不一致；同端口视为同一对端
    if (!peer && this.role === 'guest') {
      for (const p of this.peers.values()) {
        if (p.port !== rinfo.port) continue;
        this.peers.delete(p.key);
        p.key = key;
        p.address = rinfo.address;
        p.family = rinfo.family;
        this.peers.set(key, p);
        peer = p;
        break;
      }
    }

    if (pkt.type === T.HELLO) {
      this._onHello(pkt, rinfo);
      return;
    }
    if (!peer) {
      if (pkt.type === T.PING) {
        const p = { key, address: rinfo.address, port: rinfo.port, family: rinfo.family, lastSeen: Date.now(), slot: -1 };
        this._sendTo(p, T.PONG, 0, 0, 0, 0, null);
      }
      return;
    }
    peer.lastSeen = Date.now();

    const rec = this.streams.get(pkt.streamId);
    switch (pkt.type) {
      case T.HELLO_ACK:
        if (this.role === 'guest') this._onHelloAck(pkt, peer);
        break;
      case T.SYN:
        this._onSyn(pkt, peer);
        break;
      case T.DATA:
        if (rec) {
          rec.stream.onData(pkt.seq, pkt.payload);
          if (pkt.ack) rec.stream.onAck(pkt.ack);
        }
        break;
      case T.ACK:
        if (rec && pkt.ack) rec.stream.onAck(pkt.ack);
        break;
      case T.FIN:
        if (rec) rec.stream.onFin();
        break;
      case T.RST:
        if (rec) rec.stream.destroy('rst');
        break;
      case T.PING:
        this._sendTo(peer, T.PONG, 0, 0, 0, 0, null);
        break;
      case T.PONG:
        break;
      default:
        break;
    }
  }

  /**
   * 从隧道自身的 socket 发起 STUN 探测。
   * 必须用同一个 socket，才能拿到这个本地端口对应的 NAT 映射端口。
   */
  probeStun(server, timeout = 1500) {
    if (!this.s4) return Promise.resolve(null);
    const txid = randomBytes(12);
    const key = txid.toString('hex');
    const pkt = stun.buildRequest(txid);
    return new Promise((resolve) => {
      const done = (v) => {
        if (!this._stunPending.has(key)) return;
        this._stunPending.delete(key);
        clearTimeout(timer);
        resolve(v);
      };
      const timer = setTimeout(() => done(null), timeout);
      this._stunPending.set(key, done);
      this._rawSend({ address: server.host, port: server.port, family: 'IPv4' }, pkt);
    });
  }

  _onStun(msg) {
    let parsed;
    try { parsed = stun.parse(msg); } catch (_) { return; }
    const key = parsed.txid.toString('hex');
    const done = this._stunPending.get(key);
    if (!done) return;
    const mapped = stun.extractMapped(parsed);
    done(mapped);
  }

  _onHello(pkt, rinfo) {
    const nonce = pkt.payload.subarray(0, 16);
    const mac = pkt.payload.subarray(16, 24);
    if (nonce.length !== 16 || mac.length !== 8) return;
    const expect = mac8(this.token, HELLO_TAG, nonce);
    if (!safeEqual(mac, expect)) {
      this.log.warn(`拒绝未授权握手: ${rinfo.address}:${rinfo.port}`);
      return;
    }

    const key = keyOf(rinfo);
    let peer = this.peers.get(key);
    if (!peer) {
      peer = {
        key,
        address: rinfo.address,
        port: rinfo.port,
        family: rinfo.family,
        lastSeen: Date.now(),
        slot: this._allocSlot(),
        clientNonce: nonce,
        joinedAt: Date.now(),
      };
      this.peers.set(key, peer);
      this.defaultPeer = this.defaultPeer || peer;
      this.log.info(`新节点接入: ${peer.address}:${peer.port}`);
      this.emit('peer-join', peer);
    }
    peer.lastSeen = Date.now();

    const hostNonce = randomBytes(16);
    const ack = Buffer.concat([
      hostNonce,
      mac8(this.token, ACK_TAG, nonce, hostNonce),
    ]);
    this._sendTo(peer, T.HELLO_ACK, 0, 0, 0, 0, ack);
    this._ensureKeepalive();
    this.established = true;
    this.emit('state', this.snapshot());
  }

  _onHelloAck(pkt, peer) {
    if (this.established) return;
    const hostNonce = pkt.payload.subarray(0, 16);
    const mac = pkt.payload.subarray(16, 24);
    if (hostNonce.length !== 16 || mac.length !== 8) return;
    const expect = mac8(this.token, ACK_TAG, this.clientNonce, hostNonce);
    if (!safeEqual(mac, expect)) {
      this.log.warn('握手响应校验失败（连接码/密码不符）');
      return;
    }
    clearInterval(this._helloTimer);
    this._helloTimer = null;
    peer.slot = 0;
    this.defaultPeer = peer;
    this.established = true;
    this.log.info(`已连接对端: ${peer.address}:${peer.port}`);
    this._ensureKeepalive();
    this.emit('connected', peer);
    this.emit('state', this.snapshot());
    if (this._resolveConnect) this._resolveConnect(peer);
  }

  _ensureKeepalive() {
    if (this._keepTimer) return;
    this._keepTimer = setInterval(() => {
      if (this.destroyed) return;
      for (const p of this.peers.values()) {
        this._sendTo(p, T.PING, 0, 0, 0, 0, null);
      }
    }, KEEPALIVE);
    this._keepTimer.unref?.();
  }

  _allocSlot() {
    for (let i = 0; i < MAX_SLOTS; i++) {
      this.slotSeq = (this.slotSeq % MAX_SLOTS) + 1;
      let used = false;
      for (const p of this.peers.values()) if (p.slot === this.slotSeq) { used = true; break; }
      if (!used) return this.slotSeq;
    }
    return 1;
  }

  _sweep() {
    const now = Date.now();
    for (const [k, p] of this.peers) {
      if (now - p.lastSeen > PEER_TIMEOUT) {
        this.log.info(`节点超时移除: ${p.address}:${p.port}`);
        this.peers.delete(k);
        if (this.defaultPeer === p) this.defaultPeer = this.peers.values().next().value || null;
        for (const [id, rec] of this.streams) if (rec.peer === p) rec.stream.destroy('peer-lost');
        this.emit('peer-leave', p);
        this.emit('state', this.snapshot());
      }
    }
  }

  /* ---------------- 流 ---------------- */

  _onSyn(pkt, peer) {
    if (this.streams.has(pkt.streamId)) return; // 重复 SYN
    if (pkt.payload.length < 2) return;
    const port = pkt.payload.readUInt16BE(0);
    const stream = new ReliableStream(this, pkt.streamId, { port, peer });
    this.streams.set(pkt.streamId, { stream, peer });
    stream.on('close', (reason) => {
      this.streams.delete(pkt.streamId);
      this.emit('state', this.snapshot());
      this.log.debug(`流 #${pkt.streamId} 关闭 (${reason})`);
    });
    this.emit('stream', stream, port, peer);
  }

  openStream(port, peer = this.defaultPeer) {
    if (!peer) throw new Error('尚未建立连接');
    if (!this.established) throw new Error('隧道未就绪');
    const slot = (peer.slot > 0 && peer.slot <= MAX_SLOTS) ? peer.slot : 1;
    const id = ((slot & 0x0f) << 12) | (this.streamSeq & 0x0fff);
    this.streamSeq = (this.streamSeq + 2) & 0x0fff;
    if (this.streamSeq === 0) this.streamSeq = this.role === 'host' ? 0 : 1;

    const payload = Buffer.allocUnsafe(2);
    payload.writeUInt16BE(port & 0xffff, 0);

    const stream = new ReliableStream(this, id, { port, peer });
    this.streams.set(id, { stream, peer });
    stream.on('close', (reason) => {
      this.streams.delete(id);
      this.log.debug(`流 #${id} 关闭 (${reason})`);
    });
    this._sendTo(peer, T.SYN, id, 0, 0, 0, payload);
    return stream;
  }

  /* ---------------- 发送 ---------------- */

  _send(type, streamId, seq, ack, flags, payload) {
    const rec = this.streams.get(streamId);
    const peer = rec ? rec.peer : this.defaultPeer;
    if (!peer) return;
    this._sendTo(peer, type, streamId, seq, ack, flags, payload);
  }

  _sendTo(peer, type, streamId, seq, ack, flags, payload) {
    const buf = encode(type, streamId, seq, ack, flags, payload);
    const ep = { address: peer.address, port: peer.port, family: peer.family };
    this._rawSend(ep, buf);
  }

  _rawSend(ep, buf) {
    let sock;
    if (ep.family === 'IPv6') sock = this.s6;
    else sock = this.s4;
    if (!sock) return;
    try {
      sock.send(buf, ep.port, ep.address, () => {});
      this.txBytes += buf.length;
    } catch (e) {
      this.log.debug('send 失败:', e.message);
    }
  }

  /* ---------------- 状态 ---------------- */

  snapshot() {
    const peers = [];
    for (const p of this.peers.values()) {
      peers.push({
        address: p.address,
        port: p.port,
        family: p.family,
        idle: Math.round((Date.now() - p.lastSeen) / 1000),
      });
    }
    const streams = [];
    let bytesIn = 0, bytesOut = 0;
    for (const { stream } of this.streams.values()) {
      bytesIn += stream.bytesIn;
      bytesOut += stream.bytesOut;
      streams.push({
        id: stream.id,
        port: stream.meta.port,
        in: stream.bytesIn,
        out: stream.bytesOut,
        buffered: stream.buffered,
      });
    }
    return {
      role: this.role,
      established: this.established,
      localPort: this.localPort,
      peers,
      streams,
      rxBytes: this.rxBytes,
      txBytes: this.txBytes,
      streamBytesIn: bytesIn,
      streamBytesOut: bytesOut,
    };
  }

  close() {
    if (this.destroyed) return;
    this.destroyed = true;
    clearInterval(this._helloTimer);
    clearInterval(this._keepTimer);
    clearInterval(this._sweepTimer);
    for (const { stream } of this.streams.values()) stream.destroy('tunnel-close');
    this.streams.clear();
    for (const s of [this.s4, this.s6]) {
      if (!s) continue;
      try { s.close(); } catch (_) {}
    }
    this.s4 = this.s6 = null;
    this.emit('close');
  }
}

module.exports = { Tunnel };
