package dev.ra.geepee

import android.annotation.SuppressLint
import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper

internal data class LiveTrackingConfig(
    val locationMinTimeMs: Long,
    val locationMinDistanceMeters: Float,
    val headingMinIntervalMs: Long,
    val headingMinDeltaDegrees: Double,
    val headingSensorPeriodUs: Int,
)

internal class LiveTrackingController(
    private val application: Application,
    private val onLocation: (Location) -> Unit,
    private val onHeadingDegrees: (Double) -> Unit,
) {
    private val locationManager = application.getSystemService(LocationManager::class.java)
    private val sensorManager = application.getSystemService(SensorManager::class.java)

    private val locationListener = LocationListener { location ->
        onLocation(location)
    }

    private val headingListener = object : SensorEventListener {
        private val rotationMatrix = FloatArray(9)
        private val orientation = FloatArray(3)
        private var lastHeadingDegrees: Double? = null
        private var lastHeadingUpdateMillis = 0L
        private var activeConfig: LiveTrackingConfig? = null

        override fun onSensorChanged(event: SensorEvent) {
            val config = activeConfig ?: return
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR &&
                event.sensor.type != Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR
            ) {
                return
            }

            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientation)
            val headingDegrees = normalizeHeadingDegrees(Math.toDegrees(orientation[0].toDouble()))
            val now = System.currentTimeMillis()
            val previousHeading = lastHeadingDegrees
            val changedEnough = previousHeading == null ||
                kotlin.math.abs(normalizeSignedHeadingDegrees(headingDegrees - previousHeading)) >=
                config.headingMinDeltaDegrees
            val oldEnough = now - lastHeadingUpdateMillis >= config.headingMinIntervalMs
            if (!changedEnough && !oldEnough) {
                return
            }

            lastHeadingDegrees = headingDegrees
            lastHeadingUpdateMillis = now
            onHeadingDegrees(headingDegrees)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        fun activate(config: LiveTrackingConfig) {
            activeConfig = config
        }

        fun clearState() {
            activeConfig = null
            lastHeadingDegrees = null
            lastHeadingUpdateMillis = 0L
        }
    }

    var receivingLocationUpdates: Boolean = false
        private set

    var receivingHeadingUpdates: Boolean = false
        private set

    fun hasEnabledProviders(): Boolean = enabledProviders().isNotEmpty()

    @SuppressLint("MissingPermission")
    fun startLocationUpdates(config: LiveTrackingConfig): Boolean {
        if (receivingLocationUpdates) {
            return true
        }

        val providers = enabledProviders()
        if (providers.isEmpty()) {
            return false
        }

        providers.forEach { provider ->
            locationManager.requestLocationUpdates(
                provider,
                config.locationMinTimeMs,
                config.locationMinDistanceMeters,
                locationListener,
                Looper.getMainLooper(),
            )
        }

        bestLastKnownLocation(providers)?.let(onLocation)
        receivingLocationUpdates = true
        return true
    }

    fun stopLocationUpdates() {
        if (!receivingLocationUpdates) {
            return
        }
        locationManager.removeUpdates(locationListener)
        receivingLocationUpdates = false
    }

    fun startHeadingUpdates(config: LiveTrackingConfig): Boolean {
        if (receivingHeadingUpdates) {
            return true
        }

        val headingSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
            ?: return false

        headingListener.activate(config)
        sensorManager.registerListener(
            headingListener,
            headingSensor,
            config.headingSensorPeriodUs,
        )
        receivingHeadingUpdates = true
        return true
    }

    fun stopHeadingUpdates() {
        if (receivingHeadingUpdates) {
            sensorManager.unregisterListener(headingListener)
        }
        receivingHeadingUpdates = false
        headingListener.clearState()
    }

    @SuppressLint("MissingPermission")
    fun requestImmediateLocationRefresh(hasFinePermission: Boolean): Boolean {
        val providers = enabledProviders()
        if (providers.isEmpty()) {
            return false
        }

        bestLastKnownLocation(providers)?.let(onLocation)
        val provider = preferredCurrentLocationProvider(providers, hasFinePermission) ?: return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            locationManager.getCurrentLocation(
                provider,
                CancellationSignal(),
                application.mainExecutor,
            ) { location ->
                location?.let(onLocation)
            }
        } else {
            locationManager.requestSingleUpdate(
                provider,
                object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        onLocation(location)
                        locationManager.removeUpdates(this)
                    }
                },
                Looper.getMainLooper(),
            )
        }
        return true
    }

    fun shutdown() {
        stopLocationUpdates()
        stopHeadingUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun bestLastKnownLocation(providers: List<String>): Location? {
        return providers
            .mapNotNull { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            }
            .minByOrNull { location ->
                val agePenalty = (System.currentTimeMillis() - location.time).coerceAtLeast(0L) / 1_000L
                location.accuracy + agePenalty
            }
    }

    private fun enabledProviders(): List<String> {
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { provider ->
                runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
            }
    }

    private fun preferredCurrentLocationProvider(
        providers: List<String>,
        hasFinePermission: Boolean,
    ): String? {
        return when {
            hasFinePermission && providers.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            providers.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> providers.firstOrNull()
        }
    }
}
