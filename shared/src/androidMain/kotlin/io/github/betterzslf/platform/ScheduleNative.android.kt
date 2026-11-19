package io.github.betterzslf.platform

import android.content.Intent

actual fun createScheduleNativeBridge(): ScheduleNativeBridge =
    newAndroidScheduleNativeBridge { AndroidPlatform.context }

/** Root adapter preserves the cold/warm-start entry point without leaking its host into the module. */
fun handleScheduleNativeIntent(intent: Intent?): Boolean =
    handleAndroidScheduleNativeIntent(intent) { AndroidPlatform.context }
