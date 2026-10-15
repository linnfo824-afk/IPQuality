package io.github.weathercard

import android.content.Context
import org.json.JSONObject

/** 每个小部件各自保存城市和最近一次的天气数据。 */
class Prefs(context: Context) {

    private val appContext = context.applicationContext
    private val sp = appContext.getSharedPreferences("weather_card", Context.MODE_PRIVATE)

    fun getPlace(widgetId: Int): Place = readPlace("place_$widgetId") ?: getDefaultPlace()

    fun setPlace(widgetId: Int, place: Place) = writePlace("place_$widgetId", place)

    /** 最近一次选择的城市，新添加的卡片默认使用它。 */
    fun getDefaultPlace(): Place =
        readPlace(KEY_DEFAULT_PLACE) ?: Place(appContext.getString(R.string.default_city), 39.9042, 116.4074)

    fun setDefaultPlace(place: Place) = writePlace(KEY_DEFAULT_PLACE, place)

    fun getWeather(widgetId: Int): Pair<String, Long>? {
        val json = sp.getString("weather_$widgetId", null) ?: return null
        return json to sp.getLong("weather_time_$widgetId", 0L)
    }

    fun saveWeather(widgetId: Int, json: String, time: Long) {
        sp.edit()
            .putString("weather_$widgetId", json)
            .putLong("weather_time_$widgetId", time)
            .remove("error_$widgetId")
            .apply()
    }

    fun markError(widgetId: Int) = sp.edit().putBoolean("error_$widgetId", true).apply()

    fun hasError(widgetId: Int): Boolean = sp.getBoolean("error_$widgetId", false)

    fun isStale(widgetId: Int, maxAgeMillis: Long): Boolean =
        System.currentTimeMillis() - sp.getLong("weather_time_$widgetId", 0L) > maxAgeMillis

    fun clearWeather(widgetId: Int) {
        sp.edit()
            .remove("weather_$widgetId")
            .remove("weather_time_$widgetId")
            .remove("error_$widgetId")
            .apply()
    }

    fun remove(widgetId: Int) {
        clearWeather(widgetId)
        sp.edit().remove("place_$widgetId").apply()
    }

    private fun readPlace(key: String): Place? {
        val raw = sp.getString(key, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            Place(json.getString("name"), json.getDouble("lat"), json.getDouble("lon"))
        }.getOrNull()
    }

    private fun writePlace(key: String, place: Place) {
        val json = JSONObject()
            .put("name", place.name)
            .put("lat", place.latitude)
            .put("lon", place.longitude)
        sp.edit().putString(key, json.toString()).apply()
    }

    private companion object {
        const val KEY_DEFAULT_PLACE = "place_default"
    }
}
