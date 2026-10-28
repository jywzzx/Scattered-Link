# 代码审核与缺陷记录（v2）

原则：**只记录真实发生过、真实被复现、真实修掉的问题**。
下面每一条都在开发过程中真实打断过流程。

---

## A. 产品缺陷（会影响用户）

### A0. 界面控件未赋值导致程序启动即崩（v2.4.0 修复）

**现象**：用户双击 exe 后程序无法打开，启动日志记录：
```
2026-10-26 13:42:38  窗口创建失败: java.lang.NullPointerException:
                    Cannot read field "parent" because "<parameter1>" is null
2026-10-26 13:42:38      at lanlink.ui.AppFrame.buildJoinCard(AppFrame.java:484)
```
**根因**：加入页的错误提示标签 `joinError` 只做了字段声明，从未实例化：
```java
private JLabel joinError;        // 仅声明
...
top.add(joinError);              // 第 484 行：等价于 add(null)
```
Swing 的 `Container.add(null)` 会在 `addImpl` 内触发
`NullPointerException: Cannot read field "parent"`。该行位于 `buildJoinCard()` 中，
而 `buildJoinCard()` 由构造函数调用，因此窗口**根本无法创建**，程序启动即退。

**引入原因**：v2.3.0 为加入页插入「追加共享中继节点」卡片时，
一次编辑操作误将 `joinError` 的实例化语句一并替换删除。

**为何 2116 项自检没能发现**：全部自检均为纯逻辑测试，从未构造过界面。
javac 同样不会报错 —— 字段声明本身合法，只是运行时值为 null。

**修复**：

1. 补回 `joinError = leftLabel(" ", font(12), ERR);`
2. `buildRelayCard` 增加 null 防御，调用方传入 null 时自行创建
3. **架构级修复**：`AppFrame` 由 `extends JFrame` 改为 `extends JPanel`，
   窗口外壳移至新增的 `MainWindow`。JFrame 的构造需要图形环境，
   无头环境下直接抛 `HeadlessException`，导致界面**无法被任何自动测试覆盖**。
   改为 JPanel 后界面可在无头环境完整构建与遍历。
4. 新增 `UiSmokeTest`：无头构建整个界面并遍历组件树，
   检查是否存在 null 组件、空标题标签、无文字按钮。
5. 新增 `build/check-null-fields.py`：静态扫描「声明却未赋值」的 UI 字段，
   使该类缺陷在编译期即被拦截。
6. 两项检查均已接入 `build/build.ps1`，任一失败即中止打包。
7. `Main` 中窗口创建失败不再 `rethrow`（会导致 JVM 直接退出、用户看不到任何原因），
   改为弹出含日志路径的明确提示框。

**验证**：

- 注入缺陷复测：移除初始化语句后，`check-null-fields.py` 精确命中
  `MISS joinError`，退出码 1，构建被正确拦截
- `UiSmokeTest`：5 项全过，`nulls = 0`
- 打包产物实跑：`LANLink.exe --uitest` 输出 `ui_build = OK / panels = 139 / nulls = 0`
- 最终 zip 解包后再实跑 `--uitest`，同样全部通过

### A1. 手机端配置缺少连接路径，导致「运行中但互相看不到」（v2.6.0 修复）

**现象**：用户导入电脑端生成的 TOML 到手机端，手机端显示「运行中」，
但成员列表中只有本机，电脑端显示「等待对方接入」，成员列表为空。

**用户提供的手机端事件日志（决定性证据）**：

```
正在连接 udp://113.133.208.7:42789
连接错误 ["udp://113.133.208.7:42789","V4","AnyhowError(\n  \"connect timeout ...
正在连接 tcp://113.133.208.7:42789
连接错误 ["tcp://113.133.208.7:42789","V4","AnyhowError(\n  \"connect timeout ...
```

**根因**：生成的 TOML 中只有公网地址一条路径，缺少另外两条：

