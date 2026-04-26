package dev.ra.geepee

import android.content.Context
import android.net.Uri

private const val PREFS_NAME = "geepee_app_state"
private const val KEY_ROUTE_URI = "route_uri"
private const val KEY_ROUTE_NAME = "route_name"
private const val KEY_SESSION_ACTIVE = "session_active"
private const val KEY_BATTERY_SAVER = "battery_saver"
private const val KEY_DARK_MODE = "dark_mode"
private const val KEY_ORIENTATION_MODE = "orientation_mode"
private const val KEY_ROUTE_SCALE = "route_scale"
private const val KEY_SETUP_OVERVIEW_MODE = "setup_overview_mode"

internal data class RestorableAppState(
    val routeUri: Uri?,
    val routeName: String?,
    val sessionActive: Boolean,
    val preferences: AppPreferences,
)

internal class AppStateStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): RestorableAppState {
        val routeUri = preferences.getString(KEY_ROUTE_URI, null)?.let(Uri::parse)
        return RestorableAppState(
            routeUri = routeUri,
            routeName = preferences.getString(KEY_ROUTE_NAME, null),
            sessionActive = preferences.getBoolean(KEY_SESSION_ACTIVE, false),
            preferences = AppPreferences(
                batterySaverEnabled = preferences.getBoolean(KEY_BATTERY_SAVER, true),
                darkModeEnabled = preferences.getBoolean(KEY_DARK_MODE, true),
                orientationMode = enumValueOrDefault(
                    rawValue = preferences.getString(KEY_ORIENTATION_MODE, null),
                    defaultValue = OrientationMode.CourseUp,
                ),
                routeScale = enumValueOrDefault(
                    rawValue = preferences.getString(KEY_ROUTE_SCALE, null),
                    defaultValue = RouteScale.TwoHundred,
                ),
                setupOverviewMode = enumValueOrDefault(
                    rawValue = preferences.getString(KEY_SETUP_OVERVIEW_MODE, null),
                    defaultValue = SetupOverviewMode.Route,
                ),
            ),
        )
    }

    fun saveRouteSelection(uri: Uri, routeName: String) {
        preferences.edit()
            .putString(KEY_ROUTE_URI, uri.toString())
            .putString(KEY_ROUTE_NAME, routeName)
            .apply()
    }

    fun clearRouteSelection() {
        preferences.edit()
            .remove(KEY_ROUTE_URI)
            .remove(KEY_ROUTE_NAME)
            .putBoolean(KEY_SESSION_ACTIVE, false)
            .apply()
    }

    fun setSessionActive(active: Boolean) {
        preferences.edit()
            .putBoolean(KEY_SESSION_ACTIVE, active)
            .apply()
    }

    fun savePreferences(appPreferences: AppPreferences) {
        preferences.edit()
            .putBoolean(KEY_BATTERY_SAVER, appPreferences.batterySaverEnabled)
            .putBoolean(KEY_DARK_MODE, appPreferences.darkModeEnabled)
            .putString(KEY_ORIENTATION_MODE, appPreferences.orientationMode.name)
            .putString(KEY_ROUTE_SCALE, appPreferences.routeScale.name)
            .putString(KEY_SETUP_OVERVIEW_MODE, appPreferences.setupOverviewMode.name)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(
        rawValue: String?,
        defaultValue: T,
    ): T {
        return rawValue?.let { value ->
            enumValues<T>().firstOrNull { it.name == value }
        } ?: defaultValue
    }
}
