# GeePee Audit Notes

GeePee is a foreground GPX route monitor with explicit route-context downloads. Live route status is computed on-device from the selected GPX and current location. Route-context data is fetched only through the tile-download path, then cached and projected into local map-info overlays.

## Review Map

Entry, state, and UI projection:

- [MainActivity.kt](app/src/main/java/dev/ra/geepee/MainActivity.kt)
- [GeePeeViewModel.kt](app/src/main/java/dev/ra/geepee/GeePeeViewModel.kt)
- [AppStateStore.kt](app/src/main/java/dev/ra/geepee/AppStateStore.kt)
- [AppPreferences.kt](app/src/main/java/dev/ra/geepee/AppPreferences.kt)
- [UiProjection.kt](app/src/main/java/dev/ra/geepee/UiProjection.kt)
- [UiModels.kt](app/src/main/java/dev/ra/geepee/UiModels.kt)

Screen composition and presentation:

- [GeePeeScreen.kt](app/src/main/java/dev/ra/geepee/GeePeeScreen.kt)
- [MovementChrome.kt](app/src/main/java/dev/ra/geepee/MovementChrome.kt)
- [MovementViewState.kt](app/src/main/java/dev/ra/geepee/MovementViewState.kt)
- [SetupChrome.kt](app/src/main/java/dev/ra/geepee/SetupChrome.kt)
- [SetupViewportState.kt](app/src/main/java/dev/ra/geepee/SetupViewportState.kt)
- [GeePeeTheme.kt](app/src/main/java/dev/ra/geepee/GeePeeTheme.kt)
- [RouteRibbon.kt](app/src/main/java/dev/ra/geepee/RouteRibbon.kt)
- [RouteStatusFormatter.kt](app/src/main/java/dev/ra/geepee/RouteStatusFormatter.kt)
- [HeadingSelection.kt](app/src/main/java/dev/ra/geepee/HeadingSelection.kt)
- [RouteViewRotation.kt](app/src/main/java/dev/ra/geepee/RouteViewRotation.kt)
- [RoutePoiSelections.kt](app/src/main/java/dev/ra/geepee/RoutePoiSelections.kt)
- [Formatting.kt](app/src/main/java/dev/ra/geepee/Formatting.kt)
- [ContentUri.kt](app/src/main/java/dev/ra/geepee/ContentUri.kt)

Route loading, session state, and live tracking:

- [RouteRepository.kt](app/src/main/java/dev/ra/geepee/RouteRepository.kt)
- [RouteLoadCoordinator.kt](app/src/main/java/dev/ra/geepee/RouteLoadCoordinator.kt)
- [RouteLoadState.kt](app/src/main/java/dev/ra/geepee/RouteLoadState.kt)
- [RouteRuntimeState.kt](app/src/main/java/dev/ra/geepee/RouteRuntimeState.kt)
- [SessionState.kt](app/src/main/java/dev/ra/geepee/SessionState.kt)
- [LiveTrackingController.kt](app/src/main/java/dev/ra/geepee/LiveTrackingController.kt)
- [LiveTrackingPolicy.kt](app/src/main/java/dev/ra/geepee/LiveTrackingPolicy.kt)
- [GpxParser.kt](app/src/main/java/dev/ra/geepee/GpxParser.kt)
- [RouteMatching.kt](app/src/main/java/dev/ra/geepee/RouteMatching.kt)
- [RouteMath.kt](app/src/main/java/dev/ra/geepee/RouteMath.kt)

Tiles, downloads, cache, and map-info:

