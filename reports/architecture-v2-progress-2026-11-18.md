# V2 架构重构：2026-11-18 实施与验证记录

> 进行中记录，Asia/Shanghai。不是最终验收，也不是可发布的固定 S/P 配对。
> **已按用户“先落盘，不做了”暂停。** 已完成 Run 全只读四层与课表 Binding 最小批的公共／Pro 实测闭环，最新 shared JVM/Host **672/669**、课表 runtime **40/40**、课表 UI **6/6**；Pro 332 项（本轮 composition 15 执行，其余 UP-TO-DATE）。全量守卫公共 360／私有 84 通过，详见末尾。校园登录下一批仅有只读方案、基线与未领取任务，**尚未改源或接线**。整体 V2/R5 仍未完成，自动目标已暂停。

暂停交接与恢复起点已另存：[V2 暂停交接](<architecture-v2-paused-2026-11-18.md>)。本轮仅保留本地代码与证据，不再继续实施。

## 基线与操作边界

- 工作区为 `E:/better-zslf`；公共 HEAD 仍为 `1674115e7ccfc574313647d1e4c646903601c4c5`，私有 HEAD 仍为 `80481a947c4a03b8671cc709a8b741e4652b8ba3`。
- 实施依据是已批准的 [V2 方案](<architecture-decoupling-v2-pro-extraction-2026-11-17.md>)；[上一日交接](<architecture-v2-progress-2026-11-17.md>)中的断点是历史状态，不代表今日仍未修。
- [agents.md](<../agents.md>) 仅保留模型规则，没有混入项目规范。
- 没有 stage／commit／push、fetch、历史改写、签名、发布或安装；没有真实学校业务请求、GPS、BLE 扫描／连接或真实上传。
- 原有未跟踪文件与本地缓存保留。物理迁移的大量新文件仍未跟踪，不能只保存 tracked diff 作为完整备份。
- [公共基线锁](<../work/pro-ci/public-base.properties>) 未改 pin，`releaseReady=false`。本地 Pro 验证只使用显式开发模式；不宣称当前 dirty 树已形成可复现发布。

## 已落盘的主要闭包

### 公共与私有产品根

- [公共 settings](<../settings.gradle.kts>) 与 [shared 构建](<../shared/build.gradle.kts>) 为 Standard-only；不再以 source overlays／同名 edition 替身纳入 Pro。
- [私有 settings](<../work/pro-ci/settings.gradle.kts>) 通过明确坐标替换消费公共 API／模块，拥有 Water、route-tools、map-editor、composition、ProApp 和 Android 产品根。
- 公共只读地图与私有编辑器分属独立模块；公共真实 GPS、课表、资源和定位权限留在公共产品。私有 picker、BLE 平台实现、文案、测试、工具及 notice 均随功能归属迁移。
- [产品贡献合同](<../shared/src/commonMain/kotlin/io/github/betterzslf/composition/ApplicationContributions.kt>)、[产品导航合同与 saver](<../shared/src/commonMain/kotlin/io/github/betterzslf/navigation/ProductNavigation.kt>)、[Pro 导航](<../work/pro-ci/pro/composition/src/commonMain/kotlin/io/github/betterzslf/pro/composition/ProNavigation.kt>) 使用显式 typed key 与封闭持久化 union，不序列化开放 NavKey。
- [App](<../shared/src/commonMain/kotlin/io/github/betterzslf/App.kt>) 中产品／run／schedule／update 清理与 wipe 顺序已改为可等待、single-flight，关闭先失效 admission；本地清理失败保持 fail-closed，不先删 storage 后异步停 owner。

### R5 第一批真实功能分层

| 范围 | 已落盘 | 验证边界 |
| --- | --- | --- |
| 课表 | [api 模型](<../feature/schedule/api/src/commonMain/kotlin/io/github/betterzslf/feature/schedule/ScheduleModels.kt>)、[domain 日期算法](<../feature/schedule/domain/src/commonMain/kotlin/io/github/betterzslf/feature/schedule/ScheduleDates.kt>)／学籍端口、data Ktor／解析／三平台 codec 及测试物理迁移 | JVM 通过；Runtime／persistence／native publication／课表 UI 尚在 shared |
| Home | [纯投影](<../feature/home/presentation/src/commonMain/kotlin/io/github/betterzslf/feature/home/presentation/HomePresentation.kt>)、[完整 Content](<../feature/home/ui/src/commonMain/kotlin/io/github/betterzslf/feature/home/ui/HomeContent.kt>)、[薄 binder](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/HomePage.kt>)、[typed adapter](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/HomeScheduleAdapter.kt>) | 24 项纯投影测试及 UI JVM 编译通过；shared adapter 11 项待统一执行 |
| update Phase 1 | [domain](<../feature/update/domain/build.gradle.kts>)／[data](<../feature/update/data/build.gradle.kts>)／[presentation](<../feature/update/presentation/build.gradle.kts>)／[ui](<../feature/update/ui/build.gradle.kts>)，shared 仅产品 binder 与原生 factory／actual 闭包 | 四模块 50 项测试通过；原 factory 4 项与 AndroidHost 3 项待 shared 集成；原生 actual 未迁出，不冒充 native isolation |

Home 另明确修复 UNSYNCED 与未绑定区分、真实进度／长课时倒计时、下一课程正确 term 索引；原 13 项测试的 51 条断言逐行守恒。UI 状态为不可变显示 DTO 和 typed events，不暴露 Graph／ScheduleSnapshot／Route。

update 保留原 47 个 common 测试方法，各名称恰好一个定义；新增 7 个投影／事件门禁测试。UI 不持有 URL、policy、repository、controller 或安装 spec；spec 仅在 controller 内部，确认重新核验当前候选、policy 与原生状态。

## 安全补丁（正在统一复验）

- [SessionStore](<../core/network-session/src/commonMain/kotlin/io/github/betterzslf/core/storage/SessionStore.kt>) 以单一内部 snapshot 作为请求 authority；成功 save（含相同内容）与 clear finally 同步递增 epoch，先发布 authority 后发布兼容 flows。失败 save 不换 epoch，删除失败仍立即失效内存。
- [ApiSessionBinding](<../core/network-session/src/commonMain/kotlin/io/github/betterzslf/core/net/ApiSessionBinding.kt>) 与 [ApiClient](<../core/network-session/src/commonMain/kotlin/io/github/betterzslf/core/net/ApiClient.kt>) 将所有 bound／unbound 请求及 HTTP／envelope 401 绑定捕获 snapshot。旧 401 不清新 epoch；匿名旧响应无 authority 清新登录。
- [AuthRepository](<../shared/src/commonMain/kotlin/io/github/betterzslf/data/AuthRepository.kt>) 的密码变更迟到 Accepted 使用 compare-and-clear，保持 `NEW_SESSION_PRESERVED`／`CLEAR_FAILED` receipt，未恢复不安全 token-only API。
- [RunRuntime](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/runui/RunRuntime.kt>) 正在补同值 save／ABA epoch、强制显式恢复、history 不选中旧 record、停止旧 worker 不重新公开 record，以及 transport 同步 identity gate。
- [RunSessionCoordinator](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/run/RunSessionCoordinator.kt>) 与 [RunUploadWorker](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/run/RunUploadWorker.kt>) 的固定采样 lease／取消后不续批补丁由 task-35 实施中，尚未验证。
- [原生课表 open resolver](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/schedule/ScheduleNativeOpenTarget.kt>) 已修独立 local slot 准入，不再把 campus userId 当学籍 owner；返回完整 term／course／week selection，15 个新测试定义待执行。
- App 的独立学籍 shell 准入已补纯导航测试；全局 epoch 重建可能丢密码重置 receipt／重启独立 dorm 生命周期，正在改为有限 binder 的 epoch key，尚不作为已闭合项。

## iOS、CI 与来源门

- 公共 iOS 只产／链接 Shared；私有 [ProApp 构建](<../work/pro-ci/pro/app/build.gradle.kts>) 只产一个静态 ProApp；私有 [Xcode 配置](<../work/pro-ci/iosApp/project.yml>) 只链接 ProApp，不链接第二个 Shared。无 `export()`／`transitiveExport`。
- [私有 Swift 入口](<../work/pro-ci/iosApp/Sources/ContentView.swift>) 与 [Kotlin 入口](<../work/pro-ci/pro/app/src/iosMain/kotlin/io/github/betterzslf/pro/ProMainViewController.kt>) 仅源码命名形态核对一致；没有生成 header／实际 Swift ABI 验证。
- 双产品版本仍 code 59，Standard `1.0.0-rc58`，Pro `1.0.0-rc58-pro`；Apple marketing numeric `1.0.0`。Android 两根已把版本覆盖收紧为 canonical code／exact assertions，不允许命令行产生另一 code。
- [私有构建门](<../work/pro-ci/build.gradle.kts>) 已收紧 closed UTF-8 lock schema、固定 repository／S／API、strict boolean、独立 Git root、origin／HEAD／tracked+untracked clean；显式开发 verify 已实际 PASS。false-lock 与非 canonical 开发参数拒绝测试正在运行。
- 私有 [release bridge](<../work/pro-ci/scripts/release_bridge.py>)、[独立 verifier](<../work/pro-ci/scripts/verify_release.py>) 与 workflows 已切私有产品根：S/P/W 分别追溯，独立版本，记录锁／工具／精确 workflow blob 摘要，fresh 每 job 最小 token；false latch 保持关闭。
- 公共新增九模块的架构／文字／CI scope 正在 task-36 跟进。旧 protocol allowlist 只允许逐项审查迁移路径／offset，不能扩大白名单拿绿。

## 本日实际执行证据

| 执行 | 实际结果 | 不代表 |
| --- | --- | --- |
| schedule api/domain/data 离线 JVM | exit 0；domain 11／0 fail；data 37／0 fail／1 skip（live probe 未启用） | 不代表学校在线登录、Android／Native codec passed |
| Home presentation tests + UI compileKotlinJvm | exit 0；24／0 fail／0 skip | 不代表 Compose 视觉／设备布局已验 |
| update 四模块 jvmTest | exit 0；domain 11、data 22、presentation 14、ui 3，全部 0 fail／0 skip | 不代表 Android 安装器或 iOS actual 已验 |
| 私有 verifyPublicBase 显式开发模式 | exit 0 | 不代表 releaseReady 或固定 dirty pair 可发布 |
| iOS Python 单元测试（成员执行） | 公共 14+8，私有 23+9，全 PASS | 不代表 K/N／Xcode 编译 |
| iOS 静态 source guards（成员执行） | 公共 GPS 25／map 45；私有 BLEpicker 27／editor 48，全 PASS | 不代表原生权限、回调、设备能力 |
| 私有脚本离线 suite（成员执行） | 71／71 PASS（bridge 50、verifier 12、metadata 9） | 不代表远端 workflow 或发布成功 |

早于 R5／P0 补丁的公共 shared 953、私有 Water 92／map-editor 37／route-tools 188 等通过证据仅为历史；当前树需要新的统一回归后才更新本表。所有未来 test run 显式移除 `SCHEDULE_LIVE_PROBE`、`SCHEDULE_TEST_ACCOUNT`、`SCHEDULE_TEST_PASSWORD`。

## 12:18 统一回归与已修真实集成故障

### Gradle 组件坐标不能沿用相同 group + 叶名

