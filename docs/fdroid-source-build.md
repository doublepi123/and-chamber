# 源码构建实验：离线原生编译（fdroid-source-runtime）

> 状态：实验性。F-Droid 候选元数据（`fdroid/metadata/io.andchamber.app.yml`）已存在，但官方 CI 构建、审核、签名和发布仍未完成，不声明可复现构建或收录通过。下方“初次本地验证”记录源码改造阶段的历史结果，以哈希识别，保持不变；后续集成检查以对应 CI 为准。原生脚本或补丁再变更时，需按下方“变更后需要做什么”重新验证。候选元数据与其验证状态由 `fdroid/` 目录负责，本文档不重复记录。

## 许可证、版本与签名（当前）

- **许可证文本随 APK 分发**：`third_party/native-runtime/` 保存 PRoot（GPL-2.0-or-later）、talloc（LGPL-3.0-or-later，并附 GPL-3.0 全文）和 libandroid-shmem（BSD-3-Clause）的许可证文件与来源记录（[provenance.json](../third_party/native-runtime/provenance.json)）。Gradle 任务 `syncNativeLicenseAssets` 将其复制到 `assets/licenses/native/`，所有构建变体均包含。
- **版本**：`gradle.properties` 中的 `andchamber.versionName` 与 `andchamber.versionCode` 为当前版本（0.0.8 / 8）。发布工作流会校验标签与这两个值一致；旧标签（无这两个键）不做校验。
- **签名**：采用 F-Droid 独立签名。应用签名不由 GitHub 发布流程与 F-Droid 共享；之后更换签名密钥等同于重新安装并需要数据迁移。
- **本节不代表**：官方 F-Droid CI 构建、扫描器结果、审核或发布已经通过。

## 链路

默认构建不再从网络下载 Termux `.deb`。原生运行时分两步：

1. **源码准备（联网，显式执行，Gradle 不执行）**
   ```sh
   python3 scripts/prepare_android_runtime_sources.py --output-dir build/runtime-sources
   ```
   按 `runtime_tools/runtime_sources.lock.json` 下载三个源码归档（proot 5.1.107.97、talloc 2.5.0、libandroid-shmem 0.7），逐个校验 SHA-256 与许可证文件哈希，安全解压到 `build/runtime-sources/<component>/`，并写出 `sources.json` 作为来源记录。
   可用 `--local-archives-dir <dir>` 代替网络下载，目录内须含锁文件中同名归档（脚本仍逐个校验哈希）。

2. **离线编译（Gradle 任务 `buildAndChamberRuntimeNative`，`preBuild` 依赖它）**
   调用 `scripts/build_android_runtime_native.py`，参数为 `--sources-dir`、`--ndk-dir`、`--output-dir build/generated/runtime-jni`、`--work-dir build/runtime-native`。脚本只使用标准库，不访问网络。每个组件先复制到工作目录再构建，准备好的源码树不会被修改。补丁位于 `runtime_tools/patches/`，仅应用于构建副本。

Gradle 的 `buildAndChamberRuntimeNative` 声明为输入的内容：锁文件、两个脚本、三个补丁文件、`source.properties` 所在 NDK 的修订标识、NDK 版本常量和源码目录。`runtime_tools` 中的 Python 模块不参与原生编译，因此不计入指纹。若源码目录、工作目录或输出目录互相重叠，构建会报错。

## 环境要求

- JDK 17（如 `/usr/lib/jvm/java-17-openjdk-amd64`）
- Android SDK：由 `-Pandchamber.sdkDir=<path>`、`ANDROID_HOME`、`ANDROID_SDK_ROOT` 或 `local.properties` 的 `sdk.dir` 提供
- NDK `29.0.14206865`：`sdkmanager "ndk;29.0.14206865"`，或用 `-Pandchamber.ndkDir=<path>` 指定。相对路径相对于项目根目录解析。
- Python 3.12 或更高版本（脚本仅使用标准库；本地验证版本为 3.14，较低版本未测试）
- 宿主工具：`make`、`patch`、`awk`（任一符合 POSIX 的实现，本地验证使用 mawk）、`sh`、`tar`、`xz`、`gzip`、`bzip2`。CI 中通过 `command -v` 检查，缺失则失败。