| 缺失路径 | 后果 |
|---|---|
| 局域网地址 | 同一路由器下本可零延迟直连，却绕道公网被 NAT 拦下 |
| 社区公共节点 | 中继要求双方都在公共节点网络上，手机侧不挂则永远发现不了电脑端 |

实测环境：手机 `192.168.100.100`、电脑 `192.168.100.101`（同一网段），
但 TOML 里只有 `113.133.208.7:42789`（公网地址）。
该网络为对称型 NAT 且 UPnP 映射失败，公网地址不可达 → 必然 connect timeout。

**修复**：`buildPhoneToml` 改为同时输出三类初始节点：

1. 局域网地址 tcp + udp（同一网段时延迟最低，完全绕过 NAT）
2. 公网地址 tcp + udp（跨网络直连）
3. 社区公共节点全部条目（免注册、免费，中继兜底）

同时在界面上新增「局域网地址」显示项，用户可手工核对。

**验证**：
- 内核级实测：房主监听随机端口，加入方使用修复后的 TOML，**3 秒内互相发现**
- 新增 12 项断言（`Tests.phoneTomlHasAllThreeRoutes`），覆盖三条路径齐全、
  内外网同址不重复写 peer、无局域网地址时不产生 `tcp://null` 残缺行

**补充教训**：此前「中继必须双方都挂公共节点」这一条只在电脑端实现了，
手机端走的是独立的 TOML 配置路径，该路径没有同步。中继类特性一旦存在两条
配置路径，就必须同时覆盖，否则只修一半。

---

### A2. 窗口尺寸写死，标题栏与关闭按钮跑到屏幕外（v2.6.0 修复）

**现象**：用户反馈「关闭节点都到屏幕外了，显示不全」。

**根因**：`MainWindow` 中写死 `setSize(820, 720)` + `setLocationRelativeTo(null)`。

- 屏幕可用高度小于 720 时（1366×768 笔记本扣掉任务栏后仅约 728，
  再扣窗口边框后更小），窗口居中放置会有一半落在屏幕外，
  标题栏随之移出可视区，关闭按钮点不到。
- 建房页内容很长（邀请码 + 成员列表 + 手机端配置 + TOML 预览 + 中继配置），
  固定高度下底部按钮被直接裁掉，无法滚动查看。

**修复**：
1. 窗口尺寸按 `GraphicsEnvironment.getMaximumWindowBounds()` 动态计算，
   并按屏幕可用区域定位，保证整个窗口（含标题栏）落在可视区内。
2. 三张卡片各自包一层 `JScrollPane`，内容超高时可垂直滚动。
   自定义 `FillWidthViewport` 强制内容宽度跟随视口，避免出现横向滚动条。

**验证**：新增 `LayoutProbe`（10 项断言），覆盖 5 种常见分辨率下窗口不溢出，
以及「内容宽度跟随视口 / 高度保留可滚动 / 横向滚动条不出现」。

---

### A1. EasyTier 默认监听端口组与房主端口撞车

**现象**：加入方内核启动后立刻退出：
```
ERROR CORE: instance ... stopped error=failed to listen on wg://0.0.0.0:11011
Caused by: io error: 通常每个套接字地址只允许使用一次。 (os error 10048)
```
**根因**：只给 `-p`（对端）不给 `-l`（监听）时，内核会启用一套默认监听器
（`tcp/udp 11010` + `wg 11011` + `quic 11012` …），与房主已在用的 11010/11011 冲突。

**修复**：加入方显式指定自己的监听端口：
```java
cmd.add("-l"); cmd.add("tcp://0.0.0.0:11020");
cmd.add("-l"); cmd.add("udp://0.0.0.0:11020");
```
**验证**：`engine-test2.py` 由"B alive: False"变为 True。

---

### A2. EasyTier 不接受 127.0.0.1 作为对端地址

**现象**：同机测试时加入方无限重试：
```
connect to peer error dst="tcp://127.0.0.1:11010"
  error=TunnelError(IOError(Os { code: 10049, kind: AddrNotAvailable }))
```
**排查**：换成真实网卡 IP `tcp://192.168.100.101:11010` 后 **立即可达**
（`cost=p2p`, `lat 0.51ms`, `tunnel tcp`）。

