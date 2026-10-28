# 移除 Swing 前端的边界与步骤

## 一、结论

本项目只保留一个界面：`sparsa-desktop`（Electron + Vue）。
Java 侧只保留两类代码：`lanlink.core`（网络能力）与 `lanlink.server`（本地接口）。
`lanlink.ui`（Swing 界面）、`lanlink.Main` 的窗口分支、`lanlink.core.QrCode`（后端出图）属于待删范围。

判断依据：界面由谁绘制。Vue 绘制即保留，`javax.swing` 绘制即删除。后端能力不受界面技术影响，全部保留。

## 二、本次已完成的切断

问题现象：在「设置 → 后端连接」点击「重启后端」后，桌面弹出了旧的 Java 界面。

根因：后端入口候选列表里包含 `LANLink-v2/dist/LANLink/LANLink.exe`。
该文件是旧版 jpackage 产物，其 `app/LANLink.cfg` 中 `app.mainclass=lanlink.Main`，即 Swing 窗口入口。
它不识别 `--api` 参数，参数被忽略后 `Main` 直接创建窗口，于是旧界面被拉起。
同时旧代码在启动超时后只改状态、不结束进程，窗口就一直留在桌面。

已改动：

| 文件 | 改动 |
|---|---|
| `sparsa-desktop/electron/backend.cjs` | 候选列表只保留 `SPARSA_BACKEND` 与 `resources/backend/SparsaBackend.exe`，删除全部旧启动器 |
| 同上 | 新增 `entryProblem()`：读取 jpackage 的 `app/<name>.cfg`，主类不是 `lanlink.server.ApiMain` 的一律拒绝 |
| 同上 | 新增 `rejected()`：列出被拒绝的入口与原因，供界面显示 |
| 同上 | 启动超时改为先 `killChild()` 再报错，不再留下残留进程 |
| `sparsa-desktop/electron/ipc.cjs` | 探测接口返回 `rejected` 字段 |
| `sparsa-desktop/src/views/SettingsView.vue` | 新增「未采用的启动器」区块，显示路径与原因 |
| `sparsa-desktop/scripts/verify.mjs` | 新增 5 条断言，锁定「入口不能是 LANLink.exe」 |

验证结果：入口选择为
`java -Dfile.encoding=UTF-8 -cp LANLink-v2\build\classes lanlink.server.ApiMain --api`；
三个旧启动器均被拒绝，原因分别为「主类不是 lanlink.server.ApiMain，属于旧版界面入口」与「不是 API 后端的 jar 文件」。
自检 56 项通过，0 失败。

## 三、待删除清单

按影响从大到小排列。**必须按顺序执行**，否则中间状态无法编译。

| 顺序 | 对象 | 文件 | 影响 |
|---|---|---|---|
| 1 | Swing 界面 | `src/main/java/lanlink/ui/` 全部（AppFrame、MainWindow、QrCard、Theme） | 无外部引用；`Tests.java` 与 2 个测试文件引用它，需同步处理 |
| 2 | 界面入口 | `src/main/java/lanlink/Main.java` | 删除窗口分支，只保留 `--api` 委托到 `ApiMain`，或整体删除并把 `ApiMain` 定为唯一入口 |
| 3 | 后端出图 | `src/main/java/lanlink/core/QrCode.java` | 前端改用 `qrcode` 库生成，后端出图已无调用方 |
| 4 | Swing 测试 | `src/test/java/lanlink/UiSmokeTest.java`、`LayoutProbe.java` | 界面已不存在，无法保留 |
| 5 | 二维码测试 | `src/test/java/lanlink/QrRoundTrip.java`、`QrDump.java`、`TomlEmit.java` | 均直接调用 `QrCode.render`，随第 3 步一并删除 |
| 6 | 断言调整 | `src/test/java/lanlink/Tests.java` | 移除引用 `AppFrame`、`QrCode`、`lanlink.ui` 的断言，其余 2100 余条保留 |
| 7 | 构建脚本 | `build/build.ps1` 第 56 行（对 AppFrame 的字段静态检查）、第 72 行（headless 界面测试）、第 148–170 行（jpackage 生成 LANLink.exe） | 打包流程改为只产出 `sparsa-server.jar` 与本项目自己的 `SparsaBackend` |
| 8 | 静态检查脚本 | `build/check-null-fields.py` | 只服务于 Swing 字段，随第 7 步删除 |
| 9 | 历史产物 | `LANLink-v2/dist/LANLink/`、`LANLink-v2/build/LANLink/` | 各约 72 MB，旧界面打包产物，删除后不会再生 |

保留项（不可删）：

- `lanlink.core.Engine / Invite / PhoneProfile / PhoneConfigText / Diagnostics / NetInfo / Stun / Json`：网络能力本体。
- `lanlink.server.ApiServer / ApiMain / JsonWriter`：前端唯一依赖的接口层。
- `TOML` 中的 `network_name = lanlink`：跨端协议标识，与界面技术无关。

## 四、执行顺序与验证

每一步都要能编译、能通过自检，不允许一次性全删。

**第 1 步：删界面，先不动测试**

```
删除 src/main/java/lanlink/ui/
把 Main.java 改为只保留 --api 分支
```

验证：`javac` 编译 `core` 与 `server` 通过；`lanlink.Tests` 会因为引用 `AppFrame` 报编译错误，此时先跳到第 2 步。

**第 2 步：删测试与断言**

```
删除 UiSmokeTest.java、LayoutProbe.java、QrRoundTrip.java、QrDump.java、TomlEmit.java
从 Tests.java 中删除 AppFrame / QrCode / lanlink.ui 相关断言
```

验证：`npm run backend:build` 通过；`java -cp build/test-classes;build/classes lanlink.Tests` 输出 `fail 0`。

**第 3 步：删 QrCode 与构建脚本分支**

```
删除 core/QrCode.java
从 build.ps1 移除三次 Swing 相关调用与 jpackage 段
删除 build/check-null-fields.py
```

验证：`npm run backend:build` 通过；`npm run app:run` 启动后「设置 → 后端连接」显示「后端已连接」。

**第 4 步：清理历史产物**

```
删除 LANLink-v2/dist/LANLink/ 与 LANLink-v2/build/LANLink/
```

验证：`npm run app:run` 仍显示「后端已连接」，且「未采用的启动器」区块消失，说明已经没有任何旧入口残留。

## 五、风险与回滚

| 风险 | 说明 | 应对 |
|---|---|---|
| 丢失界面回归基线 | 现有 2188 条断言中，约 30 条依赖 Swing 组件 | 删除前先记录数量，删除后确认剩余断言全部通过 |
| 打包流程中断 | `build.ps1` 被 `package.py` 依赖 | 先改 `build/package.mjs`（已独立实现完整流程），确认它可产出可用包后再动 `build.ps1` |
| 手机端二维码不可用 | 前端已改用 `qrcode` 库，不依赖后端出图 | 删除前用真机扫码验证一次 |

回滚方式：所有删除操作都在 git 版本控制下进行，单个提交内完成即可整体 revert。
在源码入库之前，先对 `LANLink-v2/src` 做一次目录复制备份。

## 六、为什么不在本次一并执行

删除 Swing 会同时改动 9 处、使 5 个测试文件失效、并让 `build.ps1` 无法运行。
当前的首要任务是同网络联调（见 `08-同网络联机测试流程.md`）。
本次已先切断「桌面端能拉起旧界面」这条路径，功能上不会再回退；
代码删除作为独立一步执行，避免把功能验证和结构重构混在一起。
