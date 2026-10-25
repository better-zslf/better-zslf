# 验证记录

## 2026-10-23：首次集成

- Kotlin 2.4.21 / Compose Multiplatform 1.12.2 / Miuix 0.9.4。
- Gradle 9.6.1、JDK 21、AGP 9.4.1、compileSdk 37.2、targetSdk 36、minSdk 33。
- `:shared:jvmTest :androidApp:assembleDebug :androidApp:lintDebug` 首次全量通过。
- 初次 JUnit 报告共 112 个方法；接入 BLE adapter 后复跑为 132 个方法、零失败/错误，即 131 项离线测试及 1 个默认关闭的真实接口门控。门控不计作真实测试。
- 独立显式开启的真实接口测试通过：16 次登录/查询请求，服务端体测场次返回已知业务错误 400；其余查询成功。不包含预约、报名、密码、跑步成绩等写操作。
- 测试输出只记录 endpoint/code/count，不记录账号、身份资料、token、参数或响应正文。
- 最后复跑 `:shared:jvmTest :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:lintDebug` 成功。Android App 单元测试任务当前为 NO-SOURCE，不算作额外测试。
- Android lint 零错误、一个警告：targetSdk 未取最新，当前仍为 36。数据迁移规则警告已修复并复核；云备份与设备迁移均排除应用数据。

## 本机模拟器

Android API 36 x86_64，768×1280，WHPX 运行时自检可用，未改变 Windows 功能设置。已安装 Debug APK 并冷启动成功（约 1.9 秒）；登录、四 Tab、校园跑空态、设置二级导航、浅色/深色主题切换及进程重启后的加密会话恢复均通过。退出登录及退出后重启仍未登录也通过（使用全新 UI dump 排除旧文件误判）。发现并修复手动深色主题下原生状态栏图标不可读的问题，最后构建重新安装后已截图复核。期间 AndroidRuntime 错误日志为空。

模拟器不提供真实 GPS 运动或水控器 GATT 兼容性结论。

## 默认离线命令

```bash
bash ./gradlew :shared:jvmTest :androidApp:assembleDebug :androidApp:lintDebug
```

真实接口测试只在同时提供 `ZSLF_LIVE_TEST=1`、`ZSLF_USERNAME`、`ZSLF_PASSWORD` 的临时环境中执行。不要把真实值写进命令文件、配置、Issue 或 CI secrets 的常规测试配置。普通 CI 不访问学校服务。

## 第二轮集成

- 内容分页、通知已读、预约/体测表单与附件流程、热水器 UI 及真实采样会话协调已接入。
- 全量项目报告 238 个 JUnit 方法、零失败/错误：237 项离线测试与 1 个默认关闭的 Live 门控方法。预约/体测的 28 项离线用例包含在内。
- Android Debug APK、lint 均通过；另在 GitHub 的测试分支成功完成 Android 检查与产物上传。
- 首次 macOS CI 在 Android command-line tools 下载阶段返回 404，未进入 iOS 编译；不能把该次运行当作 iOS 验收。
- 软件蓝牙在 Bumble LocalLink 的真实虚拟 HCI/L2CAP/ATT/GATT 层完成发现、通知订阅、B0/B2/BA/B3/B4 与断开。这仍不等于 Android/Kable 或实体水控器验收。
- 增加仅 Debug 构建可用的原生定位/文件选择探针；探针不创建学校 HTTP 客户端，不上传轨迹或文件，不进入 Release APK。

## 2026-10-25：测试分支集成

- [CI12](https://github.com/better-zslf/better-zslf/actions/runs/40162399854) 对应 `6696138`：Android 测试、lint 与 Debug APK 构建通过。JUnit 报告为 599 个方法、零失败/错误；其中 1 个 Live 门控默认关闭，实际离线用例为 598 项。Android App 单元测试任务仍为 NO-SOURCE。
- 诊断宿主脚本 42 项及 scripts 测试 67 项已实际通过。这些是宿主回归，不计入 Native 验收。
- Android App/Kable 已与 AVD、Bumble 的虚拟 HCI/L2CAP/ATT/GATT 链路完成端到端联调，含操作、重建与 Back/结束释放；31/31 指一个完整场景的 31 个证据断言，不是 31 个独立场景，也不等于实体水控器兼容性。
- 本机前一集成版已验证个人资料只读页面、找回页空表单禁发及返回、横竖屏 NavigationRail 切换与四标签独立历史。找回测试未发送短信、核验验证码或重置密码。
- Android 地图的受控主文档 403、原生裁剪与相对高度基准为零问题已修复。CI12 APK 的独立本机重签副本已在 owned AVD 复验：AMap 真实底图、SDK Ready、合成导入折线与六行原始 WGS84 坐标滚动均通过；Native 视口为 720×420px，CSS 视口为 480×280px，未注入 CSS 或伪造 Ready。这不涵盖其他 provider、真实 GPS、任务 marker 或全部触摸交互。
- iOS framework 与 Xcode Simulator app 构建已通过。CI11 的 Mac 路径别名断言已修正，CI12 的 42 项 preflight 通过；随后真实 signed-positive seed 启动被系统签名校验终止，尚未获得有效 App PID 或 Native seed/verify 报告。截图为 SpringBoard，不是 Login 通过。当前正核对 Simulator 模拟 entitlements 与真实签名的构建方式。
- 本轮账号测试仍限登录和只读查询，未执行学校密码、预约、报名、打卡、跑步上传或附件写操作；生产跑步发送入口保持关闭，导入路线仅本机预览。

## 同日：文案与确认行集成

- UI/App 自有中文已集中到纯 common 文案目录：963 个中文静态/模板 token 迁移，16 个协议/查找 token 保持原值；显示标签与文档 ID、时段 wire 值独立。
- 确认行采用单一复选框语义与整行 48dp 最小触控高度，保留原确认门与回调；本轮只完成源码、编译和回归，不代替 TalkBack/VoiceOver 验收。
- 本机单 worker 集成构建在 3 分 13 秒内通过：605 个 JUnit 方法、零失败/错误/跳过，含 604 项离线用例与 1 个默认关闭的 Live 门控。新增 6 个文案方法覆盖 805 个独立字面量/边界断言及纯 fake 回调顺序。Android Debug APK 与 lint 通过，Android App 单元测试仍为 NO-SOURCE。
- 55 项诊断宿主测试与 123 项 scripts 回归实际通过。文案 guard 既核验词法/精确例外，也直接扫描当前 UI/App，并接入 Android CI 入口；这不涵盖尚未迁移的 feature/model/actual 文案。
- iOS 新流程交由 Xcode 正常打包 Simulator entitlements，安装前审查模拟段、真实签名与原/复制产物；宿主用例通过不等于新流程 Native 已通过，远端结果另验。

## 尚待验收

iOS 原生启动、Keychain 重启与交互、地图数据更新后的状态提示及其他 provider/任务场景、模拟定位补充链路、真机 GPS/BLE、生产预约/体测接受性、真实跑步任务、导入增强范围、Native updater、完整文案层级与读屏、发布签名及正式发版。离线通过不代表这些项目已经完成。