**处理**：不在代码里 hack（那是上游行为），而是
① 测试脚本一律用真实网卡 IP；② 在架构文档"已知限制"里写明；
③ 邀请码里填的本来就是用户真实 IP，生产路径不受影响。

---

### A3. 邀请码解析器把非法地址当合法

**现象**（单元测试抓出）：`113.133.208.7:abc` 通过了校验。

**根因**：拆端口时发现 `abc` 不是数字，就回退成"整个字符串都是 host"，
而 host 只做了字符集校验 `[0-9a-zA-Z.\-:]`，冒号是合法字符 → 蒙混过关。

**修复**：新增 IP 字面量强校验，只认真正的 IPv4 / IPv6，域名和半截地址一律拒绝：
```java
static boolean isIp(String s) {
    if (isIpv4(s)) return true;
    if (s.indexOf(':') < 0) return false;
    if (!s.matches("[0-9a-fA-F:\\.]+")) return false;
    return InetAddress.getByName(s) instanceof Inet6Address;
}
```
---

### A4. 从聊天文本里抠邀请码，把前后缀一起吞了

**现象**（单元测试抓出）：把 `邀请码：113.133.208.7:11010#k7m2q9 你看下` 整段粘进去，
解析出 `host = ":113.133.208.7"`、`pwd = "k7m2q9你看下"`。

**两个根因**：

1. `raw.replace(" ", "")` 是 `String.replace(CharSequence, CharSequence)`，
   **只替换第一个空格**，剩下的空格还在；
2. 抽取用的正则 `[0-9a-zA-Z\.\-:\[\]]+` 允许以冒号开头，
   于是把中文后面的全角冒号（已转半角）也吞进了 host。

**修复**：
```java
// 地址必须以字母数字或 [ 开头，杜绝误吞冒号
private static final Pattern CODE = Pattern.compile(
    "([0-9a-zA-Z\\[]+[0-9a-zA-Z\\.\\-:\\[\\]]*)#([^\\s，。,;；]+)");
```
并去掉"全局去空格"，改为分段 `trim()`。

**验证**：`pasted host` / `pasted pwd` 两条断言由 fail 转 pass。

---

### A5. 自检逻辑写错，把正常产物判成坏的

**现象**：`package.py` 报"缺少 runtime 或 jar"，但产物其实是完整的。

**根因**：jpackage 生成的 app-image 里 **没有 `runtime\bin\java.exe`** ——
启动器 `LANLink.exe` 是直接加载 `runtime\bin\server\jvm.dll` 的，不需要 java.exe。
我却按"有 java.exe 才算完整"来判定。

**修复**：改成直接运行打包出来的 exe：
```python
r = subprocess.run([exe, '--check'], capture_output=True, timeout=120)
ok = r.returncode == 0 and 'easytier-core.exe' in out
```
实测输出：
```
java   = 17.0.17
core   = D:\...\LANLink\app\engine\easytier-core.exe
cli    = D:\...\LANLink\app\engine\easytier-cli.exe
lanIp  = 192.168.100.101
```
---

## A6. 建房必然失败：`Cannot invoke "Invite.roomName()" because "parameter2" is null`

**现象**（用户第一版实测）：点【我来建房】→ 邀请码显示出来了，但状态一直"未连接"，
日志红字 `Cannot invoke "lanlink.core.Invite.roomName()" because "<parameter2>" is null`。

**根因**：`SwingUtilities.invokeLater` 是**异步**的，我却在它之后立刻用了它要赋值的字段：
```java
// 错误写法
SwingUtilities.invokeLater(() -> {
    currentInvite = Invite.parse(...);   // 排进 EDT 队列，还没执行
});
engine.start(Role.HOST, currentInvite, ...);  // 立刻执行 → currentInvite == null
```
`Engine.start` 的第 2 个参数是 `Invite inv`，于是 JVM 报 helpful NPE：`parameter2 is null`。

