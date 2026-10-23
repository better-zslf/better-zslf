# iOS 入口与构建

本目录提供 SwiftUI 宿主和 XcodeGen 工程定义；共享 UI 通过
[`MainViewController`](<../shared/src/iosMain/kotlin/io/github/betterzslf/MainViewController.kt>)
调用 `ComposeUIViewController { App() }`。Bundle ID 为 `io.github.betterzslf`，目标下限 iOS 14。
当前 KMP 目标为 arm64 真机与 Apple Silicon 模拟器；没有 Intel 模拟器目标。

## 生成与编译（macOS）

需要 Xcode、JDK 21、XcodeGen，以及根目录 Gradle wrapper。
从仓库根目录执行：

```bash
bash iosApp/scripts/generate-project.sh
xcodebuild -project iosApp/BetterZslf.xcodeproj \
  -scheme BetterZslf -configuration Debug \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath iosApp/DerivedData CODE_SIGNING_ALLOWED=NO build
```

也可在 Xcode 中打开生成工程；真机签名使用本机的 Team/Provisioning 配置，仓库不保存签名凭据。
[`project.yml`](<project.yml>) 在 Compile Sources 之前运行
[`embed-shared-framework.sh`](<scripts/embed-shared-framework.sh>)，调用
`:shared:embedAndSignAppleFrameworkForXcode`。
共享模块已配置静态 `Shared.framework`，用链接选项引入，**不要另加 Embed/Copy Frameworks phase**。
脚本关闭环境变量回显，不启用 `set -x`；不要把签名环境或会话数据写入构建日志。

## 单一版本源

只维护根 [`version.properties`](<../version.properties>) 的 `VERSION_NAME` / `VERSION_CODE`。
[`prepare-version.sh`](<scripts/prepare-version.sh>) 将它们转换为生成目录中的 xcconfig；
[`Info.plist`](<Info.plist>) 引用 `$(MARKETING_VERSION)` / `$(CURRENT_PROJECT_VERSION)`，没有另一份版本常量。
iOS 的 `PlatformServices.versionName` 读取实际 App bundle 版本。

CI 临时覆盖与 Gradle 使用同名参数：

```bash
bash iosApp/scripts/generate-project.sh -PversionName=1.2.3 -PversionCode=123
```

优先级：命令行 `-PversionName` / `-PversionCode` >
`ORG_GRADLE_PROJECT_versionName` / `ORG_GRADLE_PROJECT_versionCode` 环境变量 > 根属性文件。
Xcode build phase 将最终展开的版本原样作为 `-P` 参数传给 Gradle。
`versionName` 必须为 iOS 接受的三段整数版本，`versionCode` 为正整数；无效值立即失败，不静默转换。
修改根版本或 CI 覆盖后，**先重新运行生成脚本，再启动新的 xcodebuild/Xcode build**；
构建脚本阶段才生成 xcconfig 对当前构建的版本设置生效过晚，因此版本准备放在项目生成之前。
生成工程与生成版本配置均由本目录忽略规则排除。

## 平台数据与文章隔离

- [`KeychainKeyValueStore`](<../shared/src/iosMain/kotlin/io/github/betterzslf/platform/KeychainKeyValueStore.kt>)：
  Security Generic Password，使用 Bundle ID 派生的专属 service；`clear()` 仅删除该 service。
  `AfterFirstUnlockThisDeviceOnly`、不启用 iCloud 同步/共享 access group，不保存登录口令。
  持久化失败抛出只含操作名/OSStatus 的错误，不记录 key/value/token/个人资料。
  Keychain 条目可能跨卸载保留；登出走共享 `SessionStore.clear()` 删除 session 条目。
- [`PlatformServices`](<../shared/src/iosMain/kotlin/io/github/betterzslf/platform/PlatformServices.ios.kt>)：
  Darwin 使用系统 TLS 验证，禁共享 cookie/URL credential 存储和响应缓存，没有 HTTP 日志插件。
  系统说明仅包含系统名、系统版本和通用机型，不读取用户设备名或设备标识符。
- [`HtmlPanel`](<../shared/src/iosMain/kotlin/io/github/betterzslf/platform/HtmlPanel.ios.kt>)：
  WKWebView 非持久数据存储、禁 JavaScript、无脚本注入/消息处理器/native bridge；
  首个 CSP 禁脚本、连接、表单、frame、媒体和对象，图片只允许 HTTPS/data。
  HTML 本地字符串不携带登录 token；除本地文档加载与页内锚点，导航均取消。
  只有用户点击的不含内嵌凭据的 HTTPS 链接交给系统浏览器；自动跳转和自定义 URL scheme 不外跳。

