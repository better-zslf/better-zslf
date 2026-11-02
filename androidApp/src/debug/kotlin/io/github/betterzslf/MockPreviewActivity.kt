package io.github.betterzslf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import io.github.betterzslf.core.model.Session
import io.github.betterzslf.core.storage.MemoryKeyValueStore
import io.github.betterzslf.platform.PlatformServices
import io.github.betterzslf.platform.createPlatformServices
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Debug-only synthetic preview surface. Absent from release.
 *
 * Purpose: render every screen against made-up data so the UI can be screenshotted and compared
 * with the design file. It never reaches the network, never reads a real account, and stores
 * everything in a [MemoryKeyValueStore] that dies with the process.
 *
 * Intent extras (all optional):
 *  - `theme` = `light` | `dark`      force the appearance instead of following the system
 *  - `signed_in` = `false`           start on the entry chooser instead of the shell
 *  - `academic` = `false`            skip the synthetic academic (timetable) binding
 */
class MockPreviewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = intent.getStringExtra("theme")?.equals("dark", ignoreCase = true) == true
        val light = intent.getStringExtra("theme")?.equals("light", ignoreCase = true) == true
        val signedIn = intent.getStringExtra("signed_in")?.equals("false", ignoreCase = true) != true
        val bindAcademic = intent.getStringExtra("academic")?.equals("false", ignoreCase = true) != true

        val base = createPlatformServices()
        val platform = object : PlatformServices by base {
            override val storage = MemoryKeyValueStore()
            override val versionName = "1.0.0-rc39-pro"
            override fun openUrl(url: String) = Unit
            override fun copyToClipboard(text: String) = true
            override fun httpClient() = HttpClient(MockEngine { request ->
                val body = MockResponses.respond(request.url.host, request.url.encodedPath, request.url.parameters)
                respond(
                    content = body,
                    headers = headersOf(
                        HttpHeaders.ContentType,
                        if (request.url.host == ACADEMIC_HOST) "text/html; charset=UTF-8"
                        else ContentType.Application.Json.toString(),
                    ),
                )
            })
        }

        val graph = AppGraph(platform)
        when {
            dark -> graph.settings.update { it.copy(theme = "Dark") }
            light -> graph.settings.update { it.copy(theme = "Light") }
        }

        setContent {
            App(platform, graphProvider = { graph })
            LaunchedEffect(Unit) {
                if (signedIn) {
                    graph.sessions.save(Session(SYNTHETIC_TOKEN, MockResponses.userInfoObject,
                        buildJsonObject { put("id", SYNTHETIC_OWNER) }))
                }
                // The timetable slot is a local constant, not the campus user id: wait for any
                // owner to be assigned rather than for the campus id.
                graph.schedule.state.first { it.owner != null }
                if (signedIn && bindAcademic) {
                    graph.schedule.dismissBindingPrompt()
                    // Reported, never swallowed: a silent failure here looks exactly like an
                    // unbound app and would hide a broken fixture from whoever screenshots it.
                    val verified = runCatching { graph.schedule.verifyBinding(MockResponses.ACADEMIC_USER, "synthetic") }
                    val confirmed = verified.getOrDefault(false) && runCatching { graph.schedule.confirmBinding() }.getOrDefault(false)
                    android.util.Log.i("MockPreview",
                        "academic verify=${verified.getOrElse { "throw:${it::class.java.simpleName}" }} confirmed=$confirmed")
                }
            }
        }
    }
}

// The academic system is its own host with its own HTML protocol; the mock answers it in HTML.
private const val ACADEMIC_HOST = "jwgln.zsc.edu.cn"
private const val SYNTHETIC_OWNER = "synthetic-owner"
private const val SYNTHETIC_TOKEN = "synthetic-offline-token"

/**
 * Every response the debug preview can produce. All names, numbers, places and scores are made
 * up; none of it is copied from a real account or a real response capture.
 */
private object MockResponses {

    const val ACADEMIC_USER = "fixture-student"

