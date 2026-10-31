package io.github.betterzslf.schedule.vendor

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import io.github.betterzslf.core.text.AppText.Schedule
import io.github.betterzslf.feature.schedule.ScheduleLiveClock
import kotlin.math.roundToInt

/**
 * Builds the expanded card that every vendor path shows.
 *
 * One implementation instead of one per ROM: the layout, the wording, the phase colours and the
 * progress bar behave identically whatever draws the card, and only the surrounding notification
 * differs (Xiaomi's JSON island, vivo's superx bundle, Flyme's live extras, or the plain AOSP
 * card). Keeping it in one place is what makes "same as Meizu" true by construction rather than by
 * four parallel edits that drift apart.
 *
 * What the card shows, in both phases:
 * - the course name, with the bare status on the right in the phase's accent colour;
 * - a progress bar: elapsed time while in class, consumed break while between classes;
 * - three columns -- teacher / room / period in class, and start time instead of the period
 *   between classes.
 *
 * Colour carries the phase: blue while a class runs, amber between classes. A missing value leaves
 * its column blank rather than printing a placeholder, so a course with no room recorded simply has
 * nothing in that slot.
 */
internal object LiveCard {

    const val PROGRESS_MAX = 100
    /** Blue while a class runs. */
    val ACCENT_IN_CLASS: Int = 0xFF4C8DFF.toInt()
    /** Amber during a break; a break is not an alarm, so not red. */
    val ACCENT_BREAK: Int = 0xFFFFB020.toInt()

    /** A blank course name would leave the card's headline empty, so it is never passed through. */
    fun build(context: Context, data: VendorLiveData): RemoteViews? {
        val ids = Ids.resolve(context) ?: return null
        return RemoteViews(context.packageName, ids.layout).apply {
            setTextViewText(ids.title, data.courseName.ifBlank { data.title })
            setTextViewText(ids.status, data.status)

            val inClass = !data.betweenClasses
            setTextColor(ids.status, if (inClass) ACCENT_IN_CLASS else ACCENT_BREAK)

            val progress = data.progress?.coerceIn(0f, 1f)
            if (progress == null) {
                // No measurable fraction: hide the bar rather than draw one that means nothing.
                setViewVisibility(ids.progressInClass, View.GONE)
                setViewVisibility(ids.progressBreak, View.GONE)
            } else {
                // Exactly one bar is visible. Two views exist because ProgressBar.setProgressTintList
                // is not a @RemotableViewMethod, so a host drops it; the colour has to be fixed at
                // inflate time, and two bars is the only way to have two colours.
                val bar = if (inClass) ids.progressInClass else ids.progressBreak
                setViewVisibility(ids.progressInClass, if (inClass) View.VISIBLE else View.GONE)
                setViewVisibility(ids.progressBreak, if (inClass) View.GONE else View.VISIBLE)
                // setProgress is annotated @RemotableViewMethod; RemoteViews.setProgressBar is not,
                // which is why the value is pushed as a plain setInt.
                setInt(bar, "setProgress", (progress * PROGRESS_MAX).roundToInt())
            }

            val columns = listOf(
                Schedule.LIVE_FIELD_TEACHER to data.teacher,
                Schedule.LIVE_FIELD_ROOM to data.room,
                (if (inClass) Schedule.LIVE_FIELD_PERIOD else Schedule.LIVE_FIELD_START) to
                    if (inClass) data.periodText else ScheduleLiveClock.hhmm(data.startsAtMillis),
            )
            val cells = listOf(
                Triple(ids.label1, ids.value1, columns[0]),
                Triple(ids.label2, ids.value2, columns[1]),
                Triple(ids.label3, ids.value3, columns[2]),
            )
            cells.forEach { (labelId, valueId, pair) ->
                val (label, value) = pair
                // setTextViewText resets visibility, so writing an empty string is enough to blank a
                // column. No separate GONE is needed, and leaving the label visible with no value
                // would read as a value that failed to load.
                setTextViewText(labelId, if (value.isBlank()) "" else label)
                setTextViewText(valueId, value)
            }
        }
    }

    /**
     * Text colour to hand the ROM for the expanded card.
     *
     * Black on a light surface, white on a dark one, decided from the device's night mode. The card
     * has no background of its own -- the ROM paints it, and Flyme paints it from the wallpaper --
     * so "which colour is readable" cannot be answered from our layout. Night mode is the best
     * available proxy, and it is the same one the values-night resources use, so the two agree.
     */
    fun contentColor(context: Context): Int {
        val nightMode = context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return if (nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
            0xFFFFFFFF.toInt()
        } else {
            0xFF1A1A1E.toInt()
        }
    }

    /** The compact status-bar text: the short chip, or the course name plus it when asked. */
    fun capsuleText(data: VendorLiveData): String {
        val short = data.chip.trim().ifEmpty { Schedule.VENDOR_MEIZU_CAPSULE_FALLBACK }
        if (!data.capsuleLongText) return short
        val course = data.courseName.ifBlank { data.title }.trim()
        return if (course.isBlank()) short else Schedule.liveCapsulePhrase(course, short)
    }

    /** Layout and view ids, resolved once per post. Null when a resource is missing. */
    private class Ids(
        val layout: Int,
        val title: Int,
        val status: Int,
        val progressInClass: Int,
        val progressBreak: Int,
        val label1: Int, val value1: Int,
        val label2: Int, val value2: Int,
        val label3: Int, val value3: Int,
    ) {
        companion object {
            /**
             * Resolves every id, returning null if any is absent.
             *
             * A RemoteViews the host cannot inflate renders as a blank card, which is
             * indistinguishable from "the ROM ignored the payload". Failing here instead lets the
             * caller fall back to a plain notification.
             */
            fun resolve(context: Context): Ids? {
                fun id(name: String): Int = context.resources.getIdentifier(name, "id", context.packageName)
                val layout = context.resources.getIdentifier("schedule_live_card", "layout", context.packageName)
                val ids = Ids(
                    layout,
                    id("schedule_live_title"), id("schedule_live_status"),
                    id("schedule_live_progress"), id("schedule_live_progress_break"),
                    id("schedule_live_label_1"), id("schedule_live_value_1"),
                    id("schedule_live_label_2"), id("schedule_live_value_2"),
                    id("schedule_live_label_3"), id("schedule_live_value_3"),
                )
                if (layout == 0) return null
                val all = listOf(ids.title, ids.status, ids.progressInClass, ids.progressBreak,
                    ids.label1, ids.value1, ids.label2, ids.value2, ids.label3, ids.value3)
                return if (all.any { it == 0 }) null else ids
            }
        }
    }
}
