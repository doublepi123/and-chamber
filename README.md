<p align="center">
  <img src="docs/icon/icon.png" alt="AndChamber" width="112">
</p>

# AndChamber

**在 Android 手机上原生运行 [OpenChamber](https://github.com/openchamber/openchamber)（OpenCode 的 Web 界面），无需电脑、无需 root。**

[English](README.en.md)

AndChamber 在应用私有存储里用 PRoot 搭建一个 Alpine Linux 用户空间，在其中安装并运行 OpenChamber 与 OpenCode，再用全屏 WebView 打开本机的 OpenChamber。也可以连接电脑上运行的 OpenChamber 服务器。

> [!IMPORTANT]
> AndChamber 是独立的开源项目，与 OpenChamber、OpenCode 官方无关。模型与服务的认证、调用由 OpenCode 及你配置的服务商负责，AndChamber 不提供任何模型服务。

## 功能

- **本机运行**：首次设置时自动下载 Alpine Linux，安装 Node.js、Git、ripgrep 等工具，再安装 OpenChamber 和 OpenCode；之后一键启动。
- **打开即用**：设置完成后，点开 App 直接进入本机 OpenChamber；服务未运行时会自动启动。
- **自动登录**：本机服务使用随机生成的 UI 密码，App 自动登录，不需要手动输入。
- **后台常驻**：运行时在前台服务中运行，切到后台也不会断；服务异常退出时自动重启。
- **公共目录**（可选）：项目和 OpenCode 配置放在 `/sdcard/AndChamber/`，可以用文件管理器或通过 USB 在电脑上维护；会话数据库、登录凭据、插件依赖仍留在私有目录。
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

## 安装

从 [Releases](https://github.com/doublepi123/and-chamber/releases) 下载 `AndChamber-<版本>.apk` 并安装。每个 APK 都附带 `.sha256` 校验文件。

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
- `opencode/` 里的 `service.json`、`node_modules/` 是私有数据的挂载占位，请不要修改或删除。
- 开启公共目录后，其他拥有“所有文件访问”权限的 App 也能读取该目录，请不要在配置文件中直接写入 API Key。

## 运行时组成

| 组件 | 版本 | 来源 |
|---|---|---|
| Alpine Linux minirootfs | 3.24.1 | dl-cdn.alpinelinux.org，SHA-256 固定 |
| Node.js | 24.x | Alpine `nodejs` 包 |
| OpenChamber | 2.4.0 | npm `@openchamber/web` |
| OpenCode | 2.0.32 | npm `@opencode/cli`（musl 版本） |
| PRoot | 5.1.107 | Termux 软件包，构建时固定版本并打包为 jniLibs |

## 从源码构建

需要 JDK 17、Android SDK（platform 35、build-tools 35）、Python 3。不需要 NDK：PRoot 等原生库在构建时从固定版本的 Termux 软件包中提取。

```bash
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:testDebugUnitTest :app:assembleDebug
# 输出：app/build/outputs/apk/debug/app-debug.apk
```

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
