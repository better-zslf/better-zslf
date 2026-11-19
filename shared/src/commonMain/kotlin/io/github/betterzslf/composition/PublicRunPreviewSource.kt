package io.github.betterzslf.composition

import io.github.betterzslf.core.net.ApiClient
import io.github.betterzslf.core.storage.SessionStore
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.run.api.*
import io.github.betterzslf.feature.runui.ApiRunReadSource
import io.github.betterzslf.feature.runui.RunReadOutcome
import io.github.betterzslf.feature.runui.RunTaskSelection
import io.github.betterzslf.feature.runui.withQueryContext
import io.github.betterzslf.platform.RunMapCoordinateSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/** Public composition adapter: credentials remain here; the extension receives only a read projection. */
internal class PublicRunPreviewSource(
    private val api: ApiClient,
    sessions: SessionStore,
    scope: CoroutineScope,
) : RunPreviewSource {
    // Observe the mutation itself, never a later collector turn that can conflate clear/save ABA.
    override val revision = sessions.revision
    private val ownerJob = scope.coroutineContext[Job]

    override suspend fun read(selection: RunPreviewSelection): RunPreviewRead {
        if (ownerJob?.isActive != true) {
            return RunPreviewRead.Failed(AppText.Run.READ_FAILURE_MANUAL_REFRESH_NO_WRITE_RETRY_HINT)
        }
        val capturedRevision = revision.value
        // Each explicit read captures once. Its source never retargets an account after suspension.
        val source = ApiRunReadSource.capture(api)
            ?: return RunPreviewRead.Failed(AppText.Run.LOGIN_TO_VIEW_CURRENT_ACCOUNT_RUN_DATA_HINT)
        val owner = source.boundOwnerId
        val lease = object : RunPreviewLease {
            override fun isCurrent(): Boolean = ownerJob?.isActive == true &&
                revision.value == capturedRevision && source.isCurrentSession() &&
                revision.value == capturedRevision
            override fun toString(): String = "RunPreviewLease(redacted)"
        }
        // Recheck after capturing the binding so a concurrent owner/map mutation cannot be admitted.
        if (!lease.isCurrent()) return RunPreviewRead.Failed(AppText.Auth.REQUEST_ACCOUNT_OWNERSHIP_MISMATCH)
        return try {
            when (val response = source.detail(selection.taskId, owner, selection.scheduleId)) {
                // The feature returns finite issues, never an arbitrary backend message.
                is RunReadOutcome.Failure -> if (!lease.isCurrent()) {
                    RunPreviewRead.Failed(AppText.Auth.REQUEST_ACCOUNT_OWNERSHIP_MISMATCH)
                } else RunPreviewRead.Failed(AppText.Run.READ_FAILURE_MANUAL_REFRESH_NO_WRITE_RETRY_HINT)
                is RunReadOutcome.Success -> {
                    if (!lease.isCurrent()) return RunPreviewRead.Failed(AppText.Auth.REQUEST_ACCOUNT_OWNERSHIP_MISMATCH)
                    val decoded = response.data.value
                        ?: return RunPreviewRead.Failed(AppText.Run.ERRAND_DETAIL_OBJECT_UNRECOGNIZED)
                    val detail = decoded.withQueryContext(RunTaskSelection(selection.taskId, selection.scheduleId))
                    // Missing fields may inherit query provenance. Explicitly conflicting, malformed
                    // or ambiguous identities must not become a Ready geometry projection with no ids.
                    if (detail.task.errandId != selection.taskId || detail.task.scheduleId != selection.scheduleId) {
                        return RunPreviewRead.Failed(AppText.Run.TASK_DETAIL_IDENTITY_MISMATCH_START_DISABLED)
                    }
                    val context = RunPreviewContext(detail.task.title, detail.checkpoints, detail.walkPaths,
                        detail.minimumDistanceMeters, detail.warnings, detail.checkpointSourceCount,
                        RunMapCoordinateSystem.GCJ02)
                    if (!lease.isCurrent()) return RunPreviewRead.Failed(AppText.Auth.REQUEST_ACCOUNT_OWNERSHIP_MISMATCH)
                    RunPreviewRead.Ready(context, lease)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { RunPreviewRead.Failed(AppText.Run.READ_FAILURE_MANUAL_REFRESH_NO_WRITE_RETRY_HINT) }
    }
}
