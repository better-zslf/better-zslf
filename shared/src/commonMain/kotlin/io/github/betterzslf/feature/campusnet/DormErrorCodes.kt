package io.github.betterzslf.feature.campusnet

/**
 * Error-code table for the ESurfing (天翼校园网) dorm dial-in protocol.
 *
 * Research note: `work/notes/esurfing-dorm-auth.md` §五 (错误码) — §5.1 分类决定重试策略 and
 * §5.2 完整错误码表. Mirrors `work/notes/esurfing-ref/ErrorCode.c` (`error_code_lookup`).
 *
 * The server reports failures in two response headers:
 *  - `Error-Code`: portal side (`1xx` network / `14xxxx` authentication / `4xx` account)
 *  - `Sub-Error`:  RADIUS / ACS side (`130xxxxx` / `1101xxxx`)
 *
 * Both code spaces are disjoint, so a single table answers for both. When both headers are present
 * `Sub-Error` wins, because it is the more specific one: `102 + 13012000` is really a bad password,
 * not a network problem.
 *
 * ## About `DormError.message`
 *
 * [DormError.message] is **not** display text and is deliberately not localised here. This file is
 * `commonMain`, and all user-visible copy in this project lives in `AppText` — which is not ours to
 * edit. The values are provisional English fallbacks that also serve as stable lookup keys; the
 * Lead maps each code onto an `AppText` entry (the canonical Chinese wording is the §5.2 table) and
 * the UI renders only that. Do not show [DormError.message] to a user as-is.
 */

/**
 * How a failed authentication should be retried.
 *
 * §5.1: the classification, not the message, decides the retry strategy. See
 * [dormRetryDelayMinutes] for the schedule each family maps to.
 */
enum class DormErrorFamily {
    /** Network or temporary server trouble: back off and retry indefinitely. */
    TRANSIENT,

    /** Self-healing but requires waiting (arrears / signed in elsewhere / over limit): retry every 35 min. */
    SLOW,

    /** Needs a human to change the configuration: stop and wait for the user. */
    FATAL,

    /** No error recorded (or the code is not in the table). */
    NONE,
}

/**
 * One resolved server error.
 *
 * @property code the `Error-Code` or `Sub-Error` value exactly as the server sent it.
 * @property family what to do about it — the only field retry logic should branch on.
 * @property message provisional English fallback / lookup key; see the file KDoc. Not for display.
 */
data class DormError(val code: String, val family: DormErrorFamily, val message: String)

/**
 * Authentication retry backoff in minutes, from the reference implementation (`DialerClient.c`
 * `static const uint64_t table[]`).
 *
 * The last step is deliberately **35 and not 30**: the server punishes frequent logins by locking
 * the account for 30 minutes, so retrying exactly on the 30-minute mark can land back inside the
 * lock window. 5 extra minutes keeps the retry outside it. (Source comment states this explicitly.)
 */
val DORM_AUTH_RETRY_BACKOFF_MINUTES: List<Int> = listOf(1, 3, 5, 10, 20, 35)

/** The fixed long wait used by [DormErrorFamily.SLOW]: always the last backoff step. */
val DORM_SLOW_RETRY_MINUTES: Int = DORM_AUTH_RETRY_BACKOFF_MINUTES.last()

/**
 * Look up a server error by the two response headers.
 *
 * Rules from §五 and `error_code_lookup`:
 *  - `Sub-Error` is checked first and wins whenever it is known, even if `Error-Code` is also set.
 *  - Otherwise `Error-Code` is checked.
 *  - Leading/trailing whitespace is ignored; blank or `null` means "header absent".
 *
 * @param errorCode value of the `Error-Code` header, `null` or blank when absent.
 * @param subError value of the `Sub-Error` header, `null` or blank when absent.
 * @return the matching error, or `null` when neither header carries a code from the table. `null`
 *         means "unrecognised", which is handled like a network problem; note that `140000`
 *         ("the server recorded no error") is a *known* code and resolves to [DormErrorFamily.NONE].
 */
