# Sparsa 零星

*Scattered devices, one network.*

把不同网络环境下的设备接入同一个虚拟局域网。成员在界面上看到彼此的虚拟 IP，
在任意支持局域网联机的游戏里填入对方 IP 即可联机，用法与 Radmin VPN 一致。

- 无需注册、无需自备服务器
- 桌面端：Electron + Vue 3 + Element Plus
- 手机端：EasyTier 官方 App（导入配置即可）
- 网络内核：EasyTier v2.6.4（Apache-2.0）

## 目录

| 目录 | 说明 |
|---|---|
| `LANLink-v2/` | Java 后端：内核管理 + 本地 HTTP 接口 |
| `sparsa-desktop/` | 桌面端：Electron 主进程 + Vue 3 界面 |
| `LANLink/` | 更早的纯 Node 实现（历史存档，不参与构建） |

用 IDEA 打开**仓库根目录**即可同时看到前后端两端源码。

## 快速开始

```bash
cd sparsa-desktop
npm install
npm run app:dev          # 起界面 + 自动拉起 Java 后端
```

只调界面（不需要后端）：

```bash
npm run dev              # 浏览器打开，自动进入演示模式
```

## 文档

- [目录结构与模块职责](sparsa-desktop/docs/01-目录结构与模块职责.md)
- [前后端接口契约](sparsa-desktop/docs/02-前后端接口契约.md)
- [从 Swing 迁移对照](sparsa-desktop/docs/03-从Swing到Vue迁移对照.md)
- [运行、构建与自检](sparsa-desktop/docs/04-运行与打包.md)
- [分支与协作方案](sparsa-desktop/docs/05-分支与协作.md)
- [分发与部署](sparsa-desktop/docs/06-分发与部署.md)
- [UI 文案规范](sparsa-desktop/docs/07-UI文案规范.md)
- [同网络联机测试流程](sparsa-desktop/docs/08-同网络联机测试流程.md)
- [移除 Swing 的边界与步骤](sparsa-desktop/docs/09-移除Swing的边界与步骤.md)

## 一个必须知道的约定

对外品牌是 **Sparsa / 零星**；但 TOML 里的 `network_name` 仍然是 `lanlink`。
它是与 EasyTier 内核、以及已导入配置的手机端之间的**协议标识**，
改了会让老配置全部失联。产品名与协议标识是两件事。

## 许可

界面与业务代码 MIT；网络内核 EasyTier 为 Apache-2.0，详见 `LICENSE`。
