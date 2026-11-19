# V2 架构重构：2026-11-17 进度与明日接续

> 记录时间：2026-11-17 21:22，Asia/Shanghai。用户要求“先落盘今天的进度，明天继续”。
> **状态：已停工、全部 Team 成员 inactive、自动目标 paused。当前工作区是迁移中间态，不是最终可构建版本。**
> 今日早先的构建通过仅对应当时树；后续 Pro/地图迁移及产品接线尚未重新集成验证。不要把已有 APK、测试 XML 或 Gradle 缓存视作当前树通过的证据。

## 1. 工作基线与约束

- 公共工作区：`E:/better-zslf`；HEAD `1674115e7ccfc574313647d1e4c646903601c4c5`。
- 私有工作区：`E:/better-zslf/work/pro-ci`，是独立 Git 仓库；HEAD `80481a947c4a03b8671cc709a8b741e4652b8ba3`。本轮未 fetch/pull。
- 批准的实施依据：[V2 方案](<architecture-decoupling-v2-pro-extraction-2026-11-17.md>)；[V1 审查](<architecture-decoupling-review-2026-11-17.md>)仅作历史背景。
- [agents.md](<../agents.md>) 已落盘，只包含用户要求的模型规则：Team 跟随 Lead，不覆盖模型；subagent 指定 `workbuddy/deepseek-v4.1-flash`、`max`。
- 指定 WorkBuddy provider 在本 session 不可用，未换另一模型创建 subagent。Team 创建未传模型覆盖参数；工具的成员模型显示曾与 Lead 路由不同，后续状态均显示跟随当前 Lead，不将其写成“已强制验证同模型”。
- **未提交、未暂存、未推送、未改写历史；未签名或发布。** 未访问学校业务服务，未扫描/连接 BLE，未操作设备、GPS 或真实上传。
- 原有未跟踪脚本、报告和本地缓存均保留，没有执行 clean/reset/批量删除。

## 2. 今日实际落盘

### 2.1 公共模块与第一批可运行纵切

| 范围 | 已落盘内容 | 当前验证口径 |
| --- | --- | --- |
| 构建约定 | [build-logic 设置](<../build-logic/settings.gradle.kts>)、[依赖](<../build-logic/build.gradle.kts>)、[KMP 约定](<../build-logic/src/main/groovy/betterzslf.kmp-library.gradle>)、[Compose 约定](<../build-logic/src/main/groovy/betterzslf.compose-library.gradle>) | 离线配置曾成功；JVM21、Android/iOS targets，普通库不产 framework |
| 公共文案 | [core:text 构建](<../core/text/build.gradle.kts>)、[AppText](<../core/text/src/commonMain/kotlin/io/github/betterzslf/core/text/AppText.kt>) 同包物理迁入 | 文案内容尚未清除 Water/ManualRoute/路线预览专属块 |
| 真实跑步模型 | [core:run-model 构建](<../core/run-model/build.gradle.kts>)、[RunGeometry](<../core/run-model/src/commonMain/kotlin/io/github/betterzslf/feature/run/RunGeometry.kt>)、[RunTrack](<../core/run-model/src/commonMain/kotlin/io/github/betterzslf/feature/run/RunTrack.kt>)、[RunGeoPath](<../core/run-model/src/commonMain/kotlin/io/github/betterzslf/feature/run/RunGeoPath.kt>) | core 19 tests 曾通过；真实 assembler 保持私有 |
| 坐标边界 | [坐标转换](<../core/run-model/src/commonMain/kotlin/io/github/betterzslf/platform/RunMapCoordinates.kt>)、[坐标帧](<../core/run-model/src/commonMain/kotlin/io/github/betterzslf/platform/RunMapCoordinateSystem.kt>)、[纯 wire 转换](<../core/run-model/src/commonMain/kotlin/io/github/betterzslf/feature/run/RunWireLocation.kt>) | shared 的 HTTP/batch adapter 不迁入 core；用不同文件名避免 JVM Kt facade 冲突 |
| 设计系统/布局 | [design-system 构建](<../design-system/build.gradle.kts>)、[DesignComponents](<../design-system/src/commonMain/kotlin/io/github/betterzslf/ui/DesignComponents.kt>)、[PageLayout](<../design-system/src/commonMain/kotlin/io/github/betterzslf/ui/PageLayout.kt>)、[ConfirmationCheckRow](<../design-system/src/commonMain/kotlin/io/github/betterzslf/ui/ConfirmationCheckRow.kt>) | 8 tests 曾通过；JSON/NavigationResults/AsyncContent 留 shared |
| 服务目录样板 | [ServicesUiState](<../feature/services/presentation/src/commonMain/kotlin/io/github/betterzslf/feature/services/presentation/ServicesUiState.kt>)、[ServicesContent](<../feature/services/ui/src/commonMain/kotlin/io/github/betterzslf/feature/services/ui/ServicesContent.kt>)、[页面 binder](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/ServicesPage.kt>)、[目录装配](<../shared/src/commonMain/kotlin/io/github/betterzslf/composition/ServiceDirectory.kt>) | 最初4 tests/UI JVM编译曾通过；之后 SnapshotList 防输出强转修改及新增测试尚未重跑；目录产品接线仍在改 |
| 预览只读合同 | [RunPreviewContext](<../feature/run/api/src/commonMain/kotlin/io/github/betterzslf/feature/run/api/RunPreviewContext.kt>)、[RunPreviewSource](<../feature/run/api/src/commonMain/kotlin/io/github/betterzslf/feature/run/api/RunPreviewSource.kt>)、[API 测试](<../feature/run/api/src/commonTest/kotlin/io/github/betterzslf/feature/run/api/RunPreviewContextTest.kt>) | 后加、尚未编译；不暴露 raw JSON/Session/token/repository/写能力 |

