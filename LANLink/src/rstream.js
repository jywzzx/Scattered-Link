'use strict';
const { EventEmitter } = require('node:events');
const { T, MAX_PAYLOAD } = require('./proto');

const WINDOW = 32;         // 单流在途包数上限
const RTO_MIN = 220;       // 初始重传超时(ms)
const RTO_MAX = 2000;
const TICK = 50;
const MAX_RETRY = 14;      // 单包最大重传次数
const IDLE_TIMEOUT = 30000; // 长时间无进展判定断线

/**
 * 基于 UDP 的「可靠有序字节流」。
 * 最小子集：包级序号 + 累积 ACK + 超时重传 + 重复 ACK 快速重传 + 固定窗口。
 * 明确不做拥塞控制（局域网联机 / 单用户场景，只会拖慢速度）。
 *
 * 半关闭语义：
 *   end() 只置 finSent 标志，等发送队列清空后才真正发 FIN；
 *   对端 FIN 到达只关闭读方向，本端仍可继续发送剩余数据。
 */
class ReliableStream extends EventEmitter {
  constructor(tunnel, id, meta = {}) {
    super();
    this.tunnel = tunnel;
    this.id = id;
    this.meta = meta;

    this.sendQueue = new Map(); // seq -> { buf, ts, tries }
    this.pending = [];          // 等待窗口的 payload 分片
    this.nextSeq = 0;
    this.rto = RTO_MIN;

    this.recvNext = 0;
    this.recvMap = new Map();

    this.bytesIn = 0;
    this.bytesOut = 0;
    this.lastProgress = Date.now();

    this.finSent = false;       // 应用层要求关闭写方向
    this._finFlushed = false;   // 数据已发完，FIN 已发出
    this.remoteClosed = false;
    this.destroyed = false;
    this.dupAck = 0;

    this.timer = setInterval(() => this._tick(), TICK);
    if (this.timer.unref) this.timer.unref();
  }

  get buffered() {
    let n = 0;
    for (const p of this.pending) n += p.length;
    for (const p of this.sendQueue.values()) n += p.buf.length;
    return n;
  }

  write(chunk) {
    if (this.destroyed || this.finSent) return false;
    if (!chunk || chunk.length === 0) return true;
    for (let off = 0; off < chunk.length; off += MAX_PAYLOAD) {
      this.pending.push(chunk.subarray(off, Math.min(off + MAX_PAYLOAD, chunk.length)));
    }
    this._drain();
    return true;
  }

  _drain() {
    while (this.pending.length && this.sendQueue.size < WINDOW && !this.destroyed) {
      const buf = this.pending.shift();
      const seq = this.nextSeq++;
      this.sendQueue.set(seq, { buf, ts: Date.now(), tries: 0 });
      this.bytesOut += buf.length;
      this.tunnel._send(T.DATA, this.id, seq, this.recvNext, 0, buf);
    }
    this._maybeSendFin();
  }

  _maybeSendFin() {
    if (!this.finSent || this._finFlushed || this.destroyed) return;
    if (this.pending.length || this.sendQueue.size) return;
    this._finFlushed = true;
    let n = 0;
    const send = () => {
      if (this.destroyed) return;
      this.tunnel._send(T.FIN, this.id, 0, this.recvNext, 0, null);
      if (++n < 3) setTimeout(send, 150).unref?.();
    };
    send();
    this._maybeFinish();
  }

  _tick() {
    if (this.destroyed) return;
    const now = Date.now();
    if (now - this.lastProgress > IDLE_TIMEOUT && this.sendQueue.size > 0) {
      this.destroy('idle-timeout');
      return;
    }
    for (const [seq, p] of this.sendQueue) {
      if (now - p.ts > this.rto) {
        if (p.tries >= MAX_RETRY) {
          this.destroy('retry-exhausted');
          return;
        }
        p.tries++;
        p.ts = now;
        this.tunnel._send(T.DATA, this.id, seq, this.recvNext, 0, p.buf);
      }
    }
    this.emit('drain');
  }

  onAck(ack) {
    if (this.destroyed) return;
    let progressed = false;
    for (const seq of this.sendQueue.keys()) {
      if (seq < ack) {
        this.sendQueue.delete(seq);
        progressed = true;
      } else break; // Map 按插入序 == 序号序
    }
    if (progressed) {
      this.dupAck = 0;
      this.rto = RTO_MIN;
      this.lastProgress = Date.now();
      this._drain();
      this.emit('drain');
    } else if (this.sendQueue.size > 0) {
      this.dupAck++;
      if (this.dupAck >= 3) {
        this.dupAck = 0;
        const first = this.sendQueue.entries().next();
        if (!first.done) {
          const [seq, p] = first.value;
          p.ts = Date.now();
          p.tries++;
          this.tunnel._send(T.DATA, this.id, seq, this.recvNext, 0, p.buf);
        }
      }
    } else {
      this._maybeSendFin();
    }
  }

  onData(seq, payload) {
    if (this.destroyed) return;
    this.lastProgress = Date.now();
    this.bytesIn += payload.length;

    if (seq < this.recvNext) {
      this._ack(); // 重复包，仅补 ACK
      return;
    }
    if (!this.recvMap.has(seq)) this.recvMap.set(seq, payload);

    while (this.recvMap.has(this.recvNext)) {
      const p = this.recvMap.get(this.recvNext);
      this.recvMap.delete(this.recvNext);
      this.recvNext++;
      if (p.length) this.emit('data', p);
    }
    if (this.recvMap.size > WINDOW * 4) {
      this.destroy('reorder-overflow');
      return;
    }
    this._ack();
  }

  _ack() {
    this.tunnel._send(T.ACK, this.id, 0, this.recvNext, 0, null);
  }

  end() {
    if (this.finSent || this.destroyed) return;
    this.finSent = true;
    this._maybeSendFin();
  }

  onFin() {
    if (this.destroyed || this.remoteClosed) return;
    this.remoteClosed = true;
    this._ack();
    this.emit('end');
    this._maybeFinish();
  }

  _maybeFinish() {
    if (!this.remoteClosed || !this._finFlushed) return;
    // 双向都收尾了，再留一点时间让最后的 FIN/ACK 落地
    setTimeout(() => this.destroy('done'), 400).unref?.();
  }

  destroy(reason = 'destroy') {
    if (this.destroyed) return;
    this.destroyed = true;
    clearInterval(this.timer);
    this.sendQueue.clear();
    this.pending.length = 0;
    this.recvMap.clear();
    this.emit('close', reason);
  }
}

module.exports = { ReliableStream, WINDOW, RTO_MIN };