    fun respond(host: String, path: String, parameters: io.ktor.http.Parameters): String {
        if (host == ACADEMIC_HOST) return academic(path)
        return when {
            path.endsWith("/app/login") -> envelope(buildJsonObject {
                put("accessToken", SYNTHETIC_TOKEN)
                put("userInfo", userInfoObject)
                put("deptInfo", buildJsonObject { put("id", SYNTHETIC_OWNER) })
            })
            path.endsWith("/app/appUser/getUserInfo") -> envelope(buildJsonObject {
                put("userInfo", userInfoObject)
            })
            path.endsWith("/app/appUser/getUserPhotoURL") -> envelope(buildJsonObject {
                put("attachment", kotlinx.serialization.json.JsonNull)
            })
            path.endsWith("/app/appUser/getUserArctileStatis") -> envelope(buildJsonObject {
                put("collect", "18"); put("comment", "4"); put("history", "26")
            })
            path.endsWith("/app/appCmsArticle/articleDetailsByName") -> envelope(article(parameters))
            path.endsWith("/run/errand/getErrandList") -> envelope(buildJsonObject { put("errandList", taskList()) })
            path.endsWith("/run/errand/getErrandDetail") -> envelope(buildJsonObject { put("errandDetail", taskDetail()) })
            path.endsWith("/run/errand/getUserErrandTrackRecord") -> envelope(buildJsonObject {
                put("userErrandTrackRecord", recordList())
            })
            path.endsWith("/run/errand/getTrackByTrid") -> envelope(buildJsonObject { put("track", trackPoints()) })
            path.endsWith("/run/errand/getTrackRecordByTrid") -> envelope(buildJsonObject { put("recordMap", recordObject()) })
            path.endsWith("/app/campusNetwork/getOperatorAccountByIDCard") -> envelope(buildJsonObject {
                put("operatorAccount", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "30001"); put("operatorAccount", "076212345678"); put("typeStatus", "正常")
                    })
                    add(buildJsonObject {
                        put("id", "30002"); put("operatorAccount", "076287654321"); put("typeStatus", "正常")
                    })
                })
            })
            path.endsWith("/app/campusNetwork/checkPhone") -> envelope(buildJsonObject { put("ok", true) })
            path.endsWith("/app/campusNetwork/updateOperatorPasswordById") -> envelope(buildJsonObject { put("ok", true) })
            path.endsWith("/app/appUser/modifyPassword") -> envelope(buildJsonObject { put("ok", true) })
            path.endsWith("/app/appUser/checkPhone") -> envelope(buildJsonObject { put("ok", true) })
            path.endsWith("/app/appUser/resetNewPassWord") -> envelope(buildJsonObject { put("ok", true) })
            path.endsWith("/app/checkVersion") -> envelope(buildJsonObject {
                put("versionCode", "40"); put("versionName", "1.0.0-rc39-pro")
                put("downloadUrl", "https://example.invalid/synthetic.apk"); put("forceUpdate", false)
            })
            else -> envelope(buildJsonObject { put("ok", true) })
        }
    }

    /** `data` is the only part the app reads; `code=200` is the success discriminator. */
    private fun envelope(data: kotlinx.serialization.json.JsonElement): String =
        buildJsonObject { put("code", 200); put("message", "synthetic"); put("data", data) }.toString()

    val userInfoObject: JsonObject = buildJsonObject {
        put("id", SYNTHETIC_OWNER)
        put("name", "合成同学")
        put("schoolName", "电子科技大学中山学院")
        put("studentNum", "2023999001")
        put("account", "2023999001")
        put("typeValue", "本科生")
        put("sexValue", "男")
    }

    private fun article(parameters: io.ktor.http.Parameters): JsonObject {
        val name = parameters["articleName"].orEmpty().ifBlank { "synthetic-doc" }
        return buildJsonObject {
            put("id", "9001")
            put("title", when (name) {
                "user-agreement" -> "用户协议（合成样例）"
                "privacy-policy" -> "隐私政策（合成样例）"
                else -> "合成文档 · $name"
            })
            put("creatTime", "2026-10-01 09:00")
            put("content", ARTICLE_HTML)
        }
    }

    private fun taskList() = buildJsonArray {
        // The list decoder reads `id` as errandScheduleId in this container. Supplying a second,
        // different `errandScheduleId` makes the two plan ids conflict and drops the schedule, so
        // `id` is the only plan field here and it matches the detail's errandScheduleId.
        add(buildJsonObject {
            put("id", "5501"); put("errandId", "8801")
            put("eName", "秋季学期阳光晨跑"); put("startTime", "2026-10-08 06:00")
            put("endTime", "2026-11-30 22:00"); put("isFinish", "0")
        })
        add(buildJsonObject {
            put("id", "5502"); put("errandId", "8802")
            put("eName", "校园健身跑 · 3 公里"); put("startTime", "2026-10-20 06:00")
            put("endTime", "2026-12-15 22:00"); put("isFinish", "0")
        })
        add(buildJsonObject {
            put("id", "5503"); put("errandId", "8803")
            put("eName", "上学期阳光长跑"); put("startTime", "2026-03-01 06:00")
            put("endTime", "2026-06-30 22:00"); put("isFinish", "1")
        })
    }

    private fun taskDetail() = buildJsonObject {
        put("errandId", "8801"); put("errandScheduleId", "5501")
        put("eName", "秋季学期阳光晨跑")
        put("startTime", "2026-10-08 06:00"); put("endTime", "2026-11-30 22:00"); put("isFinish", "0")
        // The distance needs an explicit unit or the decoder refuses to guess; metres here.
        put("minimumDistance", "2000"); put("minimumDistanceUnit", "m")
        put("geoCoorList", buildJsonArray {
            for (index in 0 until 4) {
                add(buildJsonObject {
                    put("name", listOf("起点 · 体育馆西侧", "一号点 · 图书馆北门", "二号点 · 中心湖东岸", "终点 · 体育馆西侧")[index])
                    put("lon", 113.31 + index * 0.002)
                    put("lat", 22.53 + index * 0.001)
                })
            }
        })
        put("walkPaths", buildJsonArray { add(trackPoints()) })
    }

    private fun recordList() = buildJsonArray {
        for (index in 0 until 6) {
            add(buildJsonObject {
                put("trid", "770$index")
                put("eName", listOf("秋季学期阳光晨跑", "校园健身跑 · 3 公里", "上学期阳光长跑")[index % 3])
                put("runLength", (2400 + index * 380).toString())
                put("runTime", (780_000 + index * 45_000L).toString())
                put("status", "1")
            })
        }
    }

    private fun recordObject() = buildJsonObject {
        put("trid", "7700"); put("eName", "秋季学期阳光晨跑")
        put("runLength", "2860"); put("runTime", "812000"); put("status", "1")
    }

    /** `[lon, lat]` pairs, which is the documented shape the decoder accepts. */
    private fun trackPoints() = buildJsonArray {
        for (index in 0 until 40) {
            val t = index / 39.0
            add(buildJsonArray {
                add(113.3100 + t * 0.0075)
                add(22.5300 + kotlin.math.sin(t * kotlin.math.PI) * 0.0021)
            })
        }
    }

    private fun academic(path: String): String = when {
        // The menu identity regex is `名称(账号)`, so the synthetic name comes first.
        path.endsWith("xsMain.jsp") -> "<div id='Top1_divLoginName' class='Nsb_top_menu_nc'>合成同学($ACADEMIC_USER)</div>"
        path.endsWith("xskb_list.do") -> timetableHtml()
        path.endsWith("jxzl_query") -> calendarHtml()
        else -> "<html><body>Offline synthetic response</body></html>"
    }

    private fun termSelect() = "<select id='xnxq01id' name='xnxq01id'>" +
        "<option value='2025-2026-2'>2025-2026 学年第二学期</option>" +
        "<option value='2026-2027-1' selected>2026-2027 学年第一学期</option></select>"

    /** Twelve periods x seven days, with a double-period class, a two-courses-in-one-slot cell. */
    private fun timetableHtml(): String = buildString {
        append(termSelect())
        append("<table id='kbtable'><tr><th>节次</th>")
        listOf("一", "二", "三", "四", "五", "六", "日").forEach { append("<th>星期$it</th>") }
        append("</tr>")
        for (period in 1..12 step 2) {
            append("<tr><th>第$period-${period + 1}节</th>")
            for (day in 1..7) {
                append("<td>")
                coursesFor(day, period).forEach { course ->
                    append("<div class='kbcontent'>")
                    append(course.title).append("<br>")
                    append("<font title='老师'>").append(course.teacher).append("</font><br>")
                    append("<font title='教室'>").append(course.room).append("</font><br>")
                    append("<font title='周次(节次)'>").append(course.weeks).append("[$period-${period + 1}节]</font>")
                    append("</div>")
                }
                append("</td>")
            }
            append("</tr>")
        }
        append("<tr><td>备注</td><td colspan='7'>全部课程、教师、地点与账号均为手工合成，没有真实网络请求。</td></tr>")
        append("</table>")
    }

    private data class MockCourse(val title: String, val teacher: String, val room: String, val weeks: String)

    private fun coursesFor(day: Int, period: Int): List<MockCourse> {
        val allWeeks = "1-16(周)"
        val oddWeeks = "1,3,5,7,9,11,13,15(周)"
        return when (day to period) {
            1 to 1 -> listOf(MockCourse("高等数学 A", "合成教师甲", "A101", allWeeks))
            1 to 3 -> listOf(MockCourse("线性代数", "合成教师乙", "D110", allWeeks))
            1 to 5 -> listOf(MockCourse("大学物理", "合成教师丙", "B204", allWeeks))
            2 to 1 -> listOf(MockCourse("数据结构", "合成教师丁", "B302", allWeeks))
            2 to 5 -> listOf(MockCourse("体育 · 篮球", "合成教师戊", "风雨操场", oddWeeks))
            3 to 1 -> listOf(MockCourse("大学英语 III", "合成教师己", "C205", allWeeks))
            3 to 3 -> listOf(
                MockCourse("概率论与数理统计", "合成教师庚", "A203", allWeeks),
                MockCourse("专业选修 · 人工智能导论", "合成教师辛", "A305", oddWeeks),
            )
            3 to 9 -> listOf(MockCourse("程序设计实验", "合成教师壬", "机房 3", allWeeks))
            4 to 1 -> listOf(MockCourse("马克思主义基本原理", "合成教师癸", "E102", allWeeks))
            4 to 7 -> listOf(MockCourse("形势与政策", "合成教师子", "E204", "1-8(周)"))
            5 to 3 -> listOf(MockCourse("电路与电子技术", "合成教师丑", "F301", allWeeks))
            5 to 9 -> listOf(MockCourse("创新创业实践", "合成教师寅", "创客空间", "9-16(周)"))
            6 to 1 -> listOf(MockCourse("中国近现代史纲要", "合成教师卯", "E105", oddWeeks))
            else -> emptyList()
        }
    }

    private fun calendarHtml() = buildString {
        append(termSelect())
        append("<table><tr><th>周次</th><th>星期一</th><th>星期二</th></tr>")
        val monday = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        for (week in 1..20) {
            val date = monday.plusWeeks((week - 1).toLong())
            append("<tr><td>").append(week).append("</td>")
            append("<td title='").append(date.year).append("年").append(date.monthValue).append("月")
                .append(date.dayOfMonth).append("日'>合成日期</td>")
            append("<td title='").append(date.plusDays(1).monthValue).append("月")
                .append(date.plusDays(1).dayOfMonth).append("日'>合成日期</td>")
            append("</tr>")
        }
        append("</table>")
    }

    private const val ARTICLE_HTML = """
        <h2>合成文档标题</h2>
        <p>这是一段完全合成的正文，用来验证协议/隐私页在真实排版下的行高、段落间距与卡片内边距。</p>
        <p>它不包含任何真实条款文本，也不来自任何真实接口响应。</p>
        <ul><li>第一条：合成列表项，用于观察列表缩进。</li><li>第二条：合成列表项。</li></ul>
        <p>结束段落。</p>
    """
}