- 首轮 shared 编译出现数百个已存在类型无法解析，并非这些文件未搬完整。实际原因是不同子树重复的 `api`／`domain`／`data` 等项目使用同一 `group:name`，Gradle 在组合依赖图中把项目静默替换成了另一项目。
- [KMP convention](<../build-logic/src/main/groovy/betterzslf.kmp-library.gradle>) 现按 parent-qualified group 产生唯一身份；[根坐标门](<../build.gradle.kts>) 在 compile/link/assemble/bundle/test 前检查全体可复用项目。实际输出 18 个唯一身份，主编译闭合。私有消费仍用 [明确 substitution aliases](<../work/pro-ci/settings.gradle.kts>)，未借 source overlays 或 friend paths。

### epoch、取消和 page lifetime 闭合结果

- task-33／35／37／38 生产代码与测试均已冻结。真实 Run owner 固定 epoch；旧 GPS 采样 lease 不换新身份；取消后的 transport 只准有限本地 receipt checkpoint，不继续 claim/send；queue 确认在 mutex 内和 persist 前复验 authority。
- root NavDisplay 不再全局 `key(epoch)`；有限会话 binder 重建能力／敏感输入，AccountRecovery finalReceipt 保留在局部 epoch key 外，Dorm controller／启动清理／heartbeat 不因 campus epoch 被重启。
- Profile 同值 save 与 ABA 现在同步隐藏字段并清空旧 avatar bytes，未等 flow collector；迟到 decode／read 不能重新发布；仍只允许显式 refresh 读网络。
- 首轮公共 JVM 924／0 fail、AndroidHost 924／9 fail：9 项全部来自新增 [应用生命周期测试](<../shared/src/commonTest/kotlin/io/github/betterzslf/composition/ApplicationCompositionLifecycleTest.kt>) 隐式创建真实 Android ScheduleNativeBridge，host 无 context。现 [AppGraph](<../shared/src/commonMain/kotlin/io/github/betterzslf/App.kt>) 增加默认行为不变的尾部 `scheduleNativeFactory`，fixture 显式注入 Unsupported 假桥，保留全部 9 项断言；没有用忽略／删除测试拿绿。
- 最终 `:shared:jvmTest` **929 tests／0 failures／0 errors／0 skipped**；`:shared:testAndroidHostTest` **929／0／0／0**。其中 ProfileController 12（含新 5）、SessionEpochReadFence 10、RunRuntime 36、Coordinator 29、Queue 20、Worker 5、native-open resolver 15、SessionNavigation 11、生命周期 9，已核 XML 而非仅数源码注解。前轮 924 的 Profile XML 只有旧 7 项；未把尚未纳入的 5 项误算成首轮已执行。
- Standard `:androidApp:assembleDebug` 实际成功；android-host 和 androidApp 的 UnitTest task 为 NO-SOURCE，不能称其有新增断言。Host 是本地 JVM 测试，不是 Android 设备／Instrumentation 测试。

### 模块化 JVM 实际结果

| 模块 | 测试数 | failures/errors/skipped |
| --- | ---: | --- |
| core:run-model | 19 | 0/0/0 |
| design-system | 8 | 0/0/0 |
| feature:run:api | 2 | 0/0/0 |
| services:presentation | 5 | 0/0/0 |
| readonly-map | 35 | 0/0/0 |
| schedule:domain | 11 | 0/0/0 |
| schedule:data | 37 | 0/0/1（live probe 未启用） |
| home:presentation | 24 | 0/0/0 |
| update:domain/data/presentation/ui | 11/22/14/3 | 全部 0/0/0 |

core:text、schedule:api、services:ui、home:ui 的测试 task 无测试源；生产编译通过，不伪造测试计数。公共 shared 新 Home adapter／update factory 已随 929 闭包执行。

### Pro JVM 与 D8 facade 冲突

- 私有开发模式 `verifyPublicBase` 实际通过；Water 92、map-editor 37、route-tools 188、Pro navigation 15 均 0 fail/error/skip；ProApp JVM 无 source／tests，不冒称 iOS umbrella 已编译。
- 首轮 Pro Android 编译成功但 D8 合并失败：公共 GPS permission requester 与私有文件 picker 的实际函数不同，却同包同名 `FilePicker.android.kt`，均生成 `FilePicker_androidKt` 和 synthetic lambda，导致重复 DEX 类型。这是 Android 最终制品验证才捕捉到的真实问题。
- 私有三平台 actual 已作**字节保持物理重命名**为 [ProFilePicker.android](<../work/pro-ci/pro/route-tools/src/androidMain/kotlin/io/github/betterzslf/platform/ProFilePicker.android.kt>)／[ProFilePicker.jvm](<../work/pro-ci/pro/route-tools/src/jvmMain/kotlin/io/github/betterzslf/platform/ProFilePicker.jvm.kt>)／[ProFilePicker.ios](<../work/pro-ci/pro/route-tools/src/iosMain/kotlin/io/github/betterzslf/platform/ProFilePicker.ios.kt>)，API／权限／协议不改；私有 iOS 静态 guard 路径已同步。未用排除依赖、重复类豁免或 shrink 避开错误。最终 Pro 全 JVM＋`:androidApp:assembleDebug` **exit 0**（1m51s）；实际 Pro navigation 15 项成功，route-tools 188 项在重命名后重跑成功。

### 守卫、来源与实际 APK 审查

- task-36 公共脚本 suite **321／321 PASS**；私有脚本 **71／71 PASS**。最终真实 architecture guard **PASS，226 files**，display guard **PASS，198 files／2734 tokens／60 Han／60 精确例外（58 protocol，2 diagnostic）**。
- 相对 HEAD 的 allowlist 只改原 55 项路径（索引 5..59）：26 HTML + 29 WeekParsing。source／kind／context／全部四位置字段与 reason/category/owner 全守恒；另外 5 项未动。不扩大白名单。
- 实际 Standard debug APK 已按 DEX class_defs（不是字符串搜索）+ merged manifest + ZIP 资源审查：19 DEX，22205 个 defined classes，私有 Water／Pro／Kable／route editor/import 符号 **0**；无 BLE 权限／feature；GPS 前后台权限、RunLocationService、课表 receiver／widgets、updater class/provider 与课表资源都在。此审查不安装、不请求权限、不启动 GPS。
- 私有 false-lock 和 `publicDevelopment=TRUE` 两项 Gradle 负例均按预期拒绝。锁仍 `releaseReady=false`；当前 dirty 开发树不变成发布 pin。
- 仅命令拼写失误：两个 guard 均没有 `--strict` CLI 参数；第一次多传参数被 argparse 拒绝后，按其默认 fail-closed 模式重跑已通过。此命令失败不记成守卫代码失败或“首次全绿”。

## 尚待完成

1. 私有离线 facade guard 与旧 FilePicker 负例加入 CI 预检中；生产源码已冻结，不因静态守卫改变已通过的产品。
2. R5 仍未完成 auth／run／campusnet 的完整 domain/data/presentation/UI 分层；不得把第一批样板当全部功能完成。下一步应先抽真正 core network/session 单安全闭包，再推进 [密码恢复四模块纵切规划](<account-recovery-slice-plan-2026-11-18.md>) 与 [校园网四模块纵切规划](<campusnet-vertical-slice-plan-2026-11-18.md>)；两份规划不是源码完成。
3. macOS K/N／Xcode、模拟器／实体设备／BLE／真实 GPS、签名发布均未执行；正式 S/P pin 需要审查提交后另行完成，不自动修改 false 锁。

### 最终双产品制品补证

- Standard：`io.github.betterzslf`／code59／`1.0.0-rc58`；19 DEX／22205 defined classes／0 private classes；SHA-256 `26b76daa5ce2752cf6122ceaa4d4b9a4dc8eb2f98399927854ca48ae2023ab3c`。
- Pro：`io.github.betterzslf.pro`／code59／`1.0.0-rc58-pro`；20 DEX／23064 defined classes／552 private Water/Pro/Kable/import classes；WaterBleController、ProRuntimeOwner、RunImportController、MapEditorDrawing、AndroidMapEditorRenderer 均真实存在。SCAN/CONNECT 与 optional BLE feature 仅 Pro 有；GPS／课表／updater 两产品都保留；SHA-256 `2a991a68f648c87e3e68097eb60c11b672b0edfa2b3bcd0880b40d87287c0652`。
- [本地审查脚本](<../.local/v2-validation/audit_products.py>) 和 [逐项 JSON 证据](<../.local/v2-validation/apk-capability-audit.json>) 只读最终 debug APK；第一版审查错误地期待已淘汰的 RunMapEditing 类型，定位到实际 MapEditorDrawing/AndroidMapEditorRenderer 后按真实源码及 DEX 修正断言。没有据此修改应用、缩小真实功能或把字符串引用冒充 class_defs。
- 全生产 Kotlin 254 文件的只读跨 module 审计：common＋Android、common＋JVM 和 Native 词法同 basename 候选均 0；502 个顶层 named class/interface/object 与文件 facade 交叉亦无撞名。无 JvmName／multifile／JvmPackageName override。此静态审查不替代已完成的 D8，也不宣称 K/N 验证通过。
- 重命名后私有 iOS source/plist guard 27／27、Python version/locator 23／23 实际通过；未运行 macOS 构建。

## 核心抽取批次：真实 logging 与 network-session（task-43／44／45）

- [logging](<../core/logging/build.gradle.kts>) 独立持有 AppLog／LogEntry／LogRedaction 三个原生产文件及日志测试，物理迁移且字节不变；仅依赖 coroutines。没有增加 diagnostics 替身或将内部时钟助手公开。
- [network-session](<../core/network-session/build.gradle.kts>) 独立持有六个 net 文件、Session、ApiResult、KeyValueStore、SessionStore 共十个生产文件。九个文件字节守恒；ApiClient 仅把既有 `boundOwnerId`／`isBindingCurrent`／`clearCurrentBinding` 三个有限操作改为 public 并补固定 provenance／authority 文档，复原该三处修改后 SHA 与迁移前一致。snapshot、binding 构造及 token／epoch 访问保持内部闭包。
- 新核心只依赖 core:text／core:logging 与原 Ktor／serialization／coroutines；不依赖 shared、repositories 或任何 feature/UI。shared 显式 api 两核心，私有根显式新增两条 composite 替换；实际坐标校验输出二十个唯一组件身份。
- task-43 的五十个原测试方法同名保留：StoreRevision 12、Store 7、ApiClient 16、Binding 13、Multipart 2。[核心 fixture](<../core/network-session/src/commonTest/kotlin/io/github/betterzslf/core/net/CoreApiTestFixture.kt>) 为 internal 且不创建 AppRepositories；原 shared fixture 字节不变。十四个原 repo API 场景与九个 repo binding 场景另保留为真实 shared 集成断言，六个原纯 API 场景不重复保留在 shared。
- **已实际通过**：日志 JVM 10／0；network-session 与 shared 的 JVM／Android 生产编译；Standard debug assemble。此次 Standard APK 再审：十九个 DEX／22205 个 defined classes／零 private classes，能力检查全通过，SHA-256 `e934200d9cf7e08555da2f12fc6ebe65cfb98637ecbb74b2b46afdd8a80b3daa`。此处尚不声称五十个核心测试或拆分后的 shared 测试通过。
- task-42 私有 facade 预检已落真实两份 CI，在正式锁校验与 public root／wrapper 校验之后、Gradle 之前执行；已复现旧 FilePicker 不同函数同文件名仍碰撞的负例。成员实际执行私有 Python 84／84、真实组合词法预检 250 文件和两 shell 语法检查通过；Lead 统一复核另记。此预检是保守源码检查，不替代 D8 或 K/N。
- **task-45 实际闭环通过**：公共串行构建 1m49s、私有构建 1m31s，均 exit 0。真实 JUnit XML 核对：logging JVM／Host 各 10、network-session JVM／Host 各 50、shared JVM／Host 各 **892**，全部 failure/error/skip=0；shared 中原六组 core/log 测试输出已移除，新 repository 14＋9 两组均存在，不混入旧 XML。Pro Water 92／route-tools 188／map-editor 37／composition 15，共 **332**，全零失败。部分既有 Pro 测试为 Gradle UP-TO-DATE，依据未变输入及现存 XML，不伪称强制重执行；ProApp／AndroidApp 测试仍 NO-SOURCE。
- 双 debug 再审通过：Standard 保持十九个 DEX／22205 defined／零 private；Pro 二十个 DEX／23064 defined／552 private，BLE 仅 Pro，GPS／课表／updater 两产品均在。最新 Pro SHA-256 `f21fd2e4d76fd28aca8ff63893e0343b4016440b76e6bb9c25338ac1ba309a19`；Standard SHA 如上。只读 [逐组 JUnit 证据](<../.local/v2-validation/core-regression-junit.json>) 与 [双 APK 证据](<../.local/v2-validation/apk-capability-audit.json>) 已更新。
- Lead 复跑 task-42 私有 Python **84／84** 与真实组合预检 **250 文件 PASS**。公共两核心 boundary／display／JVM＋Native CI 更新仍由 task-46 独立实施中。
- 密码恢复及校园网三层／UI 后继任务已有精确不重叠范围与依赖；本核心阶段的通过不代表后继功能分层已实现。

