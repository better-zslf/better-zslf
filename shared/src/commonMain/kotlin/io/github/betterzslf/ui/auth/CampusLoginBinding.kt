package io.github.betterzslf.ui.auth

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import io.github.betterzslf.AppGraph
import io.github.betterzslf.composition.ContributedOfflineEntry
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.auth.ApiCampusLoginSource
import io.github.betterzslf.feature.auth.CampusLoginController
import io.github.betterzslf.feature.auth.CampusLoginPhase
import io.github.betterzslf.feature.auth.CampusLoginState
import io.github.betterzslf.feature.auth.campusLoginCredentialsIssue
import io.github.betterzslf.feature.auth.normalizeCampusLoginUsername
import io.github.betterzslf.feature.auth.noticeText
import io.github.betterzslf.ui.AppUiTokens
import io.github.betterzslf.ui.PasswordVisibilityIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * The root side effects of the campus binder. Every member is a one-shot intent exit owned by the
 * trusted binder; the core never touches the graph, the session store, or the raw state setters
 * behind these callbacks directly, and credentials never live in this interface.
 */
internal interface CampusLoginBinderSideEffects {
    fun back()
    fun recoverPassword()
    fun openDocument(id: String)
    fun openExtraEntry(target: NavKey)
    fun clearStorageNotice()
    fun clearPassword()
}

/**
 * The opaque local binding between a rendered extra entry and its root-owned navigation target.
 * The label the content shows and the key it reports back are both minted here, per render
 * generation: an older generation's key can never be resolved against a newer binding.
 */
internal class CampusLoginExtraBinding internal constructor(internal val key: String, internal val target: NavKey) {
    override fun toString(): String = "CampusLoginExtraBinding(redacted)"
}

/** Mints the binding of one render generation; [serial] is the per-root monotonically increasing id. */
internal fun nextCampusLoginExtraBinding(entry: ContributedOfflineEntry?, serial: Long): CampusLoginExtraBinding? =
    entry?.let { CampusLoginExtraBinding("login-extra-$serial", it.target) }

/** Only the current generation's key resolves; an old key is dropped, never reused against a newer map. */
internal fun resolveCampusLoginExtraEntry(key: String, binding: CampusLoginExtraBinding?): NavKey? =
    if (binding != null && key == binding.key) binding.target else null

/**
 * The storage-notice whitelist. The graph only ever assigns four existing AppText constants;
 * anything unrecognized falls back to a fixed generic constant, so no arbitrary text reaches
 * the form even if a future caller grows a new notice.
 */
internal fun storageNoticeText(raw: String): String = when (raw) {
    AppText.App.SESSION_CLEANUP_FAILED_OLD_SESSION_MAY_REAPPEAR_HINT,
    AppText.App.LOCAL_DATA_CLEAR_BLOCKED_BY_ACTIVITY_HINT,
    AppText.App.LOCAL_DATA_CLEAR_BLOCKED_BY_UPDATE_HINT,
    AppText.App.PARTIAL_LOCAL_DATA_CLEAR_FAILED,
    -> raw
    else -> AppText.App.SESSION_READ_FAILED_TITLE
}

/**
 * The banner fold, matching the legacy `message.ifBlank { storageNotice }` split exactly: a
 * login issue shows under the login-notice title, the storage fallback under the local-data
 * title, and no notice at all folds the banner away.
 */
internal fun campusLoginBannerNotice(state: CampusLoginState, storageNotice: String?): CampusLoginNotice? {
    val issueCopy = state.noticeText()
    return when {
        issueCopy != null -> CampusLoginNotice(AppText.Auth.LOGIN_NOTICE_TITLE, issueCopy)
        storageNotice != null -> CampusLoginNotice(AppText.App.LOCAL_DATA_NOTICE, storageNoticeText(storageNotice))
        else -> null
    }
}

