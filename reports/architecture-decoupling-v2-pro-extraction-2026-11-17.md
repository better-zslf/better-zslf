# 架构拆分 V2：激进模块化与 Pro 源码迁出方案

> 项目：better-zslf / Oh My ZSC  
> 日期：2026-11-17  
> 状态：报告已完成；已分批落盘并完成静态审稿/链接核对，未开始任何代码迁移。  
> 约束：本轮只读项目和参考资料，仅写 Markdown；不修改源码、配置、CI、权限、Git 历史或现有仓库结构，不执行构建/测试/发布。

## 1. 本轮要求改变了什么

用户明确允许激进拆分和大规模重构，并要求 **Pro 功能代码后续解离到 `pro-ci` 仓库**。这不是上一轮“先保守收敛”的补充细节，而是新的架构约束。

本报告是当前推荐方案，取代 [第一版报告](<architecture-decoupling-review-2026-11-17.md>) 中“少量聚合模块可能足够、Pro 暂留原 source selection”的长期目标；第一版的源码证据、状态/生命周期风险和不改代码的事实仍有效。**允许大量重构，不代表允许改变安全语义、假定协议成功，或在本轮开始改代码。**

### 新结论

1. **不再把 `:shared`、`:business`、`:data` 三个大块视为最终结构**。以功能为垂直边界，关键依赖以 API / domain / data / presentation / UI 模块隔离；共享基础设施按能力拆，不按“前端/后端”笼统归类。
2. **Pro 分仓是目标，不是可选优化**。`pro-ci` 从“测试/发布控制仓库”升级为“Pro 产品源码、装配和交付仓库”；主仓保留公共基座与 Standard 产品。
3. **采用编译期组合，而非源码覆盖**。公共模块不引用 Pro 类型，不探测私有目录，不复制私有源码进公共 sourceSet；Pro 在自己的根构建中依赖公共模块。
4. **业务逻辑、UI 布局和视觉组件建立真实编译边界**。页面可仅用状态样本独立开发；真实运行时通过窄依赖注入，组件不承担业务请求。
5. **激进的是目标边界和改造深度，不是一次性切换所有功能**。允许成批重写 AppGraph、导航、edition façade、模块/CI 接线，同时用阶段性集成门槛保证可追踪、可回滚。

## 2. 进一步核实的事实与范围

### 2.1 主仓的现状

静态证据基于主仓本地 HEAD `1674115e7ccfc574313647d1e4c646903601c4c5`；报告中的行号对应本次读到的文件，不保证后续重构后仍有效。

