# Resource Self-Audit Design

GeePee can self-audit its own activity, data use, and cache footprint. It should present these as app-local usage stats, not as exact Android battery drain.

## Goals

- Show the user what GeePee is doing that can affect battery or data use.
- Keep the accounting local, private, and cheap.
- Attribute usage to app-owned actions: foreground tracking, heading sensing, Overpass downloads, cache storage, and map-info work.
- Separate exact counters from estimates.
- Make the data model testable without Compose or Android framework dependencies.

## Limits

- Exact battery percentage, mAh, or system-wide energy attribution is Android platform telemetry and is not available to a normal app.
- Android `TrafficStats` can report UID network counters since boot, but it cannot reliably explain which app action caused every byte.
- GeePee should therefore show exact app-instrumented counters for its own Overpass requests and storage, plus activity proxies for battery-relevant work.

## User Surface

Add `Usage stats` to both setup and movement menus.

The panel should show:

- `Tracking`: active foreground tracking time, current cadence, GPS fixes, network fixes, last fix age.
- `Heading`: sensor mode, accepted heading updates, optional raw sensor event count.
- `Downloads`: Overpass requests, downloaded bytes, failed bytes, TooLarge bytes, active downloads.
- `Storage`: cached tiles, raw tile bytes, runtime bytes, route-overlay bytes, total cache bytes.
- `Map info`: nearby-way queries, cache hits, overlay builds, last build time.

Suggested copy:

```text
Usage stats

Tracking
Foreground tracking: 18 min
Location cadence: every 4 sec / 8 m
Fixes: 124 GPS, 8 network
Heading updates: 1,420 accepted

Downloads
Overpass: 22 requests, 18.4 MB received
Too large: 2 requests, 24.0 MB received before stop
Active downloads: 1

Storage
Tiles: 31 cached
Cache: 42.8 MB total
Raw 18.2 MB, runtime 10.4 MB, map-info 14.2 MB
```

Add `Reset stats` if persisted lifetime counters are implemented.

## Data Model

Create `ResourceAudit.kt`.

Keep the core model pure:

```kotlin
internal data class ResourceAuditState(
    val session: TrackingAudit = TrackingAudit(),
    val heading: HeadingAudit = HeadingAudit(),
    val downloads: DownloadAudit = DownloadAudit(),
    val storage: StorageAudit = StorageAudit(),
    val mapInfo: MapInfoAudit = MapInfoAudit(),
)
```

Use small immutable value types and reducer-style functions:

- `onTrackingStarted(config, nowMillis)`
- `onTrackingStopped(nowMillis)`
- `onLocationFix(provider, nowMillis)`
- `onHeadingSensorEvent(accepted, nowMillis)`
- `onDownloadStarted(tileId, estimatedBytes, nowMillis)`
- `onDownloadProgress(tileId, downloadedBytes, contentLengthBytes, nowMillis)`
- `onDownloadFinished(tileId, result, bytesReceived, nowMillis)`
- `withStorageSnapshot(snapshot)`
- `onMapInfoQuery(result, durationMillis)`

The ViewModel owns the current `ResourceAuditState` and exposes a formatted projection through `GeePeeUiState`.

## Exact vs Estimated Counters

Exact:

- Tile response bytes read from Overpass input streams.
- Request body bytes sent to Overpass.
- Cached tile count.
- Raw/runtime/route-overlay file sizes.
- Location fixes delivered to the app.
- Heading updates accepted by GeePee.
- Map-info query/build durations measured by the app.

Estimated or proxy:

- Battery impact from active foreground tracking time.
- Battery impact from heading sensor activity.
- Network bytes outside the Overpass downloader.
- CPU cost of route matching and rendering.

UI should label estimated/proxy values as activity, cadence, or work counts, not energy.

## Instrumentation SSoTs

### Live Tracking

Update [LiveTrackingController.kt](../app/src/main/java/dev/ra/geepee/LiveTrackingController.kt) to accept an audit sink:

```kotlin
internal interface LiveTrackingAuditSink {
    fun onLocationTrackingStarted(config: LiveTrackingConfig, providerCount: Int)
    fun onLocationTrackingStopped()
    fun onLocationFix(provider: String?)
    fun onHeadingTrackingStarted(config: LiveTrackingConfig, sensorType: Int)
    fun onHeadingSensorEventAccepted()
    fun onHeadingTrackingStopped()
}
```

