@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.github.betterzslf.feature.schedule.runtime

import io.github.betterzslf.core.storage.KeyValueStore
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.schedule.*
import io.github.betterzslf.platform.*
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.*

/**
 * Application-scoped owner, not a screen-scoped request. All mutations use the app state dispatcher.
 * Cancellation is complemented by owner/account/generation checks at every persistence boundary.
 */
class ScheduleRuntime(
    private val scope: CoroutineScope,
    storage: KeyValueStore,
    private val sourceFactory: () -> AcademicSource,
    val native: ScheduleNativeBridge = createScheduleNativeBridge(),
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val persistence = SchedulePersistence(storage)
    private val mutableState = MutableStateFlow(ScheduleState())
    val state: StateFlow<ScheduleState> = mutableState.asStateFlow()
    private var stored: StoredSchedule? = null
    // Retain only attribution across a temporary campus 401 or a failed explicit cleanup.
    private var cleanupOwner: String? = null
    private data class CleanupIntent(val owner: String?, val logout: Boolean, val revision: String)
    private var pendingCleanup: CleanupIntent? = null
    private var generation = 0L
    private var verificationGeneration = 0L
    private var source: AcademicSource? = null
    private var sourceAuthenticated = false
    private var pending: Verification? = null
    private var verificationRequest: Deferred<AcademicIdentity>? = null
    private val syncMutex = Mutex()
    private data class SyncResolution(val termId: String, val defaultId: String?)
    private var sync: Deferred<SyncResolution?>? = null
    private var syncKey: String? = null
    private var closed = false
    private var revision = Uuid.random().toString()
    val ownerRevision: String get() = revision

    private class Verification(
        val owner: String, val generation: Long, val inputGeneration: Long,
        val account: String, val password: String, val source: AcademicSource,
    ) { override fun toString() = "Verification(<redacted>)" }

    /** null is an expired/absent campus session, not an instruction to delete encrypted credentials. */
    fun setOwner(owner: String?, force: Boolean = false) {
        if (closed || (!force && mutableState.value.owner == owner)) return
        if (owner != null) cleanupOwner = owner
        invalidateRequests()
        stored = null
        revision = Uuid.random().toString()
        mutableState.value = ScheduleState(owner = owner)
        if (pendingCleanup != null && !retryPendingCleanup()) return
        val nativeOk = runCatching { native.clear(if (owner == null) ScheduleNativeStatus.UNAUTHENTICATED else ScheduleNativeStatus.UNBOUND, if (owner == null) null else revision) }.isSuccess
        if (owner == null) return
        try {
            val record = persistence.restore(owner)
            stored = record
            if (record != null) {
                revision = record.bindingRevision
                native.clear(ScheduleNativeStatus.NOT_SYNCED, revision)
                showRecord(record, resetWeek = true)
                publishNative(cached = true)
            } else {
                val prompt = !persistence.hasSeenPrompt(owner)
                if (prompt) persistence.markPromptSeen(owner)
                mutableState.value = mutableState.value.copy(showBindingPrompt = prompt)
            }
            if (!nativeOk) nativeFailure()
        } catch (_: Exception) {
            mutableState.value = ScheduleState(owner = owner, storageFailure = true, message = AppText.Schedule.STORAGE_FAILURE)
        }
    }

    fun dismissBindingPrompt() {
        mutableState.value = mutableState.value.copy(showBindingPrompt = false)
    }

    fun courseIdForOccurrence(id: String): String? {
        val record = stored ?: return null
        val snapshot = record.snapshots.firstOrNull { it.term.id == record.sourceTermId } ?: return null
        val now = nowMillis()
        return scheduleOccurrences(snapshot, record.periods, now - 7L * 86_400_000, now + 14L * 86_400_000)
            .firstOrNull { it.id == id }?.courseId
    }

    fun cancelVerification() {
        verificationGeneration++
        verificationRequest?.cancel()
        verificationRequest = null
        runCatching { pending?.source?.close() }
        pending = null
        mutableState.value = mutableState.value.copy(verifiedAccount = null, verifying = false)
    }

    suspend fun verifyBinding(account: String, password: String): Boolean {
        val owner = mutableState.value.owner ?: return false
        if (pendingCleanup != null || mutableState.value.storageFailure) return false
        cancelVerification()
        if (closed || account.isBlank() || account.length > 128 || password.isEmpty() || password.length > 512) {
            mutableState.value = mutableState.value.copy(message = AppText.Schedule.VERIFY_FAILED)
            return false
        }
        val ticket = generation
        val inputTicket = verificationGeneration
        val candidate = try { sourceFactory() } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(message = AppText.Schedule.VERIFY_FAILED)
            return false
        }
        var retained = false
        val request = scope.async(start = CoroutineStart.LAZY) { candidate.authenticate(account.trim(), password) }
        verificationRequest = request
        mutableState.value = mutableState.value.copy(verifying = true, message = null)
        try {
            request.start()
            val identity = request.await()
            if (!isCurrent(owner, ticket) || inputTicket != verificationGeneration) return false
            check(identity.account == account.trim())
            pending = Verification(owner, ticket, inputTicket, account.trim(), password, candidate)
            retained = true
            mutableState.value = mutableState.value.copy(verifiedAccount = account.trim(), verifying = false)
            return true
        } catch (cancelled: CancellationException) {
            if (!isCurrent(owner, ticket) || inputTicket != verificationGeneration) return false
            throw cancelled
        } catch (_: Exception) {
            if (isCurrent(owner, ticket) && inputTicket == verificationGeneration) {
                mutableState.value = mutableState.value.copy(message = AppText.Schedule.VERIFY_FAILED, verifying = false)
            }
            return false
        } finally {
            if (verificationRequest === request) verificationRequest = null
            if (!retained) { request.cancel(); runCatching { candidate.close() } }
            if (isCurrent(owner, ticket) && inputTicket == verificationGeneration) {
                mutableState.value = mutableState.value.copy(verifying = false)
            }
        }
    }

    suspend fun confirmBinding(): Boolean {
        val candidate = pending ?: return false
        if (!isCurrent(candidate.owner, candidate.generation) || candidate.inputGeneration != verificationGeneration) return false
        val nextRevision = Uuid.random().toString()
        val previous = stored?.takeIf { it.owner == candidate.owner && it.account == candidate.account }
        val candidateRecord = previous?.copy(password = candidate.password, bindingRevision = nextRevision)
            ?: StoredSchedule(owner = candidate.owner, account = candidate.account, password = candidate.password,
                bindingRevision = nextRevision)
        val next = try {
            persistence.save(candidateRecord, newBinding = true)
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(storageFailure = true, message = AppText.Schedule.STORAGE_FAILURE)
            return false
        }
        // Only a committed binding may replace the previous source/record.
        pending = null
        invalidateRequests()
        source = candidate.source
        sourceAuthenticated = true
        stored = next
        revision = next.bindingRevision
        mutableState.value = ScheduleState(owner = next.owner, boundAccount = next.account)
        showRecord(next, resetWeek = true)
        runCatching { native.clear(ScheduleNativeStatus.NOT_SYNCED, revision) }.onFailure { nativeFailure() }
        publishNative(cached = true)
        refresh()
        return true // Sync failure is separate from successful credential binding.
    }

    /** Auto and manual requests for the same target share one task; different terms serialize. */
    suspend fun refresh(termId: String? = null) {
        val owner = mutableState.value.owner ?: return
        val ticket = generation
        val initial = stored ?: return
        val requestedId = termId ?: initial.selectedTermId
        val requestedKey = "$ticket:$requestedId"
        val cachedDefault = initial.terms.singleOrNull { it.isDefault }?.id ?: initial.terms.singleOrNull()?.id
        while (isCurrent(owner, ticket)) {
            var sameTarget = false
            var activeRequested = requestedId
            val task = syncMutex.withLock {
                val active = sync
                if (active != null && !active.isCompleted) {
                    sameTarget = syncKey == requestedKey
                    activeRequested = syncKey?.substringAfter(':').orEmpty()
                    active
                } else {
                    sameTarget = true
                    scope.async(start = CoroutineStart.LAZY) {
                        var resolved: SyncResolution? = null
                        performSync(owner, ticket, requestedId) { resolved = it }
                        resolved
                    }.also {
                        sync = it
                        syncKey = requestedKey
                        it.start()
                    }
                }
            }
            val resolved = task.await()
            val defaultId = resolved?.defaultId ?: cachedDefault
            val wanted = requestedId.ifBlank { defaultId.orEmpty() }
            val actual = resolved?.termId ?: activeRequested.ifBlank { defaultId.orEmpty() }
            if (sameTarget || (wanted.isNotBlank() && wanted == actual)) return
        }
    }

    /** Persistent first-foreground attempt per school day; a failure never turns every resume into a loop. */
    suspend fun onForeground() {
        runCatching { native.refreshCapabilities() }.onFailure { nativeFailure() }
        if (pendingCleanup != null) {
            if (!retryPendingCleanup()) return
            setOwner(mutableState.value.owner, force = true)
        }
        if (mutableState.value.storageFailure && !recoverStorage()) return
        val current = stored ?: return
        if (!isCurrent(current.owner, generation)) return
        publishNative(cached = true)
        val owner = current.owner
        val ticket = generation
        val date = scheduleSchoolDate(nowMillis()).toString()
        fun markAttempt(key: String): Boolean {
            val latest = stored ?: return false
            if (!isCurrent(owner, ticket, latest.account)) return false
            val validIds = latest.terms.map { it.id }.toSet() + "default" + key
            val attempts = latest.dailyAttempts.filter { (id, day) -> id != key && day == date && id in validIds } + (key to date)
            return save(latest.copy(dailyAttempts = attempts), owner, ticket, latest.account)
        }
        // Resolve the server's current semester daily even while the user is browsing history.
        // The default alias migrates to the canonical returned term at the same commit boundary.
        val sourceKey = current.sourceTermId.ifBlank { "default" }
        if (current.dailyAttempts[sourceKey] != date && current.dailyAttempts["default"] != date) {
            if (!markAttempt("default")) return
            refresh("")
        }
        val latest = stored ?: return
        if (!isCurrent(owner, ticket, latest.account) || mutableState.value.needsCredentials) return
        val selected = latest.selectedTermId
        if (selected.isNotBlank() && selected != latest.sourceTermId && latest.dailyAttempts[selected] != date) {
            if (!markAttempt(selected)) return
            refresh(selected)
        }
    }

    fun selectWeek(week: Int) {
        if (week !in 0..99) return
        mutableState.value = mutableState.value.copy(selectedWeek = week)
    }

    suspend fun selectTerm(id: String) {
        val current = stored ?: return
        if (current.terms.none { it.id == id }) return
        val next = current.copy(selectedTermId = id)
        if (!save(next)) return
        showRecord(next, resetWeek = true)
        if (next.snapshots.none { it.term.id == id }) refresh(id)
    }

    suspend fun setReminders(enabled: Boolean, leadMinutes: Int = 10) {
        val current = stored ?: return
        if (!native.supported || leadMinutes !in 0..120) return
        if (save(current.copy(remindersEnabled = enabled, reminderLeadMinutes = leadMinutes))) {
            showRecord(stored!!)
            publishNative(cached = true)
        }
    }

    /**
     * Switch the vendor capsule between the short minute count and the course-name phrase.
     *
     * Republishing is deliberate: the pill is drawn from the notification's extras, so the change
     * only becomes visible after the card is posted again.
     */
    suspend fun setCapsuleLongText(long: Boolean) {
        val current = stored ?: return
        if (!native.supported) return
        if (save(current.copy(capsuleLongText = long))) {
            showRecord(stored!!)
            publishNative(cached = true)
        }
    }

    suspend fun updatePeriods(periods: List<SchedulePeriod>): Boolean {
        val current = stored ?: return false
        if (!validateSchedulePeriods(periods.sortedBy { it.number })) {
            mutableState.value = mutableState.value.copy(message = AppText.Schedule.PERIODS_INVALID)
            return false
        }
        if (!save(current.copy(periods = periods.sortedBy { it.number }))) return false
        showRecord(stored!!)
        publishNative(cached = true) // Native delivery is reported separately from an atomic local save.
        return true
    }

    suspend fun unbind(): Boolean = clearBinding(logout = false)
    fun clearForLogout(): Boolean = clearBinding(logout = true)

    private fun clearBinding(logout: Boolean): Boolean {
        val owner = pendingCleanup?.owner ?: mutableState.value.owner ?: stored?.owner ?: cleanupOwner
        invalidateRequests()
        stored = null
        revision = Uuid.random().toString()
        pendingCleanup = CleanupIntent(owner, logout, revision)
        mutableState.value = ScheduleState(owner = if (logout) null else mutableState.value.owner)
        return retryPendingCleanup()
    }

    private fun retryPendingCleanup(): Boolean {
        val intent = pendingCleanup ?: return true
        val nativeOk = runCatching {
            native.clear(if (intent.logout) ScheduleNativeStatus.UNAUTHENTICATED else ScheduleNativeStatus.UNBOUND,
                if (intent.logout) null else intent.revision)
        }.isSuccess
        val storageOk = intent.owner?.let { runCatching { persistence.revoke(it) }.isSuccess } ?: true
        if (nativeOk && storageOk) {
            pendingCleanup = null
            if (intent.logout) cleanupOwner = null
        }
        mutableState.value = mutableState.value.copy(storageFailure = !storageOk || !nativeOk,
            message = if (!storageOk) AppText.Schedule.UNBIND_FAILED else if (!nativeOk) AppText.Schedule.NATIVE_UPDATE_FAILED else null)
        return nativeOk && storageOk
    }

    private suspend fun performSync(owner: String, ticket: Long, requestedId: String, resolved: (SyncResolution) -> Unit) {
        val captured = stored ?: return
        if (!isCurrent(owner, ticket, captured.account)) return
        mutableState.value = mutableState.value.copy(busy = true, message = null)
        var loggedInThisSync = false
        val client = try { source ?: sourceFactory().also { source = it; sourceAuthenticated = false } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (isCurrent(owner, ticket, captured.account)) mutableState.value = mutableState.value.copy(busy = false,
                message = if (mutableState.value.snapshot == null) AppText.Schedule.NO_CACHE else AppText.Schedule.CACHE_RETAINED_HINT)
            return
        }
        suspend fun login() {
            if (!isCurrent(owner, ticket, captured.account)) throw CancellationException()
            if (loggedInThisSync) throw AcademicSessionExpiredException()
            loggedInThisSync = true
            mutableState.value = mutableState.value.copy(reauthenticating = true)
            val identity = client.authenticate(captured.account, captured.password)
            if (!isCurrent(owner, ticket, captured.account)) throw CancellationException()
            if (identity.account != captured.account) throw AcademicAuthenticationException()
            sourceAuthenticated = true
            mutableState.value = mutableState.value.copy(reauthenticating = false)
        }
        suspend fun load(): Pair<List<ScheduleTerm>, ScheduleSnapshot> {
            val terms = client.terms()
            if (!isCurrent(owner, ticket, captured.account)) throw CancellationException()
            if (terms.isEmpty() || terms.size > 128 || terms.any { it.id.isBlank() } || terms.map { it.id }.distinct().size != terms.size) throw AcademicProtocolException()
            val selected = terms.firstOrNull { it.id == requestedId }
                ?: terms.singleOrNull { it.isDefault }
                ?: terms.singleOrNull()
                ?: throw AcademicProtocolException() // Never pick the first HTML option as the current term.
            resolved(SyncResolution(selected.id, terms.singleOrNull { it.isDefault }?.id ?: terms.singleOrNull()?.id))
            return terms to client.fetch(selected)
        }
        try {
            if (!sourceAuthenticated) login()
            val (terms, response) = try { load() } catch (_: AcademicSessionExpiredException) {
                if (!isCurrent(owner, ticket, captured.account)) throw CancellationException()
                sourceAuthenticated = false
                login()
                load() // Exactly one reauthentication and one replay, never an unbounded retry.
            }
            if (!isCurrent(owner, ticket, captured.account)) return
            val latest = stored ?: return
            val expectedTerm = terms.firstOrNull { it.id == requestedId } ?: terms.singleOrNull { it.isDefault } ?: terms.singleOrNull()
            if (response.term.id != expectedTerm?.id || !validSnapshot(response)) throw AcademicProtocolException()
            if (!response.complete) {
                mutableState.value = mutableState.value.copy(message = AppText.Schedule.PARTIAL_PARSE_RETAINED)
                publishNative(cached = true)
                return
            }
            val oldHealthy = latest.snapshots.firstOrNull { it.term.id == response.term.id }
            if (response.weekMondays.isEmpty() && !oldHealthy?.weekMondays.isNullOrEmpty() && response.warnings.any { it.startsWith("CALENDAR_") }) {
                // A transient calendar failure must not erase healthy dated reminders or mark old dates fresh.
                mutableState.value = mutableState.value.copy(message = AppText.Schedule.CACHE_RETAINED_HINT)
                publishNative(cached = true)
                return
            }
            val healthy = response.copy(syncedAtMillis = nowMillis())
            val defaultId = terms.singleOrNull { it.isDefault }?.id
            val sourceTerm = if (healthy.term.id == defaultId) healthy.term.id
                else latest.sourceTermId.ifBlank { defaultId ?: healthy.term.id }
            val selectedTerm = latest.selectedTermId.takeIf { id -> terms.any { it.id == id } } ?: healthy.term.id
            val all = latest.snapshots.filterNot { it.term.id == healthy.term.id } + healthy
            // Pin BOTH the reminder source and the latest visible semester, including gated history replies.
            val pinnedIds = setOf(sourceTerm, selectedTerm)
            val pinned = all.filter { it.term.id in pinnedIds }
            val snapshots = pinned + all.filterNot { it.term.id in pinnedIds }
                .takeLast(SchedulePersistence.MAX_TERMS - pinned.size)
            val attemptKey = requestedId.ifBlank { "default" }
            val aliasAttempt = latest.dailyAttempts[attemptKey].takeIf { requestedId != healthy.term.id }
            val attempts = if (aliasAttempt == null) latest.dailyAttempts else
                latest.dailyAttempts - attemptKey + (healthy.term.id to aliasAttempt)
            val next = latest.copy(terms = terms, snapshots = snapshots, sourceTermId = sourceTerm,
                selectedTermId = selectedTerm, dailyAttempts = attempts)
            if (!save(next, owner, ticket, captured.account)) return
            showRecord(next, resetWeek = mutableState.value.snapshot == null)
            mutableState.value = mutableState.value.copy(needsCredentials = false, message = null)
            publishNative(cached = healthy.term.id != sourceTerm)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AcademicAuthenticationException) {
            if (isCurrent(owner, ticket, captured.account)) {
                sourceAuthenticated = false
                mutableState.value = mutableState.value.copy(needsCredentials = true, message = AppText.Schedule.CREDENTIALS_REQUIRED)
                publishNative(cached = true)
            }
        } catch (_: AcademicSessionExpiredException) {
            if (isCurrent(owner, ticket, captured.account)) {
                sourceAuthenticated = false
                mutableState.value = mutableState.value.copy(needsCredentials = true, message = AppText.Schedule.CREDENTIALS_REQUIRED)
                publishNative(cached = true)
            }
        } catch (_: Exception) {
            if (isCurrent(owner, ticket, captured.account)) {
                mutableState.value = mutableState.value.copy(message = if (mutableState.value.snapshot == null) AppText.Schedule.NO_CACHE else AppText.Schedule.CACHE_RETAINED_HINT)
                publishNative(cached = true)
            }
        } finally {
            if (isCurrent(owner, ticket, captured.account)) mutableState.value = mutableState.value.copy(busy = false, reauthenticating = false)
        }
    }

    private fun recoverStorage(): Boolean {
        val owner = mutableState.value.owner ?: return false
        return try {
            val restored = persistence.restore(owner)
            if (restored?.bindingRevision != stored?.bindingRevision) {
                invalidateRequests()
                stored = restored
                revision = restored?.bindingRevision ?: Uuid.random().toString()
                native.clear(if (restored == null) ScheduleNativeStatus.UNBOUND else ScheduleNativeStatus.NOT_SYNCED, revision)
                mutableState.value = ScheduleState(owner = owner)
            }
            if (restored != null) {
                stored = restored
                showRecord(restored)
            } else {
                val prompt = !persistence.hasSeenPrompt(owner)
                if (prompt) persistence.markPromptSeen(owner)
                mutableState.value = mutableState.value.copy(showBindingPrompt = mutableState.value.showBindingPrompt || prompt)
            }
            mutableState.value = mutableState.value.copy(storageFailure = false, message = null)
            true
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(storageFailure = true, message = AppText.Schedule.STORAGE_FAILURE)
            false
        }
    }

    private fun showRecord(record: StoredSchedule, resetWeek: Boolean = false) {
        val snapshot = record.snapshots.firstOrNull { it.term.id == record.selectedTermId }
        val sourceSnapshot = record.snapshots.firstOrNull { it.term.id == record.sourceTermId }
        val pendingCount = sourceSnapshot?.let(::scheduleUndatedCourseCount) ?: 0
        mutableState.value = mutableState.value.copy(owner = record.owner, boundAccount = record.account,
            snapshot = snapshot, sourceSnapshot = sourceSnapshot, terms = record.terms, sourceTermId = record.sourceTermId.ifBlank { null },
            selectedWeek = if (resetWeek) currentWeek(snapshot) else mutableState.value.selectedWeek,
            remindersEnabled = record.remindersEnabled, reminderLeadMinutes = record.reminderLeadMinutes,
            capsuleLongText = record.capsuleLongText,
            periods = record.periods, pendingReminderCourses = pendingCount, storageFailure = false)
    }

    private fun currentWeek(snapshot: ScheduleSnapshot?): Int {
        if (snapshot == null) return 1
        return scheduleWeekForDate(snapshot, scheduleSchoolDate(nowMillis()))
            ?: snapshot.courses.flatMap { it.weeks }.minOrNull() ?: 1
    }

    private fun save(next: StoredSchedule, owner: String = next.owner, ticket: Long = generation, account: String = next.account): Boolean {
        if (!isCurrent(owner, ticket, account)) return false
        return try {
            stored = persistence.save(next)
            true
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(storageFailure = true, message = AppText.Schedule.STORAGE_FAILURE)
            false
        }
    }

    private fun publishNative(cached: Boolean): Boolean {
        if (!native.supported) return true
        val record = stored ?: return true
        if (!isCurrent(record.owner, generation, record.account)) return false
        val snapshot = record.snapshots.firstOrNull { it.term.id == record.sourceTermId }
        val status = when {
            mutableState.value.needsCredentials -> ScheduleNativeStatus.NEEDS_LOGIN
            snapshot == null -> ScheduleNativeStatus.NOT_SYNCED
            cached -> ScheduleNativeStatus.CACHED
            else -> ScheduleNativeStatus.READY
        }
        return runCatching { native.publish(ScheduleNativePublication(ownerRevision = revision, status = status,
            snapshot = snapshot, periods = record.periods, remindersEnabled = record.remindersEnabled,
            reminderLeadMinutes = record.reminderLeadMinutes, uncertainCount = mutableState.value.pendingReminderCourses,
            // Display-only: the island shows the instructor, but the redactor drops this before
            // anything is persisted. Sourced from the in-memory snapshot, which still has it.
            capsuleLongText = record.capsuleLongText,
            displayTeachers = snapshot?.courses.orEmpty()
                .filter { it.teacher.isNotBlank() }
                .associate { it.id to it.teacher })) }.getOrDefault(false).also {
            if (!it) nativeFailure()
        }
    }

    private fun nativeFailure() {
        mutableState.value = mutableState.value.copy(message = AppText.Schedule.NATIVE_UPDATE_FAILED)
    }

    private fun isCurrent(owner: String, ticket: Long, account: String? = null): Boolean =
        !closed && generation == ticket && mutableState.value.owner == owner && (account == null || stored?.account == account)

    private fun invalidateRequests() {
        generation++
        cancelVerification()
        sync?.cancel()
        sync = null
        syncKey = null
        runCatching { source?.close() }
        source = null
        sourceAuthenticated = false
    }

    fun close() {
        if (closed) return
        invalidateRequests()
        closed = true
        native.close()
    }

    companion object {
        private fun validSnapshot(snapshot: ScheduleSnapshot): Boolean = snapshot.courses.size <= 1000 &&
            snapshot.courses.all { course -> course.id.isNotBlank() && course.title.isNotBlank() && course.dayOfWeek in 1..7 &&
                course.periods.isNotEmpty() && course.periods.all { it in 1..12 } && course.weeks.all { it in 0..99 } } &&
            snapshot.courses.map { it.id }.distinct().size == snapshot.courses.size &&
            snapshot.weekMondays.all { (week, date) -> week in 0..99 && runCatching { LocalDate.parse(date).dayOfWeek == DayOfWeek.MONDAY }.getOrDefault(false) }
    }
}
