package io.github.betterzslf.ui.campusnet

import io.github.betterzslf.core.net.ApiTestFixture
import io.github.betterzslf.core.net.fixtureSession
import io.github.betterzslf.feature.campusnet.CampusNetworkController
import io.github.betterzslf.feature.campusnet.CampusPasswordPhase
import io.github.betterzslf.feature.campusnet.RepositoryCampusNetworkSource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The private binder gate is exercised against the real typed factory/controller authority. */
class CampusPasswordConfirmationGateTest {
    private class Fixture {
        var writes = 0
        val api = ApiTestFixture(MockEngine { request ->
            when (request.url.encodedPath.substringAfterLast('/')) {
                "getOperatorAccountByIDCard" -> respond("""{"code":200,"data":{"operatorAccount":[{"id":"fixture-target","operatorAccount":"13800000000@carrier","typeStatus":"1"}]}}""")
                "checkPhone" -> respond("""{"code":200,"data":{}}""")
                "updateOperatorPasswordById" -> { writes++; respond("""{"code":200,"data":{}}""") }
                else -> error("Unexpected offline fixture endpoint")
            }
        })
        val source = assertNotNull(RepositoryCampusNetworkSource.capture(api.repositories.campusNetwork))
        val controller = CampusNetworkController(source)
        private val epoch = api.sessions.revision.value
        var mounted = true
        val gate = CampusPasswordConfirmationGate(
            { mounted && api.sessions.revision.value == epoch && source.isCurrentSession() },
            controller::captureConfirmationId,
        )

        suspend fun prepare(): Long {
            assertTrue(controller.refresh())
            controller.setExpanded(true)
            controller.selectAccount(controller.state.value.accounts.single().key)
            assertTrue(controller.checkPhone("13800000000"))
            assertTrue(controller.prepare("13800000000", "fixture-new-password", "fixture-new-password"))
            return assertNotNull(controller.captureConfirmationId())
        }

        fun open(): Boolean = gate.open(assertNotNull(controller.state.value.preview).maskedAccount)
        fun close() { mounted = false; gate.retire(); controller.close(); api.close() }
    }

    @Test fun acknowledgedOpenedVersionIsConsumedOnlyOnceAndWritesExactlyOnce() = runTest {
        val f = Fixture()
        try {
            val original = f.prepare()
            assertTrue(f.open())
            assertFalse(f.gate.acknowledged)
            assertNull(f.gate.consume())
            assertTrue(f.gate.isOpen)
            f.gate.toggleAcknowledgement()
            val consumed = assertNotNull(f.gate.consume())
            assertEquals(original, consumed)
            assertFalse(f.gate.isOpen)
            assertFalse(f.gate.acknowledged)
            assertEquals("", f.gate.maskedAccount)
            assertNull(f.gate.consume())
            assertTrue(f.controller.confirm(consumed, true))
            assertFalse(f.controller.confirm(consumed, true))
            assertEquals(1, f.writes)
        } finally { f.close() }
    }

    @Test fun equalLookingReplacementPreviewNeverSubstitutesItsNewNonceForTheOpenedVersion() = runTest {
        val f = Fixture()
        try {
            val original = f.prepare()
            val displayed = assertNotNull(f.controller.state.value.preview)
            assertTrue(f.open())
            f.gate.toggleAcknowledgement()
            f.controller.editPreview()
            assertTrue(f.controller.prepare("13800000000", "fixture-other-password", "fixture-other-password"))
            val replacement = assertNotNull(f.controller.captureConfirmationId())
            assertTrue(original != replacement)
            assertEquals(displayed, f.controller.state.value.preview) // Same masked target is not the same authority.
            assertFalse(displayed === f.controller.state.value.preview)
            assertNull(f.gate.consume())
            assertFalse(f.gate.isOpen)
            assertFalse(f.gate.acknowledged)
            assertEquals(0, f.writes)
            assertTrue(f.open())
            assertFalse(f.gate.acknowledged)
            f.gate.toggleAcknowledgement()
            assertEquals(replacement, f.gate.consume())
        } finally { f.close() }
    }

