package io.github.betterzslf.feature.campusnet

import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

private const val PHONE = "13800000000"
private const val PASSWORD = "fixture-operator-new"

private fun campusRows(vararg rows: JsonObject): JsonElement = buildJsonObject { put("operatorAccount", JsonArray(rows.toList())) }
private fun campusRow(id: String? = "fixture-target", account: String = "fixture-private-account") = buildJsonObject {
    if (id != null) put("id", id)
    put("operatorAccount", account)
    put("typeStatus", "1")
}

/** Memory-only fixture. This source never constructs a client or contacts a host. */
private class CampusFixtureSource : CampusNetworkSource {
    var current = true
    val calls = mutableListOf<String>()
    var accountResponse: ApiResult<JsonElement> = ApiResult.Success(campusRows(campusRow()))
    var phoneResponse: ApiResult<JsonElement> = ApiResult.Success(JsonNull)
    var writeResponse: ApiResult<JsonElement> = ApiResult.Success(JsonNull)
    var writeGate: CompletableDeferred<Unit>? = null
    var readBackGate: CompletableDeferred<Unit>? = null
    var onWrite: (() -> Unit)? = null
    var lastTarget: String? = null
    override fun isCurrentSession() = current
    override suspend fun accounts(): ApiResult<JsonElement> {
        calls += "accounts"
        if (calls.count { it == "accounts" } > 1) readBackGate?.await()
        return accountResponse
    }
    override suspend fun checkPhone(phone: String): ApiResult<JsonElement> { calls += "phone"; return phoneResponse }
    override suspend fun updatePassword(accountId: String, password: String): ApiResult<JsonElement> {
        calls += "write"
        lastTarget = accountId
        writeGate?.await()
        onWrite?.invoke()
        return writeResponse
    }
}

class CampusNetworkControllerTest {
    private suspend fun ready(source: CampusFixtureSource = CampusFixtureSource()): CampusNetworkController {
        val controller = CampusNetworkController(source)
        assertTrue(controller.refresh())
        controller.setExpanded(true)
        controller.selectAccount(controller.state.value.accounts.first().key)
        assertTrue(controller.checkPhone(PHONE))
        return controller
    }

    @Test fun loadingAndExpandingNeverChangeAPasswordAndPhoneCheckIsRequired() = runTest {
        val source = CampusFixtureSource()
        val controller = CampusNetworkController(source)
        assertTrue(controller.refresh())
        assertFalse(controller.state.value.expanded)
        assertFalse(controller.prepare(PHONE, PASSWORD, PASSWORD))
        controller.setExpanded(true)
        controller.selectAccount(controller.state.value.accounts.first().key)
        assertFalse(controller.prepare(PHONE, PASSWORD, PASSWORD))
        assertEquals(listOf("accounts"), source.calls)
        assertNull(controller.state.value.preview)
    }

    @Test fun malformedOrRefusedPhoneCheckNeverOpensPasswordSubmission() = runTest {
        val source = CampusFixtureSource()
        val controller = CampusNetworkController(source)
        controller.refresh(); controller.setExpanded(true)
        controller.selectAccount(controller.state.value.accounts.first().key)
        assertFalse(controller.checkPhone("bad"))
        assertEquals(listOf("accounts"), source.calls)
        source.phoneResponse = ApiResult.Failure(ApiError.Business(400, "fixture rejection"))
        assertFalse(controller.checkPhone(PHONE))
        assertFalse(controller.state.value.phoneChecked)
        assertFalse(controller.prepare(PHONE, PASSWORD, PASSWORD))
        assertEquals(listOf("accounts", "phone"), source.calls)
    }

    @Test fun missingOrAmbiguousTargetsRemainReadOnlyAndNeverGuessAliases() = runTest {
        val source = CampusFixtureSource().apply { accountResponse = ApiResult.Success(campusRows(
            campusRow(null), campusRow("duplicate", "first"), campusRow("duplicate", "second"),
            buildJsonObject { put("operatorAccountId", "not-a-proven-response-field"); put("operatorAccount", "alias-only") }
        )) }
        val controller = CampusNetworkController(source)
        assertTrue(controller.refresh())
        assertEquals(4, controller.state.value.accounts.size)
        assertTrue(controller.state.value.accounts.none { it.canChangePassword })
        controller.setExpanded(true)
        controller.selectAccount(controller.state.value.accounts.first().key)
        assertFalse(controller.checkPhone(PHONE))
        assertFalse(controller.prepare(PHONE, PASSWORD, PASSWORD))
        assertEquals(listOf("accounts"), source.calls)
    }

    @Test fun passwordValidationIsLocalAndDoesNotInventSchoolPasswordRules() = runTest {
        val source = CampusFixtureSource()
        val controller = ready(source)
        assertFalse(controller.prepare(PHONE, "", ""))
        assertFalse(controller.prepare(PHONE, "new", "different"))
        assertFalse(controller.prepare(PHONE, "new\n", "new\n"))
        assertEquals(listOf("accounts", "phone"), source.calls)
        // Operator complexity is undocumented: equality/nonblank/control characters only.
        assertNull(campusPasswordValidation("x", "x"))
        assertTrue(controller.prepare(PHONE, PASSWORD, PASSWORD))
        assertEquals(CampusPasswordPhase.PREVIEW, controller.state.value.phase)
        assertEquals(listOf("accounts", "phone"), source.calls)
    }

