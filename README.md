# Sparsa 零星

*Scattered devices, one network.*

把不同网络环境下的设备接入同一个虚拟局域网。成员在界面上看到彼此的虚拟 IP，
在支持局域网联机的游戏中直接填入对方虚拟 IP 即可联机。

- 无需注册，无需自备服务器
- 桌面端：Electron + Vue 3 + Element Plus
- 手机端：EasyTier 官方 App，导入配置即可
- 网络内核：EasyTier 2.6.4（Apache-2.0）

## 一、普通用户：下载即用

1. 从 [Releases](https://github.com/jywzzx/Sparsa/releases) 下载 `Sparsa-1.0.0-win64.zip`
2. 解压到硬盘，不要直接在压缩包内运行
3. 双击 `Sparsa.exe`，系统询问管理员权限时选择「是」
4. 房主点击「创建房间」，把邀请码发给对方
5. 对方点击「加入房间」，粘贴邀请码后连接

压缩包内自带 Java 运行环境与网络内核，接收方无需安装任何依赖。
运行环境要求与排错方法见 [分发与部署](sparsa-desktop/docs/06-分发与部署.md)。

## 二、开发者：源码结构

```
Sparsa/
├── LANLink-v2/        Java 后端：内核管理 + 本地 HTTP 接口
│   ├── src/main/java/lanlink/core/      网络能力（Engine / Invite / PhoneProfile 等）
│   ├── src/main/java/lanlink/server/    本地接口层（ApiServer / ApiMain）
│   ├── src/main/java/lanlink/ui/        Swing 界面，待移除
│   └── src/test/java/lanlink/           2188 条逻辑自检
└── sparsa-desktop/    桌面端：Electron 主进程 + Vue 3 界面
    ├── electron/      窗口、IPC、后端守护
    ├── src/           页面、组件、状态、接口
    └── docs/          设计文档与操作手册
```

用 IDEA 打开仓库根目录即可同时看到前后端。

### 本地运行

```bash
cd sparsa-desktop
npm install
npm run backend:build     # 编译 Java 到 LANLink-v2/build/classes
npm run app:run           # 启动桌面端，后端由主程序自动拉起
```

开发模式需要热更新时改用 `npm run app:dev`。

### 打包分发

```bash
npm run app:dist          # 产出 release/Sparsa-<版本>-win64.zip
```

### 自检

```bash
npm run verify                                              # 前端 60 项
java -cp "build/test-classes;build/classes;lib/zxing-core-3.5.3.jar" lanlink.Tests   # 后端 2188 项
```

`lib` 下的 zxing 依赖可用 `build/fetch-zxing.py` 获取，不纳入版本控制。

## 三、文档

- [目录结构与模块职责](sparsa-desktop/docs/01-目录结构与模块职责.md)
- [前后端接口契约](sparsa-desktop/docs/02-前后端接口契约.md)
- [从 Swing 到 Vue 迁移对照](sparsa-desktop/docs/03-从Swing到Vue迁移对照.md)
- [运行、构建与自检](sparsa-desktop/docs/04-运行与打包.md)
- [分支与协作方案](sparsa-desktop/docs/05-分支与协作.md)
- [分发与部署](sparsa-desktop/docs/06-分发与部署.md)
- [UI 文案规范](sparsa-desktop/docs/07-UI文案规范.md)
- [同网络联机测试流程](sparsa-desktop/docs/08-同网络联机测试流程.md)
- [移除 Swing 的边界与步骤](sparsa-desktop/docs/09-移除Swing的边界与步骤.md)

与 EasyTier 内核、以及已导入配置的手机端之间的协议标识，改动会让旧配置全部失联。

## 五、许可

界面与业务代码采用 MIT 授权，见 `LICENSE`。
网络内核 EasyTier 采用 Apache-2.0，第三方组件声明见打包产物内的 `LICENSE-EasyTier.txt`。
