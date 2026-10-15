package io.github.weathercard

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 选择城市。两种打开方式：
 * - 添加/点按卡片城市名：带 EXTRA_APPWIDGET_ID，只修改这张卡片；
 * - 从桌面图标打开：修改所有卡片和之后新卡片的默认城市。
 */
class ConfigActivity : Activity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val isWidgetConfig get() = widgetId != AppWidgetManager.INVALID_APPWIDGET_ID

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var locationCancel: CancellationSignal? = null
    private var legacyListener: LocationListener? = null

    private lateinit var prefs: Prefs
    private lateinit var queryInput: EditText
    private lateinit var progress: ProgressBar
    private lateinit var message: TextView
    private lateinit var adapter: ResultAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (isWidgetConfig) {
            // 用户直接返回时取消添加卡片
            setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        }

        setContentView(R.layout.activity_config)
        queryInput = findViewById(R.id.query)
        progress = findViewById(R.id.progress)
        message = findViewById(R.id.message)

        findViewById<TextView>(R.id.title)
            .setText(if (isWidgetConfig) R.string.config_title else R.string.app_name)
        findViewById<TextView>(R.id.intro).visibility = if (isWidgetConfig) View.GONE else View.VISIBLE

        val current = if (isWidgetConfig) prefs.getPlace(widgetId) else prefs.getDefaultPlace()
        findViewById<TextView>(R.id.current_city).text = getString(R.string.current_city, current.name)

        val primary = findViewById<Button>(R.id.primary_action)
        if (isWidgetConfig) {
            primary.setText(R.string.use_this_city)
            primary.setOnClickListener { choose(current) }
        } else {
            val manager = getSystemService(AppWidgetManager::class.java)
            if (manager != null && manager.isRequestPinAppWidgetSupported) {
                primary.setText(R.string.add_to_home)
                primary.setOnClickListener {
                    manager.requestPinAppWidget(ComponentName(this, WeatherWidgetProvider::class.java), null, null)
                }
            } else {
                primary.visibility = View.GONE
            }
        }

        adapter = ResultAdapter()
        findViewById<ListView>(R.id.results).apply {
            adapter = this@ConfigActivity.adapter
            setOnItemClickListener { _, _, position, _ -> choose(this@ConfigActivity.adapter.getItem(position)!!.place) }
        }

        findViewById<Button>(R.id.search).setOnClickListener { search() }
        queryInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search()
                true
            } else {
                false
            }
        }
        findViewById<Button>(R.id.use_location).setOnClickListener { useCurrentLocation() }
    }

    override fun onDestroy() {
        cancelLocationRequest()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun search() {
        val query = queryInput.text.toString().trim()
        if (query.isEmpty()) return
        hideKeyboard()
        showProgress(R.string.searching)
        val language = Locale.getDefault().language.ifBlank { "en" }
        executor.execute {
            val result = runCatching { WeatherApi.searchPlaces(query, language) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                progress.visibility = View.GONE
                result.onSuccess { places ->
                    adapter.clear()
                    adapter.addAll(places)
                    message.text = if (places.isEmpty()) getString(R.string.no_results) else ""
                }.onFailure {
                    message.text = getString(R.string.network_error, it.message ?: it.javaClass.simpleName)
                }
            }
        }
    }

    private fun useCurrentLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION)
            return
        }
        val locationManager = getSystemService(LocationManager::class.java)
        if (locationManager == null) {
            message.text = getString(R.string.location_unavailable)
            return
        }
        val enabled = locationManager.getProviders(true)

        val recent = enabled.mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < LAST_KNOWN_MAX_AGE_MILLIS }
            .maxByOrNull { it.time }
        if (recent != null) {
            onLocation(recent)
            return
        }

        val provider = listOf(LocationManager.NETWORK_PROVIDER, FUSED_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { it in enabled }
        if (provider == null) {
            message.text = getString(R.string.location_unavailable)
            return
        }

        showProgress(R.string.locating)
        cancelLocationRequest()
        mainHandler.postDelayed(::onLocationFailed, LOCATION_TIMEOUT_MILLIS)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val signal = CancellationSignal().also { locationCancel = it }
            locationManager.getCurrentLocation(provider, signal, mainExecutor) { location ->
                if (location != null) onLocation(location) else onLocationFailed()
            }
        } else {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) = onLocation(location)

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
            }
            legacyListener = listener
            @Suppress("DEPRECATION")
            locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        }
    }

    private fun onLocationFailed() {
        cancelLocationRequest()
        if (isFinishing || isDestroyed) return
        progress.visibility = View.GONE
        message.text = getString(R.string.location_unavailable)
    }

    private fun onLocation(location: Location) {
        cancelLocationRequest()
        if (isFinishing || isDestroyed) return
        showProgress(R.string.locating)
        val latitude = Math.round(location.latitude * 10_000) / 10_000.0
        val longitude = Math.round(location.longitude * 10_000) / 10_000.0
        executor.execute {
            val name = reverseGeocode(latitude, longitude) ?: getString(R.string.current_location)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) choose(Place(name, latitude, longitude))
            }
        }
    }

    private fun reverseGeocode(latitude: Double, longitude: Double): String? {
        if (!Geocoder.isPresent()) return null
        return runCatching {
            @Suppress("DEPRECATION")
            val address = Geocoder(this, Locale.getDefault()).getFromLocation(latitude, longitude, 1)?.firstOrNull()
            address?.let { it.subLocality ?: it.locality ?: it.subAdminArea ?: it.adminArea }
        }.getOrNull()
    }

    private fun cancelLocationRequest() {
        mainHandler.removeCallbacksAndMessages(null)
        locationCancel?.cancel()
        locationCancel = null
        legacyListener?.let { listener ->
            getSystemService(LocationManager::class.java)?.removeUpdates(listener)
        }
        legacyListener = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_LOCATION) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            useCurrentLocation()
        } else {
            message.text = getString(R.string.location_denied)
        }
    }

    private fun choose(place: Place) {
        val manager = AppWidgetManager.getInstance(this)
        prefs.setDefaultPlace(place)
        if (isWidgetConfig) {
            prefs.setPlace(widgetId, place)
            prefs.clearWeather(widgetId)
            WidgetRenderer.update(this, manager, widgetId, loading = true)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        } else {
            WeatherWidgetProvider.allWidgetIds(this).forEach {
                prefs.setPlace(it, place)
                prefs.clearWeather(it)
                WidgetRenderer.update(this, manager, it, loading = true)
            }
            Toast.makeText(this, getString(R.string.city_saved, place.name), Toast.LENGTH_SHORT).show()
        }
        WeatherWidgetProvider.schedulePeriodic(this)
        WeatherWidgetProvider.refreshNow(this)
        if (isWidgetConfig) {
            finish()
        } else {
            findViewById<TextView>(R.id.current_city).text = getString(R.string.current_city, place.name)
            progress.visibility = View.GONE
            message.text = ""
        }
    }

    private fun showProgress(textRes: Int) {
        progress.visibility = View.VISIBLE
        message.setText(textRes)
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(queryInput.windowToken, 0)
    }

    private inner class ResultAdapter :
        ArrayAdapter<WeatherApi.SearchResult>(
            this@ConfigActivity,
            android.R.layout.simple_list_item_2,
            android.R.id.text1,
        ) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getView(position, convertView, parent)
            val item = getItem(position)!!
            view.findViewById<TextView>(android.R.id.text1).text = item.place.name
            view.findViewById<TextView>(android.R.id.text2).text = item.detail
            return view
        }
    }

    private companion object {
        const val REQUEST_LOCATION = 1
        const val FUSED_PROVIDER = "fused"
        const val LOCATION_TIMEOUT_MILLIS = 20_000L
        const val LAST_KNOWN_MAX_AGE_MILLIS = 30 * 60 * 1000L
    }
}
