# GeePee Audit Notes

This app is intentionally narrow: it is a foreground-only GPX off-route monitor.

Read these files first:
- [MainActivity.kt](app/src/main/java/dev/ra/geepee/MainActivity.kt)
- [AppStateStore.kt](app/src/main/java/dev/ra/geepee/AppStateStore.kt)
- [GeePeeScreen.kt](app/src/main/java/dev/ra/geepee/GeePeeScreen.kt)
- [MovementChrome.kt](app/src/main/java/dev/ra/geepee/MovementChrome.kt)
- [SetupChrome.kt](app/src/main/java/dev/ra/geepee/SetupChrome.kt)
- [RouteCanvas.kt](app/src/main/java/dev/ra/geepee/RouteCanvas.kt)
- [GeePeeTheme.kt](app/src/main/java/dev/ra/geepee/GeePeeTheme.kt)
- [UiModels.kt](app/src/main/java/dev/ra/geepee/UiModels.kt)
- [GeePeeViewModel.kt](app/src/main/java/dev/ra/geepee/GeePeeViewModel.kt)
- [RouteMatching.kt](app/src/main/java/dev/ra/geepee/RouteMatching.kt)
- [RouteMath.kt](app/src/main/java/dev/ra/geepee/RouteMath.kt)
- [GpxParser.kt](app/src/main/java/dev/ra/geepee/GpxParser.kt)
- [RouteMatcherTest.kt](app/src/test/java/dev/ra/geepee/RouteMatcherTest.kt)

Behavior summary:
- The user chooses one GPX file.
- The selected GPX URI, its display name, whether a session was active, the battery-saver flag, dark/light mode, orientation mode, and scale are retained in one private app-state preferences file.
- The GPX route itself is reparsed from the selected URI and kept in memory only for the current process.
- The app requests location only after the user presses `Start`.
- Location updates stop when the app leaves the foreground or the user presses `Stop`.
- A tap on the GPS age label triggers one explicit refresh attempt.
- `Battery saver` changes the live location and heading update cadence.
- `Dark mode` / `Light mode` only change presentation.
- All route calculations happen on-device.

What is not present:
- no network access
- no analytics
- no ads
- no background location permission
- no foreground service
- no copied GPX persistence in app storage
- no backup of app data

Permissions:
- `ACCESS_COARSE_LOCATION`
- `ACCESS_FINE_LOCATION`

Storage:
- GPX data is read through `ContentResolver.openInputStream()`.
- The app does not copy the GPX file into app storage.
- The app keeps at most one persisted read grant for the currently selected GPX URI.
- The app stores only the selected route URI, route name, session-active flag, battery-saver flag, dark/light mode flag, orientation mode, and scale in private app preferences.
- `android:fullBackupContent` excludes app backup on Android 11 and lower.
- `android:dataExtractionRules` excludes cloud backup and device transfer on Android 12+.

Side-effect boundaries:
- File I/O happens only in `GeePeeViewModel.loadRoute()`.
- Tiny persistent app-state writes happen only in `AppStateStore.kt`.
- URI grant retention/release happens only in `GeePeeViewModel.rememberSelectedRoute()` / `clearRememberedRoute()`.
- Location subscription happens only in `GeePeeViewModel.startLocationUpdatesIfPossible()`.
- One-shot location refresh happens only in `GeePeeViewModel.requestImmediateLocationRefresh()`.
- Route matching and route math are pure Kotlin in `RouteMatching.kt` and `RouteMath.kt`.

Fast grep checks:
1. Network:
   `rg -n "Http|Socket|URLConnection|Retrofit|OkHttp|ktor|INTERNET" .`
2. Persistence:
   `rg -n "SharedPreferences|Room|DataStore|openFileOutput|FileOutputStream" .`
3. Background execution:
   `rg -n "Service|ForegroundService|WorkManager|AlarmManager|ACCESS_BACKGROUND_LOCATION" .`