[公共 settings](<../settings.gradle.kts>) 已 include core、DS、services、run-api；[shared 构建](<../shared/build.gradle.kts>) 已连第一批依赖。shared 和 [Android 构建](<../androidApp/build.gradle.kts>) 已将默认值改为 Standard 并拒绝公共根 `pro` 构建；旧源集选择/exclude/Kable 分支尚未删除，属于过渡态。

### 2.2 Water 完整闭包迁入私有仓（task-9 源码工作完成）

- 模块 [Water 构建](<../work/pro-ci/pro/water/build.gradle.kts>) 已初建；13 个旧生产文件、7 个专属测试和4个工具已物理迁入同源集布局，旧 shared 对应源文件已删除。
- 协议/transport/controller、presentation、screen 与 Bluetooth expect/Android/iOS/JVM actual 一起迁移，未只搬 UI 外壳。
- [ProText.Water](<../work/pro-ci/pro/water/src/commonMain/kotlin/io/github/betterzslf/pro/core/text/ProText.kt>) 已完整复制，迁入引用已切到私有文案；公共 AppText 尚保留原块，待混合测试切割后删。
- [MIT notice](<../work/pro-ci/licenses/waterctl-MIT.txt>) 已复制；公共原 notice 未擅删。
- [ProRuntimeOwner](<../work/pro-ci/pro/water/src/commonMain/kotlin/io/github/betterzslf/pro/composition/ProRuntimeOwner.kt>) 为产品级惰性 owner，构造/首次读取不扫描、连接或申请权限；shutdown 为 NonCancellable、single-flight、等待清理，不调用 end，也不宣称蓝牙断开等于物理断水。
- [Owner 三项测试](<../work/pro-ci/pro/water/src/commonTest/kotlin/io/github/betterzslf/pro/composition/ProRuntimeOwnerTest.kt>) 已写，**尚未运行**；完整 BLE/presenter/UI 的迁后 Gradle 回归尚未运行。
- Android actual 原已通过 Compose LocalContext 获取 applicationContext，迁后没有新增 shared AndroidPlatform 依赖。
- 工具改为从 Water 模块定位源码及 ProText，临时 Kotlin 只落 module build，不再写 commonTest 源树。

### 2.3 Pro 路线算法/controller 迁出（task-10 源码工作完成）

