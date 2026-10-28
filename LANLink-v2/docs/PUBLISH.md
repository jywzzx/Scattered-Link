# LANLink 发布到 GitHub —— 操作手册

本手册记录如何把 LANLink 的各个版本发布到 GitHub 仓库，
包含环境障碍的排查结论、所需材料、完整命令与验证方法。

---

## 一、本次发布结果（2026-10-26）

仓库：**https://github.com/jywzzx/Sparsa** （公开，默认分支 main）

| 版本 | 附件 | 大小 | 说明 |
|---|---|---|---|
| **v2.5.0** | LANLink-v2.5.0-win64.zip | 71.5 MB | **推荐版本**，异地联机经中继实测打通 |
| v2.0.0 | LANLink-v2-win64.zip | 71.5 MB | 首个虚拟局域网版本 |
| v2.1.0 | LANLink-v2.1.0-win64.zip | 71.5 MB | 密钥重构与稳定性改进 |
| v2.2.0 | LANLink-v2.2.0-win64.zip | 71.5 MB | 网络环境诊断与中继兜底 |
| v2.3.0 | LANLink-v2.3.0-win64.zip | 71.5 MB | 内置社区公共节点 |
| **v2.4.0** | LANLink-v2.4.0-win64.zip | 71.5 MB | **推荐版本**，修复启动即崩 |

每个版本另附 `.sha256` 校验文件，供用户验证下载完整性。
`latest` 已指向 v2.4.0。

**直接下载地址**（无需登录）：

```
https://github.com/jywzzx/Sparsa/releases/download/v2.5.0/LANLink-v2.5.0-win64.zip
```

---

## 二、本机环境的两个障碍及绕过方案

### 障碍 1：hosts 文件劫持 GitHub 域名

`C:\Windows\System32\drivers\etc\hosts` 中有 240 行把大量域名指向 `127.0.0.1`，
其中包括：

```
127.0.0.1 api.github.com
127.0.0.1 github.com
127.0.0.1 uploads.github.com
127.0.0.1 objects.githubusercontent.com
127.0.0.1 raw.githubusercontent.com
127.0.0.1 codeload.github.com
```

同时还屏蔽了 Steam、Twitch、Google、YouTube、Roblox 等。
该文件由 Steam++ 加速器等工具写入，**不修改它也能完成发布**。

### 障碍 2：系统代理导致 curl 参数失效

本机运行着 Steam++ 加速器，curl 默认走代理管道。
此时 `curl --resolve <域名>:<IP>` **不生效**（返回 000）。
加 `--noproxy "*"` 才恢复 —— 说明是代理在处理 resolve。

### 绕过方案：真实 IP + 保留 SNI

`build/gh_client.py` 的做法：

1. 用 `nslookup <域名> 223.5.5.5`（公共 DNS）拿真实 IP，绕开被劫持的系统解析
2. 用 `socket.create_connection((真实IP, 443))` 建连
3. 用 `ssl.wrap_socket(sock, server_hostname=域名)` 握手
   —— **保留 SNI 与 Host 头**，因此 TLS 证书校验正常
4. 在 `http.client.HTTPSConnection` 层面覆写 `connect()` 实现上述两步

实测结果：

| 端点 | 状态 |
|---|---|
| `api.github.com` @ 20.205.243.168 | HTTP 200，rate limit 59/60 |
| `uploads.github.com` @ 20.205.243.161 | HTTP 404（正常，根路径无内容） |
| `objects.githubusercontent.com` @ 185.199.108.133 | HTTP 404（同上） |

> 若将来网络环境变化，只需更新 `gh_client.py` 顶部的 `SEED_IPS`，
> 或依赖 `nslookup` 自动发现新 IP。

---

## 三、需要准备的材料

| 项目 | 说明 |
|---|---|
| Personal Access Token | 在 https://github.com/settings/tokens 生成 |
| Token 权限 | 勾选 `repo`（用于创建仓库、推送文件、创建 Release、上传附件） |
| 仓库名 | 本次使用 `Sparsa` |
| 可见性 | 默认公开；加 `--private` 则为私有 |

