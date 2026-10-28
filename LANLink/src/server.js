'use strict';
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const net = require('node:net');
const { EventEmitter } = require('node:events');

const { Tunnel } = require('./tunnel');
const { bridgeTcp } = require('./bridge');
const { Upnp } = require('./upnp');
const { collectLocal, probePublic } = require('./netinfo');
const ticket = require('./ticket');
const { randomToken, Logger, formatBytes } = require('./util');

const PUBLIC_DIR = path.join(__dirname, '..', 'public');
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
};

class App extends EventEmitter {
  constructor(opts = {}) {
    super();
    this.log = opts.logger || new Logger('app');
    this.wantPort = opts.httpPort || 7788;
    this.mode = 'idle';
    this.tunnel = null;
    this.upnp = null;
    this.publicInfo = null;
    this.host = null;
    this.guest = null;
    this.httpServer = null;
    this.sse = new Set();
    this.net = collectLocal();
    this.startedAt = Date.now();
    this.lastTick = { t: Date.now(), rx: 0, tx: 0, rate: { rx: 0, tx: 0 } };
    this.events = []; // 最近事件（UI 日志）
  }

  /* ============ 事件/日志 ============ */

  pushEvent(level, text) {
    const ev = { t: Date.now(), level, text };
    this.events.push(ev);
    if (this.events.length > 200) this.events.shift();
    this.broadcast({ type: 'event', event: ev });
    const fn = level === 'error' ? 'error' : level === 'warn' ? 'warn' : 'info';
    this.log[fn](text);
  }

  /* ============ HTTP ============ */

  async listen() {
    const server = http.createServer((req, res) => this._route(req, res));
    this.httpServer = server;

    for (let i = 0; i < 12; i++) {
      const port = this.wantPort + i;
      try {
        await new Promise((resolve, reject) => {
          server.once('error', reject);
          server.listen(port, '127.0.0.1', () => {
            server.off('error', reject);
            resolve();
          });
        });
        this.httpPort = port;
        break;
      } catch (e) {
        if (e.code !== 'EADDRINUSE') throw e;
        this.log.warn(`端口 ${port} 被占用，尝试下一个`);
      }
    }
    if (!this.httpPort) throw new Error('找不到可用 HTTP 端口');

    const tick = setInterval(() => {
      if (this.sse.size === 0) return;
      this.broadcast({ type: 'state', state: this.snapshot() });
    }, 1000);
    tick.unref();
    this._tick = tick;

    return this.httpPort;
  }

  _route(req, res) {
    const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    const p = decodeURIComponent(url.pathname);

    if (req.method === 'GET' && (p === '/' || p === '/index.html')) {
      return this._static(res, 'index.html');
    }
    if (req.method === 'GET' && p.startsWith('/static/')) {
      return this._static(res, p.slice('/static/'.length));
    }
    if (req.method === 'GET' && (p === '/manifest.webmanifest' || p === '/icon.svg')) {
      return this._static(res, p.slice(1));
    }
    if (req.method === 'GET' && p === '/api/state') {
      return this._json(res, 200, this.snapshot());
    }
    if (req.method === 'GET' && p === '/api/events') {
      return this._sse(req, res);
    }
    if (req.method === 'GET' && p === '/api/net') {
      this.net = collectLocal();
      return this._json(res, 200, { net: this.net, public: this.publicInfo });
    }
    if (req.method === 'POST') {
      return this._readBody(req).then((body) => this._post(p, body, res)).catch((e) => {
        this._json(res, 400, { error: e.message });
      });
    }
    this._json(res, 404, { error: 'not found' });
  }