- [route-tools 构建](<../work/pro-ci/pro/route-tools/build.gradle.kts>) 已初建，6份生产实现和5份原测试已物理迁入；新增1份 [typed-context 回归](<../work/pro-ci/pro/route-tools/src/commonTest/kotlin/io/github/betterzslf/feature/runui/RunPreviewContextControllerTest.kt>)。
- parser/validation/detour 与 TrackPreview 私有化；真实公共生成器的 assembler 没有为预览开放。
- [RunImportController](<../work/pro-ci/pro/route-tools/src/commonMain/kotlin/io/github/betterzslf/feature/runui/RunImportController.kt>) 改为接收 RunPreviewContext，不依赖 RunTaskDetail/RunTaskItem/raw JSON/RunRepository/RunRuntime。
- [RunManualDraft](<../work/pro-ci/pro/route-tools/src/commonMain/kotlin/io/github/betterzslf/feature/runui/RunManualDraft.kt>) 和测试已使用私有 MapEditorEvent。
- [RouteText](<../work/pro-ci/pro/route-tools/src/commonMain/kotlin/io/github/betterzslf/pro/route/text/RouteText.kt>) 已复制完整 ManualRoute 与实际使用的67个 Run 键及对应函数；公共文本后续统一裁剪。
- 按 context.coordinateSystem 做本地转换：WGS84 不二次偏移，unknown-frame 编辑失败不覆盖旧导入。新增4用例覆盖帧与输入快照。
- 共113个 `@Test` 定义（迁入109+新增4），**不是113项已经执行通过**。需等 picker/map 依赖接线后运行。

### 2.4 公共只读地图 / 私有绘图引擎（task-12 源码工作完成）

- 公共 [RunMapPanel 合同](<../platform-ui/readonly-map/src/commonMain/kotlin/io/github/betterzslf/platform/RunMapPanel.kt>)、[RunMapWeb](<../platform-ui/readonly-map/src/commonMain/kotlin/io/github/betterzslf/platform/RunMapWeb.kt>)、[BootstrapPolicy](<../platform-ui/readonly-map/src/commonMain/kotlin/io/github/betterzslf/platform/RunMapBootstrapPolicy.kt>)、三平台 actual 与两组测试共8文件已从 shared 物理迁出，仍保留公共包名。
- 公共模型保留 actual/recommended/imported 只读路径/checkpoint；删除 editMode、DrawPoint、绘图 JS/parser 和 Pro overlay 反向引用。
- 私有 [MapEditorPanel 合同](<../work/pro-ci/pro/map-editor/src/commonMain/kotlin/io/github/betterzslf/pro/map/MapEditorPanel.kt>)、[MapEditorWeb](<../work/pro-ci/pro/map-editor/src/commonMain/kotlin/io/github/betterzslf/pro/map/MapEditorWeb.kt>)、[Drawing](<../work/pro-ci/pro/map-editor/src/commonMain/kotlin/io/github/betterzslf/pro/map/MapEditorDrawing.kt>)、bootstrap、三actual和两组测试共9文件已落盘。
- 私有包为 `io.github.betterzslf.pro.map`；MapEditorModel 保留原编辑模型字段；MapEditorEvent 为 Ready/Failed/DrawPoint/CheckpointSelected；复用公共 Provider/Failure/Path/Checkpoint 与 core 坐标帧/点类型。
- 旧 Pro RunMapEditing 与 Standard 空 overlay 已删除；没有公开任意 JavaScript 注入扩展口。
- **两个地图模块 build.gradle、settings/dependency 接线都未完成；shared/Pro UI 旧调用尚未统一改。** 仅源码安全等价/JS语法等静态检查已做，Kotlin/Native 和 commonTest 没有跑。

### 2.5 产品根与导航：只完成了半段接线