## 显式原生能力

- [`FilePicker`](<../shared/src/iosMain/kotlin/io/github/betterzslf/platform/FilePicker.ios.kt>)：
  只有 `launch()` 才展示单文件 UIDocumentPicker；外部文档使用 NSFileCoordinator 和成对 security-scoped access。
  已知大小超过 8 MiB 时在打开流前失败；大小未知或虚报时仍使用最多 64 KiB/次的 NSInputStream，
  总读取量最多为 **8 MiB + 1 字节**（末尾探测），不使用整文件 NSData。
  取消/离开页面会清空弱 delegate、取消读取与待执行的文件协调；已进入读取块时在下一次有界读取边界响应取消，
  最终关闭流并释放 scope。结果只含净化文件名/媒体类型/内存内容，错误不带真实路径或 provider 信息。
- [`LocationPermission`](<../shared/src/iosMain/kotlin/io/github/betterzslf/platform/LocationPermission.ios.kt>)：
  只在前台显式 `launch()` 时请求 When In Use；近似定位返回 `false`，不自动请求 Always 或临时精确升级。
- [`LocationTracker`](<../shared/src/iosMain/kotlin/io/github/betterzslf/platform/LocationTracker.ios.kt>)：
  进程唯一、主线程管理 CLLocationManager/状态/样本发送；先订阅无 replay 的 `samples`，再显式 `start()`。
  `start()` 只检查已有精确权限，不弹权限框，只接受前台开始。
  BestForNavigation + Fitness、关闭自动暂停、开启后台定位和可见定位指示，配合
  [`Info.plist`](<Info.plist>) 的 `location` background mode，使前台开始的记录可持续到后台。
  这不是后台冷启动/被杀后的自动唤醒，也不承诺系统资源不足时持续运行。
  原生样本先经过有效坐标、精度 0..100 米、启动时刻/新鲜度/递增时间戳过滤，
  以原生时间戳与单调接收时间双重执行 10..60 秒节流。
  `stop()` 取消待开始/待发送任务、停止原生更新、移除 delegate 并释放 manager，之后可显式再开始；
  `close()` 永久置 Unavailable 并取消 scope。权限变化只停，不自动开始/重试/联网。
- [`BluetoothAccess`](<../shared/src/iosMain/kotlin/io/github/betterzslf/platform/BluetoothAccess.ios.kt>)：
  remember/状态观察不初始化 CBCentralManager；只有 `requestPermissions()` 创建它，且仅观察 power/authorization，
  不扫描、不连接。`requestEnable()` 只显示系统设置打开指引；离开组合会解除 delegate。
  `createWaterEpilogueContext()` 仅供明确的协议操作调用，采样 1..9999 seed，
  使用显式 Gregorian + `Asia/Shanghai` 日历，不受用户佛历等设置影响。

## 验证状态

实现环境为 Windows/WSL，**尚未执行 Xcode、Kotlin/Native 编译或 iOS 运行验收**。
本次已在 WSL 通过 16 项离线脚本测试、4 个脚本的 `bash -n`、plist/YAML 解析与关键配置断言；
实际 XcodeGen 工程生成仍待 macOS CI。可在 macOS 或 WSL 离线复现
[`version-scripts-test.sh`](<tests/version-scripts-test.sh>)：

```bash
bash iosApp/tests/version-scripts-test.sh
```

这些测试只验证属性解析、覆盖优先级、输入校验和 XcodeGen/Gradle 调用参数（工具使用 stub），
不代表 iOS 编译成功。待 macOS CI 完成模拟器编译；运行验收还应覆盖：
Keychain CRUD/空字符串/重启恢复/登出删除/服务隔离、浅深色文章、页内锚点、`target=_blank`、
HTTPS 外链、自定义 scheme/脚本/表单/自动重定向受限、VoiceOver 与退出页面清理。
新增能力已通过 **28 项实际纯 Kotlin 规则 JVM 测试**与 **33 项源码/plist 守卫**。
真实测试源为 [`CapabilityRulesTest.kt`](<tests/CapabilityRulesTest.kt>)，直接编译
[`IosCapabilityRules.kt`](<../shared/src/iosMain/kotlin/io/github/betterzslf/platform/IosCapabilityRules.kt>)，
不执行 Gradle、不编译 UIKit/Core Location/Core Bluetooth actual：

