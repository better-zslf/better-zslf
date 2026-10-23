package io.github.betterzslf.feature.water

import com.juul.kable.Advertisement
import com.juul.kable.Bluetooth
import com.juul.kable.Filter
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlin.time.Duration.Companion.seconds

/** A scan result, not an account identifier. No platform Advertisement leaks into UI. */
data class WaterBleDevice(val id: String, val name: String, val rssi: Int? = null)

enum class WaterBleLinkState { Disconnected, Connecting, Connected, Disconnecting }

/**
 * Injectable boundary. Construction does not start a scan or connect. Collection
 * of [scan] is explicitly user initiated; cancelling collection must stop scanning.
 */
interface WaterBleTransport {
    fun scan(): Flow<WaterBleDevice>
    /** Creates a disconnected handle; called only after scan collection is stopped. */
    fun createConnection(device: WaterBleDevice): WaterBleConnection
}

interface WaterBleConnection {
    val states: Flow<WaterBleLinkState>
    val isConnected: Boolean
    /** [onReady] runs only after RXD notifications have actually been enabled. */
    fun notifications(onReady: suspend () -> Unit): Flow<ByteArray>
    suspend fun connect()
    /** Completes or throws for this write; implementations must not retry it. */
    suspend fun write(bytes: ByteArray)
    suspend fun disconnect()
    /** Force-releases resources even if graceful disconnect failed or timed out. */
    fun close()
}

/**
 * Kable 0.42.0 adapter, checked against that release's source (not main):
 * https://github.com/JuulLabs/kable/tree/0.42.0/kable-core/src/commonMain/kotlin
 * Uses observe's onSubscription barrier, Uuid characteristicOf, and close().
 *
 * Android: the app must grant BLUETOOTH_SCAN/CONNECT and retain KableInitializer
 * in its merged manifest. iOS: provide NSBluetoothAlwaysUsageDescription.
 * Permissions/radio prompts belong to the platform UI, not this transport.
 * Android autoConnectIf defaults to false in 0.42.0 and is never enabled here.
 */
class KableWaterBleTransport(
    private val namePrefix: String = "Water",
    private val writeType: WriteType = WriteType.WithResponse,
) : WaterBleTransport {
    private val advertisements = linkedMapOf<String, Advertisement>()

    override fun scan(): Flow<WaterBleDevice> = flow {
        // No service-only filter: wateremu/known firmware may omit F1F0 in advertisements.
        val scanner = Scanner {
            filters {
                match { name = Filter.Name.Prefix(namePrefix) }
                match { services = listOf(Bluetooth.BaseUuid + WaterProtocol.SERVICE_UUID_16) }
            }
        }
        scanner.advertisements.collect { advertisement ->
            val name = advertisement.name ?: advertisement.peripheralName ?: return@collect
            if (name.length < 5 || advertisement.isConnectable == false) return@collect
            val id = "${advertisement.identifier}"
            advertisements[id] = advertisement
            if (advertisements.size > 200) advertisements.remove(advertisements.keys.first())
            emit(WaterBleDevice(id, name, advertisement.rssi))
        }
    }

    override fun createConnection(device: WaterBleDevice): WaterBleConnection {
        val advertisement = advertisements[device.id]
            ?: throw IllegalArgumentException("Scan again to obtain this water device")
        require((advertisement.name ?: advertisement.peripheralName) == device.name) {
            "Device name changed; select the current scan result"
        }
        val peripheral = Peripheral(advertisement) { disconnectTimeout = 2.seconds }
        return KableWaterBleConnection(peripheral, writeType)
    }
}

private class KableWaterBleConnection(
    private val peripheral: Peripheral,
    private val writeType: WriteType,
) : WaterBleConnection {
    private val txd = characteristicOf(
        Bluetooth.BaseUuid + WaterProtocol.SERVICE_UUID_16,
        Bluetooth.BaseUuid + WaterProtocol.TXD_UUID_16,
    )
    private val rxd = characteristicOf(
        Bluetooth.BaseUuid + WaterProtocol.SERVICE_UUID_16,
        Bluetooth.BaseUuid + WaterProtocol.RXD_UUID_16,
    )

    override val isConnected: Boolean get() = peripheral.state.value is State.Connected
    override val states: Flow<WaterBleLinkState> = peripheral.state.map {
        when (it) {
            is State.Connected -> WaterBleLinkState.Connected
            is State.Connecting -> WaterBleLinkState.Connecting
            State.Disconnecting -> WaterBleLinkState.Disconnecting
            is State.Disconnected -> WaterBleLinkState.Disconnected
        }
    }

    override fun notifications(onReady: suspend () -> Unit): Flow<ByteArray> =
        peripheral.observe(rxd, onSubscription = onReady)

    override suspend fun connect() { peripheral.connect() }
    override suspend fun write(bytes: ByteArray) { peripheral.write(txd, bytes.copyOf(), writeType) }
    override suspend fun disconnect() { peripheral.disconnect() }
    override fun close() { peripheral.close() }
}
