package io.github.betterzslf.feature.schedule

import kotlin.test.*

class AcademicHtmlParsingTest {
    @Test fun identityUsesTheFixedMenuAndExactLastParenthesizedAccount() {
        val identity = assertNotNull(parseAcademicIdentity(ScheduleFixtures.identity, ScheduleFixtures.account))
        assertEquals(ScheduleFixtures.account, identity.account)
        assertEquals("Fixture Student", identity.displayName)
        assertNull(parseAcademicIdentity(ScheduleFixtures.identity, "OTHER_FIXTURE"))
        assertFalse(identity.toString().contains(ScheduleFixtures.account))
        assertFalse(identity.toString().contains("Fixture Student"))
        assertEquals(ScheduleFixtures.account, parseAcademicIdentity(ScheduleFixtures.identity.replace("Fixture Student", "Fixture (Alias)"), ScheduleFixtures.account)?.account)
    }

    @Test fun independentBrAccountLineIsRecognizedWithoutAnArbitraryContainsFallback() {
        val html = "<div>Fixture profile<br>学号：FIXTURE001<br>Unrelated line</div>"
        assertEquals(ScheduleFixtures.account, assertNotNull(parseAcademicIdentity(html, ScheduleFixtures.account)).account)
        assertNull(parseAcademicIdentity("<div>Unrelated reference FIXTURE001</div>", ScheduleFixtures.account))
        assertNull(parseAcademicIdentity("<p>FIXTURE001</p><span>Fixture Student(FIXTURE001)</span>", ScheduleFixtures.account))
    }

    @Test fun anotherAccountInTheMenuOrConflictingIdentityCannotBeHiddenByBodyText() {
        val wrong = ScheduleFixtures.identity.replace("FIXTURE001", "OTHER_FIXTURE") + "<p>FIXTURE001</p>"
        assertNull(parseAcademicIdentity(wrong, ScheduleFixtures.account))
        assertNull(parseAcademicIdentity(ScheduleFixtures.identity + "<span>学号：OTHER_FIXTURE</span>", ScheduleFixtures.account))
        assertNull(parseAcademicIdentity(ScheduleFixtures.login + ScheduleFixtures.identity, ScheduleFixtures.account))
    }

    @Test fun explicitSelectedTermIsNotTheFirstOptionOrAnUnrelatedSelect() {
        val terms = parseAcademicTerms("<select><option selected value='BAD'>bad</option></select>" + ScheduleFixtures.terms())
        assertEquals(2, terms.size)
        assertFalse(terms.first().isDefault)
        assertEquals(ScheduleFixtures.selectedId, terms.single { it.isDefault }.id)
        assertTrue(parseAcademicTerms(ScheduleFixtures.terms().replace(" selected", "")).none { it.isDefault })
    }

    @Test fun duplicateTermValuesAndMultipleSelectedOptionsAreRejected() {
        assertFailsWith<AcademicProtocolException> { parseAcademicTerms("<select id='xnxq01id'><option selected value='A'>A</option><option selected value='B'>B</option></select>") }
        assertFailsWith<AcademicProtocolException> { parseAcademicTerms("<select id='xnxq01id'><option value='A'>A</option><option value='A'>Again</option></select>") }
    }

    @Test fun detailWinsOverBriefAndMultipleCoursesSinglePeriodsAndRemarksStaySeparate() {
        val calendar = parseAcademicCalendar(ScheduleFixtures.calendar())
        val snapshot = parseAcademicSchedule(ScheduleFixtures.schedule(), ScheduleFixtures.term, calendar, 123)
        assertTrue(snapshot.complete)
        assertEquals(3, snapshot.courses.size)
        val course = snapshot.courses.single { it.title == "Fixture Course A" }
        assertEquals(listOf(1, 2), course.periods)
        assertEquals(listOf(1, 3), course.weeks)
        assertEquals(1, course.dayOfWeek)
        assertEquals(listOf(2), snapshot.courses.single { it.title == "Fixture Course B" }.periods)
        assertEquals(7, snapshot.courses.single { it.title == "Fixture Sunday" }.dayOfWeek)
        assertEquals(listOf("Synthetic timetable remark"), snapshot.remarks)
        assertEquals(123L, snapshot.syncedAtMillis)
        assertTrue(snapshot.courses.map { it.id }.all { it.startsWith("course-") })
    }

    @Test fun unknownWeekFormatReturnsAnExplicitlyIncompleteResultNotAnEmptyHealthySchedule() {
        val snapshot = parseAcademicSchedule(ScheduleFixtures.schedule("TBD[01-02节]"), ScheduleFixtures.term,
            AcademicCalendar(emptyMap(), listOf("CALENDAR_UNAVAILABLE")), 0)
        assertFalse(snapshot.complete)
        assertTrue(snapshot.warnings.contains("WEEK_FORMAT_UNRECOGNIZED"))
        assertTrue(snapshot.courses.any { it.weeks.isEmpty() })
        assertTrue(scheduleOccurrences(snapshot, defaultSchedulePeriods(), 0, Long.MAX_VALUE).isEmpty())
    }

