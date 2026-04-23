package dev.ra.geepee

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

private const val LOCATION_MIN_TIME_MS = 2_000L
private const val LOCATION_MIN_DISTANCE_METERS = 3f
private const val LOCATION_MIN_TIME_BATTERY_SAVER_MS = 4_000L
private const val LOCATION_MIN_DISTANCE_BATTERY_SAVER_METERS = 8f
private const val HEADING_MIN_INTERVAL_MS = 40L
private const val HEADING_MIN_DELTA_DEGREES = 1.5
private const val HEADING_MIN_INTERVAL_BATTERY_SAVER_MS = 90L
private const val HEADING_MIN_DELTA_BATTERY_SAVER_DEGREES = 3.0
private const val HEADING_SENSOR_PERIOD_US = 20_000
private const val HEADING_SENSOR_PERIOD_BATTERY_SAVER_US = 40_000
private const val COURSE_HEADING_MIN_SPEED_MPS = 1.8f
private const val COURSE_HEADING_MAX_ACCURACY_DEGREES = 20f
private const val LOCATION_HISTORY_LIMIT = 12
private const val LOCATION_HISTORY_MIN_DISTANCE_METERS = 2.5
private const val LOG_TAG = "GeePee"

enum class RouteTone {
    Idle,
    Ready,
    OnRoute,
    Drifting,
    OffRoute,
    Warning,
}

data class RouteStatus(
    val tone: RouteTone,
    val badge: String,
    val headline: String,
    val detail: String,
)

data class CompassState(
    val routeBearingDegrees: Double,
    val headingDegrees: Double?,
)

data class GeePeeUiState(
    val routeName: String? = null,
    val routeModel: RouteModel? = null,
    val analysis: RouteAnalysis? = null,
    val locationHistoryPoints: List<ProjectedPoint> = emptyList(),
    val compass: CompassState? = null,
    val lastFixTimestampMillis: Long? = null,
    val sessionRunning: Boolean = false,
    val routeLoading: Boolean = false,
    val hasCoarsePermission: Boolean = false,
    val hasFinePermission: Boolean = false,
    val batterySaverEnabled: Boolean = true,
    val status: RouteStatus = RouteStatus(
        tone = RouteTone.Idle,
        badge = "Idle",
        headline = "Load a GPX route",
        detail = "GeePee only watches your drift from the line.",
    ),
) {
    val hasLocationPermission: Boolean
        get() = hasCoarsePermission || hasFinePermission
}

class GeePeeViewModel(application: Application) : AndroidViewModel(application) {
    private val contentResolver = application.contentResolver
    private val locationManager = application.getSystemService(LocationManager::class.java)
    private val sensorManager = application.getSystemService(SensorManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val appStateStore = AppStateStore(application)

    // All long-lived app state lives here. Nothing else persists route or location data.
    private var routeModel: RouteModel? = null
    private var routeMatcher: RouteMatcher? = null
    private var routeName: String? = null
    private var currentFix: LocationFix? = null
    private var currentAnalysis: RouteAnalysis? = null
    private var locationHistoryPoints: List<ProjectedPoint> = emptyList()
    private var currentHeadingDegrees: Double? = null
    private var smoothedHeading: SmoothedHeading? = null
    private var issueMessage: String? = null
    private var hasCoarsePermission = false
    private var hasFinePermission = false
    private var monitoringWanted = false
    private var isForeground = false
    private var receivingLocationUpdates = false
    private var receivingHeadingUpdates = false
    private var routeLoading = false
    private var lastHeadingUpdateMillis = 0L
    private var batterySaverEnabled = true

    var uiState by mutableStateOf(GeePeeUiState())
        private set

    private val locationListener = LocationListener { location ->
        handleLocation(location)
    }

    private val headingListener = object : SensorEventListener {
        private val rotationMatrix = FloatArray(9)
        private val orientation = FloatArray(3)

        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR &&
                event.sensor.type != Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR
            ) {
                return
            }

            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientation)
            val heading = normalizeBearing(Math.toDegrees(orientation[0].toDouble()))
            val now = System.currentTimeMillis()
            val previous = currentHeadingDegrees
            val changedEnough = previous == null ||
                abs(normalizeSignedHeadingDegrees(heading - previous)) >= headingMinDeltaDegrees()
            val oldEnough = now - lastHeadingUpdateMillis >= headingMinIntervalMs()
            if (!changedEnough && !oldEnough) {
                return
            }