    @Test fun dismissReopenAndTerminalTransitionsNeverRetainAcknowledgement() = runTest {
        val f = Fixture()
        try {
            f.prepare(); assertTrue(f.open()); f.gate.toggleAcknowledgement()
            f.gate.dismiss()
            assertFalse(f.gate.isOpen); assertFalse(f.gate.acknowledged)
            assertTrue(f.open()); assertFalse(f.gate.acknowledged)
            f.gate.toggleAcknowledgement()
            f.controller.editPreview()
            f.gate.reconcile()
            assertFalse(f.gate.isOpen); assertFalse(f.gate.acknowledged)
            assertNull(f.gate.consume())
            assertFalse(f.gate.open("masked-only"))
            assertEquals(0, f.writes)
        } finally { f.close() }
    }

    @Test fun sameValueSaveOrClearSaveAbaBeforeObserverTurnRevokesTheOpenedGate() = runTest {
        for (aba in listOf(false, true)) {
            val f = Fixture()
            try {
                val id = f.prepare(); assertTrue(f.open()); f.gate.toggleAcknowledgement()
                val original = assertNotNull(f.api.sessions.session.value)
                if (aba) f.api.sessions.clear()
                f.api.sessions.save(original.copy())
                // No UI collector/disposal needs to run for fixed core authority to reject the action.
                assertFalse(f.source.isCurrentSession())
                assertNull(f.gate.consume())
                assertFalse(f.gate.isOpen); assertFalse(f.gate.acknowledged)
                assertFalse(f.controller.confirm(id, true))
                assertEquals(CampusPasswordPhase.SESSION_CHANGED, f.controller.state.value.phase)
                assertEquals(0, f.writes)
            } finally { f.close() }
        }
    }

    @Test fun sameNumericIdInANewControllerCannotReviveTheRetiredControllersGate() = runTest {
        val f = Fixture()
        try {
            val oldId = f.prepare(); assertTrue(f.open()); f.gate.toggleAcknowledgement()
            f.api.sessions.save(fixtureSession())
            val newSource = assertNotNull(RepositoryCampusNetworkSource.capture(f.api.repositories.campusNetwork))
            val replacement = CampusNetworkController(newSource)
            try {
                assertTrue(replacement.refresh()); replacement.setExpanded(true)
                replacement.selectAccount(replacement.state.value.accounts.single().key)
                assertTrue(replacement.checkPhone("13800000000"))
                assertTrue(replacement.prepare("13800000000", "new-controller-password", "new-controller-password"))
                assertEquals(oldId, replacement.captureConfirmationId()) // Nonces are controller-local, not global identity.
                assertNull(f.gate.consume())
                assertFalse(f.controller.confirm(oldId, true))
                assertEquals(0, f.writes)
                assertTrue(newSource.isCurrentSession())
            } finally { replacement.close() }
        } finally { f.close() }
    }

    @Test fun sourceRetiredAfterGateConsumptionStillPreventsTheLaunchedControllerWrite() = runTest {
        val f = Fixture()
        try {
            f.prepare(); assertTrue(f.open()); f.gate.toggleAcknowledgement()
            val fixedId = assertNotNull(f.gate.consume())
            f.api.sessions.save(assertNotNull(f.api.sessions.session.value).copy())
            assertFalse(f.controller.confirm(fixedId, true))
            assertEquals(0, f.writes)
        } finally { f.close() }
    }

    @Test fun disposalAndClosedControllerPreventLateUiIntentsWithoutAnyRequest() = runTest {
        for (closeController in listOf(false, true)) {
            val f = Fixture()
            try {
                f.prepare(); assertTrue(f.open()); f.gate.toggleAcknowledgement()
                if (closeController) f.controller.close() else { f.mounted = false; f.gate.retire() }
                assertNull(f.gate.consume())
                assertFalse(f.gate.open("masked-only"))
                f.gate.toggleAcknowledgement()
                assertFalse(f.gate.acknowledged)
                assertEquals(0, f.writes)
            } finally { f.close() }
        }
    }

    @Test fun gateDiagnosticsNeverExposeNonceOrMaskedTarget() {
        val gate = CampusPasswordConfirmationGate({ true }, { 123456789L })
        assertTrue(gate.open("fixture-masked-target"))
        assertEquals("CampusPasswordConfirmationGate(redacted)", gate.toString())
        assertFalse(gate.toString().contains("123456789"))
        assertFalse(gate.toString().contains("fixture-masked-target"))
    }
}