/**
 * The non-composable intent core of the campus login binder: every user intent is dispatched
 * here exactly once, and this class decides what may touch the controller, the coroutine, and
 * the root side effects. It is the unit the binding test drives with the real controller, the
 * real captured source, and a mock engine — intent only, no rendering.
 *
 * Job discipline kept from the original submit closure:
 *  - begin's gates are the original guards (busy, blank fields, submit-allowed phase);
 *  - the credentials live only in the submit call stack, trimmed for the account exactly as
 *    before, with the password passed through as typed;
 *  - a dropped receipt (retired, foreign, replayed) performs zero root actions — it never
 *    clears the storage notice and never touches new input;
 *  - the accepted continuation is consumed exactly once, and the storage-notice clear plus
 *    the password clear happen in that same call frame.
 */
internal class CampusLoginBinderCore(
    private val controller: CampusLoginController,
    private val scope: CoroutineScope,
    private val sideEffects: CampusLoginBinderSideEffects,
) {
    /** The mirrored snapshot; refreshed after every controller call this core makes. */
    var state: CampusLoginState by mutableStateOf(controller.state)
        private set

    /** The edit gate: every text change retires an in-flight attempt and its pending receipt. */
    fun onEdit() {
        controller.inputsChanged()
        state = controller.state
    }

    /** The explicit SUBMIT gate and job; the returned job is for observation only, null when a gate refused. */
    fun beginSubmit(username: String, password: String): kotlinx.coroutines.Job? {
        val current = controller.state
        if (current.busy || !current.submitAllowed) return null
        // Local admission mirrors the original blank guards; old login passwords carry no policy.
        if (campusLoginCredentialsIssue(username, password) != null) return null
        val account = normalizeCampusLoginUsername(username)
        val secret = password
        val ticket = controller.beginSubmission() ?: return null
        state = controller.state
        return scope.launch {
            // The controller keeps the job active before and after the await; a cancelled scope
            // surfaces as a dropped receipt and no root action is taken here.
            val terminated = controller.submit(ticket, account, secret)
            if (terminated && controller.state.phase == CampusLoginPhase.ACCEPTED) {
                if (controller.consumeAcceptedContinuation(ticket)) {
                    // Same call frame, exactly once: the notice clear and the password clear.
                    sideEffects.clearStorageNotice()
                    sideEffects.clearPassword()
                }
            }
            // A foreign or retired receipt is dropped above; only the mirror refreshes, and the
            // mount that owns it decides whether anything is still rendered from it.
            state = controller.state
        }
    }

    /** Chooser-only intents never reach this page; the rest route straight to root exits. */
    fun onEvent(event: AuthLoginUiEvent, username: String, password: String) {
        when (event) {
            AuthLoginUiEvent.BACK -> sideEffects.back()
            AuthLoginUiEvent.SUBMIT -> beginSubmit(username, password)
            AuthLoginUiEvent.RECOVER_PASSWORD -> sideEffects.recoverPassword()
            AuthLoginUiEvent.USER_AGREEMENT -> sideEffects.openDocument(AppText.Document.USER_AGREEMENT)
            AuthLoginUiEvent.PRIVACY_POLICY -> sideEffects.openDocument(AppText.Document.PRIVACY_POLICY)
            AuthLoginUiEvent.CHOOSE_CAMPUS, AuthLoginUiEvent.CHOOSE_ACADEMIC,
            AuthLoginUiEvent.CHOOSE_DORM, AuthLoginUiEvent.ENTER_APP -> {}
        }
    }

    /** The opaque extra-entry resolution; an unknown or stale key performs no navigation. */
    fun onExtraEntry(key: String, binding: CampusLoginExtraBinding?) {
        val target = resolveCampusLoginExtraEntry(key, binding) ?: return
        sideEffects.openExtraEntry(target)
    }
}

/**
 * The campus-form root binder. The raw draft strings, the reveal flag and their saved-state
 * placement stay out here; the controller itself is mounted on the graph only, never keyed to
 * the campus epoch — the login's own accepted save advances that epoch, and remounting on it
 * would kill the very receipt the continuation still needs. The preflight closure below is a
 * plain read on the state-dispatcher thread for UX gating only: the binding CAS inside the
 * captured source remains the real authority over which attempt is current.
 */