  async _post(p, body, res) {
    try {
      switch (p) {
        case '/api/host/start': {
          const out = await this.startHost(body);
          return this._json(res, 200, out);
        }
        case '/api/host/stop': {
          await this.stopAll();
          return this._json(res, 200, { ok: true });
        }
        case '/api/join': {
          const out = await this.join(body);
          return this._json(res, 200, out);
        }
        case '/api/leave': {
          await this.stopAll();
          return this._json(res, 200, { ok: true });
        }
        case '/api/net/refresh': {
          this.net = collectLocal();
          if (this.tunnel && this.tunnel.started) {
            this.publicInfo = await probePublic(this.tunnel);
          }
          return this._json(res, 200, { net: this.net, public: this.publicInfo });
        }
        default:
          return this._json(res, 404, { error: 'not found' });
      }
    } catch (e) {
      this.log.error('API 错误:', e.message);
      return this._json(res, 500, { error: e.message });
    }
  }

  _static(res, rel) {
    const full = path.resolve(PUBLIC_DIR, rel);
    if (!full.startsWith(path.resolve(PUBLIC_DIR))) {
      return this._json(res, 403, { error: 'forbidden' });
    }
    fs.readFile(full, (err, data) => {
      if (err) return this._json(res, 404, { error: 'not found' });
      res.writeHead(200, {
        'Content-Type': MIME[path.extname(full).toLowerCase()] || 'application/octet-stream',
        'Cache-Control': 'no-cache',
      });
      res.end(data);
    });
  }

  _json(res, code, obj) {
    const buf = Buffer.from(JSON.stringify(obj));
    res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': buf.length });
    res.end(buf);
  }

