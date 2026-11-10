# better-zslf 视觉与交互规范

> **MIUIX 优先，设计稿辅助；统一体验，不逐像素复刻。**
>
> 适用于 Android / iOS 的共享 Compose UI，以及 Standard / Pro 两个编译版本。
> 本文是后续新页面、视觉重构和代码审查的共同基准，不代表所有目标已实现。
> 核对日期：2026-11-09；当前依赖 / 最新稳定版 **0.9.4**。
> 新视觉目标：已发布的 **0.9.4-45645854-SNAPSHOT**（固定提交 `45645854cd46c32275c5666e3aafa9980d8f104e`），采用最新官方 OS4 Glass；尚未在本项目接入或编译验证。

**使用方式**：具体升级与组件采用按 [DESIGN TODO](<DESIGN-TODO.md>) 执行；本文记录统一规则。做页面先读第 1–2 节和第 6 节；改主题查第 3–5 节；做光影查第 7 节；提交前按第 10 节验收。遇到冲突，不以截图像素覆盖组件行为，也不以“保持旧实现”为由拒绝官方新组件。

## 1. 设计方向与决策顺序

目标是做一个**精致、克制、原生感强的 HyperOS / MIUIX 校园工具**：清晰的大标题、安静的中性底色、分组曲面、少量强调色、连续圆角、自然反馈。最好看的效果来自一致的材质和层级，不是给每个元素加特效。

发生冲突时，按以下顺序决策：

1. **可用性与真实状态**：文字可读、触控可达、辅助功能可用、数据状态不误导。
2. **MIUIX 原生组件及行为**：主题、形状、反馈、弹层、导航和转场优先使用库实现。
3. **本项目共享 token 与封装**：只在一个地方适配产品视觉，不让页面分别解释设计。
4. **Ardot 信息层级与布局意图**：保留重点、分组、主次关系；具体数值服从前面三项。
5. **官方新材质与组件**：按已核实版本成套采用 Glass 导航、顶栏、Tab、菜单和弹层，必须可降级；不是保留旧壳后只加手写特效。

**MIUIX 优先不等于每页直接使用库默认值。** 项目可以通过一份 `MiuixTheme` 和薄封装统一品牌色、字号及尺寸；不 fork 库、不另造一套 Material 风格系统、不通过额外手势覆盖库交互。

### 1.1 必须 / 推荐 / 可选

- **必须**：共享主题、语义颜色、完整状态、安全区、大字体适配，以及 [待办](<DESIGN-TODO.md>) 首批指定的最新官方组件迁移。
- **采用目标**：`GlassNavigationBar` 的官方拖动胶囊、`GlassTopAppBar(progressiveBlur = true)`、Glass Tab / Dropdown / Dialog / Sheet；以真实设备验收后作为正常支持路径，旧基础组件保留完整降级。
- **推荐**：普通内容面使用原生连续圆角、可点卡片 Sink、MIUIX 导航转场、配套 Glass 图标与材质预设。
- **可选**：自定义光源、陀螺仪视差、无明确场景的组件；不能为追求组件覆盖率新增假功能。
- **不采用**：全屏玻璃、每张普通卡片都加特效、自研拖拽胶囊、自研大标题折叠、重复按压缩放。官方新组件自身的拖动 / 转场 / 描边不是禁用对象。

### 1.2 设计来源与已确定取舍