- [TileContext.kt](app/src/main/java/dev/ra/geepee/TileContext.kt)
- [TileResolutionPolicy.kt](app/src/main/java/dev/ra/geepee/TileResolutionPolicy.kt)
- [TileContextRepository.kt](app/src/main/java/dev/ra/geepee/TileContextRepository.kt)
- [TileDownloadCoordinator.kt](app/src/main/java/dev/ra/geepee/TileDownloadCoordinator.kt)
- [TileRuntimePack.kt](app/src/main/java/dev/ra/geepee/TileRuntimePack.kt)
- [RouteTileOverlay.kt](app/src/main/java/dev/ra/geepee/RouteTileOverlay.kt)
- [RouteContext.kt](app/src/main/java/dev/ra/geepee/RouteContext.kt)
- [RouteContextCoordinator.kt](app/src/main/java/dev/ra/geepee/RouteContextCoordinator.kt)
- [TilePrunePolicy.kt](app/src/main/java/dev/ra/geepee/TilePrunePolicy.kt)
- [PreviewTileSelection.kt](app/src/main/java/dev/ra/geepee/PreviewTileSelection.kt)

Canvas, gestures, and external actions:

- [RouteCanvas.kt](app/src/main/java/dev/ra/geepee/RouteCanvas.kt)
- [RouteCanvasGestures.kt](app/src/main/java/dev/ra/geepee/RouteCanvasGestures.kt)
- [TileGridCanvas.kt](app/src/main/java/dev/ra/geepee/TileGridCanvas.kt)
- [TileDeleteDialog.kt](app/src/main/java/dev/ra/geepee/TileDeleteDialog.kt)
- [MapIntents.kt](app/src/main/java/dev/ra/geepee/MapIntents.kt)
- [ScreenPinning.kt](app/src/main/java/dev/ra/geepee/ScreenPinning.kt)

## Behavior Model

- The user opens one GPX document URI. `RouteRepository` parses it through `ContentResolver.openInputStream()`, retains the persisted read grant, releases the previous route grant, and stores route metadata through `AppStateStore`.
- The persisted app record is `geepee_app_state`: route URI, route name, reversed-route flag, session-active flag, battery-saver flag, theme flag, orientation mode, and route scale.
- The GPX geometry is process memory. On restore, the selected document URI is reopened and reparsed.
- `SessionState` owns start/stop/permission/foreground transitions. `LiveTrackingController` subscribes to location and heading only when those transitions allow tracking.
- `LiveTrackingPolicy` owns update cadence. Battery saver changes cadence, not route math.
- Route matching and geometry stay in pure Kotlin under `RouteMatching.kt`, `RouteMath.kt`, and `HeadingMath.kt`.
- `buildGeePeeUiState()` is the final app-state to UI-state projection. UI copy such as map-info availability is derived there rather than duplicated in composables.
- Tile display/data resolution is parameterized by `TileResolutionPolicy`. Display tiles are preview affordances; data tiles are the concrete cache/download unit.
- Tile downloads are explicit user actions flowing through `GeePeeViewModel.downloadTile()`, `TileDownloadCoordinator`, and `TileContextRepository.downloadTile()`.
- Successful tile downloads are stored as raw context JSON, compiled runtime packs, and route-overlay cache entries. Failed, cancelled, downloading, and too-large states are runtime UI state.
- Map-info is derived from cached route-context tiles. `RouteContextCoordinator` builds POIs, nearby ways, and availability from loaded tile coverage and route-overlay readiness.
- Tile deletion uses one plan builder. Selected downloaded tiles take precedence; an empty selection uses the unused-tile policy.

## Permissions

Runtime manifest permissions:

- `ACCESS_COARSE_LOCATION`
- `ACCESS_FINE_LOCATION`
- `INTERNET`

The location permissions feed `SessionState`; `INTERNET` supports explicit Overpass tile downloads.

## Network Boundaries

- App network fetches are centralized in [TileContextRepository.kt](app/src/main/java/dev/ra/geepee/TileContextRepository.kt), using `HttpURLConnection` against `OVERPASS_ENDPOINT`.
- Overpass requests are bounded by connect/read timeouts and `MAX_TILE_DOWNLOAD_BYTES`.
- Oversized Overpass responses become `TileDownloadStatus.TooLarge` through `TileDownloadTooLargeException` and `TileDownloadUpdate.TooLarge`.
- Generic HTTP, parser, and I/O failures become `TileDownloadStatus.Error`.
- [MapIntents.kt](app/src/main/java/dev/ra/geepee/MapIntents.kt) launches external `geo:` or OSM web intents for "Open in" actions.
- [scripts/fetch_osm_route_context.py](scripts/fetch_osm_route_context.py) is development tooling for offline context generation.

