package cn.edu.bjtu.mis.widget

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import cn.edu.bjtu.mis.BjtuMisApplication
import cn.edu.bjtu.mis.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class TimetableWidgetRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsService.RemoteViewsFactory =
        WidgetListFactory(
            context = applicationContext,
            kind = intent.getStringExtra(EXTRA_KIND) ?: KIND_TODAY_COURSES,
        )

    companion object {
        const val EXTRA_KIND = "cn.edu.bjtu.mis.widget.EXTRA_KIND"
        const val KIND_CALENDAR = "calendar"
        const val KIND_TODAY_COURSES = "today_courses"

        private val stripeColors = intArrayOf(
            0xFF8B5CF6.toInt(),
            0xFF2563EB.toInt(),
            0xFFEF4444.toInt(),
            0xFF0F766E.toInt(),
            0xFFF97316.toInt(),
            0xFF16A34A.toInt(),
        )

        private fun stripeColor(key: String): Int {
            val index = (key.hashCode() and Int.MAX_VALUE) % stripeColors.size
            return stripeColors[index]
        }
    }

    private class WidgetListFactory(
        private val context: Context,
        private val kind: String,
    ) : RemoteViewsService.RemoteViewsFactory {
        private var calendarEvents: List<TimetableWidgetCalendarEvent> = emptyList()
        private var todayCourses: List<TimetableWidgetCourse> = emptyList()

        override fun onCreate() = Unit

        override fun onDataSetChanged() {
            val model = runBlocking(Dispatchers.IO) {
                val app = context.applicationContext as? BjtuMisApplication
                if (app == null) {
                    TimetableWidgetMapper.empty()
                } else {
                    TimetableWidgetDataSource(app.container.database.dao()).load()
                }
            }
            calendarEvents = model.calendarEvents
            todayCourses = model.today.courses
        }

        override fun onDestroy() {
            calendarEvents = emptyList()
            todayCourses = emptyList()
        }

        override fun getCount(): Int =
            if (kind == KIND_CALENDAR) calendarEvents.size else todayCourses.size

        override fun getViewAt(position: Int): RemoteViews? =
            if (kind == KIND_CALENDAR) {
                calendarEvents.getOrNull(position)?.let(::calendarEventView)
            } else {
                todayCourses.getOrNull(position)?.let(::courseView)
            }

        override fun getLoadingView(): RemoteViews? = null

        override fun getViewTypeCount(): Int = 1

        override fun getItemId(position: Int): Long = position.toLong()

        override fun hasStableIds(): Boolean = false

        private fun calendarEventView(event: TimetableWidgetCalendarEvent): RemoteViews =
            RemoteViews(context.packageName, R.layout.timetable_widget_calendar_item).apply {
                val mutedColor = 0xFF6B7280.toInt()
                val titleColor = if (event.placeholder) mutedColor else 0xFF111827.toInt()
                val stripeColor = if (event.placeholder) 0xFF94A3B8.toInt() else 0xFF2563EB.toInt()
                setInt(R.id.timetable_widget_calendar_item_bar, "setBackgroundColor", stripeColor)
                setTextViewText(R.id.timetable_widget_calendar_item_title, event.title)
                setTextColor(R.id.timetable_widget_calendar_item_title, titleColor)
                setTextViewText(
                    R.id.timetable_widget_calendar_item_date,
                    "${event.dayLabel} ${event.dateLabel} ${event.weekdayLabel}",
                )
                setTextViewText(R.id.timetable_widget_calendar_item_detail, event.detail)
                setViewVisibility(
                    R.id.timetable_widget_calendar_item_detail,
                    if (event.detail.isBlank()) View.GONE else View.VISIBLE,
                )
            }

        private fun courseView(course: TimetableWidgetCourse): RemoteViews =
            RemoteViews(context.packageName, R.layout.timetable_widget_course_item).apply {
                setInt(R.id.timetable_widget_course_item_bar, "setBackgroundColor", stripeColor(course.title))
                setTextViewText(R.id.timetable_widget_course_item_title, course.title)
                setTextViewText(R.id.timetable_widget_course_item_time, course.timeLabel)
                setTextViewText(R.id.timetable_widget_course_item_detail, course.detail)
            }
    }
}