**修复**：先算局部变量，再启动内核，最后刷界面 —— 绝不依赖"UI 线程已经给字段赋过值"：
```java
final Invite inv = Invite.parse(...);          // 后台线程内完成
engine.start(Role.HOST, inv, vip, PORT, false); // 用局部变量
SwingUtilities.invokeLater(() -> { /* 只做 UI 刷新 */ });
```
**防御**：`Engine.start` 入口加 null 检查，报"内部错误：邀请信息为空"而不是 NPE。

**验证**：`Tests.engineRejectsNullInvite()` + `MemberProbe` 真实建房跑通。

---

## A7. 界面大量问号/方块：字体没有中文字形

**现象**：截图里"手机端要填这三行"下面全是方块，日志区也是。

**根因**：这两处我用了 `new Font("Consolas", ...)`。Consolas 是**纯西文等宽字体，不含任何中文字形**，
Swing 在没有字体回退的情况下直接画方框。界面其他地方正常，因为用的是微软雅黑。

**修复**：所有含中文的控件改用 `Theme.font()`（微软雅黑，含完整中文字形）：
```java
- logArea.setFont(new Font("Consolas", Font.PLAIN, 11));
+ logArea.setFont(font(11));
```
只有纯 ASCII 的邀请码仍用等宽字体（好看且不会出方块）。

---

## A8. 成员列表会串到别人的数据（RPC 端口写死 15888）

**现象**（联调发现）：加入方看到的"成员"是它自己 `10.144.0.2`，而不是主机 `10.144.0.1`。

**根因**：`easytier-cli` 默认连 `127.0.0.1:15888`，而我把两个实例的 RPC 端口都固定成 15888。
第二个实例启动时端口已被占用，内核随机换了一个；但 `cli` 仍然去问 15888 ——
**问到的是第一个实例的数据**。用户机器上若开着别的 EasyTier，还会串到别人的网络。

**修复**：每个实例启动时向系统要一个空闲端口，并把它记下来给 cli 用：
```java
rpcPortal = "127.0.0.1:" + freePort();
cmd.add("-r"); cmd.add(rpcPortal);
// refreshPeers(): cli -p <rpcPortal> -o json peer
```
**验证**（`MemberProbe`，两个真实内核）：
```
host   rpc=127.0.0.1:56504
joiner rpc=127.0.0.1:56509
--- HOST 视角 ---
  10.144.0.2   lanlink-LAPTOP-9UI87AON   直连  0.50ms
--- JOINER 视角 ---
  10.144.0.1   lanlink-host              直连  0.35ms
RESULT hostSees=true joinerSees=true
```
---

## A9. 「显示在同一局域网」被当成「能互通」（v2.6.1 修复）

**现象**：电脑端成员列表显示两台手机都在线（含延迟），但两台手机之间在游戏里连接超时。

**根因**：成员列表来自内核 `peer` 表，属于**控制面**信息 —— 两端经同一中继注册后即可被彼此看到，
并不代表数据面可达。界面从未做过任何「成员 A ↔ 成员 B」的探测，于是把「可见」直接显示成「已连通」。

**修复**：
- `sweepReachability()`：每 15 秒对每个成员执行一次 ICMP 探测，成员行尾追加 `✓ 可达 nms` / `✗ 不通`。
- 明确不探测本机虚拟地址：`ping 10.144.0.1`（自己）走本机回环，永远成功，
  既不能证明虚拟网卡正常，也不能证明隧道通，只会给出虚假结论；有意义的对象是其他成员。

## A10. 内核日志只存内存，故障无法事后定位（v2.6.1 修复）

**现象**：用户反馈连不上，`startup.log` 里只有启动/提权/防火墙五行，无法判断走的是直连还是中继。

**根因**：`Engine` 把内核 stdout 存进内存 `List`（上限 400 行）供界面「复制诊断信息」使用，
从不落盘；进程退出即丢失，长日志还会被覆盖。