    @Test fun finalAcknowledgementAndCurrentReceiptAreBothRequired() = runTest {
        val source = CampusFixtureSource()
        val controller = ready(source)
        assertTrue(controller.prepare(PHONE, PASSWORD, PASSWORD))
        val old = assertNotNull(controller.state.value.preview).id
        assertFalse(controller.confirm(old, false))
        controller.editPreview()
        assertTrue(controller.prepare(PHONE, PASSWORD, PASSWORD))
        val latest = assertNotNull(controller.state.value.preview).id
        assertNotEquals(old, latest)
        assertFalse(controller.confirm(old, true))
        assertTrue(controller.confirm(latest, true))
        assertEquals("fixture-target", source.lastTarget)
        assertEquals(listOf("accounts", "phone", "write", "accounts"), source.calls)
        assertEquals(CampusPasswordPhase.COMPLETED, controller.state.value.phase)
        assertFalse(controller.confirm(latest, true))
    }

    @Test fun doubleTapAndRefreshDuringSubmissionSendOnlyOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = CampusFixtureSource().apply { writeGate = gate }
        val controller = ready(source)
        controller.prepare(PHONE, PASSWORD, PASSWORD)
        val id = assertNotNull(controller.state.value.preview).id
        val first = launch { controller.confirm(id, true) }
        runCurrent()
        assertEquals(CampusPasswordPhase.SUBMITTING, controller.state.value.phase)
        assertFalse(controller.confirm(id, true))
        assertFalse(controller.refresh())
        gate.complete(Unit); first.join()
        assertEquals(1, source.calls.count { it == "write" })
        assertEquals(2, source.calls.count { it == "accounts" })
    }

    @Test fun explicitBusinessFailureIsTerminalAndDoesNotTriggerReadBackOrRetry() = runTest {
        val source = CampusFixtureSource().apply { writeResponse = ApiResult.Failure(ApiError.Business(400, "fixture rejected")) }
        val controller = ready(source)
        controller.prepare(PHONE, PASSWORD, PASSWORD)
        val id = assertNotNull(controller.state.value.preview).id
        assertFalse(controller.confirm(id, true))
        assertEquals(CampusPasswordPhase.FAILED, controller.state.value.phase)
        controller.setExpanded(false); controller.setExpanded(true)
        assertFalse(controller.prepare(PHONE, PASSWORD, PASSWORD))
        assertFalse(controller.confirm(id, true))
        assertEquals(listOf("accounts", "phone", "write"), source.calls)
    }

    @Test fun timeoutNetworkHttpAndUnknownBodiesStopWithoutAutomaticRetry() = runTest {
        for (error in listOf(ApiError.Timeout(), ApiError.Network(), ApiError.Http(503), ApiError.Decode())) {
            val source = CampusFixtureSource().apply { writeResponse = ApiResult.Failure(error) }
            val controller = ready(source)
            controller.prepare(PHONE, PASSWORD, PASSWORD)
            val id = assertNotNull(controller.state.value.preview).id
            assertFalse(controller.confirm(id, true))
            assertEquals(CampusPasswordPhase.UNCERTAIN, controller.state.value.phase)
            assertFalse(controller.confirm(id, true))
            assertTrue(controller.refresh()) // Explicit query is allowed, but never resets this write attempt.
            assertEquals(CampusPasswordPhase.UNCERTAIN, controller.state.value.phase)
            assertFalse(controller.prepare(PHONE, PASSWORD, PASSWORD))
            assertEquals(1, source.calls.count { it == "write" })
        }
    }

    @Test fun cancelledInFlightWriteRetainsUncertainState() = runTest {
        val source = CampusFixtureSource().apply { writeGate = CompletableDeferred() }
        val controller = ready(source)
        controller.prepare(PHONE, PASSWORD, PASSWORD)
        val id = assertNotNull(controller.state.value.preview).id
        val job = launch { controller.confirm(id, true) }
        runCurrent(); job.cancelAndJoin()
        assertEquals(CampusPasswordPhase.UNCERTAIN, controller.state.value.phase)
        assertFalse(controller.confirm(id, true))
        assertEquals(1, source.calls.count { it == "write" })
        assertEquals(1, source.calls.count { it == "accounts" })
    }

    @Test fun cancellingOnlyReadBackKeepsTheAlreadyReceivedWriteAcknowledgement() = runTest {
        val source = CampusFixtureSource().apply { readBackGate = CompletableDeferred() }
        val controller = ready(source)
        controller.prepare(PHONE, PASSWORD, PASSWORD)
        val id = assertNotNull(controller.state.value.preview).id
        val job = launch { controller.confirm(id, true) }
        runCurrent()
        assertEquals(CampusPasswordPhase.COMPLETED, controller.state.value.phase)
        assertTrue(controller.state.value.loading)
        job.cancelAndJoin()
        assertEquals(CampusPasswordPhase.COMPLETED, controller.state.value.phase)
        assertNotNull(controller.state.value.queryMessage)
        assertFalse(controller.confirm(id, true))
        assertEquals(1, source.calls.count { it == "write" })
    }

    @Test fun successfulPhoneCheckIsReusedWithoutSendingAnotherSmsRequest() = runTest {
        val source = CampusFixtureSource()
        val controller = ready(source)
        assertTrue(controller.checkPhone(PHONE))
        assertEquals(1, source.calls.count { it == "phone" })
        controller.forgetPhoneCheck()
        assertFalse(controller.state.value.phoneChecked)
        assertFalse(controller.prepare(PHONE, PASSWORD, PASSWORD))
    }

    @Test fun successfulAcknowledgementAndFailedReadBackAreNotConflated() = runTest {
        val source = CampusFixtureSource().apply { onWrite = { accountResponse = ApiResult.Failure(ApiError.Timeout()) } }
        val controller = ready(source)
        controller.prepare(PHONE, PASSWORD, PASSWORD)
        assertTrue(controller.confirm(assertNotNull(controller.state.value.preview).id, true))
        assertEquals(CampusPasswordPhase.COMPLETED, controller.state.value.phase)
        assertNotNull(controller.state.value.queryMessage)
        assertEquals("服务已返回改密成功，但暂未查到最新账号。可以刷新查询，不要重复修改密码。", controller.state.value.formMessage)
        assertEquals(1, source.calls.count { it == "write" })
    }

    @Test fun failedRefreshKeepsVisibleRowsButRevokesTheirWriteTargets() = runTest {
        val source = CampusFixtureSource()
        val controller = ready(source)
        val previous = controller.state.value.accounts
        source.accountResponse = ApiResult.Failure(ApiError.Network())
        assertFalse(controller.refresh())
        assertEquals(previous, controller.state.value.accounts)
        assertNull(controller.state.value.selectedAccountKey)
        assertFalse(controller.state.value.phoneChecked)
        controller.selectAccount(previous.first().key)
        assertFalse(controller.checkPhone(PHONE))
        assertEquals(1, source.calls.count { it == "phone" })
    }

    @Test fun changingSelectionOrPhoneInvalidatesTheCheckAndPreview() = runTest {
        val source = CampusFixtureSource().apply { accountResponse = ApiResult.Success(campusRows(campusRow(), campusRow("second", "second-account"))) }
        val controller = ready(source)
        controller.prepare(PHONE, PASSWORD, PASSWORD)
        val old = assertNotNull(controller.state.value.preview).id
        controller.selectAccount(controller.state.value.accounts.last().key)
        assertFalse(controller.state.value.phoneChecked)
        assertNull(controller.state.value.preview)
        assertFalse(controller.confirm(old, true))
        assertTrue(controller.checkPhone(PHONE))
        controller.forgetPhoneCheck()
        assertFalse(controller.prepare(PHONE, PASSWORD, PASSWORD))
        assertEquals(0, source.calls.count { it == "write" })
    }

    @Test fun closingOrChangingSessionClearsRowsAndSensitivePreviewWithoutNewCalls() = runTest {
        val source = CampusFixtureSource()
        val controller = ready(source)
        controller.prepare(PHONE, PASSWORD, PASSWORD)
        val id = assertNotNull(controller.state.value.preview).id
        source.current = false
        assertFalse(controller.confirm(id, true))
        assertEquals(CampusPasswordPhase.SESSION_CHANGED, controller.state.value.phase)
        assertTrue(controller.state.value.accounts.isEmpty())
        assertNull(controller.state.value.preview)
        assertFalse(controller.state.value.phoneChecked)
        controller.close()
        assertFalse(controller.refresh())
        assertEquals(listOf("accounts", "phone"), source.calls)
    }

    @Test fun stateAndPreviewDebugRepresentationsNeverContainPrivateInputsOrTargetIds() = runTest {
        val controller = ready()
        controller.prepare(PHONE, PASSWORD, PASSWORD)
        val debug = controller.toString() + controller.state.value.toString()
        for (secret in listOf(PHONE, PASSWORD, "fixture-target", "fixture-private-account")) assertFalse(debug.contains(secret))
    }

    @Test fun strictListShapeDistinguishesUnknownFromEmptyAndKeepsAllRows() {
        assertIs<ApiResult.Failure>(parseCampusAccounts(buildJsonObject { put("list", JsonArray(emptyList())) }, 1))
        assertIs<ApiResult.Failure>(parseCampusAccounts(buildJsonObject { put("operatorAccount", JsonArray(listOf(JsonPrimitive("bad")))) }, 1))
        val empty = assertIs<ApiResult.Success<CampusAccountsSnapshot>>(parseCampusAccounts(campusRows(), 1)).data
        assertTrue(empty.rows.isEmpty())
        val many = (0 until 1489).map { campusRow("fixture-$it", "account-$it") }.toTypedArray()
        val all = assertIs<ApiResult.Success<CampusAccountsSnapshot>>(parseCampusAccounts(campusRows(*many), 2)).data
        assertEquals(1489, all.rows.size)
        assertEquals(1489, all.targets.size)
    }
}