fun dormErrorCode(errorCode: String?, subError: String?): DormError? =
    findDormError(subError) ?: findDormError(errorCode)

/**
 * How long to wait before the next authentication attempt.
 *
 * Mirrors the retry branch of `DialerClient.c`:
 *  - [DormErrorFamily.FATAL] → `null`: stop auto-retrying until the user changes the configuration
 *    (or restarts / signs out). Retrying a wrong password only triggers the server's login lock.
 *  - [DormErrorFamily.SLOW] → always [DORM_SLOW_RETRY_MINUTES] (35 min), regardless of attempt count.
 *  - [DormErrorFamily.TRANSIENT] and [DormErrorFamily.NONE] → walk
 *    [DORM_AUTH_RETRY_BACKOFF_MINUTES] from the start and stay on the last step forever. `NONE`
 *    follows the same ladder as `TRANSIENT`, which is what the C implementation does.
 *
 * @param failedAttempts how many consecutive authentication failures have happened, starting at 1
 *        for the first failure. Values below 1 are treated as 1.
 * @return minutes to wait, or `null` when automatic retry must stop.
 */
fun dormRetryDelayMinutes(family: DormErrorFamily, failedAttempts: Int): Int? = when (family) {
    DormErrorFamily.FATAL -> null
    DormErrorFamily.SLOW -> DORM_SLOW_RETRY_MINUTES
    DormErrorFamily.TRANSIENT, DormErrorFamily.NONE -> {
        val step = failedAttempts.coerceAtLeast(1)
        DORM_AUTH_RETRY_BACKOFF_MINUTES[minOf(step, DORM_AUTH_RETRY_BACKOFF_MINUTES.size) - 1]
    }
}

/** Number of table entries, exposed for tests that assert the table was not silently truncated. */
internal val DORM_ERROR_CODE_COUNT: Int get() = DORM_ERROR_TABLE.size

/** Trims a header value and resolves it against the table; `null` when absent or unknown. */
private fun findDormError(rawCode: String?): DormError? {
    val code = rawCode?.trim()
    if (code.isNullOrEmpty()) return null
    return DORM_ERROR_TABLE[code]
}

/**
 * The §5.2 table, keyed by code. 48 entries: §5.2 is captioned "34 条" but the table it actually
 * lists — and `ErrorCode.c` — carry 48, so the concrete data wins over the caption. Windows-client
 * local self-check codes (3xx service / 500 component / 20010101-05 sharing / 1700xxxx reporting /
 * 991-993 network self-check) are out of scope, exactly as in the reference implementation.
 */
