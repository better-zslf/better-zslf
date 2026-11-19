package io.github.betterzslf.platform

import android.content.Context

/**
 * Application context supplied once by the shared Android bootstrap (AndroidPlatform.initialize).
 * The location module intentionally owns no other global Context source; the injected reference
 * mirrors the internal holder idiom the previous in-module implementation used.
 */
object LocationAndroidContext {
    internal lateinit var context: Context

    fun initialize(context: Context) {
        this.context = context.applicationContext
    }
}