**修复**：`openLogFile()` / `append()` 双写 `%LOCALAPPDATA%\LANLink\logs\engine-<时间>.log`
（UTF-8，逐行带毫秒时间戳，自动保留最近 10 份），诊断文本追加日志路径。

## A11. 手机端 TOML 的虚拟 IP 被写死，两台手机撞号（v2.6.1 修复）

**根因**：`pickPhoneIp()` 建房瞬间成员表为空，必然返回 `10.144.0.9`，`buildPhoneToml()` 直接写死。

**修复**：TOML 头部注明「第二台手机改为 `nextVirtualIp()`」，界面同步显示建议值；
不再让两台手机共用同一份配置而不自知。

---

## B. 构建链缺陷（不影响用户，但会产出坏包）

### B1. PowerShell 读 UTF-8 无 BOM 的脚本 → 中文全部乱码

`build.ps1` 里写了中文提示，PS 5.1 按 ANSI 解码 → `字符串缺少终止符`，整个脚本解析失败。

**修复**：脚本内**一律用 ASCII**，中文只出现在随包文档里。

### B2. javac 的 @argfile 带 BOM → 直接崩

`Set-Content -Encoding UTF8` 在 PS 5.1 会写 BOM，javac 读参数文件时报
`MalformedInputException: Input length = 1`。

**修复**：改 `-Encoding Default`（系统 ANSI）。

### B3. javac @argfile 里 Windows 反斜杠被当转义符

`D:\局域网联机软件\...` 被吃成 `D:局域网联机软件...` → `找不到文件`。

**修复**：写参数文件时统一转成正斜杠 `$_.FullName.Replace('\','/')`。

### B4. PowerShell 传 `-Dfile.encoding=UTF-8` 被拆开

java 把 `.encoding=UTF-8` 当成主类名，报 `找不到或无法加载主类`。

**修复**：给整个参数加引号 `"-Dfile.encoding=UTF-8"`。

### B5. 复制 160MB 目录时脚本静默中断，产出残缺 zip

**现象**：zip 打出来 49.6MB，解压后 `app\` 目录是空的 —— 双击 exe 必然启动失败。
构建日志停在 `[STEP] collect dist`，没有任何错误输出。

**根因**：`Copy-Item -Recurse` 复制大目录时脚本被打断（无提示），
`$ErrorActionPreference = "Stop"` 也没能捕获。

**修复**：**把所有文件装配与打包从 PowerShell 移到 Python**
（`build\package.py`），并在 packaging 前强制做一次 `LANLink.exe --check`。
现在流程是：`build.ps1`（编译+打包镜像）→ `package.py`（装配+自检+zip），
任何一步失败都会显式返回非 0。

---

## C. 安全自查

| 检查项 | 结论 |
|---|---|
| 硬编码密码 | 无。房间密码每次建房 `SecureRandom` 重新生成 |
| 传输加密 | EasyTier 默认 AES-GCM，未关闭（没有传 `--disable-encryption`） |
| 谁能加入 | 必须同时知道 `network_name`（由密码派生）与 `network_secret` |
| 管理接口暴露 | RPC 只监听 `127.0.0.1:15888`，未开 `0.0.0.0` |
| 命令注入 | 子进程用 `ProcessBuilder` 数组形式传参，不拼命令行字符串 |
| 路径穿越 | 无文件下载/上传逻辑，不接受路径输入 |
| 隐私 | 无账号、无遥测；只连用户填的对端 IP |

## D. 刻意不做的事

| 不做 | 原因 |
|---|---|
| 自己实现打洞 / NAT 穿透 | 上游已支持 TCP/UDP/对称型生日攻击三类打洞，重写只会更差 |
| 自己做虚拟网卡驱动 | 需要 C/JNI 工具链，本机没有；且 Wintun 已随内核提供 |
| 打包自定义 APK | 没有 Android SDK/NDK；未真机验证的 APK 是负资产 |
| 账号体系 / 好友列表 / 中转服务器 | 直接违背"无需注册 + 零成本"的硬要求 |
| 用 TAP 做二层 | Android `VpnService` 只给 TUN，二层在手机端会崩 |
