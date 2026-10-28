# 代码审核记录

审核方式：写完后逐模块自审 + 用可复现的测试逐条证伪。
下面 6 个问题都是**真实存在、真实复现、真实修复**的，不是编出来的检查项。

---

## 缺陷 1：客户端收到 HELLO_ACK 后被静默丢弃 → 握手永远超时

**现象**：两端都是正确令牌，`connect()` 仍然 7.5 秒后抛「握手超时」。

**根因**：`Tunnel._onPacket` 的分发入口要求先按 `rinfo` 查到 `this.peers` 记录，
查不到就直接 `return`。而 guest 侧从未往 `peers` 里写过任何记录 ——
它只在收到 host 的 `HELLO` 时才登记，可 guest 是主动方，永远不会收到 `HELLO`。
于是 `HELLO_ACK` 100% 被丢弃。

**修复**：`connect()` 里预先按候选端点登记 peer；同时补一条「同端口即同一对端」的兜底匹配，
解决 IPv6 地址被系统展开成 `2001:db8:0:0:...` 导致 key 不一致的问题。

```js
// src/tunnel.js connect()
for (const ep of this._pendingEndpoints) {
  const key = `${ep.address}|${ep.port}`;
  if (!this.peers.has(key)) this.peers.set(key, { key, address: ep.address, port: ep.port, ... });
}
```

**验证**：`e2e.test.js` 第 1 个用例由 fail → pass。

---

## 缺陷 2：`end()` 立即置关闭标志，导致还没发出去的数据全丢

**现象**：客户端 `write(8KB)` 后立刻 `end()`，对端只收到几百字节。

**根因**：原实现 `end()` 第一行就是 `this.closed = true`，而 `_drain()` 的循环条件是
`while (... && !this.closed)`。窗口只有 32 包，8 KB 数据里有 7 个包还在 `pending` 队列排队，
`end()` 一来就再也发不出去了。

**修复**：区分「应用层要求关闭」和「数据已发完」两个状态：

```js
end() { this.finSent = true; this._maybeSendFin(); }

_maybeSendFin() {
  if (!this.finSent || this._finFlushed) return;
  if (this.pending.length || this.sendQueue.size) return;  // 队列没空就不发 FIN
  this._finFlushed = true;
  ...发 3 次 FIN（抗丢包）
}
```

`onAck()` 每次清空队列后都回调一次 `_maybeSendFin()`，保证 FIN 在正确的时机发出。

**验证**：`rstream.test.js` 的丢包用例 + `e2e.test.js` 的 96 KB 回声用例。

---

## 缺陷 3：TCP 半关闭被吞掉，回声数据写不回去

**现象**：隧道里数据已经到了对端，对端也写回了 socket，但发起方收到 0 字节。

**根因**：`net.createServer()` 和 `net.connect()` 默认 `allowHalfOpen: false`。
客户端 `end()` 后，服务端 socket 一收到 FIN 就**自动关闭写方向**，
我们随后想 `socket.write()` 回数据时已经写不动了。
调试日志里的关键两行：

```
[guest] sock end
[guest] sock close          ← 此时才开始往回写，已经晚了
[echo] recv 8192
```

**修复**：所有 `net.createServer` / `net.connect` 显式开半关闭：

```js
net.createServer({ allowHalfOpen: true }, (sock) => { ... });
net.connect({ host, port, allowHalfOpen: true }, cb);
```

并在 `bridge.js` 里明确实现半关闭语义：收到 `end` 只 `stream.end()`（关写方向），不 destroy。

---

## 缺陷 4：socket `close` 事件里直接 destroy，把在途数据掐断

**现象**：修完缺陷 3 后，仍然偶发丢最后一段数据。

**根因**：`bridge.js` 原来把 `socket.on('close')` 直接连到 `shutdown()` → `stream.destroy()`。
socket 关闭时，隧道里可能还压着几十 KB 没确认的数据，一 destroy 就全没了。

**修复**：拆成两个独立状态位，双向都收尾才销毁；出错路径才直接 RST。

