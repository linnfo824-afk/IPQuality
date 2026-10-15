package io.github.weathercard

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import java.text.DateFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** 把缓存的天气数据渲染成桌面卡片，按卡片尺寸选择小 / 宽 / 大三种布局。 */
object WidgetRenderer {

    private enum class Size(val layout: Int, val showForecast: Boolean) {
        SMALL(R.layout.widget_small, false),
        WIDE(R.layout.widget_wide, false),
        LARGE(R.layout.widget_wide, true),
    }

    private class State(val place: Place, val weather: Weather?, val failed: Boolean)

    private val forecastDays = intArrayOf(R.id.day0, R.id.day1, R.id.day2, R.id.day3, R.id.day4)
    private val forecastIcons = intArrayOf(R.id.icon0, R.id.icon1, R.id.icon2, R.id.icon3, R.id.icon4)
    private val forecastTemps = intArrayOf(R.id.temp0, R.id.temp1, R.id.temp2, R.id.temp3, R.id.temp4)
    private val forecastColumns = intArrayOf(R.id.col0, R.id.col1, R.id.col2, R.id.col3, R.id.col4)

    fun update(context: Context, manager: AppWidgetManager, widgetId: Int, loading: Boolean = false) {
        val state = loadState(context, widgetId)
        val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+：系统会按卡片当前尺寸自动挑选能放下的最大布局
            RemoteViews(
                mapOf(
                    SizeF(100f, 90f) to build(context, widgetId, state, Size.SMALL, loading),
                    SizeF(220f, 90f) to build(context, widgetId, state, Size.WIDE, loading),
                    SizeF(220f, 170f) to build(context, widgetId, state, Size.LARGE, loading),
                ),
            )
        } else {
            val size = sizeFromOptions(manager.getAppWidgetOptions(widgetId))
            build(context, widgetId, state, size, loading)
        }
        manager.updateAppWidget(widgetId, views)
    }

    private fun sizeFromOptions(options: Bundle): Size {
        // 竖屏时卡片宽度取 MIN_WIDTH、高度取 MAX_HEIGHT
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        return when {
            width == 0 -> Size.WIDE
            width < 220 -> Size.SMALL
            height >= 170 -> Size.LARGE
            else -> Size.WIDE
        }
    }

    private fun loadState(context: Context, widgetId: Int): State {
        val prefs = Prefs(context)
        val weather = prefs.getWeather(widgetId)?.let { (json, time) ->
            runCatching { WeatherApi.parseForecast(json, time) }.getOrNull()
        }
        return State(prefs.getPlace(widgetId), weather, prefs.hasError(widgetId))
    }

    private fun build(context: Context, widgetId: Int, state: State, size: Size, loading: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, size.layout)
        views.setOnClickPendingIntent(android.R.id.background, refreshIntent(context))
        views.setOnClickPendingIntent(R.id.city, configIntent(context, widgetId))
        views.setTextViewText(R.id.city, state.place.name)
        views.setTextViewText(R.id.status, statusText(context, state, loading))

        val weather = state.weather
        if (weather == null) {
            views.setImageViewResource(R.id.bg, R.drawable.bg_cloudy)
            views.setTextViewText(R.id.icon, "🌡️")
            views.setTextViewText(R.id.temp, "--°")
            val message = if (state.failed && !loading) R.string.load_failed else R.string.loading
            views.setTextViewText(R.id.desc, context.getString(message))
            views.setTextViewText(R.id.high_low, "")
            if (size != Size.SMALL) {
                views.setTextViewText(R.id.details, "")
                views.setTextViewText(R.id.wind, "")
                views.setViewVisibility(R.id.forecast, View.GONE)
            }
            return views
        }

        views.setImageViewResource(R.id.bg, WeatherCodes.background(weather.weatherCode, weather.isDay))
        views.setTextViewText(R.id.icon, WeatherCodes.emoji(weather.weatherCode, weather.isDay))
        views.setTextViewText(R.id.temp, formatTemp(weather.temperature))
        views.setTextViewText(R.id.desc, context.getString(WeatherCodes.description(weather.weatherCode)))
        val today = weather.daily.firstOrNull()
        views.setTextViewText(
            R.id.high_low,
            today?.let { context.getString(R.string.high_low, formatTemp(it.tempMax), formatTemp(it.tempMin)) } ?: "",
        )
        if (size == Size.SMALL) return views

        views.setTextViewText(
            R.id.details,
            context.getString(R.string.feels_like_humidity, formatTemp(weather.apparentTemperature), weather.humidity),
        )
        views.setTextViewText(R.id.wind, windText(context, weather, today?.precipitationProbability))

        if (size.showForecast && weather.daily.isNotEmpty()) {
            views.setViewVisibility(R.id.forecast, View.VISIBLE)
            forecastColumns.indices.forEach { i ->
                val day = weather.daily.getOrNull(i)
                if (day == null) {
                    views.setViewVisibility(forecastColumns[i], View.GONE)
                    return@forEach
                }
                views.setViewVisibility(forecastColumns[i], View.VISIBLE)
                views.setTextViewText(forecastDays[i], dayLabel(context, day.date, i))
                views.setTextViewText(forecastIcons[i], WeatherCodes.emoji(day.weatherCode, isDay = true))
                views.setTextViewText(
                    forecastTemps[i],
                    context.getString(R.string.forecast_range, formatTemp(day.tempMax), formatTemp(day.tempMin)),
                )
            }
        } else {
            views.setViewVisibility(R.id.forecast, View.GONE)
        }
        return views
    }

    private fun statusText(context: Context, state: State, loading: Boolean): String {
        if (loading) return context.getString(R.string.updating)
        val weather = state.weather ?: return ""
        val time = DateUtils.formatSameDayTime(
            weather.fetchedAt,
            System.currentTimeMillis(),
            DateFormat.SHORT,
            DateFormat.SHORT,
        ).toString()
        return context.getString(if (state.failed) R.string.update_failed_at else R.string.updated_at, time)
    }

    private fun windText(context: Context, weather: Weather, precipitation: Int?): String {
        val direction = context.resources.getStringArray(R.array.wind_directions)[
            WeatherCodes.directionIndex(weather.windDirection),
        ]
        val wind = context.getString(
            R.string.wind_format,
            direction,
            weather.windSpeed.roundToInt(),
            WeatherCodes.beaufort(weather.windSpeed),
        )
        return if (precipitation == null) wind else context.getString(R.string.wind_and_rain, wind, precipitation)
    }

    private fun dayLabel(context: Context, date: String, index: Int): String = when (index) {
        0 -> context.getString(R.string.today)
        1 -> context.getString(R.string.tomorrow)
        else -> runCatching {
            LocalDate.parse(date).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        }.getOrDefault("")
    }

    private fun formatTemp(value: Double): String = "${value.roundToInt()}°"

    private fun refreshIntent(context: Context): PendingIntent {
        val intent = Intent(context, WeatherWidgetProvider::class.java)
            .setAction(WeatherWidgetProvider.ACTION_REFRESH)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun configIntent(context: Context, widgetId: Int): PendingIntent {
        val intent = Intent(context, ConfigActivity::class.java)
            .setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return PendingIntent.getActivity(
            context,
            widgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
