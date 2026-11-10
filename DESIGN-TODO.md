# DESIGN TODO — 跟进最新 MIUIX，采用 OS4 Glass

> **目标：用上游新组件替换项目里的旧实现，而不只是把旧 UI 换一层颜色。**
> 本文是 [DESIGN.md](<DESIGN.md>) 的执行待办；核对日期：2026-11-09。
> 本次只更新文档；下面的实现任务均未完成，不能据此宣称已经升级依赖或 UI。

## 0. 最新版本事实与采用决策

| 通道 | 核实结果 | 本项目决策 |
| --- | --- | --- |
| 最新稳定版 | GitHub latest release 与 Maven Central 都为 **0.9.4**，发布于 2026-09-20 | 项目已声明 0.9.4；不是简单改版本号就能获得全部新组件 |
| 最新主线 | **`45645854cd46c32275c5666e3aafa9980d8f104e`**，提交日期 2026-11-09；比 v0.9.4 超前 70 个提交 | 本轮新视觉以这个不可变提交为调研与接入目标，不依赖浮动 main |
| 新增模块 | **`miuix-glass`、`miuix-glass-icons`**，OS4 玻璃材质和整组配套组件 | 纳入实际迁移任务，不再长期停留在“可选效果” |
| 已发布开发版 | GitHub Packages 的 glass 根构件与 Android 构件页面已列出 **`0.9.4-45645854-SNAPSHOT`**，发布于 2026-11-09 | 以此精确开发版为接入目标；尚未在本项目执行依赖解析 / 编译，iOS 变体和其他配套模块仍须按 D00 验证 |

**采用目标**：在支持且通过验收的设备上，导航、一级顶栏、筛选 Tab、菜单和短弹层采用官方 Glass；普通长列表和表单底仍用 MIUIX 实色曲面。Classic / 不透明模式是完整降级路径，不是拒绝新组件的默认理由。

重要纠正：最新主线 **`GlassNavigationBar` 已有可拖动选中胶囊**，含按下、拖动、释放回弹、标签、键盘和无障碍选择；不再需要之前稿里提议的自研 pointerInput 胶囊导航。

不能用 `miuix-glass:0.9.4` 冒充稳定版已发布，也不要求把 Glass 手写移植回旧库。依赖接入按 D00 完成后，按以下任务真正使用新能力。

## 1. 批次与完成口径

- **P0 / A：接入基础与两个代表界面**：D00 → D01 → G01 + G02；同时做 S01。先交付“课程表 + 设置”整套可见新视觉。
- **P1 / B：配套组件成套采用**：G03–G07、S02–S05，覆盖校园跑、账号流程、设置和短弹层。
- **P2 / C：剩余新能力与全局回归**：S06–S08、V01。需要真实场景的功能不为了组件覆盖率硬塞。
- 所有 checkbox 仅在代码完成、测试与截图 / 实机证据齐备后勾选；“已读上游文档”不等于“已接入”。
- 每个任务注明：**组件 / 入口 / 依赖 / 验收**。同一批先改共享入口，再迁移页面，不让每页各建 Glass 配方。

## 1.1 本次 rc（0.9.4 稳定版可落地部分）实际完成项

> 2026-11-10 核对：下方 D00–D01 / G01–G07 依赖 GitHub Packages 的 `0.9.4-45645854-SNAPSHOT`
> 与未验证的 iOS 变体，本轮**未接入**，相关 checkbox 仍全部未勾选，不冒称完成。本轮只在
> 项目已声明的 0.9.4 稳定版上，落地 [DESIGN.md](<DESIGN.md>) §10.2 列出的可独立整改项，并在
> API36 模拟器上做了浅 / 深 / 130% 字号视觉验收。

- [x] **§3.3 主操作对比度**：在 `DesignComponents.kt` 新增唯一颜色工厂 `primaryActionColors()` /
      `primaryTextActionColors()`，固定品牌主题用浅色 `#266BF2 + #FFFFFF`（约 4.70:1）、
      深色 `#5C94FF + #0D0F12`（约 6.52:1）；Monet 保留自身语义配对。`PrimaryAction`、
      `ConfirmDialog`、校园网 / 账号恢复 / 更新 / 热水器 / 课表学期等所有主操作入口统一改走工厂，
      不再散落 `ButtonDefaults.*Primary()`。`DesignSystemTest` 新增对比度回归断言。
- [x] **S01 密码 trailingIcon**：删除“Miuix TextField 没有 trailing slot”的过时注释；宿舍宽带密码
      与教务绑定密码改用 `TextField.trailingIcon` + 共享 `PasswordVisibilityIcon`（`MiuixIcons.Show/Hide`、
      48dp 触控目标、20dp 图标、可读描述），移除各自占一行的显示 / 隐藏按钮。
