package io.github.betterzslf.ui.run

import io.github.betterzslf.feature.runui.RunManualDraft
import io.github.betterzslf.feature.runui.RunManualDraftCommit
import io.github.betterzslf.feature.runui.RunManualDraftError
import io.github.betterzslf.platform.RunMapCoordinateSystem
import io.github.betterzslf.platform.RunMapEvent
import io.github.betterzslf.platform.RunMapFailure
import io.github.betterzslf.platform.RunMapModel
import io.github.betterzslf.platform.RunMapPath
import io.github.betterzslf.platform.RunMapProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Opaque configuration scope. The public provider key is never included in diagnostics. */
internal class RunManualDraftIdentity(
    private val owner: Any,
    private val controller: Any,
    val provider: RunMapProvider?,
    private val apiKey: String?,
) {
    val frame: RunMapCoordinateSystem = when (provider) {
        RunMapProvider.AMAP -> RunMapCoordinateSystem.GCJ02
        RunMapProvider.TIAN_DI_TU -> RunMapCoordinateSystem.WGS84
        null -> RunMapCoordinateSystem.UNKNOWN
    }
    val configured: Boolean get() = provider != null && !apiKey.isNullOrBlank()
    fun sharesOwner(other: RunManualDraftIdentity): Boolean = owner === other.owner && controller === other.controller

    fun model(draft: RunManualDraft?, dark: Boolean, editing: Boolean): RunMapModel = RunMapModel(
        provider = provider, apiKey = apiKey,
        importedPath = RunMapPath(points = draft?.points.orEmpty(), coordinateSystem = frame),
        currentLocation = null, editMode = editing, dark = dark,
    )

    override fun equals(other: Any?): Boolean = other is RunManualDraftIdentity &&
        owner === other.owner && controller === other.controller && provider == other.provider &&
        apiKey == other.apiKey && frame == other.frame
    override fun hashCode(): Int = ((owner.hashCode() * 31 + controller.hashCode()) * 31 +
        (provider?.hashCode() ?: 0)) * 31 + (apiKey?.hashCode() ?: 0)
    override fun toString(): String = "RunManualDraftIdentity(redacted)"
}

internal enum class RunManualMapStatus { PENDING, READY, FAILED }
internal enum class RunManualDraftPhase { EDITING, SAVING, SAVE_FAILED, SAVED, STALE, CLOSED }

internal data class RunManualDraftState(
    val phase: RunManualDraftPhase,
    val mapStatus: RunManualMapStatus,
    val draft: RunManualDraft?,
    val failure: RunMapFailure? = null,
    val editError: RunManualDraftError? = null,
) {
    val pointCount: Int get() = draft?.pointCount ?: 0
    val canSave: Boolean get() = phase == RunManualDraftPhase.EDITING &&
        mapStatus == RunManualMapStatus.READY && draft?.canCommit == true
    val canUndo: Boolean get() = phase == RunManualDraftPhase.EDITING && pointCount > 0
    override fun toString(): String = "RunManualDraftState(phase=$phase, map=$mapStatus, count=$pointCount)"
}