@Composable
internal fun CampusLoginBinder(
    graph: AppGraph,
    mountEpoch: Long,
    username: String,
    onUsername: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    visible: Boolean,
    onVisible: (Boolean) -> Unit,
    extraEntry: ContributedOfflineEntry?,
    onExtraEntry: (NavKey) -> Unit,
    onResetPassword: (() -> Unit)?,
    onDocument: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val preflight: () -> Boolean = {
        // UX preflight only, never an atomic commit: single state-dispatcher reads.
        scope.isActive && graph.applicationScope.isActive &&
            !graph.clearingLocalData && graph.sessions.revision.value == mountEpoch
    }
    // Mounted on the graph alone: an own-accepted epoch advance must not rebuild or close this
    // controller, or it would kill its own receipt before the continuation is consumed.
    val controller = remember(graph) {
        CampusLoginController(
            sourceFactory = { ApiCampusLoginSource.capture(graph.api) },
            isCurrent = preflight,
        )
    }
    DisposableEffect(controller) { onDispose { controller.close() } }
    // Live exits for the core's long-lived object: routing must read the newest frame's lambdas,
    // never the ones captured when the core was first remembered.
    val currentBack by rememberUpdatedState(onBack)
    val currentResetPassword by rememberUpdatedState(onResetPassword)
    val currentDocument by rememberUpdatedState(onDocument)
    val currentExtraEntry by rememberUpdatedState(onExtraEntry)
    val currentPassword by rememberUpdatedState(onPassword)
    val core = remember(controller, scope) {
        CampusLoginBinderCore(controller, scope, object : CampusLoginBinderSideEffects {
            override fun back() { currentBack() }
            override fun recoverPassword() { currentResetPassword?.invoke() }
            override fun openDocument(id: String) { currentDocument(id) }
            override fun openExtraEntry(target: NavKey) { currentExtraEntry(target) }
            override fun clearStorageNotice() { graph.clearStorageNotice() }
            override fun clearPassword() { currentPassword("") }
        })
    }
    // One binding per extra-entry render generation; old keys resolve against nothing.
    val extraSerial = remember { object { var value = 0L } }
    val extraBinding = remember(extraEntry) {
        extraSerial.value += 1
        nextCampusLoginExtraBinding(extraEntry, extraSerial.value)
    }

    val state = core.state
    CampusLoginContent(
        display = CampusLoginDisplay(
            busy = state.busy,
            notice = campusLoginBannerNotice(state, graph.storageNotice),
            extraLabel = extraEntry?.title,
            extraKey = extraBinding?.key ?: "",
        ),
        controls = CampusLoginControls(
            submitEnabled = state.submitAllowed && username.isNotBlank() && password.isNotEmpty(),
            recoveryAvailable = onResetPassword != null,
        ),
        onEvent = { event -> core.onEvent(event, username, password) },
        onExtraEntry = { key -> core.onExtraEntry(key, extraBinding) },
        accountField = {
            TextField(value = username, onValueChange = { value ->
                core.onEdit(); onUsername(value)
            }, label = AppText.Auth.STUDENT_NUMBER_FIELD,
                singleLine = true, cornerRadius = AppUiTokens.FieldRadius, enabled = !state.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().heightIn(min = AppUiTokens.ButtonHeight).testTag("username"))
        },
        passwordField = {
            // S01: the reveal control lives in the field's own trailing slot. The password is
            // submitted exactly as typed — only the student number is ever trimmed.
            TextField(value = password, onValueChange = { value ->
                core.onEdit(); onPassword(value)
            }, label = AppText.Auth.PASSWORD_FIELD,
                singleLine = true, cornerRadius = AppUiTokens.FieldRadius, enabled = !state.busy,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                trailingIcon = { PasswordVisibilityIcon(visible, { onVisible(!visible) }) },
                modifier = Modifier.fillMaxWidth().heightIn(min = AppUiTokens.ButtonHeight).testTag("password"))
        },
    )
}