```js
socket.on('close', () => { sockDone = true; stream.end(); finish(); });   // 优雅
socket.on('error', () => { sockDone = true; stream.destroy('socket-error'); }); // 异常
stream.on('close', () => { try { socket.destroy(); } catch {} });         // 流彻底结束才毁 socket
```

---

## 缺陷 5：IPv6 socket 绑到了另一个随机端口

**现象**：界面显示「IPv6 直连」入口，但该端口没有任何服务在听。

**根因**：`start()` 里先后 `bind(0)` 了 udp4 和 udp6 两个 socket。
端口填 0 意味着「内核随便给」，于是两个 socket 拿到**两个不同的随机端口**，
而对外公布用的是 udp4 的端口。

**修复**：先绑 udp4 拿到端口，把它写回 `this.port`，再让 udp6 复用它。

```js
this.s4 = await this._bind('udp4');
this.localPort = this.s4.address().port;
if (this.port === 0) this.port = this.localPort;   // 关键：让 udp6 绑同一个号
this.s6 = await this._bind('udp6');
```

---

## 缺陷 6：重传时引用了不存在的字段

**现象**：一旦发生超时重传，`throw` 出 `p.seq is undefined`（发给对端的 seq 变成 0）。

**根因**：`sendQueue` 是 `Map<seq, {buf, ts, tries}>`，序号在 key 上，value 里没有 `seq`。
重传代码却写了 `this.tunnel._send(T.DATA, this.id, p.seq, ...)`。

**修复**：改为 `for (const [seq, p] of this.sendQueue)` 使用 key。

---

## 安全自查

| 检查项 | 结论 |
| --- | --- |
| 硬编码密钥/密码 | 无。令牌每次启动 `crypto.randomBytes(4)` 生成 |
| SQL 注入 | 无数据库 |
| 命令注入 | 唯一 `exec` 是打开浏览器，URL 由本机端口拼接，不含用户输入 |
| 路径穿越 | `_static()` 用 `path.resolve` 后做前缀校验，`../` 被拒 |
| XSS | 前端所有动态文本走 `textContent`；`innerHTML` 只用于固定模板 |
| 拒绝服务 | 请求体 64 KB 上限；`recvMap` 超过 4 倍窗口即断流；单包最大 1114 字节 |
| 时序攻击 | 握手 MAC 用 `crypto.timingSafeEqual` |
| 端口越权 | 主机侧白名单，只转发 `servicePort`，其它端口直接 RST |
| 控制台暴露 | HTTP 只监听 `127.0.0.1` |
| 隐私 | 无遥测、无上报；STUN 仅用于获知自己的公网地址，可用 `LANLINK_STUN=` 彻底关闭 |

---

## 性能自审

回环 UDP 实测（`node test/perf.js 8`，Node 22 / Windows）：

```
握手耗时         3 ms
往返延迟(64B)    min 0.30 ms  p50 0.35 ms  max 3.15 ms
传输 8 MB        1.42 s   吞吐 5.6 MB/s (45 Mbps)
```

45 Mbps 对"联机打游戏"（通常 < 5 Mbps）绰绰有余，瓶颈在 Node 单线程的逐包处理。
如果要提到千兆级，需要把 1100B 的包改成批量发送（GSO 风格），
但那属于过度优化 —— 本项目明确不做。

---

## 刻意没做的事（避免过度设计）

| 没做 | 为什么 |
| --- | --- |
| 拥塞控制 | 单用户、局域网级链路，拥塞控制只会让游戏更卡 |
| 选择性重传 (SACK) | 累积 ACK + 快速重传已足够；SACK 增加状态机复杂度 |
| 多端口同时转发 | 需求是"联一个游戏"，多端口是 YAGNI |
| 独立的协调服务器 | 直接违背"不接第三方"的硬要求 |
| 账号体系 / 房间列表 | 同上 |
| TypeScript / 打包工具 | 2000 行以内，纯 JS + 原生 ESM-less 更易读、零构建 |
