# 热水器离线协议

核心五个协议文件是纯 Kotlin，没有 BLE、网络、平台 import、账号信息、系统时钟或后台任务。可选的 `WaterBleController.kt` / `WaterBleTransport.kt` 负责 Kable 0.42.0 接线，接口见末节；本次验证只注入内存 transport，未运行真实 BLE。平台/UI 由 Lead 接线；温度、时长设置没有上游已验证的写指令，本移植未杜撰这些命令。

## 上游与许可

- [waterctl 2.1.4 / 2.x](https://github.com/celesWuff/waterctl/tree/2.x)：本地输入为 `work/tmp-waterctl`。
- 移植 `src/algorithms.ts`、`src/payloads.ts`、`src/solvers.ts`、`src/bluetooth.ts`、`src/deputy.wat`；黄金向量来自两份 `*.spec.ts`。
- MIT：©2021 celesWuff；WAT 另署 ©2021–2024 celesWuff, Deputy。完整通知保留在 [waterctl-MIT.txt](../../../../../../../../../licenses/waterctl-MIT.txt)。
- WAT 数据段：2096 字节，SHA-256 `9929cda94ef2186ae5d19acaccf30df0c60e33c8643211a01ebe1068da18f81c`。除文档列出的表外，还保留偏移 768 的 16 字节查表；以实际 WAT 指令为准。

## 公共契约

```kotlin
// WaterProtocol: fresh ByteArray for every fixed/dynamic frame
normalizeNotification(input: ByteArray): WaterNotification
crc16changgong(value: String): Int
crc16cgaeaf(value: ByteArray): Int // only the low byte
makeKey(input: ByteArray): ByteArray // exactly 4 bytes -> 4 bytes
makeUnlockResponse(unlockRequest: ByteArray, deviceName: String): ByteArray
makeStartEpilogue(deviceName: String, isKeyAuthPresent: Boolean, context: WaterEpilogueContext): ByteArray
// properties: startPrologue, endPrologue, endEpilogue, baAck, offlinebombFix

WaterEpilogueContext(userIdSeed: Int, dateTime: WaterDateTime)
WaterDateTime(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int)

WaterSessionMachine(
    deviceId: String,
    deviceName: String,
    epilogueContext: () -> WaterEpilogueContext,
    failureGuard: WaterFailureGuard = WaterFailureGuard(),
    operationTimeoutMillis: Long = 15_000,
    legacyDelayMillis: Long = 500,
)
// read-only state and lastFailure
begin(nowMillis: Long, manuallyConfirmedRetry: Boolean = false): List<WaterEffect>
end(nowMillis: Long): List<WaterEffect>
cancel(): List<WaterEffect>
onNotification(input: ByteArray, nowMillis: Long): List<WaterEffect>
onTimer(timer: WaterTimer, nowMillis: Long): List<WaterEffect>
onTransportFailure(nowMillis: Long): List<WaterEffect>
```

### 适配顺序

1. 持有跨重连共享的 `WaterFailureGuard`，用稳定设备 ID（不是仅设备名）调用 `status(deviceId, nowMillis)`；默认一小时内失败两次需手动确认。计数为内存态；进程重启持久化属于后续平台接线范围。
2. 完成通知订阅后调用 `begin`；仅显式用户操作调用新一次 `begin`。从不由失败回调自动重连/重试。
3. 按列表顺序执行 `WaterEffect.Write`（取 `toByteArray()`）、`Schedule`、`CancelTimer`、`Disconnect`。计时器把完整 `WaterTimer` token 回传 `onTimer`；过期 token 被忽略，提前触发的 token 会返回剩余等待时间。
4. B4 写入完成后再执行断开。任一写入失败应停止剩余队列，调用 `onTransportFailure` 并执行其断开效果，包括最终 B4 失败场景。普通断开事件无需伪造写入失败。
5. `nowMillis` 统一使用非负单调时基。B2 构造时才调用 `epilogueContext`；调用端将当前时刻转换为 **Asia/Shanghai** 六个日期组件，随机 seed 为 1..9999，非真实用户 ID。
6. UI 根据 `state` / `ReportFailure` / `RequireManualConfirmation` 更新；展示 `WaterFailureGuard.LOCKOUT_NOTICE`。手动确认只适用于这一次 `begin(..., manuallyConfirmedRetry = true)`，不会清掉历史记录或允许后续静默尝试。

归一化接受 `FD FD 09` / `FD 09` / `09` 前缀，忽略 `AT+` 漏发和不足一帧的有效前缀。它不猜测任意分片/粘包，也不把字节 4 误当作通用长度。AE 严格要求 20 字节与正确 CGAEAF 校验。

### 兼容细节与有意差异

- nonce `FFFF` 的响应使用 `0100`，与上游一致，不修正成 `0000`。
- `makeKey` 保留 WAT 中 `b=FF && a为奇数` 时先 `a+=2`，再对 `a=257` 归零的顺序；输出遵循 TS 的小端 32 位读取后高字节优先顺序。
- B2 随机 ID 保留上游“整数拆高低字节 → 各字节 decAsHex → 截成字节”的特殊编码，非标准四位十进制 BCD。
- 比上游更保守：未知 AF 结果、坏 AE 校验、重复挑战、错序结果均断开；不会像上游未知 AF 分支那样先继续写 B2。
- AF `01/02/04`、握手超时（含 B0 无响应）、启动不接受立即断开并计入失败次数；结束超时不额外计为认证失败。
- 废弃 BB encoder 仅为上游测试兼容保留并标为 `@Deprecated`，状态机没有选择它的路径；BC 仅回复固定清理帧，BA 只回 ack，无任何上传逻辑。

## 验证记录（2026-10-23）

独立运行官方 Kotlin **2.4.21** CLI + Zulu JDK **26**；未运行项目 Gradle、Android 或 iOS 构建。

- **28 个测试方法通过**：全部上游 5 条 ChangGong、5 条 CGAEAF、7 条完整 AE→AF 黄金向量，旧/新 B2 与废弃 BB 的结构、BCD/随机 ID 特殊边界、归一化、错误帧、超时、手动确认、旧 token、B4 写失败和各握手状态。
- 原始 WAT 经 WABT **1.0.37** 编译并在 Node WebAssembly 中实际执行；原始 TypeScript 只剥离类型/导入，注入固定时间与随机值。Kotlin 对 **220702** 条差分向量逐字节通过：
  - 65536 个全部 nonce（固定 MAC）+ 65536 个全部 MAC（固定 nonce `01FF`）+ 65536 个固定种子随机四字节输入，共 **196608** 个 key。
  - **4096** 个随机 AE，覆盖不同 echo byte、nonce、MAC 与数字/字母设备名掩码。
  - seed 1..9999 × 新旧固件共 **19998** 个 B2。
- 编译器在 JDK26 下输出上游 `sun.misc.Unsafe` 弃用警告，测试退出码为 0。

### wateremu 的确切覆盖限制

[wateremu 源码](https://github.com/celesWuff/wateremu/blob/d7942feca3ad92a17154bcb3f4304b02b49c6b8f/src/main.cpp) 是 ESP32/Arduino 固件，MIT ©2024 celesWuff。测试回放其回调中的原样响应数组；**不是** ESP32 固件运行时或 BLE 联调：

- 旧固件回调帧 `B0 → B2 → BA/ack → B3 → B4` 已在内存完成往返。
- 该 commit 的新固件 AE 与 AF 都写着 `malformed, to be fixed`；AE 的校验字段为 `00`，AF 状态为 `00`。测试验证原样输入会被断开，不将它描述为新固件认证成功。
- 新固件成功路径由 waterctl 黄金向量及状态机测试覆盖，尚无“完整上游 wateremu 新固件运行时”成功证据。
- 没有开启蓝牙、扫描、连接或访问真实设备；没有任何真实网络写操作。真实固件差异、GATT 时序/权限、平台计时与上海时区接线仍待相应平台验证。
- 196608 个 key 不是全部 `2^32` 输入穷举；设备名实际格式和跨固件差异没有由这些离线向量证明。

## 可复现命令

独立 CLI 工具**仅验证原 28 项纯协议测试/220702 条差分向量**，显式排除 `WaterBle*.kt`，无需 Kable/协程 classpath。仅在项目测试未并发运行时执行：它们在本测试目录 `build/` 中短暂生成 `.kt` 并在编译后删除，避免后续 Gradle 递归扫描到副本。最终 jar/向量在被忽略的 `build/` 中。

含 adapter 的全部热水器测试由 Lead 统一运行 `./gradlew :shared:jvmTest --tests '*Water*Test'`，不调用真实 BLE。

```powershell
$Tests = 'shared/src/commonTest/kotlin/io/github/betterzslf/feature/water'
$Compiler = '.local/kotlin-2.4.21/kotlinc'
# $Python/$Node/$JavaHome 为本机可用工具路径
& $Python "$Tests/tools/verify_kotlin.py" --compiler-home $Compiler --java-home $JavaHome

# 用未改动的上游 WAT 重新生成查表源（仅需要审查/同步上游时运行）
& $Python "$Tests/tools/generate_key_tables.py" work/tmp-waterctl/src/deputy.wat `
  shared/src/commonMain/kotlin/io/github/betterzslf/feature/water/WaterKeyTables.kt

# WABT 下载并解包到测试 build/oracle（无项目依赖变更）
New-Item -ItemType Directory -Force "$Tests/build/oracle" | Out-Null
Invoke-WebRequest https://registry.npmjs.org/wabt/-/wabt-1.0.37.tgz -OutFile "$Tests/build/oracle/wabt.tgz"
tar -xzf "$Tests/build/oracle/wabt.tgz" -C "$Tests/build/oracle"
& $Node "$Tests/tools/generate_oracle.mjs" work/tmp-waterctl `
  "$Tests/build/oracle/package/index.js" "$Tests/build/oracle/vectors.tsv"
& $Python "$Tests/tools/verify_oracle.py" --compiler-home $Compiler --java-home $JavaHome `
  --vectors "$Tests/build/oracle/vectors.tsv"
```

## common Kable adapter（task-6）

### 接线契约

```kotlin
WaterBleController(
    parentScope: CoroutineScope,
    transport: WaterBleTransport, // 真正接线用 KableWaterBleTransport()；模拟验证注入 fake
    epilogueContext: () -> WaterEpilogueContext, // 在 B2 时采样上海时间/随机 seed
    // 其余参数为可注入的 guard、单调时钟与 timeout
)
val state: StateFlow<WaterBleUiState>
suspend fun startScan(durationMillis: Long = 15_000)
suspend fun stopScan()
suspend fun connect(device: WaterBleDevice, manuallyConfirmedRetry: Boolean = false): Boolean
suspend fun begin(manuallyConfirmedRetry: Boolean = false): Boolean
suspend fun end(): Boolean
suspend fun disconnect()
suspend fun close()
suspend fun retryStatus(deviceId: String): WaterRetryStatus
```

- 构造无扫描、连接或权限请求。扫描必须显式调用，默认 15 秒停止；同 ID 合并结果，UI 最多保留 100 个。默认 Kable 过滤器为 `Water` 名称前缀 **或** F1F0 服务，避免只过滤服务导致漏掉未广播服务 UUID 的固件/wateremu。
- `connect` 只连接并启用 RXD，成功后状态为 `Ready`，**不会发 B0**。用户单独触发 `begin` 才认证；返回 `true` 只代表 B0 已写入并被本地接受，不代表热水已开启，需等待 `state.phase == Active`。重复 `begin` 不再发 B0。
- `end` 只请求 B3，正常结束等待 B3→B4 写完成再断开。`disconnect`/`close` 则取消本地流程并关闭 BLE，不把单纯断开描述为设备已停止。
- 默认使用进程全局 guard/统一单调时间源，跨控制器重建/重连保留失败次数；全局 mutex 串行访问 guard。**没有落盘持久化**。注入 guard 时也应复用相同单调时基。
- 两次失败后 `connect` 在创建 GATT handle **之前**返回 `false` 并进入 `ManualConfirmationRequired`。显式 `connect(..., true)` 的确认只供随后一次用户主动 `begin()` 消费，既不自动认证，也不清除历史记录；下一次仍需新的手动确认。已连接时也可对 `begin(..., true)` 明确确认。
- 每条 Write 顺序 await；写异常/写超时中止整批旧 effects，不继续装入旧 timer，也不重试写。最终 B4 写失败照样断开/close。父 scope 取消、用户断开、链路丢失、RXD 意外结束、正常 B3/B4 均释放观察者和计时器。
- 清理在 `NonCancellable` 中进行；优雅 disconnect 有界等待，最后总调用 `close()`。不同连接使用独立事件队列，旧通知/旧 timer 不驱动新连接。没有自动扫描重启、自动重连或认证重试循环。
- UI 必须展示 `failure` / `retryStatus` 及 `WaterFailureGuard.LOCKOUT_NOTICE`。在父 scope 生命周期结束前主动 `close()`；所有 permission dialog、蓝牙开关和扫描/连接/认证按钮由平台/UI 提供。

### 0.42.0 API 证据与平台前置条件

按固定 [Kable 0.42.0 源码](https://github.com/JuulLabs/kable/tree/0.42.0/kable-core/src/commonMain/kotlin) 核验，而非 main：

- [Peripheral](https://github.com/JuulLabs/kable/blob/0.42.0/kable-core/src/commonMain/kotlin/Peripheral.kt)：在 connect 前收集 `observe(rxd, onSubscription = ...)`；回调是通知实际配置完成的屏障。先等待连接和该屏障，才向 UI 报 `Ready`。
- [Profile](https://github.com/JuulLabs/kable/blob/0.42.0/kable-core/src/commonMain/kotlin/Profile.kt)：使用 `characteristicOf(Uuid, Uuid)`；字符串重载在此版本为 `DeprecationLevel.ERROR`。UUID 通过 `Bluetooth.BaseUuid + 0xf1f0/f1f1/f1f2` 构造。
- 显式 `WriteType.WithResponse`，不假设 write 调用即 fire-and-forget。若实际设备只有 WithoutResponse 属性，需先验证并显式配置 transport 的 writeType；本实现不自动降级/重发。
- [Android builder](https://github.com/JuulLabs/kable/blob/0.42.0/kable-core/src/androidMain/kotlin/PeripheralBuilder.kt) 的 `autoConnectIf` 默认 false，未启用；common peripheral disconnect timeout 显式设 2 秒。
- Android 需声明并在前台获准 `BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`，确认蓝牙已打开；保留依赖 [manifest](https://github.com/JuulLabs/kable/blob/0.42.0/kable-core/src/androidMain/AndroidManifest.xml) 合并的 `KableInitializer` / AndroidX Startup provider。adapter 不替 UI 弹权限、不自动打开蓝牙。
- iOS 需 `NSBluetoothAlwaysUsageDescription`；CoreBluetooth 授权与页面生命周期仍由平台处理。未改 Android/iOS/Gradle/UI 文件。

### adapter 离线验证结果

独立官方 Kotlin **2.4.21** CLI + 本地 JDK **21**，直接使用 Gradle 缓存中的 **Kable JVM 0.42.0**、**coroutines core/test 1.11.0** 编译两个生产文件并运行全部测试：**48/48 方法通过（原 28 + adapter 20）**。

新增 20 项涵盖显式扫描/去重/取消、连接不认证、订阅就绪屏障、新/旧固件、顺序 await、写异常/悬挂写超时、最终 B4 写失败、AF 失败与旧 timer 清除、无响应超时、挂起 connect/write 的用户取消、待处理命令完成、链路/RXD 结束、两次失败 GATT 前门控、一次确认、全局 guard 跨控制器、父 scope 取消、跨连接旧通知隔离、close 幂等。临时 CLI 源与 jar 只位于被忽略的 `work/water-adapter-verification`，没有在 source root 中留下临时 `.kt`。

**覆盖限制**：fake transport 验证 reducer/协程/资源生命周期，不证明 Android/iOS GATT 行为、权限交互、真实设备属性、无线时序或 wateremu ESP32 运行时。没有实例化/调用真实 Kable scanner/peripheral，没有扫描/连接真实设备，也未运行 BLE 广播模拟。项目 Gradle/Android/模拟器的最终验收由 Lead 统一执行。
