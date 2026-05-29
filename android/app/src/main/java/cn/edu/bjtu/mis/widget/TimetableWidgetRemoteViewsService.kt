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
        const val KIND_TODAY_CALENDAR = "today_calendar"
        const val KIND_TOMORROW_CALENDAR = "tomorrow_calendar"
        const val KIND_TODAY_COURSES = "today_courses"
        const val KIND_TOMORROW_COURSES = "tomorrow_courses"

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
        private var todayCalendarEvents: List<TimetableWidgetCalendarEvent> = emptyList()
        private var tomorrowCalendarEvents: List<TimetableWidgetCalendarEvent> = emptyList()
        private var todayCourses: List<TimetableWidgetCourse> = emptyList()
        private var tomorrowCourses: List<TimetableWidgetCourse> = emptyList()

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
            todayCalendarEvents = model.calendarEvents.filter { it.dayLabel == model.today.label }
            tomorrowCalendarEvents = model.calendarEvents.filter { it.dayLabel == model.tomorrow.label }
            todayCourses = model.today.courses
            tomorrowCourses = model.tomorrow.courses
        }

        override fun onDestroy() {
            calendarEvents = emptyList()
            todayCalendarEvents = emptyList()
            tomorrowCalendarEvents = emptyList()
            todayCourses = emptyList()
            tomorrowCourses = emptyList()
        }

        override fun getCount(): Int =
            when (kind) {
                KIND_CALENDAR -> calendarEvents.size
                KIND_TODAY_CALENDAR -> todayCalendarEvents.size
                KIND_TOMORROW_CALENDAR -> tomorrowCalendarEvents.size
                KIND_TOMORROW_COURSES -> tomorrowCourses.size
                else -> todayCourses.size
            }

        override fun getViewAt(position: Int): RemoteViews? =
            when (kind) {
                KIND_CALENDAR -> calendarEvents.getOrNull(position)?.let(::calendarEventView)
                KIND_TODAY_CALENDAR -> todayCalendarEvents.getOrNull(position)?.let(::calendarEventView)
                KIND_TOMORROW_CALENDAR -> tomorrowCalendarEvents.getOrNull(position)?.let(::calendarEventView)
                KIND_TOMORROW_COURSES -> tomorrowCourses.getOrNull(position)?.let(::courseView)
                else -> todayCourses.getOrNull(position)?.let(::courseView)
            }

        override fun getLoadingView(): RemoteViews? = null

        override fun getViewTypeCount(): Int = 1

        override fun getItemId(position: Int): Long = position.toLong()

        override fun hasStableIds(): Boolean = false

        private fun calendarEventView(event: TimetableWidgetCalendarEvent): RemoteViews =
            RemoteViews(context.packageName, R.layout.timetable_widget_calendar_item).apply {
                setInt(R.id.timetable_widget_calendar_item_bar, "setBackgroundColor", 0xFF2563EB.toInt())
                setTextViewText(R.id.timetable_widget_calendar_item_title, event.title)
                setTextColor(R.id.timetable_widget_calendar_item_title, 0xFF111827.toInt())
                setTextViewText(
                    R.id.timetable_widget_calendar_item_date,
                    "${event.dayLabel} ${event.dateLabel} ${event.weekdayLabel}",
                )
                setTextViewText(R.id.timetable_widget_calendar_item_detail, event.detail)
                setViewVisibility(
                    R.id.timetable_widget_calendar_item_detail,
                    if (event.detail.isBlank()) View.GONE else View.VISIBLE,
                )
                setOnClickFillInIntent(R.id.timetable_widget_calendar_item_root, Intent())
            }

        private fun courseView(course: TimetableWidgetCourse): RemoteViews =
            RemoteViews(context.packageName, R.layout.timetable_widget_course_item).apply {
                setInt(R.id.timetable_widget_course_item_bar, "setBackgroundColor", stripeColor(course.title))
                setTextViewText(R.id.timetable_widget_course_item_title, course.title)
                setTextViewText(R.id.timetable_widget_course_item_time, course.timeLabel)
                setTextViewText(R.id.timetable_widget_course_item_detail, course.detail)
                setOnClickFillInIntent(R.id.timetable_widget_course_item_root, Intent())
            }
    }
}
