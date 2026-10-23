# 构建指南

本页只讲怎么把 Android 与 iOS 客户端编译出来。工程结构、业务边界与验证记录不在本仓维护。

## 环境

| 项目 | 版本 |
| --- | --- |
| JDK | 21 |
| Gradle | 由仓库 Wrapper 提供（`./gradlew`） |
| Android SDK | Platform 37.2 / Build Tools 36.0.0，最低 Android 13 |
| iOS | macOS + Xcode 26.4.1 + XcodeGen + `python3`，最低 iOS 14 |

Windows 本机可先只在该 shell 内激活工作区工具链：

```powershell
. ./scripts/use-local-tools.ps1
```

工具装在忽略的 `.local/`，不改系统环境变量与全局 Gradle/Android 配置。

## Android

```sh
./gradlew :androidApp:assembleDebug
```

产物：`androidApp/build/outputs/apk/debug/androidApp-debug.apk`。

需要 lint 与单元测试时：

```sh
./gradlew :shared:jvmTest :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:assembleDebug
```

签名 release 包用环境变量传入 keystore，不把密码写进源码或属性文件：

```sh
export ANDROID_KEYSTORE_PATH=/path/to/release.jks
export ANDROID_KEYSTORE_PASSWORD=...
export ANDROID_KEY_ALIAS=...
export ANDROID_KEY_PASSWORD=...
./gradlew :androidApp:assembleRelease
```

`shared` 的 `jvm()` 只用于宿主机测试，不发布桌面程序。

## iOS

在 macOS 上从仓库根目录执行：

```sh
bash iosApp/scripts/generate-project.sh
xcodebuild -project iosApp/BetterZslf.xcodeproj \
  -scheme BetterZslf -configuration Debug \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath iosApp/DerivedData CODE_SIGNING_ALLOWED=NO build
```

生成工程后用 Xcode 打开也可以；真机签名用本机 Team/Provisioning，仓库不保存凭据。
`Shared.framework` 是静态库，已由编译脚本链接，不要另加 Embed/Copy Frameworks phase。
CI 只构建未签名的 Simulator App，不产出 IPA。

## 版本号

版本只有一个来源：根目录 `version.properties`。

```properties
VERSION_NAME=1.0.0
VERSION_CODE=2
```

- `VERSION_NAME` 必须是三段数字，iOS 的 `CFBundleShortVersionString` 直接取它。
- `VERSION_CODE` 为递增正整数，Android `versionCode` 与 iOS `CFBundleVersion` 都用它。
- 需要预发布标签时加 `VERSION_PRERELEASE=rc1`（`rc` 加不带前导零的序号）；它只影响应用内显示的版本与发布 tag，不进 Apple 营销版本。
- 改动后先重新生成 iOS 工程再构建；Android 每次构建都会重新读取。

## 常见问题

- 换过构建类型或平台后先 `clean`，避免沿用上一次的类型输出。
- iOS 构建阶段会调用 Gradle 生成 framework，Xcode 需要能读到同一份 JDK 21。
- 仓库脚本默认不开 shell 追踪，避免把签名环境或会话数据写进日志。

## License

MIT