- [共享构建](<../shared/build.gradle.kts#L9-L39>) 当前 edition 默认 **pro**；Pro 排除表证明实现不仅在 `proMain`，大量仍在公共源集。[sourceSets](<../shared/build.gradle.kts#L60-L91>) 通过 edition property 改编译输入，并条件引入 Kable。不能把旧说明里的“默认 Standard”当作这次源码事实。
- [ProEditionFeatures](<../shared/src/proMain/kotlin/io/github/betterzslf/edition/ProEditionFeatures.kt#L24-L64>) 负责惰性水控、登录/首页入口和 Pro 页面，但仍接收全局 AppGraph 与公共路由。
- [版本目录](<../gradle/libs.versions.toml#L1-L14>) 固定 Kotlin/Compose/AGP/MIUIX 等版本。本轮做架构分析，不顺带要求升级这些库。
- [主仓发布流程](<../.github/workflows/release.yml#L214-L223>) 的 Pro 通知目前被硬编码 `false` 禁用。用户要求迁源码 **不等于授权恢复自动发布/通知**。

### 2.2 找到了本地 pro-ci，但不等于确认远端现状

在工作区内发现 [pro-ci 本地说明](<../work/pro-ci/README.md>) 对应独立 Git 检出：本轮只读检查得到本地 HEAD 为 `80481a9`，最近提交日期 2026-10-28，工作区无本地 diff。本轮没有 fetch/pull，也没有调用 GitHub 仓库 API，**以下关于 Pro CI 的观察仅代表该本地快照**。

该快照的 [职责声明](<../work/pro-ci/README.md#L3-L9>) 和 [架构说明](<../work/pro-ci/docs/architecture.md#L5-L19>) 仍明确“应用源码在上游、本仓不是源码 fork”。因此需要正式改变其职责：不是继续让 CI 切 edition，而是使 Pro 功能实现和 Pro app composition 成为该仓唯一生产源。

本轮未读取签名私钥、账号或环境凭据；现有 CI 中 secret 的名字是代码配置证据，不是可用凭据。未把历史 CI 成功记录当作本轮构建结果。

### 2.3 对“最优实践”的使用口径

架构没有适用一切项目的唯一最优解。这里采用的实践是：高内聚低耦合、最小公开 API、依赖倒置、功能模块化、产品根装配、单个产品单个 Kotlin/Native umbrella framework、固定源码/产物版本及隔离 CI 信任域。

已读取的官方参考：

- [Android：常见模块化模式](https://developer.android.com/topic/modularization/patterns)：功能/数据/app 模块、最小公开面、DI、优先 implementation；本文将其应用到 KMP，不照搬 Android-only 插件。
- [Android：导航模块化](https://developer.android.com/guide/navigation/navigation-3/modularize)：将导航 key 与内容实现分离。项目目前使用 MIUIX nav，**借鉴边界，不假定 Navigation 3 API 可直接替换或强制换库**。
- [Gradle：Composite builds](https://docs.gradle.org/current/userguide/composite_builds.html)：跨构建源码协同、依赖替换及隔离限制。
- [KMP：工程配置与 umbrella framework](https://kotlinlang.org/docs/multiplatform/multiplatform-project-configuration.html)、[Native 二进制 export](https://kotlinlang.org/docs/multiplatform/multiplatform-build-native-binaries.html)：多个共享模块由产品 umbrella 组合，避免重复框架/过度 export。

参考文档当前页面版本不一定等于本仓工具版本；后续实现必须按锁定工具链做验证，本轮不将文档示例视为已验证配置。

## 3. 双仓目标：Pro 是下游产品，不是公共仓的一个源码开关

### 3.1 仓库职责正式改成这样

| 仓库 | 应拥有 | 不应再承担 |
| --- | --- | --- |
| `better-zslf` | 公共能力/功能模块、设计系统、公共 app-shell、Standard Android/iOS 宿主、公共契约测试、公共构建逻辑 | Pro 实现、BLE 协议/Pro 路线编辑器、私有源码占位路径、依赖私有仓才能执行的 Standard 基线验证 |
| `pro-ci` | Pro 功能源码、Pro 私有文案/fixture/测试、Pro Android/iOS 产品根、Pro composition/umbrella、Pro CI/签名/发布、固定公共版本清单 | 复制维护整个公共应用、只给公共仓传 edition=pro、覆盖公共 sourceSet、通过私有 app 反向修改公共模块 |

依赖方向只能是：

```text
Pro app / Pro 功能（pro-ci）
          │
          ├──> 公共 app-shell / 公共 UI / DS
          └──> 公共功能 API、公开的实现工厂与平台能力
                        （better-zslf）

Standard app（better-zslf）──> 同一套公共模块
公共模块 ──X──> Pro 实现 / pro-ci 路径 / Pro 私有依赖
```

这里的“公共”指由主仓拥有的可复用部分，不是声称所有 API 应全开、所有制品必须无权限公开。Pro 仓当前文档称私有，本报告按这一现有职责设计；不代替维护者决定托管可见性或访问策略。

### 3.2 产品装配必须各自有根

- Standard 的 production root 只列公共 feature，构建不需要 Pro clone、Pro token 或 Pro 包仓。
- Pro 的 production root 列同一公共 feature 加 Pro feature；Pro 可以组合公共 UI/数据实现，**不能依赖 Standard 已打包 APK、Standard 完整框架的内部 graph，或把 Standard root 当可注入插件的巨型容器**。
- 公共 `app-shell` 只提供主题/窗口、导航宿主与槽位/注册入口，不默认创建全部功能。把当前 [AppGraph](<../shared/src/commonMain/kotlin/io/github/betterzslf/App.kt#L63-L178>) 重构为按能力注入的装配，不继续作为跨仓 API。
- 保持现有应用 identity 与签名连续：Android `io.github.betterzslf` / `io.github.betterzslf.pro`，iOS 对应已配置的 bundle；移动仓库不应造成新应用或数据丢失。版本独立策略见后文。

### 3.3 iOS 的关键区别：每个产品一个 umbrella

公共模块仍是 Kotlin Multiplatform 库，既可供 Standard 使用，也可供 Pro 从源码或制品消费。Standard 与 Pro 各有自己的 umbrella composition：

```text
Standard iOS host -> Standard umbrella -> 公共模块
Pro iOS host      -> Pro umbrella      -> 公共模块 + Pro 模块
```

不要在 Pro iOS app 同时链接一个完整 Standard framework 和一个包含同样依赖的 Pro framework。[KMP 官方说明](https://kotlinlang.org/docs/multiplatform/multiplatform-project-configuration.html) 指出多个框架可能重复依赖并造成类型/状态互不连通；这里应在 Pro umbrella 一次组合。

当前 [XcodeGen 配置](<../iosApp/project.yml#L45-L55>) 固定链接 Shared 和公共构建脚本。后续允许大改为两个产品根各自链接自己的 umbrella。对 Swift 只暴露启动/平台入口等必要 API；[export 的官方语义](https://kotlinlang.org/docs/multiplatform/multiplatform-build-native-binaries.html) 会把被导出依赖的 API 纳入 framework，可能削弱裁剪，不能把所有模块 `transitiveExport=true` 当快捷解法。framework baseName 可分别配置，但应用 bundle ID/签名/存储身份不可随意更改。

### 3.4 不采用的跨仓方案

- 公共构建里写 `if (proDir.exists())` 自动加载私有实现：同一命令因机器目录不同而产物不同，且让公共仓知道私有拓扑。
- 把 Pro 源码复制到公共目录、Git patch 覆盖公共文件、同包同名类替换：继续共享一个 compilation，编译边界虚假，追溯来源困难。
- 在运行时反射/下载 Pro Kotlin 插件：没有这个需求，iOS/权限/生命周期更复杂。这里是**静态编译期产品组合**，不是动态特性下发。
- 把整仓 fork 到 pro-ci 再长期同步：变成双主干，公共 bugfix 和 UI 规范很快分叉。
- 仅把 workflow 移走就宣告 Pro 隔离：那是当前快照已有的组织形式，不满足本轮源码迁出要求。

## 4. 激进模块目标：功能纵切，状态/布局/视觉物理隔离

### 4.1 不再保留全局 business/data/UI 巨块作为终点

推荐把复杂、独立变化的功能建成下列模块族。这里的路径是**未来结构示意，不是本轮创建的目录或可直接应用的补丁**：

```text
better-zslf/
  build-logic/                    固定版本的 convention plugins / 构建策略
  core/
    foundation/                   少量基础结果、时钟、日志等纯 Kotlin 约定
    identity-api/                 不透明会话/身份能力，无原始 token/JsonObject
    storage-api/                  必要的安全存储能力口；不做全局业务数据库
    geo/                          公共地理值类型、坐标系与纯数学
  infrastructure/
    school-access/                学校认证 + net + session store 的受控实现
    storage-platform/             Android/iOS/JVM 安全介质实现
  design-system/
    tokens/                       Compose 主题/尺寸/字体/语义角色
    components/                   MIUIX 薄适配与项目通用组件
    layout/                       Screen/PageList/Inset/窗口布局约定
  navigation/
    contracts/                    版本化导航载荷、稳定 ID 与宿主导航约定
    miuix-adapter/                MIUIX NavKey/保存恢复/entry 适配
  feature/
    schedule/{api,domain,data,presentation,ui}/
    run/{api,domain,data,presentation,ui}/
    campus-network/{api,domain,data,presentation,ui}/
    auth/..., profile/..., documents/...
    home/{presentation,ui}/       消费课表只读投影，无伪造 data 层
    services/ui/                  由产品提供服务目录与动作
    settings/..., update/...
  platform-ui/
    readonly-map/                 公共只读地图、provider 安全策略、interop
    html-document/                文档原生渲染与加载/释放合同
  app-shell/                      主题/窗口/五栈显示壳，不创建业务对象
  composition/public/             公共实现装配工厂，返回受控 entry 与 owner
  product/standard/
    composition/                  Standard 唯一产品依赖列表/生命周期根
    android-app/
    ios-umbrella/
    ios-host/
  testing/
    contract-kit/                 不依赖 app 的公开 fake/协议验证工具
    ui-gallery/                   状态样本与视觉宿主，仅测试/debug
```

`api` 不是“某个类标 public”这么简单，它是跨功能/跨仓可消费的最小契约：稳定业务 ID、只读投影、必要命令、导航数据和能力口；实现细节留其他模块。纯算法可用 Kotlin 标准库/协程/日期库；无必要时不引入 Compose/Ktor/平台 SDK。

**模块数量不是 KPI**。对于文档、设置等小功能，domain/data 可先在一个实现模块内部按包封装；但以下边界是新目标的硬边界，不能继续退回同一 compilation：

- 跨仓 API 与私有实现。
- 复杂业务/domain 与具体网络/设备 adapter。
- 纯展示状态/事件与 Compose UI。
- 功能页面布局与跨功能 DS。
- 产品 composition root 与被装配的公共库。

这样允许大规模重构，同时不为简单页机械生成五份空模块。复杂课表/校园网/真实跑步值得完整模块族；首页、服务目录不应为对称而新增空 data 层。

### 4.2 功能内依赖方向

```text
箭头表示左侧依赖右侧：

feature:x:domain       -> feature:x:api
feature:x:data         -> feature:x:domain / api + infrastructure adapters
feature:x:presentation -> feature:x:domain / api
feature:x:ui           -> feature:x:presentation / api + design-system

产品 composition      -> data 实现工厂 + runtime/presenter 工厂 + ui entry
其他 feature          -> x:api 中确实需要的合同（不依赖 x:data / x:ui）
```

图中 `presentation` 依赖 domain/api，**不依赖 data 实现**；data 实现 domain/api 的 ports。Content 通过状态/动作与受控 entry 接口对接，无 AppGraph、Repository、HttpClient、token，也不直接发系统请求；Route binder 只协调 lifecycle/effects，通过注入的平台 launcher/renderer 在明确用户动作下请求权限或展示原生 UI，业务请求走 Presenter/use case。其他 feature 最多依赖 `x:api` 的必要部分，不能直接 import `x:ui/data`；组合入口由产品根提供。

domain 与 api 不各复制一套所有模型：跨功能必须共享的稳定值对象归 api，domain 内部流程模型保持 internal。api 只依赖自身和公共基础值，绝不能再 import domain 形成环；若 port 签名需要 domain-only 模型，该 port 留 domain，或把真正跨边界的值迁到 api。data/presentation 已是独立模块，所需的 domain 用例/port/类型必须形成有限 public 面，不能把 Kotlin internal 当包级可见。若某契约只有 adapter 与本功能使用，它留 domain，不为“所有人都能用”提升到全局 core。

### 4.3 “UI 布局”与“UI 实现”真正的隔离

- `feature:x:presentation`：纯状态模型、输入投影、表单校验/交互、事件与效果，不画控件。
- `feature:x:ui`：Route binder + 页面 Content + 局部控件；用 DS 组件构成布局，接受原生区域 slot，不接具体 data/repository。Route binder 可以 collect Presenter 状态、协调受控 effects 并调用注入 launcher/renderer；Content 只接 UiState/事件/必要 slot，不承担这些接入工作。
- `design-system:*`：通用视觉实现与行为，依赖 Compose/MIUIX，不理解课表/蓝牙/登录。
- 功能独占的视觉卡片仍归该功能；只有复杂且确有独立维护需求时，再把 `feature:x:ui-components` 与 `feature:x:ui` 拆成两个模块。**不是每个 Text/Row 都抽接口，更不是设计稿转另一套动态布局引擎。**

实现中每个 Content 应能用不可变 UiState + 事件 spy 渲染。`remember` 滚动、展开、密码显隐可保留，但身份失效 key、退出清理和秘密字段不保存规则必须明确。运行时 owner 保留真实业务状态，Presenter 不另建一份可任意改写的“镜像数据库”。

### 4.4 基础设施也要按安全能力拆，而不是把 HTTP 完全泛化

当前 [ApiSessionBinding](<../shared/src/commonMain/kotlin/io/github/betterzslf/core/net/ApiSessionBinding.kt#L5-L21>) 和 [ApiClient 内部校验](<../shared/src/commonMain/kotlin/io/github/betterzslf/core/net/ApiClient.kt#L48-L57>) 依赖同一 compilation 的 internal 保护。新方案允许重做边界，但不以公开 token/底层 storage 为代价：

- 学校认证、request binding、迟到 401 清理和 session storage 先归同一个受控 infrastructure 模块；功能 data 使用不透明 bound 会话/传输能力，不取 token 字符串，再实现本功能的 typed source/repository port。基础身份/传输层不反向 import 所有 feature api，也不变成新的全业务 ApiClient 神对象。
- 教务专用 cookies、公用下载/GitHub、校园明文 allowlist 和学校认证 client 不合并。基础 HTTP 可复用 engine/安全配置，但身份域与资源关闭责任独立。
- 持久化格式/介质 adapter 与业务事务规则分清；typed store port 表达原子保存/撤销、durable checkpoint，不把整个 KeyValueStore 任意塞给每个 UI。
- 公共几何是纯值与算法。可正式重命名历史 `GeneratedRunTrack` 等含混类型并做持久化兼容，但生产真实采样与 Pro 预览类型必须有清晰来源标记，不能因为重构把后者接入真实记录/上传命令。

## 5. Pro 如何接入公共 UI：重做扩展契约，而非导出 AppGraph

### 5.1 产品根显式装配，注册表只管 UI entry

当前 [EditionFeatures](<../shared/src/commonMain/kotlin/io/github/betterzslf/edition/EditionFeatures.kt#L11-L16>) 虽没暴露 Water 类型，却暴露 `AppGraph` 和全局 `Route`；[Pro 实现](<../shared/src/proMain/kotlin/io/github/betterzslf/edition/ProEditionFeatures.kt#L59-L63>) 直接调用其他源集中 internal UI。这在一个 compilation 内能工作，迁到独立 Pro 模块后不是稳定扩展合同。

建议拆成几个明确、窄小的方向：

| 合同 | 公共方负责 | Pro 方提供 | 禁止泄漏 |
| --- | --- | --- | --- |
| 页面 entry/导航注册 | MIUIX adapter、保存恢复/守卫扩展点、未知目标降级 | Pro destination 与页面 entry 的显式列表 | 全局 graph、任意按字符串取服务 |
| 服务目录/登录入口 | 通用 `ServiceItemUiState` 与布局，语义动作回调 | Pro 条目、能力可用性与点击意图 | Water controller 到 ServicesContent |
| 任务预览输入 | 只读任务/地图配置 API、捕获 owner revision 的有效性能力 | Pro 预览 Presenter/UI | 学校 token、可写 Runtime、全量 Session/Repository |
| 原生视图 slot | 明确 model/event/dispose 的渲染合同 | Pro editor/文档选择器的 renderer | 任意未经校验的 JS/URL 或直接 native GPS 写入口 |
| 应用生命周期 | 明确 owner、挂起式 shutdown 顺序与失败结果 | Pro 运行时的启动/可关闭 handle | 仅取消全局 scope 就声称设备正常结束 |

注册表只解决“哪个目标对应哪个 UI、采用哪个导航策略”。它**不是 DI service locator，不注册网络客户端全集，也不能传入任意代码重写所有业务**。构造依赖采用 constructor/manual factory 注入，composition root 显式持有实例；按功能工厂可以批量组合，不强制引入新 DI 框架。

### 5.2 一个 Pro feature 需要什么，而不是它能看到什么

水控功能需要的是生命周期 scope、BLE transport、单调时钟、平台权限/时间上下文、自己的文案和 UI；不需要校园登录仓库。

路线预览需要 typed `RunTaskPreviewContext`、地图配置、有限设置和文件读取能力，不需要 `RunRuntime`/上传队列。[当前 RunImportPage](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/run/RunImportPage.kt#L32-L69>) 从 graph 中订阅 settings/session、查任务、建 controller 并用捕获账户比较失效；应把这几种输入改为明确 typed source 与 revision 校验，保留身份失效规则，不继续跨仓共享 raw Session。

新的 feature factory 可返回“页面入口 + 必要的服务目录项 + lifecycle owner handle”，实际字段按需求定义。它不应返回一个包含所有私有对象的 ProGraph 给公共 app-shell，也不应让 Content 直接接 handle 做 shutdown。

### 5.3 导航必须处理 sealed 与序列化的跨模块事实

现有 [Route](<../shared/src/commonMain/kotlin/io/github/betterzslf/App.kt#L181-L207>) 是公共模块中的 sealed interface，页面用 `kind: String`。不能要求私有模块在另一 compilation 直接为公共 sealed 根增加子类型，也不能把 Pro 的全部 key 继续硬编码到公共根里。

**优先方案：各 feature 定义自己的 closed destination，两个产品宿主分别拥有自己的 root union/wrapper。**

- 例如公共课表契约声明课表目标；Pro 路线工具声明私有目标；Standard 根只包装公共目标，Pro 根包装公共+私有目标。所有根的直接子类型在该产品自己的模块内定义。
- 公共 app-shell 不写死根类型全集；由产品根注入/组合导航内容与 key adapter，保持五 tab stack 的唯一 owner。
- UI Content 只发 `OpenCourse(selection)` / `OpenRoutePreview(contextId)` 等语义意图；跨功能由产品 coordinator 转接，或依赖对方 api 中的必要只读目标，绝不依赖对方 impl。
- 若 MIUIX 当前 API 必须使用更开放的根 key，也可选显式 polymorphic serializer 聚合，但必须在产品根注册白名单、稳定 serialName/schema 与缺失目标降级。**不能假定动态反射会在 Kotlin/Native 找到私有 serializer**。

此处是架构选择，不是已编译的导航实现。后续先做 MIUIX 保存/恢复、NavKey、可序列化 wrapper 的最小验证；官方 Navigation 3 模块化只作理念参考，不强行换库。允许破坏源码 API，但不无声破坏用户已保存的路由：旧 `kind/id` 通过版本化 decoder 转为新目标；不可恢复或缺失的 Pro 页面安全返回已知入口，不擅自执行旧动作。

### 5.4 能力存在、入口可见和操作授权分开

[EditionAccess](<../shared/src/commonMain/kotlin/io/github/betterzslf/edition/EditionAccess.kt#L3-L20>) 目前兼顾版本、开关、页面守卫。未来不再让所有公共 feature 问 `BuildEdition.isPro`：

1. **实现是否存在**：由构建依赖和注册列表决定，Standard 根本不链接 Pro。
2. **入口是否可用**：由产品配置/权限/设备支持投影成 UI 能力。
3. **操作是否获准**：由业务用例验证当前 owner/revision、参数、权限、用户确认和 busy 状态。

注册了 Pro feature 不等于允许扫描、连接、开始供水或发送轨迹；按钮隐藏也不能替代命令端校验。公共源里可保留既有旧路由的安全拒绝/迁移规则，但不要因此保留整个私有功能枚举和入口实现。

## 6. Pro 迁出清单：MOVE / KEEP / SPLIT

当前排除表是有用起点，**不是完整文件清单**。迁移必须按“源 → imports/调用者 → 平台 actual → 测试/fixture → 资源/权限 → 构建/发布”追闭包；不能只搜索文件名含 Pro。

### 6.1 MOVE：应由 pro-ci 唯一持有的实现

| 当前内容与证据 | Pro 目标归属 | 注意事项 |
| --- | --- | --- |
| [Pro 排除表](<../shared/build.gradle.kts#L16-L39>) 中 water、waterui、ui/water 全组 | `pro:water:{domain,transport,presentation,ui}` | 协议/密钥表/状态机、Kable transport、controller/presenter、页面一起迁；库依赖与 notice 同迁 |
| 排除表中的 [RunRouteParser](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/run/RunRouteParser.kt>)、[RunRouteValidation](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/run/RunRouteValidation.kt>)、[RunDetourPlanner](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/run/RunDetourPlanner.kt>) | `pro:route-tools:domain` | 属本地预览/编辑，不带真实 GPS/write 能力 |
| [RunImportController](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/runui/RunImportController.kt>)、[RunManualDraft](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/runui/RunManualDraft.kt>)、[RunImportPage](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/run/RunImportPage.kt>)、[RunManualDraftSession](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/run/RunManualDraftSession.kt>)、[RunManualDraftSheet](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/run/RunManualDraftSheet.kt>) | route-tools 的 presentation/ui | 抽掉 graph/internal RunUi helper，保留 mounted draft/admission/owner 失效闸 |
| [Pro build identity](<../shared/src/proMain/kotlin/io/github/betterzslf/edition/BuildEdition.kt>) 与 [Pro façade](<../shared/src/proMain/kotlin/io/github/betterzslf/edition/ProEditionFeatures.kt>) | Pro 产品 composition/身份配置 | 不再保留公共源集覆盖的同名 createEditionFeatures；按新 entry/lifecycle 合同重写 |
| [RunTrackPreview](<../shared/src/proMain/kotlin/io/github/betterzslf/feature/run/RunTrackPreview.kt#L7-L64>)、[RunMapEditing](<../shared/src/proMain/kotlin/io/github/betterzslf/platform/RunMapEditing.kt#L7-L28>) | Pro route preview 与 editor | 独立于公共轨迹构造/地图，只消费正式 API，不依赖同包 internal |
| [Bluetooth expect](<../shared/src/commonMain/kotlin/io/github/betterzslf/platform/BluetoothAccess.kt>)、[Android actual](<../shared/src/androidMain/kotlin/io/github/betterzslf/platform/BluetoothAccess.android.kt>)、[iOS actual](<../shared/src/iosMain/kotlin/io/github/betterzslf/platform/BluetoothAccess.ios.kt>)、[JVM actual](<../shared/src/jvmMain/kotlin/io/github/betterzslf/platform/BluetoothAccess.jvm.kt>) | Pro BLE/platform 模块 | expect/actual 在同一模块配对；iOS permission manager 仍仅在用户明确动作时创建 |
| 当前仅为路线导入服务的 PickedFile/文件读取/选择器 | Pro file-import/platform 模块 | 必须先从公共定位相关同文件切开；未来若公共功能真实需要文件选择，再抽成无 Pro 语义的能力，而非为未来假想需求留实现 |
| [AppText.Water](<../shared/src/commonMain/kotlin/io/github/betterzslf/core/text/AppText.kt#L1782-L1899>)、[ManualRoute](<../shared/src/commonMain/kotlin/io/github/betterzslf/core/text/AppText.kt#L1930-L1965>)、其他专属键 | 私有 ProText/feature resources | 同步 alias、golden、平台文案和扫描规则；不保留公共中文 key 外壳反向引用私有文本 |
| 专属测试、golden vector、wateremu fixture、tools 与 Kable 依赖 | Pro 测试/工具/依赖清单 | 复制测试名称不够，需迁 helper、fixture、许可证和生成规则；禁止把 build/oracle 等生成目录当作生产源码 |

Pro 私有目录的目标示意：

```text
pro-ci/
  pro/water/{domain,transport,presentation,ui}/
  pro/route-tools/{domain,presentation,ui}/
  pro/platform/{bluetooth,file-import,map-editor}/
  product/pro/{composition,android-app,ios-umbrella,ios-host}/
  testing/{fixtures,integration,ui-gallery}/
  build-logic/ 或固定的公共 convention-plugin 依赖
  .github/workflows/、scripts/、docs/
  公共基座版本/来源锁文件
```

### 6.2 KEEP：不能被“Pro 相关命名”误带走

| 保留公共部分 | 原因与证据 |
| --- | --- |
| 真实 GPS 的样本/过滤/checkpoint/recording runtime/journal/queue | 两版都保留真实记录。 [RunGeometry](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/run/RunGeometry.kt#L8-L24>) 的坐标与定位模型、[进度逻辑](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/run/RunGeometry.kt#L115-L161>) 不是 Pro 编辑器 |
| canonical track、real fromSamples、validator | [RunTrack](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/run/RunTrack.kt#L7-L24>) 历史名字含 Generated；[fromSamples](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/run/RunTrack.kt#L62-L87>) 是真实已接纳样本构造器，不能整文件迁走 |
| 公共几何/坐标转换、服务器任务/成绩/历史与地图配置的 typed 读取 | Standard 已使用，Pro 下游通过公开 API 复用；不复制一套解码器 |
| 只读地图/provider/bootstrap、安全 allowlist、Android/iOS/JVM view interop | [地图合同](<../shared/src/commonMain/kotlin/io/github/betterzslf/platform/RunMapPanel.kt#L8-L30>) 有真实路径与 checkpoint；编辑只是附加能力，不代表整个地图归 Pro |
| GPS、前台定位服务、课表通知/receiver/widget 与其资源 | [公共 Manifest](<../androidApp/src/main/AndroidManifest.xml#L3-L56>) 属 Standard 产品运行要求；不能因迁走 Pro manifest 一并删掉 |
| iOS location usage/background location | [Info.plist](<../iosApp/Info.plist#L35-L42>) 的真实定位能力是公共功能，不随 BLE 一起迁 |
| Standard 的负向隔离测试和公共真实能力正向测试 | [EditionCompilationTest](<../shared/src/jvmTest/kotlin/io/github/betterzslf/edition/EditionCompilationTest.kt#L12-L40>) 未来改成公共无 Pro、真实核心仍在；不是整份迁走 |

### 6.3 SPLIT：最容易“只搬目录就失败”的混合点

1. **Manifest 是整份替换，不是纯 Pro 增量**。[Android 配置](<../androidApp/build.gradle.kts#L38-L39>) 把 Pro 文件设为 main manifest；[Pro Manifest](<../androidApp/src/pro/AndroidManifest.xml#L3-L18>) 混有全部公共权限，BLE 只有 15–17 行。未来公共能力模块/Standard 宿主保留公共声明，Pro 产品显式增加 BLE；必须检查最终 merged manifest，而不是移动整份 XML。
2. **Info.plist 同文件含两类能力**：[BLE 描述](<../iosApp/Info.plist#L33-L34>) 到 Pro app，定位段留两产品。Pro 的权限清单由 Pro host 明确拥有，不靠私有库强行修改 Standard plist。
3. **文件选择和定位同文件**：[PlatformCapabilities](<../shared/src/commonMain/kotlin/io/github/betterzslf/platform/PlatformCapabilities.kt#L9-L50>) 混 PickedFile、picker、location；[Android actual](<../shared/src/androidMain/kotlin/io/github/betterzslf/platform/FilePicker.android.kt#L15-L63>) 前半是 picker、后半是定位权限。必须按声明切开。对应 JVM/iOS helpers 和测试也分离。
4. **公共 UI 中存在 Pro 导航分支**：[RunPages](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/run/RunPages.kt#L28-L47>) 用 graph 再委派 Pro 页面。公共任务/记录页面保留，Pro 入口由贡献/回调组合，不能继续 import 私有 UI，也不能整页迁走。仅导入使用的小图形可归 Pro。
5. **全局 AppText/测试混合**：专属 Water/ManualRoute 与公共 Run 消息分开，Run 中 preview/import 的 key 按使用者迁出；Golden/CallbackOrder/平台文案测试按断言拆，不整文件复制或删除。
6. **iOS 离线 guard 硬读 Pro 文件**：[check-capabilities.py](<../iosApp/tests/check-capabilities.py#L8-L40>) 无条件读 BLE/picker，且 [后段](<../iosApp/tests/check-capabilities.py#L63-L75>) 要求 BLE/plist；[ci-ios](<../scripts/ci-ios.sh#L25-L29>) 在编译前调用它。必须拆为公共 location guard 与 Pro BLE/picker guard，否则 Standard 在读文件阶段就失败。它是静态守卫，不能当 Native/device 验证。

## 7. 两个需要设计决策的接缝：地图编辑与公共几何

### 7.1 地图不能以“任意脚本插件”解耦

当前 [RunMapWeb](<../shared/src/commonMain/kotlin/io/github/betterzslf/platform/RunMapWeb.kt#L132-L145>) 在 HTML 生成中引用同名 `RunMapEditing`，解析与 [payload](<../shared/src/commonMain/kotlin/io/github/betterzslf/platform/RunMapWeb.kt#L174-L258>) 也依赖 editMode；公共 [RunMapModel/Event](<../shared/src/commonMain/kotlin/io/github/betterzslf/platform/RunMapPanel.kt#L31-L56>) 直接含 importedPath/editMode/DrawPoint。`standardMain` 通过 [空脚本 stub](<../shared/src/standardMain/kotlin/io/github/betterzslf/platform/RunMapEditing.kt#L5-L11>) 禁用编辑。

推荐最终切法：

- 公共 `platform-ui:readonly-map` 保留只读 model、已知路径/marker、provider policy、bootstrap、checkpoint selection、原生渲染释放；接口不再含私有编辑开关。
- Pro `platform:map-editor` 自己拥有 editor model、绘图事件和文档/脚本生成；可复用公共的底层**安全 WebView 宿主与 provider/坐标策略**，但公共宿主不认私有命令。
- 如要避免重复 provider 适配，可抽低层 host 合同，由各产品 composition 显式选择并固定依赖版本的 renderer。这是构建/审查信任边界，**公共 Kotlin 接口不能天然证明实现可信**，也不是运行时授权机制；host 仍独立强制 URL、bridge 字段、epoch、消息大小等校验，不接受调用者自报 trusted。不要把 `extraJavaScript: String`、任意 URL/bridge handler 直接加入 feature API；那会以“解耦”名义扩大 WebView 攻击面。
- editor 的 documentId、来源/坐标帧、字段白名单、有限数值、失效 generation 验证保留；公共地图继续拒绝 draw，Pro editor 的 draw 只能修改预览草稿，不作为 NativeLocationTracker 事件。

这里允许对地图层进行较大重构，不必强行保留同名 overlay。**安全策略可共享，业务事件不混流**；缺失 editor 不应导致 Standard 真实路径/推荐路径不显示。

### 7.2 公共几何 API 可以重设，但不是把 preview 接进生产

[RunTrackPreview](<../shared/src/proMain/kotlin/io/github/betterzslf/feature/run/RunTrackPreview.kt#L33-L62>) 当前直接调用公共 internal `RunGeo.cumulative/pointAt` 和 `RunTrackGenerator.assemble`。跨仓后相同 package 也无权访问。

允许两种处理，推荐第一种：

1. **提炼正式的纯几何 API** 到公共 `core:geo`，仅含坐标值、长度、插值、路径采样等确有复用的算法，写契约/极值测试。真实轨迹的“已接纳样本”构造与状态机不一起公开。
2. 很小且确属 Pro 预览的辅助算法留 Pro，自行维护并保留来源/许可；不把整个公共 runtime fork 一份。

进一步重构时区分 `RealSample/RecordedTrack` 与 `PreviewPath/PreviewTrack` 等语义类型。改名必须为已有存储格式提供兼容/迁移，不把历史字段随意删除。即便底层数值表示相同，也不能为 PreviewTrack 提供到生产采样/上传命令的隐式转换。

## 8. 生命周期、安全与许可证：搬仓不改变业务事实

### 8.1 Water 必须有可等待的产品 owner

当前 [WaterBleController](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/water/WaterBleController.kt#L53-L87>) 已说明应用/会话范围、process-wide guard，并在 parent scope 取消时做 NonCancellable 补偿；但 [EditionFeatures](<../shared/src/commonMain/kotlin/io/github/betterzslf/edition/EditionFeatures.kt#L12-L16>) 没有 shutdown，[AppGraph.close](<../shared/src/commonMain/kotlin/io/github/betterzslf/App.kt#L178>) 只取消 applicationScope，无法通过该 façade await Water 清理完成。

这是**统一生命周期屏障缺口**，不是已动态证明的 BLE 泄漏。Pro root 应持有 app 级惰性 owner，提供幂等、可等待的 shutdown：拒绝新命令 → 取消/等待扫描和 session → 有界断开与强制资源释放 → 等 collector/timer 静默 → 完成报告。页面 dispose 只结束页面绑定，不销毁应用控制器；重建页面不得重置失败重试确认窗口。

[正常结束判定与关闭实现](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/water/WaterBleController.kt#L328-L367>) 的语义必须保留：断开 BLE、取消 scope 或 shutdown 完成 **不等于实体设备已经停止出水**。正常协议结束需实际回执与资源释放；不确认时明确提示人工确认，不为了接口统一自动重试/重连。

### 8.2 跨仓不应扩大副作用授权

- Pro 路线工具继续仅内存预览，不拿 GPS、journal、queue、上传能力；当前 [RunImportPage 的 mounted/identity 门禁](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/run/RunImportPage.kt#L55-L99>) 和后台运算发布前校验迁移后保留。
- 公共 runtime 的 owner+token 原子绑定、迟到结果 fence、Unknown 写结果不自动重发保留。Pro 构建并不启用真实轨迹上传门。
- expect 与 actual 在各能力模块内成对；不把 expect 留公共仓而 actual 单独放私有库来“补实现”。可改成公共普通 port、Pro 自己实现；平台工厂在产品根显式选择。
- 校园 logout、教务解除绑定、本地 wipe、Pro owner shutdown 各有明确作用域。不要因“统一退出”而悄悄给离线水控发 begin/end，或错误清除另一个认证域的凭据。
- 账号、密码、token、BLE 设备地址等不进入可保存导航、公开日志、CI fixture、UI 诊断状态；跨仓 provenance 中不得包含这些运行数据。

### 8.3 历史与第三方许可要分开处理

[waterctl-MIT 授权文件](<../licenses/waterctl-MIT.txt#L1-L10>) 说明移植代码与 wateremu fixture 来源；[原 notice](<../licenses/waterctl-MIT.txt#L18-L26>) 和 [附加 notice](<../licenses/waterctl-MIT.txt#L36-L48>) 要求副本/实质部分保留版权和许可。Pro 协议、key tables、fixture、工具迁出时，通知与来源一并迁入并随实际分发保留；私有仓不免除保留义务。

“从公共当前树移除 Pro”不等于从 Git 历史、fork、tag、缓存、既有 APK 中抹掉。这里的迁出目标是**此后源码所有权/构建输入/新发布制品分离**，不承诺让已公开代码重新变成秘密。历史重写、删 tag/旧 release 或凭据轮换是另一种高影响任务，本轮未做，也不默认为迁移步骤。若仍有公共制品分发相关代码，保留其适用 notice，而非迁仓即清掉所有许可证。

## 9. 跨仓构建：先固定源码组合，长期支持正式制品契约

### 9.1 推荐落地：Pro 根驱动、固定 SHA 的 Gradle composite

当前只有一个 KMP 大模块，尚无已验证公共 KMP 发布矩阵。为了让迁出可实施，**第一条生产集成路径推荐固定公共 SHA 的 composite source build**：

1. pro-ci 检出自身固定提交，读取受审的公共基座锁清单。
2. 在临时目录检出主仓的完整固定提交 S，禁止浮动 main、未知 dirty tree 或“找得到就用”的任意本地目录。
3. 从 **Pro 根构建** 显式 include 公共独立构建，并为消费的公共模块声明准确 dependency substitution；Pro 自身项目直接依赖约定 coordinates。
4. Pro Android app/Pro iOS umbrella 从公共模块和私有模块一起编译；不在公共源树写入私有文件，不执行“把 private srcDir 追加到 public commonMain”的步骤。
5. Standard 的 build settings 中没有任何 pro-ci include，也不需要 Pro 凭据。开发机跨仓协同时可以用显式、可见的本地替换参数，但 release 必须关掉未验证本地 override。

[Gradle 官方文档](https://docs.gradle.org/current/userguide/composite_builds.html) 明确 included builds **独立配置，不共享 repositories、plugins 或 properties**，依赖替换也不应假定跨构建自动传递。故必须分别配置和验证：

- 公共与 Pro 一致的 Kotlin/Compose/AGP、target/minSdk、依赖/编译选项基线。
- group/name/variant/capability 与实际替换列表；不能仅凭 `includeBuild` 就声称所有 KMP variant 正确匹配。
- build-logic/version catalog 的显式共享方式：固定发布的 convention plugin/catalog，或固定公共 build-logic source include；不是靠 rootProject 跨仓相对路径读取。
- 两个根各自的依赖锁定/校验、仓库 allowlist、缓存 key。release 将 S/P/工具链/锁摘要计入构建记录；缓存不跨不受信 PR 和签名发布共享写权限。
- 平台工厂与 expect/actual 在正确模块一起编译；iOS framework 由 Pro umbrella 生成，不由公共 Standard 的 framework 代替。

### 9.2 长期制品模式：与源码模式使用同一公共坐标

公共接口稳定后，可以发布版本化公共 KMP 制品供 Pro 使用，包括 metadata、Android 产物与所需 Native target。建议 source composite 与 published 模式使用同一坐标/API，开发可替换源码、发布可选择受验证制品；**同一 release 必须固定一种已验的路径，不混入 latest/SNAPSHOT/mavenLocal**。

| 选择 | 优点 | 成本与边界 |
| --- | --- | --- |
| 固定 SHA composite（首推迁移路径） | 不需先建完整制品仓；跨仓重构可见；每次编译有源码闭包 | Pro CI 编译更多公共源码；两个构建配置独立；仍需保证 publication 等价 |
| 版本化 KMP 制品（稳定后） | 明确公共 API/发布节奏，可复用缓存 | 需维护全部 target/metadata/源码与依赖校验、仓库访问和兼容测试 |
| 子模块 pin | 源码版本可固定 | 只解决 checkout，不解决模块 API/编译隔离；不是必须再加一层 Git 嵌套 |
| 复制/patch/fork | 表面快 | 长期追溯与双主干成本最高，不作为方案 |

不要把 Pro 编译成 AAR 后直接供 iOS 使用；也不要把公共 `.framework` 当 Kotlin 依赖来再次组成 Pro umbrella。KMP 库消费与 Swift framework export 是不同层次。

### 9.3 API 兼容与重构节奏

- 大规模重构期允许公共 API 发生破坏性变化，但必须用固定 S+P 配对、显式 API/契约版本和跨仓集成测试一起推进，不以“不兼容”为理由继续泄漏内部对象。
- 公共 API 最小化，用 `implementation` 隐藏实现；`api` 只用于签名确实需要的类型。为跨仓 API 建 binary/source compatibility dump（工具按当前版本验证），为状态/副作用语义建合同测试；ABI 通过不代表业务语义兼容。
- 公共 contract-test kit 可供 Pro 跑 source/renderer 契约，内部测试 helper 不批量改 public；生产 module 禁止依赖 test kit。
- 约定兼容窗口：Pro 锁定当前受支持公共版本，每次升级走一条可追踪集成变更。不要一边持续改公共 main，一边让 Pro release 临时拉最新实现。

## 10. CI、发布和版本必须随源码归属一起改变

### 10.1 当前流程为何不能原样沿用

主仓 [build workflow](<../.github/workflows/build.yml#L22-L54>) 只构建 Standard；本地 Pro 快照 [tests workflow](<../work/pro-ci/.github/workflows/tests.yml#L60-L126>) 仍检出公共 source 后在该目录传 edition 构建，[iOS 部分](<../work/pro-ci/.github/workflows/tests.yml#L153-L222>) 也执行公共脚本。本地 [Pro release](<../work/pro-ci/.github/workflows/release.yml#L89-L174>) 在公共 `source` 目录构建/签名 Pro，Pro 仓自身只是 controller。

源码迁出以后，Pro binary 由“公共 S + 私有 P”共同决定，不能仍把公共 source HEAD 叫作唯一应用源码 SHA。必须修改 CI 的构建根、任务路径、缓存/制品目录、版本源、测试归属及 provenance，不是把 `edition=pro` 换一个值。

### 10.2 推荐的验证责任

| 验证域 | 在哪里必须独立通过 | 不得依赖 |
| --- | --- | --- |
| 公共基础/功能 unit、API、架构规则 | 主仓 PR/受信分支 | Pro clone/token/私有 workflow 的本地绝对路径 |
| Standard Android/iOS 构建与公共平台 guards | 主仓自己的验证闭包 | pro-ci 恰好存在；否则公共仓已不独立 |
| Pro domain/transport/presentation/UI unit 与 Native builds | pro-ci | 公共仓中的私有源码副本 |
| S+P 跨仓集成/兼容与完整产品验证 | pro-ci 的受信集成流程 | 未固定 main 或任意外部 artifact |
| 签名/发布 | 各产品受保护发布环境 | 不受信 PR、未验证产物、上一个任务“accepted”消息 |

[ci_workflow_contract.py](<../scripts/ci_workflow_contract.py#L6-L19>) 当前要求外部 Pro tests workflow 路径；未来公共基本测试应使用主仓自身合同，Pro orchestration 检查迁到 Pro，或把通用 verifier 做成显式可复用工具。不能让没有私有仓的公共测试失败。

公开 PR 与 secret 分离：任何 Gradle build logic、测试脚本、composite source 都会执行代码，**固定 SHA 仅解决身份，不自动变成可信**。fork/未审查 Pro PR 不注入签名/App 私钥，不通过 `pull_request_target` checkout 不受信代码运行高权限任务；需要私有依赖的集成由受控维护者流程执行。签名阶段只提升已验证的确定来源闭包，最小权限、隔离 runner、短生命周期凭据和清理继续保持。

### 10.3 来源清单从 S/C 升级为 S/P/W

当前 [release_bridge](<../work/pro-ci/scripts/release_bridge.py#L244-L260>) 区分公共源码 S 和控制器 C，[tag 逻辑](<../work/pro-ci/scripts/release_bridge.py#L272-L287>) 把 Pro tag 指向 C。代码迁入 pro-ci 后，应明确：

- **S / baseSourceSha**：公共模块源提交，或已验证公共 artifact 对应源码提交。
- **P / proSourceSha**：Pro 功能与产品 composition 提交。
- **W / workflowSha**：实际可信构建/发布逻辑提交；若与 P 同一仓同一固定提交则 W=P，但仍分别记录语义。
- **L / baseLockDigest**：锁清单及依赖/工具链摘要，用于确定公共/Pro 组合。

建议新的 release manifest 至少记录 product、versionName/code、S/P/W、公共 API/契约版本、构建模式（source/published）、公共制品坐标/摘要或 clone revision、依赖锁摘要、目标平台、signing certificate digest 和最终 artifact SHA-256。这里列的是新字段建议，不在本轮写任何 JSON。

现有 [verify_release](<../scripts/verify_release.py#L89-L107>) 只有单个 gitCommit，且校验脚本根的 HEAD。迁出后该语义需升级：双仓身份/clean tree/锁映射均验证；不要把 P 冒充 S，或只验证 S 却构建了另一个 P。Pro tag 应绑定实际 Pro 源码 P；Standard tag 仍只代表公共产品源。若 W 与 P 分离，产物中两者都列出。

### 10.4 Pro 版本必须能独立发布修复

当前 [release_metadata](<../scripts/release_metadata.py#L18-L49>) 从公共版本追加 `-pro`，[Pro tag 守卫](<../work/pro-ci/scripts/release_bridge.py#L89-L100>) 也固定该映射。若不变，Pro 私有 bugfix 往往需要公共仓空发新版本才能获得新 tag/code。

建议：**Pro 产品版本由 Pro 仓唯一维护，公共基座版本由锁清单维护，两者独立**。初次迁移保持现有应用 ID、证书及版本递增连续；可继续用展示后缀 `-pro`，但 Pro marketing triplet/预发布序列与 Android versionCode / iOS build number 必须允许在同一个 S 上递增。不要根据版本字符串算出“必然兼容公共版本”。

公共基座升级生成 Pro 集成变更/测试候选，Pro 发布仍是显式受信决策。公共 release 不必自动发布 Pro；现有自动通知的硬禁用 [false latch](<../.github/workflows/release.yml#L214-L223>) 继续保留，除非用户后续单独授权并审查。工作流 dispatch accepted 也不等于 Pro 测试、构建或发布成功。

### 10.5 应用内更新也是产品配置边界，不只改 CI

新增核查发现 [GithubReleaseModels](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/update/GithubReleaseModels.kt#L28-L52>) 的版本解析直接读 BuildEdition，[更新地址/校验策略](<../shared/src/commonMain/kotlin/io/github/betterzslf/feature/update/GithubReleaseModels.kt#L97-L129>) 也由全局 edition 选择；[Pro identity](<../shared/src/proMain/kotlin/io/github/betterzslf/edition/BuildEdition.kt#L4-L10>) 把更新仓库、appId、后缀绑在同名覆盖对象里。Android 安装器还 [校验产品 appId 与版本](<../shared/src/androidMain/kotlin/io/github/betterzslf/platform/AppUpdater.android.kt#L254>)。

因此公共 updater 实现可以保留，但产品根应注入不可被服务器/页面任意改写的 `ProductUpdatePolicy`：明确仓库/资产 allowlist、包身份、版本格式、channel 和证书约束。Standard 与 Pro 的配置分别归产品宿主；不能通过在 Settings 输入一个 URL 就切换更新域。Pro 仓若私有，也须明确用户如何取得更新：公开/受控分发镜像或维持可说明的“未发布/无权访问”状态，**不把 CI token、私仓访问令牌放进 App**。此轮未查询远端仓库可见性，不据本地文档假设终端用户一定能下载 Pro release。

改 Pro 独立版本时，连同 tag/asset filename/SHA256SUMS/URL 校验和版本比较测试一起调整。禁止只放松现有 parser 以接受任何 `-pro` 或任意预发布，而忘记防止跨产品降级/安装。是否对终端提供 RC 自动更新是独立产品策略，目前读取逻辑拒绝远端 prerelease，架构重构不自动开放。

### 10.6 工具/守卫也要模块化，而非全删

[display_text_guard](<../scripts/display_text_guard.py#L10-L27>) 对旧目录、唯一 AppText 路径和精确上下文有硬约定。大量迁移必然触发失效：后续把 scope 改为各生产模块显式根、公共/Pro 各自文案目录与白名单来源；重新审查移动后的例外，不用一条“忽略 pro/忽略所有 resources”掩盖问题。Line ending、版本、plist、map、release identity 和测试 fixture 守卫也随拥有者迁移，保留其原先的保护意图。

## 11. 激进重构的实施顺序：目标一次定清，切换分阶段完成

以下仅是后续获准实施时的计划，本轮不执行。允许大量移动/重写，但每阶段要形成可编译闭包和单一生产 owner；不要求先把全部旧包“无痛整理”才可以建模块。

| 阶段 | 可大幅重构的范围 | 必须交付的退出门槛 |
| --- | --- | --- |
| R0 冻结契约与基线 | 确认公共/Pro 归属、两个产品版本/更新策略、依赖白名单；录现有测试/构建基线 | 所有 Pro 源/调用者/资源/测试有 MOVE/KEEP/SPLIT 归属，现有失败与未跑项分开登记 |
| R1 先建真实基础模块 | build-logic、DS、core identity/geo、feature api、navigation contracts；切断 graph/source overlay 对公开接口的依赖 | 一个正常外部 consumer 仅用 public API 可编译；不依赖 friend paths、同包 internal 或 Standard app graph |
| R2 重做公共产品根 | 从 AppGraph/App.kt 提取各 owner factory、app-shell、Standard Android/iOS composition；typed navigation/服务目录开始替换 | 主仓独立 Standard 完整构建，五栈与现有数据恢复策略有效；公共功能不 require Pro workflow |
| R3 Pro 迁出候选 | pro-ci 建独立根和 Pro feature/platform modules，搬协议/编辑/文案/测试/notice，依赖固定公共 S | Pro consumer 能在不修改公共 source 的条件下编译/测试；公共与私有 actual 配对；没有重复 runtime |
| R4 跨仓切换 | 固定可配对的 S_cutover + P_cutover；私有版本承接功能后，公共默认树删除 Pro 实现和 source-exclusion/overlay | 主仓全新 checkout 不含 Pro、无 Kable/BLE；Pro pinned pair 构建成功；源码归属只剩一处；旧参数失败或有清楚迁移提示 |
| R5 完整功能分层 | 大规模拆 schedule/run/campusnet/auth 的 domain/data/presentation/ui；替换 raw JSON/session 暴露，完成 owner/typed ports | 模块 DAG 无环、测试归属清晰、生产根依赖显式；Pro 仅消费窄公共 API |
| R6 产品 CI/更新/发布换轨 | 公共自主 tests、Pro 私有构建根、S/P/W manifest、独立版本、更新 policy、权限最终制品检查 | 受控 tests-only 无签名权限；发布 rehearsal 的来源/包身份/摘要闭包完整；自动发布仍未擅自启用 |
| R7 验收与删除过渡层 | 所有目标平台/极端 UI/实体能力分别验证，移除 legacy façade、重复目录与旧 path 守卫 | 不再靠 glob excludes/同名替身；公开 API/支持矩阵有维护规则；技术债不是永远并存双实现 |

R2–R3 可以用向前兼容的公共 API 提交和私有候选分支并行，但 R4 的源码删除与 Pro root 切换必须按配对锁清单验收。R5 不代表其他阶段可以暂时把新的 feature 写回万能 graph；每条迁出的功能必须先符合自己的边界。

### 11.1 双仓没有原子提交：用 pin 协调，而不是靠同时点 Merge

推荐切换流程：

1. 公共先合入迁出所需的 API 与新模块，固定 S_candidate；仍工作的旧版按旧 pin 构建。
2. pro-ci 在候选 P 上加入完整实现与新的根，锁定该 S_candidate；用 tests-only 流程验证 S/P 配对，先不发布。
3. 公共得到不依赖 Pro 的删除候选 S_cutover，私有候选改锁该版本并验证最终闭包；明确删除清单与兼容迁移。
4. 合并公共切换和私有 pin，记录经过验证的 S_cutover/P_cutover；发布只选择这个配对，不按仓库 main 的当时状态临时拼接。
5. 清理旧 overlay 的完成门槛通过后，旧构建方式有明确终止版本。短期两个仓中出现迁移候选副本不代表两处长期可维护主源；切换后主仓不再保留实现副本。

回滚不是强行覆盖 tag：已安装产品用新的递增版本修复，源码可以选择历史已验证 S/P 配对；发布产物/tag 不变。存储迁移需前向容错或有明确降级策略，不能先写不可逆新格式再用旧二进制“回滚”。

### 11.2 旧 API 和旧身份要分别处理

允许删除/重命名内部类与修改源码 API；但外部不可随意改变的包括应用 ID/证书、已有存储 key/加密 service、课表 owner/revocation、原生通知/widget 标识、持久化 route schema、更新可信域。迁模块时为这些建立兼容表与测试，不靠旧包名恰好未变来保证。

禁止为让新模块编过批量改 public、`-Xfriend-paths` 跨仓生产编译、关闭 serializer/权限守卫或临时让所有 command 成功。重构可以很大，**依赖关系与安全校验必须比原来更可证明**。

### 11.3 人与 Agent 的并行范围

- 基础/构建负责人：convention plugins、公共契约、API dump、composite/制品兼容；不同时修改每个功能内容。
- 业务负责人：功能 domain/data/owner 与边界测试。
- 布局负责人：presentation 输入样本和 Content 结构，自适应/Insets/事件，不碰真实 adapter。
- 视觉负责人：DS theme/tokens/components，与布局通过公开组件 API 协调。
- Pro 负责人：私有 feature/平台/产品根；对公共 API 的需求通过独立契约变更提出，不直接复制公共实现。
- 集成负责人：S/P pin、导航/身份/生命周期集成、双平台/发布来源闭包。

先定接口与不重叠写范围再分工；需要串行的契约改动设依赖，不让多人在 [App.kt](<../shared/src/commonMain/kotlin/io/github/betterzslf/App.kt>)、[AppText.kt](<../shared/src/commonMain/kotlin/io/github/betterzslf/core/text/AppText.kt>)、[RunUi.kt](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/run/RunUi.kt>) 这些混合文件里各自“顺手重构”。

## 12. “拆分完成”必须通过的验收矩阵

以下是**后续代码阶段的验收要求，不是本轮测试结果**。单纯把目录移动、Gradle 配置成功或 JVM unit 通过，都不足以证明双仓产品已可交付。

| 层面 | 必须可验证的事实 | 失败时不能采用的捷径 |
| --- | --- | --- |
| 主仓独立性 | 全新公共 checkout 在没有 pro-ci/私有权限时完成公共 tests 与 Standard Android/iOS 构建 | 把 Pro clone 作为公共 contributor 的前置条件 |
| 私有独立性 | Pro 从固定 S+P 构建，公共 tracked tree 前后无改动；私有源码不是公共 sourceSet 输入 | 临时复制源码、patch 上游、跨仓 friend paths |
| 模块 DAG | DS 不依赖 feature；domain 不依赖 Compose/Ktor JSON/平台 UI；presentation 不依赖 concrete data；UI 不 import graph/repositories | 用 Any/JsonObject/全能 HostDependencies 伪装接口 |
| 公共 API | 正常外部 consumer 可用 entry/DS/只读 map；签名与默认参数所需类型可见；目标/变体匹配 | 让全部 internal public，或只验证同项目 internal tests |
| Standard 隔离 | 编译/运行 classpath、APK/AAB/Native framework、依赖图中无 Pro 实现/Kable/绘图脚本；无 BLE 权限/描述；真实 GPS/路径/checkpoint 仍在 | 只隐藏按钮、只测类名反射、依赖 shrinker 当隔离 |
| Pro 存在性 | 私有类/资源/平台 actual 可编译且实际组成产品；BLE/文件/编辑 positive tests 属 Pro | 只看到 Pro tag/入口就宣告能力工作 |
| 导航 | typed 序列化 round-trip、旧五栈 schema 迁移、未知/缺失 Pro 目标安全降级、入口与 dispatch 守卫一致 | 未知 kind 默认通行、恢复崩溃、窗口切换重建全部栈 |
| 会话并发 | owner+token/revision 原子捕获、旧请求完成不覆盖新会话、旧 401 不清新 token | 只按 userId 判断同一请求身份 |
| 写操作 | Accepted / NotAccepted / Unknown 三态与确认保持，Unknown 不自动重试/切代理，取消不伪称未提交 | 把超时统一转 Failed 后自动重发 |
| 运行时退出 | run/schedule/updater/Pro owners 的停止与等待顺序可证明；wipe 前相关写入静默，单例不因重组重复创建 | 先删 storage 再异步 stop，screen dispose 关 app runtime |
| 课表与校园身份 | 本地教务 slot 与校园 session 分开，解绑撤销/迟到结果 fence、确认后原子保存保持 | 以校园 userId 替代课表本地 owner |
| 地图安全 | 只读/编辑事件分离、document/mount/provider/坐标帧校验、allowlist、checkpoint 白名单、重复字段拒绝 | 任意 extra JS、把 JS draw 喂成真实 GPS |
| 路线预览隔离 | 导入/手绘/采样只在草稿与预览类型内；失效后不发布；其依赖无法调用生产 punch/upload | 把真实 runtime 或完整 RunRepository 暴露给私有预览 |
| BLE | 显式 permission/scan/connect/begin/end；失败 guard 跨页面共用；await shutdown；不确定设备状态需人工确认 | 初始化弹权限、自动 reconnect/retry、断链等于停水 |
| 权限/宿主 | 两个最终 merged manifest/plist 与包 identity 正确；Standard GPS/课表资源未丢，Pro 只增加需要能力 | 按文件名整份搬 Pro manifest 或 FilePicker |
| 发布/更新 | S/P/W/锁摘要/产物摘要可追溯、Pro 独立递增版本、包签名/资产域校验保持 | 用公共 S 代表全部源码、注入私仓 token 到 App |

### 12.1 UI/布局/视觉可以真正独立工作吗

按每个 Content 的实际状态图覆盖所有可达状态：适用时包括 loading、空态、正常、错误、长文本、未登录/失效、busy/确认；不适用项标 N/A 并说明，不为纯展示卡片虚构业务状态。暗色/浅色、窄/宽布局、字体放大按其真实职责验收；rail、五栈和系统 Insets/底部遮挡在宿主集成矩阵覆盖。状态输入来自生产同型 UiState，动作经 spy 验证，不要求启动 AppGraph。首页、服务目录是共享样板；Water/路线 editor 是私有消费样板。

画廊使用固定 Clock、只读测试数据、fake map/file/permission 和禁网 adapter；不用真实权限/设备/网络。截图/Compose 布局测试证明的是渲染，不证明 NativeView/系统权限或 BLE 行为。产品组合 preview 与真实集成宿主分开，不能因为类名叫 MockPreview 就默认无副作用。

### 12.2 平台证据分层，不能互相冒充

1. **纯 Kotlin/JVM**：状态机、parser、geometry、输入/结果 fence、纯 iOS rules，覆盖领域与并发逻辑。
2. **静态守卫/制品扫描**：依赖/源码边界、manifest/plist、APK/框架内容、签名/来源；不证明原生回调行为。
3. **真实目标编译**：Android 与 macOS 上的 Kotlin/Native/Xcode 构建；JVM host 不代替 iOS actual 检验。
4. **模拟器/UI 集成**：导航恢复、原生文件取消/释放、屏幕生命周期、跨页面 owner；模拟器缺失硬件不应判功能全部通过。
5. **授权实体设备验证**：真实权限、前后台定位、BLE 回调/断开/异常恢复等；需用户后续明确授权、选择自有设备和测试步骤。实体设备停止状态不能由离线 emulator fixture 推断。

### 12.3 已知问题与架构迁移分开登记

第一版记录的首页状态/次日语义、课表原生 open 的本地 slot 与校园身份比较等仍是独立缺陷候选；例如 [原生 open 分支](<../shared/src/commonMain/kotlin/io/github/betterzslf/App.kt#L257-L263>) 当前检查校园 session。重构期间应给它们单独 issue/测试，不无声把行为改动混入搬目录提交，也不为维持旧 bug 而把错误契约固化为公共 API。

## 13. 补充迁出闭包：排除表以外的测试、文案与工具

已识别的主体是 17 个 commonMain Pro 实现文件（water 7、waterui 1、ui/water 1、路线算法 3、import/manual controller 2、import/manual UI/session 3），另加 Pro 源集 4 个文件、Bluetooth expect/actual、按声明拆出的 picker 与下面的关联内容。这个数字不是“迁出总共只需 21 个文件”的承诺。

### 13.1 专属测试应跟实现走，混合测试按断言拆

| 关联内容 | 动作 | 关键证据/遗漏风险 |
| --- | --- | --- |
| water 四组协议/controller/session/fixture 测试与 waterui 两组 | MOVE Pro tests | [WaterProtocolGoldenTest](<../shared/src/commonTest/kotlin/io/github/betterzslf/feature/water/WaterProtocolGoldenTest.kt#L1-L20>) 含共享 internal helper；[WateremuFixtureTest](<../shared/src/commonTest/kotlin/io/github/betterzslf/feature/water/WateremuFixtureTest.kt#L1-L26>) 是离线 emulator fixture，不是硬件 BLE 测试 |
| 路线 import/geometry/track、manual draft/controller/session tests | MOVE Pro tests | [原 test excludes](<../shared/build.gradle.kts#L30-L39>) 是第一批清单；公共几何/真实轨迹的独立测试必须仍留公共模块，不因该测试名含 Geometry 就认为公共几何都归 Pro |
| [AppTextWaterAliasTest](<../shared/src/proTest/kotlin/io/github/betterzslf/core/text/AppTextWaterAliasTest.kt#L3-L11>) | MOVE Pro text tests | LOCKOUT_NOTICE alias 仍需单一来源 |
| [AppTextManualRouteGoldenTest](<../shared/src/commonTest/kotlin/io/github/betterzslf/core/text/AppTextManualRouteGoldenTest.kt#L7-L64>) | MOVE Pro text tests | **不在现有 Pro test excludes 中**，迁文案不迁它会导致 Standard tests 断编译 |
| [PickedFileTest](<../shared/src/commonTest/kotlin/io/github/betterzslf/platform/PickedFileTest.kt#L5-L26>) | 随 picker MOVE | 也不在原排除表；防御复制、文件名脱敏、大小上限都保留 |
| AppText 综合 golden/feature/platform 测试 | SPLIT | [AppTextGoldenTest 的 Water 断言](<../shared/src/commonTest/kotlin/io/github/betterzslf/core/text/AppTextGoldenTest.kt#L436-L484>)、[Feature golden](<../shared/src/commonTest/kotlin/io/github/betterzslf/core/text/AppTextFeatureGoldenTest.kt#L212-L238>)、[Platform golden 的 BLE 断言](<../shared/src/commonTest/kotlin/io/github/betterzslf/core/text/AppTextPlatformGoldenTest.kt#L97-L106>) 到 Pro；公共正文留上游；其他 Water 引用也须逐项追踪 |
| formatter/callback 与 RC4 UI 综合测试 | SPLIT | [CallbackOrder](<../shared/src/commonTest/kotlin/io/github/betterzslf/core/text/AppTextCallbackOrderTest.kt#L21-L32>) 有 Water formatter；[Rc4UiCopyTest](<../shared/src/commonTest/kotlin/io/github/betterzslf/core/text/Rc4UiCopyTest.kt#L8-L24>) 同时含公共校园网与水提示，不能整份迁走 |
| map web tests | SPLIT | [编辑正向断言](<../shared/src/commonTest/kotlin/io/github/betterzslf/platform/RunMapWebTest.kt#L193-L209>) 到 Pro；Standard 拒绝 draw、[checkpoint/资源 allowlist](<../shared/src/commonTest/kotlin/io/github/betterzslf/platform/RunMapWebTest.kt#L212-L236>) 及 [真实/推荐路径显示](<../shared/src/commonTest/kotlin/io/github/betterzslf/platform/RunMapWebTest.kt#L238-L249>) 留上游；[bootstrap policy tests](<../shared/src/commonTest/kotlin/io/github/betterzslf/platform/RunMapBootstrapPolicyTest.kt>) 为公共核心 |
| iOS bounded-file 与 native location pure rules | SPLIT | [CapabilityRulesTest 的文件矩阵](<../iosApp/tests/CapabilityRulesTest.kt#L20-L94>) 随 Pro picker；[定位矩阵](<../iosApp/tests/CapabilityRulesTest.kt#L95-L155>) 保留公共。该文件以 JVM 编译纯规则，不能证明 UIKit/Native 行为 |

### 13.2 文案与资源不能按关键字一刀切

[AppText.Run](<../shared/src/commonMain/kotlin/io/github/betterzslf/core/text/AppText.kt#L1102-L1109>) 中 preview/import/generation 专属键、[其他 preview 配置键](<../shared/src/commonMain/kotlin/io/github/betterzslf/core/text/AppText.kt#L1361-L1365>) 需要按引用迁出；真实 GPS/filter/queue/geometry 消息仍公共。公共 domain 后续最好返回 typed error，由 feature presentation 映射文案，而不是纯几何长期依赖完整 AppText。

[ServicesPage](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/ServicesPage.kt#L42-L45>) 的“宿舍水电”禁用占位属于服务目录，不等于水控协议 UI，允许保留通用 catalog 条目或改为产品贡献。公共 app icon、theme、network security config、课表 layout/drawable/xml/string 也不是 Pro 专属资源。

[RunWidgets 的导入图形](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/run/RunWidgets.kt#L90-L102>) 当前仅导入页使用，可随 Pro 迁走；通用 Action/Card/Confirmation 应抽入 DS 或公共 run UI kit。不要为了维持旧 import 把 [RunUi 的查询/地图绑定](<../shared/src/commonMain/kotlin/io/github/betterzslf/ui/run/RunUi.kt#L28-L46>) 整文件改 public。

### 13.3 fixture 工具与法律材料一起闭包

水控工具包括 [generate_key_tables](<../shared/src/commonTest/kotlin/io/github/betterzslf/feature/water/tools/generate_key_tables.py>)、[generate_oracle](<../shared/src/commonTest/kotlin/io/github/betterzslf/feature/water/tools/generate_oracle.mjs>)、[verify_kotlin](<../shared/src/commonTest/kotlin/io/github/betterzslf/feature/water/tools/verify_kotlin.py>)、[verify_oracle](<../shared/src/commonTest/kotlin/io/github/betterzslf/feature/water/tools/verify_oracle.py>)。它们当前按旧根和源码路径定位，迁移时修复显式输入/根目录，而不是去公共仓找已删的 water 源。

生成器、生成文件和 fixture 的来源/notice、审查期望值都随 Pro 保存；不在 CI 随意从未固定远端重新生成再把“测试与新生成值相等”当正确性证据。本轮只审读工具，没有运行生成器、读取生成产物或对设备写入。

## 14. 决策摘要与本轮交付边界

### 14.1 当前推荐，已经明确的方向

1. **公共基座 + Standard 产品在主仓；Pro 实现 + Pro 产品在 pro-ci。** Pro 是正常下游消费者，不是给公共 sourceSet 打补丁的 edition。
2. **功能纵切与真实编译边界。** domain/data/presentation/UI/DS 各司其职，复杂功能充分拆分，小功能不空造层数；这是 KMP 客户端内的职责分离，不要求凭空新增一套自有后端服务。
3. **公共 API 窄、产品根强。** 不公开万能 AppGraph，不用 friend paths，不动态加载私有插件；公共与私有都从自己的根显式组合。
4. **两个产品自有 typed navigation root，iOS 各一个 umbrella。** 不跨模块扩展公共 sealed 根，不链接重复完整框架；保持旧路由/身份/存储迁移规则。
5. **Pro 迁出按闭包而不是按目录。** 源码、平台 actual、权限、文案、测试、fixture/工具、notice、CI、版本/更新都纳入；真实 GPS/公共地图/课表留上游。
6. **先用固定 S 的 composite 组成 Pro，成熟后用同坐标制品契约。** 最终以经过验证的 S+P+W 与锁摘要重现发布，Pro 可以独立修复发布。
7. **安全语义不可用解耦交换。** 单一运行时 owner、await shutdown、身份 revision、Unknown 写结果、设备状态不确定性与真实/预览数据隔离必须更清楚。

### 14.2 后续实现必须验证、尚不能写成已完成的事项

- MIUIX 当前版本对宿主 sealed wrapper、serializer/SaveableState、五栈恢复的确切适配方式。
- 锁定 Kotlin/Compose/AGP 的多模块 KMP composite 与发布 metadata/variant 是否等价，Android/iOS 各 target 的可消费闭包。
- 主仓公共模块的最终 Gradle 粒度、contract-test/API compatibility 工具选型及目标矩阵。
- 两产品 native host 的最终资源/权限合并、持久化身份与历史 schema 迁移。
- Pro 更新制品的实际分发可达性与发布身份策略；本地 pro-ci 文档不能证明远端可见性或可下载权限。

以上是落地验证问题，不改变已经选定的依赖方向，也不应变成长期保留 source overlay 的理由。

### 14.3 本轮做了什么、没有做什么

- 已对主仓及本地 pro-ci 快照作定向静态审读，结合两位审读者核实 UI/平台/测试迁出闭包；官方参考已读取并在第 2 章列出。
- 报告按章节小批追加落盘；[第一版](<architecture-decoupling-review-2026-11-17.md>) 仅补充后续要求与 V2 优先说明，不覆盖其历史证据。
- 未改应用/脚本/Gradle/CI/manifest/plist/许可证内容；未移动源码，未改 Git 分支/历史/tag、权限或账号设置。
- 未执行 Gradle、unit/Native/device tests、构建、安装、fixture 生成、真实学校网络请求或 BLE/定位操作；文档静态核对不是应用测试。
- 未访问 pro-ci 远端，也未触发 CI/发布；本地快照事实与未来建议在正文分开表达。

### 14.4 文档核对结果

- V2 的 **114 处本地 Markdown 链接**：目标文件存在、所标行号范围有效；第一版连同新增 V2 链接共 88 处，亦无无效路径/范围。
- V2 10 个 fenced-code 边界、第一版 8 个，均成对；无分批追加占位残留。此核对不代表示意模块结构已实现或配置已编译。
- UI/架构二次审稿已纳入：api 不反向依赖 domain、跨模块有限可见性、Content/Route binder 区分、renderer 的供应链信任边界和按实际可达状态验收。
- 本轮新增本 V2 Markdown；第一版只增加要求变更说明。结束前主仓 tracked diff/staged diff 为空，本地 pro-ci 也无改动；既有未跟踪文件保持原样，没有清理/覆盖。

**最终结论：可以激进拆，但应按“可复用公共能力 + 独立产品根 + 窄合同”拆；Pro 必须真实迁出源码闭包，而不是继续藏在公共源集中用构建开关裁剪。**