## 密码恢复、校园网与更新平台批次（task-47～52）

### 真实分层与行为保留

- 密码恢复的 [domain](<../feature/accountrecovery/domain/build.gradle.kts>)／[data](<../feature/accountrecovery/data/build.gradle.kts>)／[presentation](<../feature/accountrecovery/presentation/build.gradle.kts>)／[ui](<../feature/accountrecovery/ui/build.gradle.kts>) 均为实际源码模块。data 直接使用固定 core session binding 的三端点，严格读取 `data.user.id`；已受理 reset 即使清理抛错或取消仍保留 Accepted 回执，不清理替换后的会话。Auth 原 reset/modify 合同未重写，只把密码谓词委托 domain 去重。
- Recovery Content 持有完整页面、卡片、提示和确认框，但不接 Graph、Controller、手机号／验证码／密码原串、target 或票据。可信 [产品 binder](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/accountrecovery/AccountRecoveryPage.kt>) 保留非 saveable 输入 slots、固定 dialog-open 手机快照和 controller-owner-fenced reset ticket；同步输入代次与显示 preview 引用双重校验，旧 frame 不得借用相同 mask 的新 preview。最终回执在 epoch key 外，predictive-back 在 commit 读取最新写入状态。
- 校园网的 [domain](<../feature/campusnet/domain/build.gradle.kts>)／[data](<../feature/campusnet/data/build.gradle.kts>)／[presentation](<../feature/campusnet/presentation/build.gradle.kts>)／[ui](<../feature/campusnet/ui/build.gradle.kts>) 接收真实仓储、严格账户解析、portal probe 和完整 Content。两仓储七个 raw wire methods 收为 internal，不能借 inferred return 对外暴露 JSON；因此不是仓储逐字节 MOVE，只有 wire body 原样保留。混合 [RepositoryContractTest](<../shared/src/commonTest/kotlin/io/github/betterzslf/data/RepositoryContractTest.kt>) 七个校园入口改用真实 typed factory，所有既有路径、参数、授权、方法与超时断言保留。
- Campus Content 只收 masked 账户和 portal 安全投影，raw portal 地址、最终确认 id 与 ack 留 [产品 binder](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/campusnet/CampusNetworkFeaturePage.kt>)。新门禁固定用户打开的 preview，不能替换版本；它本身不是跨 controller 的授权票据，真实 controller＋固定 source binding 仍负责最终写入门禁。Login 仅更换两个 typed 读取接缝与 phone 前缀薄委托，逆向还原接缝可恢复迁移前 SHA；Dorm 的 credential、remember、heartbeat 与 signOut 生命周期未改。
- 抽取时发现既有 portal 漏洞：close/epoch 退役后迟到 redirect 或 probe 仍可继续后继请求。经单独批准，在每个挂起边界后补 cancellation/current 检查，并在 probe/dial 前复查；新增关闭期间迟回和 same-save／ABA 证据，不自动重试任何不确定写入。
- [platform:updates](<../platform/updates/build.gradle.kts>) 拥有真实 Android updater、统一不可用平台 owner、原 manifest/provider 与 cache-path XML。原 manifest SHA `38d058a57b3ab7862ea53389b162a8a0fd628fbf0a782a229151bd56b2aef92a`、XML SHA `f150b258083af2d50b2288800ac8321ebc0c67048ce44cdc6c2fbd57f2d36356` 字节守恒且旧位置已移除。三端 shared actual 留薄工厂；Android 仅注入 lazy context supplier，没有全局 Context owner。下载／校验／ledger／close 实现主体逆掉三处接线后与迁移前 SHA 相同。
- [公共 settings](<../settings.gradle.kts>) 与 [私有 composite settings](<../work/pro-ci/settings.gradle.kts>) 显式接线新增九模块；坐标校验实际输出 **29 个唯一组件**。没有 overlay、friend path、第二个 framework 或 transitive export。

### 构建失败与实际验证（不是测试定义数）

- 第一轮独立 updater Android 编译停于离线依赖解析：core 1.18.0 默认引用未缓存的 lifecycle 2.6.2／annotation 1.8.1。显式按现有产品图对齐 AndroidX lifecycle 2.11.0／annotation 1.10.0 后通过，未排除 FileProvider 等依赖，也未引入 Compose。第二次全批停于独立 UI Host 的旧 activity-ktx／core-ktx 离线缺失；仅在两 UI 的 AndroidHostTest 对齐现有产品版本，未把 Activity 加入可复用 Content 生产层。
- 最终公共批 **4m59s、exit 0**：新增各模块 JVM／Android Host、shared JVM／Host、Standard debug assemble 均实际通过。真实 XML：Recovery domain **5/5**、data **14/14**、presentation **36/36**、UI **8/5**；Campus data **21/21**、presentation **31/31**、UI **8/8**；native updates **3/9**；shared **838/835**（顺序均 JVM/Host），failure/error/skip 全零。Campus domain 为生产纯合同／规则，无独立测试，任务 NO-SOURCE 不伪称通过若干项；phone 规则由 data 与保留的三项 Login tests 覆盖。
- [逐组审查脚本](<../.local/v2-validation/summarize_feature_regression.py>) 核对精确计数、真实 testcase classname、迁移后 shared 不留旧 suite XML，以及 Recovery binding **6**／dialog **15**、Campus binding **6**／gate **8**、core repository 集成 **14+9**、shared updater adapter **3** 等仍存在。[JUnit JSON](<../.local/v2-validation/feature-regression-junit.json>) 已落。可选 live test 实际输出 `LIVE_READ_ONLY_SKIPPED: no live requests`；运行前显式清空六个 live opt-in／credential 环境变量。
- 私有 consumer 最终 **3m49s、exit 0**：Water **92**、route-tools **188**、map-editor **37**、composition **15** 共 **332**；此次日志四个 JVM test task 均实际执行而非 UP-TO-DATE。ProApp 与 AndroidApp tests 仍 NO-SOURCE；Pro debug assemble、D8 合并成功。
- 双 APK DEX class_defs／manifest／资源审查全通过：Standard **19 DEX／22289 defined／0 private**，SHA `8b553a0d845a0f86d8c4237f8788f1db2d6580ae228a6d76b7dfc8a382b9aebf`；Pro **20 DEX／23148 defined／552 private**，SHA `e963311a38613181872d3b662c343cee196b929526d176e02596afd50b0f14dd`。BLE 仍仅 Pro；两产品 GPS／课表／updater 类、组件与资源齐全；新增断言 provider 恰一份、authority 为各产品 appId＋`.updates`、非 exported、允许 URI grant 且正确关联唯一 cache XML。[最终 APK JSON](<../.local/v2-validation/apk-capability-audit.json>) 已更新。
- 原有 NonCancellable launch、实验 coroutines test opt-in、Instant alias 等编译 warning 未借本批重构顺改。没有执行视觉 UI、macOS K/N／Xcode、通知／安装／GPS／BLE 设备验证或学校真实请求；未作发布签名、安装、推送或把 dirty 开发对变成 release pin。
- 此批完成密码恢复和学校校园网账户／portal 纵切及更新平台能力；**完整 R5 仍未完成**：Run read/kernel、认证／profile／documents、课表 runtime/UI/platform 与 Dorm 独立协议的后续分层继续推进。
- **task-53 Lead 独立复核通过**：完整公共 Python **338/338**、私有 **84/84**，真实 architecture **253 文件 PASS**、display **214 文件／2749 tokens／60 Han／60 精确例外 PASS**、组合 facade **266 个生产文件 PASS**。例外总表此批零改，仍为 58 protocol＋2 diagnostic；没有新 raw JSON 或字符串白名单。CI 显式覆盖 27 个库 JVM／Native 任务、新八功能模块独立 Android Host、native updater Host 和 shared Host；六项 live-test opt-in／credential 环境在两个 job 为空且 Android 脚本先 unset。此复核不等于本机执行 Native。

## 课表 Native／runtime 与真实存储闭包（task-54～59）

