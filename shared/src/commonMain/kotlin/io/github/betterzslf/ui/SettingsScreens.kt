package io.github.betterzslf.ui

import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import io.github.betterzslf.AppGraph
import io.github.betterzslf.Route
import io.github.betterzslf.core.result.ApiResult
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
fun SettingsScreen(graph: AppGraph, open: (Route) -> Unit, back: () -> Unit) {
    val settings by graph.settings.settings.collectAsState()
    val storageError by graph.settings.error.collectAsState()
    var clear by remember { mutableStateOf(false) }
    Screen(AppText.Settings.SETTINGS_TITLE, back) { padding ->
        PageList(padding) {
            storageError?.let { error -> item { SectionCard(AppText.App.LOCAL_DATA_NOTICE, error) } }
            item { Text(AppText.Settings.APPEARANCE_TITLE) }
            item { Card {
                val names = mapOf("System" to AppText.Settings.FOLLOW_SYSTEM, "Light" to AppText.Settings.LIGHT_THEME, "Dark" to AppText.Settings.DARK_THEME)
                ArrowPreference(AppText.Settings.DARK_MODE_TITLE, summary = names[settings.theme] ?: AppText.Settings.FOLLOW_SYSTEM, onClick = {
                    val modes = listOf("System", "Light", "Dark")
                    val next = modes[(modes.indexOf(settings.theme).coerceAtLeast(0) + 1) % modes.size]
                    graph.settings.update { it.copy(theme = next) }
                })
                SwitchPreference(title = AppText.Settings.DYNAMIC_COLOR_TITLE, summary = AppText.Settings.SYSTEM_WALLPAPER_COLOR_HINT, checked = settings.dynamicColor,
                    onCheckedChange = { checked -> graph.settings.update { it.copy(dynamicColor = checked) } })
            } }
            item { Text(AppText.Navigation.CAMPUS_RUN) }
            item { Card {
                ArrowPreference(AppText.Settings.DEFAULT_RUN_MODE_TITLE, summary = if (settings.preferredRunMode == "ask") AppText.Settings.ASK_RUN_MODE_DEFAULT_REAL else AppText.Run.REAL_RUNNING_MODE_LABEL, onClick = {
                    graph.settings.update { it.copy(preferredRunMode = if (it.preferredRunMode == "real") "ask" else "real") }
                })
                ArrowPreference(AppText.Settings.LOCATION_SAMPLING_INTERVAL_TITLE, summary = AppText.Settings.samplingInterval((settings.samplingSeconds)), onClick = {
                    val values = listOf(10, 15, 30, 60)
                    val next = values[(values.indexOf(settings.samplingSeconds).coerceAtLeast(0) + 1) % values.size]
                    graph.settings.update { it.copy(samplingSeconds = next) }
                })
                SwitchPreference(title = AppText.Settings.METRIC_UNITS_TITLE, summary = AppText.Settings.METRIC_AND_IMPERIAL_UNITS_HINT, checked = settings.metricUnits,
                    onCheckedChange = { checked -> graph.settings.update { it.copy(metricUnits = checked) } })
            } }
            item { Text(AppText.Settings.LOCAL_DATA_TITLE) }
            graph.storageNotice?.let { notice -> item { SectionCard(AppText.App.LOCAL_DATA_NOTICE, notice) } }
            item { SectionCard(if (graph.clearingLocalData) AppText.Settings.CLEARING_LOCAL_DATA else AppText.Settings.CLEAR_LOCAL_DATA, AppText.Settings.CLEAR_DATA_STOPS_ACTIVITY_AND_LOGS_OUT_HINT, { if (!graph.clearingLocalData) clear = true }) }
            item { Text(AppText.Settings.ABOUT_AND_VERSION_TITLE) }
            item { SectionCard("better-zslf", graph.platform.versionName, { open(Route.Page("about", title = AppText.App.ABOUT_APP_TITLE)) }) }
            item { SectionCard(AppText.Updates.CHECK_FOR_UPDATES, onClick = { open(Route.Page("updates", title = AppText.Updates.VERSION_UPDATE_TITLE)) }) }
        }
        ConfirmDialog(clear, AppText.Settings.CLEAR_LOCAL_DATA_CONFIRM_TITLE, AppText.Settings.CLEAR_LOCAL_DATA_CONFIRM_DESCRIPTION, { clear = false }) {
            graph.clearLocalData()
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
            item { SectionCard(AppText.Auth.CHANGE_ACCOUNT_PASSWORD_TITLE, AppText.Auth.CHANGE_SCHOOL_PASSWORD_RELOGIN_HINT) }
            item { TextField(old, { old = it }, label = AppText.Auth.CURRENT_PASSWORD_FIELD, singleLine = true, enabled = !submitting,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth()) }
            item { TextField(new, { new = it }, label = AppText.Auth.NEW_PASSWORD_FIELD, singleLine = true, enabled = !submitting,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth()) }
            item { TextField(confirmation, { confirmation = it }, label = AppText.Auth.CONFIRM_NEW_PASSWORD_FIELD, singleLine = true, enabled = !submitting,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth()) }
            if (message.isNotEmpty()) item { SectionCard(AppText.Common.NOTICE_TITLE, message) }
            item { Button(enabled = !submitting && old.isNotBlank(), onClick = {
                val error = passwordValidation(new, confirmation)
                if (error != null) message = error else confirming = true
            }) { Text(if (submitting) AppText.Common.SUBMITTING else AppText.Auth.CHANGE_PASSWORD_TITLE) } }
        }
        ConfirmDialog(confirming, AppText.Auth.CHANGE_SCHOOL_PASSWORD_CONFIRM_TITLE, AppText.Auth.PASSWORD_CHANGE_IMMEDIATE_EFFECT_HINT, { confirming = false }) {
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
