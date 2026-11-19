# 前后端、UI 布局与 UI 实现解耦建议

> 项目：Oh My ZSC / better-zslf  
> 审计日期：2026-11-17（Asia/Shanghai）  
> 状态：第一版已完成；按章节分批落盘，并完成静态证据与交付范围核对。  
> 原轮次边界：只读源码，只新增本 Markdown 报告；不改代码、构建配置、现有规范，不执行构建、安装或真实网络/设备操作。
>
> **后续要求更新**：用户已明确允许激进拆分/大规模重构，并要求 Pro 功能源码迁出到 pro-ci。当前推荐架构及分仓方案改以 [V2 报告](<architecture-decoupling-v2-pro-extraction-2026-11-17.md>) 为准；本文的保守模块数量、暂留 Pro source selection 和原轮次交付记录仅作第一版背景，不覆盖新要求。

## 1. 先给结论

**值得拆，但不要直接拆成“前端仓库 / 后端仓库 / 布局仓库 / UI 实现仓库”四份。** 当前更适合：同仓库、按功能纵向组织，在功能内部把业务与展示分层；先用窄接口消除依赖，再用少量 Gradle 模块把边界锁住。

建议的职责是：

1. **业务与数据**：网络协议、解析、持久化、会话与设备流程；对外给稳定的业务模型与能力接口。
2. **展示逻辑**：把业务数据投影成 `UiState`，处理用户事件、异步状态与一次性效果，不负责绘制。
3. **页面布局**：根据 `UiState` 组合区域、安排信息层级、适配窗口和完整状态；不取仓库、不登录、不启停设备。
4. **视觉组件实现**：主题、token、MIUIX 薄适配、通用组件及无障碍行为；不理解账号、课表同步或校园跑上传。
5. **装配与平台宿主**：创建实现、选择 Standard/Pro、管理应用级生命周期，把依赖交给页面入口。

其中“布局”和“UI 实现”在 Compose 中仍然都会是 Kotlin/Composable，**分开的是依赖和改动权限，不是强行换成另一种语言或框架**。不建议为了组织分工引入动态 JSON 页面 DSL，也不建议把所有业务都搬去一个新建服务器。

### 最优先的三刀

- **先断页面对整个 `AppGraph` 的依赖**：保留薄的 Route/入口绑定，内容组件只收状态、事件与必要 slot。
- **再断业务模型对传输格式和具体实现的依赖**：把 `JsonElement`、Ktor 工厂、DTO 解析留在 adapter/data 边界。
- **最后落模块边界**：先证明一个试点可以用假数据独立渲染、独立测试，再移动目录与 Gradle 依赖；不要反过来先大搬家。

## 2. 当前仓库到底是什么

### 2.1 “后端”的准确含义

当前主工程是 **Kotlin Multiplatform + Compose Multiplatform 客户端**，不是典型的 Web 前端加自建 HTTP 后端：