- [schedule-native](<../platform/schedule-native/build.gradle.kts>) 接真实 common contract、internal policy、inert owner、原 live-state 算法及 Android 原生通知／提醒／widget／厂商 renderer，不把 platform policy 为迁移而公开。shared 三 actual 与 intent handler 留薄注入桥；Android 工厂仍按原构造时机创建 engine、刷新能力及排队 reconcile，**不是零 I/O 构造**。healthy native alarms 仍跨 UI disposal 存活。
- [schedule runtime](<../feature/schedule/runtime/build.gradle.kts>) 完整接三源及原 34 项测试。持久化／状态／测试字节原样；Runtime 唯一接线变化为必传 native 参数，逆补该默认值后恢复原 SHA `5f0ccb3df700db472b9599ac3501468dbb4224ee28e299a2274ec71371ff6c5a`。schema、三键、MAX_TERMS=3、MAX_ATTEMPTS=130、revocation marker、确认原子写与 legacy owner 不复活规则均未重写；这是持状态和存储的 **runtime**，不称纯 presentation。
- 显式扩大闭包而非反向依赖：查到 live teacher 持久化经 FQ 调 root `createPlatformServices().storage`，另查 shared 中九项实际 live-card 资源。新增 [core storage](<../core/storage/build.gradle.kts>) 持原 KV/Memory 合同字节 MOVE；[platform storage](<../platform/storage/build.gradle.kts>) 持真实 private EncryptedStore，仅增加应用 Context 工厂。LiveNotifications 只替换两处调用，逆回恢复原 SHA `21c384635e8da2855f21824cea23aa2a99ba0538c3a423ceff3715b50f40321b`。冷启动 receiver 无需根工厂、全局 callback 或 UI 安装过程。原 EncryptedStore 类体 SHA `02f194809b9a11b646df0f6a1ca01cf36c91cd593f1b188c70531d425e605fa4`，prefs／Keystore alias／AES-GCM／AAD／IV 前缀／NO_WRAP／commit 与错误文案均保持；root AndroidPlatform 反向插回块与 imports 后恢复原 SHA，WebView／portal／appearance 等无改动。
- 共二十个资源字节 MOVE（host 十一＋shared 九），原 applicationId／PendingIntent identity／SharedPrefs／hardcoded MainActivity 均保。构建前已捕两产品各七个课表组件、三个权限和十四个打包资源名的 [精确基线](<../.local/v2-validation/schedule-native-manifest-before.json>)，最终将审查合并结果结构／数量／exported／filter／metadata 全等，而不是只搜三四个类名。
- 第一轮真实构建 **1m21s 失败**于 native Android 的同包 `VendorLivePolicy` unresolved，非依赖缓存或测试断言失败：之前 import/FQ 词法审计漏掉 common 同包符号。已显式追加 task-59 迁此 internal policy 及两套原厂商合同测试十项，不 public 化／复制 policy／删断言；先前 `SourceReady` 仅是范围内源就绪，**不作为编译闭包已证明**。本轮已执行 native JVM 43、core storage JVM/Host 各 4、platform storage Android 生产编译；平台存储 JVM/Host NO-SOURCE，如实不冒充 Keystore 测试。该失败后已完成下述最终重试，不把失败日志当最终通过。
- **task-56 最终公共构建通过，2m34s、598 tasks（139 executed／459 UP-TO-DATE）**。真实 JUnit：native **53/53**（原 48＋新增 port 5）、runtime **34/34**、core storage **4/4**、network-session **50/50**、shared **756/753**，顺序 JVM/Host，failure/error/skip 均零。platform storage Android 生产编译通过，JVM/Host NO-SOURCE；没有创建测试替身声称 Android Keystore 已跑。
- [最终 XML 审查](<../.local/v2-validation/summarize_schedule_regression.py>) 不仅数总数，还与迁前保存的 [838／835 项原方法身份](<../.local/v2-validation/schedule-test-methods-before.json>) 逐项多重集合比较：原 **82** 项迁 native/runtime 后各恰一份，shared 无旧 suite 残留；其余 756／753 原 testcase classname＋name 原样保留。native 新 port 五项与 storage 新四项另计。[逐组结果及守恒 JSON](<../.local/v2-validation/schedule-regression-junit.json>) 已更新，live test 仍输出 `no live requests`。
- **私有 consumer 构建通过，1m40s、693 tasks（75 executed／618 UP-TO-DATE）**。verifyPublicBase、Pro debug/D8 与既有 332 个 JUnit 证据全绿；Water 92／route-tools 188／map-editor 37 本次 task 为 UP-TO-DATE，composition 15 本次实际执行，未称全部强制重跑。App/Android unit 任务仍 NO-SOURCE。
- 最终双 APK 审查通过：Standard **19 DEX／22295 defined／0 private**，SHA `7444e82d3c96272c2fdc8b77a86576a05b727ef911e2362dc88462d152c3254e`；Pro **20 DEX／23154 defined／552 private**，SHA `244bcd40244906f0257ccaa37afffed2c19040c347936d7032268ab068e4b1c9`。各七个课表组件（含 filter／metadata／theme／exported）、三权限与十四打包资源名和迁前基线**精确全等**，组件类全部在 DEX，updater provider 仍每产品唯一且 private，BLE 仅 Pro。[APK JSON](<../.local/v2-validation/apk-capability-audit.json>) 为最新两产品结果。
- verifyModuleCoordinates 输出 **33 个唯一库/umbrella/host 组件**（31 可复用 KMP 库＋shared＋android-host，Android 产品另计）。network 现在 api core storage，runtime **不依赖 network**；native Android 仅依低层 platform storage，不反依 shared。无 framework/export 变更、存储 schema 迁移或 new raw public 豁免。完整架构仍有 Run read/kernel、认证/profile/documents 和课表 UI 待拆。
- **task-57 Lead 独立最终复核通过**：公共 **346/346**（116.495s，含八项新增架构反例，架构测试合计 69）、私有 **84/84**（3.234s）；strict **262 文件 PASS**、display **220 文件／2763 tokens／60 Han／60 精确例外 PASS**、组合 facade **271 生产文件 PASS**。iOS **原 25 项 source/plist 检查**实际逐项通过：早前任务描述写 27 是描述误计，未增加测试凑数；本机没有 Native 编译／设备行为证据。词法器补正确排除 `::class` 类字面量，成对反例仍拒绝真正 public credential 类；显式工厂返回 KeyValueStore 时不误把 private EncryptedStore 实现表达式当公开类型。原精确文字例外表未写，六个 live 环境仍为空。

## Run 全只读闭包（task-60～65，双产品闭环）

- 课表批全部真实闭环后启动。拆分 **domain／data／presentation／UI**，不只列表界面：同一个 data decoder 接 task/detail/user-wide＋filtered records/record/track 六条真实请求路径；public 只提供固定 binding 的 typed source，JSON／wire／异常消息留内部。nullable＋recognized 区分未知结构与已知空结果，保 0 最小距离、raw 分页消耗计数、原任务三分支身份等价和完整 record 双哈希算法。后端任意错误字符串不再向 presentation/UI 透传，属于明确的安全语义变更，不冒充整字节 MOVE。
- 原 Run 写 Repository 只删七项读声明、保五项写与原 binding/private execute；定位／上传／session journal／write queue／runtime 生产代码全冻结。Pro Preview API 与读 lease 合同不变，product binder 每次读捕获新的固定源，不让旧页凭据静默重绑。implementation 前已保存 [16 个受保护文件或窄接缝 SHA 与 shared 756／753 方法身份基线](<../.local/v2-validation/run-read-before.json>)。
- 测试归属明确：原 Run 97 项为 data 42／presentation 54／shared 集成 1；原 Repository 14 项含三项 mixed，拆后为 shared 6＋data 11＝17（新增三定义是拆分，不称原 14 方法整字节守恒）。binding 的六项原 read 场景由 data internal transport 另测，root 改合法 punch 入口保写侧 401／epoch／late-success 原合同；typed publication 拒绝 stale 另立测试，不改原 transport Success 断言。新增独立 source 9、presentation 边界 6、UI 6、root binder 9、Preview finite-failure 1 分别列账，不用新增总数掩盖迁测缺失。
- **task-63 首轮真实构建 1m54s 失败**：先已执行 data **68/68**、presentation **60/60**、UI **6/6**，均 failure/error/skip=0；domain production 编译过而 JVM/Host tests 均 NO-SOURCE。shared 报四项跨模块公开属性 smart-cast 源码错误，分别位于 Selection.from 的两个 nullable id 与 recordPace 的 nullable distance/elapsed，已按原双 null 检查改捕获局部 val（未用 `!!`），并重新冻结。同时 Kotlin IC 出现 DirtyData／ScheduleRuntime classpath 移归断言，不能把真实 source error 全归为缓存。重试仅本次命令关闭 incremental，不改项目默认；第二条命令曾被 PowerShell 将未加引号的 dotted property 拆成 `.incremental=false` task 而在 29s 选择阶段失败，修正为带引号参数再跑。随后正确关闭 IC 的重试 2m18s 已通过全部生产编译，仅新 root binder test 的 `Pair<String, String>`／nullable Pair 泛型推断编译失败；只补显式 nullable Pair 类型，四对期望值和顺序不改。最终公共回归结果见下，Pro／守卫另行收集。
- [Lead 独立源不变量审查](<../.local/v2-validation/run-read-invariants.json>) 已通过：13 个保护生产文件整字节同，TaskLinks 逆两 presence＋local smart-cast 还原原 SHA，Tracker 原两 query 行之外逐字不变，Repository 只删七 read＋三 unused imports，internal transport 七 read 仅 visibility 变化、private execute 逐字同；Profile 逆 typed reads/totals/imports 和两 internal test seams 恢复完整原 SHA。三个单位协议例外仅文件/position 移归，原 source/context/category/reason/owner 全等，余 57 条零变，总数仍 60。
- **最终公共构建通过，1m54s、662 tasks（90 executed／1 from cache／571 UP-TO-DATE）**。Run data **68/68**、presentation **60/60**、UI **6/6**、shared **662/659**，均 JVM/Host 顺序，failure/error/skip=0；domain 两 tests NO-SOURCE，JVM/Android 生产编译通过。network-session 原 50/50 同批验证，Standard debug/D8 成功，live fixture 仍 `no live requests`。
- [逐方法身份与拆分账审查](<../.local/v2-validation/run-read-regression-junit.json>) 对迁前 **756/753 项**逐一映射且恰一 owner：除三个明确 mixed split 为两 halves 外，其余每项保持原名或登记的单个 safe-projection 更名；原 Run 97 全有归属，shared 无移出旧 suite 残留。最终 mapped definitions **759/756**，另 **37** 项明确新增（data 6 binding＋9 source、pres 6、UI 6、root binder 9、Preview 1）。这不是声称 756/753 全逐字不变，实际 raw API 删除、typed fixture 和 finite-error 预期变更已逐项记录。
- **最终 Pro consumer 构建通过，2m18s、755 tasks（86 executed／669 UP-TO-DATE）**。Water 92、route-tools 188、map-editor 37 本次 UP-TO-DATE，composition 15 本次执行，332 项真实 XML 均零失败/错误/跳过；Pro debug/D8 与 verifyPublicBase 成功，Pro API 两文件整 SHA 未变，App tests NO-SOURCE。本次公共与私有为排除移归 IC 缓存异常均使用一次性带引号 `-Pkotlin.incremental=false`，不更改项目默认或 CI 行为。
- 最新双 APK 审计全部通过：Standard **19 DEX／22383 defined／0 private**，SHA `b0dcc897499ab25fb9bcf9943bc1c22d66a8bc6e71581dea09032d4c2175b5c3`；Pro **20 DEX／23242 defined／552 private**，SHA `505f89001c59bc933764b81d38c7be921f5a05a599b0fc30353033500cf124d9`。原 GPS 权限、七课表组件/三权限/十四打包资源精确全等，updater provider 唯一 private、BLE 只在 Pro；没有安装、设备调用或真实上传。
- **task-64 Lead 独立最终复核通过**：公共 Python **355/355**（206.197s，架构 77 项）、私有 **84/84**（3.626s）；strict **280 文件 PASS**、display **234 文件／2780 tokens／60 Han／60 精确例外 PASS**、组合 facade **285 生产文件 PASS**、原 iOS source/plist **25 项 PASS**。35 可复用库＋shared＋host＝**37 唯一组件**，四层 Run 的 JVM/Native CI 与独立 Host 已接，Native 本机未跑。新增词法反例覆盖 bodyless typed 声明与字段同名误判：真正 shared extension call／`::` 仍拒绝，未靠放宽白名单过绿。
- 当前只读闭包已闭环，**Run 写入 runtime／定位上传内核尚未抽离**；认证/profile/documents 和其余课表 UI 仍在整体 V2 后续范围。本批完成不代表整体架构全部完成。

## 课表 Binding 最小批（task-66～69，双产品闭环）

