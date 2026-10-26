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

## 同日：B/C 文案、地图更新与 iOS 原生验证

本节对应测试分支提交 [9e6ccbca](https://github.com/better-zslf/better-zslf/commit/9e6ccbca245d103d51e1e0ca720fd8cff43a2957)。本机 Android 第四轮集成使用该提交中参与 APK 构建的代码；诊断宿主的两个 Python 文件不编入 APK。本机 APK 与 CI 产物分别验证，不互相替代。

### 本机构建与离线回归

- shared JVM 测试、Android Debug APK 构建与 lint 全部通过。JUnit 为 63 个 suite、627 个方法，失败、错误及报告跳过均为 0。HTML 报告耗时 5.345 秒，各 XML suite 时间相加为 3.808 秒；两者口径不同。
- 627 个方法包括 626 项离线用例及 1 个默认关闭的 Live 门控。报告跳过为 0，不表示已开启 Live 或实际访问学校接口。Android App 单元测试仍为 NO-SOURCE，不另计方法数。
- 本机 Python 宿主回归实际通过 284 个不同方法：scripts 217 项（4.038 秒）及 iOS 诊断 67 项（5.060 秒）。套件内部子集、此次新增方法不重复累加；CI 的前置宿主测试也不计作额外 Native 场景。
- UI/App、feature/model 与 actual 层的文案目录现有 1439 个成员：1260 个常量、179 个纯格式函数。A/B/C 三组独立文案金标的 805/720/91 个断言均已随对应 JVM 方法实际执行；C 组的 4 个方法包括两种 provider 的完整 HTML 及空文档字节比对。
- 协议与查找值继续保留原值。两个内部 Unicode 诊断数据及两条 iOS 系统权限用途文案是明确保留项；词法门禁精确约束诊断例外，并额外绑定完整 Native 源码 SHA，不作整文件豁免。因此不宣称所有中文都已迁出源码。此轮未引入统计/崩溃 SDK 或账号预填。

### Android：四标签页与同文档地图更新

- 新 APK 保留既有会话，首页、校园跑、社区、我的四标签页均已实际打开并视觉核对。无真实任务时显示本机预览，不伪造任务内容。
- 通过系统文件选择器导入合成 GPX：AMap 真实底图、SDK Ready、橙色长路径及原始 WGS84 坐标均可见。随后再次选入不同的短路径文件，核对新文件名和原始末点，旧长线消除，真实 Ready 状态保持。
- 以长线可见时为基线，选择器打开前、替换后和短线可见时共四次被动观察，App PID、地图 target/frame/loader 均不变，支持本次数据替换沿用同一文档。初次大幅滚动可能回收地图项的过程不计入这一结论。
- 未通过脚本执行、样式改写或注入 Ready 得到上述结果；也未记录 SDK 回调次数，因此不把状态保持表述为“实测第二次 Ready 次数为 0”。
- 使用应用的清空草稿后，地图页面数量为 0；只清理本次拥有的模拟器与精确匹配的调试转发，未清应用数据或登出。这些结果不代替真机 GPS、实体 BLE、其他 provider、任务 marker、读屏或更新安装验收。

### iOS：保留失败历史，单列实际成功结果

- [CI14](https://github.com/better-zslf/better-zslf/actions/runs/40182150201) 对应旧提交 [ca2caf55](https://github.com/better-zslf/better-zslf/commit/ca2caf55dce658162ea43ea2c07e40125a340e14)，seed 启动在原 30 秒预算内超时，未验证 Native CF/Keychain、AppGraph 或 Login；与此前 CI12 的系统签名终止是各自运行的历史记录。
- [CI15](https://github.com/better-zslf/better-zslf/actions/runs/40190429030) 使用本节提交，Android 检查及 iOS 构建通过，但 iOS smoke 在初始模拟器清单读取及既有重试中各发生一次 30 秒超时，结束前两次命令均仍运行且无输出。尚未选择 runtime、创建本次设备或进入 seed 启动；不是 App 启动失败，也不是已拥有 26.4 设备的运行结论。
- [CI16](https://github.com/better-zslf/better-zslf/actions/runs/40192133595) 在相同提交上另行触发完整工作流、使用新宿主，Android 检查、iOS framework/Xcode Simulator app 构建和原生 smoke 全部成功。实际选用的 Simulator 为 iOS 26.4.1（23E254a），不只是配置中的版本请求。
- CI16 的真实 CF/Keychain 合成探针通过新增、读取、更新、空值、UTF-8、命名空间隔离、删除及跨进程持久化验证。seed PID 13971 已确认退出，再由新 PID 14148 执行 verify 并完成读取、删除与最终空命名空间核验；两份报告的 probe、AppGraph 初始化及 sessionAbsent 成功。配置的 6 秒稳定性阶段与截图后的离散进程观察一致，不外推为长期稳定性。
- 两个启动阶段的原始截图均经人工核对，为正常 Login，而非 SpringBoard 或白屏。归档机器字段仍保留 `login_ui_verified=false`、`ui_review=pending`，未改写成自动 UI 验收通过；此次没有进行学校账号登录或交互验收。
- 诊断模式在 Darwin HTTP 客户端的 `onRequest` 出口使用 fail-closed 保护；报告没有请求计数器或网络抓包，不宣称量测了 HTTP 请求数为 0，也不外推到系统或其他客户端联网。
- 此次成功不抹去 CI12/14/15 的失败，也不是新增脚本内重试或 unsigned A/B 成功对照。成功流程未触发 90 秒失败日志采集窗口，不把成功单因归于该窗口或 runtime 版本。

## 尚待验收

当前仍是开发预览，上述离线与模拟器结果不代表全部需求或发布验收已完成。以下项目仍需分别记录结果：

- iOS 真机/设备 archive、实际交互、学校账号登录与完整双端回归。
- 真机 GPS 运动、实体水控器 BLE/GATT 兼容性；既有虚拟链路结果不替代实体设备。
- 头像、附件实际提交与原生更新安装；离线表单及安装前校验不等于服务端或系统接受。
- 真实跑步任务与线上发送；发送入口仍关闭，现有合成路线仅本机预览，未验证真实任务接受性。
- 生产预约/体测的实际写入接受性；本轮未执行预约、报名或取消写操作。匿名短信与场地取消契约仍有未确认项。
- S7 路线手绘在本节验证提交中尚无实现；后续实现及集成验收另记，不算作本轮已完成。其他 provider、任务 marker 与其余导入增强场景也需独立验证。
- 组件层级、完整 TalkBack/VoiceOver、发布签名与正式发版。