可选覆盖项：`-Pandchamber.runtimeSourcesDir=<path>`，相对路径相对于项目根目录解析，默认 `build/runtime-sources`。

## 命令

```sh
# 1. 准备源码（网络步骤；本地已有归档时可加 --local-archives-dir）
python3 scripts/prepare_android_runtime_sources.py --output-dir build/runtime-sources

# 2. 调试构建（编译原生库并运行单元测试、lint）
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug

# 3. 调试包与生产版共存（仅 debug，id 须显式指定，拒绝生产 id 与非法 id）
./gradlew :app:assembleDebug -Pandchamber.testApplicationId=io.andchamber.fdroidtest
```

release 构建始终使用 `io.andchamber.app`，覆盖参数不影响 release。

## 初次本地验证（历史产物，以哈希识别）

证据目录：`build/source-runtime-validation/`，文件以 `FINAL-` 为前缀。

- **源码准备**：`scripts/prepare_android_runtime_sources.py`，三个组件（proot 5.1.107.97、talloc 2.5.0、libandroid-shmem 0.7）的归档哈希、许可证文件哈希与锁文件一致。本轮未重新准备。
- **Python 脚本测试**：`python3 -m unittest discover -s scripts/tests -p 'test_*.py'`，46 项全部通过。
- **Gradle**：`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug -Pandchamber.testApplicationId=io.andchamber.fdroidtest`，`BUILD SUCCESSFUL`（日志 `FINAL-gradle-debug.log`）。`buildAndChamberRuntimeNative` 在本轮实际执行；`testDebugUnitTest` 复用了前一次成功的结果（UP-TO-DATE），其报告对应的 Android 代码未变化。
- **JVM 单元测试**：374 个，0 失败，0 错误，0 跳过。
- **Lint**：38 个警告，0 个错误。警告类别包括 GradleDependency、RequiresFeature、SdCardPath、ObsoleteSdkInt、UnusedResources 等。未建立与 main 的基线比较，因此不能判定哪些是新增警告。
- **调试 APK**：`app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `62c656e491c8f19212727b2a67524f63d49d5da305038e70bf1fac93755a69d7`。包名 `io.andchamber.fdroidtest`，`versionName` 0.0.1，`extractNativeLibs` 为真。
- **APK 原生库**：两个 ABI（arm64-v8a、x86_64）各含 5 个运行时库（`libandchamber_android_proot.so`、`..._loader.so`、`..._loader32.so`、`libandroid-shmem.so`、`libtalloc.so`），另有 AndroidX 等依赖的原生库。这 10 个运行时条目的内容与 AGP `stripDebugDebugSymbols` 阶段的输出逐字节一致。`jniLibs` 原始输出与 APK 内容不同，这是 AGP 去除调试符号造成的，属预期。
- **proot 检查**：两个 ABI 的 proot 均含版本字符串 `5.1.107.97`，talloc 的 NEEDED 项为 `libtalloc.so`，不含 `libtalloc.so.2`。两个 ABI 的 talloc SONAME 均为 `libtalloc.so`。
- **发布清单**：`processReleaseMainManifest` 生成的清单包名为 `io.andchamber.app`，不含 `fdroidtest`。
- **覆盖参数**：`io.andchamber.app`（生产 id）与 `Bad`、`io..bad`、`1x.y.z`（格式错误）被拒绝。`io.android.x` 符合包名规则，被接受。
- **模拟器（仅 `emulator-5580`，x86_64）**：
  - 生产包 `io.andchamber.app` 的 codePath、versionCode 1、versionName 0.0.1、lastUpdateTime 在安装前后相同（`FINAL-prod-before.txt` 与 `FINAL-prod-after.txt`）。
  - `adb install -r` 安装 `io.andchamber.fdroidtest`：Success。
  - `am start -W -n io.andchamber.fdroidtest/io.andchamber.app.MainActivity`：COLD 启动，Status ok。
  - 测试进程 pid 20796 的 logcat（`FINAL-logcat-test-pid.txt`）中，FATAL、AndroidRuntime、ANR 计数为 0。
  - uiautomator 界面（`FINAL-ui.xml`）显示 “Local server / Not supported / Not available on this device”，并提示需要 arm64-v8a 设备，同时保留 “Connect to your computer / Add server”。这是 x86_64 的预期门禁。仅验证了界面显示，未测试远程服务器连接。

## ARM64 模拟器尝试（失败，未完成）

目标：在 x86_64 主机上启动真 arm64 Android 模拟器，验证源码构建的原生运行时。结果：**未达成**。以下均为本地实测结果，未采用调研来源声称的版本。

- AVD：`andchamber-fdroid-arm64-api27`，路径 `build/arm64-runtime-validation/avd`，隔离 `ANDROID_AVD_HOME=build/arm64-runtime-validation/avd-home`。
- 系统镜像：官方 `system-images;android-27;default;arm64-v8a`，rev 2（Android 8.1.0），由 `sdkmanager` 安装，SDK 原有 `android-35` 未改动。
- 工具版本与观察结果（均为实际执行结果）：
  - SDK emulator 37.2.12：报 `QEMU2 emulator does not support arm64 CPU architecture`，不启动。
  - 官方包解压的 emulator 30.2.2：可启动 `qemu-system-aarch64`，guest 为真 aarch64（`uname -m=aarch64`，`ro.product.cpu.abi=arm64-v8a`），但 `sys.boot_completed` 始终未置 1。audio HAL 循环崩溃（SIGABRT/SIGSEGV，约 20 次），进入 boot phase 500 后等待 `media.sound_trigger_hw`，20 分钟内未完成启动。
  - 官方包 emulator 35.1.2（`emulator-linux_x64-11616444.zip`）：两次启动（带和不带 `-no-audio`）均在 QEMU 初始化阶段退出，报 `PCI bus not available for hda`，未进入 Android boot。`hw.audioInput/hw.audioOutput=no` 无法绕过该检查。
- 结论：本轮尝试的主机与 SDK 组合未能完成 arm64 Android 启动。未修改 KVM 权限；跨架构软件模拟也不能依靠 KVM 加速。
- 本次未在此 AVD 上安装或启动 AndChamber。
- 停止原因：约束要求不 root、不改 KVM、不停 HAL、不改 SDK 工具，且未发现仅修改 AVD 即可解决的方案。
- 代码测试与模拟器结果分开看：Python 测试、JVM 单元测试、lint 与 APK 检查在前文已通过；上述 arm64 启动失败仅属模拟器环境问题，与应用代码无关。
- 证据：`build/arm64-runtime-validation/`，包括 `01-sdkmanager-install-api27-arm64.log`、`02-avdmanager-create.log`、`03-` 至 `08-` 的 emulator 启动日志、`06-logcat-emulator-5584-full.txt`，以及 `FINAL-` 汇总。

## ARM64 真机验证（2026-10-20）

- 设备：K70U，主 ABI 为 `arm64-v8a`，内核为 `aarch64`。
- 使用独立 debug 包 `io.andchamber.fdroidtest` 和端口 `13000`，只覆盖测试包的 asset 端口，其余运行时清单字段不变；构建输出与证据保留在仓库外。
- 测试 APK SHA-256：`5c5153918c1b98e0de82349919b40b86b408f05e1c981b7d58a74f0b523f9f20`。
- 源码编译的 PRoot 实际执行成功，版本 `5.1.107.97`。Alpine `3.24.1` 安装完成；实际执行 Node `24.18.1`、npm `11.12.1` 和 OpenCode `2.0.32` 版本检查，guest `uname -m` 为 `aarch64`。
- 安装日志包含 `PTYOK`，guest Node DNS 查询通过。OpenChamber `2.4.0` 两次健康检查均为 `status=ok`、`isOpenCodeReady=true`、`openCodeRunning=true`，server identity 保持一致。
- 嵌入页面已实际显示新会话欢迎页和输入控件，未见登录或 WebView 不支持提示。页面验证未发送模型请求。
- 共享存储保持关闭，未读取原应用配置或密钥。测试后仅停止独立测试包，确认测试端口关闭；原应用安装标识及进程保持不变。测试包和私有环境保留。
- 无地址、设备标识、凭据或手机截图写入本仓库。

## 边界

- **arm64 已完成单台真机验证。** 上述结果不代表所有 Android 版本、ROM、页大小或其他设备均已通过。
- **未测试模型对话及长期运行。** 本次覆盖安装、PTY、DNS、服务器就绪和页面显示，没有调用付费模型。
- **未验证远程连接。** 仅确认 x86_64 界面的显示内容，未测试任何远程 OpenChamber 服务器的连通性。
- **不作可复现构建声明。** 仅记录了源码哈希与 NDK 版本，未比较不同机器或不同次构建的输出。
- **Gradle 离线仅限原生阶段。** 原生编译步骤不访问网络。Gradle 自身的依赖解析（Maven、Google 仓库）仍需网络，不属于本实验的离线范围。
- **F-Droid 收录未完成。** 候选元数据已存在（`fdroid/metadata/io.andchamber.app.yml`，以及 `fastlane/` 下的商店文案与图标）。官方草稿合并请求为 <https://gitlab.com/fdroid/fdroiddata/-/merge_requests/53432>，已注明账号验证使 CI 无法创建任务，并请求维护者协助。CI 兼容性修正（完整提交哈希、普通 `AutoUpdateMode`、`sudo` 安装宿主工具）尚未通过托管流水线；本地校验和历史构建不能代替官方构建、审核与收录。详见 [fdroid/README.md](../fdroid/README.md)。
- 当前发布版本为 `0.0.8 / 8`；SDK target 和原生运行逻辑未变，APK 新增许可证资源。

## 变更后需要做什么

- 原生脚本（`scripts/build_android_runtime_native.py`）或补丁（`runtime_tools/patches/`）变更：需要重新编译原生库（Gradle 会因指纹变化自动重跑 `buildAndChamberRuntimeNative`），并重新打包、重新验证 APK。源码准备无需重做。
- 源码锁文件（`runtime_tools/runtime_sources.lock.json`）或准备脚本（`scripts/prepare_android_runtime_sources.py`）变更：需要重新准备源码，之后同样需要重新编译和验证。
- 仅修改 Python 测试文件：不影响原生产物，只需重跑测试。

## 下一步（未完成）

- 在官方 CI 中复核修正后的候选配方（合并请求 53432），再推进审核、签名和收录流程。
- 许可证交付已包含在 APK 中（见上文“许可证、版本与签名”），其法律合规复核由维护者负责。
- 更多设备和 Android 版本覆盖、长期运行及其他未测功能。

## CI 与 Release

`.github/workflows/ci.yml` 与 `release.yml` 在 Gradle 之前：安装 NDK `29.0.14206865`，检查宿主工具，并通过 actions/cache 缓存 `build/runtime-sources`（键：锁文件与准备脚本）。缓存未命中时执行准备脚本（CI 中为网络下载）。`ci.yml` 另缓存 `build/generated/runtime-jni`（键：锁文件、两个脚本、补丁目录与 NDK 版本）。release 不缓存 JNI 产物，每次从源码编译。

`release.yml` 的版本推导在标签格式检查之后，比对 `gradle.properties` 中的 `andchamber.versionName` 与 `andchamber.versionCode`：不一致则失败；缺少这两个键的旧标签跳过比对。签名与 apksigner 步骤未改动。

## 相关入口

- 许可证文本与来源：[third_party/native-runtime/README.md](../third_party/native-runtime/README.md)、[provenance.json](../third_party/native-runtime/provenance.json)；仓库根目录 [NOTICE](../NOTICE)。
- 许可证校验测试：`scripts/tests/test_native_license_assets.py`（离线，`python3 -m unittest discover -s scripts/tests -p 'test_*.py'`）。