private val DORM_ERROR_TABLE: Map<String, DormError> = buildList {
    // Sub-Error: RADIUS / ACS side (130xxxxx / 1101xxxx).
    add(DormError("11010000", DormErrorFamily.TRANSIENT, "SDX information not found; confirm the campus network port is activated"))
    add(DormError("11064000", DormErrorFamily.SLOW, "Too many login attempts; try again later"))
    add(DormError("13001000", DormErrorFamily.FATAL, "Authentication rejected; check the account and password, and whether the account is in arrears"))
    add(DormError("13003000", DormErrorFamily.TRANSIENT, "Authentication server busy; try again later"))
    add(DormError("13004000", DormErrorFamily.TRANSIENT, "Login failed; the password may be wrong, the account may be in arrears, or the account state is abnormal"))
    add(DormError("13005000", DormErrorFamily.TRANSIENT, "Authentication request timed out; check the network and retry"))
    add(DormError("13012000", DormErrorFamily.FATAL, "Incorrect account or password"))
    add(DormError("13014000", DormErrorFamily.SLOW, "Too many active terminals; sign out the other terminals first"))
    add(DormError("13015000", DormErrorFamily.SLOW, "Insufficient account balance; top up and retry"))
    add(DormError("13016000", DormErrorFamily.FATAL, "The account does not exist; the account must not carry an @ suffix"))
    add(DormError("13017000", DormErrorFamily.SLOW, "Account in arrears; top up and retry"))
    add(DormError("13018000", DormErrorFamily.FATAL, "Wrong dial-in type; this account does not support portal authentication"))
    add(DormError("13019000", DormErrorFamily.FATAL, "Wrong terminal type; this account does not support dialling from this terminal type"))
    add(DormError("13020000", DormErrorFamily.SLOW, "The trial campaign has ended"))
    add(DormError("13021000", DormErrorFamily.SLOW, "The trial account has expired"))
    add(DormError("13022000", DormErrorFamily.SLOW, "Today's trial allowance is used up; try again tomorrow"))

    // Error-Code: portal authentication service.
    add(DormError("140001", DormErrorFamily.TRANSIENT, "The server considers this request invalid"))
    add(DormError("140002", DormErrorFamily.FATAL, "Incorrect client version; update the client"))
    add(DormError("140011", DormErrorFamily.FATAL, "The client MAC address the server received is invalid"))
    add(DormError("140013", DormErrorFamily.FATAL, "The algorithm id has expired; update the client"))
    add(DormError("140014", DormErrorFamily.TRANSIENT, "Login verification failed; try again later"))
    add(DormError("140015", DormErrorFamily.FATAL, "Wrong verification value; check the account configuration"))
    add(DormError("140100", DormErrorFamily.FATAL, "Incorrect account or password; enter them again"))
    add(DormError("140103", DormErrorFamily.TRANSIENT, "The gateway has no free access slots; try again later"))
    add(DormError("140104", DormErrorFamily.FATAL, "The corresponding user does not exist"))
    add(DormError("140105", DormErrorFamily.FATAL, "The user account is restricted; contact campus network maintenance"))
    add(DormError("140200", DormErrorFamily.TRANSIENT, "Key expired; authenticate again"))
    add(DormError("140300", DormErrorFamily.TRANSIENT, "Waiting for the terminal to start authentication"))
    add(DormError("140301", DormErrorFamily.TRANSIENT, "Waiting for the server to verify"))

    // Error-Code: network and connection.
    add(DormError("101", DormErrorFamily.TRANSIENT, "No network; check the local IP, gateway and DNS"))
    add(DormError("102", DormErrorFamily.TRANSIENT, "Network error; failed to obtain the login information"))
    add(DormError("103", DormErrorFamily.TRANSIENT, "Network error; failed to send the dial request"))
    add(DormError("104", DormErrorFamily.TRANSIENT, "Network error; failed to obtain the ticket"))
    add(DormError("105", DormErrorFamily.TRANSIENT, "Network error; the network state check failed"))
    add(DormError("106", DormErrorFamily.TRANSIENT, "Network error; failed to send the sign-out request"))
    add(DormError("107", DormErrorFamily.TRANSIENT, "Network error; check whether the network cable is unplugged or Wi-Fi is disconnected"))
    add(DormError("108", DormErrorFamily.TRANSIENT, "Network error; check whether the campus network is joined"))
    add(DormError("109", DormErrorFamily.TRANSIENT, "Failed to connect to the client server"))
    add(DormError("140000", DormErrorFamily.NONE, "The server recorded no error"))
    add(DormError("301", DormErrorFamily.TRANSIENT, "Failed to connect to the database server"))
    add(DormError("302", DormErrorFamily.TRANSIENT, "Failed to connect to the front-end server"))
    add(DormError("303", DormErrorFamily.TRANSIENT, "Failed to connect to the client server"))

    // Error-Code: account state.
    add(DormError("401", DormErrorFamily.SLOW, "The account is already signed in elsewhere; change the password promptly if this was not you"))
    add(DormError("403", DormErrorFamily.FATAL, "This client version has been disabled; update the client"))
    add(DormError("315", DormErrorFamily.SLOW, "Signed out by another terminal"))
    add(DormError("20010005", DormErrorFamily.FATAL, "The configuration file is corrupted; enter the account information again"))
    add(DormError("20010009", DormErrorFamily.SLOW, "The network is down, or the account is signed in elsewhere"))
    add(DormError("-600", DormErrorFamily.FATAL, "Network sharing detected; the account is blocked from signing in, turn sharing off"))
}.associateBy(DormError::code)
