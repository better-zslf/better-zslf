package io.github.betterzslf

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import io.github.betterzslf.core.model.Session
import io.github.betterzslf.core.storage.MemoryKeyValueStore
import io.github.betterzslf.feature.schedule.*
import io.github.betterzslf.platform.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Debug-only synthetic/offline schedule acceptance surface. Absent from release.
 * UI mode uses an in-memory campus session/store and a MockEngine with NO network fallback.
 * Native mode has no AppGraph, account, credential, HTTP client, cookies or private fixture data.
 * Never use this Activity to inject or capture real credentials.
 */
@android.annotation.SuppressLint("SetTextI18n")
class ScheduleProbeActivity : ComponentActivity() {
    private var graph: AppGraph? = null
    private var bridge: ScheduleNativeBridge? = null
    private var widgetHost: AppWidgetHost? = null
    private val schoolZone = ZoneId.of("Asia/Shanghai")
    private val now get() = LocalDateTime.now(schoolZone)
    private val monday get() = LocalDate.now(schoolZone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getStringExtra("mode") == "native") nativeProbe() else uiProbe()
    }

    private fun uiProbe() {
        val base = createPlatformServices()
        val platform = object : PlatformServices by base {
            override val storage = MemoryKeyValueStore()
            override fun openUrl(url: String) = Unit
            override fun httpClient() = HttpClient(MockEngine { request ->
                val path = request.url.encodedPath
                val content = when {
                    request.url.host != "jwgln.zsc.edu.cn" -> "{\"code\":200,\"data\":[]}"
                    path.endsWith("xsMain.jsp") -> "<div id='Top1_divLoginName' class='Nsb_top_menu_nc'>Synthetic(fixture-user)</div>"
                    path.endsWith("xskb_list.do") -> timetableHtml()
                    path.endsWith("jxzl_query") -> calendarHtml()
                    else -> "<html><body>Offline synthetic response</body></html>"
                }
                respond(content, headers = headersOf(HttpHeaders.ContentType,
                    if (request.url.host == "jwgln.zsc.edu.cn") "text/html; charset=UTF-8" else ContentType.Application.Json.toString()))
            })
        }
        val fixtureGraph = AppGraph(platform).also { graph = it }
        if (intent.getStringExtra("theme") == "dark") fixtureGraph.settings.update { it.copy(theme = "Dark") }
        if (intent.getStringExtra("theme") == "light") fixtureGraph.settings.update { it.copy(theme = "Light") }
        fixtureGraph.sessions.save(Session("synthetic-offline-token",
            buildJsonObject { put("id", "synthetic-owner"); put("name", "合成用户"); put("realName", "合成用户") },
            buildJsonObject {}))
        setContent {
            App(platform, graphProvider = { fixtureGraph })
            LaunchedEffect(Unit) {
                fixtureGraph.schedule.state.first { it.owner == "synthetic-owner" }
                if (!intent.getBooleanExtra("unbound", false)) {
                    fixtureGraph.schedule.dismissBindingPrompt()
                    check(fixtureGraph.schedule.verifyBinding("fixture-user", "fixture-password"))
                    check(fixtureGraph.schedule.confirmBinding())
                }
            }
        }
    }

    private fun termSelect() = "<select id='xnxq01id' name='xnxq01id'><option value='future'>合成未来学期</option><option value='fixture-term' selected>合成演示学期</option></select>"
    private fun timetableHtml(): String = buildString {
        append(termSelect()); append("<table id='kbtable'><tr><th>节次</th>")
        listOf("一", "二", "三", "四", "五", "六", "日").forEach { append("<th>星期$it</th>") }
        append("</tr>")
        for (period in 1..12 step 2) {
            append("<tr><th>第$period-${period + 1}节</th>")
            for (day in 1..7) {
                append("<td>")
                if ((day == 1 && period == 1) || (day == LocalDate.now(schoolZone).dayOfWeek.value && period in listOf(1, 5, 9))) {
                    append("<div class='kbcontent'>合成课程$period<br><font title='老师'>合成教师</font><br><font title='教室'>合成教学楼101</font><br><font title='周次(节次)'>1-20(周)[$period-${period + 1}节]</font></div>")
                    if (period == 1) append("<div class='kbcontent'>同格合成选修课<br><font title='教室'>合成教学楼202</font><br><font title='周次(节次)'>1,3,5,7,9,11,13,15,17,19(周)[$period-${period + 1}节]</font></div>")
                }
                append("</td>")
            }
            append("</tr>")
        }
        append("<tr><td>备注</td><td colspan='7'>全部课程、教师、地点与账号都是手工合成；没有真实网络请求。</td></tr></table>")
    }
    private fun calendarHtml() = buildString {
        append(termSelect()); append("<table><tr><th>周次</th><th>星期一</th></tr>")
        for (week in 1..20) {
            val date = monday.plusWeeks((week - 1).toLong())
            append("<tr><td>$week</td><td title='${date.year}年${date.monthValue}月${date.dayOfMonth}日'>合成日期</td></tr>")
        }
        append("</table>")
    }

    private fun nativeProbe() {
        val native = createScheduleNativeBridge().also { bridge = it }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 48, 24, 32) }
        val status = TextView(this).apply { textSize = 17f }
        fun display(prefix: String) {
            native.refreshCapabilities()
            val value = native.capabilities.value
            status.text = "$prefix\nnotifications=${value.notificationsGranted}; exact=${value.exactAlarmsAllowed}; batteryRestricted=${value.batteryRestricted}; pin=${value.pinWidgetsSupported}"
        }
        fun action(label: String, block: () -> Unit) {
            layout.addView(Button(this).apply { text = label; setOnClickListener { block() } })
        }
        layout.addView(TextView(this).apply { text = "Synthetic native schedule probe (debug only)"; textSize = 19f })
        layout.addView(status)
        action("Refresh capabilities") { display("refreshed") }
        action("Request notifications") { native.requestNotificationPermission(); display("request launched") }
        action("Open exact alarm settings") { native.openExactAlarmSettings() }
        action("Seed 10-minute reminder due next minute") { display(seed(native, 10)) }
        action("Seed 0-minute reminder due next minute") { display(seed(native, 0)) }
        action("Pin TODAY widget") { display("pin request=${native.requestPinWidget(ScheduleWidgetKind.TODAY)}") }
        action("Pin NEXT widget") { display("pin request=${native.requestPinWidget(ScheduleWidgetKind.NEXT)}") }
        action("Bind two local widget previews") {
            val manager = AppWidgetManager.getInstance(this)
            val host = widgetHost ?: AppWidgetHost(this, 0x524335).also { widgetHost = it }
            host.appWidgetIds.forEach(host::deleteAppWidgetId)
            var accepted = 0
            for ((provider, height) in listOf("ScheduleTodayWidgetProvider" to 250, "ScheduleNextWidgetProvider" to 160)) {
                val id = host.allocateAppWidgetId()
                val options = Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 340)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
                }
                if (manager.bindAppWidgetIdIfAllowed(id, ComponentName(this, "io.github.betterzslf.schedule.$provider"), options)) {
                    val info = manager.getAppWidgetInfo(id)
                    val view = host.createView(this, id, info)
                    view.setAppWidget(id, info)
                    layout.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        (height * resources.displayMetrics.density).toInt()))
                    accepted++
                } else host.deleteAppWidgetId(id)
            }
            host.startListening()
            display("local widgets bound=$accepted")
        }
        action("Clear publication and native work") { native.clear(); display("cleared") }
        action("Try stale revision (must fail)") {
            val stale = publication("stale-fixture-revision", 0)
            display("stale accepted=${native.publish(stale)}")
        }
        setContentView(ScrollView(this).apply { addView(layout) })
        display("ready")
    }

    private fun seed(native: ScheduleNativeBridge, lead: Int): String {
        val revision = java.util.UUID.randomUUID().toString()
        native.clear(ScheduleNativeStatus.NOT_SYNCED, revision)
        return "seed accepted=${native.publish(publication(revision, lead))}"
    }

    private fun publication(revision: String, lead: Int): ScheduleNativePublication {
        val first = now.withSecond(0).withNano(0).plusMinutes((lead + 1).toLong())
        require(first.plusMinutes(60).toLocalDate() == first.toLocalDate()) { "Use this synthetic test before 23:00 school time" }
        val formatter = DateTimeFormatter.ofPattern("HH:mm")
        val periods = (1..12).map { number ->
            val start = first.plusMinutes((number - 1) * 3L)
            SchedulePeriod(number, start.format(formatter), start.plusMinutes(2).format(formatter))
        }
        val snapshot = ScheduleSnapshot(ScheduleTerm("fixture-term", "Synthetic semester", true),
            listOf(ScheduleCourse("synthetic-native-course", "Synthetic two-period class", location = "Synthetic room 101",
                dayOfWeek = first.dayOfWeek.value, periods = listOf(1, 2), weeks = listOf(1))),
            weekMondays = mapOf(1 to first.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()),
            syncedAtMillis = System.currentTimeMillis())
        return ScheduleNativePublication(revision, ScheduleNativeStatus.READY, snapshot, periods,
            remindersEnabled = true, reminderLeadMinutes = lead)
    }

    override fun onDestroy() {
        widgetHost?.stopListening()
        graph?.close()
        bridge?.close()
        super.onDestroy()
    }
}