## Storage Boundaries

- `AppStateStore` writes small private preferences only.
- GPX bytes remain at the selected document URI; the durable in-app route record is metadata plus the persisted read grant.
- Tile cache root is `files/tile-context/v1/`.
- `manifest.json` records cached tiles with size, download time, and last-access time.
- `tiles/{z}/{x}/{y}.json` stores normalized Overpass context packs.
- `runtime/tiles/{z}/{x}/{y}.bin` stores compiled runtime packs.
- `route-overlays/{routeFingerprint}/{z}/{x}/{y}-{fetchedAtMillis}.bin` stores route-specific derived overlays.
- Cache deletion removes raw tile files and derived files for the same tile.
- `backup_rules.xml` and `data_extraction_rules.xml` exclude app data from Android backup and device transfer.

## Side-Effect SSoTs

- Route URI persistence and app preferences: [AppStateStore.kt](app/src/main/java/dev/ra/geepee/AppStateStore.kt)
- GPX parsing and persisted URI grants: [RouteRepository.kt](app/src/main/java/dev/ra/geepee/RouteRepository.kt)
- Async route-load workflow: [RouteLoadCoordinator.kt](app/src/main/java/dev/ra/geepee/RouteLoadCoordinator.kt)
- Session and permission transitions: [SessionState.kt](app/src/main/java/dev/ra/geepee/SessionState.kt)
- Location and heading subscriptions: [LiveTrackingController.kt](app/src/main/java/dev/ra/geepee/LiveTrackingController.kt)
- Live tracking cadence: [LiveTrackingPolicy.kt](app/src/main/java/dev/ra/geepee/LiveTrackingPolicy.kt)
- Tile ID, status, geometry, and serialization model: [TileContext.kt](app/src/main/java/dev/ra/geepee/TileContext.kt)
- Display/data tile resolution: [TileResolutionPolicy.kt](app/src/main/java/dev/ra/geepee/TileResolutionPolicy.kt)
- Overpass fetches and tile-cache files: [TileContextRepository.kt](app/src/main/java/dev/ra/geepee/TileContextRepository.kt)
- Download concurrency, cancellation, progress, and terminal results: [TileDownloadCoordinator.kt](app/src/main/java/dev/ra/geepee/TileDownloadCoordinator.kt)
- Route-context and nearby-way derived-cache workflow: [RouteContextCoordinator.kt](app/src/main/java/dev/ra/geepee/RouteContextCoordinator.kt)
- Tile deletion policy and dialog copy: [TilePrunePolicy.kt](app/src/main/java/dev/ra/geepee/TilePrunePolicy.kt)
- Tile selection state machine: [PreviewTileSelection.kt](app/src/main/java/dev/ra/geepee/PreviewTileSelection.kt)
- Gesture classification: [RouteCanvasGestures.kt](app/src/main/java/dev/ra/geepee/RouteCanvasGestures.kt)
- External map intents: [MapIntents.kt](app/src/main/java/dev/ra/geepee/MapIntents.kt)
- Screen pinning: [ScreenPinning.kt](app/src/main/java/dev/ra/geepee/ScreenPinning.kt)
- Final UI-state projection: [UiProjection.kt](app/src/main/java/dev/ra/geepee/UiProjection.kt)

## Correctness Invariants

- Route matching works from GPX route geometry and location fixes, independent of downloaded tile context.
- Map-info uses cached tile context and derived overlays. Runtime `Error`, `TooLarge`, and `Downloading` snapshots do not count as cached coverage.
- `TooLarge` is retryable UI state. It is not written to `manifest.json` and does not poison selected, cached, partial, or delete-unused semantics.
- Display tile outlines and data tile downloads are related by `TileResolutionPolicy`, never by hard-coded zoom checks in UI code.
- The same `DownloadTileId` identifies cache files, download status, selection, deletion, overlay derivation, and map-info coverage.
- A cached tile can expose route context beyond its visible edge because fetch bounds include configured halos.
- Delete-selected removes exactly selected cached tiles. Delete-unused protects active downloads, current route/view coverage, and recently accessed cached tiles.
- Route-overlay cache keys include route fingerprint, tile ID, and source tile fetch time, so derived map-info follows both route changes and tile refreshes.
- UI gestures classify tap, long-tap, pan, and double-tap through [RouteCanvasGestures.kt](app/src/main/java/dev/ra/geepee/RouteCanvasGestures.kt), not ad hoc composable state.