- 仅拆 Binding prompt 与表单完整 Content，账号／密码与显示开关留 root 两个受控 slot；原 draft/mask 及 Editor 测试保持原位整字节。只物理迁移 Panel/Progress，同包同函数名公开给原课表调用，其他课表页面不动。
- 原 Runtime `ownerRevision` 可能从同一已存记录恢复为旧值，且全局 `cancelVerification()` 没有页归属。此次不是简单 layout MOVE：新增 opaque passive lease、owned verification attempt、固定 post-commit receipt，明确防旧页取消新尝试及 owner ABA；原 Boolean API 兼容但共用唯一认证／保存实现，不复制业务算法。本人 confirm 保存后、等待同步之前固定 receipt，返程一次性消费，不从最新状态补租约绕过外部重绑。
- 源码修改前已保存 [17 项文件基线及真实方法身份](<../.local/v2-validation/schedule-binding-before.json>)、三份原生产源码快照：shared **662/659**、Runtime **34/34**。新方法另列账；旧 Runtime 34 项与 root Editor **14 项**冻结（早前任务文案写 11 是定义数误计，文件整 SHA 未变），不搬移、不删断言。Persistence／State／Native／其余课表页面、Run 写内核和 60 项文字例外全部整 SHA 冻结。
- task-66 只 runtime 窄操作与新测试，task-67 只 UI/root binder/新测试，task-68 只守卫/CI，Lead task-69 独占 Gradle/settings/私有坐标和串行构建。Lead 已接 UI Gradle、公共 include/shared implementation、私有显式 alias，**36 可复用库／38 唯一组件**。本批首轮构建 **2m08s 失败**：UI **6/6**、runtime **40/40** 与所有 production JVM/Android/38 坐标已过；唯一错误为新 root 测试 Store fixture 漏实现 `KeyValueStore.clear()`。仅补委托原 MemoryKeyValueStore 的一行，预期和生产源码不改。
- **公共最终构建通过，1m58s、674 tasks（73 executed／1 from cache／600 UP-TO-DATE）**。UI **6/6**、runtime **40/40**（原34＋新6）、shared **672/669**（原662/659＋新root10），均零 failure/error/skip；UI/runtime 测试本次重试 UP-TO-DATE 保留首轮实际执行结果，shared 两 suite 本次实际执行。Standard debug/D8 成功，原 live read fixture 明确无真实请求；公共终态成功后才启动 Pro 串行构建。
- [逐方法保留账](<../.local/v2-validation/schedule-binding-regression-junit.json>) 已实核原 Runtime＋shared **696/693** 项全保原 classname/methodname/模块 owner，恰好一次、无改名或 split。本批 **22 新定义**另列（runtime6／UI6／root10）；fake 两 slot 的 Content fixture 只是编译，不冒充像素渲染或点击测试。
- [Lead 源不变量逆审](<../.local/v2-validation/schedule-binding-invariants.json>) 实际 PASS：14 个受保护文件整 SHA 原样（包括原 Runtime 34 测试、Editor 14 测试、UiModel、Persistence、State、60 条文字例外），Panel/Progress 原两 block 仅 visibility 变公有，原 Components 只删该 block；认证全函数逆 owned gates/phase 后恢复原文，确认全函数逆 phase/固定 receipt 后恢复原 save/source/native/refresh 序列。其他 refresh/sync/recovery/native/storage/close 算法整块逐字原样。
- **Pro consumer 实际构建通过，2m03s、778 tasks（79 executed／699 UP-TO-DATE）**。Water 92、route-tools 188、map-editor 37 本次 UP-TO-DATE，composition **15** 本次执行；四组 332 项 XML 零失败/错误/跳过，App tests NO-SOURCE；verifyPublicBase、Pro debug/D8 成功。未改公共基线 pin，仍是显式 dirty development 配对、不宣称 release-ready。
- 双 APK 最新审计全部 PASS：Standard **19 DEX／22426 defined／0 private**，SHA `1a9e88896f6dde8a59737a4ff86d732277a745b1c3e365f7162dd102d8043536`；Pro **20 DEX／23285 defined／552 private**，SHA `74d38b45b8b3f9e40fab597049a881a3a7a3ff2709ffc1e5b8c07a86781b995a`。GPS／七课表组件／三权限／十四打包资源与原基线精确同，updater 唯一 private provider、BLE 只 Pro；未安装或运行真实业务。
- task-68 首次完整公共 360 中 359 通过，RepositoryGuard 发现四项守卫词法误判：三个 Compose libs 前缀拼写白表漏项、UI 本包 finite-enum `.text()` 被 wildcard 误当 shared JSON 扩展。只在守卫精确限定本包零参调用与外部有参调用的保守 arity 解析并保真 shared extension／`::` 反例，不改生产源迁就规则。
- **task-68 Lead 独立最终复验通过**：公共 Python **360/360**、私有 **84/84**（3.718s），strict **287 文件 PASS**、display **240 文件／2791 tokens／60 Han／60 精确例外 PASS**、组合 facade **291 生产文件 PASS**，iOS 原 **25 项 source/plist PASS**。36 库／38 公共组件的 JVM/Native/本批 UI Host 接线明确；Native 编译、UI 视觉与设备行为本机未验。
- 本批整体闭环，原 60 项文字例外文件整 SHA `5923bf2fa0708c57146887e687781ece9fb8391882615532ae37efd6be0aa0c1` 未改，生产只按 task-66/67 授权窄范围变化。其余课表 UI 与 Run 写内核、auth/profile/documents 仍在后续范围，未称 R5 完全完成。

## 校园登录批（原 task-70~72＋补建守卫/Lead 接线任务，2026-11-19 双产品闭环）

- **恢复与任务板**：跨 session 任务板丢失；从上一 session 落盘记录（session-effd24a3 的 zstd JSONL，12736 行事件）逐字恢复三张原始任务卡（含 rev2"暂停待恢复"全文），据此修正重建版两处偏差——AuthRepositoryTest.kt/RepositoryContractTest.kt 按原卡归 core+data owner（原卡明文『原AuthRepositoryTests由70owner负责别编辑』），AuthRepository 是『只删 login 声明』而非留委托接缝；并补建当时缺失的 auth 守卫（task-4）与 Lead 接线/构建/审计（task-5）任务。恢复前基线核对零漂移（双 HEAD、dirty 计数、auth-before 38 文件/6 快照逐 SHA、display 例外 SHA、双 APK 制品哈希、schedule-binding/run-read 逆审脚本复跑全过）。
- **实施**：core/network-session 六 typed LOGIN ops（captureLoginAttempt 被动／claimLoginAttempt 仅显式 SUBMIT／isLoginAttemptCurrent／suspend executeLogin／cancelLoginAttempt 归属零副作用／consumeLoginContinuation 恰一次），ApiLoginAttempt opaque（internal/redacted/无 getter/非 data），ApiLoginOutcome sealed（Accepted 无 payload／Rejected(issue)／Unknown(issue)，九值精枚举）；原 LOGIN 解码（POST query/匿名/8s）单 owner MOVE 入 core internal decoder，legacy execute 只加 private admission gate；**旧 login 成功无条件 sessions.save 覆盖途中新会话的缺陷改 core snapshot compare-and-save（CAS）修复，未知写不自动重试**。feature/auth domain/data/presentation/ui 四模块源码落地（domain 零依赖纯规则、data 诚实 finite adapter、presentation 无凭据 typed 门控 Controller、ui 完整 chooser+campus Content 九事件）；shared 侧 CampusLoginBinding root binder＋LoginScreen 改造（双 credential slot 留 root、stage rememberSaveable 保留、Dorm/学籍生命周期不按 campus epoch 重建）。Lead 接线：四 build.gradle.kts（模板 run/data＋schedule/ui）＋公共 settings include＋shared api(domain)/implementation(data/pres/ui)＋私有四显式 alias（auth-domain/data/presentation/ui）。
- **构建修复环（Lead 串行独占，每轮真实执行、错误面只减不增）**：R1（1m10s）ApiClient executeLogin 调用漏必填 body 参数（owner 补 `body=null`＝原公共 execute 默认值）＋presentation 测试 fake `var isCurrent` 与 override 撞 JVM 签名（owner 改名 currentResult）；R2（1m17s）ui 模块 6 处 unresolved——5 处是 **Lead Gradle 模板缺口**（原 LoginScreen 的 miuix extended 图标集需要 libs.miuix.icons，schedule/ui 模板未带，Lead 补依赖），1 处 owner 漏 testTag import；R3（1m09s）BackChevron 误 import 定性：它是 design-system 自有符号（原文件靠同包解析、从无 import），owner 删错 import＋补正确 design-system import 两行；R4（58s）边界测试把 enum 编译器生成的 `valueOf(String)` 误当违规（owner 精确豁免 `isEnum && name=="valueOf"`，其余保持严格）；R5（2m14s）CampusLoginBinding 漏 `kotlinx.coroutines.isActive` 扩展属性 import（owner 一行补）。
- **最终真实执行（XML 逐一核对，零 failure/error/skip）**：公共最终构建 **3m35s PASS**——core:network-session **60/60×2**（原 50 全保＋新 ApiLoginTest 10：2 项迁移保原名＋8 项新增防竞/ABA/分类参数化）；feature/auth:data **6/6×2**；presentation **23/23＋17/17**（jvmTest 含 jvm-only 反射/源码扫描边界 6，leg 不对称如实列账）；ui **10/10＋7/7**（隐私 3 仅 jvmTest）；shared **680/677**；domain jvmTest **NO-SOURCE** 不造数；Standard debug/D8 成功。**Pro consumer 2m19s PASS**：Water 92／route-tools 188／map-editor 37 本次 **UP-TO-DATE**（输入未变、依据现存 XML 零失败），composition **15 本次实际执行**，verifyPublicBase、Pro debug/D8 成功；live fixture 输出 `LIVE_READ_ONLY_SKIPPED: no live requests`，构建前六 live 环境变量已清空。
- [逐方法账](<../.local/v2-validation/auth-regression-junit.json>)：基线 672/669 每项恰一 owner——kept 670/667＋迁出恰 2（`loginUsesPostQueryAndPersistsOnlyTheReturnedSession`／`malformedLoginDoesNotCreateASession` methodname 原样、现仅存 core ApiLoginTest、shared 零残留）；新增另列不混账（core 8×2、shared 10×2、data 6×2、pres 17/6、ui 7/3、Pro 332 附执行状态）。
- [源不变量逆审](<../.local/v2-validation/auth-invariants.json>) PASS：32 protected 整字节（含 60 条文字例外 `5923bf…` 零写）；AuthRepository 逆删改恢复快照原 SHA；ApiClient **纯增量 119 行＋单行 perform 委托 seam**（原行序不变）；SessionStore **纯增量 86 行、零原行改动**；RepositoryContractTest 单行 LOGIN 表替换＋typed bridge 新增；AuthRepositoryTest 删除恰为两迁移方法；LoginScreen 的 Dorm/diagnosis/Primitives 段（快照 290-671）逐字节保留、BackToChooser 仅 private→public 机械 MOVE（函数体逐字节）、生产 binder 零 `auth.login` 残留；LiveReadOnlyTest pre `cdc4bca0…`/post `fae24e52…` SHA＋live 门/finally 片段部分 byte 校验如实披露。
- **双 APK 审计 PASS**：Standard **19 DEX／22487 defined／0 private**，SHA `c58b0afcf9cae8eb5af066b436e0e8b7b1d96e9002c3e9ff63819b649e16bf2b`；Pro **20 DEX／23346 defined／552 private**，SHA `227efb1268e3b9bdaa5153d8cee61d46077da4347fd261c7ed4229492ef32617`。七课表组件/三权限/十四打包资源与基线精确全等、updater 唯一 private provider、BLE 仅 Pro；未安装/未签名/无真实请求。
- **坐标**：40 可复用 KMP 库（36＋auth 4，全部 parent-qualified `io.github.betterzslf.feature.auth:*`）＋shared＋host＝**42 唯一组件**；V2 原始 allprojects 计 55＝前 51＋4，零坐标碰撞。
- **非字节 API/安全语义变化逐条（如实，不虚称原字节）**：①AuthRepository.login 删除，生产登录改走 typed core/data/pres，不留 raw 兼容壳；②旧 login 成功 message（"ok"）透传被 finite outcome 替换，`ApiResult.Success(session,message)` 形态不复存在（迁移测试该断言显式改记、断言总数 13→14 只增不减）；③失败路径 ApiError（Decode/Business(code,服务端文本)）收为 issue 精枚举，**服务端文本不再跨 repository 边界**，UI 文案仅 AppText 白名单有限映射；④上述 CAS 修复本身（不再无条件覆盖途中会话）＋未知写不自动重试；⑤BackToChooser private→public（FQCN 不变）；⑥LoginScreen：LoginUnavailable 无引用死代码删除、受控 onVisible(Boolean) 参数改 root slot 内 reveal、message/loading 两个 root state 由 controller 安全 DTO 替代（banner 经 storageNotice 白名单折叠）；⑦cancelLoginAttempt 取 Boolean 返回（原卡未标返回型，owner 决策并证明不破坏调用方，登记披露）；⑧LiveReadOnlyTest 登录段换 typed bridge（test-only；live 输出 code 由 ApiError 码串改 issue 枚举直印）；⑨presentation 边界测试从 commonTest 移 jvmTest（kmp-library 含 iOS target，CI 对每库跑 iosSimulatorArm64Test 会编 commonTest——JVM-only 反射/源扫描不得留 common）。
- **发现的预先存在问题（范围外、如实记录、本批未修）**：shared 的 DormLogoutStoreTest（campusnet 批产物）使用 `::class.java`，而 `:shared:iosSimulatorArm64Test` 在 CI macos job 任务列表内——CI 全程仅静态证据从未执行，属潜在 Native 编译断点；修复需用户另行授权（该文件在 protected 冻结范围）。
- **auth-73 守卫/CI 交付（task-4，两段闭环）**：公共 7 文件——architecture_guard 四坐标入册＋auth 四模块专属规则（domain/data API 导出精确 pin、Submission opaque pin、credential 形状检查、ui capability 双检查＋库白名单）；ApiClient.execute 改表达式体后旧 pin 失配（改前基线实跑 FAIL 4 条）按更严格形态重 pin（委托表达式整段钉死）；词法器既有盲区如实披露并保守修复（多行表达式体吞并下一声明，补 declaration 断点、不加豁免）；display guard 扩根后 256 文件／2797 tokens／60 Han／60 例外零新豁免；tests.yml iOS 列表＋4、ci-android.sh jvmTest＋4/Host＋4（domain Host 按 run/campusnet/accountrecovery 先例照列 NO-SOURCE，首次漏列经 Lead 裁定后补齐）、合同测试四家族同构断言。反例成对实跑：public credential 类/真 raw JSON 扩展/public Session 出口/ui 越权 import/Gradle 反向边/BackToChooser 回填与缺失——全部真拒绝，正例全绿。私有 facade 覆盖缺口由 Lead 在私有树自补（见下）。
- **Lead 独立最终复验（全部真实执行）**：公共 Python 全 suite **367/367 OK**（251.2s，含本批 7 新测试）；architecture **307 文件 PASS**、display **256 文件／2797 tokens／60 Han／60 精确例外 PASS**；私有 suite **84/84 OK**（4.855s，含 iOS 原 25 项 source/plist 检查）；私有 facade guard 扩四坐标后 **307 文件 PASS**（295＋auth 12）。如实披露一个过程性失败：Lead 扩 kotlin_facade_guard 的 PUBLIC_MODULES 时未同步其测试的独立冻结清单，私有 suite 一度 7 fail＋1 error——该独立复核门按设计拒绝了未过审的范围扩张，按 run 批先例同步测试清单后恢复 84/84。
- **最终标准回归（scripts/ci-android.sh 列表，Lead 串行 offline）**：**BUILD SUCCESSFUL 58s**，覆盖 verifyModuleCoordinates＋全部 jvmTest/Host/assembleDebug＋android-host/androidApp unit（NO-SOURCE）——本批各模块测试在当日 R5/R6 已真实执行并留 XML（core 60/60×2、data 6/6×2、pres 23/17、ui 10/7、shared 680/677），此轮全 UP-TO-DATE 保留当日真实结果；`:feature:auth:domain:testAndroidHostTest` NO-SOURCE 如实在列。
- 【如实记录的离线缺口（预先存在、非本批引入）】：lint 链（android-host/androidApp lintDebug）在本地 --offline 下于 `:design-system:checkAndroidMainAarMetadata` 停——compose 传递依赖 `androidx.activity:activity-ktx:1.7.0`／`androidx.core:core-ktx:1.16.0` 本地缓存从未有过（历代解析均被产品图对齐约束升级掉）。design-system 本批零改动、Round 6 的 assembleDebug 图内该 CHECK 任务从未运行；两条 unit 任务 offline 直过。按离线边界本批不引入网络下载，lint 链留给 CI 在线环境或用户授权后另行处理。
- **批次结论**：校园登录批双产品闭环完成——task-1~5 全 complete；六 live 环境每次构建前清空且 live fixture 输出 `no live requests`；公共最终 3m35s／Pro 2m19s 全绿；双 APK 审计 PASS（Standard `c58b0afc…`／Pro `227efb12…`）；逐方法账、源不变量逆审、守卫复验全部落盘。未做：macOS K/N／Xcode、模拟器/设备、签名发布、视觉验收；`releaseReady=false` 未改。**其余范围（Run 写内核/定位上传迁出、其余课表 UI、profile、documents、Native/设备真实验收）仍在 V2 后续批次**，未称 R5 完全完成。

