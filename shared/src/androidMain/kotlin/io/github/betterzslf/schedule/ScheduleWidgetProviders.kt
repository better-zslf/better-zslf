package io.github.betterzslf.schedule

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.util.TypedValue
import android.widget.RemoteViews
import io.github.betterzslf.core.text.AppText.NativeSchedule
import io.github.betterzslf.feature.schedule.SCHEDULE_TIME_ZONE_ID
import io.github.betterzslf.feature.schedule.scheduleNextOccurrence
import io.github.betterzslf.feature.schedule.scheduleSchoolDate
import io.github.betterzslf.feature.schedule.scheduleTodayOccurrences
import io.github.betterzslf.platform.ScheduleNativeStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

abstract class ScheduleWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: android.content.Intent) {
        super.onReceive(context, intent)
        ScheduleNativeWork.execute(goAsync()) { ScheduleNativeEngine.from(context).reconcile() }
    }
}
class ScheduleTodayWidgetProvider : ScheduleWidgetProvider()
class ScheduleNextWidgetProvider : ScheduleWidgetProvider()

internal object ScheduleWidgets {
    private val zone = ZoneId.of(SCHEDULE_TIME_ZONE_ID)
    private val clockFormat = DateTimeFormatter.ofPattern(NativeSchedule.CLOCK_FORMAT)
    private val stampFormat = DateTimeFormatter.ofPattern(NativeSchedule.SYNC_STAMP_FORMAT)
    fun clock(millis: Long): String = Instant.ofEpochMilli(millis).atZone(zone).format(clockFormat)
    private fun stamp(millis: Long): String = Instant.ofEpochMilli(millis).atZone(zone).format(stampFormat)
    fun hasWidgets(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return manager.getAppWidgetIds(ComponentName(context, ScheduleTodayWidgetProvider::class.java)).isNotEmpty() ||
            manager.getAppWidgetIds(ComponentName(context, ScheduleNextWidgetProvider::class.java)).isNotEmpty()
    }
    fun update(context: Context, view: ScheduleNativeView): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        var success = true
        for ((type, provider) in listOf(true to ScheduleTodayWidgetProvider::class.java, false to ScheduleNextWidgetProvider::class.java)) {
            manager.getAppWidgetIds(ComponentName(context, provider)).forEach { id ->
                // Even a missing app resource must replace old-owner pixels with a non-sensitive view.
                val remote = runCatching { render(context, manager, id, type, view) }.getOrElse {
                    RemoteViews("android", android.R.layout.simple_list_item_1).apply {
                        setTextViewText(android.R.id.text1, NativeSchedule.OPEN_TIMETABLE)
                        setOnClickPendingIntent(android.R.id.text1, ScheduleNativeEngine.from(context).openIntent(view.ownerRevision))
                    }
                }
                runCatching { manager.updateAppWidget(id, remote) }.onFailure { success = false }
            }
        }
        return success
    }
    private fun render(context: Context, manager: AppWidgetManager, id: Int, todayWidget: Boolean, view: ScheduleNativeView): RemoteViews {
        fun resource(name: String, kind: String): Int = context.resources.getIdentifier(name, kind, context.packageName).also { require(it != 0) { "Missing native schedule resource" } }
        val layout = resource(if (todayWidget) "schedule_widget_today" else "schedule_widget_next", "layout")
        val root = resource("schedule_widget_root", "id")
        val header = resource("schedule_widget_header", "id")
        val headline = resource("schedule_widget_headline", "id")
        val body = resource("schedule_widget_body", "id")
        val footer = resource("schedule_widget_footer", "id")
        val remote = RemoteViews(context.packageName, layout)
        val options = manager.getAppWidgetOptions(id)
        val compact = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180) < 220
        val short = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 120) < 170
        val today = scheduleTodayOccurrences(view.occurrences, view.nowMillis)
        val next = scheduleNextOccurrence(view.occurrences, view.nowMillis)
        val ready = view.publication?.snapshot != null && view.status in setOf(ScheduleNativeStatus.READY, ScheduleNativeStatus.CACHED, ScheduleNativeStatus.NEEDS_LOGIN)
        var title = when (view.status) {
            ScheduleNativeStatus.UNAUTHENTICATED -> NativeSchedule.LOGIN_REQUIRED
            ScheduleNativeStatus.UNBOUND -> NativeSchedule.UNBOUND_TITLE
            ScheduleNativeStatus.NOT_SYNCED -> NativeSchedule.NOT_SYNCED_TITLE
            ScheduleNativeStatus.NEEDS_LOGIN -> NativeSchedule.NEEDS_LOGIN_TITLE
            else -> if (!ready) NativeSchedule.NOT_SYNCED_TITLE else if (todayWidget) NativeSchedule.todayCourseCount(today.size) else next?.title ?: NativeSchedule.NO_NEXT_COURSE
        }
        var description = when (view.status) {
            ScheduleNativeStatus.UNAUTHENTICATED -> NativeSchedule.LOGIN_HINT
            ScheduleNativeStatus.UNBOUND -> NativeSchedule.UNBOUND_HINT
            ScheduleNativeStatus.NOT_SYNCED -> NativeSchedule.SYNC_HINT
            ScheduleNativeStatus.NEEDS_LOGIN -> NativeSchedule.NEEDS_LOGIN_HINT
            else -> NativeSchedule.OPEN_TIMETABLE_HINT
        }
        if (ready) {
            if (todayWidget) {
                val count = if (compact || short) 1 else 2
                if (today.isEmpty()) {
                    title = if (view.uncertainCount > 0) NativeSchedule.UNCONFIRMED_TITLE else NativeSchedule.TODAY_EMPTY
                    description = if (view.uncertainCount > 0) NativeSchedule.missingDates(view.uncertainCount) else next?.let {
                        NativeSchedule.nextSummary(it.date, clock(it.startsAtMillis), it.title)
                    } ?: NativeSchedule.NO_NEXT_COURSE
                } else {
                    description = today.take(count).joinToString("\n") {
                        NativeSchedule.todayCourseLine(clock(it.startsAtMillis), clock(it.endsAtMillis), it.title, it.location)
                    }
                    if (today.size > count) description += NativeSchedule.moreToday(today.size - count)
                }
            } else if (next != null) {
                title = next.title
                description = NativeSchedule.nextCourseTimes(next.date, clock(next.startsAtMillis), clock(next.endsAtMillis), next.location)
            } else {
                title = if (view.uncertainCount > 0) NativeSchedule.UNCONFIRMED_TITLE else NativeSchedule.NO_NEXT_COURSE
                description = if (view.uncertainCount > 0) NativeSchedule.unconfirmed(view.uncertainCount) else NativeSchedule.NEXT_EXCLUDES_STARTED
            }
        }
        val synced = view.publication?.snapshot?.syncedAtMillis?.takeIf { it > 0 }
        val notes = buildList {
            if (view.status == ScheduleNativeStatus.NEEDS_LOGIN) add(NativeSchedule.NEEDS_APP_BADGE) else if (view.stale) add(NativeSchedule.OLD_CACHE_BADGE)
            synced?.let { add(NativeSchedule.updatedAt(stamp(it))) }
            if (ready && view.uncertainCount > 0 && today.isNotEmpty()) add(NativeSchedule.pendingBadge(view.uncertainCount))
        }.joinToString(" · ").ifBlank { NativeSchedule.LOCAL_CACHE_HINT }
        remote.setTextViewText(header, NativeSchedule.widgetHeader(todayWidget, scheduleSchoolDate(view.nowMillis).toString()))
        remote.setTextViewText(headline, title)
        remote.setTextViewText(body, description)
        remote.setTextViewText(footer, notes)
        remote.setTextViewTextSize(headline, TypedValue.COMPLEX_UNIT_SP, if (compact) 15f else 17f)
        remote.setOnClickPendingIntent(root, ScheduleNativeEngine.from(context).openIntent(view.ownerRevision, if (!todayWidget && ready) next?.id else null))
        remote.setContentDescription(root, NativeSchedule.widgetContentDescription(title, description, notes))
        return remote
    }
}