- [x] **§10.2 RunWidgets**：动作字 15sp → 共享 16sp；`RunStatus` 状态色改为深浅色语义解析；
      主色按钮走中央工厂。
- [x] **§10.2 RunPages / RunImportPage**：14dp gap → 共享 `Gap`(12dp)，底部占位统一
      `pageContentPadding` 的 `max()` 策略，去掉 scaffold + overlay + 额外 20dp 相加。
- [x] **§10.2 App.kt**：启动异常主题补 `ardotTextStyles()`，异常页与正常页同一排版语言。
- [x] **§10.2 SchedulePage**：清理第三 tab / 34sp / 旧卡片色 `#1A1C21` 等过时注释。
- [x] **CI 稳定性**：`DormSignInControllerTest.aSecondAttemptWhileOneIsRunningIsRefusedNotQueued`
      改用 `runTest` 的结构化 `backgroundScope` + `CompletableDeferred` 确定性信号，替代脱离管理的
      `CoroutineScope(Dispatchers.Default)` 与 `delay(120)` 竞态；修复 rc47–rc51 在 CI 上的
      `UncompletedCoroutinesError` 发布失败。本地重复运行 3/3 通过。

**未做（保留待办）**：D00–D01 / G01–G07 / S02–S08 / V01 全套 OS4 Glass 迁移与宽屏、动态色、
数值选择等；它们需要新依赖通道与真实设备材质验收，不为组件覆盖率硬塞。

## 2. P0 — 依赖与共享基座

### D00 · 固定最新组件来源与构建通道

- [ ] 核对本次固定 SHA 的发布任务与实际仓库 metadata；记录真实可解析的版本、完整 SHA、仓库与校验信息。
- [ ] 使用 GitHub Packages 精确开发版 **`0.9.4-45645854-SNAPSHOT`**，仓库为 `https://maven.pkg.github.com/compose-miuix-ui/miuix`；逐项确认 KMP 变体和配套模块能解析。若缺少必要变体，使用同一固定 SHA 源码构建到可复现的受控 Maven 仓库，不把缺失项降级混装回 0.9.4。**不无限等待下个稳定版，也不依赖浮动 SNAPSHOT。**
- [ ] 全部 MIUIX 模块使用同一提交 / 构建版本，包括 ui、preference、icons、nav、blur、squircle、shader、glass、glass-icons；由依赖解析确认实际需要的模块，不能混用稳定 ui + 最新 glass。
- [ ] 在 [版本目录](<gradle/libs.versions.toml>) 增加 glass / glass-icons，以及业务直接调用的 squircle / shader 的显式声明；在 [共享依赖](<shared/build.gradle.kts>) 接入。不存在的模块不能只凭名字新增。
- [ ] 验证项目 Kotlin 2.4.21 / Compose 1.12.2 / JDK 21 与新构件兼容；当前项目 AGP 9.4.1、Gradle 9.6.1，而目标上游构建使用 AGP 9.5.1、Gradle 9.9.0。区分“消费已编译构件”和“自行编译上游源码”的要求，不盲目全量升级工具链。
- [ ] Android minSdk 保持 33（glass 自身也要求 33），解析并编译 Android 与 iOS arm64 / simulator 构件；JVM 仅做宿主测试。
- [ ] GitHub Packages 如需凭据，仅由本机 / CI secret 注入；不写进版本目录、文档、提交或日志。

**完成条件**：记录确定的可复现依赖来源，Android / iOS 均可编译，依赖图没有跨版本 MIUIX；Standard / Pro 不串入被裁剪功能。当前文档核实不算构建通过。

### D01 · 建立一套共享 Glass 接入，而不是一套新主题

入口：[Components.kt](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/Components.kt>)、[DesignComponents.kt](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/DesignComponents.kt>)、[App.kt](<shared/src/commonMain/kotlin/io/github/betterzslf/App.kt>)。依赖：D00。

