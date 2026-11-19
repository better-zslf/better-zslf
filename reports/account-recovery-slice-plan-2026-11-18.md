# 密码恢复纵切：typed contract、安全回执与测试迁移规划

> task-39；2026-11-18；仅规划，尚未实施。当前回归由 Lead 串行执行；本任务不改源码、测试、Gradle、CI，不发业务请求。必须等当前回归结束，并获得 Lead 对基础闭包及该纵切的分别授权后才实施。更新功能 Phase 2 不在本计划范围。

## 1. 结论与实施顺序

最终目标是**真正的 domain / data / presentation / ui 编译模块**，不是仅改目录，也不以空 data 或依赖 shared 的转发门面声称完成 R5。

1. **前置阶段：抽出 core network/session 安全闭包。** 模块名及日志接缝由 Lead 与 core 规划负责人决定；本文暂记 `:core:network-session`。保留绑定 authority、单权威 session snapshot、mutation epoch、非悬挂 compare-and-clear、迟到 401 的完整语义。
2. **密码恢复纵切：四个真实模块。** data 直接消费该 core 的有限 API，持有已捕获会话绑定、三阶段协议和精确响应解析；不得依赖 shared 内的 AuthRepository。
3. **shared 只做产品 binder / legacy 兼容调用方。** Graph、会话流、route lifetime、未持久化输入及自己的成功回执仍由 shared 持有。登录和修改密码不借本次纵切顺带大改。
4. 若前置闭包尚未完成，现有 shared adapter 可作为诚实记录的过渡实现；**不创建空 data，不将此状态计作完整功能独立**。本轮不实施该过渡方案。

目标 DAG（箭头表示依赖）：

```text
core:network-session -> core:text + Ktor + serialization + coroutines
                       + finite diagnostics seam（若日志实现继续留 shared）

feature:accountrecovery:domain -> Kotlin only
feature:accountrecovery:data -> domain + core:network-session
                               + serialization/Ktor（按真实使用显式声明）
feature:accountrecovery:presentation -> domain + core:text + coroutines
feature:accountrecovery:ui -> presentation 的 UiState / UiEvent 合同
                             + design-system + core:text + Compose / MIUIX
shared -> core:network-session + domain + data + presentation + ui
```

`ui` 源码仅允许展示合同，不因 Gradle 可见 presentation 就向 Content 传 Controller。禁止 data → shared、presentation → data、domain → core 网络/会话、core → feature/shared。

## 2. 已核实的现实边界与阻塞