  _readBody(req) {
    return new Promise((resolve, reject) => {
      let size = 0;
      const chunks = [];
      req.on('data', (c) => {
        size += c.length;
        if (size > 64 * 1024) { reject(new Error('请求体过大')); req.destroy(); return; }
        chunks.push(c);
      });
      req.on('end', () => {
        if (!chunks.length) return resolve({});
        try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); }
        catch (_) { reject(new Error('JSON 解析失败')); }
      });
      req.on('error', reject);
    });
  }

  _sse(req, res) {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-cache, no-transform',
      Connection: 'keep-alive',
      'X-Accel-Buffering': 'no',
    });
    res.write('retry: 2000\n\n');
    this.sse.add(res);
    res.write(`data: ${JSON.stringify({ type: 'state', state: this.snapshot() })}\n\n`);
    req.on('close', () => this.sse.delete(res));
  }

  broadcast(obj) {
    const line = `data: ${JSON.stringify(obj)}\n\n`;
    for (const res of this.sse) {
      try { res.write(line); } catch (_) { this.sse.delete(res); }
    }
  }

  /* ============ 主机模式 ============ */

  async startHost(cfg = {}) {
    if (this.mode !== 'idle') throw new Error('当前已有连接，请先断开');

    const servicePort = Number(cfg.servicePort);
    if (!Number.isInteger(servicePort) || servicePort <= 0 || servicePort > 65535) {
      throw new Error('请填写有效的游戏/服务端口');
    }
    const serviceHost = cfg.serviceHost || '127.0.0.1';
    const tunnelPort = Number(cfg.tunnelPort) || 0;

    this.net = collectLocal();
    const token = randomToken(4);
    const tunnel = new Tunnel({
      role: 'host',
      port: tunnelPort,
      token,
      logger: this.log.child('tunnel'),
    });

    this.pushEvent('info', `正在启动隧道（UDP ${tunnelPort || '随机'}）...`);
    await tunnel.start();
    this.tunnel = tunnel;
    this.mode = 'host';

    tunnel.on('stream', (stream, port, peer) => this._onIncomingStream(stream, port, peer));
    tunnel.on('peer-join', (p) => {
      this.pushEvent('ok', `🎮 ${p.address}:${p.port} 已接入`);
      this.broadcast({ type: 'state', state: this.snapshot() });
    });
    tunnel.on('peer-leave', (p) => {
      this.pushEvent('warn', `${p.address}:${p.port} 已离线`);
    });

    // UPnP 端口映射（尽力而为）
    let upnpIp = null;
    let mapped = false;
    try {
      this.upnp = new Upnp(this.log.child('upnp'));
      const ctx = await this.upnp.discover();
      if (ctx) {
        upnpIp = await this.upnp.getExternalIp();
        mapped = await this.upnp.addMapping({ localIp: this.net.primaryLanIp, port: tunnel.localPort });
        if (mapped) this.pushEvent('ok', `路由器已自动放行 UDP ${tunnel.localPort}`);
        else this.pushEvent('warn', 'UPnP 映射失败，将依赖打洞/IPv6');
      } else {
        this.pushEvent('warn', '未发现 UPnP 网关（路由器可能关闭了该功能）');
      }
    } catch (e) {
      this.log.debug('UPnP 流程异常:', e.message);
    }

    // 公网映射探测
    this.publicInfo = await probePublic(tunnel);
    if (this.publicInfo.ok) {
      this.pushEvent('info', `公网映射 ${this.publicInfo.mapped.address}:${this.publicInfo.mapped.port}（NAT: ${this.publicInfo.natType}）`);
    } else {
      this.pushEvent('warn', this.publicInfo.reason);
    }

    // 选出最优对外地址：可靠性优先 UPnP → IPv6 → STUN(仅锥型 NAT 有效) → 局域网
    const endpoints = [];
    const pub = this.publicInfo.ok ? this.publicInfo.mapped : null;
    const natType = this.publicInfo.natType || 'UNKNOWN';

    if (mapped && upnpIp && upnpIp !== '0.0.0.0') {
      endpoints.push({ address: upnpIp, port: tunnel.localPort, family: 'IPv4', via: 'UPnP' });
    }
    if (this.net.globalIpv6) {
      endpoints.push({ address: this.net.globalIpv6, port: tunnel.localPort, family: 'IPv6', via: 'IPv6直连' });
    }
    if (pub && pub.family === 'IPv4') {
      const usable = natType !== 'SYMMETRIC';
      endpoints.push({
        address: pub.address,
        port: pub.port,
        family: 'IPv4',
        via: usable ? 'STUN' : 'STUN(对称NAT)',
        reliable: usable,
      });
    }
    if (!endpoints.length) {
      endpoints.push({ address: this.net.primaryLanIp, port: tunnel.localPort, family: 'IPv4', via: '仅局域网' });
      this.pushEvent('error', '没有可用的公网地址：朋友只能在同一 WiFi 下连接。建议开启路由器 UPnP 或使用 IPv6。');
    } else if (natType === 'SYMMETRIC' && !mapped && !this.net.globalIpv6) {
      this.pushEvent('error', '检测到对称型 NAT 且 UPnP 不可用：打洞大概率失败。请到路由器开启 UPnP，或联系宽商开通公网 IPv6。');
    } else if (!mapped && this.net.globalIpv6) {
      this.pushEvent('info', 'UPnP 不可用，已改用 IPv6 直连入口（朋友的网络也需要 IPv6）。');
    }

    const primary = endpoints[0];
    const code = ticket.encode({
      address: primary.address,
      port: primary.port,
      token,
      servicePort,
    });

    this.host = {
      serviceHost,
      servicePort,
      code,
      keyHex: token.toString('hex'),
      endpoints,
      primary,
      connectedAt: null,
    };

    this.pushEvent('ok', `主机已就绪，连接码已生成（${primary.via}）`);
    return {
      ok: true,
      role: 'host',
      code,
      keyHex: this.host.keyHex,
      endpoints,
      servicePort,
      localPort: tunnel.localPort,
      natType: this.publicInfo.natType,
      upnp: mapped,
      public: this.publicInfo,
      lanIp: this.net.primaryLanIp,
    };
  }

  _onIncomingStream(stream, port, peer) {
    const allow = this.host && (port === this.host.servicePort || this._extraAllowed(port));
    if (!allow) {
      this.pushEvent('warn', `拒绝访问未授权端口 ${port}（来自 ${peer.address}）`);
      stream.destroy('port-denied');
      return;
    }
    const sock = net.connect({ host: this.host.serviceHost, port, allowHalfOpen: true }, () => {
      this.log.info(`转发到本地 ${this.host.serviceHost}:${port}`);
    });
    sock.on('error', (e) => {
      this.pushEvent('error', `无法连接本地服务 ${this.host.serviceHost}:${port} — ${e.message}`);
      stream.destroy('dial-failed');
    });
    bridgeTcp(sock, stream, this.log.child('bridge'));
    this.pushEvent('info', `新连接 → 本地 ${this.host.serviceHost}:${port}`);
    this.broadcast({ type: 'state', state: this.snapshot() });
  }

  _extraAllowed(port) {
    // 预留位：如需多端口，可在这里扩展白名单
    return false;
  }

  /* ============ 加入模式 ============ */

  async join(cfg = {}) {
    if (this.mode !== 'idle') throw new Error('当前已有连接，请先断开');

    let info;
    if (cfg.code && String(cfg.code).trim()) {
      info = ticket.decode(cfg.code);
    } else if (cfg.address) {
      const addr = String(cfg.address).trim().replace(/^\[|\]$/g, '');
      const port = Number(cfg.tunnelPort);
      const servicePort = Number(cfg.servicePort);
      const keyHex = String(cfg.keyHex || '').trim().toLowerCase();
      if (!addr) throw new Error('请填写对方 IP');
      if (!Number.isInteger(port) || port <= 0 || port > 65535) throw new Error('隧道端口无效');
      if (!Number.isInteger(servicePort) || servicePort <= 0 || servicePort > 65535) throw new Error('服务端口无效');
      if (!/^[0-9a-f]{8}$/.test(keyHex)) throw new Error('密钥应为 8 位十六进制字符');
      info = {
        address: addr,
        port,
        token: Buffer.from(keyHex, 'hex'),
        servicePort,
        family: addr.includes(':') ? 'IPv6' : 'IPv4',
      };
    } else {
      throw new Error('请输入连接码');
    }

    const listenPortWanted = Number(cfg.listenPort) || info.servicePort;
    this.net = collectLocal();

    const tunnel = new Tunnel({
      role: 'guest',
      token: info.token,
      logger: this.log.child('tunnel'),
    });

    this.pushEvent('info', `正在连接 ${info.address}:${info.port} ...`);
    await tunnel.start();
    this.tunnel = tunnel;
    this.mode = 'guest';

    try {
      await tunnel.connect([{ address: info.address, port: info.port, family: info.family }]);
    } catch (e) {
      await this.stopAll();
      throw new Error(`${e.message}。排查：1) 端口是否被防火墙拦截 2) 对方是否仍在等待 3) 尝试 IPv6 或开启路由器 UPnP`);
    }

    tunnel.on('peer-leave', () => {
      this.pushEvent('warn', '与主机的连接已断开');
      this.broadcast({ type: 'state', state: this.snapshot() });
    });

    // 本地监听：朋友（本机）把它当成对方的服务器
    const server = net.createServer({ allowHalfOpen: true }, (sock) => {
      let stream;
      try {
        stream = tunnel.openStream(info.servicePort);
      } catch (e) {
        sock.destroy();
        this.pushEvent('error', '创建隧道流失败: ' + e.message);
        return;
      }
      bridgeTcp(sock, stream, this.log.child('bridge'));
      this.pushEvent('info', `本地连接 → 远端 ${info.servicePort}`);
    });
    let listenPort = listenPortWanted;
    let ok = false;
    let lastErr = null;
    for (let i = 0; i < 12 && !ok; i++) {
      const candidate = listenPortWanted + i;
      try {
        await new Promise((resolve, reject) => {
          const onErr = (e) => { server.off('listening', onOk); reject(e); };
          const onOk = () => { server.off('error', onErr); resolve(); };
          server.once('error', onErr);
          server.once('listening', onOk);
          server.listen(candidate, '127.0.0.1');
        });
        listenPort = candidate;
        ok = true;
      } catch (e) {
        lastErr = e;
        if (e.code !== 'EADDRINUSE') break;
        this.pushEvent('info', `本地端口 ${candidate} 已被占用，改用 ${candidate + 1}`);
      }
    }
    if (!ok) throw new Error('找不到可用的本地转发端口' + (lastErr ? `（${lastErr.message}）` : ''));

    this.guest = {
      info,
      listenPort,
      server,
      localAddress: `127.0.0.1:${listenPort}`,
      connectedAt: Date.now(),
    };
    this.publicInfo = await probePublic(tunnel);
    this.pushEvent('ok', `已连上主机，本机访问 127.0.0.1:${listenPort} 即可进入对方局域网服务`);

    return {
      ok: true,
      role: 'guest',
      listenPort,
      localAddress: `127.0.0.1:${listenPort}`,
      remote: `${info.address}:${info.port}`,
      servicePort: info.servicePort,
    };
  }

  /* ============ 状态 / 关闭 ============ */

  snapshot() {
    const now = Date.now();
    const t = this.tunnel;
    let rx = 0, tx = 0;
    if (t) { rx = t.rxBytes; tx = t.txBytes; }
    const dt = (now - this.lastTick.t) / 1000;
    if (dt > 0.2) {
      this.lastTick.rate = {
        rx: Math.max(0, Math.round((rx - this.lastTick.rx) / dt)),
        tx: Math.max(0, Math.round((tx - this.lastTick.tx) / dt)),
      };
      this.lastTick.t = now;
      this.lastTick.rx = rx;
      this.lastTick.tx = tx;
    }

    return {
      mode: this.mode,
      uptime: Math.round((now - this.startedAt) / 1000),
      httpPort: this.httpPort,
      lanIp: this.net.primaryLanIp,
      hostname: this.net.hostname,
      platform: this.net.platform,
      ipv4: this.net.ipv4,
      ipv6: this.net.ipv6,
      globalIpv6: this.net.globalIpv6,
      public: this.publicInfo,
      host: this.host
        ? {
            code: this.host.code,
            keyHex: this.host.keyHex,
            servicePort: this.host.servicePort,
            endpoints: this.host.endpoints,
            primary: this.host.primary,
          }
        : null,
      guest: this.guest
        ? {
            localAddress: this.guest.localAddress,
            listenPort: this.guest.listenPort,
            remote: `${this.guest.info.address}:${this.guest.info.port}`,
            servicePort: this.guest.info.servicePort,
          }
        : null,
      tunnel: t && t.started ? t.snapshot() : null,
      rate: this.lastTick.rate,
      totals: { rx, tx, text: `${formatBytes(tx)} ↑ / ${formatBytes(rx)} ↓` },
      events: this.events.slice(-40),
    };
  }

  async stopAll() {
    const t = this.tunnel;
    if (t) {
      const port = t.localPort;
      const wasHost = this.mode === 'host';
      t.close();
      this.tunnel = null;
      if (wasHost && this.upnp) {
        await this.upnp.deleteMapping({ port }).catch(() => {});
      }
    }
    if (this.guest && this.guest.server) {
      await new Promise((r) => this.guest.server.close(() => r()));
    }
    this.mode = 'idle';
    this.host = null;
    this.guest = null;
    this.publicInfo = null;
    this.pushEvent('info', '已断开');
    this.broadcast({ type: 'state', state: this.snapshot() });
    return { ok: true };
  }

  async destroy() {
    clearInterval(this._tick);
    await this.stopAll().catch(() => {});
    if (this.httpServer) await new Promise((r) => this.httpServer.close(() => r()));
    for (const res of this.sse) { try { res.end(); } catch (_) {} }
    this.sse.clear();
  }
}

module.exports = { App };
