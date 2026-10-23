# 构建工具与 CI 契约

## 固定工具链

- Kotlin `2.4.21`：`org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21`（Maven Central）。
- Compose Multiplatform `1.12.2`：`org.jetbrains.compose:compose-gradle-plugin:1.12.2`（Maven Central）。Compose Compiler 插件与 Kotlin 同为 `2.4.21`。
- AGP `9.4.1`：`com.android.tools.build:gradle:9.4.1`（Google Maven）。
- Gradle Wrapper `9.6.1`，分发 ZIP、Wrapper JAR 和官方 launcher 的 SHA256 由 `verify-wrapper.py` 固定；launcher 仅允许 Git 的 LF/CRLF 换行规范化，其余内容与官方一致。
- JDK 21；Android compileSdk 37.2（AGP `release(37) { minorApiLevel = 2 }`）/ targetSdk 36 / Build Tools 36.0.0 / minSdk 33。CI 安装实际 SDK 包 `platforms;android-37.2`（没有无后缀 `android-37` 包）；本地另保留 API36 平台/运行镜像及37.0平台。Miuix 0.9.4 / 当前 AndroidX AAR 元数据要求新 compileSdk，以实际依赖校验结果为准。
- iOS：macOS 26 ARM runner、Xcode 26.4.1、`iosSimulatorArm64`、静态 `Shared.framework`；CI 只构建 unsigned simulator App，不产出已签名 IPA。

版本核实依据：