- 私有 [settings](<../work/pro-ci/settings.gradle.kts>)、[根构建](<../work/pro-ci/build.gradle.kts>)、[Gradle 属性](<../work/pro-ci/gradle.properties>) 已初建，走 composite + 外部坐标替换，不复制私有源进公共 source set。
- [public-base.properties](<../work/pro-ci/public-base.properties>) 暂记改动前公共 HEAD，`releaseReady=false`。**这个 SHA 不包含今天的未提交迁移，不能宣称可复现发布。** 开发需显式 publicSourceDir/publicDevelopment；正式 pin、provenance、release gate 尚未打通。
- [ApplicationContributions](<../shared/src/commonMain/kotlin/io/github/betterzslf/composition/ApplicationContributions.kt>) 已定义产品贡献/只读依赖边界；[App.kt](<../shared/src/commonMain/kotlin/io/github/betterzslf/App.kt>)、[LoginScreen](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/LoginScreen.kt>)、[FeaturePages](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/FeaturePages.kt>) 已开始替换旧 EditionFeatures 装配。
- 尚无私有 Android/iOS 产品根/Native umbrella，也无最终 ProComposition；typed 产品封装导航尚未实现，现在 `extension:<id>` / selection 字符串只是中途接缝，不是 V2 最终 typed-navigation 验收。
- contribution.shutdown 尚未接入 graph 清理/本地 wipe。不能把 Water owner 已实现误认为产品生命周期已接通。

## 3. 已执行验证：区分时间与范围

| 验证 | 实际结果 | 限定 |
| --- | --- | --- |
| 原构建离线 help | PASS，44s | 初始环境基线 |
| 新 build-logic 离线 help | PASS，2m1s | 第一批模块约定配置 |
| core:text/DS/services 编译测试 | PASS，1m48s | core:text 无测试；DS 8；services presentation 当时4；services UI JVM compile |
| core:run-model + shared + Android debug | PASS，4m41s，154 tasks | core 19、shared 940（其中1 skipped），0 failures；发生在 Water/地图/后续产品接口迁出之前 |
| Water standalone 纯协议 | PASS，28项 | 迁后模块、正确 compiler-home；不含 BLE/coroutines owner/UI |
| Water WAT/TS 差分 | PASS，220,702 vectors | 迁后工具读旧本地 oracle TSV，全离线，不是设备验证 |
| 架构守卫 fixtures | PASS，26/26 | 最后补 run-api scope 前；补丁后未重跑 |
| 实际架构扫描 | 曾 PASS，files=204 | 地图迁移及后续接线前，不是最终树 |
| 文本扫描 | 曾 PASS，files=193/tokens=2800/Han=60/exceptions=60 | 58 protocol + 2 diagnostic，0 deferred；精确 allowlist 未改；地图迁移/run-api最后补丁前 |
| 文本 Python suite | 137/138 PASS，1 ERROR | 唯一原问题为测试 workflow 强制私仓绝对路径；未关闭断言 |
| 地图离线安全核对 | PASS | host/allowlist/nonce/重复字段/坐标策略等价断言、JS/iOS guard语法；非 Kotlin 编译 |
| 停工双仓 git diff --check | exit0 | 21:17；只覆盖 tracked diff，不能替代所有新文件编译或验证 |

最后集成成功命令（**只作历史证据，当前树不可直接假定仍成功**）：

```powershell
$env:JAVA_HOME='E:/better-zslf/.local/jdk-21'
& E:/better-zslf/gradlew.bat --offline --no-daemon `
  :core:run-model:jvmTest :shared:jvmTest :androidApp:assembleDebug `
  -PbetterZslfEdition=standard
```

迁后 Water 离线工具为 [verify_kotlin.py](<../work/pro-ci/pro/water/src/commonTest/kotlin/io/github/betterzslf/feature/water/tools/verify_kotlin.py>) 和 [verify_oracle.py](<../work/pro-ci/pro/water/src/commonTest/kotlin/io/github/betterzslf/feature/water/tools/verify_oracle.py>)。正确 compiler-home 是 `E:/better-zslf/.local/kotlin-2.4.21/kotlinc`；最初误少一层 kotlinc 的 ClassNotFound 已通过更正参数解决，不是算法故障。

## 4. 明日优先处理的已知断点（不是已修复项）

### P0：先闭合编译，再谈新拆分

