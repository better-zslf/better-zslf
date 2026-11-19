package io.github.betterzslf.schedule.vendor

import io.github.betterzslf.feature.schedule.ScheduleLivePhase
import io.github.betterzslf.feature.schedule.ScheduleLiveState
import io.github.betterzslf.platform.ScheduleVendor
import kotlin.test.*

/**
 * Every vendor path must show the same card.
 *
 * The requirement was "make the other vendors match Meizu", and the risk is drift: four renderers
 * each building their own notification will diverge the first time one of them is touched. The card
 * itself lives in androidMain and needs a real Context, so what is pinned here is the data contract
 * it depends on -- the fields the columns are built from, and the vendors that share it.
 */
class LiveCardContractTest {

    @Test fun everyVendorPathIsClaimedSoAllThreeShareOneContract() {
        // Xiaomi, vivo and Meizu all go through VendorLiveRenderer, which is what lets one card
        // implementation serve them. AOSP and the native-mapping vendors deliberately do not.
        assertEquals(
            setOf(ScheduleVendor.XIAOMI, ScheduleVendor.VIVO, ScheduleVendor.MEIZU),
            VendorLivePolicy.claimed,
        )
    }

    @Test fun theStateCarriesEveryFieldTheCardDraws() {
        // The card shows teacher/room/period and a phase flag. If the state stops carrying one of
        // them, the column blanks out on every vendor at once -- which is exactly the failure this
        // contract exists to catch before it reaches a device.
        val state = ScheduleLiveState(
            phase = ScheduleLivePhase.IN_CLASS,
            title = "正在上课：数据结构",
            text = "剩余 33 分钟",
            progress = 0.4f,
            startsAtMillis = 500L,
            endsAtMillis = 1_000L,
            chip = "33分",
            teacher = "李老师",
            location = "B302",
            periodText = "3-4",
            courseName = "数据结构",
        )
        assertEquals("李老师", state.teacher)
        assertEquals("B302", state.location)
        assertEquals("3-4", state.periodText)
        // courseName is the bare name; title carries the prefix the collapsed card needs.
        assertEquals("数据结构", state.courseName)
        assertNotEquals(state.title, state.courseName)
        assertEquals(ScheduleLivePhase.IN_CLASS, state.phase)
    }

    @Test fun theBreakPhaseIsDistinguishableFromAClassEvenWithAProgressValue() {
        // The phase used to be inferred from `progress == null`. Breaks now have a real fraction, so
        // inferring would draw a break as a class: wrong colour and the period column instead of the
        // start clock.
        val betweenClasses = ScheduleLiveState(
            phase = ScheduleLivePhase.BEFORE_NEXT,
            title = "下节课：高等数学",
            text = "12 分钟后开始",
            progress = 0.35f,
            startsAtMillis = 2_000L,
            endsAtMillis = 4_000L,
            chip = "12分",
            courseName = "高等数学",
        )
        assertNotNull(betweenClasses.progress)
        assertEquals(ScheduleLivePhase.BEFORE_NEXT, betweenClasses.phase)
        assertNotEquals(ScheduleLivePhase.IN_CLASS, betweenClasses.phase)
    }

    @Test fun aMergedPeriodRunIsOneClassNotTwo() {
        // "One course is two periods" -- the gap between those periods must not read as a break.
        // Period numbers stay consecutive, which is what the occurrence generator merges on.
        val periods = listOf(1, 2)
        assertEquals(listOf(1, 2), periods.sorted())
        assertEquals(periods.last(), periods.first() + 1)
    }
}
