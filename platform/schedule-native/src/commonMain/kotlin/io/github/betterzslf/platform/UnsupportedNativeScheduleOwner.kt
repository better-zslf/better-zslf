package io.github.betterzslf.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Fresh inert app-side owner for platforms without a native schedule publication surface. */
fun newUnsupportedScheduleNativeBridge(): ScheduleNativeBridge = UnsupportedScheduleNativeBridge()

internal class UnsupportedScheduleNativeBridge : ScheduleNativeBridge {
    override val supported: Boolean = false
    override val capabilities = MutableStateFlow(ScheduleNativeCapabilities()).asStateFlow()
    private val requests = MutableStateFlow<ScheduleNativeOpenRequest?>(null)
    override val openRequests = requests.asStateFlow()
    override fun refreshCapabilities() = Unit
    override fun requestNotificationPermission(): Boolean = false
    override fun openNotificationSettings(): Boolean = false
    override fun openExactAlarmSettings(): Boolean = false
    override fun openPromotedNotificationSettings(): Boolean = false
    override fun openBatteryOptimizationSettings(): Boolean = false
    override fun requestPinWidget(kind: ScheduleWidgetKind): Boolean = false
    override fun publish(publication: ScheduleNativePublication): Boolean = false
    override fun clear(status: ScheduleNativeStatus, ownerRevision: String?) = Unit
    override fun consumeOpenRequest() { requests.value = null }
    override fun close() = Unit
}