Do not update Compose state for every raw sensor event. Count raw sensor events inside the controller only if needed, and flush coarse summaries at most once per second or when the stats panel opens.

### Downloads

Update [TileContextRepository.kt](../app/src/main/java/dev/ra/geepee/TileContextRepository.kt):

- Count request body bytes in `openOverpassConnection`.
- Count every response byte read, including bytes read before `TooLarge`.
- Return or emit a terminal audit result for `Success`, `Error`, `TooLarge`, and `Cancelled`.

Update [TileDownloadCoordinator.kt](../app/src/main/java/dev/ra/geepee/TileDownloadCoordinator.kt):

- Count active downloads.
- Keep terminal result classification in one place.
- Do not infer downloaded bytes from progress UI snapshots; use repository byte accounting.

### Storage

Add a repository method:

```kotlin
internal data class TileCacheFootprint(
    val cachedTileCount: Int,
    val rawTileBytes: Long,
    val runtimeBytes: Long,
    val routeOverlayBytes: Long,
) {
    val totalBytes: Long = rawTileBytes + runtimeBytes + routeOverlayBytes
}
```

`TileContextRepository.cacheFootprint()` should scan `files/tile-context/v1` on demand. The stats panel can request a refresh when opened and after tile deletion/download completion.

### Map Info

Update [RouteContextCoordinator.kt](../app/src/main/java/dev/ra/geepee/RouteContextCoordinator.kt):

- Count nearby-way query starts.
- Count result-cache hits.
- Count overlay cache hits/misses/builds.
- Record last query duration and last overlay build duration.

Keep map-info audit separate from map-info UI availability; availability is user-facing coverage state, while audit is work accounting.

## Persistence

Start with current-process/session counters and on-demand storage footprint. This is the lowest-risk version and avoids extra writes.

If lifetime stats are useful, add `ResourceAuditStore` later:

- Store only aggregate counters.
- Do not store route names, locations, tile IDs, or timestamps beyond coarse reset time.
- Flush only on terminal events, app background, and explicit reset.
- Keep cache footprint computed from files, not persisted.

## UI Integration

Add:

- `ResourceAuditUiState` in [UiModels.kt](../app/src/main/java/dev/ra/geepee/UiModels.kt)
- `resourceAuditUiState(...)` formatter in `ResourceAuditFormatter.kt`
- `UsageStatsDialog.kt` for display
- menu item in [SetupChrome.kt](../app/src/main/java/dev/ra/geepee/SetupChrome.kt)
- menu item in [MovementChrome.kt](../app/src/main/java/dev/ra/geepee/MovementChrome.kt)

The dialog should be read-only for the first version.

## Testing

Pure unit tests:

- Reducer tests for every audit event.
- Formatting tests for bytes, durations, active downloads, and empty state.
- Tracking policy tests that verify cadence shown in the audit matches [LiveTrackingPolicy.kt](../app/src/main/java/dev/ra/geepee/LiveTrackingPolicy.kt).
- Download tests proving `TooLarge` records consumed bytes without marking a tile cached.
- Cancellation tests proving active download counts return to zero.
- Storage footprint tests with temp files for raw/runtime/overlay directories.
- Map-info tests proving cache hits and misses are counted separately.

Integration-style unit tests:

- `GeePeeViewModel` updates audit state after location fixes, heading updates, tile progress, tile success, tile error, tile deletion.
- Opening the stats dialog refreshes storage footprint.

Performance guardrails:

- No Compose recomposition per raw sensor event.
- No recursive directory scan on every frame or every location update.
- No audit persistence write per location fix.

## Rollout

1. Add pure model, formatter, and tests.
2. Add storage footprint scan and tests.
3. Instrument downloads and TooLarge byte accounting.
4. Instrument tracking and heading accepted-update counts.
5. Add read-only Usage stats dialog.
6. Instrument map-info work counters.
7. Consider persisted lifetime totals and Reset stats.

## Acceptance Criteria

- User can open `Usage stats` from setup and movement modes.
- Panel distinguishes battery-relevant activity from exact data/storage counters.
- Overpass success, error, cancellation, and TooLarge outcomes are counted separately.
- TooLarge bytes are visible and do not affect cached tile semantics.
- Cache footprint matches actual app files after download and deletion.
- Unit tests cover reducers, formatting, storage footprint, and download terminal classifications.
