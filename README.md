<p align="center">
  <img src="./images/logo_rounded.png" alt="SoundMan logo" width="160" />
</p>

<h1 align="center">SoundMan</h1>

<p align="center">
  按应用调节音量与输出设备的 LSPosed / Xposed 模块。<br>
  在系统框架中托管正在播放的音频会话，把每条应用规则写到音量与路由，并在 HyperOS 音量侧栏提供入口。
</p>

<p align="center">
  <a href="./README_EN.md">English</a> · <a href="https://github.com/killerprojecte/SoundMan">项目主页</a>
</p>

<p align="center">
  <a href="https://github.com/killerprojecte/SoundMan/releases"><img src="https://img.shields.io/github/v/release/killerprojecte/SoundMan?display_name=tag" alt="GitHub release"></a>
  <a href="./LICENSE"><img src="https://img.shields.io/badge/License-GPLv3-blue.svg" alt="GPLv3 license"></a>
  <a href="https://github.com/killerprojecte/SoundMan/issues"><img src="https://img.shields.io/github/issues/killerprojecte/SoundMan" alt="GitHub issues"></a>
  <a href="https://developer.android.com/"><img src="https://img.shields.io/badge/Android-16%2B-3DDC84?logo=android&logoColor=white" alt="Android"></a>
  <a href="https://github.com/LSPosed/LSPosed"><img src="https://img.shields.io/badge/Framework-LSPosed%20%2F%20Xposed-5C6BC0" alt="Framework"></a>
</p>

## 功能概览

- 为当前正在播放的应用单独调节音量，范围 0% 到 100%。
- 为指定应用固定输出设备，或选择跟随系统当前输出。
- 最多同时维护三条独立输出链路，把不同应用钉到不同硬件。
- 设备断开后回退跟随系统，同时保留原来的固定目标。
- 通过悬浮面板或 HyperOS 音量侧栏入口打开同一套规则界面。
- 在模块主页查看激活状态、版本、构建渠道、Git 分支，并跳转 GitHub。

## 主要功能

### 应用音量

- 只列出当前正在播放的应用，避免一整份已安装应用列表干扰操作。
- 每条规则以包名为键持久化，音量取值限制在 0..100。
- 100% 表示不额外衰减；低于 100% 时由系统音频宿主对对应播放器做增益调整。
- 没有正在播放的应用时，面板会给出空状态提示。
- 需要应用列表权限才能显示应用名称；未授权时可在面板中点按授权。

### 输出设备

- 跟随系统
    - 不建立 UID 设备亲和性，使用系统当前输出。
- 固定设备
    - 为本机、有线耳机、蓝牙、USB 等已确认映射的设备类型钉路由。
    - 设备身份使用 AudioSystem 内部类型和地址，展示名称不参与匹配。
- 多应用独立输出
    - 最多三条独立播放链路。
    - 只有一台设备被占用时走正常媒体通路。
    - 第二、第三条链路会拆开 Mix，再分别钉到所选硬件。
- 断开处理
    - 固定设备断开后，当前输出回退跟随系统。
    - 原目标会保留，设备重新连接后可继续使用该规则。
- 未知公开设备类型没有已确认的内部映射时，禁止固定路由。

### 音量面板入口

- 模块主页
    - 「打开音量面板」以半透明悬浮窗显示规则界面。
    - 打开和关闭带有中心缩放淡入淡出动画。
- HyperOS 音量侧栏
    - 在系统音量侧栏插入 SoundMan 圆形入口。
    - 从侧栏打开时面板从右侧滑入，点空白或返回即可关闭。
- 悬浮窗
    - 使用 `SYSTEM_ALERT_WINDOW` 承载 Compose 面板。
    - 不接触全局媒体音量流，只改写各应用自己的规则。

### 模块主页

- 未激活提示
    - 只在模块未激活时显示红色提示，说明需要勾选的作用域。
    - 已激活时不展示状态卡片。
- 关于信息
    - 应用图标、名称和作者。
    - 版本代号、模块版本、构建渠道和 Git 分支。
    - 分支展示会去掉仓库前缀，只保留分支名。
- GitHub
    - 一键打开对应仓库。

## 模块作用域

默认作用域包含以下目标进程：

- `android`
- `com.android.systemui`

此外，如果某个应用需要被固定到独立输出设备，请把它也勾进模块作用域。应用进程内的设备亲和性 Hook
只对已勾选的应用生效。

## 前置要求

- Android 16 / API 36 及以上。
- LSPosed / Xposed 兼容环境。
- Xposed 最低版本 93。
- 音量侧栏圆形入口面向 HyperOS 系统界面；按应用音量和路由依赖 `system_server`。
- 打开悬浮面板需要授予「显示在其他应用上层」权限。
- 显示应用名称需要应用列表权限（`QUERY_ALL_PACKAGES` / `GET_INSTALLED_APPS`）。

## 安装方式

1. 从 [Releases](https://github.com/killerprojecte/SoundMan/releases) 下载最新 APK 并安装。
2. 在 LSPosed 或兼容框架中启用 `SoundMan`。
3. 确认模块作用域包含：`android`、`com.android.systemui`，以及需要独立改道的应用。
4. 重启设备，或至少重启系统框架和系统界面后再使用。
5. 打开模块应用，确认模块已激活，再打开音量面板。

## 使用说明

- 系统框架类修改通常需要重启后才能稳定生效。
- 音量侧栏入口依赖 `com.android.systemui`；调整后如未出现入口，请重启系统界面。
- 按应用固定输出设备时，目标应用也必须在模块作用域中。
- 只调节音量、不固定设备时，规则由 `android`（`system_server`）托管，不必把所有媒体应用都勾进作用域。
- 固定设备断开后，界面会提示当前跟随系统，并保留原目标。
- 首次打开悬浮面板时，系统会请求悬浮窗权限。
- 本模块不替代系统媒体音量；它只在各应用自己的播放器上叠加音量和路由规则。

## 适用场景

- 想让多个应用同时播放，但各自使用不同音量。
- 想把某个应用钉到蓝牙或 USB，同时让其他应用继续走本机扬声器。
- 想从 HyperOS 音量侧栏快速打开应用音量面板。
- 想在桌面或任意应用之上用半透明面板调整正在播放的应用。

## 从源码构建

项目使用 Kotlin、Jetpack Compose、Android Gradle Plugin、Gropify 和 Gradle Wrapper。

- JDK 17。
- Android SDK / Build Tools 37。
- 编译 SDK 37，最低 SDK 36，目标 SDK 37。

常用命令：

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

构建产物位于 `app/build/outputs/`。版本号、Git hash、分支和构建渠道由 Gropify / git 元数据写入。

## 获取帮助

- 提交问题反馈: [Issues](https://github.com/killerprojecte/SoundMan/issues)
- 查看版本发布: [Releases](https://github.com/killerprojecte/SoundMan/releases)
- 项目仓库地址: [killerprojecte/SoundMan](https://github.com/killerprojecte/SoundMan)

## 免责声明

- 本模块会修改系统音频与系统界面行为，请自行评估风险。
- 不同系统版本、不同固件版本、不同 Xposed 环境之间可能存在兼容性差异。
- 系统框架或系统界面更新后，部分 Hook 点可能需要适配。
- 使用本模块造成的功能异常、音频路由问题或设备风险，请自行承担。

## License

See [LICENSE](./LICENSE).