- [ ] 在现有 `Screen` / Shell 上增加共享材质策略：Auto / Glass / Opaque（名称为项目实现建议，**不是库 API**）；正常目标为 Glass，自动降级保留完整功能。
- [ ] 分清内容 backdrop 和浮层 backdrop：记录不透明页面内容，排除使用它的玻璃表面；弹层采样需要页面栏时另选适当采样层，排除弹层 host 和 dim 遮罩。
- [ ] `GlassShape`、`GlassMaterials`、`GlassStrokes`、`GlassShadows` 使用上游主题预设；普通 `Card` 继续使用 MIUIX squircle。不要给 Glass 外面再包普通圆角、shadow、border 和 `textureBlur`。
- [ ] 自绘 `glassPanel` 必须主动检查可用性并提供不透明 fallback；其默认 fallback 只是 clip，**不是完整安全底色**。不要假定所有组件都会自动 guard shader。
- [ ] 稳定版 `textureBlur(blurRadius: Float)` 仍按 dp 语义读取；新的 `GlassMaterial.blurRadius` 是 **Dp**。不要把两套参数类型混用。
- [ ] 解决现有小号文字 / 蓝底白字对比度风险，集中配置配对；Glass 默认色也须在采样背景、深色和 Monet 下重新测量。
- [ ] 在现有开发者入口增加材质检查样例与降级验证（不新增一级产品 tab），可同时比较 Glass / Opaque 的顶栏、导航、Tab、菜单与确认框。

**完成条件**：课程表 / 设置能共享真实采样及统一材质，前景字清晰，禁用玻璃后没有透明漏洞、自采样、双影或双 Insets。

## 3. P0 — 首批必须可见的新视觉

### G01 · 用 GlassNavigationBar 替换手工裁剪的底部栏