**Token 只在本次会话中使用，不写入 git 凭证存储。发布完成后可立即在
GitHub 设置页吊销。**

---

## 四、发布命令

### 完整发布（创建仓库 + 五个版本）

```bash
cd D:/局域网联机软件/LANLink-v2
python build/publish.py --token <你的PAT> --repo Sparsa
```

### 先试运行，不实际上传

```bash
python build/publish.py --token <你的PAT> --repo Sparsa --dry-run
```

### 私有仓库

```bash
python build/publish.py --token <你的PAT> --repo Sparsa --private
```

### 用环境变量传 token（避免出现在命令行历史里）

```bash
# PowerShell
$env:GITHUB_TOKEN = "<你的PAT>"
python build/publish.py --repo Sparsa
```

---

## 五、发布脚本做了什么

```
1. 连通性与权限自检
   ├─ GET /rate_limit        确认 API 可达
   ├─ GET /user              确认 token 有效、取得用户名
   └─ GET /user/scopes       确认 repo 权限

2. 准备仓库
   ├─ 仓库不存在 → POST /user/repos 创建（公开，含描述）
   ├─ 仓库为空   → PUT /contents/ 建首个 commit（自动创建 main 分支）
   └─ 写入 README.md 与 LICENSE

3. 逐版本发布（v2.0.0 / v2.1.0 / v2.2.0 / v2.3.0 / v2.4.0）
   ├─ POST /repos/{o}/{r}/releases   创建 Release（含版本说明）
   ├─ PUT  uploads.github.com/...     上传 zip 附件（约 71.5 MB / 个）
   └─ PUT  uploads.github.com/...     上传 .sha256 校验文件

4. 输出仓库地址与发布页面地址
```

附件命名与仓库根目录一致：`LANLink-v2.4.0-win64.zip`

### 三个必须知道的顺序与 API 约束

**① 空仓库不能直接建 Release**
`POST /releases` 返回 422 Validation Failed（target_commitish 指向不存在的分支）。
**必须先建分支写文件，再建 Release。**

**② Git Data API 在完全空仓库上返回 409**
`POST /git/blobs` 返回 409 "Git Repository is empty"。
改用 contents API（`PUT /contents/<path>`）建首个 commit，它会自动创建
default_branch。

**③ 上传大文件必须分块**
用 `putrequest` / `putheader` / `endheaders` + `send(chunk)`，
不要用 `request(method, body=bytes)` —— 后者会一次性把 71.5 MB 塞进内存。

---

## 六、已验证的 SHA256

| 文件 | SHA256 |
|---|---|
| `LANLink-v2-win64.zip` | `f205585e11bb96bd466c79ac143cd37e57ee1dbe9e3c7d26795a4d7f754ebe0d` |
| `LANLink-v2.1.0-win64.zip` | `63ff87ca1df741e4d7bded5965d4e2b7a823155c611fed3eca52c521e4d4f8bf` |
| `LANLink-v2.2.0-win64.zip` | `756b1537f4e9d3dc95d5f899fa4947bcac52524ea57a45326c56455674c27b11` |
| `LANLink-v2.3.0-win64.zip` | `2d56a0b9514a8c08823c25f9085bafea21e71291f0aa3fc3cd4f035f223e4683` |
| `LANLink-v2.4.0-win64.zip` | `1044ddafa6e8279488b04e2055a936c1217d68680eafe4d79ea604c6c63b02b2` |

发布脚本每次运行都会重新计算并随附件一同上传 `.sha256` 文件。

---

## 七、发布后验证（不能只信自己的日志）

```bash
# 1. 确认 Release 与附件齐全
python build/verify-published.py <PAT>

# 2. 逐个比对 SHA256（需跟随 302 重定向到 CDN）
python build/verify-sha.py <PAT>

# 3. 终极验收：从 GitHub 下载 → 解压 → 实跑自检
python build/final-acceptance.py <PAT>
```

`final-acceptance.py` 会实际下载 v2.4.0 附件、解压、运行
`--uitest` 与 `--check`，两者退出码均为 0 才算通过。
这是唯一能证明「用户下载到的东西真的能用」的验证。

---

## 八、故障排查