| 来源 | 用途 | 本项目决策 |
| --- | --- | --- |
| [P00 首页，44:47](https://ardot.tencent.com/file/740674213003352?node_id=44%3A47) / [深色，44:184](https://ardot.tencent.com/file/740674213003352?node_id=44%3A184) | 大标题、下一节重点卡、今日课程分组、浮动导航的视觉意图 | 可用于内容层级参考；不据此新增首页或改变启动页 |
| [P46 服务，44:298](https://ardot.tencent.com/file/740674213003352?node_id=44%3A298) / [深色，44:392](https://ardot.tencent.com/file/740674213003352?node_id=44%3A392) | 分组列表、说明文字、箭头入口 | 优先用 MIUIX Preference；不据此新增一级服务入口 |
| [材质板，24:1](https://ardot.tencent.com/file/740674213003352?node_id=24%3A1) / [交互板，24:69](https://ardot.tencent.com/file/740674213003352?node_id=24%3A69) | 曲率、模糊、高光和状态探索 | 标注中有待验证值及相互冲突的描述，不能整段照抄为代码 |
| [导航板，24:22](https://ardot.tencent.com/file/740674213003352?node_id=24%3A22) | 紧凑 / 宽屏展示参考 | 保留四 tab 和独立返回栈；以官方 GlassNavigationBar 实现现成的拖动指示器，不自绘或增加第五项 |

明确取舍：

- 保留当前 **课程表 → 校园跑 → 校园网 → 我的**。视觉统一不附带信息架构迁移。
- 大标题读主题角色；保留项目 `title1 = 28sp`、`title3 = 18sp`，不逐页追稿里的 30 / 34sp。
- 使用库原生折叠几何；0.9.4 的 `TopAppBarDefaults.CollapsedHeight = 52.dp`，不是可配置的“96dp 折叠阈值”。
- 自绘曲面跟随 MIUIX 曲率默认值；不把画布 `cornerSmoothing = 0.6` 换算成全局 `extension = 1.20f`。
- 深色普通卡采用项目已提升的 **#22262D**，不回退到稿中较暗的 #1A1C21。
- 稿里的浅灰标签、蓝底白字、小号彩色说明不自动视为对比度合格，需按第 3 节验收。

## 2. 技术基线与唯一实现入口

当前使用 Compose Multiplatform；Android 最低 API 33，iOS 使用共享 UI；JVM 仅用于宿主测试，不是待发布桌面端。

- 依赖版本：[版本目录](<gradle/libs.versions.toml#L1-L25>)。
- 已直接接入 `miuix-ui` / `miuix-preference` / `miuix-icons` / `miuix-nav` / `miuix-blur`：[共享构建配置](<shared/build.gradle.kts#L75-L91>)。
- 当前 `miuix-squircle` 由依赖链提供；新目标显式接入同构建的 `miuix-glass` / `miuix-glass-icons`，业务直接使用的 squircle / shader 同样显式声明。全部 MIUIX 模块统一到一个发布构建，不把最新 Glass 与旧 0.9.4 基础库混装。
- 最新开发版的发布证据、实际解析 / 编译任务及源码缺失变体的回退方案见 [D00](<DESIGN-TODO.md>)；库版本字符串仍以 0.9.4 开头不表示 Glass 已在稳定版中。

| 职责 | 优先复用的现有入口 |
| --- | --- |
| 主题、尺寸、字号、语义状态、主操作 | [DesignComponents.kt](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/DesignComponents.kt>)：`AppUiTokens`、`ardotLightColors` / `ardotDarkColors`、`ardotTextStyles`、`UiTone`、`InfoBanner`、`MetricStrip`、`PrimaryAction`、`SectionTitle` |
| 页面外壳、滚动、安全区、顶栏材质 | [Components.kt](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/Components.kt>)：`Screen`、`PageList`、`SectionCard`、`EmptyCard`、`AsyncContent` |
| 主题应用、主导航、转场 | [App.kt](<shared/src/commonMain/kotlin/io/github/betterzslf/App.kt#L233-L422>) |
| 紧凑 / 宽屏策略 | [NavigationLayoutPolicy.kt](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/NavigationLayoutPolicy.kt>) |
| 通用确认弹窗 | [FeaturePages.kt](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/FeaturePages.kt#L27-L35>)：`ConfirmDialog` |
| 课表领域配色 | [ScheduleTokens.kt](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/schedule/ScheduleTokens.kt>) |

新增页面先组合上述入口。确需新增变体时，先扩展共享封装并写明用途，不能在单页复制 token 或新建第二份主题。现有 `ardot*` 函数名是历史命名，不表示设计稿高于 MIUIX。

**状态标记**：下文“当前”指源码核对结果；“规范 / 目标”指后续实现要求；“可选”不构成默认启用许可。本次仅建立文档，不宣称完成 UI 重构或实机验收。

## 3. 颜色：用主题角色，不用页面色板

### 3.1 当前共享品牌基准

默认跟随系统深浅色，动态取色默认关闭；开启后使用 `ThemeController` 的 Monet 模式。深浅色和动态取色必须覆盖完整页面，不能只有按钮换色。

下表记录当前固定品牌主题。**hex 仅用于维护中央主题与校验，业务页面必须读取角色。**

| 视觉角色 | MIUIX / 项目读取位置 | 浅色 | 深色 |
| --- | --- | --- | --- |
| 页面底 | `colorScheme.surface` | #F2F2F5 | #0D0F12 |
| 普通卡片 / 导航曲面 | `surfaceContainer` | #FFFFFF | #22262D |
| 卡片主文字 | `onSurfaceContainer` | #1A1A1A | #F2F2F5 |
| 页面主文字 | `onSurface` | #1A1A1A | #F2F2F5 |
| 次级说明 | `onSurfaceVariantSummary` | #757578 | #99A1A8 |
| 品牌强调 | `primary` | #3382FF | #5C94FF |
| 柔和强调底 | `primaryContainer` | #E3EDFF | #1F2940 |
| 柔和强调内容 | `onPrimaryContainer` | 随共享主题 | 随共享主题 |
| 分隔 / 轮廓 | `dividerLine` / `outline` | #E5E5EB | #292E36 |
| 正向状态 | `UiTone.Positive` 的共享解析 | #33C759 | #4DD466 |
| 提醒状态 | `UiTone.Warning` 的共享解析 | #FF9400 | #FFA11A |
| 错误状态 | `colorScheme.error` / 统一 Negative 解析 | #FA3B3B | #FF4D59 |

注意项目当前的 `background` 是白色 / #1A1C21，**不等于页面底色**。不要按其他 UI 库的命名习惯直接换用它。`secondaryContainer` 当前也被用于部分卡片；新普通卡片优先由 `CardDefaults` 取 `surfaceContainer`，旧用法集中收敛，不逐页换色。

### 3.2 用色规则

- 全页只设一个主视觉焦点。强调色用于主要操作、当前选中、进度与关键数值，不把所有标题和图标染蓝。
- 状态色必须带文案或图标；“已连接 / 失败 / 正在处理”不能只靠绿点、红点区分。
- 提示容器可以使用语义色约 10% 的柔和底，但重要正文仍用可读文字角色。10% 是产品适配起点，不是所有背景上的通用安全值。
- 成功 / 提醒 / 错误是业务语义，不随壁纸变成任意色；普通强调和导航跟随 Monet。
- 不用 `Color.White` 当作所有深色按钮、浮层和地图标签的文字色；容器和内容色必须成对。
- 深色靠**曲面明度差**建立层级，不靠粗白描边或更重黑影。#22262D 与 #0D0F12 的对比约 1.26:1，是本项目层次选择，**不是无障碍标准**。

### 3.3 对比度是硬约束，当前色值不是免检项

验收目标：普通文字至少 **4.5:1**；大字至少 **3:1**（约 24sp 常规 / 18.7sp 粗体起）；必要的控件轮廓和状态图形至少 **3:1**。按最终合成背景测量，包括透明层、渐变最差位置和动态取色。禁用态不能拿来承载仍需阅读的重要说明。

源码 / 稿中已知风险：

- 白字在 #3382FF 上约 **3.63:1**，在 #5C94FF 上约 **2.94:1**；当前 16sp 主按钮文字不能据此宣称合格。
- #757578 在白卡上约 4.59:1，在 #F2F2F5 页面底上约 **4.11:1**；小号关键说明不能直接通用。
- 稿中 #B0B0B2 在页面底上约 **1.94:1**，不能用于必要分组名、时间或可操作入口。
- 课表浅色底上的部分彩色 meta 字也不足 4.5:1，不能因为“与稿一致”而保留低可读性。

**整改决策（目标，尚未写入代码）**：主操作在共享 `PrimaryAction` / 统一按钮颜色工厂中配置可读配对，仍使用 MIUIX `Button`。固定品牌主题的目标配对为浅色 **#266BF2 + #FFFFFF**（约 4.70:1）、深色 **#5C94FF + #0D0F12**（约 6.52:1）。这些是项目动作色，不是新增的 MIUIX 内置 token；中央定义一次，不散落到页面。Monet 模式使用其语义配对并验证，不能用固定蓝覆盖动态色。

其余关键小字优先改用更强的现有文字角色；彩色错误 / 成功提示可让图标保留色相、正文用主文字色。若需要新增可读语义色，应成套修改中央主题和测试，不能在单页临时调 hex。

## 4. 字体、数字与图标

### 4.1 字体角色

使用系统字体和平台中文回退，不捆绑字体模拟设计工具里的 Inter / Sarasa。普通文字用 MIUIX `Text`；字号从 `MiuixTheme.textStyles` 读取。

| 用途 | 角色 | 当前项目基准 |
| --- | --- | --- |
| 一级展开标题 | `title1` | 28sp / 36sp，Bold |
| 内容重点标题 | `title2` | 24sp / 32sp，SemiBold |
| 折叠标题 / 二级页标题 | `title3` | 18sp / 26sp，SemiBold |
| 卡片 / 分组标题 | `title4` | 17sp / 25sp，SemiBold |
| 主要列表 / 输入文字 | `main`、`body1` | 保留库角色，当前默认 17 / 16sp |
| 普通说明 | `body2` | 当前默认 14sp |
| 辅助说明 | `footnote1` | 当前默认 13sp |
| 非关键短标签 | `footnote2` | 当前默认 11sp；不能放操作说明或错误原因 |
| 主要操作 | 共享 `PrimaryAction` | 当前 16sp SemiBold，后续只在共享入口统一调整 |

上表的字号 / 行高 / 字重是**共享样式定义**，不是对所有库组件最终绘制的断言。0.9.4 的 `TopAppBar` 只取 `title1` / `title3` 的字号，并自行使用展开 Normal、折叠 Medium 字重；不会完整继承这里的 Bold / SemiBold 和行高。接受库的排版，不为补粗或追稿 fork 顶栏。

- 页面不再自行发明 15 / 17 / 19 / 21sp 等平行层级。同一角色跨页面一致。
- 长段说明使用适当行高（建议约 1.4–1.5 倍）和自然换行，在共享说明样式适配；不能用固定高度裁剪。
- 标题可以更粗，正文不要全局加粗。不要给中文强加大字距。
- 指标数值可用 24–40sp，单位与标签保持正文 / 辅助字号；只允许用于倒计时、里程、计时等真正的主数据。
- 重要状态、错误和操作全文可读；课程长名允许紧凑卡片内截断，但必须有可达的详情页与完整无障碍语义。
- 支持系统字号；不通过反向除以 `fontScale` 或缩放整个页面来“保持设计比例”。

### 4.2 图标

优先 `MiuixIcons`，同一功能在导航、入口和详情中使用同一语义。正文操作图标通常 24dp，辅助图标 16–20dp；视觉尺寸不等于触控尺寸。

不混用 emoji、Unicode 箭头和多套线宽不同的图标当正式控件。品牌 Logo、地图符号可以自定义；返回图标需支持方向镜像。图标按钮提供可读名称；与相邻文字重复的装饰图标不重复播报。

## 5. 布局、间距与曲率

### 5.1 间距体系

使用 4dp 为基础的尺度；文字内部微间距允许 2 / 6dp。新布局不延续稿里无明确语义的 12 / 14dp 混用。

| 用途 | 标准 | 现有入口 |
| --- | --- | --- |
| 普通页面左右留白 | 20dp | `PagePadding` |
| 认证 / 独立表单左右留白 | 24dp | `FormPadding` |
| 卡片内边距 | 16dp | `CardPadding` |
| 内容项 / 卡片常规间距 | 12dp | `Gap` |
| 大分组间距 / 页尾呼吸区 | 20dp | `SectionGap` |
| 标签与其说明 / 图标与短文案 | 4 / 8dp | 按语义使用，不新建页面级尺度 |
| 主要操作最小高度 | 52dp | `ButtonHeight`，用最小高度，不锁死高度 |
| 可交互目标 | 至少 48 × 48dp | 图标和勾选区域用内边距扩展 |

Preference 行交给 MIUIX 测量和排版，不能把稿中的 52 / 60dp 当所有字号下的固定高度。表单可增高，按钮可换行，关键说明不能截断。

48dp 是项目可用性下限，不代表库默认已满足：例如 0.9.4 `IconButton` 默认最小尺寸为 40dp，应优先用其 `minWidth = 48.dp` / `minHeight = 48.dp` 扩展目标，保留库外形和反馈。密集课表若无法同时保证文字与目标尺寸，应调整密度或转为日列表；不能用相互重叠的透明点击区域假装达标。

### 5.2 曲率：同一套 MIUIX 连续圆角

| 曲面 | 标准 |
| --- | --- |
| 普通卡片 / 分组容器 | 16dp，`CardRadius` |
| 输入框 / 提示条 | 14dp，`FieldRadius` |
| 全宽主操作 | 26dp，`ButtonRadius` |
| 紧凑导航外形 | 当前 30dp；迁移 Glass 后使用官方 GlassShape 胶囊默认，不再外包同半径 clip |
| 局部重点卡 | 可用 20dp，但需先定义共享变体，不逐页自由取值 |
| Dialog / BottomSheet / 原生控件内部形状 | 跟随 MIUIX 默认，不强制改成卡片半径 |

- MIUIX `Card`、`Button` 已有 `squircleSurface`，不要再套同半径的普通圆角 clip 把连续曲率切掉；`TextField` 使用库自己的连续圆角背景 / 边框。
- 自绘实色内容面优先 `squircleSurface`；只裁剪用 `squircleClip`；只画底用 `squircleBackground`。包名为 `top.yukonga.miuix.kmp.squircle`。
- 0.9.4 的 `SquircleDefaults.Extension` 是 **1.1f**。跟随库默认，不在业务页设置 1.15 / 1.20 / 1.25 三种曲率。
- 普通矩形布局、系统窗口、地图裁剪和全胶囊可保留合适的标准 `Shape`；不能机械替换所有 `RoundedCornerShape`。
- 普通 squircle 不支持 shader 时接受库的圆角降级。Glass 使用独立的 `GlassShape.smoothing` / 材质预设，不等同于 `SquircleDefaults.Extension`；能力不足时走不透明 fallback，不能只关闭 effect 留透明底。

### 5.3 自适应与系统区域

- 390 × 844 仅是稿的参考尺寸，不是应用的固定画布。布局使用 dp、文字使用 sp，禁止照搬画布绝对坐标。
- `< 600dp` 使用紧凑导航；有限宽度 `≥ 600dp` 使用 `NavigationRail`。宽度变化只换外壳，不重建四个 tab 的状态与历史。
- 课表遵守现有自动降级：内容宽度 `< 360dp` 或 `fontScale > 1.2` 时用日列表，不把七列硬挤进窄屏。
- 普通表单 / 详情在宽屏建议居中、最大内容宽度 640dp；课表和地图可以合理铺开。该上限是后续适配目标，不描述为当前已实现。
- 状态栏、挖孔、手势区、三键导航、横屏侧边安全区由系统 Insets 提供；不绘制稿中假的 9:41、信号栏或固定 62dp 状态栏。
- 滚动列表把 Scaffold 留白放在 `contentPadding`，不要给整个列表套外层顶栏 padding 阻断穿行与模糊；优先直接复用 `PageList`。
- 底部留白使用 `max(Scaffold bottom, 实测浮动导航占位) + SectionGap`，不能猜一个 86 / 96dp 常量，也不能重复相加系统导航区。
- 键盘弹起后焦点输入和主操作必须可达；通过滚动和 IME Insets 处理，不压缩字体或把按钮覆盖在键盘后。

## 6. 组件：先选 MIUIX，再做薄适配

| 场景 | 首选 | 统一要求 |
| --- | --- | --- |
| 页面与滚动 | `Screen` + `PageList` | 不各自重造顶栏、Insets 和底部占位 |
| 一级页标题 | 目标 `GlassTopAppBar` + `MiuixScrollBehavior`；基础栏降级 | 采用官方渐进背景与标题模糊，不在正文重复画标题 |
| 二级页标题 | 原生 `SmallTopAppBar` + 配套 Glass 操作 / 共享材质 | 保留紧凑布局；不存在可凭空使用的 GlassSmallTopAppBar |
| 普通内容面 | MIUIX `Card` / `SectionCard` | 默认实色、无额外描边与投影；16dp 曲率、16dp 内容留白 |
| 设置 / 服务入口 | `ArrowPreference`、`SwitchPreference`、`RadioButtonPreference`、`SliderPreference`；离散项目标 `GlassDropdownPreference` | 原生选择语义、滑块与下拉展开，不用自绘 Row / 按钮组仿制 |
| 主 / 次操作 | `PrimaryAction`、MIUIX `Button` / `TextButton` | 一组最多一个最强操作；状态色和尺寸在共享封装统一 |
| 输入 | MIUIX `TextField` | 原生焦点、标签、光标和输入法行为；错误说明在框外 |
| 复选确认 | MIUIX `Checkbox` + 现有 `ConfirmationCheckRow` | 整行可点、单一语义，不让文字与勾选框重复触发 |
| 状态提示 | `InfoBanner` + `UiTone` | 简短状态 + 必要操作，详细诊断默认收起 |
| 加载 / 刷新 | MIUIX `CircularProgressIndicator` / `PullToRefresh` | 不引入另一套 Spinner、骨架波纹和刷新动画 |
| 确认 / 选择弹层 | 共享入口接 `GlassDialog` / `GlassBottomSheet` 或对应 surface 工厂；基础弹层降级 | 保留宿主、焦点、遮罩、返回与离场生命周期 |
| 筛选 / 查询 / 反馈 | `GlassTabRow` / `GlassSegmentedTabRow`、`SearchBar` / `InputField`、`TooltipBox`、`Badge`、`SnackbarHost` | 用于已有筛选、日志检索和真实回执，不虚构功能 / 红点 |
| 导航与转场 | 目标 `GlassNavigationBar` + 可展开 Rail + `miuix-nav` | 官方拖动 / 显隐转场，保留四栈与预测返回 |

不能从旧教程照搬未核对的 `Super*` 名称，也不能用 Material3 的 `ColorScheme`、`Typography`、`ButtonDefaults` 冒充 MIUIX API。Compose Foundation 的布局、状态、语义和动画工具可以正常使用。

### 6.1 顶栏和列表

当前 `Screen` 以有无返回回调选择大 / 小顶栏；这是待迁移现状，不是冻结实现。共享入口应接入 GlassTopAppBar 的精确滚动状态、渐进背景和 Glass 操作按钮，二级页保留紧凑变体；不要逐页隐藏标题后手绘，也不要为用 Glass 强迫所有详情页变大标题。

- `MiuixScrollBehavior` 与列表共享 nested-scroll 链，保留库折叠与回弹。
- 日期、学期、周次是内容，不需要再加一个相同的“课程表”大标题。
- 普通分组标题统一 `SectionTitle`；只做弱标签时使用共享说明角色，但必须可读。
- Preference 分组外层用 `Card(insideMargin = PaddingValues(0.dp))`，避免卡片 16dp 留白与行组件内部留白重复叠加。
- 不给每行套独立大圆角卡片。紧密相关的行放一个组，必要时使用库 `HorizontalDivider`，不要同时叠加粗分隔线、描边和阴影。

### 6.2 卡片和重点内容

- 纯展示卡不响应按压；可点卡统一 `PressFeedbackType.Sink` 并启用库 indication。`SectionCard` 已有此行为。
- 卡内有多个独立按钮时，整个外卡不要同时做同一动作的点击目标；避免嵌套点击和重复语义。
- 重点卡可以用主题强调容器，优先实色 + 清楚的信息排版。渐变只用于品牌标识或确实需要的单个重点曲面，不能染满所有卡片。
- 若以后实现 P00“下一节”，层级为：状态 / 节次 → 课程名 → 地点教师 → 剩余时间。实际时间、课程与状态来自业务数据；缺数据不渲染稿中的占位数字。
- 重点卡的倒计时不可每秒让整页重组或反复无障碍播报；按显示精度刷新，数值变化不闪烁、不反复弹跳。
- 普通卡默认不用 Ardot 的外影 + 1dp 描边组合。确需悬浮层次时先用 MIUIX `Surface` / 对应浮动组件，而不是向不存在的 `Card(elevation=..., border=...)` 传参。

### 6.3 按钮和操作状态

MIUIX 0.9.4 `Button` 有 `enabled`、`interactionSource`、`indication` 和四个颜色字段；**没有 `loading`、`pressedColor` 或 `pressFeedbackType` 参数**。原生 indication 不等于 Card 的 Sink。不要再额外统一套 0.98 缩放或按下加深 12%。

- 默认 / 按下：交给 MIUIX；主操作配色按第 3 节的中央可读配对整改。
- 不可用：`enabled = false`，使用库禁用颜色；必要时在附近说明禁用原因。
- 处理中：业务状态阻止重复提交，按钮文案变为明确的进行时。需要进度图标时，在共享主操作中组合库指示器与文字，保持宽度和最小高度稳定。
- 处理中与不可用语义不同。如需保留主色，应集中配置 loading 的禁用配对，不把“disabledColor 永远等于 primary”扩散到所有按钮。
- 成功：展示真实回执后再更新状态；不以动画播完或刷新结束代替请求成功。
- 危险操作：明确写“退出登录 / 清除数据 / 停止记录”，使用语义错误色并保留确认。不能只写“确定”，也不能靠红色替代后果说明。

### 6.4 输入与验证

源码已确认：MIUIX 0.9.4 `TextField` **自带焦点边框及标签动画**；`TextFieldColors` 只有 `backgroundColor` / `labelColor` / `borderColor`，边框默认在聚焦时出现。无需再实现一套焦点监听来重复画蓝框。

- 默认和聚焦使用库行为；统一输入框半径 14dp，正文与光标取主题角色。
- 该版本没有 Material 式 `isError` / `supportingText` 参数。错误在框下用可读文案展示，并添加错误语义；需要红色焦点框时通过共享适配设置 `borderColor`。
- 失焦后也必须看见错误原因，不能只依赖聚焦边框。不要自行给每个输入框加常驻红色外框。
- 禁用用 `enabled = false`；允许选择 / 复制但不允许修改时考虑 `readOnly`。禁用内阴影、外发光不是必要效果，不手绘模拟。
- 键盘类型、Next / Done、密码遮罩和错误定位要完整；显示密码优先直接用已有 `TextField.trailingIcon`，不再单独占一整行。标签不能仅靠 placeholder 提供，`InputField` 仅用于搜索查询，不替代密码字段。
- 提交失败保留输入；错误修正后及时撤销过期提示。不要为展示“成功”清空仍需用户核对的表单。

### 6.5 主导航

当前紧凑外壳是 MIUIX `NavigationBar` / `NavigationBarItem` 加手动裁剪，**这是迁移起点而不是目标**。新目标明确采用 `GlassNavigationBar` + `GlassNavigationItem`，使用现成的玻璃材质、拖动胶囊和显隐动画；正常支持路径不继续冻结成旧不透明壳。

- 四项图标与标签、独立返回栈和 tab 根页展示策略不变；宽屏用官方 Rail，并在足够宽时启用可展开状态。
- `onSelect` 在按下 / 拖动跨项时触发；调用方去重且只切现有栈，不触发清栈、重发请求或业务提交。点击当前 tab 仍 no-op。
- 用官方拖动和 `visible` 生命周期，不自研 pointerInput 或指示器；外形 `shape` 不等于可独立配置的 indicatorShape，不能传不存在的参数。
- 选一方处理底部 Insets 并测量占位；Glass 默认只管理底部导航 / caption 区，横向安全区与 IME 策略仍由 Shell 负责。
- `backdrop = null` 可用主题填充回退；一般面板 / 导航应由共享能力策略主动选择，不依赖文档笼统的“shader 不可用自动回退”描述。
- 稳定版 `FloatingNavigationBar` 是另一套组件，不再把它作为 Glass 的同名替代；本轮以已发布开发版的官方 Glass 组件为目标。

### 6.6 弹层与确认

Dialog 用于短确认，BottomSheet 用于局部详情或有限选择，完整表单用独立页面。新目标采用官方 `GlassDialog` / `GlassBottomSheet` 的高密度弹层材质，或通过现有 overlay 的对应 surface 插槽迁移；前景文字保持清晰，基础实色弹层是完整降级，不再一概排除玻璃弹层。

- 统一使用 MIUIX overlay host；不要嵌套创建多套 Scaffold / popup host 来解决遮罩层级。
- 按钮顺序与返回语义在全应用保持一致；关闭动作不得隐式确认或提交。
- 地图编辑、跑步记录、密码重置已有特殊返回 / 手势保护，视觉修改不能移除这些策略。
- 小屏、键盘和大字体下允许弹层滚动；标题、操作与关闭按钮不能互相挤压。

## 7. 材质与光影：精致但不堆叠

### 7.1 三级材质

| 层级 | 用途 | 材质 |
| --- | --- | --- |
| L0 页面底 | 大面积背景 | 不透明 `surface`，无渐变、无纹理 |
| L1 内容曲面 | 列表、设置组、指标、表单 | 不透明 `surfaceContainer`；靠留白和明度区分 |
| L2 系统式浮层 / Chrome | 顶栏、导航、Tab、菜单、Dialog、Sheet | 正常目标为官方 Glass 配套材质，基础实色模式完整降级；不把 blur 盖到前景文字上 |

**“最好效果”的目标组合**：最新官方 Glass 导航 / 顶栏 / Tab / 弹层 + 库自带动效和图标 + 安静实色内容卡。成套追新，不是保留旧壳后撒一层手写高光，也不是全屏玻璃 Demo。

### 7.2 顶栏模糊的正确接法

当前 `Screen` 已接入 `rememberLayerBackdrop`、内容区 `layerBackdrop` 与顶栏 `textureBlur`。迁移后该共享入口负责 `GlassTopAppBar` 与分层 backdrop，不把旧 textureBlur 再叠到 Glass 上。源码接了 modifier **不代表所有设备上最终合成已经验收**。

- backdrop 必须采集真实滚动内容，采集区域放在内容侧；不能采集顶栏自己，也不能把静态透明色当“磨砂”。
- 正文滚到顶栏后方，操作图标、输入和稳定态文字画在材质上并保持清晰；大标题折叠时允许库定义的短暂模糊淡出，不把这套动画扩散到正文。若旧顶栏实色底盖住材质，应在共享 `Screen` 校正绘制顺序 / 容器颜色，不逐页降低所有透明度。
- 不支持、性能不足或背景采集失败时，回到完整不透明主题曲面；仅关闭 effect 而留下透明顶栏不是合格降级。
- 状态栏安全区保持可靠底色；不能让文字或背景闪到刘海 / 状态图标下面。
- 地图、WebView、系统原生视图未必被同一 Compose backdrop 捕获。相关页先用不透明栏，不承诺跨原生视图的实时磨砂。

**0.9.4 参数事实**：`textureBlur` 和 `progressiveTextureBlur` 的 `blurRadius` 参数都是 **Float，单位语义是 dp**。例如 `20f` 表示 20dp，由库换算像素；不是 `20.dp`，也不是 20 物理像素。默认半径为 20f。不要混用其他版本文档里的签名。

### 7.3 最新官方材质预设与基础回退

新功能按 [DESIGN TODO](<DESIGN-TODO.md>) 成套接入，只在共享外壳集中配置。同类型控件不得各页调不同配方。

| 用途 | 目标实现 | 边界 |
| --- | --- | --- |
| 悬浮导航 / 按钮 / Tab | 官方 Glass 组件 + `GlassMaterials.puredThinGlass` | 用其形状、描边、投影和动效，不再手叠旧 blur / border |
| 一级顶栏 | `GlassTopAppBar(progressiveBlur = true)` | 官方渐进背景与标题模糊是采用任务，不再只列为远期可选 |
| 菜单 / 确认 / Sheet | `popupViewGlass` / `dialogGlass` 对应组件默认 | 沿用 overlay host、遮罩与返回；不把它们当作普通薄玻璃片 |
| 基础磨砂回退 / 明确边缘特例 | `textureBlur` 或 `progressiveTextureBlur` | 与 Glass 替代而非叠加；Top 顶边最强、Bottom 底边最强 |
| 描边 / 投影 | Glass 的 `GlassStrokes` / `GlassShadows` 默认 | 不与旧 `Highlight` 重复描边；非 Glass 场景才单独使用基础高光预设 |
| 自定义光源 / 微噪点 | 库预设优先，按需局部调整 | 不照抄稿中待验证坐标或把 noise 升到 0.03f |

`GlassMaterial.blurRadius` 使用 Dp，与基础 blur 的 Float dp 参数不同。自绘 `glassPanel` 的默认 fallback 仅裁剪；能力策略应主动选择 null backdrop 并提供主题实色底。Dialog / Sheet 专用 surface 已有其 shader guard，仍须验证最终显示。

渐进模糊中 `startFraction` 表示**最强**位置，`endFraction` 表示变为清晰的位置；不是从清晰变模糊。优先方向预设，避免用角度和复杂曲线硬调。均匀和渐进模糊是同一栏位的替代方案，不能为了“更高级”叠两层。

高光的 `width` / `innerBlurRadius` 使用 Dp，与 `blurRadius: Float` 不同。优先默认光源，不移植稿的“中心原点 -1～1”坐标。陀螺仪高光不属于基准体验：0.9.4 的 Android 有传感器实现，Skiko 平台为零倾斜回退，不应要求 iOS 做到相同动态视差。

### 7.4 性能与降级

- 普通卡片不模糊；目标顶栏、底部导航和必要菜单使用官方 Glass。Tab 与其顶栏协调采样 / 材质，不人为设置“最多两块”阻止整套组件，但必须测量过度绘制和额外 blur 成本。
- 渐进 blur 比均匀 blur 多出全分辨率覆盖成本，限制在小面积边缘；禁止把每张列表卡或整个地图铺成动态 blur。
- backdrop、颜色列表和配置应稳定复用；不要在倒计时或滚动每帧重新创建整套材质配置。
- 静止页面不应因装饰产生持续动画或传感器更新。若平台可获得减少动态效果偏好，应减少非必要运动和高光变化。
- 在真实 Android / iOS 设备验证滚动、转场、键盘和明暗切换；性能不达标时依次去掉动态高光、渐进模糊、均匀模糊，保留语义配色和所有功能。
- 普通卡片无需用软件内阴影模拟“厚度”，也不要以额外阴影掩盖背景 / 卡片色相同的问题。

## 8. 动效与反馈

**库拥有的动效不重做；业务拥有的状态变化要稳定、克制。**

- 页面 push / pop 使用现有 `NavTransitions.MiuixDefault`，保留预测返回与已有的手势禁用策略。
- 卡片用库 Sink；开关、勾选、导航选中、输入框焦点和列表 overscroll 使用库行为，不重复包一层动画。
- 自定义局部反馈建议 120–200ms；内容显隐 / 大小变化建议 180–280ms，保持同类一致。这是非库动效的范围，不覆盖库内弹簧常量。
- 只动画发生变化的局部区域。错误文案出现可柔和扩展；不要给整个页面 `animateContentSize` 导致滚动、焦点与键盘位置抖动。
- 处理按钮不反复闪烁，指标不滚轮跳数，课程卡不逐张飞入。加载指示器停止后应对应明确状态。
- 动画不能延迟提交、返回和可达性；减少动态效果模式优先瞬时切换或淡化。
- 触觉反馈使用库 / 平台已有能力，不叠加第二次震动；高频倒计时、滚动和普通刷新不震动。

## 9. 页面家族与完整状态

### 9.1 页面统一方式

| 家族 | 视觉骨架 | 必须保留的业务边界 |
| --- | --- | --- |
| 登录 / 账号绑定 / 密码恢复 | 共享品牌标识、24dp 表单、MIUIX 输入、一个主操作、安静的协议入口 | 账号来源和错误讲清楚；键盘下可操作；校园账号与教务账号不混淆 |
| 课程表 | 原生标题与周次入口、连续圆角课表面、稳定课程配色 | 窄屏 / 大字体自动日列表；同格多课可达；无法定位的课程不能被藏掉 |
| 校园跑 / 成绩 / 轨迹 | 同一指标条、语义状态、分组记录、地图与操作区分离 | 真实记录、暂停、待上传、失败不能被统一成“成功”；手势保护不变 |
| 校园网 | 一个主状态区 + 账号 / 诊断分组 + 明确操作 | 未登录、离线、连接中、在线、失败、不在校园网必须分别展示 |
| 热水器等 Pro 功能 | 同一 MIUIX 曲面、操作和确认，必要重点卡 | 连接 / 控制 / 结果等待为真实状态；Standard 不凭视觉稿引入 Pro 入口 |
| 我的 / 设置 / 关于 / 更新 / 文档 | 分组 Preference、主题说明文字、明确版本和状态 | 版本、功能可用性和更新进度真实；长文档正常滚动；开发者内容不侵入普通页面 |

课表彩色块是**领域数据编码**，不是第二套全应用主题。颜色按课程稳定 ID 分配，同课程跨周 / 刷新不变；不能按列表位置换色。浅 / 深模式可保留识别色，但文字必须分别校验。当前板块使用同一组 pastel，后续若调整，应在领域 palette 一次完成，不逐页补丁。

“进行中”除颜色外要有可读状态；若启用轮廓，明确作为目标实现。当前虽然有 `RunningOutline` token，周课表绘制并未使用，不能写成已完成效果。详情 / 未排课列表应复用相同课程身份与色彩映射。

### 9.2 状态矩阵

| 状态 | 视觉与文案 | 禁止 |
| --- | --- | --- |
| 首次加载、无缓存 | 原位置出现库进度与简短说明，关键布局尽量稳定 | 用空白页或“暂无数据”掩盖加载 |
| 刷新、有缓存 | 保留内容，局部刷新状态与必要时间标记 | 每次刷新清空整页、导航或表单 |
| 真实空数据 | 简短空态 + 明确下一步，优先 `EmptyCard` | 用巨大插画填充，或把失败当空态 |
| 失败 | 可读原因、重试入口；可用缓存继续显示 | 只弹瞬时提示，或为美观隐藏错误 |
| 未登录 / 未绑定 | 解释账号需求 + 一个有效入口 | 伪装加载或伪造在线状态 |
| 权限 / 蓝牙 / 定位未满足 | 解释用途、当前限制、可行设置入口 | 用灰色不可点按钮却不解释原因 |
| 提交处理中 | 就地进行时，阻止重复操作，保留上下文 | 动画结束即宣称服务端成功 |
| 成功 | 真实回执驱动状态与简短反馈 | 把“请求发出 / 刷新完成”写成操作成功 |
| 未接入 / 版本不支持 | 明确标注，不展示假可用入口 | 自动打开未说明的外链或暴露编译外功能 |

错误、缓存过期、离线、不完整数据可以与内容同时存在，不必都替换为整屏警告。后台小任务不阻塞整个页面。重试不丢失当前周次、列表位置或已填表单。

### 9.3 文案与辅助功能

- 使用简短、准确的中文；操作写动词和对象，诊断信息分层，不把异常堆栈当普通正文。
- 沿用集中管理的 [AppText](<shared/src/commonMain/kotlin/io/github/betterzslf/core/text/AppText.kt>)；截图中的人名、日期、地点、分数不是可复制的运行时内容。
- 账号、密码、token 等不进入 Toast、诊断截图或日志；账号按现有隐私策略脱敏。
- 状态变化按重要程度播报，避免多个子节点重复；沿用 `InfoBanner` 的警告合并语义，不给每秒更新的文本加 live region。
- 选择项提供选中语义；错误提供可理解的关联；焦点顺序跟随阅读顺序。颜色、位置或动画不是唯一信息渠道。

## 10. 落地顺序、已知差异与验收

### 10.1 后续统一工作的顺序

1. **固定最新依赖与共享接入**：按 [D00–D01](<DESIGN-TODO.md>) 对齐已发布开发版、backdrop、能力策略与可读主题配对。
2. **首批可见采用**：GlassNavigationBar、GlassTopAppBar 和密码 trailingIcon 同批落地到课程表 / 设置代表界面；不是把材质无限推后。
3. **成套组件迁移**：Glass Tab / Dropdown / Dialog / Sheet / Popup / Icons，配合 Slider、Radio、Search、Tooltip、Badge 和 Snackbar。
4. **自适应与领域交互**：宽屏 Rail、动态色、时间选择等按真实场景采用；保留业务状态机、四栈和保护策略。
5. **回归与交付证据**：对比新旧 / Glass 与 Opaque，完成平台性能、无障碍和布局验收后更新“当前实现”。

每批先改共享入口，再选代表页面验证，最后推广。不能一边宣称“统一”，一边为每页增加参数和特殊色。

### 10.2 本次源码核对发现的差异（待整改，不是已完成项）

| 差异 | 后续处理 |
| --- | --- |
| 当前主按钮 16sp 蓝底白字和部分次级文字对比度不足 | 按第 3.3 节集中整改；不靠增粗勉强过关 |
| [RunWidgets.kt](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/run/RunWidgets.kt#L26-L52>) 的动作字为 15sp、状态色直接取浅色常量 | 对齐共享动作样式与深浅色语义解析，保留跑步业务行为 |
| [RunPages.kt](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/run/RunPages.kt#L75-L85>) 的 gap 为 14dp、底部采用相加；部分跑步详情 / 导入列表使用外层 padding | 对齐 `PageList` / `pageContentPadding` 的 12dp 与 max 占位策略；保留 Lazy 状态、刷新与分页 |
| 登录 / 校园网页仍有 28 / 24dp 等局部页面边距 | 新普通页用 20dp，认证表单用 24dp；现有页面成组迁移，不在无关改动里顺手改宽 |
| [App.kt](<shared/src/commonMain/kotlin/io/github/betterzslf/App.kt#L213-L224>) 启动异常主题未应用自定义文字样式 | 同步共享 `ardotTextStyles`，使异常页也保持同一视觉语言 |
| [SchedulePage.kt](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/schedule/SchedulePage.kt#L69-L80>) 的第三 tab / 34sp 注释已过时，部分卡片色注释也是旧值 | 以真实主题 / 导航策略为准，修改时一并清理旧注释 |
| [ScheduleWeekBoard.kt](<shared/src/commonMain/kotlin/io/github/betterzslf/ui/schedule/ScheduleWeekBoard.kt#L171-L205>) 格内存在 9 / 10sp 文字，未应用 `RunningOutline` | 密集课表是领域例外，不可扩散到普通页面；验证格内可读性、详情可达和日列表降级，轮廓按需求实现 |
| 部分自绘曲面外包标准圆角，可能与库连续曲率不一致 | 按用途改为 squircle API 或保留合理标准 Shape，不全局搜索替换 |

### 10.3 新页面 / 改版 PR 必过清单

- [ ] 使用 MIUIX 与现有共享入口；没有混入另一套主题或控件库。
- [ ] 没有散落的新 hex、任意字号和相近但不同的圆角 / 间距。
- [ ] 默认深浅色、跟随系统、Monet 开关均正确；容器与内容色配对。
- [ ] 普通文字 / 大字 / 必要控件的最终合成对比度合格，错误和状态不只靠颜色。
- [ ] 普通触控区域至少 48dp；控件语义、选择状态、焦点和屏幕阅读器无重复播报。
- [ ] 页面无双顶栏、假状态栏、双重 Insets，末项和主操作不会被导航 / 键盘遮住。
- [ ] 首次加载、缓存刷新、空态、失败、权限、未绑定、处理中和真实成功都有正确表现。
- [ ] 改宽度不丢四栈历史；tab 重选不清状态；受保护流程的返回 / 手势行为不变。
- [ ] 普通卡片不堆影；blur 有真实采样、清晰前景和完整不透明回退。
- [ ] Standard / Pro 的视觉语言一致，但入口遵守编译期功能边界。

**最小视觉矩阵**：320dp 窄屏、390dp 标准屏、600dp 临界宽屏、840dp 宽屏；默认字号及 130% / 200% 字号；浅 / 深 / 动态色；短横屏、键盘打开、手势 / 三键导航与挖孔安全区。至少覆盖课表、设置、一个长表单、一个长列表、一个地图 / 原生内容页及一个弹层。不能只交一张 390dp 亮色静态截图。

**验证方法**：先检查布局与状态，再检查对比度和无障碍，最后滚动 / 转场 / 键盘实机验收。新 token 或布局策略需补相应测试；截图不能替代逻辑测试，逻辑测试也不能证明材质与平台性能合格。

现有回归入口：[DesignSystemTest.kt](<shared/src/commonTest/kotlin/io/github/betterzslf/ui/DesignSystemTest.kt>)、[NavigationLayoutPolicyTest.kt](<shared/src/commonTest/kotlin/io/github/betterzslf/ui/NavigationLayoutPolicyTest.kt>)、[ScheduleUiModelTest.kt](<shared/src/commonTest/kotlin/io/github/betterzslf/ui/schedule/ScheduleUiModelTest.kt>)、[ScheduleBoardTest.kt](<shared/src/commonTest/kotlin/io/github/betterzslf/ui/schedule/ScheduleBoardTest.kt>)。变更共享主题时同步更新合理断言，不为了让测试通过削弱可读性要求。运行方式与平台环境见 [BUILDING.md](<BUILDING.md>)。

本文仅文档变更：已做源码 / 设计稿 / 上游版本 API 核对及静态色值对比计算，**未运行 Gradle 测试，未完成 Android / iOS 实机视觉或性能验收**。

## 11. 版本依据与维护约定

本文“当前 / 稳定版”事实依据 **v0.9.4**，新视觉目标依据已发布的 **0.9.4-45645854-SNAPSHOT** / 固定 SHA，不跟随浮动 main。最新组件、发布证据和实际接入任务集中在 [DESIGN TODO](<DESIGN-TODO.md>)；下列 v0.9.4 链接仅用于基础 API 对照，不是停止更新的版本上限。升级时核对源码签名、依赖解析、共享封装、测试和本文，不只改版本号。

- [MIUIX v0.9.4 发布说明](https://github.com/compose-miuix-ui/miuix/releases/tag/v0.9.4)：渐进模糊、导航与弹层等版本能力。
- [Button 源码](https://github.com/compose-miuix-ui/miuix/blob/v0.9.4/miuix-ui/src/commonMain/kotlin/top/yukonga/miuix/kmp/basic/Button.kt)：真实参数、内容插槽与颜色字段。
- [TextField 源码](https://github.com/compose-miuix-ui/miuix/blob/v0.9.4/miuix-ui/src/commonMain/kotlin/top/yukonga/miuix/kmp/basic/TextField.kt)：原生焦点边框、标签与颜色角色。
- [Squircle 源码](https://github.com/compose-miuix-ui/miuix/blob/v0.9.4/miuix-squircle/src/commonMain/kotlin/top/yukonga/miuix/kmp/squircle/SquircleBackground.kt) / [默认曲率](https://github.com/compose-miuix-ui/miuix/blob/v0.9.4/miuix-squircle/src/commonMain/kotlin/top/yukonga/miuix/kmp/squircle/SquirclePath.kt)：曲面 API 和默认 extension。
- [TextureEffect 源码](https://github.com/compose-miuix-ui/miuix/blob/v0.9.4/miuix-blur/src/commonMain/kotlin/top/yukonga/miuix/kmp/blur/TextureEffect.kt) / [ProgressiveBlur 方向](https://github.com/compose-miuix-ui/miuix/blob/v0.9.4/miuix-blur/src/commonMain/kotlin/top/yukonga/miuix/kmp/blur/ProgressiveBlur.kt)：Float dp 半径、方向语义与成本。
- [Highlight 预设](https://github.com/compose-miuix-ui/miuix/blob/v0.9.4/miuix-blur/src/commonMain/kotlin/top/yukonga/miuix/kmp/blur/highlight/Highlight.kt)：优先使用库提供的玻璃边缘配方。

维护原则：**先更改共享规则，再更改页面；先保证可用，再增强材质；没有平台验证的效果不写成已实现。**
