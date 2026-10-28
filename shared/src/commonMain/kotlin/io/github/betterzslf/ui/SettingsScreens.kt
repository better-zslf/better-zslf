package io.github.betterzslf.ui

import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.betterzslf.AppGraph
import io.github.betterzslf.Route
import io.github.betterzslf.core.result.ApiResult
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference

internal fun samplingChoices(current: Int): List<Int> = (listOf(10, 15, 30, 60) + current).distinct().sorted()

@Composable
fun SettingsScreen(graph: AppGraph, open: (Route) -> Unit, back: () -> Unit) {
    val settings by graph.settings.settings.collectAsState()
    val storageError by graph.settings.error.collectAsState()
    var clear by remember { mutableStateOf(false) }
    val modes = listOf("System", "Light", "Dark")
    val themeNames = listOf(AppText.Settings.FOLLOW_SYSTEM, AppText.Settings.LIGHT_THEME, AppText.Settings.DARK_THEME)
    val sampling = samplingChoices(settings.samplingSeconds)
    Screen(AppText.Settings.SETTINGS_TITLE, back) { padding ->
        PageList(padding) {
            storageError?.let { error -> item { InfoBanner(error, title = AppText.App.LOCAL_DATA_NOTICE, tone = UiTone.Warning) } }
            item { SectionTitle(AppText.Settings.APPEARANCE_TITLE) }
            item { Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius, insideMargin = PaddingValues(0.dp)) {
                WindowDropdownPreference(title = AppText.Settings.DARK_MODE_TITLE, items = themeNames,
                    selectedIndex = modes.indexOf(settings.theme).coerceAtLeast(0),
                    onSelectedIndexChange = { index -> modes.getOrNull(index)?.let { mode -> graph.settings.update { it.copy(theme = mode) } } })
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SwitchPreference(title = AppText.Settings.DYNAMIC_COLOR_TITLE, summary = AppText.Settings.SYSTEM_WALLPAPER_COLOR_HINT,
                    checked = settings.dynamicColor, onCheckedChange = { checked -> graph.settings.update { it.copy(dynamicColor = checked) } })
            } }
            item { SectionTitle(AppText.Navigation.CAMPUS_RUN) }
            item { Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius, insideMargin = PaddingValues(0.dp)) {
                WindowDropdownPreference(title = AppText.Settings.LOCATION_SAMPLING_INTERVAL_TITLE,
                    items = sampling.map { AppText.Settings.samplingInterval(it) },
                    selectedIndex = sampling.indexOf(settings.samplingSeconds).coerceAtLeast(0),
                    onSelectedIndexChange = { index -> sampling.getOrNull(index)?.let { seconds -> graph.settings.update { it.copy(samplingSeconds = seconds) } } })
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                WindowDropdownPreference(title = AppText.Settings.METRIC_UNITS_TITLE,
                    items = listOf(AppText.Settings.KILOMETERS_UNIT, AppText.Settings.MILES_UNIT),
                    selectedIndex = if (settings.metricUnits) 0 else 1,
                    onSelectedIndexChange = { index -> if (index in 0..1) graph.settings.update { it.copy(metricUnits = index == 0) } })
            } }
            item { SectionTitle(AppText.Settings.LOCAL_DATA_TITLE) }
            graph.storageNotice?.let { notice -> item { InfoBanner(notice, title = AppText.App.LOCAL_DATA_NOTICE, tone = UiTone.Warning) } }
            item { Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius, insideMargin = PaddingValues(0.dp)) {
                ArrowPreference(title = if (graph.clearingLocalData) AppText.Settings.CLEARING_LOCAL_DATA else AppText.Settings.CLEAR_LOCAL_DATA,
                    summary = AppText.Settings.CLEAR_DATA_STOPS_ACTIVITY_AND_LOGS_OUT_HINT,
                    enabled = !graph.clearingLocalData, onClick = { if (!graph.clearingLocalData) clear = true })
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                ArrowPreference(AppText.Updates.CHECK_FOR_UPDATES, onClick = { open(Route.Page("updates", title = AppText.Updates.VERSION_UPDATE_TITLE)) })
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                ArrowPreference(AppText.Settings.ABOUT_AND_VERSION_TITLE, summary = graph.platform.versionName,
                    onClick = { open(Route.Page("about", title = AppText.App.ABOUT_APP_TITLE)) })
            } }
            // The developer option is only reachable after five taps on the version string in About.
            if (settings.debugMode) item { Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius, insideMargin = PaddingValues(0.dp)) {
                ArrowPreference(AppText.DevTools.TITLE, summary = AppText.DevTools.ENTRY_SUMMARY,
                    onClick = { open(Route.Page("devtools", title = AppText.DevTools.TITLE)) })
            } }
        }
        ConfirmDialog(clear, AppText.Settings.CLEAR_LOCAL_DATA_CONFIRM_TITLE, AppText.Settings.CLEAR_LOCAL_DATA_CONFIRM_DESCRIPTION, { clear = false }) {
            if (!graph.clearingLocalData) graph.clearLocalData()
            clear = false
        }
    }
}