            currentHeadingDegrees = heading
            lastHeadingUpdateMillis = now
            refreshSmoothedHeading()
            recomputeUiState()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    init {
        val restoredState = appStateStore.load()
        monitoringWanted = restoredState.sessionActive
        batterySaverEnabled = restoredState.batterySaverEnabled
        restoreSelectedRouteIfNeeded(restoredState)
        recomputeUiState()
    }

    fun updateLocationPermissions(coarseGranted: Boolean, fineGranted: Boolean) {
        hasCoarsePermission = coarseGranted
        hasFinePermission = fineGranted
        if (!uiState.hasLocationPermission) {
            issueMessage = null
        }
        if (!hasAnyLocationPermission()) {
            stopLocationUpdates()
        } else if (monitoringWanted && isForeground) {
            startLocationUpdatesIfPossible()
        }
        if (monitoringWanted && isForeground) {
            startHeadingUpdatesIfPossible()
        } else {
            stopHeadingUpdates()
        }
        recomputeUiState()
    }

    fun setForeground(isForeground: Boolean) {
        this.isForeground = isForeground
        if (!isForeground) {
            stopLocationUpdates()
            stopHeadingUpdates()
        } else if (monitoringWanted) {
            startLocationUpdatesIfPossible()
            startHeadingUpdatesIfPossible()
        }
        recomputeUiState()
    }

    fun startMonitoring() {
        monitoringWanted = true
        routeMatcher?.reset()
        clearLocationHistory()
        appStateStore.setSessionActive(true)
        issueMessage = null
        startLocationUpdatesIfPossible()
        startHeadingUpdatesIfPossible()
        recomputeUiState()
    }

    fun setBatterySaverEnabled(enabled: Boolean) {
        if (batterySaverEnabled == enabled) {
            return
        }
        batterySaverEnabled = enabled
        appStateStore.setBatterySaverEnabled(enabled)
        if (receivingLocationUpdates) {
            stopLocationUpdates()
            if (monitoringWanted && isForeground && hasAnyLocationPermission()) {
                startLocationUpdatesIfPossible()
            }
        }
        if (receivingHeadingUpdates) {
            stopHeadingUpdates()
            if (monitoringWanted && isForeground) {
                startHeadingUpdatesIfPossible()
            }
        }
        recomputeUiState()
    }

    fun stopMonitoring() {
        monitoringWanted = false
        routeMatcher?.reset()
        clearLocationHistory()
        appStateStore.setSessionActive(false)
        issueMessage = null
        stopLocationUpdates()
        stopHeadingUpdates()
        recomputeUiState()
    }