## 范围变更说明（2026-11-19，用户指令）

- 用户指示：**真机/模拟器验收暂缓；iOS 相关工作停止**。原因：目前没有条件做 iOS 的编译与认证验证（本机无 macOS/Xcode 环境，无 Apple 开发者认证条件）。
- 自即日起，本 session 及后续批次的本地执行范围调整为：
  1. 不再尝试 macOS Kotlin/Native、Xcode 工程编译、iOS 模拟器/真机烟测、Apple 签名/发布等一切 Apple 侧验证工作；
  2. Android 真机/模拟器安装与设备行为验收同样暂缓（本地保留双 APK 的 DEX/manifest/资源审计作为最终制品证据）；
  3. CI 配置文件（含 tests.yml 的 macOS job 与 ci-ios.sh）作为**静态证据原样保留**，不做删改——供将来具备条件时直接启用；
  4. 私有 Python suite 内既有的 iOS 源码/plist 静态一致性检查属于零成本、无编译的词法核对，随套件照常运行，不在此停止范围。
- 进度报告自此不再把"iOS 编译/真机验收"列为待办，改列为**范围外（无条件执行）**；双 APK 审计与 JVM/Host 测试继续作为本机验证终点。

## Run 写内核/定位上传迁出批（run-write-0~5，task-6~11，2026-11-19 双产品闭环）

