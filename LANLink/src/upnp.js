'use strict';
/**
 * 极简 UPnP-IGD 客户端（SSDP 发现 + SOAP AddPortMapping）。
 * 目的是让家庭路由器自动为 UDP 端口开洞，从而免去「打洞失败」的困境。
 * 纯 HTTP/SOAP，无第三方依赖。
 */
const dgram = require('node:dgram');
const http = require('node:http');

const SSDP_ADDR = '239.255.255.250';
const SSDP_PORT = 1900;
const WANTED = /urn:schemas-upnp-org:service:(WANIPConnection|WANPPPConnection):\d/;

function ssdpDiscover(timeout = 1800) {
  return new Promise((resolve) => {
    const locations = new Set();
    let sock;
    try {
      sock = dgram.createSocket('udp4');
    } catch (_) {
      return resolve([]);
    }
    const msg = Buffer.from(
      'M-SEARCH * HTTP/1.1\r\n' +
      `HOST: ${SSDP_ADDR}:${SSDP_PORT}\r\n` +
      'MAN: "ssdp:discover"\r\n' +
      'MX: 1\r\n' +
      'ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n' +
      '\r\n'
    );
    const finish = () => {
      clearTimeout(timer);
      try { sock.close(); } catch (_) {}
      resolve([...locations]);
    };
    const timer = setTimeout(finish, timeout);
    sock.on('message', (buf) => {
      const text = buf.toString('ascii');
      const m = /^location:\s*(.+)$/im.exec(text);
      if (m) locations.add(m[1].trim());
    });
    sock.on('error', finish);
    sock.bind(() => {
      try {
        sock.setBroadcast(true);
        sock.send(msg, 0, msg.length, SSDP_PORT, SSDP_ADDR);
      } catch (_) { finish(); }
    });
  });
}

function httpGet(url, timeout = 2500) {
  return new Promise((resolve, reject) => {
    const req = http.get(url, { timeout }, (res) => {
      let data = '';
      res.setEncoding('utf8');
      res.on('data', (c) => { data += c; });
      res.on('end', () => resolve(data));
    });
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', reject);
  });
}

function httpPost(url, body, soapAction, timeout = 3000) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const req = http.request({
      hostname: u.hostname,
      port: u.port || 80,
      path: u.pathname + u.search,
      method: 'POST',
      timeout,
      headers: {
        'Content-Type': 'text/xml; charset="utf-8"',
        'Content-Length': Buffer.byteLength(body),
        SOAPAction: `"${soapAction}"`,
        Connection: 'close',
      },
    }, (res) => {
      let data = '';
      res.setEncoding('utf8');
      res.on('data', (c) => { data += c; });
      res.on('end', () => resolve({ status: res.statusCode, body: data }));
    });
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', reject);
    req.write(body);
    req.end();
  });
}

function findService(xml, base) {
  const re = /<service>([\s\S]*?)<\/service>/gi;
  let m;
  while ((m = re.exec(xml))) {
    const block = m[1];
    const st = /<serviceType>\s*([^<]+?)\s*<\/serviceType>/i.exec(block);
    const cu = /<controlURL>\s*([^<]+?)\s*<\/controlURL>/i.exec(block);
    if (!st || !cu) continue;
    if (!WANTED.test(st[1])) continue;
    let control = cu[1].trim();
    let controlURL;
    try {
      controlURL = new URL(control, base).toString();
    } catch (_) {
      continue;
    }
    return { serviceType: st[1].trim(), controlURL };
  }
  return null;
}

class Upnp {
  constructor(logger) {
    this.log = logger;
    this.ctx = null;
  }

  async discover() {
    const locations = await ssdpDiscover();
    for (const loc of locations) {
      try {
        const xml = await httpGet(loc);
        const svc = findService(xml, loc);
        if (svc) {
          this.ctx = { ...svc, base: loc };
          this.log.info(`发现 UPnP 网关: ${svc.controlURL}`);
          return this.ctx;
        }
      } catch (e) {
        this.log.debug('解析 IGD 失败:', e.message);
      }
    }
    return null;
  }

  _soap(action, inner) {
    return (
      '<?xml version="1.0"?>' +
      '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" ' +
      's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>' +
      `<u:${action} xmlns:u="${this.ctx.serviceType}">${inner}</u:${action}>` +
      '</s:Body></s:Envelope>'
    );
  }

  async getExternalIp() {
    if (!this.ctx) return null;
    try {
      const body = this._soap('GetExternalIPAddress', '');
      const res = await httpPost(this.ctx.controlURL, body, `${this.ctx.serviceType}#GetExternalIPAddress`);
      const m = /<NewExternalIPAddress>([^<]+)</i.exec(res.body);
      return m ? m[1].trim() : null;
    } catch (_) {
      return null;
    }
  }

  async addMapping({ localIp, port, desc = 'LANLink', ttl = 3600 }) {
    if (!this.ctx) return false;
    const inner =
      '<NewRemoteHost></NewRemoteHost>' +
      `<NewExternalPort>${port}</NewExternalPort>` +
      '<NewProtocol>UDP</NewProtocol>' +
      `<NewInternalPort>${port}</NewInternalPort>` +
      `<NewInternalClient>${localIp}</NewInternalClient>` +
      '<NewEnabled>1</NewEnabled>' +
      `<NewPortDescription>${desc}</NewPortDescription>` +
      `<NewLeaseDuration>${ttl}</NewLeaseDuration>`;
    try {
      const res = await httpPost(this.ctx.controlURL, this._soap('AddPortMapping', inner), `${this.ctx.serviceType}#AddPortMapping`);
      if (res.status === 200) {
        this.log.info(`UPnP 端口映射成功: ${localIp}:${port} (UDP)`);
        return true;
      }
      this.log.warn(`UPnP 映射被拒: HTTP ${res.status} ${res.body.slice(0, 200)}`);
      return false;
    } catch (e) {
      this.log.warn('UPnP 映射失败:', e.message);
      return false;
    }
  }

  async deleteMapping({ port }) {
    if (!this.ctx) return;
    const inner =
      '<NewRemoteHost></NewRemoteHost>' +
      `<NewExternalPort>${port}</NewExternalPort>` +
      '<NewProtocol>UDP</NewProtocol>';
    try {
      await httpPost(this.ctx.controlURL, this._soap('DeletePortMapping', inner), `${this.ctx.serviceType}#DeletePortMapping`);
    } catch (_) {}
  }
}

module.exports = { Upnp, ssdpDiscover, findService };
