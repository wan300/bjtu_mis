package cn.edu.bjtu.mis.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import cn.edu.bjtu.mis.BjtuMisApplication
import cn.edu.bjtu.mis.MainActivity
import cn.edu.bjtu.mis.R
import cn.edu.bjtu.mis.model.ModuleKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TimetableWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runCatching { updateWidgetIds(context.applicationContext, appWidgetManager, appWidgetIds) }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private val stripeColors = intArrayOf(
            0xFF8B5CF6.toInt(),
            0xFF2563EB.toInt(),
            0xFFEF4444.toInt(),
            0xFF0F766E.toInt(),
            0xFFF97316.toInt(),
            0xFF16A34A.toInt(),
        )

        fun refreshAll(context: Context) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching { updateAllNow(context.applicationContext) }
            }
        }

        suspend fun updateAllNow(context: Context) {
            val appContext = context.applicationContext
            val appWidgetManager = AppWidgetManager.getInstance(appContext)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(ComponentName(appContext, TimetableWidgetProvider::class.java))
            updateWidgetIds(appContext, appWidgetManager, appWidgetIds)
        }

        fun requestPin(context: Context): Boolean {
            val appContext = context.applicationContext
            refreshAll(appContext)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false

            val appWidgetManager = AppWidgetManager.getInstance(appContext)
            if (!appWidgetManager.isRequestPinAppWidgetSupported) return false

            val provider = ComponentName(appContext, TimetableWidgetProvider::class.java)
            appWidgetManager.requestPinAppWidget(provider, null, null)
            return true
        }

        private suspend fun updateWidgetIds(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray,
        ) {
            if (appWidgetIds.isEmpty()) return
            val model = loadModel(context)
            appWidgetIds.forEach { appWidgetId ->
                appWidgetManager.updateAppWidget(appWidgetId, buildRemoteViews(context, model))
            }
        }

        private suspend fun loadModel(context: Context): TimetableWidgetModel =
            withContext(Dispatchers.IO) {
                val app = context.applicationContext as? BjtuMisApplication
                    ?: return@withContext TimetableWidgetMapper.empty()
                TimetableWidgetDataSource(app.container.database.dao()).load()
            }

        private fun buildRemoteViews(context: Context, model: TimetableWidgetModel): RemoteViews =
            RemoteViews(context.packageName, R.layout.timetable_widget).apply {
                setTextViewText(R.id.timetable_widget_title, model.title)
                setTextViewText(R.id.timetable_widget_meta, model.meta)
                setTextViewText(R.id.timetable_widget_today_label, model.today.label)
                setTextViewText(R.id.timetable_widget_today_date, "${model.today.dateLabel} ${model.today.weekdayLabel}")
                setTextViewText(R.id.timetable_widget_tomorrow_label, model.tomorrow.label)
                setTextViewText(R.id.timetable_widget_tomorrow_date, "${model.tomorrow.dateLabel} ${model.tomorrow.weekdayLabel}")

                bindDay(
                    views = this,
                    day = model.today,
                    emptyId = R.id.timetable_widget_today_empty,
                    rows = listOf(
                        CourseRowIds(R.id.timetable_widget_today_course_1, R.id.timetable_widget_today_course_1_bar, R.id.timetable_widget_today_course_1_title, R.id.timetable_widget_today_course_1_detail),
                        CourseRowIds(R.id.timetable_widget_today_course_2, R.id.timetable_widget_today_course_2_bar, R.id.timetable_widget_today_course_2_title, R.id.timetable_widget_today_course_2_detail),
                        CourseRowIds(R.id.timetable_widget_today_course_3, R.id.timetable_widget_today_course_3_bar, R.id.timetable_widget_today_course_3_title, R.id.timetable_widget_today_course_3_detail),
                    ),
                )
                bindDay(
                    views = this,
                    day = model.tomorrow,
                    emptyId = R.id.timetable_widget_tomorrow_empty,
                    rows = listOf(
                        CourseRowIds(R.id.timetable_widget_tomorrow_course_1, R.id.timetable_widget_tomorrow_course_1_bar, R.id.timetable_widget_tomorrow_course_1_title, R.id.timetable_widget_tomorrow_course_1_detail),
                        CourseRowIds(R.id.timetable_widget_tomorrow_course_2, R.id.timetable_widget_tomorrow_course_2_bar, R.id.timetable_widget_tomorrow_course_2_title, R.id.timetable_widget_tomorrow_course_2_detail),
                        CourseRowIds(R.id.timetable_widget_tomorrow_course_3, R.id.timetable_widget_tomorrow_course_3_bar, R.id.timetable_widget_tomorrow_course_3_title, R.id.timetable_widget_tomorrow_course_3_detail),
                    ),
                )
                setOnClickPendingIntent(R.id.timetable_widget_root, openHomeworkPendingIntent(context))
            }

        private fun bindDay(
            views: RemoteViews,
            day: TimetableWidgetDay,
            emptyId: Int,
            rows: List<CourseRowIds>,
        ) {
            views.setViewVisibility(emptyId, if (day.courses.isEmpty()) View.VISIBLE else View.GONE)
            rows.forEachIndexed { index, ids ->
                val course = day.courses.getOrNull(index)
                views.setViewVisibility(ids.container, if (course == null) View.GONE else View.VISIBLE)
                if (course != null) {
                    views.setInt(ids.bar, "setBackgroundColor", stripeColor(course.title))
                    views.setTextViewText(ids.title, course.title)
                    views.setTextViewText(ids.detail, course.detail)
                }
            }
        }

        private fun stripeColor(key: String): Int {
            val index = (key.hashCode() and Int.MAX_VALUE) % stripeColors.size
            return stripeColors[index]
        }

        private fun openHomeworkPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_ROUTE, ModuleKeys.Timetable)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            return PendingIntent.getActivity(
                context,
                31001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private data class CourseRowIds(
            val container: Int,
            val bar: Int,
            val title: Int,
            val detail: Int,
        )
    }
}
