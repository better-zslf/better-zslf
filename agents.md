# Agent 模型规则

- 创建 Team 成员时，使用并跟随 Lead 当前模型，不单独覆盖模型。
- 创建 subagent 时，使用和 Lead 相同 Provider 提供的 `deepseek-v4.1-flash`，推理档位设为 `max`。

# 项目架构

双仓结构，Android 双产品（免费 Standard ＋付费 Pro），Kotlin Multiplatform（commonMain 为主，Android/iOS/JVM 目标）。

## 仓库

- `E:/better-zslf`（公共仓，github.com/better-zslf/better-zslf）：全部共享代码＋免费版产品根。
- `work/pro-ci`（私有仓，github.com/better-zslf/pro-ci，独立 Git 根）：仅 Pro 专属模块＋Pro 产品根。通过 settings.gradle.kts 里的显式坐标替换引用公共仓模块，零复制；public-base.properties 钉死配套的公共提交。

## 公共仓模块图（48 可复用库＋shared＋host＝50 组件）

- `core/`：地基——text（AppText 文案唯一来源）、logging、storage、run-model（跑步数据模型）、network-session（网络请求＋登录会话安全）。
- `design-system/`：通用界面零件（按钮、图标、品牌标、对话框）。
- `feature/<功能>/`：九个功能各自分模块，按层拆分：
  - `domain`＝纯规则计算（不碰网络不碰界面）
  - `data`＝和服务器打交道（请求与解码归属层）
  - `presentation`＝状态机（无凭据、不画界面）
  - `ui`＝Compose 界面（无 Graph/Route/凭据）
  - `runtime`＝该功能自己的运行机器（如 run/runtime、schedule/runtime）
  - 功能清单：run（跑步）、schedule（课表）、home（首页）、update（更新）、accountrecovery（找回密码）、campusnet（校园网）、auth（校园登录）、profile（个人资料）、content（协议文档）。
- `platform/`：设备能力实现——location（GPS 定位＋前台服务）、schedule-native（课表通知/小组件）、updates（安装器）、storage（加密存储）。
- `shared/`：总装壳——页面路由（Route/AppGraph binder）、产品组装、跨功能集成测试；业务逻辑不在此层。
- `androidApp/`：免费版 Android 产品根；`platform/android-host`：宿主库。
- `desktopApp/`：桌面 UI 预览入口（`gradlew :desktopApp:run`，JVM 内存桩、免装机改 UI 即看；不是产品面，不发布）。

## 关键约定

- 包名跨模块保持：文件物理迁移不改包名，同包跨模块经依赖解析；`internal` 是模块级可见，跨模块引用需逐条 public 化并披露。
- 模块坐标：build-logic 的 kmp-library convention 按 parent-qualified group 产生唯一身份；私有仓用显式坐标别名消费。
- 新模块 Gradle 模板参照既有 feature 模块：纯逻辑库用 `betterzslf.kmp-library`，含 Compose 的库用 `betterzslf.compose-library`。
- 版本真源：根目录 `version.properties`（VERSION_NAME/VERSION_CODE/VERSION_PRERELEASE），Standard/Pro 同步共用，Pro 版本名加 `-pro` 后缀。
- 守卫：`scripts/` 下 Python 守卫（architecture/display 等）与 `work/pro-ci/scripts/kotlin_facade_guard.py` 把守模块边界；`scripts/display-text-exceptions.json` 文案例外表保持零改动。
- Pro 专属能力：pro/water（水控）、route-tools、map-editor、Pro 导航、BLE——免费版 APK 零 Pro 类。