```powershell
pwsh -NoProfile -File iosApp/tests/capability-rules-test.ps1
```

```bash
python3 iosApp/tests/check-capabilities.py
```

复现脚本默认复用工作区 JDK 21/Kotlin 2.4.21，或指定 `JAVA_HOME` / `KOTLIN_HOME`。
原生待验：真机本地/iCloud/provider 文件选择取消与 8 MiB 边界、页面退出释放；
首次/拒绝/近似权限、关闭系统定位、后台指示与持续样本、暂停/恢复/退出不再回调；
蓝牙初次授权/拒绝/power 状态与惰性初始化、非公历设备的 Shanghai 协议时间。
源码守卫仅检查实现形态；原生编译与这些交互验收仍需独立完成。
无真实网络写接口、真实定位或真实蓝牙设备被调用。

## 官方依据

- [Kotlin：直接集成与 embedAndSignAppleFrameworkForXcode](https://kotlinlang.org/docs/multiplatform/multiplatform-direct-integration.html)
- [Compose：UIKit/WKWebView 集成与 Info.plist 高刷新率要求](https://kotlinlang.org/docs/multiplatform/compose-uikit-integration.html)
- [Compose：当前 UIKitView 源码与 onRelease 生命周期](https://github.com/JetBrains/compose-multiplatform-core/blob/jb-main/compose/ui/ui/src/iosMain/kotlin/androidx/compose/ui/viewinterop/UIKitView.ios.kt)
- [Ktor 3.4.3：Darwin configureSession 源码](https://github.com/ktorio/ktor/blob/3.4.3/ktor-client/ktor-client-darwin/darwin/src/io/ktor/client/engine/darwin/DarwinClientEngineConfig.kt)
- [Apple：Keychain SecItemCopyMatching](https://developer.apple.com/documentation/security/secitemcopymatching(_:_:))
- [Apple：AfterFirstUnlockThisDeviceOnly](https://developer.apple.com/documentation/security/ksecattraccessibleafterfirstunlockthisdeviceonly)
- [Apple：allowsContentJavaScript](https://developer.apple.com/documentation/webkit/wkwebpagepreferences/allowscontentjavascript)
- [Apple：WKWebsiteDataStore.nonPersistent](https://developer.apple.com/documentation/webkit/wkwebsitedatastore/nonpersistent())
- [Apple：CFBundleShortVersionString 格式](https://developer.apple.com/documentation/bundleresources/information-property-list/cfbundleshortversionstring)
- [XcodeGen：ProjectSpec](https://github.com/yonaskolb/XcodeGen/blob/master/Docs/ProjectSpec.md)
- [Apple：UIDocumentPicker 单文件打开初始化](https://developer.apple.com/documentation/uikit/uidocumentpickerviewcontroller/init(foropeningcontenttypes:ascopy:))
- [Apple：security-scoped access](https://developer.apple.com/documentation/foundation/nsurl/startaccessingsecurityscopedresource())
- [Apple：NSFileCoordinator.cancel 可由任意线程取消待协调操作](https://developer.apple.com/documentation/foundation/nsfilecoordinator/cancel())
- [Kotlin 官方 kotlinx-io：NSInputStream 原生 read 签名](https://github.com/Kotlin/kotlinx-io/blob/master/core/apple/src/SourcesApple.kt)
- [Apple：When In Use 精确定位权限请求](https://developer.apple.com/documentation/corelocation/cllocationmanager/requestwheninuseauthorization())
- [Apple：前台启动后持续后台定位与必要 background mode](https://developer.apple.com/documentation/corelocation/cllocationmanager/allowsbackgroundlocationupdates)
- [Apple：CBCentralManager 初始化与 nil queue 的主队列回调](https://developer.apple.com/documentation/corebluetooth/cbcentralmanager/init(delegate:queue:options:))
- [Kable 0.42.0：锁定版本 CBCentralManager 初始化/回调源码](https://github.com/JuulLabs/kable/blob/0.42.0/kable-core/src/appleMain/kotlin/CentralManager.kt)
- [Apple：显式 Gregorian 日历](https://developer.apple.com/documentation/foundation/nscalendar/identifier/gregorian)