    @SuppressLint("MissingPermission")
    fun requestImmediateLocationRefresh() {
        if (!monitoringWanted || !isForeground || !hasAnyLocationPermission()) {
            return
        }

        val providers = enabledProviders()
        if (providers.isEmpty()) {
            issueMessage = "Enable GPS or network location on the phone."
            recomputeUiState()
            return
        }

        bestLastKnownLocation(providers)?.let(::handleLocation)
        val provider = preferredCurrentLocationProvider(providers) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            locationManager.getCurrentLocation(
                provider,
                CancellationSignal(),
                getApplication<Application>().mainExecutor,
            ) { location ->
                location?.let(::handleLocation)
            }
        } else {
            // API 29 fallback for the explicit user refresh action.
            locationManager.requestSingleUpdate(
                provider,
                object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        handleLocation(location)
                        locationManager.removeUpdates(this)
                    }
                },
                Looper.getMainLooper(),
            )
        }
    }

    fun loadRoute(
        uri: Uri,
        displayName: String?,
        rememberSelection: Boolean = true,
        fromRestore: Boolean = false,
    ) {
        val resolvedName = displayName ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Route"
        routeLoading = true
        issueMessage = null
        recomputeUiState()

        // GPX import is the app's only file I/O path.
        ioExecutor.execute {
            try {
                val parsedSegments = contentResolver.openInputStream(uri)?.use(GpxParser::parse)
                    ?: throw IllegalArgumentException("Could not open that GPX file.")
                val model = buildRouteModel(parsedSegments)

                mainHandler.post {
                    routeModel = model
                    routeMatcher = RouteMatcher(model)
                    routeName = resolvedName
                    currentAnalysis = null
                    clearLocationHistory()
                    if (rememberSelection) {
                        rememberSelectedRoute(uri, resolvedName)
                    }
                    routeLoading = false
                    issueMessage = null
                    recomputeAnalysis()
                    recomputeUiState()
                }
            } catch (error: Exception) {
                Log.e(LOG_TAG, "Route load failed for uri=$uri", error)
                mainHandler.post {
                    routeLoading = false
                    if (fromRestore) {
                        clearRememberedRoute()
                    }
                    val reason = error.message ?: "Could not read that GPX file."
                    issueMessage = "${error.javaClass.simpleName}: $reason"
                    recomputeUiState()
                }
            }
        }
    }

    override fun onCleared() {
        stopLocationUpdates()
        stopHeadingUpdates()
        ioExecutor.shutdownNow()
        super.onCleared()
    }

    private fun recomputeAnalysis() {
        currentAnalysis = if (routeModel != null && currentFix != null) {
            routeMatcher?.match(currentFix!!)
                ?: analyzeLocationAgainstModel(
                    model = routeModel!!,
                    fix = currentFix!!,
                    previousNearestEdgeIndex = currentAnalysis?.nearestEdgeIndex?.takeIf { it >= 0 },
                )
        } else {
            null
        }
    }

    private fun handleLocation(location: Location) {
        currentFix = LocationFix(
            lat = location.latitude,
            lon = location.longitude,
            accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
            headingDegrees = location.bearing.takeIf { location.hasBearing() },
            speedMetersPerSecond = location.speed.takeIf { location.hasSpeed() },
            timestampMillis = location.time,
            bearingAccuracyDegrees = location.bearingAccuracyDegrees.takeIf { location.hasBearingAccuracy() },
        )
        issueMessage = null
        recomputeAnalysis()
        appendLocationHistory(currentAnalysis?.point)
        refreshSmoothedHeading()
        recomputeUiState()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdatesIfPossible() {
        if (receivingLocationUpdates || !monitoringWanted || !isForeground || !hasAnyLocationPermission()) {
            return
        }

        // GeePee only requests live location during an active foreground session.
        val providers = enabledProviders()
        if (providers.isEmpty()) {
            issueMessage = "Enable GPS or network location on the phone."
            recomputeUiState()
            return
        }

        providers.forEach { provider ->
            locationManager.requestLocationUpdates(
                provider,
                locationMinTimeMs(),
                locationMinDistanceMeters(),
                locationListener,
                Looper.getMainLooper(),
            )
        }

        bestLastKnownLocation(providers)?.let(::handleLocation)
        receivingLocationUpdates = true
    }

    private fun stopLocationUpdates() {
        if (!receivingLocationUpdates) {
            return
        }
        locationManager.removeUpdates(locationListener)
        receivingLocationUpdates = false
    }

    private fun startHeadingUpdatesIfPossible() {
        if (receivingHeadingUpdates || !monitoringWanted || !isForeground) {
            return
        }
        val headingSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
            ?: return
        sensorManager.registerListener(
            headingListener,
            headingSensor,
            headingSensorPeriodUs(),
        )
        receivingHeadingUpdates = true
    }

    private fun stopHeadingUpdates() {
        if (receivingHeadingUpdates) {
            sensorManager.unregisterListener(headingListener)
        }
        receivingHeadingUpdates = false
        currentHeadingDegrees = null
        lastHeadingUpdateMillis = 0L
        refreshSmoothedHeading()
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

    private fun preferredCurrentLocationProvider(providers: List<String>): String? {
        return when {
            hasFinePermission && providers.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            providers.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> providers.firstOrNull()
        }
    }

    private fun hasAnyLocationPermission(): Boolean = hasCoarsePermission || hasFinePermission

    private fun restoreSelectedRouteIfNeeded(restoredState: RestorableAppState) {
        val routeUri = restoredState.routeUri ?: return
        loadRoute(
            uri = routeUri,
            displayName = restoredState.routeName,
            rememberSelection = false,
            fromRestore = true,
        )
    }

    private fun rememberSelectedRoute(uri: Uri, displayName: String) {
        val previousUri = appStateStore.load().routeUri
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure { error ->
            Log.w(LOG_TAG, "Could not persist route grant for uri=$uri", error)
        }
        if (previousUri != null && previousUri != uri) {
            runCatching {
                contentResolver.releasePersistableUriPermission(
                    previousUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.onFailure { error ->
                Log.w(LOG_TAG, "Could not release previous route grant for uri=$previousUri", error)
            }
        }
        appStateStore.saveRouteSelection(uri, displayName)
    }

    private fun clearRememberedRoute() {
        val previousUri = appStateStore.load().routeUri
        if (previousUri != null) {
            runCatching {
                contentResolver.releasePersistableUriPermission(
                    previousUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        appStateStore.clearRouteSelection()
        monitoringWanted = false
    }

    private fun locationMinTimeMs(): Long {
        return if (batterySaverEnabled) LOCATION_MIN_TIME_BATTERY_SAVER_MS else LOCATION_MIN_TIME_MS
    }

    private fun locationMinDistanceMeters(): Float {
        return if (batterySaverEnabled) {
            LOCATION_MIN_DISTANCE_BATTERY_SAVER_METERS
        } else {
            LOCATION_MIN_DISTANCE_METERS
        }
    }

    private fun headingMinIntervalMs(): Long {
        return if (batterySaverEnabled) HEADING_MIN_INTERVAL_BATTERY_SAVER_MS else HEADING_MIN_INTERVAL_MS
    }

    private fun headingMinDeltaDegrees(): Double {
        return if (batterySaverEnabled) {
            HEADING_MIN_DELTA_BATTERY_SAVER_DEGREES
        } else {
            HEADING_MIN_DELTA_DEGREES
        }
    }

    private fun headingSensorPeriodUs(): Int {
        return if (batterySaverEnabled) {
            HEADING_SENSOR_PERIOD_BATTERY_SAVER_US
        } else {
            HEADING_SENSOR_PERIOD_US
        }
    }

    private fun recomputeUiState() {
        val status = buildStatus()
        uiState = GeePeeUiState(
            routeName = routeName,
            routeModel = routeModel,
            analysis = currentAnalysis,
            locationHistoryPoints = locationHistoryPoints,
            compass = buildCompassState(),
            lastFixTimestampMillis = currentFix?.timestampMillis,
            sessionRunning = monitoringWanted,
            routeLoading = routeLoading,
            hasCoarsePermission = hasCoarsePermission,
            hasFinePermission = hasFinePermission,
            batterySaverEnabled = batterySaverEnabled,
            status = status,
        )
    }

    private fun buildCompassState(): CompassState? {
        val fix = currentFix ?: return null
        val analysis = currentAnalysis ?: return null
        return CompassState(
            routeBearingDegrees = routeBearingDegrees(fix, analysis.nearestGeoPoint),
            headingDegrees = displayHeadingDegrees(fix),
        )
    }

    private fun appendLocationHistory(point: ProjectedPoint?) {
        if (point == null || !monitoringWanted) {
            return
        }

        val previous = locationHistoryPoints.lastOrNull()
        if (previous != null && projectedDistanceMeters(previous, point) < LOCATION_HISTORY_MIN_DISTANCE_METERS) {
            return
        }

        locationHistoryPoints = (locationHistoryPoints + point).takeLast(LOCATION_HISTORY_LIMIT)
    }

    private fun clearLocationHistory() {
        if (locationHistoryPoints.isEmpty()) {
            return
        }
        locationHistoryPoints = emptyList()
    }

    private fun projectedDistanceMeters(left: ProjectedPoint, right: ProjectedPoint): Double {
        return hypot(right.x - left.x, right.y - left.y)
    }

    private fun buildStatus(): RouteStatus {
        if (routeLoading) {
            return RouteStatus(
                tone = RouteTone.Ready,
                badge = "Loading",
                headline = "Reading route",
                detail = "Parsing the GPX file now.",
            )
        }

        if (routeModel == null) {
            return if (issueMessage != null) {
                RouteStatus(
                    tone = RouteTone.Warning,
                    badge = "Route issue",
                    headline = "Could not load route",
                    detail = issueMessage ?: "Try a different GPX file.",
                )
            } else {
                RouteStatus(
                    tone = RouteTone.Idle,
                    badge = "Idle",
                    headline = "Load a GPX route",
                    detail = "GeePee only shows when you drift from the line.",
                )
            }
        }

        if (!monitoringWanted) {
            return RouteStatus(
                tone = RouteTone.Ready,
                badge = "Ready",
                headline = "Route ready",
                detail = "${formatDistance(routeModel!!.totalLengthMeters)} loaded. Start when you want live drift alerts.",
            )
        }

        if (!hasAnyLocationPermission()) {
            return RouteStatus(
                tone = RouteTone.Warning,
                badge = "Permission",
                headline = "Allow location",
                detail = "GeePee needs foreground location during an active session.",
            )
        }

        if (enabledProviders().isEmpty()) {
            return RouteStatus(
                tone = RouteTone.Warning,
                badge = "Location off",
                headline = "Turn on location",
                detail = "Enable GPS or network location on the phone.",
            )
        }

        if (currentFix == null || currentAnalysis == null) {
            return RouteStatus(
                tone = RouteTone.Ready,
                badge = "Locating",
                headline = "Looking for your route position",
                detail = issueMessage ?: "Keep the app open until the first fix lands.",
            )
        }

        if (!hasFinePermission) {
            return RouteStatus(
                tone = RouteTone.Warning,
                badge = "Approximate",
                headline = "Precise location is better",
                detail = "Approximate fixes are too loose for reliable off-route alerts.",
            )
        }

        return statusForAnalysis(currentFix!!, currentAnalysis!!)
    }

    private fun statusForAnalysis(fix: LocationFix, analysis: RouteAnalysis): RouteStatus {
        val onThreshold = max(12.0, analysis.accuracyMeters?.toDouble() ?: 0.0)
        val driftingThreshold = max(35.0, onThreshold * 2.0)
        val offRoute = analysis.offRouteMeters
        val tone = when {
            offRoute <= onThreshold -> RouteTone.OnRoute
            offRoute <= driftingThreshold -> RouteTone.Drifting
            else -> RouteTone.OffRoute
        }

        val headline = if (tone == RouteTone.OnRoute) {
            "On route"
        } else {
            "${formatDistance(offRoute)} back to route"
        }

        val routeBearing = routeBearingDegrees(fix, analysis.nearestGeoPoint)
        val heading = displayHeadingDegrees(fix)
        val detailBits = mutableListOf(
            routeDirectionCue(routeBearing, heading),
            "${formatDistance(analysis.remainingMeters)} left",
        )
        analysis.accuracyMeters?.let { detailBits += "±${formatDistance(it.toDouble())}" }

        return RouteStatus(
            tone = tone,
            badge = when (tone) {
                RouteTone.OnRoute -> "On route"
                RouteTone.Drifting -> "Drifting"
                RouteTone.OffRoute -> "Off route"
                RouteTone.Warning -> "Warning"
                RouteTone.Ready -> "Ready"
                RouteTone.Idle -> "Idle"
            },
            headline = headline,
            detail = detailBits.joinToString(" · "),
        )
    }

    private fun routeDirectionCue(absoluteBearing: Double, heading: Double?): String {
        if (heading != null) {
            val relative = normalizeSignedBearing(absoluteBearing - heading)
            val magnitude = abs(relative).roundToInt()
            return when {
                magnitude <= 15 -> "Route ahead"
                magnitude >= 150 -> "Route behind"
                relative > 0 -> "Route ${magnitude}° right"
                else -> "Route ${magnitude}° left"
            }
        }

        return "Route ${compassDirection(absoluteBearing)}"
    }

    private fun routeBearingDegrees(fix: LocationFix, nearestGeoPoint: GeoPoint): Double {
        val results = FloatArray(3)
        Location.distanceBetween(fix.lat, fix.lon, nearestGeoPoint.lat, nearestGeoPoint.lon, results)
        return normalizeBearing(results.getOrNull(1)?.toDouble() ?: 0.0)
    }

    private fun usableHeadingDegrees(fix: LocationFix): Double? {
        val speed = fix.speedMetersPerSecond ?: return null
        val accuracy = fix.bearingAccuracyDegrees ?: return null
        return fix.headingDegrees
            ?.takeIf { bearing ->
                speed >= COURSE_HEADING_MIN_SPEED_MPS &&
                    accuracy <= COURSE_HEADING_MAX_ACCURACY_DEGREES &&
                    bearing.isFinite()
            }
            ?.toDouble()
    }

    private fun currentHeadingReading(fix: LocationFix?): HeadingReading? {
        if (fix == null) {
            return null
        }

        return usableHeadingDegrees(fix)?.let { headingDegrees ->
            HeadingReading(
                degrees = headingDegrees,
                source = HeadingSource.Course,
            )
        } ?: currentHeadingDegrees?.let { headingDegrees ->
            HeadingReading(
                degrees = headingDegrees,
                source = HeadingSource.Sensor,
            )
        }
    }

    private fun refreshSmoothedHeading() {
        smoothedHeading = smoothHeading(
            previous = smoothedHeading,
            target = currentHeadingReading(currentFix),
            batterySaverEnabled = batterySaverEnabled,
        )
    }

    private fun displayHeadingDegrees(fix: LocationFix): Double? {
        return smoothedHeading?.degrees ?: currentHeadingReading(fix)?.degrees
    }

    private fun normalizeBearing(value: Double): Double {
        return normalizeHeadingDegrees(value)
    }

    private fun normalizeSignedBearing(value: Double): Double {
        return normalizeSignedHeadingDegrees(value)
    }

    private fun compassDirection(value: Double): String {
        val directions = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val index = (((normalizeBearing(value) + 22.5) % 360.0) / 45.0).toInt()
        return directions[index]
    }
}
