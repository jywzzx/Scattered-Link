# 测试报告（v2 · 虚拟局域网版）

## 1. 自检结果总览

```
pass 362 / fail 0
```

运行方式（构建时自动执行）：

```
powershell -ExecutionPolicy Bypass -File build\build.ps1
```

| 分组 | 断言数 | 覆盖内容 |
|---|---|---|
| 邀请码往返 | 8 | `IP:port#pwd` / `IP#pwd` / `[IPv6]:port#pwd` / 空格容错 / 只填 IP+密码 |
| 邀请码拒绝 | 9 | null、空串、纯空格、缺密码、密码过短、端口越界、端口非数字、半截 IP |
| 聊天文本抽取 | 2 | `邀请码：xxx 你看下` 整段粘贴 |
| 房间名派生 | 4 | 同密码必同房间 / 异密码必异房间 / 前缀正确 |
| 虚拟 IP | 300+ | 房主固定 `.1`；加入者 300 次采样全部落在 `.2~.254` |
| 密码生成 | 2 | 长度 6 / 字符集 `[a-z2-9]{6}` |
| STUN | 12 | XOR-MAPPED 解析、非异或兼容、空包/短包/txid 不符/随机字节不崩 |
| 公网 IP 判定 | 7 | 公网 / 10 / 192.168 / 172.16 / CGNAT 100.64 / 回环 / null |
| 本机网络 | 3 | 取到合法 IP / 主机名非空 / 网卡可枚举 |

## 2. 引擎联调（真实内核，非模拟）

三轮迭代，每轮都是真起两个 `easytier-core.exe` 进程：

| 轮次 | 命令要点 | 结果 | 结论 |
|---|---|---|---|
| 1 | 加入方只给 `-p` | **失败** `os error 10048` | 内核默认监听端口与房主 11010/11011 撞车 |
| 2 | 加入方加 `-l 11020`，对端填 `127.0.0.1` | **失败** `os error 10049` | EasyTier 不支持环回地址作对端 |
| 3 | 加入方 `-l 11020`，对端填 `192.168.100.101` | **成功** | 见下方实测输出 |

第 3 轮实测（`easytier-cli peer`）：

```
| ipv4          | hostname | cost | lat(ms) | loss | rx      | tx      | tunnel | NAT      |
| 10.144.0.1/24 | host     | Local| -       | -    | -       | -       | -      | Symmetric|
| 10.144.0.2/24 | joiner   | p2p  | 0.51    | 0.0% | 1.76 kB | 1.73 kB | tcp    | Symmetric|
```

- `cost=p2p`：**点对点直连成功**，没走中继
- `lat=0.51ms`：隧道本身几乎不引入延迟
- `loss=0.0%`：零丢包
- `tunnel=tcp`：走了 TCP 隧道（因为指定 `-p tcp://`）

## 3. 成员列表联调（`MemberProbe`，两个真实内核）

这是用户最关心的能力：**界面上能不能正确看到对方的虚拟 IP**。

```
本机 IP = 10.107.140.221
房间    = lanlink-b9886de0
host   rpc=127.0.0.1:56504
joiner rpc=127.0.0.1:56509
--- HOST 视角 ---
成员数 = 1
  10.144.0.2   lanlink-LAPTOP-9UI87AON   直连  0.50ms
--- JOINER 视角 ---
成员数 = 1
  10.144.0.1   lanlink-host              直连  0.35ms
RESULT hostSees=true joinerSees=true
exit=0
```

- 两端各自看到**对方**的 IP（不是自己）→ 说明随机 RPC 端口隔离生效
- `直连` + `0.35~0.50ms` → P2P 打通，没走中继
- 数据来自 `easytier-cli -o json peer`，解析由自写的 `Json.parse` 完成

## 4. 打包产物验证

### 4.1 安装自检（`LANLink.exe --check`）

```
java   = 17.0.17
app    = D:\...\LANLink\LANLink.exe
core   = D:\...\LANLink\app\engine\easytier-core.exe
cli    = D:\...\LANLink\app\engine\easytier-cli.exe
lanIp  = 10.107.140.221
admin  = false
exit=0
```

证明：打包后的 exe 能启动、JRE 完整、内核相对路径解析正确、
`jpackage.app-path` 能拿到真实 exe 路径（提权依赖它）。

### 4.2 引擎自检（`--enginetest`，无界面跑真内核）

```
engine_alive=true
room=lanlink-0cdfaf25
LOG| [network_identity]
LOG| network_name = "lanlink-0cdfaf25"
LOG| network_secret = "jbpf8w"
LOG| [flags]
LOG| latency_first = true
LOG| no_tun = true
LOG| new listener added listener=tcp://0.0.0.0:11010
LOG| new listener added listener=udp://0.0.0.0:11010
exit=0
```

证明：
- 我们生成的参数**全部被内核接受**（没有未知参数被拒）；
- 房间名确实由密码派生且格式正确；
- `--latency-first` 生效；
- 内核进程稳定运行 9 秒未退出。

> 注：`--enable-udp-broadcast-relay`（转发局域网广播，让游戏自动发现房间）
> **默认已关闭**——它依赖 WinDivert 内核驱动，国产杀软/Defender 很容易拦，
> 被拦时内核直接起不来。需要时设环境变量 `LANLINK_BROADCAST=1`。

### 3.3 发布包完整性

```
entries 296
OK  LANLink-v2-win64/LANLink.exe
OK  LANLink-v2-win64/app/LANLink.jar
OK  LANLink-v2-win64/app/engine/easytier-core.exe
OK  LANLink-v2-win64/app/engine/wintun.dll
OK  LANLink-v2-win64/README.txt
OK  LANLink-v2-win64/手机端/LANLink-手机版.apk
OK  LANLink-v2-win64/手机端使用说明.txt
zip 71.5 MB（原始 189.4 MB）
```

> 这一步是必要的：之前因为 PowerShell 复制中断，产出过一个 49.6MB 的残缺 zip
> （`app/` 目录是空的，双击必然启动失败）。现在每次打包前强制跑 `--check`，
> 不通过直接返回非 0。详见 `REVIEW.md` B5。

## 4. 没有验证到的部分（如实说明）

| 项 | 原因 | 风险 |
|---|---|---|
| **真实两台机器跨网 P2P** | 只有一台开发机 | 中。打洞成功率取决于双方运营商，上游已支持 TCP/UDP/对称型三类打洞 |
| **GUI 交互（点击流程）** | 无法在本环境观察窗口 | 中。逻辑已在 `--enginetest` 覆盖，风险集中在布局/事件绑定 |
| **UAC 提权后重启自身** | 会弹系统对话框，不适合自动化 | 低。逻辑简单；失败时会提示用户手动"以管理员身份运行" |
| **虚拟网卡真的建起来（TUN 模式）** | 需管理员权限 | 中。`--no-tun` 已验证参数正确，TUN 路径未跑通 |
| **Android 端实机** | 无设备、无 SDK | 低（用的是官方原版 APK，上游已验证） |

## 5. 复现方式

```bash
# 完整构建 + 自检 + 打包
powershell -ExecutionPolicy Bypass -File build\build.ps1
python build\package.py

# 只跑单元自检
java -cp build/classes;build/test-classes lanlink.Tests

# 引擎联调（真起两个内核）
python build\engine-test4.py
```
