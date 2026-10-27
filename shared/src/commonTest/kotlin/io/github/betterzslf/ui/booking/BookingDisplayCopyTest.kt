package io.github.betterzslf.ui.booking

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.booking.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class BookingDisplayCopyTest {
    @Test fun reviewActionsAndScopeAreIndependentDisplayCopy() {
        assertEquals("确认并提交申请", AppText.Booking.SUBMIT_APPLICATION_ACTION)
        assertEquals("确认上传附件", AppText.Booking.CONFIRM_UPLOAD_ACTION)
        assertEquals("确认取消报名", AppText.Booking.CONFIRM_CANCELLATION_ACTION)
        assertEquals("提交报名（再次确认）", AppText.Booking.SUBMIT_REGISTRATION_ACTION)
        assertEquals("加入本次核对", AppText.Booking.ITEM_REVIEW_ACKNOWLEDGE_ACTION)
        assertEquals("按场次提交，项目仅用于本次核对", AppText.Booking.REGISTRATION_SCOPE_HINT)
        assertEquals("取消预约暂未接通", AppText.Booking.BOOKING_CANCELLATION_UNCONNECTED)
        assertEquals("可以在这里查看申请与审批进度，条件满足时也能上传附件。场馆取消还没有接通，本页不会发出取消请求。", AppText.Booking.APPLICATION_DETAILS_AND_UPLOAD_HINT)
    }

    @Test fun calendarCopyUsesDisplayLabelsAndRealWeekdays() {
        assertEquals(listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六"), (0..6).map(AppText.Booking::displayWeekday))
        assertEquals("星期待确认", AppText.Booking.displayWeekday(-1))
        assertEquals("10/28", AppText.Booking.displayMonthDay(10, 28))
        assertEquals("10月28日（周三）", bookingDateLabel("2026-10-28"))
        assertEquals("10月29日（周四）", bookingDateLabel("2026-10-29"))
        assertEquals("2月29日（周四）", bookingDateLabel("2024-02-29"))
        assertEquals("", bookingDateLabel("2026-02-29"))
        assertEquals("", bookingDateLabel("not-a-date"))
    }

    @Test fun countsAndArrangementFormattersHaveLiteralExpectations() {
        assertEquals("找到 3 条 · 已加载 10 条", AppText.Booking.loadedMatchingCounts(3, 10))
        assertEquals("已选 2 项 · 仅用于本次核对", AppText.Booking.localReviewItemCount(2))
        assertEquals("10月28日（周三） · 返回人数", AppText.Booking.returnedPeopleTitle("10月28日（周三）"))
        assertEquals("上午·男生", AppText.Booking.MORNING_BOYS_COUNT_LABEL)
        assertEquals("下午·女生", AppText.Booking.AFTERNOON_GIRLS_COUNT_LABEL)
        assertEquals("10月28日（周三） · 上午 · 男", AppText.Booking.fitnessArrangement("10月28日（周三）", "上午", "男"))
        assertEquals("请在下面选择日期、时段和性别", AppText.Booking.fitnessArrangement("", "", ""))
    }

    @Test fun statusRawValuesNeverBecomeGuessedApprovalLabels() {
        assertEquals("状态待确认", bookingStatusText(""))
        assertEquals("含义待核实（ABC）", bookingStatusText("ABC"))
        assertEquals("含义待核实（1）", bookingStatusText("1"))
        assertEquals("含义待核实（审批中）", bookingStatusText("审批中"))
    }

    @Test fun refreshRevokesOnlyTheLocalPreviewAndItsOldConfirmation() = runTest {
        var calls = 0
        val flow = BookingConfirmation.fixture(listOf(BookingWriteStep("fixture") { calls++; BookingStepResult.Completed() }))
        flow.preview(BookingPreview("fixture", listOf("old")))
        val old = flow.state.value.previewId
        assertTrue(flow.invalidatePreviewForRefresh())
        assertEquals(BookingPhase.EDITING, flow.state.value.phase)
        assertNull(flow.state.value.preview)
        flow.confirm(old, true)
        assertEquals(0, calls)
        flow.preview(BookingPreview("fixture", listOf("new eligibility")))
        flow.confirm(old, true)
        assertEquals(0, calls)
        flow.confirm(flow.state.value.previewId, true)
        assertEquals(1, calls)
        val completed = flow.state.value
        assertFalse(flow.invalidatePreviewForRefresh())
        assertSame(completed, flow.state.value)
    }

    @Test fun refreshPreservesFailedAndUncertainStepReceipts() = runTest {
        listOf(false, true).forEach { uncertain ->
            val flow = BookingConfirmation.fixture(listOf(
                BookingWriteStep("first") { BookingStepResult.Completed(mapOf("applyId" to "fixture-apply")) },
                BookingWriteStep("second") { BookingStepResult.Failed("query before another attempt", uncertain = uncertain) },
            ))
            flow.preview(BookingPreview("fixture", listOf("preview")))
            flow.confirm(flow.state.value.previewId, true)
            val receipt = flow.state.value
            assertEquals(if (uncertain) BookingPhase.UNCERTAIN else BookingPhase.FAILED, receipt.phase)
            assertFalse(flow.invalidatePreviewForRefresh())
            assertSame(receipt, flow.state.value)
            assertEquals("fixture-apply", flow.state.value.references["applyId"])
        }
    }

    @Test fun refreshDoesNotResetAnInFlightWriteOrItsLaterUnknownResult() = runTest {
        val hold = CompletableDeferred<Unit>()
        var calls = 0
        val flow = BookingConfirmation.fixture(listOf(BookingWriteStep("fixture") { calls++; hold.await(); BookingStepResult.Completed() }))
        flow.preview(BookingPreview("fixture", listOf("preview")))
        val running = launch { flow.confirm(flow.state.value.previewId, true) }
        testScheduler.runCurrent()
        val inFlight = flow.state.value
        assertEquals(BookingPhase.RUNNING, inFlight.phase)
        assertFalse(flow.invalidatePreviewForRefresh())
        assertSame(inFlight, flow.state.value)
        running.cancel(); running.join()
        val unknown = flow.state.value
        assertEquals(BookingPhase.UNCERTAIN, unknown.phase)
        assertFalse(flow.invalidatePreviewForRefresh())
        assertSame(unknown, flow.state.value)
        assertEquals(1, calls)
    }
}
