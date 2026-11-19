package io.github.betterzslf.ui.campusnet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Trusted binder-only gate. Content sees booleans/masked text, never the captured confirmation id. */
internal class CampusPasswordConfirmationGate(
    private val isLive: () -> Boolean,
    private val currentConfirmationId: () -> Long?,
) {
    private var openedId: Long? = null
    private var mounted = true
    var isOpen by mutableStateOf(false)
        private set
    var acknowledged by mutableStateOf(false)
        private set
    var maskedAccount by mutableStateOf("")
        private set

    /** Captures the exact preview the user opened; opening another preview never retains its ack. */
    fun open(masked: String): Boolean {
        dismiss()
        if (!live()) return false
        val id = runCatching(currentConfirmationId).getOrNull() ?: return false
        if (!live()) return false
        openedId = id
        maskedAccount = masked
        isOpen = true
        return true
    }

    fun reconcile() {
        if (openedId != null && !matches()) dismiss()
    }

    fun toggleAcknowledgement() {
        if (!matches()) { dismiss(); return }
        acknowledged = !acknowledged
    }

    /** Returns only the opened id once. It never substitutes a newer, equal-looking preview. */
    fun consume(): Long? {
        if (!matches()) { dismiss(); return null }
        if (!acknowledged) return null
        val id = openedId
        dismiss()
        return id
    }

    fun dismiss() {
        openedId = null
        isOpen = false
        acknowledged = false
        maskedAccount = ""
    }

    fun retire() { mounted = false; dismiss() }

    private fun live(): Boolean = mounted && runCatching(isLive).getOrDefault(false)
    private fun matches(): Boolean = isOpen && openedId != null && live() &&
        runCatching(currentConfirmationId).getOrNull() == openedId && live()

    override fun toString(): String = "CampusPasswordConfirmationGate(redacted)"
}
