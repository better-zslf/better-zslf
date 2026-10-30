package io.github.betterzslf.feature.settings

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.core.storage.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AppSettings(
    val theme: String = "System",
    val dynamicColor: Boolean = false,
    val preferredRunMode: String = "real",
    val samplingSeconds: Int = 10,
    val metricUnits: Boolean = true,
    val skipImportWarning: Boolean = false,
    /** Developer option. Off by default and never enabled by server data or saved preferences alone. */
    val debugMode: Boolean = false,
    /**
     * ISO weekdays (1=Monday..7=Sunday) the timetable should not draw, because the term really
     * has no classes on them. Empty means show all seven, which is the honest default: hiding a
     * day that does have classes would silently drop real data, so the user opts in per day.
     */
    val hiddenWeekdays: List<Int> = emptyList(),
)

class SettingsStore(private val store: KeyValueStore) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()

    /**
     * One normalisation rule for both read and write, so a corrupt or hand-edited preference can
     * never yield a duplicated/out-of-range weekday or an impossible sampling interval.
     */
    private fun normalise(value: AppSettings): AppSettings = value.copy(
        samplingSeconds = value.samplingSeconds.coerceIn(10, 60),
        hiddenWeekdays = value.hiddenWeekdays.filter { day -> day in 1..7 }.distinct().sorted(),
    )

    private val current = MutableStateFlow(runCatching {
        normalise(json.decodeFromString<AppSettings>(store.get(KEY) ?: "{}"))
    }.getOrElse {
        mutableError.value = AppText.Settings.READ_FAILED_USING_DEFAULTS_NOTICE
        AppSettings()
    })
    val settings = current.asStateFlow()
    fun update(transform: (AppSettings) -> AppSettings): Boolean {
        val next = normalise(transform(current.value))
        return try {
            store.put(KEY, json.encodeToString(next))
            current.value = next
            mutableError.value = null
            true
        } catch (_: Exception) {
            mutableError.value = AppText.Settings.SAVE_FAILED_PREVIOUS_SETTINGS_RETAINED_NOTICE
            false
        }
    }
    fun reset(): Boolean = try {
        store.remove(KEY)
        mutableError.value = null
        true
    } catch (_: Exception) {
        mutableError.value = AppText.Settings.LOCAL_SETTINGS_CLEAR_FAILED_RETRY
        false
    } finally {
        current.value = AppSettings()
        io.github.betterzslf.core.log.AppLog.setEnabled(false)
    }
    private companion object { const val KEY = "settings.v1" }
}
