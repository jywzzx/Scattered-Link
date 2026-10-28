# 架构说明（v2 · 虚拟局域网版）

## 1. 一句话

一个 Swing 图形界面（`LANLink.exe`）+ 一个原版 EasyTier 内核（`easytier-core.exe`），
用"邀请码"把用户本该填的七八个参数压成一行文本。

**对开源代码的改动量：0 行。**

## 2. 进程与权限

```
用户双击 LANLink.exe
   │
   ├─(非管理员) → PowerShell Start-Process -Verb RunAs → 弹一次 UAC
   │                                                    → 以管理员身份重新拉起自己
   ▼
JVM (runtime\bin\javaw.exe)  ← 由 jpackage 生成的 exe 启动
   │
   ├─ Swing 主窗口（lanlink.ui.AppFrame）
   └─ 子进程（lanlink.core.Engine）
         │
         ▼
      easytier-core.exe  ← 原版二进制，参数由我们生成
         │
         ├─ Wintun 创建虚拟网卡 10.144.0.x/24   ← 需要管理员
         ├─ P2P 打洞（TCP/UDP）+ 中继兜底
         └─ UDP 广播中继（--enable-udp-broadcast-relay，Windows 专属）
```

为什么必须要管理员：Windows 上创建虚拟网卡必须加载 Wintun 内核驱动。
Radmin VPN 装驱动时同样要提权，这是平台限制，不是设计选择。

## 3. 目录结构

```
LANLink-v2/
├── LANLink.exe              启动器（jpackage 生成，带图标，无控制台窗口）
├── app/
│   ├── LANLink.jar          我们的全部代码（约 1.8k 行 Java）
│   └── engine/              EasyTier 原版（未修改）
│       ├── easytier-core.exe    23.3 MB
│       ├── easytier-cli.exe      9.5 MB
│       ├── wintun.dll          虚拟网卡驱动
│       ├── WinDivert64.sys     广播中继用
│       └── Packet.dll
├── runtime/                 精简 JRE 17（jpackage 自带，用户无需装 Java）
├── 使用说明.txt
└── 手机端/
    ├── LANLink-手机版.apk   EasyTier 官方 Android 包（Apache-2.0）
    └── ...
```

## 4. 邀请码设计

```
   113.133.208.7 : 11010 # k7m2q9
   └─ 房主公网 IP  └ 端口  └ 6 位房间密码
```

关键设计：**房间名不单独传输，而是由密码派生**：

```java
roomName = "lanlink-" + sha256("lanlink-v2|" + password)[0..3].hex   // 8 位十六进制
```

好处：

- 用户只需记住/转达"IP + 密码"两件事，不用理解"网络名称"是什么。
- 密码一致 → 房间名必然一致；密码不同 → 天然隔离，连错房间都不可能。
- 邀请码可手抄、可电话口述。

解析器做了**容错**：能从 `邀请码：113.133.208.7:11010#k7m2q9 你看下` 这种整段聊天文本里把代码抠出来，
也接受 `IP:port#pwd`、`IP#pwd`、`[IPv6]:port#pwd` 三种写法。
同时**严格校验**：`113.133.208.7:abc` 这种半截地址会被明确拒绝，不会带着错误配置去连。

## 5. 网络参数表

| 项 | 值 | 说明 |
|---|---|---|
| 虚拟网段 | `10.144.0.0/24` | 刻意避开 192.168/10.0/172.16 等家用网段，减少冲突 |
| 房主虚拟 IP | `10.144.0.1` | 固定，方便朋友记 |
| 加入者虚拟 IP | `10.144.0.2~254` | 随机，避免撞号 |
| 房主监听 | `tcp/udp 11010` | 邀请码里的端口 |
| 加入者监听 | `tcp/udp 11020` | **必须显式指定**，否则内核默认端口组会和房主撞车（实测 os error 10048） |
| 本机 RPC | `127.0.0.1:15888` | 仅本机，用于查询对端列表 |

## 6. 状态机

```
            ┌──────────┐
            │  IDLE    │ 首页：两个大按钮
            └────┬─────┘
     建房 │            │ 加入
          ▼            ▼
   ┌───────────┐  ┌───────────┐
   │ HOSTING   │  │ JOINING   │  调 Engine.start()
   │ 显示邀请码 │  │ 握手 1~3s │
   └─────┬─────┘  └─────┬─────┘
         │ 朋友进来       │
         ▼               ▼
   ┌─────────────────────────┐
   │ CONNECTED               │  每 2s 调 easytier-cli peer
   │ 显示对端虚拟 IP / ping  │  刷新成员列表 + 更新状态灯
   └──────────┬──────────────┘
              │ 点【断开】
              ▼
           IDLE（并 taskkill 兜底清理内核进程）
```

对端列表的获取不依赖内核日志格式：把 `easytier-cli peer` 输出里所有
`10.144.0.x` 抓出来，排除自己即可。日志格式改了也不会崩。

## 7. 安全边界

| 面 | 措施 |
|---|---|
| 谁能进这个虚拟局域网 | EasyTier `network_secret`（6 位随机密码，每次建房重新生成）+ 房间名派生 |
| 传输加密 | EasyTier 默认 AES-GCM（未关闭） |
| 手机/电脑能看到什么 | 只有同一 `network_name` 的节点；与你的真实局域网隔离 |
| 本机管理接口 | RPC 只监听 `127.0.0.1:15888` |
| 软件本身的网络行为 | 不注册、不上报、无遥测；只连你填的对端地址 |

## 8. 已知限制（诚实列出）

1. **三层（TUN）而非二层（TAP）**
   "能 ping 通、能直连虚拟 IP"是保证的；"游戏列表里自动出现房间"只在
   开启了 `--enable-udp-broadcast-relay` 的 Windows 上比较可靠。
   原因详见 `RESEARCH.md` 第三节。

2. **手机端不是自编译的 APK**
   本机没有 Android SDK/NDK，无法从源码编译并签名。交付的是 EasyTier 官方
   ARM64 包（Apache-2.0，可自由分发），已由上游在真机验证。

3. **EasyTier 不支持用 127.0.0.1 作为对端地址**
   实测会返回 `os error 10049 (AddrNotAvailable)`。同机测试必须用真实网卡 IP。

4. **首次运行必须过一次 UAC**，且必须装驱动（Wintun 随包提供，无需单独下载）。

5. **对称型 NAT + UDP 被封的环境**仍可能失败：EasyTier 会尝试 TCP 打洞和
   生日攻击打洞，但没有任何方案能保证 100%。
