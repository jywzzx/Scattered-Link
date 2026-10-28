# 从 Swing 迁移到 Vue 的对照表

目的：换掉界面技术栈，**不换行为**。凡是旧版踩过的坑，新版必须原样保留防护。

## 一、页面级对照

| 旧（Swing） | 新（Vue） | 说明 |
|---|---|---|
| `AppFrame.buildHomeCard` | `views/HomeView.vue` | 二选一入口；环境问题从弹窗改为常驻横幅 |
| `AppFrame.buildHostCard` | `views/HostView.vue` | 分「未建房 / 已建房」两阶段 |
| `AppFrame.buildJoinCard` | `views/JoinView.vue` | 三种输入法合并为 Tab |
| `AppFrame.buildRelayCard` + `QrCard` | `views/PhoneView.vue` + `components/common/QrCanvas.vue` | 二维码改前端渲染（`qrcode` 库） |
| `AppFrame.buildDiagnostics` | `views/DiagnosticsView.vue` | 「界面声明 / 内核实际」并排 |
| `AppFrame.buildLogPanel` | `views/LogsView.vue` | 自动滚底改为「用户上滚就暂停」 |
| `AppFrame.buildBody` + `scrollable` | `layouts/MainLayout.vue` | 侧栏导航替代卡片平铺 |
| `Theme.java` | `styles/variables.scss` | 设计令牌 + Element 变量覆盖 |

## 二、逻辑级对照

| 旧方法 | 新位置 | 备注 |
|---|---|---|
| `beginHost` | `POST /session/host` → `stores/session.host()` | 参数一次性提交，成功后内核参数冻结 |
| `beginJoinFromCode` | `views/JoinView.vue` 的 `doJoin` | 前端先本地解析给即时反馈 |
| `beginJoinFromPastedConfig` / `tryJoinFromPastedConfig` | Tab「粘贴配置」+ `utils/invite.parseConfigText` | 前端解析 + 后端校验双重 |
| `refreshPhoneProfile` | `GET /phone/profile` | 模式与节点变化即重新生成 |
| `renderPhoneTips` | `components/common/TipList.vue` | 提示分级、按级别排序 |
| `savePhoneConfig` | `window.sparsa.dialog.saveText` | 走系统保存对话框 |
| `copyToClipboard` | `components/common/CopyField.vue` | 三层兜底（Electron → navigator → execCommand） |
| `buildDiagnostics` | `GET /diagnostics` | 文本由后端拼装，含内核实际参数 |
| `runPingTest` / `ping` | `POST /session/ping` | 后端执行，避免浏览器权限问题 |
| `checkPeerOffline` | `stores/session` 轮询比对 | 2 秒一次，失败保留上次状态 |
| `setTextIfChanged` | `views/LogsView.vue` 的 `load()` | 内容不变就不动 DOM，防滚动位置重置 |
| `onRelayEdited` | `stores/session.checkNeedsRestart` | 前端即时比对，红色提示 |

## 三、必须保留的六个行为约束

这些不是 UI 细节，改了就会重现线上故障：

1. **内核参数在启动时冻结**
   建房后再改「强制中继」「中继节点」不生效。界面必须显性告知「需重启」，
   否则会出现「界面显示中继、内核在直连」的假象，两端互相看不见。

2. **强制中继要求两端节点完全一致**
   中继为空时必须阻止提交。公共节点彼此独立，各挂一个等于没有通道。

3. **配置跟随模式**
   中继模式只写一个 `[[peer]]` 并置三个 `disable_* = true`；直连模式才写多路径。

4. **TOML 内不写注释**
   导入配置的人不会打开文件看注释。所有解释移到界面（`tips`）。

5. **成员列表自检不测本机**
   ping 自己永远成功，走的是回环，无法证明虚拟网卡与隧道是否正常。

6. **地址冲突要自愈并提示**
   虚拟 IP 由密钥派生，不保证唯一；发现重复要自动 +1 并告知用户。

## 四、刻意改变的地方

| 项 | 旧 | 新 | 理由 |
|---|---|---|---|
| 环境问题提示 | `JOptionPane` 弹窗 | 页面顶部常驻 `el-alert` | 弹窗会被条件反射点掉 |
| 二维码归属 | 后端 ZXing 出图，界面显示图片 | 前端 `qrcode` 直接画 | 省一次图片传输，且界面能算出容量是否够 |
| 日志滚动 | 每次刷新 `setText` | 内容变化才更新 + 尊重用户滚动 | 旧版会把人拽回顶部 |
| 邀请码输入 | 单一输入框 | Tab：邀请码 / 粘贴配置 / IP+密码 | 三种真实场景并列，不藏在提示里 |
| 窗口尺寸 | 写死导致标题栏跑出屏幕 | 按可用区域收敛 + 位置记忆 | 旧版实际故障 |

## 五、Swing 代码的去留

`ui/` 包暂时保留，原因：

- `Main --uitest` 与 `UiSmokeTest` 仍依赖它做无头检查；
- 有一批回归断言（布局、空组件、文案）挂在 Swing 上，直接删会丢测试基线。

退场条件：Vue 端补齐等价检查（`npm run verify` + `scripts/smoke.cjs`）并在两个版本内稳定后，
删除 `ui/`、`Main` 的窗口分支与 `QrCode.java`（前端不再需要后端出图）。

`core/` 与 `server/` 是长期资产，与界面技术无关，不动。