- [settings.gradle.kts](<../settings.gradle.kts#L14-L15>) 只声明 `:shared` 和 `:androidApp` 两个 Gradle 项目。
- [shared/build.gradle.kts](<../shared/build.gradle.kts#L41-L108>) 在同一共享模块配置 Android、iOS 和测试用 JVM，并同时引入 Compose/MIUIX、Ktor、协程、序列化及可选蓝牙库。
- [iOS 入口说明](<../iosApp/README.md#L1-L5>) 说明 iOS 也承载共享 Compose UI。
- [项目说明](<../README.md#L23-L28>) 将学校 HTTP API 规范指向另一个仓库；本轮没有读取或验证那个远端仓库。

因此，本报告把用户所说的“后端”主要理解为 **客户端内的数据接入、业务与设备运行层**。如果另有未在当前主工程中呈现的自建服务器，需要单独补充服务边界审计，不能据本报告认定它已经不存在或应当重建。

### 2.2 取样与限制

只读检查覆盖构建入口、共享源码目录、Android/iOS 宿主、现有视觉规范及测试入口；对高耦合入口和代表性功能做源码精读。目录统计显示：公共主源码有 155 个 Kotlin 文件，其中 `ui` 子树 38 个、`feature` 子树 81 个；公共测试发现 103 个 Kotlin 文件。它们是文件/目录统计，不是复杂度、覆盖率或测试通过率。

在上述 `ui` 子树中，静态文本检索发现 **17 个文件直接 import `AppGraph`**；在 `feature` 子树未发现直接 import Compose 或项目 `ui` 的匹配。这说明项目已有值得保留的业务/UI 分离基础，但全局对象和模型边界仍在把层次重新连起来。此处是 import 文本检索，不是完整依赖图证明。

根目录宽扫描受到 `work` 中大量参考/反编译文件影响触达预算，因此后续缩小到主工程目录；本报告**不宣称审查了整个工作区所有文件**。本轮不运行测试或构建，不宣称 Android/iOS 编译、真机视觉或设备协议验证通过。

## 3. 耦合源头：不是“文件放在一起”这么简单

| 观察 | 源码证据 | 实际代价 | 优先级 |
| --- | --- | --- | --- |
| 同一文件负责装配、应用业务、主题、导航与主布局 | [App.kt](<../shared/src/commonMain/kotlin/io/github/betterzslf/App.kt#L63-L178>) 创建会话、HTTP、运行时并执行本地数据清理；[后半段](<../shared/src/commonMain/kotlin/io/github/betterzslf/App.kt#L211-L432>) 同时负责 Compose 外壳 | 调整导航或主题时进入应用生命周期敏感区；提取 UI 模块会反向依赖装配模块 | P0 |
| 页面持有整个应用图 | [HomePage](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/HomePage.kt#L53-L84>) 直接订阅运行时；[FeaturePage](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/FeaturePages.kt#L38-L60>) 把 graph 分发给多种页面 | 任何页面都能拿到网络、存储和其他功能，边界靠自觉；预览必须装起很多无关依赖 | P0 |
| 通用 UI 中混有协议工具和异步请求执行 | [Components.kt](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/Components.kt#L20-L45>) 放 JSON 取字段与导航结果；[AsyncContent](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/Components.kt#L143-L160>) 执行 `ApiResult<JsonElement>` 加载 | 若原样抽成 Design System，就把业务数据协议一起抽进去，产生“公共组件大杂烩” | P0 |
| 类型接口已存在，但与 adapter 混放 | [ProfileRepository](<../shared/src/commonMain/kotlin/io/github/betterzslf/data/ProfileRepository.kt#L9-L38>) 实现 feature 中的接口；[RunUploadWorker](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/run/RunUploadWorker.kt#L19-L61>) 同文件定义上传口、具体仓库适配器和 worker | 按现有 `data` / `feature` 目录直接变模块，会暴露双向依赖，不能靠搬文件解决 | P0 |
| 业务契约文件带具体网络工厂 | [ScheduleModels.kt](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/schedule/ScheduleModels.kt#L78-L86>) 同时定义 `AcademicSource` 和依赖 HttpClient/Ktor 实现的创建函数 | 想让核心无 Ktor 依赖时会被工厂拖回基础设施层 | P1 |
| 业务投影与颜色/布局同文件 | [HomeView/HomeRow](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/HomePage.kt#L92-L146>) 定义状态并携带 Compose `Color`；[homeView](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/HomePage.kt#L169-L208>) 同时计算时间语义与课程配色 | 视觉调整与时间逻辑修改争抢文件；无法直接把现有模型当纯业务模型 | P1 |
| 平台能力与原生视图接口同文件 | [PlatformServices.kt](<../shared/src/commonMain/kotlin/io/github/betterzslf/platform/PlatformServices.kt#L8-L42>) 包含存储/HTTP/打开链接，也包含 Composable 外观与 HTML 面板 | 业务平台口若直接抽出仍会依赖 Compose；应区分服务能力和原生 UI 桥 | P1 |
| 看似简单页面也接收全局依赖 | [ServicesPage](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/ServicesPage.kt#L28-L75>) 接收 `AppGraph`，函数体并未使用 graph | 不必要的签名传播，形成低风险、可快速验证的清理样本 | P1 |

**不要把 `collectAsState`、`remember` 或 `LaunchedEffect` 的出现本身视为坏设计。** 页面入口收集状态、内容组件保留滚动/展开等临时交互状态是正常的；真正需要控制的是它是否拥有网络、会话、设备业务及跨页面运行时的决策权。

### 3.1 已有好基础，应当继续利用

- `feature` 目录已有 Controller、Presenter、Runtime、状态机，不需要重写成另一套 MVVM/MVI 才算解耦。先保留名称和行为，只调整依赖与归属。
- [AcademicSource](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/schedule/ScheduleModels.kt#L78-L83>) 和 [RunUploadTransport](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/run/RunUploadWorker.kt#L19-L21>) 已经是可替换能力口。
- [DesignComponents.kt](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/DesignComponents.kt#L77-L145>) 已集中 token、语义 tone；[Screen / PageList](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/Components.kt#L65-L123>) 已集中安全区、顶栏和滚动约定。
- [HomeViewTest](<../shared/src/commonTest/kotlin/io/github/betterzslf/ui/HomeViewTest.kt#L13-L17>) 已把八态投影作为纯函数测试对象；[代表用例](<../shared/src/commonTest/kotlin/io/github/betterzslf/ui/HomeViewTest.kt#L33-L169>) 可成为迁移前的行为基线，而非重新编造规则。
- [Android 宿主](<../androidApp/src/main/kotlin/io/github/betterzslf/MainActivity.kt#L15-L43>) 已保留 application 级 graph。拆分不能意外把它降为每次重组、每个页面都创建一份。

### 3.2 明确不推荐的方案

1. **先开四个仓库**：类型契约、版本发布、跨端和版本变体会先增加协调成本，尚未解决对象依赖。
2. **按现有目录直接切模块**：`data` 已引用 feature 契约，feature 又有具体 data 依赖；先消环再切。
3. **把所有内容塞进 common/core**：公共目录只允许真正稳定、跨功能使用的基础类型；功能契约应留在功能内。
4. **所有 Composable 都套一层自家组件**：仅为项目级行为/视觉策略或实际复用点封装，不复制一套 MIUIX API。
5. **把改样式与改异步业务放同一个迁移 PR**：应先无行为变更建立边界，再单独改视觉。

## 4. 推荐的目标边界：五种职责，而不是五个“大层”

以下是**建议的未来设计，不是已经实施的结构**。

```text
Android / iOS 宿主
         │
         ▼
应用装配 app-shell / composition root
    ├── 创建 data 与 native adapters
    ├── 创建业务 runtime / presentation owner
    ├── 选择版本、维护导航、处理一次性平台效果
    └── 挂载页面 Route
                │
                ├── 调用 Presentation（StateFlow<UiState> + 事件）
                └── 组合 Content（UiState + 回调 + 必要 slot）
                                  │
                                  ▼
                      Design System / 页面外壳
                                  │
                                  ▼
                          Compose + MIUIX

Presentation ──依赖──> 功能业务模型 / 能力口
业务运行时   ──依赖──> 功能业务模型 / 能力口
Data / Native adapter ──实现──> 功能能力口
```

箭头代表编译期依赖或调用方向；**业务层不 import Content / MIUIX，Content 不 import AppGraph / Repository / HttpClient**。运行时由装配层把接口对应的具体实现传进去。

### 4.1 每块具体拥有什么

| 边界 | 应拥有 | 不应拥有 | 主要变化原因 |
| --- | --- | --- | --- |
| 功能业务 / contracts | 业务值对象、读写能力口、明确失败类型、会话绑定约束、领域规则 | Compose、MIUIX、Ktor 工厂、原生控件 | 业务规则或能力需求变化 |
| data / adapter | HTTP、JSON/HTML 解码、协议兼容、存储格式、错误转换 | 页面间距、导航返回栈、显示色值 | 上游协议/持久化变化 |
| Presentation | 显示投影、加载/刷新状态、用户事件、UI 可执行能力、一次性效果 | 像素几何、真实系统组件、全局依赖查找 | 交互与展示语义变化 |
| Route / binder | 创建或取得正确 owner、订阅状态、生命周期绑定、事件/效果转接 | 大段布局、JSON 解析、重新实现业务状态机 | 页面接入/生命周期变化 |
| Content / layout | 页面区块、列表/表单结构、自适应、语义节点、临时交互状态 | HTTP、永久存储、账户/设备全流程 | 信息层级与布局变化 |
| Design System | token、主题、项目通用按钮/卡片/反馈、MIUIX 薄适配、外壳策略 | 功能特有规则、业务对象全集、仓库 | 全局视觉/无障碍策略变化 |
| 原生 UI adapter | 地图、HTML、选择器等 native view 互操作，受控参数/slot | 擅自发起业务、替代运行时保存会话 | 平台控件/渲染差异 |
| app-shell / composition root | 实现选择、DI、应用范围清理顺序、导航、Standard/Pro 装配 | 功能内部 DTO、页面内部细节 | 应用组装与宿主变化 |

### 4.2 UI 布局与 UI 实现应如何协作

布局负责人可以决定“下一节在日期下方、状态提示在操作前、窄屏纵向而宽屏分栏”；组件负责人决定“卡片材质、字号角色、主按钮反馈、错误 tone 如何解析”。**两者通过少量有产品含义的组件和 slot 协作，而不是隔着一套抽象描述语言。**

例子：页面使用 `InfoBanner`、`PrimaryAction`、`PageList` 与功能内的 `HomeHeroCard`。通用视觉进 Design System，只有首页使用的重点卡先留在首页功能；不要为了显得“共享”将其提升为几十个参数的万能卡。

对于地图/HTML 等重平台区域，布局可接收语义清晰的渲染 slot，真实宿主注入 native adapter，预览注入占位渲染。slot 是限定区域替换点，不应让整个页面变成一堆无法理解的插槽。

**仍可留在 Content 的状态**：滚动位置、展开项、密码显隐、焦点、单次未提交的本地输入。输入是否保留、何时清除应有明确产品规则。影响持久化、跨页业务、账户隔离、重复提交和设备状态的决策必须归 Presentation/业务 owner。

### 4.3 不强制套一种架构名词

继续用已有 Controller / Presenter / Runtime 完全可以。关键是让它们有单向状态出口和明确事件入口，而不是为了统一名字把全部类改成 ViewModel。一个简单页面也不必机械新增 UseCase、Reducer、Interactor 各一份；只有真实复用、状态复杂或副作用需要隔离时再引入。

## 5. 目录与模块怎么拆，才不变成另一个大泥球

### 5.1 先按功能纵向归属，再在功能内部划层

建议的包结构示意如下，**仅供后续实施，本轮未创建这些目录**：

```text
feature/home/
  presentation/   HomeUiState、HomePresenter、时间/数据投影
  ui/             HomeRoute、HomeContent、HomeHeroCard
  fixtures/       debug/test 专用八态样本

feature/schedule/
  domain/         课程、节次、日期规则、AcademicSource 等口
  application/    ScheduleRuntime、同步/绑定流程
  data/           KtorAcademicSource、HTML 解码、存储 adapter
  presentation/   页面状态投影、编辑草稿与事件
  ui/             Route、周表布局、详情与绑定表单

designsystem/
  theme/          颜色角色、文字角色、材质策略
  components/     主按钮、横幅、共享确认等
  layout/         Screen、PageList、Inset 合同

app/
  composition/    AppGraph 及实现创建
  navigation/     路由注册、五栈、登录守卫、效果转接
  shell/          主题接入、紧凑/宽屏导航壳
```

这是职责归属图，不要求每个功能立即建齐所有子目录。首页不需要凭空增加 data 层；校园网有独特协议，不能被一个全局 `Repository` 文件夹吞掉。多个文件共享一个稳定变化原因时可以同包，切忌每个 Composable 都独立为模块。

### 5.2 物理模块：建议的保守目标

**第一阶段不改 Gradle，只在现有模块内整理接口。** 一条完整纵切验证后，首批只抽 `:business`、`:data`，加上原有两个项目形成四个物理模块，UI 先在原模块内保持逻辑分层。需要独立的 UI 构建边界时再抽 `:design-system` 和 `:ui`，可演进到下表六个生产 Gradle 项目；四模块已满足需求时可以停下，不要求建齐六个，更不是每个功能复制六份：

| 模块（建议名） | 职责 | 可依赖的本项目模块 |
| --- | --- | --- |
| `:business` | 纯 Kotlin 的功能模型/ports、业务 runtime、无绘制的展示状态与逻辑；短期保持功能内分包 | 无；外部可用协程、日期等。禁止 Compose/MIUIX/Ktor |
| `:data` | 具体 HTTP/解析/存储 adapter，实现业务 ports | `:business` |
| `:design-system` | Compose/MIUIX 项目视觉与通用外壳 | 无；不依赖整个业务模块 |
| `:ui` | 功能 Route/binder、Content、功能内视觉组件 | `:business`、`:design-system` |
| `:shared` | **保留现有共享入口与 framework 角色**，逐渐瘦成装配/导航/跨端宿主及原生 adapter 容器 | 上述四项；不让它被其他四项反向依赖 |
| `:androidApp` | Android Application/Activity、系统集成与 debug 入口 | `:shared`；debug 专用需要时显式依赖 UI，不进入 release API |

无环方向：

```text
:androidApp -> :shared -> :ui -> :design-system
                 |         |
                 |         +----> :business
                 +----> :data ---> :business
                 +--------------> :business
```

`:business` 初期是聚合模块，不等于把所有功能文件混进一个包。只有当某功能确有独立负责人、构建成本或发布边界，再细分 `:feature:schedule:domain` / `:feature:schedule:presentation` 等；**先解决源码互相知道太多的问题，不追求模块数量**。

### 5.3 三个必须先处理的消环点

1. **contracts 与 adapter 不再同文件**：例如将 `AcademicSource` 留在业务，把接受 HttpClient 的 factory 放 data/装配；把 `RunUploadTransport` 和 `RunRepositoryTransport` 分归业务和 data。接口返回有意义的 typed model，不用 `Any`、`Map<String, Any>` 逃避依赖。
2. **平台不是一整块底层库**：[位置接口](<../shared/src/commonMain/kotlin/io/github/betterzslf/platform/PlatformCapabilities.kt#L31-L50>) 同时含 Compose 权限入口和依赖跑步模型的 tracker；[课表原生发布模型](<../shared/src/commonMain/kotlin/io/github/betterzslf/platform/ScheduleNative.kt#L3-L56>) 又依赖课表领域。让所需 capability port/message 归业务，native 实现归装配侧；Composable 原生 view 通过 UI 侧约定的 slot/renderer 注入。暂不额外建立一个万能 `:platform` 模块。
3. **公共文案不制造回环**：[AppText](<../shared/src/commonMain/kotlin/io/github/betterzslf/core/text/AppText.kt#L3-L55>) 集中显示文案，也混有精确协议标识。抽 DS 时，通用组件用明确的标签参数或 DS 自有通用文案；功能文案留功能展示侧。业务错误可暂保留纯字符串以控制迁移量，后续按需转为错误码/消息键。不要为了“纯净”一次性重写全部文案与 golden。

持久化同样需要先消环：`KeyValueStore` 这类能力口可归 business，但不要把具体存储 adapter 搬去 data 后仍让 runtime 直接构造它。先定义所需的 typed store/repository port 并由装配注入，或者在首批将纯 Kotlin 的 runtime+持久化状态机作为同一个未拆内部单元保留；版本编码/加密介质与设备存储实现留 adapter。对队列的 durable checkpoint 顺序不作机械改写。

物理放置也应明确：示意中的 `feature/schedule/domain`、`application`、纯 `presentation` 放 `:business`，`data` 放 `:data`，`ui`/绑定入口放 `:ui`，fixtures 放测试或 debug 源集。包路径保留功能归属，不代表各层文件最终仍在同一个 Gradle 目录。UiState 使用的消息键/错误语义归业务展示契约，资源解析留 UI；DS 的通用标签通过参数或自身定义解决，不能由 DS 反向依赖 app。初期若纯同步的旧文案暂留 `:business`，也不能让它成为其他组件传入业务能力的借口。

### 5.4 KMP 与版本约束

- 继续保留 Android/iOS 共享 UI；不因分层复制两套页面。JVM 仍然是宿主测试目标，不新增桌面产品。
- iOS 仍由现有 `Shared.framework` 和入口包装对外，内部模块只在确需 Swift 直接消费其类型时有选择地 export；不要无差别导出全部依赖。
- Standard/Pro 目前是**编译输入选择**，不是 UI 隐藏开关：[shared 配置](<../shared/build.gradle.kts#L9-L39>) 与 [sourceSets](<../shared/build.gradle.kts#L60-L91>) 排除 Pro 协议/UI/依赖。新模块必须显式继承同样的版本选择，不能把排除规则遗留在旧模块。
- 多模块迁移会碰到 Kotlin `internal` 的模块可见性：优先保持功能内部文件一起迁移，跨边界只提升必要的窄 API；不要为编译通过批量改成 public。

## 6. 用什么契约让三类工作真正能并行

### 6.1 页面契约：状态 + 事件，不传全局容器

建议采用如下形状；下段是**报告中的接口草图，未写入源码、也未验证为可编译代码**：

```kotlin
// 类型属于首页展示契约；不携带 Repository、HttpClient、token 或 Compose Color。
data class HomeUiState(
    val dateLine: String,
    val subtitle: String,
    val hero: HomeHeroUiState,
    val rows: List<HomeCourseRowUiState>,
)

sealed interface HomeAction {
    data class OpenCourse(val selection: CourseSelection) : HomeAction
    data object OpenTimetable : HomeAction
    data object OpenBinding : HomeAction
    data object RequestSync : HomeAction
}

@Composable
fun HomeContent(
    state: HomeUiState,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
)
```

实际实现时这些草图类型需一并定义；不要生搬成补丁。契约的价值在于：业务负责人负责真实输入到状态的映射，布局负责人用固定状态完成页面，组件负责人更新视觉实现，三方不需要同时改全局 graph。

具体约定：

- **UiState 用不可变快照**。模式、错误、空态应明确，不能用“数据为 null”同时代表未绑定、加载中、失败和无课。
- **业务选择 ID 与路由分开**。Content 发 `OpenCourse(selection)`；Route/导航层把它转换为现有 `Route.Page`。不在每个卡片里拼路由字符串，也不因本次分层重写导航引擎。
- **颜色使用语义，不进纯状态模型**。例如用课程色种子/色系键、`HeroMode`、语义 tone，由 UI 映射成主题颜色；复杂计算确实需要 Compose 类型时留在 UI 而非业务。
- **不要复制两套持久状态**。Runtime 保持真实业务状态，Presenter 只做投影和交互状态；不是把 Runtime 的每个字段镜像成一个可随意改写的 `mutableStateOf`。
- **不把所有交互都强制走全局事件总线**。Services 这类静态目录可以直接传窄回调。重试、异步提交、权限、导航结果有明确归属时再经 Presenter/效果通道。
- **一次性效果有交付策略**。导航/系统权限不能因重组或重新订阅重复执行；若必须跨短暂离开保留，给效果 ID 并确认消费。状态不会因一个无人订阅的瞬时事件而永久卡住。
- **安全边界不靠 UI 的 enabled**。Presenter/业务命令再次校验 owner/revision、busy 和可执行条件；按钮禁用只是体验，不是授权。

### 6.2 数据契约：typed port，而不是换个名字返回 JSON

现有 [ProfileSource / BoundProfileSource](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/profile/ProfileModels.kt#L26-L32>) 的 typed 读取与绑定能力，可作为其他功能的参照。反例是 [ContentSource](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/content/ContentSource.kt#L7-L14>) 虽然可注入，仍暴露 `JsonElement` 且与 repository adapter 同文件；[ArticleController](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/content/ArticleController.kt#L30-L45>) 仍要知道 `articleDetails` 结构。

目标不是新建泛型万能仓库，而是逐功能改成“读取文档得到文档模型”“读取校园网状态得到状态模型”。保留协议测试，解码责任逐步下沉到 data。JSON 并非全项目禁用：adapter/存储/导航序列化当然可以用；**禁止的是让业务或 UI 依赖上游 payload 的字段形状**。

会话则只给业务所需的 typed identity、有效性检查与受控能力。不要把 [Session 中的 token 与 JsonObject](<../shared/src/commonMain/kotlin/io/github/betterzslf/core/model/Session.kt#L8-L22>) 当作通用 UI 状态继续向下传。

### 6.3 视觉契约：token + 组件行为 + 状态样本

每个被多页面使用的组件，至少说明：

1. 输入与输出（含 loading / disabled / error）。
2. 最小触控区域、语义角色、键盘与屏幕阅读器行为。
3. 深浅色/动态色及不透明回退，不由页面自己拼颜色组合。
4. 尺寸约束、换行、长文案、大字体、滚动与 inset 责任。
5. 稳定的 fixture / test tag，便于回归而不绑死源文件文本。

不要抽象出 `color1/color2/height2/leftPadding3` 这样的无语义参数。也不要允许布局调用方通过万能配置对象覆盖全部内部样式，最后重新产生逐页分叉。

## 7. 渐进迁移：建议按这些小批次推进

所有条目都是**未来实施建议**，本轮仅写报告。每批保持可单独回滚，不把旧实现删除与大量行为变化捆绑。

| 批次 | 范围 | 做什么 | 完成门槛 |
| --- | --- | --- | --- |
| M0 基线与契约 | 文档、已有测试、当前行为 | 固定页面状态表、运行时 owner、路由/版本约束；记录基线测试结果 | 谁拥有状态、谁清理资源、什么属于本次行为变更都有书面结论 |
| M1 热身 | Services | 去掉未使用 graph，改为服务目录状态/能力与语义导航回调；保留入口和禁用逻辑 | 假数据可独立渲染；不创建 graph；Standard 禁用项与五 tab 可达性不变 |
| M2 正式 UI 试点 | Home | 提取 Route、纯投影/Presenter、Content 和局部视觉组件；暂不升级 MIUIX | 八态及不可用原因可独立展示；改变主题/布局无需改 runtime；真实输入和 fixture 共用契约 |
| M3 通用 UI 归位 | 共享组件 | 从通用 UI 分离 JSON helper、异步业务加载、导航结果；明确 Insets/主题职责 | DS 不依赖 ApiResult/JsonElement/graph；页面仍遵循同一外壳策略 |
| M4 一条业务纵切 | 一个已 typed 的读取功能/模型 | 先抽不涉 Pro 的模型与 ports；Profile typed read 可参考，decoder/resource 权限先厘清 | adapter 与业务间无反向引用，迟到结果/会话测试保持，返回 typed model |
| M5 首批物理边界 | business、data、shared façade | 搬已消环的代码与测试；保留网络/认证/SessionStore 内部实现整体；逐个迁移 runtime 的窄依赖 | 无环、可见性受控、expect/actual 成对、双版本不串类 |
| M6 复杂交互扩展 | 教务绑定 → 文档 native UI → 其他功能 | 先长表单与 revision 安全契约，再 native view slot；跑步/蓝牙等高副作用最后 | 跨页/重建/取消/设备生命周期不变，各功能有状态矩阵 |
| M7 可选 UI 模块 | DS、UI | 在逻辑边界稳定后物理抽出；最后才做 Glass/大视觉改版 | UI 层对数据实现无依赖；视觉迁移与业务重构能分开 review/回滚 |

M4–M5 可以先抽很小的稳定核心，再逐功能扩展；**不要为了 M5 一次性把全部 feature 迁入新模块**。同一模块内允许暂时存在清晰标记的 legacy 路径，已迁移路径不得新增对它的依赖。

### 7.1 为什么 Services 热身、Home 正式试点

- **Services** 当前只有约百行，graph 未用，风险低，适合验证分工和签名方式；但没有复杂异步状态，不能凭它宣布全项目解耦成功。
- **Home** 同时含真实课表输入、时间推进、八态投影和丰富布局，且已有纯投影测试，能验证“数据、展示和视觉分别改”的实际效果。首批不碰跑步上传或蓝牙，避免把不可逆副作用带入结构调整。
- **教务绑定** 是下一条长表单验证线：[ScheduleBinding](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/schedule/ScheduleBinding.kt#L49-L66>) 已按 owner 创建 draft、取消验证，并在 [验证与确认流程](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/schedule/ScheduleBinding.kt#L111-L145>) 检查 revision。抽 Presenter 必须保留该门禁，不是只搬 TextField。
- **Document** 适合验证平台 UI slot，但不是最简单首刀：[ContentPage](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/content/ContentPage.kt#L49-L80>) 有 Controller 装配、刷新、HTML 原生面板与固定高度，接口设计不能只停在“传一段 HTML”。

### 7.2 Home 不能只机械搬出已有 homeView

目前 [HomePage 调用](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/HomePage.kt#L54-L58>) 只把 snapshot、periods、week、now 交给投影，而 [ScheduleState](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/schedule/runtime/ScheduleState.kt#L9-L30>) 还含 boundAccount、busy、reauthenticating、needsCredentials、storageFailure。当前 [homeView](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/HomePage.kt#L174-L188>) 在 snapshot 为 null 时直接归为 UNBOUND；虽然枚举有 UNSYNCED，也不能据此认为真实输入已经覆盖所有原因。

因此试点要分成两个可审查提交：

1. **结构提取**：锁住当前输出，抽 Route/Content/投影，不“顺手修”状态规则。
2. **状态契约修正**：单独补齐“未绑定 / 已绑定未同步 / 读存储失败 / 重认证 / 有缓存刷新失败”的输入与优先级，产品确认后补测试再改显示。

对八态中“明天第一节”的语义也要明确：[firstAfterToday](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/HomePage.kt#L164-L167>) 与 [DONE_TODAY 分支](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/HomePage.kt#L234-L243>) 实际寻找窗口内任一未来日的第一节，不保证就是明天。迁移不得悄悄改变它；应以明确日期/下一次文案或确切的“明天”过滤形成独立行为决议。

### 7.3 每个迁移 PR 应交付什么

- 一个可解释的职责变化，以及改前/改后的依赖图。
- 原有行为测试保持；新增边界测试与相应状态 fixture。
- 无行为变更或明确列出行为变更，不能笼统写“重构优化”。
- 旧接口的删除/兼容期限，避免双实现长期运行。
- 可回滚路径：同一功能每次只由一个生产入口持有 owner；禁止新旧 runtime 同时启设备/写请求。

## 8. 拆分时的红线：这些不是“顺手整理”的细节

### 8.1 会话与写入语义不能被简化掉

- [ApiSessionBinding](<../shared/src/commonMain/kotlin/io/github/betterzslf/core/net/ApiSessionBinding.kt#L5-L21>) 是属于某个 ApiClient 的不透明能力，捕获 owner+token；不是随时读取全局 token 的普通 DTO。保持 [原子捕获](<../shared/src/commonMain/kotlin/io/github/betterzslf/core/net/ApiClient.kt#L48-L57>) 与 [迟到 401 不清新登录](<../shared/src/commonMain/kotlin/io/github/betterzslf/core/net/ApiClient.kt#L235-L244>) 的约束。
- 取消协程不代表旧响应永远不会到达。保留每个写存储/发布/UI 暴露边界的 owner、token、generation/revision 校验；不能用一个 `isLoggedIn` 布尔替代。
- [RunSendResult](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/run/RunUploadWorker.kt#L11-L20>) 的 Accepted / NotAccepted / Unknown 语义必须保留。超时、取消、解码失败或本地确认保存失败不等于服务器未执行，不能统一自动重试或换端点。
- 新 UI 只能表达真实状态。打开系统设置不是已授权，API 接收不等于最终成绩，面板 ready 不等于业务操作成功。

### 8.2 生命周期与存储清理要有唯一 owner

- [RunRuntime](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/runui/RunRuntime.kt#L36-L51>) 明确是应用级；[ScheduleRuntime](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/schedule/runtime/ScheduleRuntime.kt#L20-L29>) 也是应用范围。页面切换/横竖屏/宽度变化不能新建它们，不能让 Content 的 dispose 关闭全局任务。
- 页面级 Controller 的 dispose 仍由 Route 负责。局部 preview 不应继承真实 app 的传感器/网络 owner。
- [清理本地数据](<../shared/src/commonMain/kotlin/io/github/betterzslf/App.kt#L149-L175>) 已有“先停止活动及更新、再清课表/会话/存储”的顺序；抽成服务时保持等待与失败语义，不改成 fire-and-forget。
- 校园登录与教务课表绑定不是同一身份。[logout](<../shared/src/commonMain/kotlin/io/github/betterzslf/App.kt#L141-L147>) 明确保留课表，[课表 slot 说明](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/schedule/runtime/ScheduleRuntime.kt#L58-L68>) 也明确不是校园 userId。不要因“统一 session”把它们合并。
- 不因 DI 统一而合并认证域的 HTTP client：学校 token、教务 cookies、公共下载/GitHub、校园明文 allowlist 各有边界。装配层可以管理多个 owner，但不等于用一个带全局拦截器的万能 client。

### 8.3 模块可见性与工厂不能机械搬迁

[ApiClient 的绑定检查](<../shared/src/commonMain/kotlin/io/github/betterzslf/core/net/ApiClient.kt#L56-L57>)、[SessionStore 的条件清理](<../shared/src/commonMain/kotlin/io/github/betterzslf/core/storage/SessionStore.kt#L38-L41>) 和 [ProfilePhotoMetadata.Resource](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/profile/ProfileModels.kt#L17-L23>) 都有 `internal` 保护。原本同一个 compilation 内的访问在新模块会失败。

初期把 net、SessionStore、认证仓库及其绑定适配整体放在同一 data/infra 实现模块，跨边界暴露受控业务能力；资源构造和头像读取也先维持同一安全边界。不要为解决编译错误公开 token、资源 URL 或底层状态。测试 fixture 的跨模块可见性要单独设计，不应逼迫生产内部 API 公共化。

`expect` 与其 `actual` 必须在正确的 KMP 模块/源集层级配对。业务可拥有普通接口，`createLocationTracker` 等 expect factory 和 actual 初期留在共享 façade，由 composition root 注入；不能把 expect 搬去 business 而 actual 留在无关模块。

另一个消环前置是导航：本报告的 `HomeRoute` 指页面绑定 Composable，不等于当前 [sealed Route](<../shared/src/commonMain/kotlin/io/github/betterzslf/App.kt#L181-L207>) 导航键。未来 `:ui` 不可反向 import `:shared` 中的 AppGraph/导航实现；通过语义回调转接，或把确需跨模块的窄导航契约放在双方可依赖的位置。第一阶段保持已序列化键、存储 schema、五栈和返回保护不变，不能因为重命名类而静默破坏恢复。

### 8.4 静态审计发现的待验证风险，不混入视觉重构

| 风险 | 证据与判断边界 | 后续独立验证 |
| --- | --- | --- |
| 原生课表打开请求的身份口径不一致 | [AppGraph](<../shared/src/commonMain/kotlin/io/github/betterzslf/App.kt#L61-L103>) 使用 `local-timetable`；但 [原生请求处理](<../shared/src/commonMain/kotlin/io/github/betterzslf/App.kt#L257-L263>) 先要求校园 session，再消费请求，并比较 slot owner 与校园 userId。静态条件存在明显不一致，不是已完成动态复现 | academic-only 冷启动、校园登录后的 native 点击、旧 revision 请求；验证何时消费、何时拒绝、如何保留请求 |
| 首页输入不足以区分所有不可用原因 | 见第 7.2 节；目前 null snapshot 就归 UNBOUND | 为已绑定未同步、存储错误、缓存刷新失败分别补输入/输出测试 |
| Portal 的晚到结果门禁不足 | [CampusPortalController](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/campusnet/CampusPortalController.kt#L85-L105>) close 只置 alive，方法开头检查 current；[多步 connect](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/campusnet/CampusPortalController.kt#L131-L195>) 的挂起点后继续发布/调用。需核对取消与会话变化，不直接断言已发生越权 | redirect/probe/dial/read 分别挂起后 close 或切换 session，断言无旧状态暴露/不继续后续副作用 |
| lazy 网络 owner 的关闭责任未集中 | [AppGraph lazy 实例](<../shared/src/commonMain/kotlin/io/github/betterzslf/App.kt#L73-L87>) 有三个 Ktor owner，[close](<../shared/src/commonMain/kotlin/io/github/betterzslf/App.kt#L178>) 未显式逐项调用；各自存在 [probe close](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/campusnet/KtorPortalProbe.kt#L107-L110>)、[handshake close](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/campusnet/KtorDormHandshake.kt#L96-L99>)、[transport close](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/campusnet/KtorDormTransport.kt#L87-L90>) | 列出所有 client/derived client/engine 所有权，验证 close 幂等及最终释放；本轮未测泄漏量或用户可见故障 |

这些问题说明为什么需要职责拆分，但**不意味着本轮获准修复**；后续也应单独建行为修正任务，不藏进移动文件的 PR。

涉及凭据时还有一条特别约束：[教务绑定 draft](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/schedule/ScheduleUiModel.kt#L22-L38>) 的密码仅驻留未保存的表单内存，不放入可序列化公共 UiState、rememberSaveable、路由或 fixture/诊断日志。这里指“未确认草稿不持久化”，不应误写成应用从不保存已确认的教务凭据；确认后的持久化记录确实包含凭据，见 [SchedulePersistence](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/schedule/runtime/SchedulePersistence.kt#L15-L22>) 及 [原子保存](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/schedule/runtime/SchedulePersistence.kt#L80-L90>)；其注释约定由平台加密存储承载，本轮未对加密实现另做安全审计。

## 9. 开发分工与规范治理：不只拆代码，也拆改动入口

### 9.1 推荐协作流程

**契约先行 → 假状态并行 → 真实绑定 → 分层验收**：

1. 功能负责人先给状态表、事件、失败/缓存策略与安全约束。
2. 业务/数据负责人对着 typed port 与 contract test 工作，不改页面几何。
3. 布局负责人用 fixture 组合 Content，不等待真实账号/设备，也不临时写 mock API 到生产页面。
4. 组件负责人维护 token、MIUIX 适配、通用交互/材质；不在各页面分别修同一种按钮。
5. 集成人员把真实 Presenter/runtime 接入，核对生命周期和副作用，最后做系统与视觉验收。

| 工作类型 | 主要可改范围 | 需要共同评审的边界 |
| --- | --- | --- |
| 上游 API/存储适配 | 功能 data、解码、协议测试 | typed port 变更、安全与错误语义 |
| 业务/展示状态 | 功能 runtime/presentation、投影测试 | UiState/事件、owner 与清理责任 |
| 页面布局 | 功能 Content、局部卡片、状态画廊 | 事件语义、系统 inset、复杂无障碍 |
| 视觉实现 | theme、token、共享组件 | 组件 API、跨页面回归、材质降级 |
| 应用集成 | composition、navigation、宿主/edition | 生命周期、恢复、双版本/跨端 |

一人也可以兼任多种角色，重点是一次改动的职责和验收分开。多 Agent 开发时同理：把输入契约写清、分配不重叠文件范围，不能让布局 Agent 和业务 Agent 同时编辑同一个数百行页面，再靠合并冲突决定业务逻辑。

### 9.2 给公共入口设“扩容门槛”

- 新公共组件要回答“至少哪些页面复用/哪个项目级策略需要统一”；只在单功能使用的先本地保留。
- 新全局状态必须说明 owner、生命周期、失效键、订阅者与清理方式。
- 新跨功能依赖先判断是共享业务能力还是仅导航。首页依赖课表只读投影是合理的；依赖课表整个 UI 包来拼路由则应收敛。
- 避免 `Utils`、`CommonComponents`、`AppServices` 无限增长。拆分不是把原来的巨型文件换个名字。

### 9.3 先澄清设计决策的权威与历史状态

目前设计规范有历史叠加，而不仅是代码耦合：

- [DESIGN 开头与决策顺序](<../DESIGN.md#L3-L24>) 写 MIUIX 优先、Ardot 为层级意图；[DESIGN-TODO 的更正](<../DESIGN-TODO.md#L82-L86>) 又记录用户曾要求 Ardot 第一优先。不能让不同开发者各挑一句作为永久规则。
- [DESIGN 现行导航](<../DESIGN.md#L38-L45>) 与 [源码策略](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/NavigationLayoutPolicy.kt#L3-L22>) 已是五 tab，但 [后段流程与清单](<../DESIGN.md#L393-L425>) 仍有“四栈”字样。
- [TODO 的 1.4](<../DESIGN-TODO.md#L108-L120>) 记“课后隐藏卡”，随后 [1.5](<../DESIGN-TODO.md#L125-L143>) 明确改为八态且课后保留。它是历史记录，不应再被视为并列的当前验收标准。

建议后续另设轻量决策记录：每个主题只有一条“当前生效决策”，包含版本/日期/状态、取代了什么、影响哪些页面和验收证据。**规范管当前规则，TODO 管任务进展，历史变更只作背景**。由产品/设计负责人确认冲突，不让执行者擅自重新选方向。

本轮不修改既有规范，也不认定任一历史优先级永久胜出。架构分层本身应能支持已确认视觉方向，而不是替用户更换视觉方向。

### 9.4 把预览从“整机 mock”升级为两层工具

现有 [MockPreviewActivity](<../androidApp/src/debug/kotlin/io/github/betterzslf/MockPreviewActivity.kt#L25-L90>) 值得保留：它替换内存存储与 HTTP，并走真实 AppGraph 进行集成样例。但它仍委托基础 PlatformServices、创建整个 graph、通过异步验证装课表；**不是纯 Content 预览，也不能仅据注释视作全部原生能力均被禁用的沙箱**。

建议区分：

- **状态画廊**：直接喂 UiState，固定 now，无 graph/HTTP/凭据/原生副作用；用于布局、主题、大字与八态对比。
- **集成 mock 宿主**：保留真实 Route/Controller/adapter 协作；网络、存储、定位、蓝牙、系统设置和外部链接全部显式替换或禁止；用于验证接线而不是验证真实服务。

两者都不能代替真实平台验证。只用固定“成功”数据会漏掉空态/失败；只 mock HTTP 也不足以证明不会启动 native capability。

## 10. 怎么判断真的解耦成功

### 10.1 四层验收，不能互相替代

| 层次 | 最低要求 | 不能据此声称什么 |
| --- | --- | --- |
| 架构/依赖 | 已迁移 Content 不 import graph/repository/runtime；DS 不 import feature；business 不 import Compose/MIUIX/Ktor；模块 DAG 无环 | 包名正确不代表运行行为正确；import 检查也查不出所有间接能力泄漏 |
| 纯状态/事件 | 固定输入和 now 得相同 UiState；每个动作映射到正确意图；fake source 验取消、过期、重复点击 | 逻辑测试通过不能证明截图、触控或平台材质正确 |
| 视觉/无障碍 | 同一 Content 跑状态画廊、窗口/字号/主题极端组合；验证语义和操作可达 | 一张标准屏截图不能证明全部状态、真实系统 inset 或帧率 |
| 平台/集成 | 双版本、Android/iOS、冷启动/重建/切账号/权限/原生 view 生命周期与真实能力回归 | mock 或 JVM 反射不能替代平台编译和真机结果 |

建议把依赖规则变成后续 CI 守卫：第一步只对迁移完成的路径启用新规则并记 legacy 基线，第二步禁止新增违规，最后逐项清零。只禁止整个文件名、只检查函数叫 Content，或把违规移到辅助函数，都不算验收。

### 10.2 建议保留和迁移的现有测试

以下只确认源码存在和测试意图，**本轮全部未执行**：

| 现有入口 | 迁移中要守住什么 |
| --- | --- |
| [RepositoryContractTest](<../shared/src/commonTest/kotlin/io/github/betterzslf/data/RepositoryContractTest.kt#L24-L109>) | 真实 repository method 的 path、query/body、method、auth、timeout；不是只对 endpoint 枚举自比 |
| [ApiSessionBindingTest](<../shared/src/commonTest/kotlin/io/github/betterzslf/core/net/ApiSessionBindingTest.kt#L38-L146>) | 跨 client 绑定拒绝、过期绑定零 HTTP、挂起时不换 token、迟到 401 不误清新会话 |
| [RunRuntimeTest](<../shared/src/commonTest/kotlin/io/github/betterzslf/feature/runui/RunRuntimeTest.kt#L174-L245>) | 并发 close 等待、存储失败仍停 native、ack 保存失败不自动重试、构造/读取不启动设备 |
| [HomeViewTest](<../shared/src/commonTest/kotlin/io/github/betterzslf/ui/HomeViewTest.kt#L33-L169>) | 八态主体与超过一小时的换教室倒计时；不能误称所有运行态原因均已覆盖 |
| [NavigationLayoutPolicyTest](<../shared/src/commonTest/kotlin/io/github/betterzslf/ui/NavigationLayoutPolicyTest.kt#L10-L80>) | 600dp 切换、五栈对象身份、重选/尺寸变化不清历史 |
| [SessionNavigationTest](<../shared/src/commonTest/kotlin/io/github/betterzslf/navigation/SessionNavigationTest.kt#L10-L38>) | 登录、可离线页面、公开文档及 Standard/Pro 守卫 |
| [EditionCompilationTest](<../shared/src/jvmTest/kotlin/io/github/betterzslf/edition/EditionCompilationTest.kt#L5-L40>) | Standard 的 JVM classpath 真无 Pro 类型；仍须另验 Android/iOS 源集、依赖与导出 |

现有 [公共构建 workflow](<../.github/workflows/build.yml#L22-L54>) 明确只构建 Standard Android，并注释其他验证在另一处；本轮未审计或运行注释所指的外部流程。因此不能把“仓库已有 CI”当成双版本/跨平台完整覆盖。后续在受控环境分别记录命令、版本、结果与未跑项。

未来实施时，现有 JVM/Android 验证命令可从 [构建指南](<../BUILDING.md#L24-L46>) 起步，显式选择 Standard 和 Pro；新增模块后任务路径和测试归属需要同步调整。iOS 编译必须在具备所需工具链的 macOS 进行。本轮没有运行任何这些任务。

### 10.3 Home 试点的最小状态与效果矩阵

- **八态**：A 课前；B 15 分钟内；B 换教室且超过一小时；C 上课中；G 两小时以上长空档；D 已上完且保留今日历史；E 今天无课但窗口内有课；H 窗口内无课；F 未绑定/已绑定无缓存/部分数据/校历不足/存储失败。
- **附属状态**：缓存+刷新、缓存+错误、凭据失效、选择历史学期/周；明确首页用当前源还是选中历史 snapshot。
- **时间边界**：开课、下课、午夜、学校时区、明天无课而后天有课；时钟显式注入，禁止靠截图拍摄当时的时间碰运气。
- **事件**：打开课程、周表、绑定；“重新同步”到底是跳转入口还是发请求。[当前动作](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/HomePage.kt#L535-L560>) 只是跳转课表根路由，应在契约中明确，不能因按钮文案猜测实现或悄悄增加请求。
- **副作用次数**：重组零请求；导航/权限无重复；重试有明确起点；无效 owner/revision 不继续写；受保护业务返回不绕过确认。

### 10.4 视觉矩阵采用分层抽样，而不是假称全组合

沿用 [当前视觉验收基线](<../DESIGN.md#L416-L431>)：320 / 390 / 600 / 840dp、字号 100% / 130% / 200%、浅/深/动态色、短横屏、IME、手势/三键导航及挖孔安全区。

先让八态在标准条件完整跑通，再选择 B/C/F、长课程名、长错误、末项操作做极端交叉；报告逐项标明已验/未验，不宣称完成全部笛卡尔积。保持：

- 首页不加重复顶栏，布局重组不改变五栈 owner。
- 底部占位遵循 [PageList 的 max 策略](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/Components.kt#L103-L122>)，不把导航 inset 机械叠加两次；键盘不遮主操作。
- 普通文字/大字/必要图形按现行规范检查最终合成对比度；状态不只靠颜色，触控可达，重要信息大字下不丢失。
- 地图/HTML 的 ready 必须是实际 native 回调；unsupported、权限拒绝与释放各测；以后 Glass 与 Opaque 同场景比较，不因结构拆分延期无限不做视觉目标，也不与第一刀混做。

## 11. 如果现在只做五件事，我建议这样排

1. **确定名词与责任表**：把“后端”改称业务/数据/设备运行层；把“UI 布局”定义为 Content，把“UI 实现”定义为 DS+功能内视觉实现；注明哪个 owner 管生命周期。先消除沟通歧义。
2. **Services 去全局依赖做热身**：工作量较小，验证布局只依赖状态和回调；不借机增删入口。
3. **Home 做完整纵切**：工作量中等，拆绑定/投影/内容、补八态 fixture；结构迁移与状态修正各自验收。这一步能证明是否真可以并行开发。
4. **给通用 UI 和新增路径设依赖守卫**：工作量中等，把网络/JSON/导航混入 DS 的路径止住，旧代码逐步偿还。不要先全仓强制整改。
5. **达标后才拆业务/数据物理模块**：工作量较大，难点是会话能力、internal、expect/actual、source selection 与测试归属，不是新建 Gradle 文件。先四模块，UI 物理隔离可后置。

不提供未经测量的“编译快多少”“几天全迁完”承诺。用每个小批次的验收结果估算下一批，而不是按文件数平均分摊。

### 建议持续追踪的少数指标

- **边界违规数**：迁移区域的 graph/repository/协议访问必须为零，legacy 数量只减不增；17 个 UI graph import 文件只是起始扫描指标，不等于最终风险总数。
- **改动扩散范围**：一次组件/布局/协议变化，是否只动对应职责及其测试；跨边界的改动应有显式契约版本理由。
- **无环境渲染能力**：首试点的全部状态能否在不提供账号、HTTP、真实设备的情况下展示。
- **安全回归**：切账号/过期/取消/重复点击/Unknown 写入结果/清理顺序是否有测试和实际平台记录。
- **协作冲突**：同一大页面的多人冲突是否减少，而不是把冲突转移到一个万能 UiState 或公共组件文件。

**最终建议一句话：同仓库里先把“拿数据、做决定、排页面、画组件、装应用”五种改动分开，再用模块锁住已经验证的边界；不要先拆仓库，再让新仓库隔空互相耦合。**

## 12. 本轮交付与限制

- 本报告按章节多次小批写入，保留已完成内容后再追加，未用一次长写入覆盖全文。
- 本轮唯一新增文件是[本报告](<architecture-decoupling-review-2026-11-17.md>)；没有修改现有源码、Gradle、脚本、设计规范和测试，也没有提交或暂存 Git 变更。
- 只读审计结合主审与两个独立方向复核；数据/平台和 UI 审计均已完成并回传证据。未读取本地凭据配置，未审查外部后端仓库或全部参考/反编译目录。
- 未执行构建、测试、安装、应用运行、网络业务请求、设备操作或视觉截图。所有“目标结构”“未来验证”均非已完成实现；风险登记为静态证据与待验证条件，不冒充动态故障报告。
- 开始时工作区已有未跟踪的脚本、日志与 reports 内容；本轮不清理、不覆盖，也不将这些既有内容归为本次产出。

### 交付静态核对结果

- 已核查 87 处本地 Markdown 引用，文件存在且行号范围有效；0 个失效目标。
- 12 个编号章节齐全，4 组代码围栏成对；接口/目录/DAG 都明确标为建议，不作为实施完成声明。
- Git 已跟踪文件的工作区 diff 与暂存 diff 均为空；未跟踪清单与初始清单比较，仅多出本报告。已有未跟踪文件仍保留。
- `git diff --check` 无输出；该命令不覆盖未跟踪报告，因此另行做了上述引用/结构核对，不把它当成报告内容测试或源码测试。