## Test Anchors

- Route/session/loading: [RouteRepositoryTest.kt](app/src/test/java/dev/ra/geepee/RouteRepositoryTest.kt), [RouteLoadCoordinatorTest.kt](app/src/test/java/dev/ra/geepee/RouteLoadCoordinatorTest.kt), [SessionStateTest.kt](app/src/test/java/dev/ra/geepee/SessionStateTest.kt)
- Route math/matching: [RouteMatcherTest.kt](app/src/test/java/dev/ra/geepee/RouteMatcherTest.kt), [RouteMathTest.kt](app/src/test/java/dev/ra/geepee/RouteMathTest.kt), [HeadingSelectionTest.kt](app/src/test/java/dev/ra/geepee/HeadingSelectionTest.kt)
- Tiles/downloads/cache: [TileContextTest.kt](app/src/test/java/dev/ra/geepee/TileContextTest.kt), [TileContextRepositoryTest.kt](app/src/test/java/dev/ra/geepee/TileContextRepositoryTest.kt), [TileDownloadCoordinatorTest.kt](app/src/test/java/dev/ra/geepee/TileDownloadCoordinatorTest.kt)
- Map-info/overlays: [RouteContextCoordinatorTest.kt](app/src/test/java/dev/ra/geepee/RouteContextCoordinatorTest.kt), [RouteContextPipelineTest.kt](app/src/test/java/dev/ra/geepee/RouteContextPipelineTest.kt), [RouteMapInfoRegressionTest.kt](app/src/test/java/dev/ra/geepee/RouteMapInfoRegressionTest.kt), [RouteMapInfoPerformanceContractTest.kt](app/src/test/java/dev/ra/geepee/RouteMapInfoPerformanceContractTest.kt)
- Tile deletion/selection/gestures: [TileDeletePlanTest.kt](app/src/test/java/dev/ra/geepee/TileDeletePlanTest.kt), [TilePrunePolicyTest.kt](app/src/test/java/dev/ra/geepee/TilePrunePolicyTest.kt), [PreviewTileSelectionStateTest.kt](app/src/test/java/dev/ra/geepee/PreviewTileSelectionStateTest.kt), [RouteCanvasGesturesTest.kt](app/src/test/java/dev/ra/geepee/RouteCanvasGesturesTest.kt)
- UI projection: [UiProjectionTest.kt](app/src/test/java/dev/ra/geepee/UiProjectionTest.kt), [GeePeeScreenStateTest.kt](app/src/test/java/dev/ra/geepee/GeePeeScreenStateTest.kt)

## Fast Audit Checks

Network-capable paths:

```bash
rg -n "HttpURLConnection|URL\\(|OVERPASS_ENDPOINT|INTERNET|openstreetmap.org|Intent\\(Intent.ACTION_VIEW" app scripts
```

Persistence and app-private files:

```bash
rg -n "SharedPreferences|getSharedPreferences|openInputStream|takePersistableUriPermission|releasePersistableUriPermission|filesDir|writeText|writeBytes|readText|readBytes|delete\\(" app/src/main
```

Background execution surface:

```bash
rg -n "Service|ForegroundService|WorkManager|AlarmManager|ACCESS_BACKGROUND_LOCATION|RECEIVE_BOOT_COMPLETED" app/src/main
```

Tile/cache SSoT drift:

```bash
rg -n "DownloadTileId|TileDownloadStatus|TileResolutionPolicy|TilePrunePolicy|RouteContextCoordinator|TileContextRepository" app/src/main app/src/test
```