| 现实文件 | 当前职责 / 拆分要求 |
|---|---|
| [AccountRecoveryModel.kt:3–29](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/feature/accountrecovery/AccountRecoveryModel.kt#L3-L29>) | 同时定义 port、AuthRepository adapter、ApiResult / JSON；必须按职责拆分，不整体搬到 domain。 |
| [AccountRecoveryModel.kt:49–75](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/feature/accountrecovery/AccountRecoveryModel.kt#L49-L75>) | 精确 JSON target decoder 属 data；纯验证谓词属 domain；AppText 错误映射属 presentation。 |
| [AccountRecoveryController.kt:17–40](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/feature/accountrecovery/AccountRecoveryController.kt#L17-L40>) | 有纯状态机接缝，但仍 import ApiResult、JSON、data receipt；改为 domain typed outcomes 后再迁 presentation。 |
| [AuthRepository.kt:73–129](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/data/AuthRepository.kt#L73-L129>) | 三阶段请求及 receipt 清理调用 core internal API；现有 login / modify / legacy reset contracts 继续保留。 |
| [ApiClient.kt:49–63](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/core/net/ApiClient.kt#L49-L63>) | capture、bound owner、current、compare-and-clear 同属一个 authority；跨模块应仅开放有限操作，不能导出 token/snapshot。 |
| [ApiSessionBinding.kt:10–20](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/core/net/ApiSessionBinding.kt#L10-L20>) | 构造、authority、snapshot 及 matches/token/owner internals 应随 core 留在同一安全闭包；无 copy/component/serializer。 |
| [SessionStore.kt:11–24](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/core/storage/SessionStore.kt#L11-L24>)、[SessionStore.kt:42–74](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/core/storage/SessionStore.kt#L42-L74>) | snapshot epoch + owner + token 单记录；成功 save 推进 epoch，clear 即使持久化删除失败也在 finally 使内存失效；比较及清除不可分成两个可悬挂调用。 |
| [Session.kt](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/core/model/Session.kt>)、[ApiResult.kt](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/core/result/ApiResult.kt>) | token / JsonObject 和通用消息属于 core/transport，不进入 feature domain、UI 或 typed outcomes。 |
| [AppRepositories.kt:7–15](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/data/AppRepositories.kt#L7-L15>)、[App.kt:78–82](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/App.kt#L78-L82>) | 产品仍拥有唯一 ApiClient / SessionStore。新 data 注入这组 owner，不另外建第二套 session 或自行恢复凭据。 |
| [AccountRecoveryPage.kt:33–60](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/ui/accountrecovery/AccountRecoveryPage.kt#L33-L60>) | 最新任务已改为 `key(graph, epoch)` / `remember(graph, epoch)`；自有 finalReceipt 在该 key 外。必须沿用新版本，不能按旧 `key(session)` 恢复。 |

core 当前 13 个生产文件的实际集合：

- net 6：[ApiClient.kt](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/core/net/ApiClient.kt>)、[ApiConfig.kt](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/core/net/ApiConfig.kt>)、[ApiEndpoint.kt](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/core/net/ApiEndpoint.kt>)、[ApiSessionBinding.kt](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/core/net/ApiSessionBinding.kt>)、[QueryParameters.kt](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/core/net/QueryParameters.kt>)、[UploadFile.kt](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/core/net/UploadFile.kt>)。
- model/result/storage 4：[Session.kt](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/core/model/Session.kt>)、[ApiResult.kt](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/core/result/ApiResult.kt>)、[KeyValueStore.kt](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/core/storage/KeyValueStore.kt>)、[SessionStore.kt](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/core/storage/SessionStore.kt>)。
- log 3：[AppLog.kt](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/core/log/AppLog.kt>)、[LogEntry.kt](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/core/log/LogEntry.kt>)、[LogRedaction.kt](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/core/log/LogRedaction.kt>)。

只原封移动前 10 个并非完整闭包：[ApiClient.kt:189–215](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/core/net/ApiClient.kt#L189-L215>)仍直接调用 shared AppLog。core 规划负责人已建议：**10 个基础文件迁入 `:core:network-session`，log 3 留 shared**；在 ApiClient 内提供有限、结构化、不含 query/body/token 的 diagnostics event seam，默认 no-op，由 AppGraph 适配 AppLog。禁止让 core 反向依赖 shared，也不放入通用任意字符串/回调执行器掩盖日志边界。该方案仍是只读建议，本文不替其授权实施。

基础迁移还要覆盖 Auth、CampusNetwork、CampusPortal、Profile、Run 五个 repository 及 PublicRunPreviewSource 共六个文件、14 处 binding owner/current/clear 引用。仅将 ApiClient 上这三个有限操作设为跨模块可用：captured owner 的稳定读取、same-client current bool、same-client + epoch compare-and-clear bool；snapshot、binding 构造和 token/epoch 原始数据继续 internal。此处不是把所有 internals 改 public 的理由。

## 3. 建议的最小 domain 合同

以下是设计草案，不是已落盘实现：

```kotlin
interface AccountRecoverySource {
    fun isCurrentSession(): Boolean
    fun isLoggedOut(): Boolean
    suspend fun sendCode(phone: String): RecoverySmsOutcome
    suspend fun verify(phone: String, code: String): RecoveryVerifyOutcome
    suspend fun reset(target: RecoveryTargetId, password: String): RecoveryResetOutcome
}

// 仅固定分类；不携带 ApiError、原始消息、HTTP body、URL 或凭据。
enum class RecoveryIssue {
    Authentication, Business, Validation, Timeout, Network, Http, Decode, Storage,
}
enum class RecoveryLogout { Cleared, ClearFailed, NewSessionPreserved }

sealed interface RecoverySmsOutcome {
    data object Accepted : RecoverySmsOutcome
    data class Rejected(val issue: RecoveryIssue) : RecoverySmsOutcome
    data class Unknown(val issue: RecoveryIssue) : RecoverySmsOutcome
}
sealed interface RecoveryVerifyOutcome {
    data class Verified(val target: RecoveryTargetId) : RecoveryVerifyOutcome
    data class Failed(val issue: RecoveryIssue) : RecoveryVerifyOutcome
    data object MissingTarget : RecoveryVerifyOutcome
}
sealed interface RecoveryResetOutcome {
    data class Accepted(val logout: RecoveryLogout) : RecoveryResetOutcome
    data class Rejected(val issue: RecoveryIssue) : RecoveryResetOutcome
    data class Unknown(val issue: RecoveryIssue) : RecoveryResetOutcome
}
```

- source 是**捕获当次会话 epoch 的对象**；`isCurrentSession()` 只读判定，不得自动 rebinding。guest 不生成 source；所有三阶段仍要求当前认证。
- `RecoveryTargetId` 是经过验证的名义 ID 类型，非 data class，诊断 redacted，无 serializer/copy/component。可用受校验工厂和显式 `requestValue` 访问供 data 发协议；公开工厂不代表授权能力。真正授权仍是 captured binding + controller 保存的 verify 结果 + final nonce/ack，不把该 ID 冒称不可伪造 capability。
- target 原值只存在 data 与 controller 私有 verified/pending；不进入 UiState、事件、preview、saved state、日志或 receipt。`reset(target, password)` 沿用 userId 后 password 的意义，不能交换到上游 `newPassWord` 参数。
- domain 验证返回固定 ValidationIssue：11 位 ASCII 数字手机号；验证码非空且无空白/控制字符；现有新密码谓词及两次一致。密码现规则原文为长度至少 8、含 ASCII 字母、数字、非字母数字且非空白的字符、整体无空白。
- presentation 把固定 issue 映射到既有 AppText 字面；不直接展示 transport error.message 或 server success.message。
- legacy AuthRepository 对外 `login`、`resetPassword(userId, newPassword)`、`resetPasswordWithReceipt`、`modifyPassword` 及 ApiResult 契约继续保留。密码谓词若集中到 domain，旧静态方法仅委托同一实现，不复制两份规则。

### data 的真实职责

新 data 实现直接依 core ApiClient / SessionStore（或 core 提供的窄 owner 工厂），不通过 shared AuthRepository 转发。先原样保留三条 endpoint、REQUIRED auth、POST+query、参数名及受控日志：

| 阶段 | path | query |
|---|---|---|
| 发送短信 | `/app/campusNetwork/checkPhone` | `phone` |
| 验证 | `/app/appUser/checkPhone` | `phone`, `smsCode` |
| 重置 | `/app/appUser/resetNewPassWord` | `userId`, `newPassWord` |

严格 target decoder 从 transport 移入 data：**仅响应 data.user.id**；接受原有非空、trim 不变、无控制字符的字符串，或现实现允许的有限数字原表示；拒绝 null、bool、object、array、非有限数字及缺少正确容器。不得退回当前 owner、手机号、data.id、data.userId 或别名。

reset 收到已识别 Success 后，按捕获 binding 在 core 内 compare-and-clear：

- 清除成功 → `Accepted(Cleared)`；
- 持久化删除/本地清理异常但已明确成功 → `Accepted(ClearFailed)`；内存仍失效，不能降级成失败诱发重置重试；
- 新 epoch/owner/token 已替换，compare-and-clear 不匹配 → `Accepted(NewSessionPreserved)`；不清新登录。

该逻辑可以在新 data 中真实实现，但必须只调 core 的有限原子操作；旧 AuthRepository 同一路径继续保留兼容测试。若要在第二阶段消除这段兼容逻辑重复，另行确定低层服务归属，不能为此反转 data/shared 依赖。

## 4. Accepted / Rejected / Unknown 的精确保持

分类首先经过 live session / controller alive 门禁；例如真实 401 已改变会话时，当前 UI 结算为 SESSION_CHANGED，而不是为了表格硬写 UNCERTAIN。

| 上游 / 本地情况 | SMS typed outcome | reset typed outcome | 现有行为保持 |
|---|---|---|---|
| 已识别成功 envelope | Accepted | Accepted(logout) | 仅“已受理”；SMS 不保证送达/有效期。reset 已确认不再请求。 |
| 明确 Business / 本地 Validation | Rejected(issue) | Rejected(issue) | SMS 回 EDITING，不启动成功冷却；reset FAILED 且本 attempt 结束。均无自动重试。 |
| Timeout / Network / Http / Decode / Storage，或无法确认已受理的异常 | Unknown(issue) | Unknown(issue) | 同会话且仍 alive 时进入 UNCERTAIN，停止自动/同 attempt 重试，不显示成功。 |
| Authentication | Unknown(Authentication) | Unknown(Authentication) | 不擅称未发出；先尊重 source current / 已失效会话门禁，禁止重绑后继续旧流程。 |
| CancellationException | 继续抛出 | 继续抛出 | controller 在已 dispatch 后将仍有效页面标 UNCERTAIN；不把取消等同撤销，不能吞取消再继续请求。 |
| 明确成功后的 cleanup 失败/取消 | 不适用 | Accepted(ClearFailed) | 保留服务受理事实；不能因为本地清理出错重新改为 Unknown/Rejected。 |
| 新会话出现后迟到成功 | 不给新输入/codeRequested 授权 | Accepted(NewSessionPreserved) | 不清新会话，不显示旧账号成功 receipt，不重试。 |

### SMS 冷却与 late acknowledgement

依据现有 [sendCode:79–119](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/feature/accountrecovery/AccountRecoveryController.kt#L79-L119>)：

- 构造、重组、回前台、tick 只读/本地，不发短信。每次 dispatch 先有明确短信确认和合法手机号；`Mutex.tryLock` 阻止并发。
- Accepted 后从 receipt 返回时计算 `nowMillis() + 60_000`；这是本地冷却，不是服务送达或验证码有效期。tick 为 ceiling 秒数并限制 0–60，只更新数字。
- dispatch 期间手机号或验证码变化使 generation 不同：迟 Accepted **仍保留 60 秒冷却**，但 `codeRequested=false`、`sentPhone=null`，不允许把旧短信受理冒用到新输入。
- Rejected / Unknown / cancellation 不启动“成功冷却”。Rejected 可在 EDITING 再经一次显式用户确认；Unknown 终止当前 route attempt，不提供自动 resend。
- 冷却归零不触发网络。close / 变更会话清所有输入身份和本地冷却，不将其保存成跨会话令牌。

### reset 单次确认与 late receipt

依据现有 [prepare/confirm:149–209](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/feature/accountrecovery/AccountRecoveryController.kt#L149-L209>)：

1. verify 的 target、phone/code 原值必须匹配当前输入；prepare 不发网络，只生成私有 `Attempt(nonce, target, password)` 和安全 preview。
2. final confirm 要同时满足：当前会话、PREVIEW、pending 存在、nonce 与当次 preview 完全相同、明确 ack、未 attempted、无其他 flight。
3. **派发前**置 attempted，清 verified identity/sentPhone/cooldown，然后再做一次 live session admission；UI 即使双击或持有过期 bool 也不增加 HTTP。
4. 已派发后 Rejected / Unknown / cancellation 都结束该 attempt；不允许再次 prepare/confirm/reset，不把失败当自动恢复入口。
5. 自己成功 reset 会主动使旧 binding stale，因此 Accepted 分支不能先用通用 `current()` 拒绝掉自己的成功。先按 Accepted logout + `source.isLoggedOut()` 判定。
6. 只有非 NewSessionPreserved 且自己的 logout 已确认，才变 COMPLETED / CLEANUP_WARNING，并同步无额外 suspension 调用非敏感 `onAccepted`；新登录出现则 SESSION_CHANGED，不发布旧 receipt。
7. closed owner 丢弃晚结果，不重新打开页面、不重建请求；取消不宣称远端撤销。不得增加“恢复”时重发、全局 NonCancellable 包住请求或 onResume retry。

## 5. presentation、pure UI 与 shared binder

### presentation

移动 controller 的状态机，保留 tryLock、generation、nonce、attempted、alive、private verified/pending 全部门禁。方法可继续使用现有小面：sendCode / verify / prepare / confirm / phoneChanged / codeChanged / editPreview / tickCooldown / close；只替换 port/outcomes，不把发送动作塞进构造或 observer。

`RecoveryUiState` 仅含 phase、sending/verifying、cooldown、codeRequested/verified、maskedPhone、message/inputRevision、可用 action gates，以及安全 dialog 投影。preview 最多是非敏感 nonce/revision + maskedPhone；**不含 target、raw phone/code/password、binding、Session、repo、controller 或请求 Spec**。

busy-back 的纯决策可公开为有限 `RecoveryBackDecision` / `recoveryBackDecision(state)`（或保留有限 helper），shared 回调在 commit 时读取 live state 再调用。不要为了跨模块测试把所有 internal helper 或消息常量一并 public。

### UI non-sensitive props 的可实现方案

仅把原 Body 的四个敏感 String 塞进 ContentState 不满足本次要求。若 UI props 严格 non-sensitive，采用有名受控输入 slots：

```kotlin
@Composable
fun AccountRecoveryContent(
    state: RecoveryUiState,
    onEvent: (RecoveryUiEvent) -> Unit,
    phoneField: @Composable (RecoveryFieldUi) -> Unit,
    codeField: @Composable (RecoveryFieldUi) -> Unit,
    passwordFields: @Composable (RecoveryPasswordUi) -> Unit,
)
```

`RecoveryFieldUi` / `RecoveryPasswordUi` 只有 enabled / reveal 等显示标志。shared binder 在 slots 内放实际受控 TextField，并保有 value/change callbacks；Content 做 Card、Section、Banner、preview、确认弹层等布局。这样不向 UI 传四个 raw credentials，也不传“可执行任意请求”的通用 action block。

UI 事件是有限零载荷意图，如 Back、RequestSms、ConfirmSms、DismissSms、Verify、Prepare、EditPreview、RequestReset、ConfirmReset、DismissReset、AckChanged(Boolean)。不含 target、password、SMS request Spec、repo、controller 或任意 URL。UI 的 enabled 只管展示，binder/controller 对每个事件重新校验实时门禁。

设计系统已有 [PasswordVisibilityIcon](<E:/better-zslf/design-system/src/commonMain/kotlin/io/github/betterzslf/ui/DesignComponents.kt#L339-L350>)、[ConfirmationCheckRow](<E:/better-zslf/design-system/src/commonMain/kotlin/io/github/betterzslf/ui/ConfirmationCheckRow.kt>) 与 [Screen](<E:/better-zslf/design-system/src/commonMain/kotlin/io/github/betterzslf/ui/PageLayout.kt#L47-L77>)，无需反依 shared 的 UI 文件。固定文案继续 core:text，不能用 source placeholders 糊过真实 Content。

### shared binder 的保留职责

- 按 `graph + session revision` 创建 source/controller，epoch 替换即时丢旧表单，不依赖 Session 值相等判断。接入的是同一个 Graph session owner。
- 手机号、验证码、密码、确认密码、reveal 及 dialog 输入只用普通 remember；不 rememberSaveable，不落磁盘/导航参数/日志/全局 store。
- inputRevision 改变、terminal、verified=false、epoch 换或 close 时依现语义清输入及相应弹窗；phone/code change 先同步使验证/preview 失效，再计算按钮和派生字段。
- `pendingSms` 在打开短信确认时捕获手机号及输入 revision；`pendingResetNonce` 在打开 final dialog 时捕获当次 preview。确认不得偷偷读取“当前最新 nonce”替代用户当时看到的旧 preview；checkbox 随 nonce 重置。
- ConfirmSms / ConfirmReset 再读 live state、dialog 状态、phase、current binding、cooldown、revision / nonce / ack；Content 即使伪造可用 bool 也不授权请求。私有 password/target/Attempt 从不交给 Content。
- coroutine scope、collectAsState、DisposableEffect、只更新本地 cooldown 的 timer、PredictiveBackHandler 留 binder；Content 不持有 Flow/Controller，不 LaunchedEffect 网络，不自动 resume 写。
- `finalReceipt: RecoveryLogout?` 仍在 epoch key 外普通 remember(graph)。仅非敏感结果 enum 可跨自己的成功 logout；新登录清 receipt。不要为了不丢 receipt 把输入/身份随它一起提升。

### busy back 的精确语义

原 [AccountRecoveryBackPolicy.kt](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/feature/accountrecovery/AccountRecoveryBackPolicy.kt#L7-L18>)：

- 只有 `sending == true` 或 `phase == RESETTING` 挡 back；verify 虽 busy 但只读，仍允许返回取消。
- 屏幕箭头与系统 predictive gesture commit 走同一个回调；回调读取 `controller.state.value`，不能捕获 gesture start 或上一 Compose 帧的 snapshot。
- gesture 开始时 idle、commit 时 RESETTING：挡并显示固定 notice；开始时 resetting、commit 前已 UNCERTAIN / COMPLETED：允许 back，保留已结算 outcome。
- handler 必须始终注册，不能“进入 busy 才注册”错过已开始的 gesture。
- 挡 back 只显示“请求可能已受理，请等待结果。返回页面不会撤销请求。”；不取消协程、不改 receipt、不重写 phase、不启动重试。
- guest、SESSION_CHANGED、CLOSED、UNCERTAIN 及其余 stopped states 不困住用户。

## 6. 测试迁移：旧集合守恒，不靠扩大可见性

### 当前 recovery 专属基线：43 个 @Test 定义

| 现有测试文件 | 数目 | 目标与要求 |
|---|---:|---|
| [AccountRecoveryControllerTest.kt](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/feature/accountrecovery/AccountRecoveryControllerTest.kt>) | 20 | 下列 JSON 语义 2 条归 data；其余 18 改 typed fake 后归 presentation，不依赖 ApiResult/JSON/实际 AuthRepo。 |
| [AccountRecoveryBackPolicyTest.kt](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/feature/accountrecovery/AccountRecoveryBackPolicyTest.kt>) | 7 | 随纯 back 门禁归 presentation；保留 arrow/system、gesture commit latest、verify 可 back、结算后不困住、固定 notice 的字面及不改 outcome 断言。 |
| [AccountRecoveryBindingTest.kt](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/feature/accountrecovery/AccountRecoveryBindingTest.kt>) | 4 | 留 shared 集成层，改用生产 typed data factory + controller，不把 presentation 依赖反装进 data；继续测三阶段、reset 前换 token、迟 verify、迟 reset receipt。 |
| [AccountRecoveryRepositoryTest.kt](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/data/AccountRecoveryRepositoryTest.kt>) | 12 | 暂留 shared legacy AuthRepository 兼容层（43 基线的一部分），覆盖参数顺序/POST-query/REQUIRED/ABA/late receipt/cleanup failure。新 data 必须另有自己的生产实现测试，不能用旧 shared 通过冒充 data 已测。 |

因此旧 43 条的建议归属为 **presentation 25 + data 2 + shared 16**，每个旧方法名/关键断言至少保留一次。不要搬空方法占计数。

Controller 旧两条要按真实依赖拆到 data：

1. `onlyRealUserIdIsAcceptedAndNoOwnerPhoneOrAliasFallbackExists`：严格 JSON decoder cases、容器、alias / 当前 owner 不回退。
2. `numericTargetIsPreservedAndNotReplacedByCurrentOwnerOrPhone`：生产 data 三阶段 fixture 验证数值目标原表示最终进 reset 的 `userId`，而非 owner/手机号。原方法中 controller COMPLETED / onAccepted 语义由其余 presentation 成功用例继续覆盖，必要时新增 typed-target passthrough 测试；不能让 data tests 反依 presentation 只为原文复制整方法。

另外 [AuthRepositoryTest.kt](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/data/AuthRepositoryTest.kt>)现有 5 条涵盖 login、modifyPassword、legacy reset、密码规则及 guest；不全部塞进 recovery domain。若纯规则拆测，保留其既有 Auth API 契约覆盖。

[AppTextFeatureGoldenTest.kt:526–539](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/core/text/AppTextFeatureGoldenTest.kt#L526-L539>)还从 shared test 引用 internal `RECOVERY_WRITE_BACK_NOTICE`。模块移动后不可只为保旧 import 提升 public：将这一个独立字面/const alias 断言迁到实际消息归属模块的 golden test，原混合测试其余 ApiError/ApiResult/default/callback 次序保持。若内部 back notice 不再是生产 alias，则测试新生产消息投影的同一独立字面，不留无用占位常量。

### 新增验收矩阵（不是本轮已执行结果）

- **domain**：手机号/验证码/密码纯规则；target redacted/无 copy/serialization；outcome 无 transport/session types；匹配 confirmation 与原密码策略字面不变。
- **data**：真实 factory 创建捕获绑定、guest 零请求、严格三 endpoint POST+query/auth；ApiResult → Accepted/Rejected/Unknown 全分类；消息不泄漏；完整 strict data.user.id cases；current cleanup 成功/删除失败、same-value save/clear-save/map mutation/new owner 下 late Accepted；未知 envelope 不假成功，CancellationException 不吞，零自动 retry。
- **presentation**：18 条迁移用例外，补完整 issue 表、late SMS 输入改变仍冷却不授权、新 epoch before/after suspend、stale target/nonce/ack、异常/取消终止、close 后迟 accepted 不发布、state/diagnostics 无 target/凭据、无构造/收集自动请求。
- **ui**：零载荷意图 gate、disabled 状态只导航/允许的 dismiss；短信与 reset 两次确认彼此独立；ack 必须显式；Content 源和签名无 Controller/Spec/raw String 凭证；typed slots 只收非敏感 flags；golden 独立原文字面。
- **shared binder**：epoch 相同值 save 立即丢表单/old dialog；finalReceipt 跨自己的 logout 留存而不带输入；new login 清 receipt；同一 arrow/system 回调实时读；idle→resetting gesture commit 被挡、resetting→uncertain/completed commit 可 back；old dialog nonce 不被最新 nonce 替代；短信 snapshot 改输入不能发新号码；recompose/onResume/timer 无业务网络。

### core 前置测试的真实倒向阻塞

[ApiTestFixture.kt:3–31](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/core/net/ApiTestFixture.kt#L3-L31>)内建 AppRepositories，直接反依 shared，不能原封搬进 core tests。基础闭包负责人需拆 core-only fixture 与 shared repository fixture；不把测试通道变成生产反向依赖。

当前静态数量为：

- [ApiClientTest.kt](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/core/net/ApiClientTest.kt>) 16；[ApiSessionBindingTest.kt](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/core/net/ApiSessionBindingTest.kt>) 13；[MultipartTest.kt](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/core/net/MultipartTest.kt>) 2；先移除 fixture/repository 倒向，改为直接 ApiClient 的等价契约覆盖，再迁 core。已有 repository 特有行为留 shared 集成，不依赖 production public testutils。
- [SessionStoreRevisionTest.kt](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/core/SessionStoreRevisionTest.kt>) 12；[SessionStoreTest.kt](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/core/storage/SessionStoreTest.kt>) 7；随 snapshot/store 迁入 core，尤其直接触及 internal snapshot 的 revision 测试不能留错 compilation。
- [AppLogTest.kt](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/core/log/AppLogTest.kt>) 10；随 log 3 留 shared，不计作 network/session 独立通过。

core 负责人建议的基础模块目标为 **50 个既有定义（12 + 7 + 16 + 13 + 2）**，shared 另留日志 10 个。各 module 使用自有小型 internal fixtureSession，core-only fixture 不建 AppRepositories，shared repo fixture 继续只属于 shared；不将 fixture 能力提升为生产 public API。以上 60 个是此次静态扫描的定义数量，不是本轮执行 PASS；基础模块验收和旧方法集合守恒由该任务单独报告。

## 7. 后续授权门与完成定义

1. Lead 收齐当前 all-regression，冻结基线；本报告仅 task-39 文档交付。
2. 单独授权 core network/session 闭包：确定 diagnostics seam、三个有限 binding API、生产 caller 编译闭包、core-only fixture/混合测试拆分；snapshot 和 compare-and-clear 安全语义不变。
3. 基础模块独立 consumer 可编译、core tests 与 shared compatibility tests 真实通过后，再授权 recovery 四模块、shared binder、legacy AuthRepo 的精确写范围。
4. 四模块必须有真实生产实现、无 shared 反依，typed contracts 无 ApiResult/JSON/Session；UI 无 Controller/Spec/敏感 String props；固定回执/冷却/back 与旧方法集保持。
5. Lead 串行执行 JVM / shared / Android 等统一回归并核实际 XML 数量；未跑平台如实标记，不把静态数目写成通过。

**本轮完成边界：只读审阅 + 该规划文档；无源码/测试/构建变更，无网络/设备/签名/安装/发布操作。**
