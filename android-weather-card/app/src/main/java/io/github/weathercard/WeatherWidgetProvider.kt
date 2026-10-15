package io.github.weathercard

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class WeatherWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        widgetIds.forEach { WidgetRenderer.update(context, manager, it) }
        schedulePeriodic(context)
        // 只在数据过期时才联网，避免 WorkManager 组件状态变化触发 onUpdate 时反复刷新
        val prefs = Prefs(context)
        if (widgetIds.any { prefs.isStale(it, STALE_AFTER_MILLIS) }) refreshNow(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        newOptions: Bundle,
    ) {
        WidgetRenderer.update(context, manager, widgetId)
    }

    override fun onDeleted(context: Context, widgetIds: IntArray) {
        val prefs = Prefs(context)
        widgetIds.forEach { prefs.remove(it) }
    }

    override fun onEnabled(context: Context) {
        schedulePeriodic(context)
    }

    override fun onDisabled(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(PERIODIC_WORK)
            cancelUniqueWork(REFRESH_WORK)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val manager = AppWidgetManager.getInstance(context)
            allWidgetIds(context).forEach { WidgetRenderer.update(context, manager, it, loading = true) }
            refreshNow(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "io.github.weathercard.action.REFRESH"

        private const val PERIODIC_WORK = "weather_periodic"
        private const val REFRESH_WORK = "weather_refresh"
        private val STALE_AFTER_MILLIS = TimeUnit.MINUTES.toMillis(15)

        fun allWidgetIds(context: Context): IntArray =
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, WeatherWidgetProvider::class.java))

        /** 立即刷新所有卡片。不加网络约束，离线时会很快失败并在卡片上提示。 */
        fun refreshNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<WeatherWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(REFRESH_WORK, ExistingWorkPolicy.REPLACE, request)
        }

        /** 每 30 分钟在有网络时后台刷新一次。 */
        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<WeatherWorker>(30, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
