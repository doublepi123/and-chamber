<p align="center">
  <img src="docs/icon/icon.png" alt="AndChamber" width="112">
</p>

# AndChamber

**在 Android 手机上原生运行 [OpenChamber](https://github.com/openchamber/openchamber)（OpenCode 的 Web 界面），无需电脑、无需 root。**

[English](README.en.md)

<p align="center">
  <img src="docs/screenshots/home.png" alt="AndChamber 首页" width="240">
  &nbsp;
  <img src="docs/screenshots/openchamber.png" alt="手机上的 OpenChamber" width="240">
  &nbsp;
  <img src="docs/screenshots/session.png" alt="在手机上与 OpenCode 对话" width="240">
</p>
<p align="center"><sub>首页：本机服务与项目文件夹 · 在手机上打开 OpenChamber · 从通知直接进入会话</sub></p>

AndChamber 在应用私有存储里用 PRoot 搭建一个 Alpine Linux 用户空间，在其中安装并运行 OpenChamber 与 OpenCode，再用全屏 WebView 打开本机的 OpenChamber。也可以连接电脑上运行的 OpenChamber 服务器。

> [!IMPORTANT]
> AndChamber 是独立的开源项目，与 OpenChamber、OpenCode 官方无关。模型与服务的认证、调用由 OpenCode 及你配置的服务商负责，AndChamber 不提供任何模型服务。

## 功能

- **本机运行**：首次设置时自动下载 Alpine Linux，安装 Node.js、Git、ripgrep 等工具，再安装 OpenChamber 和 OpenCode；之后一键启动。
- **打开即用**：设置完成后，点开 App 直接进入本机 OpenChamber；服务未运行时会自动启动。
- **自动登录**：本机服务使用随机生成的 UI 密码，App 自动登录，不需要手动输入。
- **后台常驻**：运行时在前台服务中运行，切到后台也不会断；服务异常退出时自动重启。
- **公共目录**（可选）：项目和 OpenCode 配置放在 `/sdcard/AndChamber/`，可以用文件管理器或通过 USB 在电脑上维护；会话数据库、登录凭据、插件依赖仍留在私有目录。
- **系统通知与快捷回复**：App 在后台时，OpenChamber 的完成、出错、提问、权限请求等通知会以 Android 通知送达；可以直接在通知上回复、允许/拒绝权限、回答简单问题，点通知直接进入对应会话。AndChamber 自身的运行错误也会通知。
- **中英双语**：界面跟随系统语言显示中文或英文。
- **远程服务器**：添加电脑上 OpenChamber 的地址即可连接；明文 HTTP 需要手动确认。
- **终端可用**：`node-pty` 在设备上针对 musl 重新编译，OpenChamber 的终端功能正常可用。

## 系统要求

| 项目 | 要求 |
|---|---|
| Android | 8.0（API 26）及以上 |
| 本机运行 | **arm64-v8a** 设备 |
| x86_64 设备 | 只能连接远程服务器（Android 的 x86_64 seccomp 策略拦截 `fork`，PRoot 无法转换） |
| 存储空间 | 约 1.2 GB（下载 + 安装） |
| 网络 | 首次设置需要能访问 Alpine 软件源与 npm，建议使用 Wi-Fi |
| WebView | 内嵌页面需要系统 WebView 支持多 Profile（`MULTI_PROFILE`）。不支持或绑定失败时，页面会提示更新 WebView 提供程序（Android System WebView 或 Chrome） |

## 安装

从 [Releases](https://github.com/doublepi123/and-chamber/releases) 下载 `AndChamber-<版本>.apk` 并安装。每个 APK 都附带 `.sha256` 校验文件。

## 限制与升级说明

- **内嵌页面依赖 WebView 多 Profile**：本机和远程服务器的页面都要求 WebView 支持 `MULTI_PROFILE`。不支持时不会退回到共享 Cookie，页面不加载，本机后台服务、首页和通知仍可使用。
- **远程服务器可在外部浏览器打开**：不支持内嵌时，可以用“在浏览器中打开”打开格式有效的已保存地址，不会附加密码、Cookie 或令牌。
- **升级后可能需要重新登录**：每个服务器使用独立的 WebView Profile，新 Profile 首次打开时可能要求登录。服务器配置、密码和旧网页数据会保留，不会被清理或迁移。
- **原生下载跟随应用进程**：下载在应用运行期间进行，进程被杀后不会断点续传或自动恢复。
- **发送结果不确定时请先核对**：通知回复或权限操作返回超时、中断等不确定结果时，应用不会自动重发，请先打开会话确认，再决定是否手动重新发送。
- **跨源导航交给外部浏览器**：作为顶层导航处理的跨源链接仍在浏览器打开；已经进入应用下载器的请求则遵循下载凭据隔离规则。

## 使用

1. 打开 App，在“ON THIS PHONE”卡片中点 **Set up**。安装大约需要 5～10 分钟，期间可以切到后台。
2. 安装完成后会自动启动，点 **Open** 进入 OpenChamber。以后点开 App 会直接进入。
3. 在 OpenChamber 中配置模型服务商（Settings → Providers），开始使用。

### 返回键与首页

- 在 OpenChamber 中按返回：先在网页内后退，退到底后 App 回到后台，服务继续运行。
- 想回到 AndChamber 首页：点屏幕右侧边缘的小把手，选择 **Home**。
- 首页的 Details 中可以关闭“Open OpenChamber when the app starts”。

### 公共目录

在首页的“Projects and settings folder”卡片中开启，并授予“所有文件访问”权限，然后按提示重启服务。

```text
/sdcard/AndChamber/
├── projects/    → Linux 中的 ~/projects（服务器默认工作目录为 ~）
├── opencode/    → OpenCode 全局配置目录（opencode.json(c)、AGENTS.md、skills/、agents/、commands/ 等）
└── README.txt
```

- 私有目录 `~/projects-private` 始终可用，适合需要符号链接、`node_modules` 或可执行权限的项目（共享存储不支持这些）。
- 关闭公共目录时，`~/projects` 指向私有目录，不再挂载 `~/projects-private`。
- `~/projects` 中的 Git 仓库可以正常使用：AndChamber 会为公共存储配置 Git（不创建符号链接、不保留可执行权限位）；可执行脚本请用 `sh script.sh` 运行。
- `opencode/` 里的 `service.json`、`node_modules/` 是私有数据的挂载占位，请不要修改或删除。
- 开启公共目录后，其他拥有“所有文件访问”权限的 App 也能读取该目录，请不要在配置文件中直接写入 API Key。

## 运行时组成

| 组件 | 版本 | 来源 |
|---|---|---|
| Alpine Linux minirootfs | 3.24.1 | dl-cdn.alpinelinux.org，SHA-256 固定 |
| Node.js | 24.x | Alpine `nodejs` 包 |
| OpenChamber | 2.4.0 | npm `@openchamber/web` |
| OpenCode | 2.0.32 | npm `@opencode/cli`（musl 版本） |
| PRoot | 5.1.107.97 | 源码构建：固定版本源码归档，SHA-256 校验，编译为 jniLibs（与 talloc、libandroid-shmem 一起随 APK 分发） |

## 从源码构建

需要 JDK 17、Android SDK（Command-line Tools latest、platform 35、build-tools 35、NDK 29.0.14206865）、Python 3.12 及以上，以及宿主工具 `make`、`patch`、`awk`、`sh`、`tar`、`xz`、`gzip`、`bzip2`。原生库（PRoot、talloc、libandroid-shmem）从锁定的源码归档编译，不再从 Termux 软件包提取。

```bash
export ANDROID_HOME=$HOME/Android/Sdk
# 安装 NDK（一次性）
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "ndk;29.0.14206865"
# 1. 准备原生源码（联网，Gradle 构建之前执行）
python3 scripts/prepare_android_runtime_sources.py --output-dir build/runtime-sources
# 2. 构建与测试（原生编译离线运行，Maven 与 Google 仓库仍需网络）
./gradlew :app:testDebugUnitTest :app:assembleDebug
# 输出：app/build/outputs/apk/debug/app-debug.apk
python3 -m unittest discover -s scripts/tests -p 'test_*.py'   # 构建脚本的离线测试
```

详细步骤、宿主工具检查与实验性说明见 [docs/fdroid-source-build.md](docs/fdroid-source-build.md)。

Release 签名通过环境变量启用：`ANDCHAMBER_KEYSTORE_FILE`、`ANDCHAMBER_KEYSTORE_PASSWORD`、`ANDCHAMBER_KEY_ALIAS`、`ANDCHAMBER_KEY_PASSWORD`。四个变量都提供时才会签名。

推送 `v*` 标签会触发 GitHub Actions 构建签名 APK 并发布 Release。

## 项目结构

```text
app/src/main/java/io/andchamber/app/
├── runtime/          运行时：安装器、PRoot 命令、OpenChamber 进程、看门狗、共享存储
├── service/          前台服务与唤醒锁
├── data/             远程服务器存储、WebView 自动登录
├── di/               Koin 依赖注入
└── ui/               Compose 界面：首页、WebView、启动流程、主题
runtime_tools/, scripts/   构建时准备 PRoot 原生库
```

## 致谢与许可

- PRoot 运行时管线、校验下载、环境原子切换等代码移植自 [AndCode](https://github.com/yuga-hashimoto/and-code)（MIT）。
- 图标基于 OpenChamber 的立方体标志重新绘制。
- 运行时下载的 OpenChamber、OpenCode、Alpine Linux、PRoot 等软件遵循各自的许可证，详见 [NOTICE](NOTICE)。

本项目以 [MIT 许可证](LICENSE) 发布。
