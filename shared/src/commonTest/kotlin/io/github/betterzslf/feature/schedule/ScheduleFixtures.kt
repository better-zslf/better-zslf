package io.github.betterzslf.feature.schedule

/** Entirely synthetic. No research artifact, real identity, captured HTML or credential value is embedded. */
internal object ScheduleFixtures {
    const val account = "FIXTURE001"
    const val password = "fixture-password"
    const val selectedId = "2026-2027-1"
    val term = ScheduleTerm(selectedId, "Fixture term", true)
    val login = """<html><form action="/jsxsd/xk/LoginToXk" method="post"><input name="userAccount"><input type="password" name="userPassword"></form></html>"""
    val identity = """<div id="Top1_divLoginName" class="Nsb_top_menu_nc">Fixture Student(FIXTURE001)<a href="/jsxsd/">Exit</a></div>"""

    fun terms(selected: String = selectedId): String = """
        <select id="xnxq01id" name="xnxq01id">
        <option value="2025-2026-2" ${if (selected == "2025-2026-2") "selected" else ""}>Fixture old term</option>
        <option value="2026-2027-1" ${if (selected == selectedId) "selected" else ""}>Fixture term</option></select>
    """.trimIndent()

    private val days = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
    fun course(title: String = "Fixture Course A", weeks: String = "1-4(单周)[01-02节]", room: String = "Room A"): String =
        """$title<br><font title="老师">Teacher A</font><br><font title="周次(节次)">$weeks</font><br><font title="教室">$room</font>"""

    fun schedule(weekText: String = "1-4(单周)[01-02节]", selected: String = selectedId): String = terms(selected) + """
        <table id="kbtable"><tr><th>Period</th>${days.joinToString("") { "<th>$it</th>" }}</tr>
        <tr><th>第1，2节</th><td>
        <div class="kbcontent1">Fixture Course A</div>
        <div class="kbcontent">${course(weeks = weekText)}<br>--------------------<br>${course("Fixture Course B", "1,2-4(周)[02节]", "Room B")}</div>
        </td>${"<td>&nbsp;</td>".repeat(6)}</tr>
        <tr><th>第三节</th>${"<td></td>".repeat(6)}<td><div class="kbcontent">${course("Fixture Sunday", "0,1-4周[03节]", "Room C")}</div></td></tr>
        <tr><th>备注</th><td colspan="7">Synthetic timetable remark</td></tr></table>
    """.trimIndent()

    fun calendar(selected: String = selectedId): String = terms(selected) + """
        <table id="fixtureCalendar">
        <tr><td>0</td><td title="2026年08月31日"></td><td title="2026年09月01日"></td><td title="2026年09月02日"></td><td title="2026年09月03日"></td><td title="2026年09月04日"></td><td title="2026年09月05日"></td><td title="2026年09月06日"></td></tr>
        <tr><td>1</td><td title="2026年09月07日"></td><td title="2026年09月08日"></td><td title="2026年09月09日"></td><td title="2026年09月10日"></td><td title="2026年09月11日"></td><td title="2026年09月12日"></td><td title="2026年09月13日"></td></tr>
        </table>
    """.trimIndent()
}
