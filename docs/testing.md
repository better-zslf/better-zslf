# 验证记录

## 文档拆分说明（2026-10-26）

本页保留普通功能、真实GPS人工流程和通用平台的历史事实。历史章节数字来自当时单版本/指定提交，包含现已归Pro的子集，不是Standard/Pro拆分后的数量；本次实际CI结果单列固定S/C与运行链接，不与学校或设备验收混用。

热水器及路线导入/手绘/补里程/自动规划的专项开发、操作和验收历史迁至[Pro验证记录](https://github.com/better-zslf/pro-ci/blob/main/docs/testing.md)、[热水器](https://github.com/better-zslf/pro-ci/blob/main/docs/water.md)、[路线工具](https://github.com/better-zslf/pro-ci/blob/main/docs/routes.md)。Pro CI为私有仓，链接需相应访问权限。默认standard保留任务/成绩/历史/服务器地图/真实GPS记录与显式人工流程；Pro也保留生产`runFactory=null`等既有门。双仓与edition验收另记，不把627等旧报告当作本轮已跑。

## 2026-10-26：GitHub App跨仓与双版CI验收

- 当前跨仓认证改为GitHub App；维护者已确认JWT身份、installation `175411436`的`better-zslf`/selected及Actions写/Contents读/Metadata读元数据。另已实证Pro CI token仅列唯一Pro CI仓（Actions写/Metadata读），Source token仅列唯一Source仓（Contents读/Metadata读）；读源main ref与Pro活跃workflow通过，测试token已revoke。未枚举App全installation仓库名单，不把这些API结果外推为新App完整CI、Native或自动发行通过。
- 两仓`CI_APP_CLIENT_ID`（Repository Variable）和`CI_APP_PRIVATE_KEY`（加密Repository Secret，App PEM）已配置成功，不再要求两枚个人PAT。每job单仓token约1小时、job末默认revoke，不跨job复用；五项Android签名secret不变。[配置与状态](https://github.com/better-zslf/pro-ci/blob/main/docs/ci.md)。
- 完整测试workflow统一在Pro canonical tests入口；源码S、控制器C、source gate/签名/aggregate职责分别记录。Pro tag为C而产物Source SHA为S，既有tag/release不覆盖；Source `publish=false`以40位`source_ref`dry-run，不创建正式tag/release。
- [实际App触发的完整验收40312777553](<https://github.com/better-zslf/pro-ci/actions/runs/40312777553>)成功，actor为`pro-ci[bot]`，固定Source S=`82ce360ac09ac574cfd73f27437b2230f9f17d1d`、controller C=`2ed9e9f1dcc8df096deb56533107cdb96af92b2e`。Android两版检查、iOS两版framework/Xcode构建及owned Simulator smoke通过，各Job独立签发/私有源检出/post撤销通过；本机Source248、controller36、iOS版本脚本Bash23通过。
- 首次Pro Xcode二次Gradle配置的版本后缀错误已窄修，Apple marketing保持数字、Gradle Pro version加-pro，原Android严格版本门和Native诊断/owner/时窗不变。原失败运行保留。本次publish=false，正式签名发行job跳过，没有制造正式Source/Pro版本；学校业务、真机及硬件结果仍分别记录。

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

- 内容分页、通知已读、预约/体测表单与附件流程及真实采样会话协调已接入；当时单版本的Pro专项实现历史迁至上方Pro记录。
- 全量项目报告 238 个 JUnit 方法、零失败/错误：237 项离线测试与 1 个默认关闭的 Live 门控方法。预约/体测的 28 项离线用例包含在内。
- Android Debug APK、lint 均通过；另在 GitHub 的测试分支成功完成 Android 检查与产物上传。
- 首次 macOS CI 在 Android command-line tools 下载阶段返回 404，未进入 iOS 编译；不能把该次运行当作 iOS 验收。
- 同次Pro软件蓝牙的分层验证及其后续记录已迁至[Pro验证历史](https://github.com/better-zslf/pro-ci/blob/main/docs/testing.md)，不计为Standard原生/硬件验收。
- 增加仅 Debug 构建可用的原生定位/文件选择探针；探针不创建学校 HTTP 客户端，不上传轨迹或文件，不进入 Release APK。

## 2026-10-25：测试分支集成

- [CI12](https://github.com/better-zslf/better-zslf/actions/runs/40162399854) 对应 `6696138`：Android 测试、lint 与 Debug APK 构建通过。JUnit 报告为 599 个方法、零失败/错误；其中 1 个 Live 门控默认关闭，实际离线用例为 598 项。Android App 单元测试任务仍为 NO-SOURCE。
- 诊断宿主脚本 42 项及 scripts 测试 67 项已实际通过。这些是宿主回归，不计入 Native 验收。
- 该日热水器的Pro虚拟端到端记录及证据断言口径已迁至[Pro验证历史](https://github.com/better-zslf/pro-ci/blob/main/docs/testing.md)；未改写为Standard或实体兼容性通过。
- 本机前一集成版已验证个人资料只读页面、找回页空表单禁发及返回、横竖屏 NavigationRail 切换与四标签独立历史。找回测试未发送短信、核验验证码或重置密码。
- Android地图平台的受控主文档403、原生裁剪与相对高度基准为零问题曾修复；该次实际数据验证是现属Pro的合成导入场景，视口/Ready/坐标证据与未覆盖项见[Pro记录](https://github.com/better-zslf/pro-ci/blob/main/docs/testing.md)。它不是正常服务器任务地图或真实GPS已验收。
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

### Android：四标签页与地图验收边界

- 旧APK保留既有会话，首页、校园跑、社区、我的四标签页均已实际打开并视觉核对，未伪造真实任务内容。
- 同次GPX本机预览、长短路线的同文档替换、真实Ready状态及清空草稿记录属于现已归Pro的场景，完整观察、未注入与未覆盖边界迁至[Pro验证历史](https://github.com/better-zslf/pro-ci/blob/main/docs/testing.md)。它不证明Standard实际服务器任务地图/marker、真机GPS、其他provider或全部交互通过。

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
- 真机GPS运动、真实服务器任务地图/marker与正常人工记录的端到端流程。
- 头像、附件实际提交与原生更新安装；离线表单及安装前校验不等于服务端或系统接受。
- 真实跑步任务与线上发送；发送入口仍关闭，生产runFactory/transportFactory门控不因Pro属性而解除，真实任务接受性尚未验证。
- 生产预约/体测的实际写入接受性；本轮未执行预约、报名或取消写操作。匿名短信与场地取消契约仍有未确认项。
- Pro手绘、导入增强、其他provider、规划及实体水控器等专项未验事项与旧S7实现状态，见[Pro待验清单](https://github.com/better-zslf/pro-ci/blob/main/docs/testing.md)，不从旧记录倒推当前已完成。
- 组件层级、完整 TalkBack/VoiceOver、发布签名与正式发版。