fun passwordValidation(password: String, confirmation: String): String? = when {
    password.length < 8 -> AppText.Auth.NEW_PASSWORD_MINIMUM_LENGTH_ERROR
    !io.github.betterzslf.data.AuthRepository.isValidNewPassword(password) -> AppText.Auth.NEW_PASSWORD_COMPLEXITY_ERROR
    password != confirmation -> AppText.Auth.NEW_PASSWORD_CONFIRMATION_MISMATCH
    else -> null
}

@Composable
fun PasswordScreen(graph: AppGraph, back: () -> Unit) {
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Screen(AppText.Auth.CHANGE_PASSWORD_TITLE, back) { padding ->
        PageList(padding) {
            item { InfoBanner(AppText.Auth.CHANGE_SCHOOL_PASSWORD_RELOGIN_HINT) }
            item { TextField(old, { old = it }, label = AppText.Auth.CURRENT_PASSWORD_FIELD, singleLine = true, enabled = !submitting,
                cornerRadius = AppUiTokens.FieldRadius, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth()) }
            item { TextField(new, { new = it }, label = AppText.Auth.NEW_PASSWORD_FIELD, singleLine = true, enabled = !submitting,
                cornerRadius = AppUiTokens.FieldRadius, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth()) }
            item { TextField(confirmation, { confirmation = it }, label = AppText.Auth.CONFIRM_NEW_PASSWORD_FIELD, singleLine = true, enabled = !submitting,
                cornerRadius = AppUiTokens.FieldRadius, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth()) }
            if (message.isNotBlank()) item { InfoBanner(message, title = AppText.Common.NOTICE_TITLE, tone = UiTone.Warning) }
            item { PrimaryAction(text = if (submitting) AppText.Common.SUBMITTING else AppText.Auth.CHANGE_PASSWORD_TITLE,
                enabled = !submitting && old.isNotBlank(), onClick = {
                    if (!submitting) {
                        val error = passwordValidation(new, confirmation)
                        if (error != null) message = error else confirming = true
                    }
                }) }
        }
        ConfirmDialog(confirming, AppText.Auth.CHANGE_SCHOOL_PASSWORD_CONFIRM_TITLE, AppText.Auth.PASSWORD_CHANGE_IMMEDIATE_EFFECT_HINT, { confirming = false }) {
            if (!submitting) {
                confirming = false
                submitting = true
                scope.launch {
                    try {
                        when (val result = graph.repositories.auth.modifyPassword(old, new)) {
                            is ApiResult.Failure -> message = result.error.message
                            is ApiResult.Success -> { old = ""; new = ""; confirmation = "" }
                        }
                    } finally { submitting = false }
                }
            }
        }
    }
}
