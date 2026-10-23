package io.github.betterzslf.feature.booking

import io.github.betterzslf.platform.PickedFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

class BookingConfirmationTest {
    @Test fun previewAndFinalAcknowledgementAreBothRequired() = runTest {
        var calls = 0
        val flow = BookingConfirmation.fixture(listOf(BookingWriteStep("fixture") { calls++; BookingStepResult.Completed() }))
        flow.confirm(1, true)
        assertEquals(0, calls)
        assertFalse(flow.preview(BookingPreview("fixture", emptyList(), listOf("invalid"))))
        assertEquals(0, calls)
        assertTrue(flow.preview(BookingPreview("fixture", listOf("bounded preview"))))
        val id = flow.state.value.previewId
        flow.confirm(id, false); flow.confirm(id + 1, true)
        assertEquals(0, calls)
        flow.confirm(id, true); flow.confirm(id, true)
        assertEquals(1, calls)
        assertEquals(BookingPhase.COMPLETED, flow.state.value.phase)
    }

    @Test fun editingInvalidatesTheOldPreviewReceipt() = runTest {
        var calls = 0
        val flow = BookingConfirmation.fixture(listOf(BookingWriteStep("fixture") { calls++; BookingStepResult.Completed() }))
        flow.preview(BookingPreview("fixture", listOf("old")))
        val old = flow.state.value.previewId
        flow.edit(); flow.preview(BookingPreview("fixture", listOf("new")))
        flow.confirm(old, true)
        assertEquals(0, calls)
        flow.confirm(flow.state.value.previewId, true)
        assertEquals(1, calls)
    }

    @Test fun simultaneousConfirmTapsExecuteOnlyOneChain() = runTest {
        var calls = 0
        val hold = CompletableDeferred<Unit>()
        val flow = BookingConfirmation.fixture(listOf(BookingWriteStep("fixture") { calls++; hold.await(); BookingStepResult.Completed() }))
        flow.preview(BookingPreview("fixture", listOf("preview")))
        val id = flow.state.value.previewId
        val running = launch { flow.confirm(id, true) }
        testScheduler.runCurrent()
        flow.confirm(id, true)
        assertEquals(1, calls)
        hold.complete(Unit); running.join()
        assertEquals(BookingPhase.COMPLETED, flow.state.value.phase)
    }

    @Test fun partialFailureKeepsApplyReferenceAndDoesNotRunLaterStepsOrRetry() = runTest {
        val calls = mutableListOf<String>()
        val flow = BookingConfirmation.fixture(listOf(
            BookingWriteStep("create") { calls += "create"; BookingStepResult.Completed(mapOf("applyId" to "fixture-apply")) },
            BookingWriteStep("relate") { refs -> calls += "relate"; assertEquals("fixture-apply", refs["applyId"]); BookingStepResult.Failed("fixture failure") },
            BookingWriteStep("later") { calls += "later"; BookingStepResult.Completed() },
        ))
        flow.preview(BookingPreview("fixture", listOf("preview")))
        val id = flow.state.value.previewId
        flow.confirm(id, true); flow.confirm(id, true); flow.edit()
        assertFalse(flow.preview(BookingPreview("retry", emptyList())))
        assertEquals(listOf("create", "relate"), calls)
        assertEquals("fixture-apply", flow.state.value.references["applyId"])
        assertEquals(BookingPhase.FAILED, flow.state.value.phase)
        assertEquals(listOf(BookingStepPhase.COMPLETED, BookingStepPhase.FAILED, BookingStepPhase.PENDING), flow.state.value.steps.map { it.phase })
    }

    @Test fun cancellationDuringAWritePreservesUncertainState() = runTest {
        val hold = CompletableDeferred<Unit>()
        val flow = BookingConfirmation.fixture(listOf(BookingWriteStep("fixture") { hold.await(); BookingStepResult.Completed() }))
        flow.preview(BookingPreview("fixture", listOf("preview")))
        val running = launch { flow.confirm(flow.state.value.previewId, true) }
        testScheduler.runCurrent(); running.cancel(); running.join()
        assertEquals(BookingPhase.UNCERTAIN, flow.state.value.phase)
        assertEquals(BookingStepPhase.UNCERTAIN, flow.state.value.steps.single().phase)
        assertFalse(flow.preview(BookingPreview("retry", emptyList())))
    }

    @Test fun unknownVenueCancellationRemainsExplicitlyBlocked() = runTest {
        val flow = BookingConfirmation(BookingAction.VENUE_CANCEL)
        assertFalse(flow.permitsSubmission)
        flow.preview(BookingPreview("cancel", listOf("fixture")))
        flow.confirm(flow.state.value.previewId, true)
        assertEquals(BookingPhase.BLOCKED, flow.state.value.phase)
        assertTrue(flow.state.value.steps.isEmpty())
    }

    @Test fun formValidationAndDebugStringsDoNotLeakPersonalFields() {
        val form = VenueApplicationForm("site", listOf("time"), "fixture venue", JsonPrimitive(1), "fixture group", "activity", "fixture-contact", "0000000", "fixture-user", "fixture-name")
        assertTrue(form.validation().isEmpty())
        assertFalse(form.toString().contains("fixture-contact"))
        val fitness = FitnessEnrolmentForm("test", "", "fixture-user", "fixture-name", "fixture-student", "000000000000000000", "男", "2030-01-10", "上午")
        assertTrue(fitness.validation().isEmpty())
        assertFalse(fitness.toString().contains("000000"))
        assertFalse(BookingPreview("fixture", listOf("private-value")).toString().contains("private-value"))
        assertEquals("••••0000", bookingMasked(fitness.identityCard))
    }

    @Test fun attachmentValidationEnforcesStatusNineFilesAndImageType() {
        val image = PickedFile("fixture.jpg", "image/jpeg", byteArrayOf(1))
        assertTrue(attachmentValidation("apply", "user", listOf(image), "1").isEmpty())
        assertTrue(attachmentValidation("apply", "user", List(10) { image }, "1").isNotEmpty())
        assertTrue(attachmentValidation("apply", "user", listOf(image), "0").isNotEmpty())
        assertTrue(attachmentValidation("apply", "user", listOf(PickedFile("fixture.txt", "text/plain", byteArrayOf(1))), "1").isNotEmpty())
        assertFailsWith<IllegalArgumentException> { PickedFile("huge", "image/jpeg", ByteArray(8 * 1024 * 1024 + 1)) }
    }
}