- [Kotlin Gradle 兼容矩阵](https://kotlinlang.org/docs/gradle-configure-project.html)：KGP 2.4.20–2.4.21 的 fully supported Gradle 范围为 7.6.3–9.7.0，AGP 范围为 8.5.2–9.3.1；文档允许更新 AGP，但 **9.4.1 超出 fully supported 矩阵，实际工程验证以 CI 结果为准**。
- [AGP 9.4 兼容要求](https://developer.android.com/build/releases/agp-9-4-0-release-notes?hl=en)：最低 Gradle 9.6.0、JDK 17、Build Tools 36.0.0，最高 API 37。
- [KMP 兼容矩阵](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)：Kotlin 2.4.21 对齐 Xcode 26.4。
- [CMP 兼容说明](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html)：CMP 1.12.2 与当前 Kotlin 兼容，iOS 下限 14。
- [macOS 26 runner 库存](https://github.com/actions/runner-images/blob/main/images/macos/macos-26-arm64-Readme.md)：包含 `/Applications/Xcode_26.4.1.app`。
- [Gradle 官方分发校验](https://gradle.org/release-checksums/#9.6.1)。

## Windows：只安装到工作区

```powershell
pwsh -File scripts/bootstrap-windows.ps1
. ./scripts/use-local-tools.ps1
java -version
gradle --version
adb devices -l
```

工具、下载校验记录、Android 用户状态、Gradle cache、Kotlin/Native cache 全部位于忽略的 `.local/`；不修改系统/用户环境变量、注册表或全局 Gradle/Android 配置。bootstrap 只运行工具版本/安装验证，不运行工程构建。

`install-android-packages.ps1` 可单独恢复 SDK 安装：它先校验 Google 官方仓库 XML 中的 ZIP 长度与 SHA1，再解压，并保存计算出的 SHA256。这样可恢复 sdkmanager 网络传输中断导致的半包。

本机内存紧张时由执行构建者覆盖参数，例如 `--max-workers=2 "-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8" "-Pkotlin.daemon.jvmargs=-Xmx768m"`。仅在当前进程点源工具脚本；重新开 shell 后全局配置保持原样。WSL 未另装 Linux JDK/SDK，Windows 与 Linux 工具链不混用。`ANDROID_USER_HOME` 独立指定；脚本清除仅当前进程的 deprecated `ANDROID_PREFS_ROOT`，避免 AGP 将其解释为不同的 `.android` 父目录。

复用已校验下载包填充工作区 Wrapper cache（无项目构建）：

```powershell
python scripts/seed_wrapper_cache.py
. ./scripts/use-local-tools.ps1
./gradlew.bat --version
```

### API36 模拟器（只在 `.local/`）

```powershell
pwsh -File scripts/prepare-emulator.ps1
pwsh -File scripts/run-emulator.ps1  # 默认 headless，2核、1536MiB
# 如需显示窗口：pwsh -File scripts/run-emulator.ps1 -Window
```

使用官方 AOSP API36 `default;x86_64` 镜像（无 Google Play 服务/账号），AVD 名 `better_zslf_api36_x86_64`，默认768×1280 / 240dpi、2核、请求1536MiB；该 API36/Emulator37 组合实测仍强制上调到2560MiB，因此启动前按实际内存最低需求检查。Emulator 与镜像下载先校验官方长度/SHA1，并记录 SHA256；`ANDROID_AVD_HOME=.local/avd`。启动前执行 `emulator -accel-check`，只读取 Hypervisor 状态，不启用或关闭 Windows 全局功能；空闲内存不足会明确停在启动前。前台运行便于 Ctrl+C 结束；自动工具执行时应跟踪对应后台 job，使用完再结束。

## 持续集成任务

Android：

```bash
bash scripts/ci-android.sh
# :shared:jvmTest
# :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug
```

共享模块的 `jvm()` 仅用于逻辑测试，不发布桌面 UI。CI 上传测试/lint 报告和 debug APK；不连接学校账号、真实 BLE 或生产写接口。任务依赖：`version.properties`、`gradle/libs.versions.toml` 与 Wrapper 为版本源，不从 README 推断版本。

iOS（macOS）：

```bash
bash scripts/ci-ios.sh
# 先验证版本脚本、linkDebugFrameworkIosSimulatorArm64，再生成 Xcode 工程并构建 simulator App
```

只有 macOS job 的实际结果才能证明原生编译；Windows/WSL 的脚本检查不等于 iOS 编译通过。

## Tag 自动发布

`v*` tag 触发 release workflow；Android 与 iOS CI 均通过后才构建签名 release APK/AAB，并创建 GitHub Release。此文档不自动创建 tag，也不执行发布。

版本只读根 `version.properties`：`VERSION_NAME=x.y.z`、`VERSION_CODE=正整数`。tag 必须严格等于 `v${VERSION_NAME}`，版本号范围 1..2100000000；构建通过 `-PversionName` / `-PversionCode` 传入同样的已校验值。数字三段版本与 iOS MARKETING_VERSION 保持一致，暂不接受预发布后缀。发布后请递增 versionCode，已存在的 Release 不会被覆盖。

在仓库或 `release` environment 配置以下 **必需** secrets；始终使用备份妥当的同一份生产签名密钥，缺任一项会明确失败，不临时生成密钥，不回退到 debug 签名：

| Secret | 用途 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 原生产 JKS/PKCS12 文件的 base64 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 已存在的签名 key alias |
| `ANDROID_KEY_PASSWORD` | 签名 key 密码 |
| `ANDROID_SIGNING_CERT_SHA256`（推荐） | 预期证书指纹，可带冒号，防止更换签名身份 |

CI 只在 `RUNNER_TEMP` 解码密钥，设置 `ANDROID_KEYSTORE_PATH` 供 Gradle 读取。密码只经环境变量进入 Gradle/keytool，值不写入属性文件、不进入命令行或 artifact。无论成功/失败，都会删除临时 keystore。

发布前验证 APK 签名、APK/AAB 同一证书、APK versionName/versionCode 与 tag/版本源一致。Release 附件为版本命名的 `.apk`、`.aab`、公开版本与证书信息 `release.json`、`SHA256SUMS`。`contents: write` 只授予 release job；`GH_TOKEN` 只在发布步骤提供。仓库 `release` environment 若配置保护规则，自动构建会遵守这些规则。

## 不执行工程构建的检查

```bash
python3 scripts/verify-wrapper.py
python3 -m unittest discover -s scripts/tests -v
python3 scripts/release_metadata.py --tag v0.1.0
bash -n scripts/ci-android.sh
bash -n scripts/ci-android-release.sh
bash -n scripts/ci-ios.sh
```

Issue forms 复用了既有字段 ID，只增加设备/脱敏检查，并将文档入口指向公开 README；不把本地 `work/` 或原始分析资料带进仓库/CI 附件。