| 现象 | 原因与处理 |
|---|---|
| `GitHub API 401: Requires authentication` | 请求没带 Authorization 头 |
| `GitHub API 401: Bad credentials` | token 无效或已过期，重新生成 |
| `GitHub API 403` 且提示 rate limit | 未认证请求配额用尽（60 次/小时） |
| `GitHub API 404` 查仓库 | 仓库不存在或 token 无权访问 |
| `GitHub API 409: Git Repository is empty` | 空仓库用了 Git Data API，改用 contents API |
| `GitHub API 422: Validation Failed` | 空仓库直接建 Release，先建分支 |
| 上传超时 | 71.5 MB × 5 个附件，按当前带宽约需 15 分钟；脚本会输出实时速度 |
| `无可用 IP：api.github.com` | `gh_client.py` 的 `SEED_IPS` 全部失效，用 `nslookup` 查新 IP 后补进该字典 |
| 附件下载返回 302 | 正常，资产走 CDN 重定向，跟随 Location 即可 |
| 打包报「N 个文件复制失败」且重试无效 | 见下条 |

### 本机特有：沙箱的「安全删除」机制

`os.remove` 会把文件移入回收站，回收站操作失败会抛错，文件随后被标记为
**不可删除**，导致后续覆盖写全部 Permission denied 且 chmod 无效。

**判别方法**：用 `os.rename` 探针 —— 能改名说明不是文件占用。

**解决**：所有脚本都不删不改已有文件，只写新文件。
打包写入带时间戳的新目录，发布时用 `_asset_name()` 还原规范名，
查找文件时逐个算 SHA256 匹配而非取 mtime 最新的。

---

## 九、异地联机验证结论（v2.5.0）

「连上了但成员列表为空」这个问题，**程序逻辑本来就是对的**，是我的测试方法错了。

### 关键：同机测试测不出异地场景

同一台机器上起两个 EasyTier 实例时会**自动建立 P2P 直连**
（route 表显示 `path_len=1`、`next_hop=DIRECT`、延迟 0.2~0.5ms）。
于是要么测成「同机直连」（根本碰不到中继路径），
要么在直连建好之前就轮询（看起来像中继失败）。

### 正确的模拟方式

```
--disable-p2p true                  禁用自动 P2P
--disable-udp-hole-punching true    禁用 UDP 打洞
--disable-tcp-hole-punching true    禁用 TCP 打洞
```

三者同用 = 强制只能经中继，等价于「异地对称 NAT + 端口未映射」。

### 实测结果

| 场景 | 结果 |
|---|---|
| 强制中继，双方都挂公共节点 | **可互通**，`cost=relay(2)`，延迟 143~956 毫秒 |
| 只有加入方挂公共节点（房主不挂） | **不可互通** —— 房主 peer 表里只有自己 |
| 程序级 E2E（真实 Engine 类） | 3 秒内互见，中继延迟 956 毫秒 |

第二条是重要设计约束：**房主与加入方都必须挂公共节点**，
中继节点需要双方都在中继网络上才能把两个节点接起来。

### 社区公共节点（免注册、免费）

程序启动时并发探测以下节点，取可达的 3 个挂载：

```
tcp://225284.xyz:11010
tcp://183.230.36.171:11010
tcp://easytier.weiai.org.cn:11010
tcp://ros.scpsl.com.cn:11010
tcp://et.basd1.de:11010
wss://et.chinokou.cn
```

### 强制只用中继的开关

设环境变量 `LANLINK_NO_P2P=1` 可禁用直连与打洞，只走中继。
在对称型 NAT 且端口未映射的网络里，直连尝试会反复超时耗掉 20 秒以上，
直接走中继反而更快建立连接。

### 构建流水线的四道关

| 检查 | 内容 |
|---|---|
| 静态字段检查 | 「声明却未赋值」的界面字段（javac 查不出，运行即崩） |
| 逻辑自检 | 2116 项断言 |
| 界面冒烟测试 | 无头构建整个界面并遍历组件树 |
| 夹具测试 + 中继 E2E | 真实中继 JSON 解析 + 真实内核经中继互见 |

**任一失败即中止打包。**