组件：`GlassNavigationBar` + `GlassNavigationItem`。入口：[App.kt:350–403](<shared/src/commonMain/kotlin/io/github/betterzslf/App.kt#L350-L403>)、[导航策略](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/NavigationLayoutPolicy.kt>)。依赖：D01。

- [ ] 替换 `Column + NavigationBar + RoundedCornerShape` 的外形拼装，直接采用官方胶囊、材质、指示器、按压与拖动回弹。
- [ ] 四项及顺序仍为课程表 / 校园跑 / 校园网 / 我的；保留文字标签和四个独立返回栈，不新增首页 / 服务，也不改启动 tab。
- [ ] 将 `onSelect` 接入现有选择策略；该回调会在按下和拖动跨项时触发，不能假设只在松手时触发。去重选择，不触发清栈、网络重置或业务提交。
- [ ] 采用其 `visible` 生命周期，不用 `if (visible)` 立刻删组合截断退出动画；隐藏期间不得保留透明可点击区域。
- [ ] 选用一方管理导航安全区：由库处理，或 `defaultWindowInsetsPadding = false` 后由 Shell 完整管理；重新测量最终占位，不套旧的固定高度 / 重复 navigationBarsPadding。
- [ ] 保留 `max(scaffoldBottom, measuredOverlayInset)` 的内容预留原则，覆盖大字体两行标签、三键导航、iOS 手势区和横屏。
- [ ] 选中 / 按下颜色优先使用最新源码的主题默认。主线已支持 Monet 强调色，不能照旧文档强行盖成黑白。

**完成条件**：真实玻璃底栏、官方可拖动胶囊和四项标签同时可见；拖动 / 取消 / 返回 / 窗口缩放 / 键盘与屏幕阅读器正确，状态不丢。`trailingAction` 暂不加假搜索按钮；有真实动作再使用其官方宽度 / 显隐动画。

### G02 · 采用 GlassTopAppBar 的渐进背景和标题转场

组件：`GlassTopAppBar`、`GlassIconButton`；入口：[Screen](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/Components.kt#L65-L123>)。依赖：D01。

- [ ] 一级可滚动页优先替换为 `GlassTopAppBar`，保留 `MiuixScrollBehavior`；明确设置源码已有但部分文档未列出的 **`progressiveBlur = true`**。
- [ ] 将真实列表状态传为 `isContentScrolled = listState.canScrollBackward`（或对应 ScrollState），回到顶部时正确收起材质；需要时扩展共享 `PageList` 的 state 入口，不能额外叠一层滚动容器。
- [ ] 使用 `largeTitleBlurRadius` 与官方背景带，沿用组件内部 GlassMotion 完成标题淡出模糊；不把内部动画对象当公共 API 调用，也不另造折叠动画。减少动态效果时提供低动态变体。
- [ ] 导航 / 操作图标用 `GlassIconButton`；默认视觉按钮 44dp 与项目 48dp 目标需协调，优先通过库尺寸能力统一处理，不叠第二个点击目标。
- [ ] 二级页仍保持紧凑标题：使用原生 `SmallTopAppBar` 配合 Glass 操作按钮与共享材质，或经验证的紧凑变体。主线没有可直接假定的 `GlassSmallTopAppBar`，禁止为用 Glass 强行给每页加大标题。
- [ ] 移交系统顶部安全区处理，消除当前 `Screen` 手动状态栏 inset 与新顶栏始终添加的 top inset 的重复；允许背景带和按钮阴影延伸，不被旧 clip 截断。

**完成条件**：顶部静止、滚动折叠、滚回顶部、快速返回均无空带 / 双标题 / 模糊操作图标；仅大标题按库动画短暂模糊，稳定态可读。地图和 WebView 页无法采样时正确不透明回退。

## 4. P1 — Glass 配套组件成套用起来

### G03 · 用 Glass Tab 统一筛选控件

组件：`GlassTabRow`、`GlassSegmentedTabRow`。入口：[校园跑筛选](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/run/RunPages.kt#L75-L91>)、[开发者日志筛选](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/devtools/DebugSettingsScreen.kt#L82-L94>)。依赖：G02。

- [ ] 校园跑“全部 / 未完成 / 已完成”改为 `GlassTabRow`，置于共享顶栏 `bottomContent` 或确定的吸顶区域，跟随页面采样与滚动状态。
- [ ] 日志“全部 / 警告 / 错误”改为 `GlassSegmentedTabRow`；其 `backdrop` 必须非空，无采样能力时整体回退到基础 `TabRowWithContour`，不能传 null 硬凑。
- [ ] 重点内容本身已用强调色时选 neutral 配方；需要强调时用库 primary 配方，统一角色，不逐页手画轨道。
- [ ] `surfaceAlpha` 只控制材质 / 绘制进度，0 不会禁用点击；隐藏或关闭状态必须单独处理语义和输入。
- [ ] 保留现有筛选枚举、分页与请求防重，选中才改变业务状态；不因加动画重新加载整页。

**完成条件**：两个筛选场景都真实迁移，选中指示器、滚动材质与正文一致；320dp / 大字体不裁文案。必要时使用有界滚动或原生可读替代，不压小字号。

### G04 · Settings 采用 GlassDropdownPreference

组件：`GlassDropdownPreference`，必要时 `GlassDropdownPopup`。入口：[SettingsScreens.kt](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/SettingsScreens.kt#L27-L53>)。依赖：D01。

- [ ] 主题模式 / 单位等已有离散设置从 `WindowDropdownPreference` 迁移到玻璃下拉设置行；采样由共享页面提供，不让每行重复创建 backdrop。
- [ ] 选中值、未选中、空选项、禁用、打开时配置失效的行为沿用库；设置保存失败必须还原显示值并留可读错误。
- [ ] 提醒提前量从横向滚动 TextButton 组迁移为同款设置行，保留已有非均匀选项 0 / 5 / 10 / 15 / 30 分钟及当前合法值，不偷偷更改业务范围。
- [ ] 保持 Miuix Scaffold host、根层渲染和不透明 fallback；展开收起由组件管理，不额外 `if (expanded)` 切断动画。

**完成条件**：设置和提醒有一致的官方展开 / 选择 / 关闭体验，长选项不越界，键盘和窗口变化后锚点不漂移。

### G05 · 确认框与普通详情 Sheet 采用官方玻璃表面

组件：`GlassDialog`、`GlassDialogButton`、`GlassBottomSheet`，或 `glassDialogSurface` / `glassBottomSheetSurface`。入口：[ConfirmDialog](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/FeaturePages.kt#L27-L35>)、[跑步详情弹层](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/run/RunTrackerPage.kt>)、[导入参数弹层](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/run/RunImportPage.kt#L207-L241>)。依赖：D01。

- [ ] 首选通过现有 `OverlayDialog.surface` 接入 `glassDialogSurface`，保留业务和宿主；简单调用可统一迁移为 `GlassDialog`。两种只选一个共享入口，避免双 host。
- [ ] 用 `GlassDialogButtonRole.Normal / Primary / Destructive` 表达按钮语义；确认、危险操作和处理中仍基于真实状态，不借迁移省略二次确认。
- [ ] 跑步详情和参数 Sheet 用 `GlassBottomSheet` 或对应 surface 插槽；不额外再画一层遮罩、blur 和背景采样。
- [ ] `show = false` 后保持组件组合直到离场完成；需要释放或路由动作时用 `onDismissFinished`，不能直接 if-show 卸载。
- [ ] [手动地图编辑 Sheet](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/run/RunManualDraftSheet.kt#L49-L95>) 保留当前 host 和状态机，优先只接表面工厂；`allowDismiss = false`、`enableNestedScroll = false`、预测返回与会话失效策略不得被简化。

**完成条件**：退出 / 清除 / 修改密码 / 跑步操作仍准确确认；遮罩不自采样，短屏 / 键盘下按钮可达，快速重复打开关闭不漏回调。原生地图无法被采样时使用主题不透明回退，不能变成空洞。

### G06 · 工具栏更多操作采用 GlassTransformPopup

组件：`GlassTransformPopup`、`GlassPopupItem`、`rememberGlassPopupAnchor`；常规锚定菜单可用 `GlassPopup`。入口：[开发者操作](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/devtools/DebugSettingsScreen.kt#L95-L107>)、[校园跑操作](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/run/RunPages.kt>)。依赖：G02、G05。

- [ ] 先把开发者“导出 / 清除”集中到真实的“更多”操作，采用官方按钮 → 菜单变换；清除仍进入原确认流程。
- [ ] 用 `glassPopupAnchor` / `glassPopupAnchorContent` 共享锚点；菜单是**整页根 Box 的最后一层**，坐标原点与锚点一致，不塞进已经 padding / offset 的列表 item。
- [ ] 处理系统栏、IME、安全边距及返回 dispatcher；它不是简单的 Scaffold popup host 组件，不能照 Dialog 的接法嵌进去。
- [ ] 不为单项操作强行制造级联菜单。确有二级选项时才使用 `GlassSecondaryPopup`，保留完整键盘 / 返回层级。

**完成条件**：菜单变换能实际看到，窗口缩放和横竖屏下锚点正确；取消关闭不执行动作，导出和清除无重复调用。

### G07 · Glass 图标与真实悬浮工具

组件：`MiuixIcons.Glass.*`、`GlassIconButton`。入口：[自绘 BackChevron / 品牌基础组件](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/DesignComponents.kt>)、课表管理与地图工具。依赖：G01、G02。

- [ ] 接入同版本 `miuix-glass-icons`，玻璃按钮优先使用 28dp 画布的 Glass 图标；返回用 `ChevronBackward`，不能把水平箭头 `Back` 当尖括号。
- [ ] 同一组控件统一图标字重，优先 Regular，必要时选中态用 Medium；库有五种字重，不自行拉伸描边。
- [ ] 将现有地图“定位 / 重置视图”等真实悬浮操作逐步接为 `GlassIconButton`；平台地图采样不可靠时使用官方不透明回退，不截图假模糊。
- [ ] 普通实色列表图标可继续用 `MiuixIcons` 24dp；不要全量替换成风格不匹配的 Glass 图标。

**完成条件**：不再依赖自绘返回符号来模拟新风格；RTL、图标完整轮廓、48dp 目标和可读描述通过验证。

## 5. 稳定版已有但项目未充分采用的能力

### S01 · 密码字段直接使用 trailingIcon（P0）

组件：MIUIX `TextField.trailingIcon`、`IconButton`、`TooltipBox`。入口：[宿舍登录密码](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/LoginScreen.kt#L424-L451>)、[教务绑定](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/schedule/ScheduleBinding.kt#L81-L90>)，以及其他密码表单。依赖：无需等 Glass，可先做。

- [ ] 删除“MIUIX TextField 没有 trailing slot”的过时假设与注释；**稳定版 0.9.4 已有 `trailingIcon`**。
- [ ] 将单独占一行的显示 / 隐藏密码操作移到输入框 trailingIcon，统一眼睛图标、描述、Tooltip 和点击目标。
- [ ] 保留输入选择、焦点、测试语义、提交禁用条件；密码仍不持久化、不恢复、不记日志。不能让显示密码开关意外触发提交或账号重验。

**完成条件**：表单更紧凑，所有密码可达且安全；键盘打开 / 130% 字号不遮挡，开关前后内容和选择稳定。

### S02 · SliderPreference 调整采样间隔（P1）

入口：[设置采样项](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/SettingsScreens.kt#L43-L53>)、[SettingsStore](<shared/src/commonMain/kotlin/io/github/betterzslf/feature/settings/SettingsStore.kt#L33-L40>)。

- [ ] 用 `SliderPreference` 展示数值和语义关键点，替换简单下拉；项目当前允许整数 **10–60 秒**，可采用整数档位与 10 / 15 / 30 / 60 关键点。
- [ ] 若只保留原先非均匀快捷选项，必须做 index → 秒数映射，不能用均匀 steps 冒充 10 / 15 / 30 / 60。
- [ ] 拖动只更新本地草稿，在 `onValueChangeFinished` 提交一次；失败回滚并反馈，不每帧持久化 / 重启业务采样。
- [ ] `valueText` 始终显示准确秒数；当前 42 等非预设合法值不能被进入页面时自动改成最近档。

**完成条件**：可键盘 / 无障碍调节，存储次数受控；旧设置读写和范围不变，不增加不必要的震动。

### S03 · RadioButtonPreference 替换自绘单选卡（P1）

入口：[校园网账号卡](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/campusnet/CampusNetworkFeaturePage.kt#L294-L315>)、[学期选择](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/schedule/ScheduleSecondaryPages.kt#L30-L98>)。

- [ ] 校园网账号选择用 `RadioButtonPreference` 统一选择标识与整行点击，移除“浅蓝卡 + 全宽选择按钮”的冗余组合。
- [ ] 保留 maskedAccount、只读 / 无效 ID 说明、手动展开 gate、busy / terminal 条件及最终确认；进入页面绝不自动选中后执行改密。
- [ ] 学期长名称选项优先单选列表或配套 Dropdown，避免固定两列胶囊截断真实名称；周次选择保持可见上下文，不把 20 周全塞进水平 Tab。
- [ ] [课表周次入口](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/schedule/SchedulePage.kt#L258-L269>) 不再将周次文字和 Unicode ▾ 分成两个点击目标；改用统一的 GlassPopup / 官方图标入口，并保留完整学期周次页、周 0 和中间空周的可达性。

**完成条件**：单选状态有原生语义，选中与不可选状态可区分，服务端选项变化不会选错账号 / 学期。

### S04 · SearchBar / InputField 用于日志本地检索（P1）

入口：[DebugSettingsScreen.kt](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/devtools/DebugSettingsScreen.kt>)、[日志窗口 / 行](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/devtools/DebugLogViewer.kt>)。

- [ ] 在已有本地日志筛选上接 `SearchBar` + `InputField`，按 tag / message / level 检索当前保留的日志窗口，无需新增网络接口。
- [ ] 保持记录条数上限、级别筛选与单一 LazyColumn；说明搜索范围，不把“无匹配”当作“无日志”。
- [ ] 正确处理搜索展开 / 返回 / 清除 / 键盘焦点，导出仍遵守原脱敏规则与全量导出语义，不能悄悄改为只导出搜索结果。

**完成条件**：搜索、级别筛选、刷新和清除可组合；不重复嵌套滚动。`InputField` 是查询输入，不拿来替代密码 / 登录 `TextField`。

### S05 · Tooltip / Badge / Snackbar 形成统一反馈（P1）

入口：共享 [Screen](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/Components.kt>)、[开发者日志](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/devtools/DebugLogViewer.kt>)、课表管理和账号操作。

- [ ] 纯图标操作统一 `TooltipBox` / `PlainTooltip`，长按和鼠标悬停可看名称，同时保留 contentDescription，不仅依赖悬停。
- [ ] 已有日志等级 `Badge` 保留并收敛主题；用 MIUIX `Badge` 替换 [RunStatus 的自绘彩色文字胶囊](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/run/RunWidgets.kt#L42-L52>)，统一深浅状态色。真实计数使用 `BadgedBox` 或支持的导航 badge 插槽，不制造未读红点。
- [ ] [跑步记录页已有待上传计数](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/run/RunTrackerPage.kt#L62-L65>) 时，在本地记录动作上使用 BadgedBox 展示该真实计数；不能把所有未提交记录等同上传失败，也不擅自增加不存在的 GlassNavigationItem badge 参数。
- [ ] 在现有 Scaffold 接共享 `SnackbarHostState` / `SnackbarHost`，用于保存、复制、导出这类非阻断真实回执；遮挡浮动导航时用实测占位避让。
- [ ] 服务失败、权限问题、离线和需要用户持续阅读的错误保留 `InfoBanner`，不全改成一闪而过的 Snackbar。

**完成条件**：同一事件不重复弹两种反馈，跨导航无过期消息；TalkBack 不重复播报，回执不泄露敏感信息。

## 6. P2 — 宽屏、主题和深层交互

### S06 · 启用 NavigationRail 可展开版本

组件：`rememberNavigationRailState`、带 state 的 `NavigationRail`。入口：[宽屏 Shell](<shared/src/commonMain/kotlin/io/github/betterzslf/App.kt#L375-L403>)、[布局策略](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/NavigationLayoutPolicy.kt>)。依赖：G01。

- [ ] 宽屏仍用库 Rail，不把底部 GlassNavigationBar 拉成长竖条；在有足够内容宽度的窗口启用官方展开 / 折叠状态与按钮，600dp 附近保持紧凑。
- [ ] 区分固定布局 `expanded` 重载与交互式 `state` 重载，采用后者管理用户切换；状态跟随窗口恢复，但不拥有或重建四栈。
- [ ] 展开宽度、选中药丸和动画优先库默认；根据实测剩余宽度决定可展开门槛，不把 600dp 直接等同于“必须展开”。

**完成条件**：紧凑 / 宽屏 / 展开来回切换均不丢列表位置、表单和返回历史；横屏挖孔不遮 Rail，内容没有被 240dp 侧栏挤坏。

### S07 · 启用新版动态主题能力，而非只换主色

组件：`ThemeController`、`ThemeColorSpec`、`ThemePaletteStyle`。入口：[主题应用](<shared/src/commonMain/kotlin/io/github/betterzslf/App.kt#L233-L239>)、[设置模型](<shared/src/commonMain/kotlin/io/github/betterzslf/feature/settings/SettingsStore.kt>)、[外观设置](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/SettingsScreens.kt#L27-L41>)。

- [ ] 将稳定版已有的 `ThemeColorSpec.Spec2025` 纳入动态色目标；优先 TonalSpot / Neutral，通过统一主题配置使用，不能逐页生成调色板。
- [ ] 在现有外观设置中提供精简的调色板风格选择和预览；用新 GlassDropdownPreference，动态色关闭时隐藏或禁用并解释，不新增独立“主题商店”。
- [ ] 最新主线新增的 `ThemePaletteStyle.Cmf` 可作为明确标注的高级预览项；它在库内使用 Spec2026，**没有可凭空填写的 `ThemeColorSpec.Spec2026` 枚举值**。先验证实际可读性再向普通用户开放。
- [ ] 深浅色、背景、卡片、图标、选中、按钮和弹层同时切换；修复 fixed-color 岛，不把业务红黄绿随壁纸任意改色。
- [ ] 保留旧设置默认与失败回退，新增字段可迁移；切换主题不重建业务图、清空草稿或注销会话。

**完成条件**：至少两种壁纸色的浅 / 深截图证明完整覆盖；各类配对合格，切换无白闪。不是“API 已存在”就把所有 palette 全部铺成设置项。

### S08 · 数值选择与层级交互按真实场景接入

- [ ] 在 [作息校准](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/schedule/SchedulePeriodsPage.kt#L49-L65>) 的开始 / 结束时间编辑中采用 MIUIX `NumberPicker`（时 / 分两列）作为输入辅助，放入 G05 的 Sheet；保留精确键盘输入和 `HH:mm` / 时间不重叠验证，不用无界滑块选时间。
- [ ] Picker 在本地草稿中编辑，确认后更新原 editor 并使旧预览失效；取消不改原值，不绕过现有“预览影响 → 确认保存”。大字体不可读时退回文本，不重复上报触觉。
- [ ] `BreadcrumbBar` / `BreadcrumbItem` 只在需要可点击祖先导航的深层管理或文档路径中采用；单层详情不为了用新组件加三条导航，且绝不展示凭据或本地敏感路径。接入须映射现有 route，不能另建并行历史。
- [ ] 将 [课表日列表的自绘日期条](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/schedule/SchedulePage.kt#L314-L347>) 收敛到官方 Tab；在现有“按天看课”的场景接 Foundation `HorizontalPager` 与 MIUIX `springAnimateToPage` / `pagerGestureOverride`，采用原生弹簧切日及纵横手势协调，不再自研滑动状态。
- [ ] 日 Pager 保留隐藏日期策略、选中日期、窄屏 / 大字日列表、未排课与不确定内容；缓存切日不触发网络拉取或改变选周。**不强加四个主 tab 的 swipe Pager**，不与地图拖动或预测返回抢手势。
- [ ] `RangeSliderPreference` 需要真实双端范围，当前没有对应设置，不虚构业务参数；`miuix-window` 主要用于桌面窗口，本项目未发布桌面应用，不为“追新”引入无用模块。

**完成条件**：作息时间辅助能实际操作并经过验证；条件型能力有明确场景才落地，不把整套 UI 变成组件目录。

## 7. V01 — 最终验收与交付证据

依赖：D00–D01、G01–G07、S01–S07；S08 的条件型能力不阻塞已明确迁移的核心组件。

- [ ] Android / iOS、Standard / Pro 构建与现有设计系统 / 导航 / 课表测试通过；实现方式见 [BUILDING.md](<BUILDING.md>)，测试不仅检查旧的硬编码像素。
- [ ] 更新 [DesignSystemTest](<shared/src/commonTest/kotlin/io/github/betterzslf/ui/DesignSystemTest.kt>) 与 [NavigationLayoutPolicyTest](<shared/src/commonTest/kotlin/io/github/betterzslf/ui/NavigationLayoutPolicyTest.kt>)，覆盖新占位、拖动选择去重、四栈恢复与动态色配对；不为升级删掉业务不变量。
- [ ] 交付“课程表 / 设置 / 校园跑筛选 / 一份确认框 / 一份 Sheet”的**前后对比**，包含实际 Glass 与 Opaque 两档，不只提交代码截图。
- [ ] 320 / 390 / 600 / 840dp、100% / 130% / 200% 字号、浅 / 深 / Monet、键盘 / 三键导航 / 横屏均可用；新组件默认固定高度与字号策略同样需要审查。
- [ ] 屏幕阅读器、键盘焦点、拖动和取消、预测返回、弹层半途再打开、切后台恢复全部验证。透明度 0 不得留下点击阻挡。
- [ ] 测试滑动帧耗时、键盘 / 转场卡顿、GPU 与静态能耗；为 Glass 与 Opaque 使用同一场景记录结果，未达标时降低共享材质档位，不逐页打碎效果。
- [ ] 实测地图 / WebView 采样边界；无法采样时完整回退，禁用 / shader 不支持路径不出现空洞和伪磨砂。
- [ ] 更新 [DESIGN.md](<DESIGN.md>) 的“当前实现”部分和本页完成项，记录实际依赖版本、验收平台及未完成项；没有实机验证的功能不得标为完成。

## 8. 版本依据与查阅入口

以下来源已核实；线上文档可能先于发布包更新，**同一版本发生冲突时以固定 SHA 的公共源码为准**。

- 稳定版：[GitHub latest release API](https://api.github.com/repos/compose-miuix-ui/miuix/releases/latest)、[Maven Central metadata](https://repo.maven.apache.org/maven2/top/yukonga/miuix/kmp/miuix-ui/maven-metadata.xml)。
- 新功能固定提交：[45645854](https://github.com/compose-miuix-ui/miuix/commit/45645854cd46c32275c5666e3aafa9980d8f104e)；[与 v0.9.4 的比较](https://github.com/compose-miuix-ui/miuix/compare/v0.9.4...45645854cd46c32275c5666e3aafa9980d8f104e)。
- 已发布开发版：[miuix-glass 包](https://github.com/compose-miuix-ui/miuix/packages/3372718)、[Android 包](https://github.com/compose-miuix-ui/miuix/packages/3372712)、[对应发布任务](https://github.com/compose-miuix-ui/miuix/actions/runs/42578007090)。发布存在不等于本项目所有变体已解析通过。
- [Glass 组件总览](https://github.com/compose-miuix-ui/miuix/blob/45645854cd46c32275c5666e3aafa9980d8f104e/docs/zh_CN/components/glass/index.md)、[材质 / 背景采样指南](https://github.com/compose-miuix-ui/miuix/blob/45645854cd46c32275c5666e3aafa9980d8f104e/docs/zh_CN/guide/glass.md)。
- [GlassNavigationBar 公共源码](https://github.com/compose-miuix-ui/miuix/blob/45645854cd46c32275c5666e3aafa9980d8f104e/miuix-glass/src/commonMain/kotlin/top/yukonga/miuix/kmp/glass/GlassNavigationBar.kt)：拖动选择、动态主题色、visible 与尾部动作。
- [GlassTopAppBar 公共源码](https://github.com/compose-miuix-ui/miuix/blob/45645854cd46c32275c5666e3aafa9980d8f104e/miuix-glass/src/commonMain/kotlin/top/yukonga/miuix/kmp/glass/GlassTopAppBar.kt)：`progressiveBlur`、`material`、`backgroundAlpha` 和精确滚动状态重载。
- [玻璃弹层指南](https://github.com/compose-miuix-ui/miuix/blob/45645854cd46c32275c5666e3aafa9980d8f104e/docs/zh_CN/components/glass/glassdialog.md)、[图标指南](https://github.com/compose-miuix-ui/miuix/blob/45645854cd46c32275c5666e3aafa9980d8f104e/docs/zh_CN/guide/icons.md)。
- [TextField 稳定版源码](https://github.com/compose-miuix-ui/miuix/blob/v0.9.4/miuix-ui/src/commonMain/kotlin/top/yukonga/miuix/kmp/basic/TextField.kt)、[SliderPreference 稳定版源码](https://github.com/compose-miuix-ui/miuix/blob/v0.9.4/miuix-preference/src/commonMain/kotlin/top/yukonga/miuix/kmp/preference/SliderPreference.kt)、[最新主题控制器](https://github.com/compose-miuix-ui/miuix/blob/45645854cd46c32275c5666e3aafa9980d8f104e/miuix-ui/src/commonMain/kotlin/top/yukonga/miuix/kmp/theme/ThemeController.kt)。

**最终方向：追最新官方组件，成套采用，固定版本；旧实现可以替换，业务安全和可用性不能退步。**
