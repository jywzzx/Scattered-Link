'use strict';
const crypto = require('node:crypto');

function randomBytes(n) { return crypto.randomBytes(n); }
function randomToken(n = 4) { return crypto.randomBytes(n); }
function hmac(key, data) { return crypto.createHmac('sha256', key).update(data).digest(); }
function sha256(data) { return crypto.createHash('sha256').update(data).digest(); }

/** 定长常量时间比较，防时序侧信道 */
function safeEqual(a, b) {
  if (!Buffer.isBuffer(a) || !Buffer.isBuffer(b)) return false;
  if (a.length !== b.length) return false;
  return crypto.timingSafeEqual(a, b);
}

function formatBytes(n) {
  if (n < 1024) return `${n} B`;
  if (n < 1048576) return `${(n / 1024).toFixed(1)} KB`;
  if (n < 1073741824) return `${(n / 1048576).toFixed(2)} MB`;
  return `${(n / 1073741824).toFixed(2)} GB`;
}

const LEVELS = { debug: 10, info: 20, warn: 30, error: 40 };

class Logger {
  constructor(scope = 'core', level = process.env.LANLINK_LOG || 'info') {
    this.scope = scope;
    this.level = LEVELS[level] || LEVELS.info;
  }
  _emit(lv, args) {
    if (LEVELS[lv] < this.level) return;
    const ts = new Date().toISOString().slice(11, 23);
    const line = `${ts} ${lv.toUpperCase().padEnd(5)} [${this.scope}] ${args.join(' ')}`;
    (lv === 'error' || lv === 'warn' ? process.stderr : process.stdout).write(line + '\n');
  }
  debug(...a) { this._emit('debug', a); }
  info(...a) { this._emit('info', a); }
  warn(...a) { this._emit('warn', a); }
  error(...a) { this._emit('error', a); }
  child(scope) { const l = new Logger(scope, 'info'); l.level = this.level; return l; }
}

/** 简单环形速率统计 */
class RateMeter {
  constructor(windowMs = 1000) {
    this.windowMs = windowMs;
    this.samples = [];
  }
  add(n) {
    const now = Date.now();
    this.samples.push([now, n]);
    this._trim(now);
  }
  _trim(now) {
    while (this.samples.length && now - this.samples[0][0] > this.windowMs) this.samples.shift();
  }
  /** 每秒速率 */
  rate() {
    const now = Date.now();
    this._trim(now);
    const span = Math.max(this.windowMs, now - (this.samples[0] ? this.samples[0][0] : now));
    let sum = 0;
    for (const s of this.samples) sum += s[1];
    return Math.round((sum * 1000) / span);
  }
  totalRate() { return this.samples.reduce((a, s) => a + s[1], 0); }
}

module.exports = { randomBytes, randomToken, hmac, sha256, safeEqual, formatBytes, Logger, RateMeter };