- **分析先行**：run-analyst 只读分析（[run-write-analysis.json](<../.local/v2-validation/run-write-analysis.json>)，全带行号、fact/inference 分标）确认冻结面、调用面（写内核引用全部收敛在 shared 内，Pro 侧零命中）、134 项测试账与五风险；Lead 亲核两处存疑（Geometry/RealTrack 实测 core:run-model 的 feature.run 包符号——分析员初判正确；haversine 无 shared 定义亦证实）。AGENTS.md 规定的 deepseek-v4.1-flash 在注册表不可用（仅 opencode-go/glm-5.3），改用队友形式并记录偏差。
- **方案锁定**：新建 feature/run:runtime（九件生产 byte-MOVE 保包名：七内核＋RunRuntime＋RunTaskLinks）；RunRepository.kt 整文件 MOVE 入 run:data（选项 A——选项 B 有冻结 import 死结且违反不复制原则）；新建 platform:location（LocationTracker.android/ios＋LocationPermission.ios＋PlatformCapabilities 整件、FilePicker.android 实况为纯 permission actual 整件迁为 LocationPermission.android）；测试 120 项随 runtime、7 项随 run-model、其余留位；shared 生产零编辑。
- **基线**：[run-write-before.json](<../.local/v2-validation/run-write-before.json>) 23 快照＋10 protected＋方法身份表；捕获过程如实记录 Lead 两处预期错误（run-model Host 腿、run:domain NO-SOURCE 归属）并在重跑前清理半成品。
- **实施**：run-runtime/run-flanks 两队友交付；Lead 独立复验 15＋1 件 byte-MOVE 双重 SHA、留位件原字节、BackToChooser 同款模式。Owner 交付的超出字面卡面的必要判断均披露（UserRequestLauncher 随迁为 expect 返回类型；FilePicker.android 名不副实为整件 permission actual）。
- **Lead seam 三件（接线前提，全部披露可逆）**：①PlatformCapabilities.jvm.kt＋IosCapabilityRules.kt byte-MOVE（pre-SHA 登记 a5fff692…/1b478da0…）；②LocationAndroidContext 新 holder（object/initialize public、context internal——AndroidPlatform 同款形态）＋LocationTracker.android 两行符号替换＋AndroidPlatform.initialize 一行注入——R3 实测 internal 模块级不豁免同包后修正；③两个空声明壳（原 PlatformCapabilities/FilePicker 残件）删除，原件有快照可恢复。
- **构建修复环（每轮真实执行，错误面只减不增）**：R1（2m06s）run:runtime 测试编译缺 kotlinx-coroutines-test（Lead 补 commonTest 依赖）；R2（1m29s）platform:location 的 commonMain compose.ui 拖出 jvm 腿 ui-desktop→navigationevent/skiko 未缓存链——compose.ui 下沉 android/ios 源集（jvm 腿不再拖链，Lead）；R3（2m21s）LocationAndroidContext internal 模块级不可达——object/initialize 转 public（Lead，教训同 D8 批：同包跨模块不豁免 internal）；R4（1m25s）**方案盲点**：留位 RunTransportContractTest 实摸 RunBatcher internal 符号（json/validateBatch）——Lead 认账 reopen task-7，run-runtime 按已验证模式（CampusLoginApiFixture 先例公共 API 自建 RunTransportFixture）迁移＋9 用例断言逐条守恒；R5（2m21s）**第二层同类**：RunRuntimeTest 摸 RunTaskLinks（internal）——其 import 全公共符号，Lead 直接 byte-MOVE（5520f692…双证）；另 Lead 补迁 Geometry/RealTrack 两件（建卡时漏派的计划内 MOVE）。**R6（2m37s）＋确认轮（1m57s）全绿**。
- **最终真实执行（XML 逐腿核对零失败）**：feature/run:runtime **120/120×2**；core:run-model **26**（原 19＋迁入 7，Host 腿 NO-SOURCE 如实）；feature/run:data **68/68×2**（不变）；shared **553/550**（680/677＝留位＋120 随迁＋7 随迁，逐项恰一 owner）；platform:location 测试 NO-SOURCE；Standard debug/D8 成功。**Pro consumer 2m26s PASS**：Water 92/route-tools 188/map-editor 37 本次 UP-TO-DATE（输入未变），composition **15 本次执行**，verifyPublicBase、Pro debug/D8 成功；两新私有 alias（run-runtime/platform-location）生效。
- [逐方法账](<../.local/v2-validation/run-write-regression-junit.json>) ACCOUNT-OK：两腿集合精确相等断言；**新增 @Test 为零**（两处 fixture seam 均保持 methodname 与断言，只换 helper）——本批是纯迁移账，不产生新方法定义。
- [源不变量逆审](<../.local/v2-validation/run-write-invariants.json>) INVARIANTS-PASS：24 件 byte-MOVE 新路径 SHA＝基线/pre-SHA、旧路径清空；RunTaskLinksTest/RunTransportContractTest seam 核验（methodnames、无反向 import）；LocationTracker.android 两行 seam 与 AndroidPlatform 一行 seam **逆编辑恢复基线精确 SHA**；8 件留位 protected 原字节；display 例外 `5923bf…` 零写。
- **双 APK 审计 PASS**：Standard **19 DEX／22489 defined／0 private**，SHA `501ec7563d053e874f5e8fc111130f471934595e333dc41eaa301ac79689631e`；Pro **20 DEX／23348 defined／552 private**，SHA `d60303ea341db11795df4b388d9d52c6cc651ff3dccfccef1619523d65dc5177`。课表组件/权限/资源与基线精确全等；**RunLocationService 经 shared→platform:location 依赖链进包、GPS 前台权限保持**（R4 manifest 风险点双验证通过）；updater 唯一 private provider、BLE 仅 Pro。
- **坐标**：40＋2＝42 可复用 KMP 库＋shared＋host＝44 唯一组件；V2 原始 allprojects 计 57＝前 55＋2，零碰撞。
- **非字节变化逐条（本批全文）**：①LocationTracker.android 两行（AndroidPlatform.context→LocationAndroidContext.context）；②AndroidPlatform.initialize 一行注入；③LocationAndroidContext 新 holder 文件；④RunTaskLinksTest 3 用例本地 fixture seam（11 @Test 不变）；⑤RunTransportContractTest 迁移＋RunTransportFixture seam（9 @Test 与写侧 401/epoch/late-success 断言逐条不减）；⑥两空声明壳删除；⑦其余 24 件纯 byte-MOVE（含 RunRepository/PlatformCapabilities 整件）。
- **run-guard 守卫/CI 交付（task-10）＋Lead 独立复验（全部真实执行）**：两坐标入册照实际依赖图；RunRepository 迁入触发的 12 条基线违例**全部以精确 pin 收编不放松**（五写端点 token 级头 pin、成员合同 8 项全头比对、私有构造器门槛）；三 location 平台件走 LOCATION_PLATFORM_MOVES 机制（SCHEDULE_ANDROID_FILES 先例）；MOVED_FILES 落册十件。CI：tests.yml iOS＋2、ci-android.sh jvmTest/Host＋4（Host NO-SOURCE 照先例）、合同测试同步。私有 facade 双文件（guard＋独立冻结测试清单）**同时同步**——auth 批 7-fail 教训未复发。新增成对反例四组（kernel 归属/读层写禁/location seam 不变式/仓储 pin），全部真拒绝验证。Lead 复跑：公共 suite **370/370 OK**（432.5s）、私有 **84/84**（5.7s）、architecture 真树 **310 文件 PASS**、display **257 文件/2797 tokens/60 Han/60 例外**、私有 facade **308 文件 PASS**；display 例外 SHA `5923bf…` 批前批后逐位一致零写。
- **最终标准回归（更新版 ci-android.sh 全列表，offline）**：**BUILD SUCCESSFUL 2m30s**——:feature:run:runtime 两腿 UP-TO-DATE 保留当日真实执行（120/120），:platform:location 两腿 NO-SOURCE 如实在列，其余模块全绿或保留当日 XML；lint 链照旧 offline 阻断（预先存在缺口，范围外如实记录）。
- **批次结论**：Run 写内核/定位上传迁出批双产品闭环完成——task-6~11 全 complete；六 live 环境每轮清空（本批全程未启用）；公共 R6＋确认轮、Pro 2m26s 全绿；双 APK 审计 PASS（Standard `501ec756…`／Pro `d60303ea…`）；逐方法账（零新增定义）、源不变量逆审（24 件 byte-MOVE＋3 seam 可逆）、守卫复验全部落盘。44 唯一组件（42 库＋shared＋host）。**剩余范围：其余课表 UI、profile、documents**；iOS/真机按范围变更说明停止。

## 课表剩余 UI 批（schedule-ui-0~4，task-12~16，2026-11-19 双产品闭环）

- **分析先行（Team 满员 8/8 → 复用制＋一次性 subagent）**：分析走 subagent（模型注册表无 AGENTS.md 规定的 deepseek-v4.1-flash，沿用 glm-5.3 偏差记录），task-12 由 Lead 代领；产出 [schedule-ui-remaining-analysis.json](<../.local/v2-validation/schedule-ui-remaining-analysis.json>)——九件清点、三先例（RunPages/HomePage/ScheduleBinding 的 Route 壳件留 shared）、测试账 93+11、五风险、Pro 零引用证实。
- **Lead 五问裁定**：①Draft 随 UiModel 迁＋public 化、Session 零编辑（同包跨模块解析）；②OpenTarget＋DetailSelection（原在 UiModel.kt:69，随段拆分）→ schedule:runtime（引用方向决定）；③Route 壳件（SchedulePage 整件＋Management/Reminder 段）留 shared；④ConfirmDialog 下沉 design-system；⑤**EditorTest/VisibilityTest 整件留 shared——破掉分析员担心的两个 mixed 点（零 split、零 settings 依赖）**；另判定"四处 import 改向"因保包名同包解析全部归零（实测成立）。
- **基线**：[schedule-ui-before.json](<../.local/v2-validation/schedule-ui-before.json>) 18 快照＋10 protected＋方法身份（shared 553/550、runtime 40/40、ui 6/6）；捕获中 Lead 一处测试文件名笔误（WeekBoardTest→实际 ScheduleBoardTest）如实清理重跑。
- **实施（复用制：run-runtime→task-13 大件、run-flanks→task-14 flanks，并行零冲突）**：task-13——四件整迁＋UiModel 拆分（DetailSelection 段→runtime、主体→ui）＋两件段切分（SecondaryPages 留 Management 段、NativePages 留 Reminder＋vendor 段，迁出段落 ui 模块新文件）＋**30 处 internal→public 逐符号带引用面行号账**＋Board(16)/UiModel(29) 测试整迁；留驻三件（SchedulePage/Binding/BindingSession）零编辑证实。task-14——OpenTarget＋15 项测试迁 runtime＋ConfirmDialog 下沉（拼回密码学验证：残件＋新文件段==原文件逐字节）；如实披露卡面预设"internal→public"实况为零（原函数本就 public）。
- **构建修复环（本 session 最短：两轮）**：R1（2m18s）唯一错误＝OpenTarget 迁移件的 class/fun 仍是 internal（task-14 卡面漏可见性 pass，Lead 认账 reopen rev4 两行修复恰 −18 字节）；R2（2m49s）**BUILD SUCCESSFUL**。公共全绿后 Pro 串行 **2m36s PASS**（composition 15 本次执行、Water/route/map 317 项 UP-TO-DATE 如实）。
- **最终真实执行（XML 逐腿核对零失败）**：feature/schedule/ui **51/51×2**（原 6＋Board16＋UiModel29）；schedule:runtime **55/55×2**（原 40＋OpenTarget15）；shared **493/490**（553/550＝留 493/490＋45 随 ui＋15 随 runtime）；design-system 8 不变；Standard debug/D8 成功。
- [逐方法账](<../.local/v2-validation/schedule-ui-regression-junit.json>) ACCOUNT-OK：两腿集合精确相等、**新增 @Test 为零**（纯迁移账）。
- [源不变量逆审](<../.local/v2-validation/schedule-ui-invariants.json>) INVARIANTS-PASS：3 测试件纯迁移双证；5 生产件**可见性归一验证**（strip internal/public 前缀后与基线全等，17 符号成对）；UiModel 拆分两块拼回原文件（归一后）；两段切分的留驻件＝原文件精确行段拼接、迁出段在新文件中归一保留；ConfirmDialog 拼回逐字节成立；12 留驻/protected 件原字节；display 例外 `5923bf…` 零写。
- **双 APK 审计 PASS**：Standard **19 DEX／22495 defined／0 private**，SHA `f81d2d21d9a5153cde35…`（完整值见证据 JSON）；Pro **20 DEX／23354 defined／552 private**，SHA `4e10afcb…`。课表组件/权限/资源与基线精确全等、updater 唯一 private、BLE 仅 Pro。
- **接线**：零新模块、零 settings/shared/alias 变化——唯一 Gradle 编辑＝feature/schedule/ui 增 implementation(schedule:api/runtime＋kotlinx-datetime)；ScheduleWidgetKind 经 runtime 的 api(platform:schedule-native) 传递可见无需直接声明；组件计数维持 44（本批零增量）。
- **非字节变化逐条（本批全文）**：①32 处 internal→public（task-13 的 30 符号＋OpenTarget class/fun 两处，全部带 shared 引用面行号）；②SecondaryPages/NativePages 两段切分；③UiModel 拆分为 ui 主体＋runtime DetailSelection；④ConfirmDialog 自 FeaturePages 下沉 design-system；⑤三处死 import（拆分机械后果，warning 级，留驻件按逐字纪律未裁剪）；⑥迁移件其余字节全等。
- **run-guard 守卫交付（task-15）＋Lead 独立复验（全部真实执行）**：MOVED_FILES 三键落册（schedule:ui 七件＋runtime 两件＋design-system 一件）；32 处 internal→public 照 run 批先例 **token 级精确 pin**（SCHEDULE_PAGE_HEADERS 30 符号＋Draft 六成员脱敏全头 pin＋runtime/DS pin）；能力禁令分层——binding 四件全禁保持、页面仅放行四枚已审渲染输入（State/Snapshot/Runtime/Draft），权威 token（Lease/Attempt/Commit/BindingSession/AcademicSource/Session/NavKey/Route）仍全拒；schedule:ui 依赖图按实际接线（api/runtime 经传递闭包核实）。**一处设计取舍如实披露**：ScheduleBindingDraft 凭据形字段按"精确冻结"处理（构造器六参＋六成员＋脱敏 toString 全 pin，漂移即 fail），binding 四件对其引用禁令原样保留。新增 4 组成对反例（97/97 含真树绑定门）。display 真树 257→261 文件 PASS、Han/例外 60/60 零新增。Lead 复跑：公共 suite **374/374 OK**（542.0s）、私有 **84/84**、architecture 真树 **314 文件**、display **261 文件**、私有 facade **312 文件 PASS**；display 例外 SHA `5923bf…` 逐位一致零写。
- **最终标准回归（offline）**：**BUILD SUCCESSFUL 3m01s**——schedule:ui 51/51×2、schedule:runtime 55/55×2 本次 UP-TO-DATE 保留当日真实执行，其余全绿或保留当日 XML；六 live 环境清空；lint 链照旧 offline 阻断（预存缺口）。
- **批次结论**：课表剩余 UI 批双产品闭环完成——task-12~16 全 complete；零新模块（组件计数维持 44）、零 settings/alias 变化、唯一 Gradle 编辑＝schedule:ui 增三项依赖；双 APK 审计 PASS（Standard `f81d2d21…`／Pro `4e10afcb…`）；逐方法账（45 随 ui＋15 随 runtime＋493/490 留、零新增定义）、逆审（可见性归一验证＋段切分拼回）全部落盘。**至此课表纵切全部完成；剩余范围：profile、documents**。

