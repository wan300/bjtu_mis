package cn.edu.bjtu.mis.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
                appWidgetManager.updateAppWidget(appWidgetId, buildRemoteViews(context, model, appWidgetId))
            }
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.timetable_widget_today_calendar_list)
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.timetable_widget_tomorrow_calendar_list)
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.timetable_widget_today_course_list)
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.timetable_widget_tomorrow_course_list)
        }

        private suspend fun loadModel(context: Context): TimetableWidgetModel =
            withContext(Dispatchers.IO) {
                val app = context.applicationContext as? BjtuMisApplication
                    ?: return@withContext TimetableWidgetMapper.empty()
                TimetableWidgetDataSource(
                    dao = app.container.database.dao(),
                    employmentCalendarSyncStore = app.container.employmentCalendarSyncStore,
                ).load()
            }

        private fun buildRemoteViews(
            context: Context,
            model: TimetableWidgetModel,
            appWidgetId: Int,
        ): RemoteViews =
            RemoteViews(context.packageName, R.layout.timetable_widget).apply {
                val calendarPendingIntent = openRoutePendingIntent(
                    context = context,
                    route = ModuleKeys.Calendar,
                    requestCode = REQUEST_OPEN_CALENDAR,
                )
                val timetablePendingIntent = openRoutePendingIntent(
                    context = context,
                    route = ModuleKeys.Timetable,
                    requestCode = REQUEST_OPEN_TIMETABLE,
                )
                val calendarItemPendingIntent = openRoutePendingIntent(
                    context = context,
                    route = ModuleKeys.Calendar,
                    requestCode = REQUEST_OPEN_CALENDAR_ITEM,
                    mutable = true,
                )
                val timetableItemPendingIntent = openRoutePendingIntent(
                    context = context,
                    route = ModuleKeys.Timetable,
                    requestCode = REQUEST_OPEN_TIMETABLE_ITEM,
                    mutable = true,
                )

                setTextViewText(R.id.timetable_widget_title, model.title)
                setTextViewText(R.id.timetable_widget_meta, model.meta)
                setTextViewText(R.id.timetable_widget_today_calendar_label, model.today.label)
                setTextViewText(R.id.timetable_widget_today_calendar_date, "${model.today.dateLabel} ${model.today.weekdayLabel}")
                setTextViewText(R.id.timetable_widget_tomorrow_calendar_label, model.tomorrow.label)
                setTextViewText(R.id.timetable_widget_tomorrow_calendar_date, "${model.tomorrow.dateLabel} ${model.tomorrow.weekdayLabel}")

                setRemoteAdapter(
                    R.id.timetable_widget_today_calendar_list,
                    serviceIntent(context, appWidgetId, TimetableWidgetRemoteViewsService.KIND_TODAY_CALENDAR),
                )
                setPendingIntentTemplate(R.id.timetable_widget_today_calendar_list, calendarItemPendingIntent)
                setRemoteAdapter(
                    R.id.timetable_widget_tomorrow_calendar_list,
                    serviceIntent(context, appWidgetId, TimetableWidgetRemoteViewsService.KIND_TOMORROW_CALENDAR),
                )
                setPendingIntentTemplate(R.id.timetable_widget_tomorrow_calendar_list, calendarItemPendingIntent)
                setRemoteAdapter(
                    R.id.timetable_widget_today_course_list,
                    serviceIntent(context, appWidgetId, TimetableWidgetRemoteViewsService.KIND_TODAY_COURSES),
                )
                setPendingIntentTemplate(R.id.timetable_widget_today_course_list, timetableItemPendingIntent)
                setEmptyView(R.id.timetable_widget_today_course_list, R.id.timetable_widget_today_course_empty)
                setRemoteAdapter(
                    R.id.timetable_widget_tomorrow_course_list,
                    serviceIntent(context, appWidgetId, TimetableWidgetRemoteViewsService.KIND_TOMORROW_COURSES),
                )
                setPendingIntentTemplate(R.id.timetable_widget_tomorrow_course_list, timetableItemPendingIntent)
                setEmptyView(R.id.timetable_widget_tomorrow_course_list, R.id.timetable_widget_tomorrow_course_empty)

                setOnClickPendingIntent(R.id.timetable_widget_root, calendarPendingIntent)
                setOnClickPendingIntent(R.id.timetable_widget_calendar_title, calendarPendingIntent)
                setOnClickPendingIntent(R.id.timetable_widget_calendar_section, calendarPendingIntent)
                setOnClickPendingIntent(R.id.timetable_widget_course_title, timetablePendingIntent)
                setOnClickPendingIntent(R.id.timetable_widget_course_section, timetablePendingIntent)
                setOnClickPendingIntent(R.id.timetable_widget_today_course_empty, timetablePendingIntent)
                setOnClickPendingIntent(R.id.timetable_widget_tomorrow_course_empty, timetablePendingIntent)
            }

        private fun serviceIntent(
            context: Context,
            appWidgetId: Int,
            kind: String,
        ): Intent =
            Intent(context, TimetableWidgetRemoteViewsService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(TimetableWidgetRemoteViewsService.EXTRA_KIND, kind)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }

        private fun openRoutePendingIntent(
            context: Context,
            route: String,
            requestCode: Int,
            mutable: Boolean = false,
        ): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_ROUTE, route)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val mutabilityFlag = if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or mutabilityFlag,
            )
        }

        private const val REQUEST_OPEN_CALENDAR = 31001
        private const val REQUEST_OPEN_TIMETABLE = 31002
        private const val REQUEST_OPEN_CALENDAR_ITEM = 31003
        private const val REQUEST_OPEN_TIMETABLE_ITEM = 31004
    }
}