    @Test fun calendarHasExplicitWeekZeroAndDoesNotInferAbsentWeeks() {
        val calendar = parseAcademicCalendar(ScheduleFixtures.calendar())
        assertEquals(mapOf(0 to "2026-08-31", 1 to "2026-09-07"), calendar.mondays)
        assertNull(calendar.mondays[2])
        assertEquals("CALENDAR_UNAVAILABLE", parseAcademicCalendar("<html>No calendar</html>").warnings.single())
    }

    @Test fun invalidCalendarMondayAndConflictingWeekAreRejectedWithoutInventingAnAnchor() {
        val invalid = parseAcademicCalendar(ScheduleFixtures.calendar().replace("2026年08月31日", "2026年09月01日"))
        assertTrue(invalid.mondays.isEmpty())
        assertTrue(invalid.warnings.contains("CALENDAR_INVALID"))
        val conflict = "<table><tr><td>0</td><td title='2026年08月31日'></td></tr><tr><td>0</td><td title='2026年09月07日'></td></tr></table>"
        assertTrue(parseAcademicCalendar(conflict).mondays.isEmpty())
    }

    @Test fun loginShellErrorPageAndWrongSelectedTermDoNotBecomeAHealthySnapshot() {
        assertTrue(academicLoginShell(ScheduleFixtures.login))
        assertFailsWith<AcademicProtocolException> { parseAcademicSchedule(ScheduleFixtures.login, ScheduleFixtures.term, AcademicCalendar(emptyMap(), emptyList()), 0) }
        assertFailsWith<AcademicProtocolException> { parseAcademicSchedule("<h1>Server failure</h1>", ScheduleFixtures.term, AcademicCalendar(emptyMap(), emptyList()), 0) }
        assertFailsWith<AcademicProtocolException> { parseAcademicSchedule(ScheduleFixtures.schedule(selected = "2025-2026-2"), ScheduleFixtures.term, AcademicCalendar(emptyMap(), emptyList()), 0) }
    }

    @Test fun logicalGridPreservesCourseRowSpanAndLeadingSectionColSpan() {
        val days = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
        val html = ScheduleFixtures.terms() + """
            <table id='kbtable'><tr><th colspan='2'>Period</th>${days.joinToString("") { "<th>$it</th>" }}</tr>
            <tr><th rowspan='2'>AM</th><th>第1，2节</th><td rowspan='2'><div class='kbcontent'>${ScheduleFixtures.course(weeks = "1-4周[01-04节]")}</div></td>${"<td></td>".repeat(6)}</tr>
            <tr><th>第3，4节</th>${"<td></td>".repeat(6)}</tr></table>
        """.trimIndent()
        val snapshot = parseAcademicSchedule(html, ScheduleFixtures.term, AcademicCalendar(emptyMap(), emptyList()), 0)
        assertTrue(snapshot.complete)
        assertEquals(1, snapshot.courses.size)
        assertEquals(listOf(1, 2, 3, 4), snapshot.courses.single().periods)
        assertEquals(1, snapshot.courses.single().dayOfWeek)
    }

    @Test fun weekGrammarSupportsDiscreteRangesParityAndWeekZeroButRejectsUnknownParts() {
        assertEquals(listOf(0, 1, 2, 3), parseAcademicWeeks("0,1-3周").values)
        assertEquals(listOf(1, 3, 5, 7), parseAcademicWeeks("1-8(单周)").values)
        assertEquals(listOf(2, 4, 7, 9), parseAcademicWeeks("1-4(双周),7-9(单周)").values)
        assertEquals(listOf(1, 3), parseAcademicWeeks("1,2,3(单周)").values)
        assertEquals(listOf(1, 2, 3, 4), parseAcademicWeeks("1-4(周)[01-02节]").values)
        for (bad in listOf("", "TBD", "1-3,UNKNOWN", "3-1", "-1", "100", "99999999999999999999", "1,,2")) assertFalse(parseAcademicWeeks(bad).valid, bad)
        assertEquals(listOf(1, 2), parseAcademicPeriods("第1，2节").values)
        assertEquals(listOf(1), parseAcademicPeriods("第1节").values)
        assertEquals(listOf(11, 12), parseAcademicPeriods("第十一、十二节").values)
        assertFalse(parseAcademicPeriods("第1，13节").valid)
    }

    @Test fun strictUtf8AndLegacyGb18030AreDecodedWithoutLossyReplacement() {
        assertEquals("Fixture UTF8", decodeAcademicHtml("Fixture UTF8".encodeToByteArray()))
        // Synthetic GB18030 bytes for a protocol label, not captured personal data.
        assertEquals("课表", decodeAcademicHtml(byteArrayOf(0xbf.toByte(), 0xce.toByte(), 0xb1.toByte(), 0xed.toByte())))
        assertFailsWith<AcademicProtocolException> { decodeAcademicHtml(byteArrayOf(0x81.toByte())) }
        assertFailsWith<AcademicProtocolException> { decodeAcademicHtml(byteArrayOf()) }
    }
}
