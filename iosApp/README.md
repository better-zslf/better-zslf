# iOS 入口

本目录提供 SwiftUI 宿主和 XcodeGen 工程定义，共享 UI 通过
[`MainViewController`](<../shared/src/iosMain/kotlin/io/github/betterzslf/MainViewController.kt>)
调用 `ComposeUIViewController { App() }`。目标下限 iOS 14，KMP 目标为 arm64 真机与 Apple Silicon 模拟器，没有 Intel 模拟器目标。

构建步骤、版本号规则与常见问题见仓库根目录 [构建指南](<../BUILDING.md>)。

## 目录

| 路径 | 内容 |
| --- | --- |
| [`project.yml`](<project.yml>) | XcodeGen 工程定义；`PRODUCT_BUNDLE_IDENTIFIER` 取自生成的 xcconfig |
| [`Info.plist`](<Info.plist>) | 应用描述；版本字段引用 `$(MARKETING_VERSION)` / `$(CURRENT_PROJECT_VERSION)` / `$(BETTER_ZSLF_DISPLAY_VERSION)` |
| `scripts/generate-project.sh` | 准备版本配置并调用 XcodeGen |
| `scripts/prepare-version.sh` | 版本薄桥，校验交给 `scripts/version_settings.py` |
| `scripts/embed-shared-framework.sh` | Compile Sources 前调用 `:shared:embedAndSignAppleFrameworkForXcode` |
| `tests/` | 离线脚本与源码守卫，不需要 Xcode |

`Shared.framework` 是静态库，用链接选项引入，不要另加 Embed/Copy Frameworks phase。
脚本关闭环境变量回显、不启用 `set -x`；签名环境与会话数据不写入构建日志。

## 版本来源

只维护根 `version.properties`。生成脚本把它写成 `iosApp/.generated/Version.xcconfig`，
其中 `BETTER_ZSLF_VERSION_NAME` 是 Apple 营销用的三段数字，`BETTER_ZSLF_DISPLAY_VERSION`
是应用内显示值（含可选的 `rcN` 预发布标签）。`CFBundleShortVersionString` 始终取数字值。

覆盖顺序为命令行 `-PversionName` / `-PversionCode` > `ORG_GRADLE_PROJECT_*` 环境变量 > 根属性文件；
无效值立即失败，不静默转换。改完版本先重新运行生成脚本，再启动新的 xcodebuild。

## 平台实现要点

- `KeychainKeyValueStore` 使用 Bundle ID 派生的专属 service，`AfterFirstUnlockThisDeviceOnly`，
  不启用 iCloud 同步，不保存登录口令；`clear()` 只删该 service。
- `PlatformServices` 用系统 TLS，禁用共享 cookie/credential 存储与响应缓存，没有 HTTP 日志插件。
- `HtmlPanel` 用非持久数据存储的 WKWebView，禁 JavaScript 与消息处理器；除本地文档与页内锚点外导航均取消。
- `FilePicker` 只在显式 `launch()` 时展示单文件选择器，单文件上限 8 MiB。
- `LocationPermission` / `LocationTracker` 只在前台显式请求 When In Use，先订阅再 `start()`；
  停止或关闭即释放 manager，权限变化只停不自动重试。
- `BluetoothAccess` 的 `requestPermissions()` 才创建 `CBCentralManager`，不扫描、不连接。

## 离线检查

```bash
bash iosApp/tests/version-scripts-test.sh
python3 iosApp/tests/check-capabilities.py
python3 iosApp/tests/check-map.py
```

这些只验证属性解析、覆盖优先级、输入校验与源码守卫（工具使用 stub），不代表原生编译通过；
真实构建与运行以 macOS 上的实际结果为准。

## 官方依据

- [Kotlin：直接集成与 embedAndSignAppleFrameworkForXcode](https://kotlinlang.org/docs/multiplatform/multiplatform-direct-integration.html)
- [Compose：UIKit/WKWebView 集成与 Info.plist 高刷新率要求](https://kotlinlang.org/docs/multiplatform/compose-uikit-integration.html)
- [Apple：Keychain SecItemCopyMatching](https://developer.apple.com/documentation/security/secitemcopymatching(_:_:))
- [Apple：WKWebsiteDataStore.nonPersistent](https://developer.apple.com/documentation/webkit/wkwebsitedatastore/nonpersistent())
- [Apple：CFBundleShortVersionString 格式](https://developer.apple.com/documentation/bundleresources/information-property-list/cfbundleshortversionstring)
- [XcodeGen：ProjectSpec](https://github.com/yonaskolb/XcodeGen/blob/master/Docs/ProjectSpec.md)
- [Apple：前台启动后持续后台定位与必要 background mode](https://developer.apple.com/documentation/corelocation/cllocationmanager/allowsbackgroundlocationupdates)
- [Kable 0.42.0：锁定版本 CBCentralManager 初始化/回调源码](https://github.com/JuulLabs/kable/blob/0.42.0/kable-core/src/appleMain/kotlin/CentralManager.kt)
