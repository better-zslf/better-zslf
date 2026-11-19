# R5 校园网首个真实纵切：账号、会话绑定与学校 portal

日期：2026-11-18。状态：**只读规划，尚未授权生产实施**。本报告不代表校园网或 Dorm 已完成模块化。

本规划轮只读取源码与测试定义、创建本报告；未运行 Gradle、真实校园请求、诊断、拨号、注销、heartbeat、设备或安装。task-34 与 task-38 源码保持冻结。task-38 新 5 条 Profile 测试以 Lead 最后一轮 XML 为准：成员随后读取确认 [JVM 12/0](<E:/better-zslf/shared/build/test-results/jvmTest/TEST-io.github.betterzslf.feature.profile.ProfileControllerTest.xml#L2-L15>) 与 [Android Host 12/0](<E:/better-zslf/shared/build/test-results/testAndroidHostTest/TEST-io.github.betterzslf.feature.profile.ProfileControllerTest.xml#L2-L15>)，均含新 5 条测试名；早一轮只有旧 7 条的结果不算新测试通过。不把 Profile 结果当作本校园网计划验收。

## 1. 推荐先闭合的切面与边界

首切选择 **校园宽带账号查询 → 当前绑定的手机号检查 → 显式改密确认 → 学校代理 portal 状态/连接/断开 → 完整账号和连接卡内容 UI**。不是只移一个 interface 或空目录。

- `bind` 在当前实现中指 **ApiClient 的不透明 owner/token/epoch 绑定能力**，不是新增业务接口。现存校园网只有 7 个 endpoint；没有证据支持增加“绑定账号/验证码验证/运营商选择”等接口。
- 学校 `/verification/…` 代理拨号与运营商 `:7001` Dorm 协议是不同纵切。首切不搬 Dorm cipher、XML、dialer、session 或其 controller，不把整个登录页面归到校园网 UI。
- 校园账号与 portal 页面在 session revision 变化后重新捕获 capability；Dorm controller 则保持 graph/route lifetime。保留 task-37 已完成的寿命分离，不用校园 epoch 重新触发 Dorm 的旧会话注销或心跳。
- **完整 data 迁出有 network/session 前置依赖。** 在该前置未完成前，可以完成 typed 边界与 parser/controller/content 物理迁移，但须标记“HTTP bridge 暂留 shared”，不可声称 campus data 完全隔离。

## 2. 当前真实文件与依赖闭包

| 当前文件 | 当前职责、直接依赖 | 首切目标 |
|---|---|---|
| [CampusNetworkRepository](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/data/CampusNetworkRepository.kt#L9-L32>) | 32 行；依赖 ApiClient/ApiSessionBinding；accounts、checkPhone、updatePassword 返回原始 JSON；只允许捕获一次当前绑定 | network 前置完成后物理移至 campusnet data；不复制第二份 HTTP 实现 |
| [CampusPortalRepository](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/data/CampusPortalRepository.kt#L18-L41>) | 4 个学校代理调用、同样的不透明绑定 | 同上；不转成运营商 dialer |
| [CampusNetworkModel](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/feature/campusnet/CampusNetworkModel.kt>) | 混有 Source、具体 Repository adapter、JSON parser、masked UI state、target map、输入校验与错误文案 | 必须按 domain/data/presentation 拆开，不能整文件扔 domain |
| [CampusNetworkController](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/feature/campusnet/CampusNetworkController.kt>) | 256 行；依赖 JSON+ApiResult；generation、隐私 target map、两级确认、一次写及回读、取消后 uncertain | presentation；接 typed source，保持请求时序及 terminal 规则 |
| [CampusPortalModel](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/feature/campusnet/CampusPortalModel.kt>) | online/dial typed 值与 JSON decoder、拒绝分类、显示文案、portal/Dorm HTTP allowlist 混装 | 值和地址 policy 到 domain；JSON decoder 到 data；文案到 presentation |
| [CampusPortalController](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/feature/campusnet/CampusPortalController.kt>) | 227 行；Source/Repository adapter/state/controller 同文件；读取 redirect JSON，probe 后代理 dial | Source 到 domain、Repository adapter 到 data、state/controller 到 presentation |
| [KtorPortalProbe](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/feature/campusnet/KtorPortalProbe.kt>) | ProbeResult 与 Ktor 实现混装；单跳、无认证派生 client、5 秒、取消向上传播 | ProbeResult/窄 probe port 到 domain；Ktor 实现到 data；仍由 AppGraph 持有和关闭 |
| [CampusNetworkFeaturePage](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/ui/campusnet/CampusNetworkFeaturePage.kt#L156-L230>) | 430 行；Graph route binder、Dorm 转发、controller collect、表单秘密、两次入页 read、完整卡片/确认框 | shared 仅 route/lifetime/event binder；完整 state+callbacks Content 到 ui。不得把 Graph 一起搬 |
| [LoginScreen 的 Dorm 校园账号读取](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/ui/LoginScreen.kt#L307-L401>) | Dorm 自身寿命与校园 read fence 已分离；学校 accounts 的宽松 phone lookup 仍在 UI JSON 里 | 仅替换为 typed phone read adapter；不移动/执行 Dorm 副作用 |
| [LoginScreen 的诊断读取](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/ui/LoginScreen.kt#L535-L576>) | bound redirect、probe、handshake 均有 SessionEpochReadFence；旧结果不写 shared holder | 仅 redirect 输入换 typed source，诊断/controller/handshake 归后续 Dorm 纵切 |
| [AppRepositories](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/data/AppRepositories.kt#L7-L21>) | App 组合根创建两个 repository；其他 feature 共用 ApiClient/SessionStore | 接线保留 root，不向 feature 传整个 AppRepositories |
| [FeaturePages](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/ui/FeaturePages.kt#L49-L52>) | campusnet/dormnetwork 路由映射 | 保持产品 binder，不迁入 feature |

另外，[portal 地址 allowlist](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/feature/campusnet/CampusPortalModel.kt#L86-L121>) 被 [Android platform](<E:/better-zslf/shared/src/androidMain/kotlin/io/github/betterzslf/platform/AndroidPlatform.kt#L68>)、[iOS platform](<E:/better-zslf/shared/src/iosMain/kotlin/io/github/betterzslf/platform/PlatformServices.ios.kt#L56>)、[JVM platform](<E:/better-zslf/shared/src/jvmMain/kotlin/io/github/betterzslf/platform/JvmPlatform.kt#L25>) 与 [Dorm transport](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/feature/campusnet/KtorDormTransport.kt#L41-L49>) 共用。它应是一个单一纯 policy，不能在平台/Dorm 再复制。保持包名和函数名，可免平台行为变更。

## 3. 真正的 core 前置，不用循环依赖掩盖

当前 `shared → 新 feature:data → shared` 会构成环：

1. [ApiResult/ApiError](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/core/result/ApiResult.kt>)、[ApiClient](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/core/net/ApiClient.kt>) 和所有网络 helper 仍在 shared。
2. [ApiClient 的 binding 检查](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/core/net/ApiClient.kt#L47-L63>) 与 [ApiSessionBinding](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/core/net/ApiSessionBinding.kt>) 共同依赖 [SessionStore 内部 SessionSnapshot](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/core/storage/SessionStore.kt#L10-L18>)；不能仅移 ApiClient 而让 data 反依 shared。
3. `isBindingCurrent` 不只被校园仓储调用，[AuthRepository](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/data/AuthRepository.kt#L33-L38>)、[ProfileRepository](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/data/ProfileRepository.kt#L16-L18>)、[RunRepository](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/data/RunRepository.kt#L28>) 及 [PublicRunPreviewSource](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/composition/PublicRunPreviewSource.kt#L35-L39>) 也用它。迁模块时这些 `internal` 接缝须显式解决。
4. Auth 的 [exact binding 条件清理](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/data/AuthRepository.kt#L113-L122>) 亦跨此接缝；不得恢复 token-only 比较，不应为了编译批量 public snapshot/token/accessor 或开 friend。

建议另开 core scope，由 Lead 决定合并模块或更细 DAG。有限源码闭包为：6 个 net 文件（Client/Config/Endpoint/Binding/QueryParameters/UploadFile）、ApiResult、Session，以及 SessionStore/KeyValueStore；共 **10 个当前文件**。这只是源码闭包，不等于“10 个文件移动即可完成”：要另定上述 narrow authority API、test fixture 归属、Gradle 和产品消费验证。

合并 network/session 核心可以保留内部 SessionSnapshot；若拆成多个 core 模块，则还需一个不泄露 token/snapshot 的安全接缝。唯一权威快照、先更新 current 再通知 revision/session、late401 compare-and-clear 都必须保持。

若 core 前置暂不做：data 可先拿 **data-only wire port**，shared 的薄 adapter 只负责已绑定的 7 个 raw repository 调用和错误分类转换，feature data 负责 JSON→typed。该 wire port 允许 JSON 只存在 data 边界，不得进入 domain/presentation/ui。但这是临时桥，**不计为 repository/HTTP 的完整迁出**；不推荐为了短期目录数量再发明第二套通用网络层。

## 4. 最小 typed source 替代

建议 domain 定义只满足当前分支的有限 outcome，而不是把整个 ApiError/服务器 message 传上来。现有 campusFailure 只读 8 个 error 类型，下面映射可逐一无损保留其分支，避免为纯 domain 反依 shared。

```kotlin
// 规划接口，不是本次已实施的代码。
enum class CampusIssue {
    AUTHENTICATION, BUSINESS, VALIDATION, TIMEOUT, NETWORK, MALFORMED, HTTP, STORAGE,
}
sealed interface CampusOutcome<out T> {
    data class Success<T>(val value: T) : CampusOutcome<T>
    data class Failed(val issue: CampusIssue) : CampusOutcome<Nothing>
}

// 只用于 data→presentation，toString 必须 redacted，不进入 UiState。
class CampusAccountRecord(val id: String, val account: String, val typeStatus: String)

interface CampusNetworkSource {
    fun isCurrentSession(): Boolean
    suspend fun accounts(): CampusOutcome<List<CampusAccountRecord>>
    suspend fun checkPhone(phone: String): CampusOutcome<Unit>
    suspend fun updatePassword(accountId: String, password: String): CampusOutcome<Unit>
}
interface CampusPortalSource {
    fun isCurrentSession(): Boolean
    suspend fun internetInfo(): CampusOutcome<CampusOnlineStatus>
    suspend fun redirectTarget(): CampusOutcome<CampusRedirectTarget>
    suspend fun dialIn(portalUrl: String): CampusOutcome<CampusDialResult>
    suspend fun offline(): CampusOutcome<Unit>
}
class CampusRedirectTarget(val address: String) // 空串仍保留为“缺地址”，不发明新 Decode 分支
fun interface CampusPortalProbeSource {
    suspend fun probe(portalUrl: String): PortalProbeResult
}
```

- Repository adapter 保留一次 `capture()` 的 fixed binding：`isCurrentSession()` 每次查询同一能力的实时有效性，方法调用不得偷偷 recapture 新会话。构造不发 HTTP。
- UI state 仍只有 masked account/key/phase/preview；原始 id/account、密码只存在 data 和 controller 临时私有字段。target key 仍按 controller query generation/index 生成，不让 decoder 自行计数。
- JSON accounts decoder 只校验结构与字段 scalar；重复 id、trim/control/非空资格、target map、masking 保持一个纯 typed snapshot builder。它可置 domain，展示 fallback 文案由 presentation 选取。不要为了搬 parser 将 ephemeral query generation 改成持久身份。
- 校验 phone/password 可返回有限 issue，再在 presentation 映射原 AppText；不新加运营商复杂度、验证码或手机号持久化规则。
- Controller `call` 改 generic outcome helper，保留 CancellationException 原样抛出、未知异常映射 NETWORK。写入后 uncertain/terminal 与成功回读失败分离保持原样。
- 如果 core scope 已抽出纯 result 合同并决定继续用 `ApiResult<T>`，上述 outcome 可替换为 `ApiResult<这些 typed 值>`；**JSON 不返回到 controller** 是硬边界。不要同时保留两套公开结果体系。

### Dorm 的两个窄读取接缝

1. `redirectTarget()` 的 typed address 可直接供诊断入口使用；它继续套现有 [SessionEpochReadFence](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/ui/SessionEpochReadFence.kt>)，不迁其 route lifetime。
2. Dorm phone 不能直接复用严格 accounts decoder：当前 [Dorm lookup](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/ui/LoginScreen.kt#L382-L394>) 会跳过非对象/无效行，继续找后面的合法手机号；而账号列表 [strict parser](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/feature/campusnet/CampusNetworkModel.kt#L60-L80>) 遇任意非对象行就失败。

因此给 shared Dorm binder 一个有限 typed `readProvisionedPhone(): CampusOutcome<String?>` adapter（复用同一已绑定 accounts endpoint，但保留独立宽松 projection），而不是把 JSON 带回 UI或改变 strict parser 行为。`null` 保持“没有可用自动手机号，允许原有手输”；错误保持原有 queryFailed。前缀算法只有一个原实现，若抽纯 helper 必须物理迁出/更新原调用，不复制。此改动只接输入，不碰 Dorm sign-in/sign-out/heartbeat。

## 5. 不顺改行为的逐项约束

| 行为 | 必须保留的事实 |
|---|---|
| accounts wire | 只有 `operatorAccount[]`；不猜 list/operatorAccountId 等 alias。id/account 仅接受 string 或可解析 Long 的 primitive；未知 typeStatus 原样截 32 字符。重复 id 全部只读；无声截断 1489 行会破坏现有测试 |
| targets/UI | key 为 generation/index；display 无 id/完整账号；刷新失败可保留可见行，但撤销旧 target；改选择/手机号撤销 check 与 preview |
| 手机检查 | code=200 只表示 phone check/SMS 请求成功，不是验证 SMS code；成功 check 可复用，不能自动重发 SMS |
| 改密 | 显式展开→选当前 target→phone check→preview→最终 acknowledgement；只写一次。未知/超时/取消进入 uncertain，不自动重试；成功 ACK 后回读失败仍已完成 |
| online decode | **当前实现和测试接受 string 与 numeric 0/1。** 原模型注释“numeric 不接受”已过时，本轮不据注释改 parser；boolean/未知/缺失为 Unreadable |
| redirect | 只读取 addr；checkStr 目前不使用，迁移不引入新拒绝条件；缺失/非 primitive 保持空地址的旧显示分支 |
| dial decode | status 1 为 Accepted；msg 优先，message 次之；PassWord/Product/no redirect 匹配顺序与 raw 120 字符上限保持 |
| checkPhone/updatePassword/offline ACK | 当前仅看 ApiClient envelope success，不验证 data 内部 status；typed Unit 不得额外解析成新失败 |
| probe | 单跳、不自动跟随重定向、不继承校园 token/cookie/logging；timeout、UA、header case、close 与取消语义保留 |
| cleartext | portal 与两固定 Dorm host policy 原样；不扩 host/port、TLS、network security XML；OS 打开仅产品回调 |
| 寿命 | accounts/portal capability按epoch失效；Dorm runtime/holder/credentials/heartbeat不按campus epoch重启；取消和dispose不自动网络注销 |

7 个 endpoint、POST/query 名及 token 注入方式以 [ApiEndpoint](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/core/net/ApiEndpoint.kt#L44-L56>) 与 [RepositoryContractTest](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/data/RepositoryContractTest.kt#L60-L66>) 为准。该混合 contract 中的 7 个 entry 不是 7 个独立 `@Test`，测试统计不重复计数。

## 6. 拟授权 scope 与顺序（均未实施）

### A. core 前置，单独授权

上述 10 个文件的物理归属、有限 binding authority API、相关 core tests/fixture、全部 Gradle 接线由 Lead 新 scope 决定；不得与 campus 迁移并写当前安全核心。

### B. campusnet domain/data/presentation 源码任务

- `feature/campusnet/domain/src`：typed sources/results、record、online/dial/probe 值、纯输入/账号 target 资格 policy、地址 allowlist。无 Graph、Repository、Ktor、JsonElement、Compose、Session/token。
- `feature/campusnet/data/src`：两个仓储（前置完成后）、Repository typed adapters、accounts/portal/phone JSON decoder、KtorPortalProbe。只依 core network（或明确临时 wire port）与 domain；不依 shared/presentation/ui。
- `feature/campusnet/presentation/src`：两个 controller、masked state、preview、消息映射。无 JSON、Ktor、具体 repo/Graph；原 generation/一次确认/取消分支不重写。duration 文案 helper 随完整 Content 留 ui，既有同文件测试按下表物理拆分。
- 需拆/移的现存源码：上表的两个 repositories、四个 accounts/portal model/controller、KtorPortalProbe。移后 shared 不留重复实现；不扩大到 Dorm/diagnosis。

### C. campusnet UI/content 与产品 binder，排在 B 的接口冻结之后

- `feature/campusnet/ui/src`：完整连接卡/账号表单/preview/确认框 Content，输入只含 typed UI state、非敏感时间投影与事件 callbacks；不接 controller/source/Graph/Route/Json/raw target id。
- shared 的 [原页面](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/ui/campusnet/CampusNetworkFeaturePage.kt>) 只保留 epoch capture、controller scope/dispose、显式 refresh/事件 adapter、产品 URL/导航映射。原 Dorm 转发可保 root facade，不随 Content 搬。
- [LoginScreen](<E:/better-zslf/shared/src/commonMain/kotlin/io/github/betterzslf/ui/LoginScreen.kt>) 仅两个 typed read 接口衔接；不物理搬 757 行、学籍/校园登录/密码及 Dorm 状态循环。
- 用户输入的 phone/password/confirmation 不进入 saveable UiState；若 Content 本地持有，按输入 revision/terminal 清空。最终提交仍对应 controller 的固定 previewId，不能把确认 gate 放松成任意 callback。

### D. Lead 串行接线与验收

Gradle/settings、shared deps、外部 Pro consumption/substitution、architecture/display/CI guards 都单独 scope。预期依赖：`shared → campusnet data/presentation/ui`；`data → domain + core network`；`presentation → domain + core:text + coroutines`；`ui → presentation（只用state/events）+ design-system`。下层无 `→ shared`，无 friend/source overlay/副本实现。依赖不成立时不能用移动目录代替验收。

## 7. 现有测试定义数量与准确归属

以下是读取 `@Test` 的**定义数**，不是本计划执行结果。首切强相关基线 **58 条**，不包含混合 repository contract、session core 或 live tests。

| 当前文件 | 定义数 | 迁移后建议归属 |
|---|---:|---|
| [CampusNetworkRepositoryTest](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/data/CampusNetworkRepositoryTest.kt>) | 4 | 先留 shared 的 real ApiClient integration；core fixture分离后才移 data |
| [CampusNetworkBindingTest](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/feature/campusnet/CampusNetworkBindingTest.kt>) | 3 | 先留 shared：真实绑定+controller+MockEngine跨层，不能将整个 AppRepositories fixture 带入 feature |
| [CampusNetworkControllerTest](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/feature/campusnet/CampusNetworkControllerTest.kt>) | 17 | 16 条 typed fake放 presentation；strict JSON list shape 的1条移 data；大列表target资格断言补入既有presentation用例，不删覆盖 |
| [CampusNetworkRawStatusTest](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/feature/campusnet/CampusNetworkRawStatusTest.kt>) | 3 | presentation；第3条改 typed record输入，JSON形状覆盖归 data |
| [CampusPortalControllerTest](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/feature/campusnet/CampusPortalControllerTest.kt>) | 12 | status解析1条→data；duration1条→ui；其余10条typed fake→presentation（含现有allowlist+拒绝状态断言） |
| [KtorPortalProbeTest 及同文件 HeaderCaseTest](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/feature/campusnet/KtorPortalProbeTest.kt>) | 12 | data，MockEngine；不执行真实 portal |
| [CampusStatusCardSpecTest](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/ui/CampusStatusCardSpecTest.kt>) | 7 | ui，跟完整卡片/duration/content文案归属迁，不复制 |
| **合计** | **58** | 基线分布：data14 / presentation29 / ui8 / shared7；纯domain policy原覆盖可先由presentation消费者调用，后续可原断言拆而不虚增数量 |

最小新增建议 **5 条**（需实施时再定测试名；目标基线58+5=63，不是本轮通过数字）：

1. data：typed portal decoder 保留 numeric/string/unknown/null status、dial拒绝 literals和 msg/message优先、redirect blank fallback，防迁移时把 wire 细节留在 fake controller 测试而漏测真实 decoder。
2. data：三个 Unit ACK 的 envelope-only语义及错误kind映射/取消原样抛出，不自动再调一次。
3. shared integration：same-value save在collector前同时使两个旧typed source失效、零请求、零recapture。
4. shared integration：clear/save ABA同凭据不复活固定binding；原三条token/owner测试不替代此项。
5. data/shared窄边：Dorm typed phone projection对坏行+后续有效行保留宽松查找，不触发carrier transport/自动注销/heartbeat。

[ApiTestFixture](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/core/net/ApiTestFixture.kt>) 当前 internal 且创建全 AppRepositories。先保跨层 tests 在 shared 是真实归属，不为目录对称而公开整个 fixture/复制生产仓储；core/data独立用局部内存 SessionStore+MockEngine fixture，不新增 live凭据路径。

### 后续 Dorm 独立切面的已读测试规模

本次审计的另 **162 条定义**暂不随首切移动，具体分组：

- [CampusDiagnosisControllerTest](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/feature/campusnet/CampusDiagnosisControllerTest.kt>) 16。
- [DormPhoneSourceTest](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/ui/DormPhoneSourceTest.kt>) 3；typed phone适配若需要抽前缀helper，逐条归属决定，不复制算法。
- [DormCipherNegotiationTest](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/feature/campusnet/DormCipherNegotiationTest.kt>) 10、[DormClientIdentityTest](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/feature/campusnet/DormClientIdentityTest.kt>) 8、[DormDialerTest](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/feature/campusnet/DormDialerTest.kt>) 12、[DormErrorDisplayTest](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/feature/campusnet/DormErrorDisplayTest.kt>) 9、[DormLocalTimeTest](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/feature/campusnet/DormLocalTimeTest.kt>) 7、[DormLogoutStoreTest](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/feature/campusnet/DormLogoutStoreTest.kt>) 10、[DormSessionTest](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/feature/campusnet/DormSessionTest.kt>) 9、[DormSignInControllerTest](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/feature/campusnet/DormSignInControllerTest.kt>) 13、[KtorDormTransportTest](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/feature/campusnet/KtorDormTransportTest.kt>) 8，共86。
- [DormCipherDispatchTest](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/feature/campusnet/crypto/DormCipherDispatchTest.kt>) 30、[DormCipherSm4DesVectorTest](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/feature/campusnet/crypto/DormCipherSm4DesVectorTest.kt>) 8、[DormCipherVectorTest](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/feature/campusnet/crypto/DormCipherVectorTest.kt>) 10、[DormPrimitiveVectorTest](<E:/better-zslf/shared/src/commonTest/kotlin/io/github/betterzslf/feature/campusnet/crypto/DormPrimitiveVectorTest.kt>) 9，共57。

因此已盘点集合为 58+162=**220 条**；不是全仓测试总数，也不含未来新增或另模块 core tests。Dorm 后续完整闭包还含协议/crypto 生产24个保留文件及3个 clock actual；本次不搬空原 campusnet目录，也不声明Dorm完成。

## 8. 关闭条件与下一步

仅在 core 前置方案被 Lead 另授权后，才起 campus production scope。一次性冻结 typed contracts后分 data/presentation 与 UI/binder disjoint任务，避免当前安全回归期间并写。

首切验收必须同时看到：typed controller测试+真实MockEngine adapter绑定测试、完整Content编译、shared产品route可消费、Android assemble与私有外部consumer通过；无重复生产实现、无反向依赖、无JSON/Graph进入domain/presentation/ui；旧58条覆盖不丢，新增5条离线安全用例通过。Native/iOS只在相应环境实际跑过后才记执行结果。

**本报告已交付规划，不申请也不执行真实校园网络操作；等待 Lead 新 scope。**
