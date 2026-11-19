# V2 重构暂停交接 — 2026-11-18

> 用户指令：**“先落盘，不做了”**。已暂停自动目标；未经用户明确恢复，不领取任务、不继续改源码、不构建。当前代码与本地证据保留，不回滚，不执行 Git 提交或推送。

## 1. 当前落盘状态

- 工作区 `E:/better-zslf`；私有独立根 `work/pro-ci`。
- 公共 HEAD：`1674115e7ccfc574313647d1e4c646903601c4c5`。
- 私有 HEAD：`80481a947c4a03b8671cc709a8b741e4652b8ba3`。
- 18:05（Asia/Shanghai）只读 Git 状态检查：公共 597、私有 131 条 changed/untracked entries。它们是当时状态条目数，不是提交数或全部代码文件数。
- 修改与新增源码已写入文件系统；大量物理迁移文件仍未跟踪，**仅保存 tracked diff 不足以备份全部实现**。
- [私有公共基线锁](<../work/pro-ci/public-base.properties>) 未改 pin，`releaseReady=false`；本地验证使用显式 development 配对，不作为可发布固定 S/P 配对。
- 全部队友停止执行；本批相关后台任务均已收取，未保留终端；不再启动新任务。
- 自动目标 `goal-3b8766f1-57a1-4d28-be2d-8b14d6343ec5` 已置 paused，revision 5。

## 2. 最后完成的真实闭环

完整历史见 [实施与验证记录](<architecture-v2-progress-2026-11-18.md>)，不是全部 V2 已完成。

### Run 全只读四层

- domain/data/presentation/UI 已物理分层，JSON/wire 留受控实现；五项写 Repository 与定位上传内核未在此批迁走。
- data JVM/Host **68/68**，presentation **60/60**，UI **6/6**，当批 shared **662/659**，均零 failure/error/skip。
- 原 756/753 项方法逐一映射；三个 mixed 方法明确拆分，37 项新增另账。见 [Run 逐方法证据](<../.local/v2-validation/run-read-regression-junit.json>)。

### 课表 Binding 最小批（task-66～69）

- 完整 prompt/form Content 迁出，凭据仍在 root 两个 slot；opaque lease、owned verification attempt、固定 commit receipt 防旧页操作与 ABA。
- **公共最终构建 PASS：1m58s，674 tasks（73 executed／1 cache／600 UP-TO-DATE）**。
- 课表 UI JVM/Host **6/6**，runtime **40/40**，shared **672/669**，全部零失败/错误/跳过。UI/runtime 首轮已实际执行，最终重试 UP-TO-DATE；shared 最终两套实际执行。
- 原 runtime＋shared **696/693** 方法保原名、原 owner 且恰一次；22 新定义另列。见 [课表绑定逐方法证据](<../.local/v2-validation/schedule-binding-regression-junit.json>)。
- **Pro 构建 PASS：2m03s，778 tasks（79 executed／699 UP-TO-DATE）**。Water92／route188／map37 本次 UP-TO-DATE，composition15 本次实际执行；332 项 XML 零失败/错误/跳过。
- Lead 独立守卫：公共 Python **360/360**、私有 **84/84**，architecture287 文件、display240 文件／2791 tokens／60 Han／60 精确例外、facade291 生产文件，全部 PASS；iOS 原25项 source/plist 检查 PASS。
- [源不变量逆审](<../.local/v2-validation/schedule-binding-invariants.json>)：14 个保护文件整 SHA 不变；Panel/Progress 原体仅 visibility 变化；认证和保存函数逆授权 gates/phase/receipt 后恢复原文。60 项文字例外本批零改。
- 36 个可复用库＋shared＋host＝**38 唯一组件**。Native 编译、UI 像素/点击、Keystore 与设备行为没有本机实测证据。

### 最新制品隔离审查

[双产品 APK 审计](<../.local/v2-validation/apk-capability-audit.json>) 全部 PASS：

| 产品 | DEX／defined classes／private classes | SHA-256 |
|---|---|---|
| Standard | 19／22426／0 | `1a9e88896f6dde8a59737a4ff86d732277a745b1c3e365f7162dd102d8043536` |
| Pro | 20／23285／552 | `74d38b45b8b3f9e40fab597049a881a3a7a3ff2709ffc1e5b8c07a86781b995a` |

GPS、原七课表组件／三权限／十四打包资源均保原基线；updater 唯一 private provider，BLE 只在 Pro。没有安装、真实学校请求、GPS/BLE 调用、真实上传或发布。

## 3. 未实施的下一批：校园登录

**仅完成只读方案和基线，尚未开始源码实现。** 暂停时 `feature/auth` 目录不存在。task-70／71／72 均 pending、未领取、无人执行，标题已标“暂停待恢复”；创建任务不等于已给队友 Source 授权。auth 守卫与 Lead 接线任务尚未建立。

最终选定方案（前面的其他候选均作废）：

1. 受控学校认证归 `core/network-session`：六个 typed LOGIN 操作 `captureLoginAttempt`／`claimLoginAttempt`／`isLoginAttemptCurrent`／`executeLogin`／`cancelLoginAttempt`／`consumeLoginContinuation`。
2. opaque attempt＋store-wide claim nonce＋内部 snapshot CAS。登录解码与 Session 条件保存单 owner 在 core；**不新增 public Session mutation 或 raw JSON 出口**，不泛化任意 endpoint。
3. feature/auth/data 明确是 finite outcome／uncertain 状态／一次 source 映射 adapter，不假称 decoder 已迁 feature data。
4. presentation 不含凭据或 Session；root 管 page/edit/render/job 与一次续行。完整 chooser/campus form 迁 Content，凭据两 slot 留 root。
5. 保原 stage `rememberSaveable` 和学籍/Dorm 独立生命周期，不按 campus epoch 重建全子树。额外入口的 NavKey 留 root，UI 只发本地 opaque key。
6. 已知旧登录无条件 save 可覆盖途中建立的新会话。修复必须在 core authority/CAS，不靠 UI 结果门；未知写结果不自动重试。
7. 原两项纯 login 测试拟迁 core，其他 AuthRepository 三项留 root；mixed case 和 wire contract 保真实 typed 调用及原断言，不留生产 raw 兼容壳。

已保存 [auth 修改前基线](<../.local/v2-validation/auth-before.json>)：38 项文件、6 份源码快照，真实 shared **672/669**、core **50/50** 方法身份。基线是恢复证据，不是 auth 新实现通过证据。

## 4. 恢复时的起点

只有用户明确要求继续后才恢复自动目标。先读本交接和 [进度记录](<architecture-v2-progress-2026-11-18.md>)，检查当前 Git 与基线是否被外部改变，再按 task-70～72 的互斥文件范围授权领取；补建 auth 守卫与 Lead 接线/回归任务。

Lead 独占 Gradle/settings/私有 aliases 和串行构建；所有 owner Source/TestReady 后冻结，再公共完整回归→Pro consumer→逐方法账／源码逆审／APK 审计。不要用源测试定义数、静态守卫或旧 XML 冒充本轮执行。

其余课表 UI、Run 写内核迁出、profile、documents、完整 Native/设备验收仍未完成。本次暂停不标记整体目标完成。