1. 给公共 readonly-map / 私有 map-editor 建立 Gradle 模块并接 settings/composite/substitution，route-tools 依赖 private map-editor。Water 缺 lifecycle-compose 依赖应核齐。检查 Gradle9.6 根脚本的 API 可用性，Pro 根尚未做过配置测试。
2. [App.kt:439](<../shared/src/commonMain/kotlin/io/github/betterzslf/App.kt#L439>) 仍调用 `ServiceDirectory(EditionAccess.waterEnabled)`，但 [目录构造器](<../shared/src/commonMain/kotlin/io/github/betterzslf/composition/ServiceDirectory.kt#L8>) 已改 ApplicationContributions；需要完成迁移而非只修成可编译。贡献 routes 已入表，但贡献 entries 尚未加入 state 的 sections，会导致私有入口不可见。
3. [RunPages](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/run/RunPages.kt>) 仍引用已经从 AppGraph 删除的 editionFeatures，RunPages/RunTrackerPage 的旧 Pro 按钮需要改为产品贡献，公共根最终无 Pro 功能分支。
4. [RunUi](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/run/RunUi.kt>) 仍传 editMode=false；[RunMapFeedback](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/run/RunMapFeedback.kt>) 及其测试仍引用公共 DrawPoint；混合 [AppTextPlatformGoldenTest](<../shared/src/commonTest/kotlin/io/github/betterzslf/core/text/AppTextPlatformGoldenTest.kt>) 仍有编辑模型。依新公共只读合同切割，不恢复公共编辑口。
5. [PublicRunPreviewSource](<../shared/src/commonMain/kotlin/io/github/betterzslf/composition/PublicRunPreviewSource.kt>) 是未编译草稿；[RunDataDecoder.detail](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/runui/RunData.kt#L77>) 返回可空，需要核对投影/错误分支及所用 AppText 常量是否真实存在。
6. 私有路线 UI、ManualDraftSession/Sheet 与 picker（expect/actual/JVM helper/专属测试）仍在公共 shared，需整体迁出并改接 RunPreviewSource/lease、MapEditor*、私有 RouteText，不能靠 exclude 宣称零源码。

### P0：task-13 预览 lease 修复尚未开始写

只读 review 已确认以下实质问题，用户停工时 task-13 仅领取，**源码/新测试零写入**：

- 当前 source 通过异步收集 Session StateFlow 递增 revision；clear→save 同一 owner/token 的 ABA 可被 conflation 吞掉，旧 lease 再有效；初始化 collect 也可能误伤早到读取。
- withQueryContext 对服务端显式 task/schedule 矛盾只清 id/加 warning，当前投影仍可能把另一任务几何作为 Ready 发出。
- graph.close 取消 scope 后，当前 lease 未检查 Job 存活，仍可能显示 current。

指定修法：在 [SessionStore](<../shared/src/commonMain/kotlin/io/github/betterzslf/core/storage/SessionStore.kt>) 每次成功 save（含相同 snapshot）及 clear finally 同步递增 StateFlow revision，失败 save 不增；source 直接暴露该 epoch，捕获前后核验，lease 同时校验 epoch + API binding + owner Job.isActive；投影前拒显式 identity mismatch，缺失身份允许 query 补齐。保留 late401 不清新身份规则。新增 MockEngine 测试覆盖 ABA/同值save/map变更/close/late401/missing-vs-mismatch；不要真实网络。

### P1：完成公共/私有产品根与清理闭包

- 替换并删除旧 [EditionFeatures](<../shared/src/commonMain/kotlin/io/github/betterzslf/edition/EditionFeatures.kt>)、[EditionAccess](<../shared/src/commonMain/kotlin/io/github/betterzslf/edition/EditionAccess.kt>)、standard/pro facade 源集，清除 shared excludes/source overlays/仅 Pro 依赖。
- public AppText 裁掉 Water/ManualRoute/预览专属键，拆混合 golden/alias tests；不要把 Standard 真实 GPS/地图/history 文案或行为误迁私有。
- 公共 manifest/plist 去 BLE/picker 私有声明，保留定位/后台服务/课表/widget；Pro manifest 必须合并增量权限，不整份替换公共主 manifest。
- 建立 Pro Android/iOS root、私有 Native umbrella、产品版本与 update policy。当前 GithubReleasePolicy/Android updater 仍依赖 BuildEdition 全局，未实现最终产品注入。
- 产品 shutdown/wipe 必须等待 owner，不能提前擦 storage 或仅取消 scope；导航保存恢复、离线入口准入与 MIUIX0.9.4 合同需实测。
- 固定整合后的公共 S、私有 P、工作流 W provenance；当前 dirty HEAD 只允许显式开发模式，不能进入发布路径。通知 release 的 leading false 锁保持关闭。

### P1：CI 与静态守卫

- task-11 已领取但**没有改任何文件**；公共 tests.yml 尚不存在，resolver 默认仍强制 BETTER_ZSLF_TEST_WORKFLOW 私仓绝对路径。
- 明日新增公共 JVM/Android + macOS Native/Simulator 测试，保留 pinned 工具/Actions、现有诊断安全合同；外部 override 可选且仍要求 absolute。
- [architecture_guard.py](<../scripts/architecture_guard.py>) / [display_text_guard.py](<../scripts/display_text_guard.py>) 最后已补 run-api scope，**尚未复测**；readonly-map 尚未纳 scope，原地图路径 fixtures/byte guards 待迁移。
- 不通过放松精确 allowlist、跳过安全 tests 或关闭 guard 来拿绿。

## 5. 当前任务板与恢复顺序

| 任务 | 停工状态 | 明日责任/下一步 |
| --- | --- | --- |
| 1–4 审计/方案 | completed | 只参考已批准V2 |
| 5 DS/布局 | completed | Lead 串行集成验证 |
| 6 core/run-model | completed | 迁后跨仓/Pro seed/detour 回归 |
| 7 文本/架构 guard | completed | 最后 run-api 补丁复测；纳地图 scope |
| 8 服务纵切/Lead接线 | in_progress | 目录/state/产品导航和生命周期闭合 |
| 9 Water 源码迁移 | completed | 全 Gradle、BLE/UI/owner tests 待跑 |
| 10 路线算法/controller | completed | picker/map接线后跑113定义的测试集合 |
| 11 公共CI独立化 | in_progress，尚未写 | architecture-guards |
| 12 地图源/test分离 | completed | Lead 接 Gradle + UI，跑安全回归 |
| 13 同步 epoch/lease | in_progress，尚未写 | data-boundaries，优先安全修复 |

建议顺序：恢复目标 → 先读本记录与双方 git status → task-13 / CI / Pro UI+picker 分 disjoint scopes 并行 → Lead 地图/产品构建接线 → 串行 offline help/module tests/shared tests/Android assemble → 公共零Pro源与私有闭包guard → iOS macOS验证、pin/provenance/文档。

## 6. 环境与停工保全

- 工作目录必须先确认 `E:/better-zslf`；DSH 安装源码 checkout 与本项目无关，不要改那里。
- JDK21 home：`E:/better-zslf/.local/jdk-21`；系统默认另有 JDK26，每条 Gradle 命令显式设 JAVA_HOME。
- Android SDK：`E:/better-zslf/.local/android-sdk`，local.properties 已指向它；Gradle wrapper9.6.1 与第一批依赖已有离线缓存。
- Python：`C:/Users/bx233/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/python/python.exe`，脚本测试用 `-B`。
- standalone Kotlin home：`E:/better-zslf/.local/kotlin-2.4.21/kotlinc`。
- 21:17 双仓 status 已核：公共 tracked 为66文件改/删，约229 insertions/10935 deletions；这个 diff stat **不包含新建模块/私有仓未跟踪文件**，大删除是物理搬迁，不代表只删实现。新代码大量尚未跟踪，禁止只保留 tracked diff 当备份。
- 私有仓新增模块及根构建都尚未跟踪；[现有 .gitignore](<../work/pro-ci/.gitignore>) 尚未覆盖模块 build，Water 验证产生的两个 jar 会出现在 untracked。不要误提交 build 产物，也不要停工时擅清。
- 旧公共 Water commonTest 下历史未跟踪 oracle/wabt/build 缓存未迁未删；只作为本地验证输入，不是生产源遗留，也不是可提交 fixture。
- 原有 relaunch/restart/其他 reports 未跟踪项全部保留。
- 全部4名 Team 成员已交接并 inactive；没有运行中的后台 job/终端。已收取全部4个 Gradle后台job结果。自动目标 `goal-3b8766f1-57a1-4d28-be2d-8b14d6343ec5` revision2、phase paused。
- 明天用户明确“继续”后再 resume，不自动定时、不自行在夜间续跑。
