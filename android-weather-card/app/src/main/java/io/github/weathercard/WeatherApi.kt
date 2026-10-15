package io.github.weathercard

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Open-Meteo 接口封装（免费、无需 API Key）。所有方法都会阻塞，需在后台线程调用。 */
object WeatherApi {

    private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
    private const val GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"

    data class SearchResult(val place: Place, val detail: String)

    fun fetchForecastJson(latitude: Double, longitude: Double): String {
        val url = "$FORECAST_URL?latitude=$latitude&longitude=$longitude" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day," +
            "weather_code,wind_speed_10m,wind_direction_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=5"
        return httpGet(url)
    }

    fun parseForecast(json: String, fetchedAt: Long): Weather {
        val root = JSONObject(json)
        val current = root.getJSONObject("current")
        val days = mutableListOf<DailyForecast>()
        root.optJSONObject("daily")?.let { daily ->
            val time = daily.getJSONArray("time")
            val codes = daily.getJSONArray("weather_code")
            val max = daily.getJSONArray("temperature_2m_max")
            val min = daily.getJSONArray("temperature_2m_min")
            val precipitation = daily.optJSONArray("precipitation_probability_max")
            for (i in 0 until time.length()) {
                if (codes.isNull(i) || max.isNull(i) || min.isNull(i)) continue
                days += DailyForecast(
                    date = time.getString(i),
                    weatherCode = codes.getInt(i),
                    tempMax = max.getDouble(i),
                    tempMin = min.getDouble(i),
                    precipitationProbability = precipitation?.takeUnless { it.isNull(i) }?.getInt(i),
                )
            }
        }
        return Weather(
            temperature = current.getDouble("temperature_2m"),
            apparentTemperature = current.getDouble("apparent_temperature"),
            humidity = current.getInt("relative_humidity_2m"),
            windSpeed = current.getDouble("wind_speed_10m"),
            windDirection = current.optDouble("wind_direction_10m", 0.0),
            weatherCode = current.getInt("weather_code"),
            isDay = current.optInt("is_day", 1) == 1,
            daily = days,
            fetchedAt = fetchedAt,
        )
    }

    fun searchPlaces(query: String, language: String): List<SearchResult> {
        val url = "$GEOCODING_URL?name=${URLEncoder.encode(query, "UTF-8")}" +
            "&count=10&language=$language&format=json"
        val results = JSONObject(httpGet(url)).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            val item = results.getJSONObject(i)
            val detail = listOf(item.optString("admin1"), item.optString("country"))
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            SearchResult(
                place = Place(item.getString("name"), item.getDouble("latitude"), item.getDouble("longitude")),
                detail = detail,
            )
        }
    }

    private fun httpGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "WeatherCard/1.0 (Android)")
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
