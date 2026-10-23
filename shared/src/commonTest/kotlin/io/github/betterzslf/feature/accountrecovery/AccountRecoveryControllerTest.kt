package io.github.betterzslf.feature.accountrecovery

import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.data.PasswordResetLogout
import io.github.betterzslf.data.PasswordResetReceipt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

private const val PHONE = "13800000000"
private const val CODE = "000000"
private const val PASSWORD = "Fixture#1"
private fun identity(id: JsonElement = JsonPrimitive("verified-fixture-target")): JsonElement =
    buildJsonObject { put("user", buildJsonObject { put("id", id) }) }

/** In-memory source with no HTTP client, secrets store, or process/hardware access. */
private class RecoveryFixtureSource : AccountRecoverySource {
    var current = true
    var loggedOut = false
    val calls = mutableListOf<String>()
    var sendResult: ApiResult<JsonElement> = ApiResult.Success(JsonNull)
    var verifyResult: ApiResult<JsonElement> = ApiResult.Success(identity())
    var resetResult: PasswordResetReceipt = PasswordResetReceipt.Accepted(PasswordResetLogout.CLEARED)
    var sendGate: CompletableDeferred<Unit>? = null
    var verifyGate: CompletableDeferred<Unit>? = null
    var resetGate: CompletableDeferred<Unit>? = null
    var resetId: String? = null
    override fun isCurrentSession() = current
    override fun isLoggedOut() = loggedOut
    override suspend fun sendCode(phone: String): ApiResult<JsonElement> { calls += "send"; sendGate?.await(); return sendResult }
    override suspend fun verify(phone: String, code: String): ApiResult<JsonElement> {
        calls += "verify"; verifyGate?.await(); return verifyResult
    }
    override suspend fun reset(userId: String, password: String): PasswordResetReceipt {
        calls += "reset"; resetId = userId; resetGate?.await()
        val accepted = resetResult as? PasswordResetReceipt.Accepted
        if (accepted != null) { current = false; loggedOut = accepted.logout != PasswordResetLogout.NEW_SESSION_PRESERVED }
        return resetResult
    }
}

class AccountRecoveryControllerTest {
    private suspend fun ready(source: RecoveryFixtureSource, onAccepted: (PasswordResetLogout) -> Unit = {}): AccountRecoveryController {
        val controller = AccountRecoveryController(source, nowMillis = { 1_000L }, onAccepted = onAccepted)
        assertTrue(controller.sendCode(PHONE, true))
        assertTrue(controller.verify(PHONE, CODE))
        return controller
    }
    private fun preview(controller: AccountRecoveryController): Long {
        assertTrue(controller.prepare(PHONE, CODE, PASSWORD, PASSWORD))
        return assertNotNull(controller.state.value.preview).id
    }

    @Test fun guestHasAnHonestRequiredSessionStateAndEveryActionStaysDisabled() = runTest {
        val controller = AccountRecoveryController(null)
        assertEquals(AccountRecoveryPhase.GUEST, controller.state.value.phase)
        assertTrue(controller.state.value.message.orEmpty().contains("匿名"))
        assertFalse(controller.sendCode(PHONE, true))
        assertFalse(controller.verify(PHONE, CODE))
        assertFalse(controller.prepare(PHONE, CODE, PASSWORD, PASSWORD))
        assertFalse(controller.confirm(1, true))
    }

    @Test fun smsNeedsItsOwnConfirmationAndValidPhoneAndNeverRunsOnConstruction() = runTest {
        val source = RecoveryFixtureSource()
        val controller = AccountRecoveryController(source)
        assertTrue(source.calls.isEmpty())
        assertFalse(controller.sendCode(PHONE, false))
        assertFalse(controller.sendCode("bad", true))
        assertTrue(source.calls.isEmpty())
        assertTrue(controller.sendCode(PHONE, true))
        assertEquals(listOf("send"), source.calls)
        assertEquals(60, controller.state.value.cooldownSeconds)
        assertTrue(controller.state.value.message.orEmpty().contains("不保证送达"))
    }

