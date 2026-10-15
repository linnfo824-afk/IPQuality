package io.github.weathercard

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

data class Place(val name: String, val latitude: Double, val longitude: Double)

data class DailyForecast(
    val date: String,
    val weatherCode: Int,
    val tempMax: Double,
    val tempMin: Double,
    val precipitationProbability: Int?,
)

data class Weather(
    val temperature: Double,
    val apparentTemperature: Double,
    val humidity: Int,
    val windSpeed: Double,
    val windDirection: Double,
    val weatherCode: Int,
    val isDay: Boolean,
    val daily: List<DailyForecast>,
    val fetchedAt: Long,
)

enum class Condition { CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, THUNDER }

/** WMO 天气代码（Open-Meteo 使用）到图标、背景和文字描述的映射。 */
object WeatherCodes {

    fun condition(code: Int): Condition = when (code) {
        0 -> Condition.CLEAR
        1, 2 -> Condition.PARTLY_CLOUDY
        3 -> Condition.CLOUDY
        45, 48 -> Condition.FOG
        51, 53, 55, 56, 57 -> Condition.DRIZZLE
        61, 63, 65, 66, 67, 80, 81, 82 -> Condition.RAIN
        71, 73, 75, 77, 85, 86 -> Condition.SNOW
        95, 96, 99 -> Condition.THUNDER
        else -> Condition.CLOUDY
    }

    fun emoji(code: Int, isDay: Boolean): String = when (condition(code)) {
        Condition.CLEAR -> if (isDay) "☀️" else "🌙"
        Condition.PARTLY_CLOUDY -> if (isDay) "⛅" else "☁️"
        Condition.CLOUDY -> "☁️"
        Condition.FOG -> "🌫️"
        Condition.DRIZZLE -> if (isDay) "🌦️" else "🌧️"
        Condition.RAIN -> "🌧️"
        Condition.SNOW -> "🌨️"
        Condition.THUNDER -> "⛈️"
    }

    @DrawableRes
    fun background(code: Int, isDay: Boolean): Int = when (condition(code)) {
        Condition.CLEAR, Condition.PARTLY_CLOUDY -> if (isDay) R.drawable.bg_clear else R.drawable.bg_night
        Condition.CLOUDY, Condition.FOG -> if (isDay) R.drawable.bg_cloudy else R.drawable.bg_night
        Condition.DRIZZLE, Condition.RAIN -> R.drawable.bg_rain
        Condition.SNOW -> R.drawable.bg_snow
        Condition.THUNDER -> R.drawable.bg_storm
    }

    @StringRes
    fun description(code: Int): Int = when (code) {
        0 -> R.string.w_clear
        1 -> R.string.w_mainly_clear
        2 -> R.string.w_partly_cloudy
        3 -> R.string.w_overcast
        45 -> R.string.w_fog
        48 -> R.string.w_rime_fog
        51 -> R.string.w_drizzle_light
        53 -> R.string.w_drizzle
        55 -> R.string.w_drizzle_dense
        56, 57 -> R.string.w_freezing_drizzle
        61 -> R.string.w_rain_light
        63 -> R.string.w_rain
        65 -> R.string.w_rain_heavy
        66, 67 -> R.string.w_freezing_rain
        71 -> R.string.w_snow_light
        73 -> R.string.w_snow
        75 -> R.string.w_snow_heavy
        77 -> R.string.w_snow_grains
        80 -> R.string.w_showers_light
        81 -> R.string.w_showers
        82 -> R.string.w_showers_heavy
        85 -> R.string.w_snow_showers
        86 -> R.string.w_snow_showers_heavy
        95 -> R.string.w_thunderstorm
        96, 99 -> R.string.w_thunderstorm_hail
        else -> R.string.w_unknown
    }

    /** km/h 转换为蒲福风级（0–12）。 */
    fun beaufort(kmh: Double): Int {
        val limits = doubleArrayOf(1.0, 6.0, 12.0, 20.0, 29.0, 39.0, 50.0, 62.0, 75.0, 89.0, 103.0, 118.0)
        val index = limits.indexOfFirst { kmh < it }
        return if (index == -1) 12 else index
    }

    /** 风向角度转换为 8 方位索引：0=北，1=东北，…，7=西北。 */
    fun directionIndex(degrees: Double): Int {
        val normalized = ((degrees % 360) + 360) % 360
        return ((normalized + 22.5) / 45).toInt() % 8
    }
}
