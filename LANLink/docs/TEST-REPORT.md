# 测试报告

环境：Windows 11 / Node.js v22.22.2 / 回环网络
执行：`npm test`（等价于下方命令）

```
node --test test/proto.test.js test/ticket.test.js test/stun.test.js \
           test/rstream.test.js test/e2e.test.js test/app.test.js
```

## 1. 结果总览

```
# tests 27
# pass 27
# fail 0
```

| 测试文件 | 用例数 | 覆盖内容 |
| --- | --- | --- |
| `proto.test.js` | 5 | 线格式编解码、边界值、畸形包不崩溃 |
| `ticket.test.js` | 6 | 连接码 v4/v6 往返、CRC 篡改检测、非法输入、长度预算 |
| `stun.test.js` | 6 | STUN 报文识别、XOR-MAPPED 解析、老式属性兼容、缺失属性 |
| `rstream.test.js` | 4 | 无损伤传输、丢包+乱序+重复、双向并发、FIN 语义 |
| `e2e.test.js` | 3 | 真实 UDP 握手、96 KB TCP 回声逐字节一致、错误密钥拒绝、端口白名单 |
| `app.test.js` | 3 | HTTP 控制面 + 隧道数据面全链路、错误码、参数校验 |

## 2. 关键用例说明

### 2.1 抗丢包（最有说服力的一条）

`rstream.test.js` 用一个确定性的伪随机链路模拟器（自实现 xorshift，不用第三方 mock），
人为注入 **20% 丢包 + 20% 乱序 + 10% 重复**：

```js
const { a, b } = makePair({ loss: 0.2, reorder: 0.2, dup: 0.1, seed: 12345 });
const out = await transfer(a, b, 40 * 1024);
assert.ok(out.equals(a._expected), '存在丢包/乱序时数据必须逐字节一致');
```

结果：**通过**。40 KB 在恶劣链路下仍在 0.31 s 内完成，且逐字节一致 —— 证明序号/ACK/重传/去重逻辑正确。

### 2.2 端到端字节一致性

`e2e.test.js` 起真实 UDP socket，主机侧接回声 TCP 服务，客户端经隧道发 96 KB
并立刻 `end()`（模拟"发完就关"的游戏行为），断言：

```
回声字节数应一致  98304 === 98304   ✓
内容逐字节相等                      ✓
```

这条用例曾经是失败状态，修复了 3 个真实缺陷（半关闭、提前 destroy、sendQueue 未排空）后才通过，
详见 `docs/REVIEW.md` 缺陷 2/3/4。

### 2.3 安全断言

| 断言 | 结果 |
| --- | --- |
| 令牌错误 → 主机 `peers.size === 0`，不触发 `peer-join` | ✓ |
| 客户端请求未授权端口 22 → 主机记录 `denied=[22]`，连接被 RST | ✓ |
| 篡改连接码任一字节 → CRC 校验失败并抛出可读错误 | ✓ |
| 截断的隧道包 → `decode()` 返回 `null`，不抛异常 | ✓ |

### 2.4 全链路（app.test.js）

真实起两个 `App` 实例（模拟两台电脑），一个 `startHost`，一个 `join`，
再起一个真实 TCP 客户端打通 64 KB：

```
HTTP 静态页 200 + 含品牌名        ✓
主机生成连接码，解码后 servicePort 正确  ✓
客户端加入 → 得到 127.0.0.1:xxxxx  ✓
经隧道往返 64 KB，逐字节一致       ✓
主机侧 peers.length === 1          ✓
断开后两端 mode 回到 idle          ✓
```

## 3. 性能基准

`node test/perf.js 8`（8 MB，回环 UDP，Node 22 单线程）：

```
握手耗时         3 ms
往返延迟(64B)    min 0.30 ms   p50 0.35 ms   max 3.15 ms
传输 8 MB        1.42 s        吞吐 5.6 MB/s (45 Mbps)
```

- **延迟**：p50 0.35 ms，说明隧道本身几乎不引入额外延迟（真实跨网会叠加物理 RTT）。
- **吞吐**：45 Mbps，瓶颈是 Node 单线程逐包处理（1100 B/包 → 约 1150 包/秒/MB）。
  对游戏联机（通常 < 5 Mbps）余量充足；如需千兆需改批量收发，属于过度优化，本项目不做。

## 4. 真实环境自检

`node test/live-check.js` —— 不打桩，真连公网 STUN，真起一次主机。
在本机（Windows 11，家庭宽带）实测：

```
主机名           LAPTOP-9UI87AON (win32)
局域网 IPv4      192.168.100.101          ← 正确挑中物理网卡，没被 VMware / Radmin / 热点抢位
  · WLAN                           192.168.100.101  LAN
  · WLAN 2                         192.168.137.1    LAN
  · VMware Network Adapter VMnet8  192.168.200.1    LAN
  · Radmin VPN                     26.75.10.234     PUBLIC
公网 IPv6        无
公网映射         113.133.208.7:38698  [IPv4]
NAT 类型         CONE                    ← 3 个 STUN 服务器 × 2 轮，5 次采样端口完全一致
UPnP 映射        失败/不可用             ← 该环境路由器未开 UPnP，程序如实报告
连接码           LANL-BHGF0AeXLjUR5q1j3ZI   （23 字符）
可用入口         113.133.208.7:38702  via STUN
```

结论：
- STUN 客户端与真实服务器互通，`XOR-MAPPED-ADDRESS` 解析正确；
- 多服务器交叉校验能稳定判定 NAT 类型（早期只查 1 个服务器的版本会误报，已修）；
- 主机在此环境下可提供 STUN 打洞入口，符合设计预期。

## 5. 未覆盖 / 已知缺口

| 项 | 说明 |
| --- | --- |
| 真实跨网 P2P | 需要两台不同网络的机器，本次环境只有一台，无法真实验证打洞成功路径；打洞概率本身也依赖运营商 |
| 对称型 NAT 表现 | 只能验证"正确识别并报警"，无法验证"打洞成功"（本就预期失败） |
| UDP 游戏转发 | 未实现（饥荒、Minecraft 基岩版不支持），见架构文档已知边界 |
| UPnP 成功路径 | 本机路由器未开 UPnP，只验证了失败降级路径；SOAP 报文构造有单测覆盖 |
| iOS 客户端 | 无本地 Node 运行时，仅文档说明 |
| 并发多客户端 | 架构支持（peer 槽位），但无专门压力测试 |

## 6. 复现方式

```bash
# 全量测试
npm test

# 吞吐基准
npm run perf

# 真实环境自检（会访问公网 STUN）
npm run live
```