/** Pure in-memory event/commit policy. Callers supply live identity and parent-admission predicates. */
internal class RunManualDraftSession(
    val identity: RunManualDraftIdentity,
    private val isIdentityCurrent: () -> Boolean,
    private val isAdmissionCurrent: () -> Boolean,
) {
    /** Every open creates a fresh mount, even for an identical provider and key. */
    val mount: Any = Any()
    private val mutableState = MutableStateFlow(RunManualDraftState(
        phase = RunManualDraftPhase.EDITING,
        mapStatus = if (identity.configured) RunManualMapStatus.PENDING else RunManualMapStatus.FAILED,
        draft = identity.provider?.let { RunManualDraft.empty(it) },
        failure = if (identity.configured) null else RunMapFailure.CONFIGURATION,
    ))
    val state = mutableState.asStateFlow()
    private var pendingCommit: RunManualDraftCommit? = null

    /** A saving operation intentionally makes its own old admission stale; do not cancel it for that. */
    fun refresh(): Boolean {
        val old = mutableState.value
        if (old.phase in setOf(RunManualDraftPhase.CLOSED, RunManualDraftPhase.STALE, RunManualDraftPhase.SAVED)) return false
        if (!isIdentityCurrent() || (old.phase == RunManualDraftPhase.EDITING && !isAdmissionCurrent())) {
            retire(RunManualDraftPhase.STALE)
            return false
        }
        return true
    }

    fun onEvent(identity: RunManualDraftIdentity, mount: Any, event: RunMapEvent): Boolean {
        // A retired document must not even invalidate a different current document.
        if (this.identity != identity || this.mount !== mount) return false
        if (!refresh()) return false
        val old = mutableState.value
        if (old.phase != RunManualDraftPhase.EDITING || !this.identity.configured) return false
        when (event) {
            RunMapEvent.Ready -> {
                // Failure is terminal for this mount; a late Ready never manufactures a retry.
                if (old.mapStatus != RunManualMapStatus.PENDING) return false
                mutableState.value = old.copy(mapStatus = RunManualMapStatus.READY)
            }
            is RunMapEvent.Failed -> mutableState.value = old.copy(mapStatus = RunManualMapStatus.FAILED, failure = event.reason)
            is RunMapEvent.DrawPoint -> {
                if (old.mapStatus != RunManualMapStatus.READY) return false
                val edit = old.draft?.append(event) ?: return false
                mutableState.value = old.copy(draft = edit.draft, editError = edit.error)
                return edit.accepted
            }
            is RunMapEvent.CheckpointSelected -> return false
        }
        return true
    }

    fun undo(): Boolean {
        if (!refresh()) return false
        val old = mutableState.value
        if (old.phase != RunManualDraftPhase.EDITING) return false
        val edit = old.draft?.undo() ?: return false
        mutableState.value = old.copy(draft = edit.draft, editError = edit.error)
        return edit.accepted
    }

    fun clear(): Boolean {
        if (!refresh()) return false
        val old = mutableState.value
        if (old.phase != RunManualDraftPhase.EDITING) return false
        val draft = old.draft ?: return false
        mutableState.value = old.copy(draft = draft.clear(), editError = null)
        return true
    }

    /** Synchronous single-flight boundary: no more draw/edit admission before any coroutine starts. */
    fun takeCommit(): RunManualDraftCommit? {
        if (!refresh()) return null
        val old = mutableState.value
        if (old.phase != RunManualDraftPhase.EDITING || old.mapStatus != RunManualMapStatus.READY) return null
        val commit = old.draft?.commit() ?: return null
        if (!commit.accepted) {
            mutableState.value = old.copy(editError = commit.error)
            return null
        }
        pendingCommit = commit
        mutableState.value = old.copy(phase = RunManualDraftPhase.SAVING, editError = null)
        return commit
    }

    /** Pure final-publication guard; own busy state must not invalidate its original admission. */
    fun canPublishCommit(commit: RunManualDraftCommit): Boolean =
        pendingCommit === commit && mutableState.value.phase == RunManualDraftPhase.SAVING && isIdentityCurrent()

    fun finishCommit(commit: RunManualDraftCommit, accepted: Boolean): Boolean {
        if (pendingCommit !== commit || mutableState.value.phase != RunManualDraftPhase.SAVING) return false
        if (!refresh()) return false
        pendingCommit = null
        val old = mutableState.value
        mutableState.value = if (accepted) old.copy(phase = RunManualDraftPhase.SAVED, draft = old.draft?.clear())
            else old.copy(phase = RunManualDraftPhase.SAVE_FAILED)
        return true
    }

    /** Must run before hiding the overlay, not only when the exit animation disposes it. */
    fun close() = retire(RunManualDraftPhase.CLOSED)

    fun mapModel(dark: Boolean): RunMapModel {
        val current = mutableState.value
        return identity.model(current.draft, dark, editing = current.phase == RunManualDraftPhase.EDITING)
    }

    private fun retire(phase: RunManualDraftPhase) {
        pendingCommit = null
        val old = mutableState.value
        mutableState.value = old.copy(phase = phase, draft = old.draft?.clear(), editError = null)
    }

    override fun toString(): String = "RunManualDraftSession(phase=${mutableState.value.phase}, redacted)"
}