## profile＋documents 收尾批（profile-doc-0~4，task-17~21，2026-11-19 双产品闭环——V2 模块化最后一批）

- **分析**（subagent，复用制＋模型偏差沿用）：[profile-documents-analysis.json](<../.local/v2-validation/profile-documents-analysis.json>)——profile 6 件 713 行＋documents（content 家族，协议文档只读器）7 件 308 行清点、Pro 零引用逐条排除、documents 实况刻画（死代码/反向边/平台耦合三重锁定的精确证据）。
- **Lead 裁定**：profile 三模块（壳 ProfileFeatureScreen 留驻不建空 ui 层）；documents 采 **D-A 对称收尾**＋两处授权（HtmlBodyPanel 死代码删除、RepositoryContentSource 1 行构造 seam——campusnet 先例）；**ArticleCards 裁定 (B) 不删**——owner 证据链推翻卡面前提（其 text 扩展来自同包 ui/Components public 符号、非 ContentModels internal，删除断编译不成立），protected MainScreens 纯清理不动，与 HtmlBodyLoadSession（Panel 删除后生产面暂无消费者、12 测试照跑零害）一并登记**后续清理候选批**。
- **实施**（复用 run-runtime/run-flanks）：task-18 profile——5 生产＋4 测试迁三模块（28 @Test），11 处 internal→public 逐符号账（含如实更正分析文件"无跨模块 internal 依赖"的误判；ProfileOwner private 构造保持 token 不外泄），fixture seam 三件（ProfileApiFixture 公共 API 自建＋session/header 本地副本），runTotals 定位结论零动作（定义在壳、同模块 internal 可见）；task-19 documents——5 件迁移＋HtmlBodyPanel 删除＋三处 seam（构造参数 3 行、ContentPage 构造点 1 行拼回验证、AppRepositories 实况零改动）＋HtmlBodyLoadSession 留驻结论。
- **Lead 接线期一处架构修正（披露）**：owner 迁移的 ContentSource.kt 内 RepositoryContentSource 类段引用 ContentRepository（data 类型）却落 domain——反向边；按 campusnet 先例（仓储型 source 住 data）拆分：接口留 domain、类段迁 data 新文件（拼回验证＝owner seammed 文件逐字节）。
- **接线**：六新模块 build.gradle.kts（profile domain/data/presentation＋content domain/data/presentation，依赖按真实 import 推导）；settings include 六模块；shared 增 implementation×4（data/presentation 两侧）；私有 alias 六条。组件计数 44＋6＝**50 唯一组件**。
- **构建环**：R1（2m46s）唯一错误＝content:presentation commonTest 漏 coroutines.test（Lead 接线缺口补一行）；R2（4m46s）**BUILD SUCCESSFUL**；Pro **2m20s PASS**（composition 15 执行、317 项 UP-TO-DATE 如实）。
- **最终真实执行（XML 逐腿核对零失败）**：profile domain **5**、data **11/11**、presentation **12/12**；content domain/data **NO-SOURCE** 如实、presentation **3/3**；shared **462/459**（493/490＝留＋31 随迁×两腿）；Standard debug/D8 成功。
- [逐方法账](<../.local/v2-validation/profile-doc-regression-junit.json>) ACCOUNT-OK：31 项恰一 owner×两腿、HtmlBodyLoadSessionTest(12) 留驻、**零新增定义**。
- [源不变量逆审](<../.local/v2-validation/profile-doc-invariants.json>) INVARIANTS-PASS：8 件纯迁移双证；Models/Policy 可见性归一验证（11 符号）；3 fixture seam methodnames/断言守恒；ContentSource 拆分拼回 owner seammed 文件逐字节；ContentPage 1 行 seam 逆恢复基线 SHA；AppRepositories 零改动证实；9 protected 原字节（MainScreens 按 B 裁定未动）；HtmlBodyPanel 删除可从快照恢复；display 例外 `5923bf…` 零写。
- **双 APK 审计 PASS**：Standard **19 DEX／22487 defined／0 private**，SHA `cff64255d84226dc9e1907ac7cda58b34aceb4f1f7ba831b0f26593190924fb7`；Pro **20 DEX／23346 defined／552 private**，SHA `a1dc37b4261050d81b3df5250dcac8ec2046fbddda56bb9d523d57bcade51cf0`。课表组件/权限/资源与基线精确全等、updater 唯一 private、BLE 仅 Pro。
- **非字节变化逐条（本批全文）**：①profile 11 处 internal→public（逐符号带引用面行号）；②RepositoryContentSource 3 行构造参数 seam＋ContentPage 1 行构造点；③HtmlBodyPanel 整件删除（快照可恢复）；④Lead 拆分 ContentSource（类段迁 data）；⑤content:presentation commonTest 增 coroutines.test；⑥其余迁移件字节全等。
- **run-guard 守卫交付（task-20）＋Lead 独立复验（全部真实执行）**：六坐标完整入册（MODULES/ALLOWED_PROJECTS 照实际 build——content:data 按文件实况仅 api(domain)+network-session 未照"同构"口径，如实）；MOVED_FILES 十件；11 处 internal→public 逐符号 pin（PROFILE_DISCLOSED_HEADERS，私构造类头+脱敏 toString 全冻结）；十枚 raw/inferred 头 legacy 精确 pin；profile/content:data 进 DATA_MODULES（四先例同列）、domain/presentation 走显式 import 分支（**归类判断披露**：profile domain 带 network-session/Session 头，进 TYPED_FLOW 会误禁——照 schedule:runtime 先例）；display 六根；CI 六条×三处；私有 facade 双文件镜像同步（7-fail 教训防再犯，tuple 严格相等）；三组成对反例。守卫过程一次接线缺口如实上报——shared 缺 profile/content domain 两行直列（Lead 裁定 B 自补 :81/:84，守卫规则"入册模块显式接线"照先例满足）。Lead 复跑：公共 suite **377/377 OK**（696.4s）、私有 **84/84**、architecture 真树 **320 文件**、display **261 文件**、facade **312 文件**；display 例外 SHA `5923bf…` 逐位一致零写。
- **最终标准回归（offline 全 50 模块列表）**：**BUILD SUCCESSFUL 3m15s**——六新模块全部入列（content domain/data NO-SOURCE 如实）、shared 462/459 因依赖行变化本次复执行零失败、Standard debug/D8 成功；六 live 环境清空。
- **批次结论＋V2 模块化总验收**：profile＋documents 收尾批双产品闭环完成——task-17~21 全 complete；**四遗留纵切（Run 写内核、课表剩余 UI、profile、documents）全部完成**。**最终双 APK 总验收 PASS**：Standard `cff64255d84226dc9e1907ac7cda58b34aceb4f1f7ba831b0f26593190924fb7`（19 DEX／22487 defined／0 private）、Pro `a1dc37b4261050d81b3df5250dcac8ec2046fbddda56bb9d523d57bcade51cf0`（20 DEX／23346 defined／552 private）——最终制品哈希与审计值逐位一致，课表组件/权限/资源与基线精确全等、updater 唯一 private provider、BLE 仅 Pro。**V2 模块化按用户授权的验收标准（最终双 APK 审计全 PASS）达成**：48 可复用 KMP 库＋shared＋host＝50 唯一组件（V2 raw 计 61＝前 55＋4＋2... 以 check_component_coordinates 实际输出为准），shared 由"全功能大单体"收敛为总装壳层（Route/AppGraph binder 与集成测试），全部九大功能（跑步读写、课表、首页、更新、找回密码、校园网、校园登录、个人资料、协议文档）各自持独立 domain/data/presentation(/ui/runtime) 模块。范围外如实保留：iOS/真机验收停止（无 Apple 认证条件）；后续清理候选批（ArticleCards 死代码、HtmlBodyLoadSession 无生产消费者）；lint 链离线缺口（预存）；`releaseReady=false` 未改、零 git 写操作全程。

## 版本发布与收尾（2026-11-19 夜，用户授权 git 操作与 rc 构建）

- **双仓 commit＋push（用户指令，本 session 唯一一次 git 写）**：公共 `3e73e3a`（V2 迁移全量 244A/61D/75M/168R）→`e3885f5`（rc59 bump）→`bb9307f`（compose 库离线 AAR 元数据对齐）；私有 `2f59da0`（消费方接线）→`19ce7f4`（rc59 bump）→`fd621f4`（pro 模块对齐）→`bcff260`（pin `bb9307f`＋`releaseReady=true`，设计内发布锁流程："pin 审查后启用"——集成 V2 公共提交已提交推送并全量验证）。3 个 session 遗留杂项（reports/ 下两桥脚本＋一笔记）保持未跟踪。
- **版本**：`version.properties` 双仓同步 rc58/59→**rc59/60**；tag `v1.0.0-rc59`（公共）与 `v1.0.0-rc59-pro`（私有）**只打本地未推送**（用户指令）。
- **Pro rc59 三种制品**（[.local/rc59-dist/](<../.local/rc59-dist/>)）：debug APK 45.06 MB `09194f95…`；**unsigned release（R8/minify 首次真实成功，5m06s production 门全过：pin 精确匹配独立 source/ 干净快照、零路径 override、verifyReleaseInputs/verifyPublicBase 真实执行）** 5.44 MB `a7b68c71…`；**release 本体＋debug.keystore 签名版**（zipalign＋apksigner 实签验签、`CN=Android Debug`）5.46 MB `6a6f4a04…`——用户明确要求"release 产物＋debug 钥匙"，生产签名路径（GitHub Secrets 五项、workflow_dispatch 触发的 release.yml）全程未碰。
- **离线依赖对齐根因修复**：compose 库的 androidx 旧传递依赖（activity-ktx 1.7.0／core-ktx 1.16.0）本地从未缓存导致 release 链 AAR 元数据检查离线失败（与历史记录的 lint 链缺口同根因）——按目录已缓存版本对齐（1.13.0/1.18.0，update 批"对齐现有产品版本"同款先例）：公共 compose-library convention＋私有四 pro 模块 androidMain。
- **死代码清理批（2026-11-20，用户授权"清理了吧"）**：①MainScreens.kt 的 ArticleCards 段（L50-76，零调用死 composable）＋四个孤儿 import（AppText/serialization.*/MiuixIcons/extended.*）删除；②HtmlBodyLoadSession.kt＋其 12 项测试删除（HtmlBodyPanel 删除后零生产消费者，平台 HtmlPanel expect/actual 链另有消费者不动）。验证：shared **450/447**（462/459−12×两腿）零失败、Standard debug 1m47s PASS、architecture **319 文件**/display **260 文件 2789 tokens**（tokens −8＝删除段文案）/facade **311 文件**真树全 PASS、公共 suite **377/377**、私有 **84/84**。**如实记录一处流程漏项**：rc59 升版后未即时重跑私有套件，死代码清理复跑时 `test_private_default_is_the_frozen_rc_product` 冻结门抓住 version.properties 已 rc59/60（设计内钉值维护：测试钉值同步 rc59/60 后恢复 84/84）——该门按设计冻结"当前产品身份"，升版必须同步更新钉值，此次由清理批代为发现。rc59 的两个本地 tag 指向清理前提交（`bb9307f`/`bcff260`）——tag 即已审查的发布状态，清理属其后 hygiene，不移动 tag。

