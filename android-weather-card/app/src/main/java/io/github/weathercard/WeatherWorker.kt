package io.github.weathercard

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 拉取所有卡片的天气；多个卡片使用同一城市时只请求一次。 */
class WeatherWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val widgetIds = WeatherWidgetProvider.allWidgetIds(context)
        if (widgetIds.isEmpty()) return Result.success()

        val prefs = Prefs(context)
        val manager = AppWidgetManager.getInstance(context)
        var failed = false

        widgetIds.groupBy { prefs.getPlace(it) }.forEach { (place, ids) ->
            try {
                val json = withContext(Dispatchers.IO) {
                    WeatherApi.fetchForecastJson(place.latitude, place.longitude)
                }
                val now = System.currentTimeMillis()
                WeatherApi.parseForecast(json, now) // 解析失败时视为本次更新失败，保留旧数据
                ids.forEach { prefs.saveWeather(it, json, now) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                ids.forEach { prefs.markError(it) }
            }
            ids.forEach { WidgetRenderer.update(context, manager, it) }
        }

        return if (failed && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
    }

    private companion object {
        const val MAX_RETRIES = 3
    }
}
