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
- [AppPreferences.kt](app/src/main/java/dev/ra/geepee/AppPreferences.kt)
- [RouteLoadState.kt](app/src/main/java/dev/ra/geepee/RouteLoadState.kt)
- [SetupViewportState.kt](app/src/main/java/dev/ra/geepee/SetupViewportState.kt)
- [GeePeeViewModel.kt](app/src/main/java/dev/ra/geepee/GeePeeViewModel.kt)
- [SessionState.kt](app/src/main/java/dev/ra/geepee/SessionState.kt)
- [RouteRepository.kt](app/src/main/java/dev/ra/geepee/RouteRepository.kt)
- [RouteLoadCoordinator.kt](app/src/main/java/dev/ra/geepee/RouteLoadCoordinator.kt)
- [RouteRuntimeState.kt](app/src/main/java/dev/ra/geepee/RouteRuntimeState.kt)
- [LiveTrackingController.kt](app/src/main/java/dev/ra/geepee/LiveTrackingController.kt)
- [LiveTrackingPolicy.kt](app/src/main/java/dev/ra/geepee/LiveTrackingPolicy.kt)
- [UiProjection.kt](app/src/main/java/dev/ra/geepee/UiProjection.kt)
- [HeadingSelection.kt](app/src/main/java/dev/ra/geepee/HeadingSelection.kt)
- [RouteStatusFormatter.kt](app/src/main/java/dev/ra/geepee/RouteStatusFormatter.kt)
- [RouteMatching.kt](app/src/main/java/dev/ra/geepee/RouteMatching.kt)
- [RouteMath.kt](app/src/main/java/dev/ra/geepee/RouteMath.kt)
- [GpxParser.kt](app/src/main/java/dev/ra/geepee/GpxParser.kt)
- [HeadingSelectionTest.kt](app/src/test/java/dev/ra/geepee/HeadingSelectionTest.kt)
- [LiveTrackingPolicyTest.kt](app/src/test/java/dev/ra/geepee/LiveTrackingPolicyTest.kt)
- [RouteLoadStateTest.kt](app/src/test/java/dev/ra/geepee/RouteLoadStateTest.kt)
- [RouteLoadCoordinatorTest.kt](app/src/test/java/dev/ra/geepee/RouteLoadCoordinatorTest.kt)
- [RouteRuntimeStateTest.kt](app/src/test/java/dev/ra/geepee/RouteRuntimeStateTest.kt)
- [RouteStatusFormatterTest.kt](app/src/test/java/dev/ra/geepee/RouteStatusFormatterTest.kt)
- [SessionStateTest.kt](app/src/test/java/dev/ra/geepee/SessionStateTest.kt)
- [UiProjectionTest.kt](app/src/test/java/dev/ra/geepee/UiProjectionTest.kt)
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
- Persisted presentation/tracking preferences live only in `AppPreferences.kt`.
- Route-load workflow state lives only in `RouteLoadState.kt`.
- GPX parsing and persisted route-grant retention/release happen only in `RouteRepository.kt`.
- Async route-load workflow lives only in `RouteLoadCoordinator.kt`.
- Location and heading subscription happen only in `LiveTrackingController.kt`.
- Live tracking cadence policy lives only in `LiveTrackingPolicy.kt`.
- One-shot location refresh happens only in `LiveTrackingController.requestImmediateLocationRefresh()`.
- Live route/session projection state lives only in `RouteRuntimeState.kt`.
- Session/permission transition rules live only in `SessionState.kt`.
- Final UI-state projection lives only in `UiProjection.kt`.
- Route matching and route math are pure Kotlin in `RouteMatching.kt` and `RouteMath.kt`.

Fast grep checks:
1. Network:
   `rg -n "Http|Socket|URLConnection|Retrofit|OkHttp|ktor|INTERNET" .`
2. Persistence:
   `rg -n "SharedPreferences|Room|DataStore|openFileOutput|FileOutputStream" .`
3. Background execution:
   `rg -n "Service|ForegroundService|WorkManager|AlarmManager|ACCESS_BACKGROUND_LOCATION" .`