    @Test fun cooldownIsLocalAndExpiryNeverSendsAnotherSms() = runTest {
        var now = 1_000L
        val source = RecoveryFixtureSource()
        val controller = AccountRecoveryController(source, nowMillis = { now })
        controller.sendCode(PHONE, true)
        assertFalse(controller.sendCode(PHONE, true))
        now += 59_000; controller.tickCooldown()
        assertEquals(1, controller.state.value.cooldownSeconds)
        now += 1_000; controller.tickCooldown()
        assertEquals(0, controller.state.value.cooldownSeconds)
        assertEquals(listOf("send"), source.calls)
        assertTrue(controller.sendCode(PHONE, true)) // Explicit action only.
        assertEquals(2, source.calls.size)
    }

    @Test fun acknowledgedSmsForEditedInputsKeepsCooldownButDoesNotVerifyTheNewPhone() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = RecoveryFixtureSource().apply { sendGate = gate }
        val controller = AccountRecoveryController(source)
        val job = launch { assertFalse(controller.sendCode(PHONE, true)) }
        runCurrent(); controller.phoneChanged(); gate.complete(Unit); job.join()
        assertEquals(60, controller.state.value.cooldownSeconds)
        assertFalse(controller.state.value.codeRequested)
        assertFalse(controller.verify("13900000000", CODE))
        assertEquals(listOf("send"), source.calls)
    }

    @Test fun rejectedAndUnknownSmsNeverStartASuccessCountdownOrAutomaticallyRetry() = runTest {
        for (error in listOf(ApiError.Business(400, "fixture rejected"), ApiError.Timeout(), ApiError.Decode())) {
            val source = RecoveryFixtureSource().apply { sendResult = ApiResult.Failure(error) }
            val controller = AccountRecoveryController(source)
            assertFalse(controller.sendCode(PHONE, true))
            assertEquals(0, controller.state.value.cooldownSeconds)
            assertFalse(controller.state.value.codeRequested)
            assertFalse(controller.verify(PHONE, CODE))
            assertFalse(controller.prepare(PHONE, CODE, PASSWORD, PASSWORD))
            if (error !is ApiError.Business) {
                assertEquals(AccountRecoveryPhase.UNCERTAIN, controller.state.value.phase)
                assertFalse(controller.sendCode(PHONE, true))
            }
            assertEquals(listOf("send"), source.calls)
        }
    }

    @Test fun unverifiedOrUnrequestedPhoneNeverProducesATargetOrReset() = runTest {
        val source = RecoveryFixtureSource()
        val controller = AccountRecoveryController(source)
        assertFalse(controller.verify(PHONE, CODE))
        assertFalse(controller.prepare(PHONE, CODE, PASSWORD, PASSWORD))
        assertFalse(controller.confirm(1, true))
        assertTrue(source.calls.isEmpty())
        assertTrue(controller.sendCode(PHONE, true))
        assertFalse(controller.prepare(PHONE, CODE, PASSWORD, PASSWORD))
        assertEquals(listOf("send"), source.calls)
    }

    @Test fun onlyRealUserIdIsAcceptedAndNoOwnerPhoneOrAliasFallbackExists() = runTest {
        val bad = listOf(JsonNull, JsonPrimitive(""), JsonPrimitive(" "), JsonPrimitive(true),
            buildJsonObject { put("id", "nested") }, JsonArray(emptyList()))
        for (id in bad) {
            val source = RecoveryFixtureSource().apply { verifyResult = ApiResult.Success(identity(id)) }
            val controller = AccountRecoveryController(source)
            controller.sendCode(PHONE, true)
            assertFalse(controller.verify(PHONE, CODE))
            assertFalse(controller.state.value.verified)
            assertFalse(controller.prepare(PHONE, CODE, PASSWORD, PASSWORD))
            assertEquals(0, source.calls.count { it == "reset" })
        }
        assertNull(recoveryVerifiedId(buildJsonObject { put("userId", "alias-id"); put("id", "another-alias") }))
        assertNull(recoveryVerifiedId(buildJsonObject { put("user", JsonPrimitive("wrong-container")) }))
    }

    @Test fun numericTargetIsPreservedAndNotReplacedByCurrentOwnerOrPhone() = runTest {
        val source = RecoveryFixtureSource().apply { verifyResult = ApiResult.Success(identity(JsonPrimitive(17))) }
        val receipts = mutableListOf<PasswordResetLogout>()
        val controller = ready(source) { receipts += it }
        assertTrue(controller.confirm(preview(controller), true))
        assertEquals("17", source.resetId)
        assertEquals(listOf("send", "verify", "reset"), source.calls)
        assertEquals(AccountRecoveryPhase.COMPLETED, controller.state.value.phase)
        assertEquals(listOf(PasswordResetLogout.CLEARED), receipts)
    }

    @Test fun codeAndPhoneEditingInvalidateVerifiedIdentityAndAnyPreview() = runTest {
        val source = RecoveryFixtureSource()
        val controller = ready(source)
        val old = preview(controller)
        controller.codeChanged()
        assertFalse(controller.state.value.verified)
        assertNull(controller.state.value.preview)
        assertFalse(controller.confirm(old, true))
        assertTrue(controller.state.value.codeRequested)
        assertTrue(controller.verify(PHONE, "111111"))
        assertFalse(controller.prepare(PHONE, CODE, PASSWORD, PASSWORD))
        controller.phoneChanged()
        assertFalse(controller.state.value.codeRequested)
        assertFalse(controller.state.value.verified)
        assertFalse(controller.verify("13900000000", CODE))
        assertEquals(0, source.calls.count { it == "reset" })
    }

    @Test fun lateVerificationAfterCodeEditIsIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = RecoveryFixtureSource().apply { verifyGate = gate }
        val controller = AccountRecoveryController(source)
        controller.sendCode(PHONE, true)
        val job = launch { assertFalse(controller.verify(PHONE, CODE)) }
        runCurrent(); controller.codeChanged(); gate.complete(Unit); job.join()
        assertFalse(controller.state.value.verified)
        assertFalse(controller.state.value.verifying)
        assertFalse(controller.prepare(PHONE, CODE, PASSWORD, PASSWORD))
    }

    @Test fun changedSessionBeforeFinalConfirmationSendsZeroResetRequests() = runTest {
        val source = RecoveryFixtureSource()
        val controller = ready(source)
        val id = preview(controller)
        source.current = false
        assertFalse(controller.confirm(id, true))
        assertEquals(AccountRecoveryPhase.SESSION_CHANGED, controller.state.value.phase)
        assertNull(controller.state.value.preview)
        assertFalse(controller.state.value.verified)
        assertEquals(0, source.calls.count { it == "reset" })
    }

    @Test fun validFinalAcknowledgementAndLatestPreviewAreBothRequired() = runTest {
        val source = RecoveryFixtureSource()
        val controller = ready(source)
        val old = preview(controller)
        assertFalse(controller.confirm(old, false))
        controller.editPreview()
        val latest = preview(controller)
        assertNotEquals(old, latest)
        assertFalse(controller.confirm(old, true))
        assertTrue(controller.confirm(latest, true))
        assertEquals("verified-fixture-target", source.resetId)
        assertEquals(1, source.calls.count { it == "reset" })
    }

    @Test fun existingSchoolPasswordPolicyAndMatchingConfirmationAreRequired() = runTest {
        val source = RecoveryFixtureSource()
        val controller = ready(source)
        for (password in listOf("short", "onlyletters", "123456789!", "Letters123", "Ab1! abcdef"))
            assertFalse(controller.prepare(PHONE, CODE, password, password))
        assertFalse(controller.prepare(PHONE, CODE, PASSWORD, "Other#123"))
        assertNull(recoveryPasswordValidation(PASSWORD, PASSWORD))
        assertEquals(0, source.calls.count { it == "reset" })
    }

    @Test fun doubleClickAndOtherOperationsDuringResetStaySingleFlight() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = RecoveryFixtureSource().apply { resetGate = gate }
        val controller = ready(source)
        val id = preview(controller)
        val job = launch { controller.confirm(id, true) }
        runCurrent()
        assertEquals(AccountRecoveryPhase.RESETTING, controller.state.value.phase)
        assertFalse(controller.confirm(id, true))
        assertFalse(controller.sendCode(PHONE, true))
        assertFalse(controller.verify(PHONE, CODE))
        gate.complete(Unit); job.join()
        assertEquals(1, source.calls.count { it == "reset" })
    }

    @Test fun rejectedTimeoutAndUnknownResetResultsStopWithoutAutomaticRetry() = runTest {
        for (error in listOf(ApiError.Business(400, "fixture rejected"), ApiError.Timeout(), ApiError.Network(), ApiError.Http(503), ApiError.Decode())) {
            val source = RecoveryFixtureSource().apply { resetResult = PasswordResetReceipt.Failed(error) }
            val controller = ready(source)
            val id = preview(controller)
            assertFalse(controller.confirm(id, true))
            assertEquals(if (error is ApiError.Business) AccountRecoveryPhase.FAILED else AccountRecoveryPhase.UNCERTAIN, controller.state.value.phase)
            assertFalse(controller.confirm(id, true))
            assertFalse(controller.prepare(PHONE, CODE, PASSWORD, PASSWORD))
            assertFalse(controller.sendCode(PHONE, true))
            assertEquals(1, source.calls.count { it == "reset" })
        }
    }

    @Test fun cancelledResetRemainsUncertainAndIsNeverRetried() = runTest {
        val source = RecoveryFixtureSource().apply { resetGate = CompletableDeferred() }
        val controller = ready(source)
        val id = preview(controller)
        val job = launch { controller.confirm(id, true) }
        runCurrent(); job.cancelAndJoin()
        assertEquals(AccountRecoveryPhase.UNCERTAIN, controller.state.value.phase)
        assertFalse(controller.confirm(id, true))
        assertEquals(1, source.calls.count { it == "reset" })
    }

    @Test fun localCleanupFailureIsAnAcknowledgedSuccessNotAResetRetry() = runTest {
        val source = RecoveryFixtureSource().apply { resetResult = PasswordResetReceipt.Accepted(PasswordResetLogout.CLEAR_FAILED) }
        val receipts = mutableListOf<PasswordResetLogout>()
        val controller = ready(source) { receipts += it }
        val id = preview(controller)
        assertTrue(controller.confirm(id, true))
        assertEquals(AccountRecoveryPhase.CLEANUP_WARNING, controller.state.value.phase)
        assertEquals(listOf(PasswordResetLogout.CLEAR_FAILED), receipts)
        assertFalse(controller.confirm(id, true))
        assertEquals(1, source.calls.count { it == "reset" })
    }

    @Test fun lateAckNeverLogsOutOrReportsTheOldReceiptInANewerSession() = runTest {
        val source = RecoveryFixtureSource().apply { resetResult = PasswordResetReceipt.Accepted(PasswordResetLogout.NEW_SESSION_PRESERVED) }
        val receipts = mutableListOf<PasswordResetLogout>()
        val controller = ready(source) { receipts += it }
        assertFalse(controller.confirm(preview(controller), true))
        assertEquals(AccountRecoveryPhase.SESSION_CHANGED, controller.state.value.phase)
        assertTrue(receipts.isEmpty())
        assertFalse(source.loggedOut)
    }

    @Test fun closeDropsLateVerificationAndAllPresentedIdentity() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = RecoveryFixtureSource().apply { verifyGate = gate }
        val controller = AccountRecoveryController(source)
        controller.sendCode(PHONE, true)
        val job = launch { assertFalse(controller.verify(PHONE, CODE)) }
        runCurrent(); controller.close(); gate.complete(Unit); job.join()
        assertEquals(AccountRecoveryPhase.CLOSED, controller.state.value.phase)
        assertFalse(controller.state.value.verified)
        assertNull(controller.state.value.maskedPhone)
        assertNull(controller.state.value.preview)
        assertEquals(0, controller.state.value.cooldownSeconds)
        assertFalse(controller.prepare(PHONE, CODE, PASSWORD, PASSWORD))
    }

    @Test fun debugStateContainsNoPhoneCodeTargetIdOrPassword() = runTest {
        val controller = ready(RecoveryFixtureSource())
        preview(controller)
        val text = controller.toString() + controller.state.value.toString()
        for (secret in listOf(PHONE, CODE, PASSWORD, "verified-fixture-target")) assertFalse(text.contains(secret))
    }
}
