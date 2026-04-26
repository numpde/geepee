package dev.ra.geepee

import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal sealed interface TileDownloadUpdate {
    data class Progress(
        val downloadedBytes: Long,
        val actualBytes: Long?,
    ) : TileDownloadUpdate

    data class Success(
        val snapshot: TileDownloadSnapshot,
    ) : TileDownloadUpdate

    data class Error(
        val message: String,
    ) : TileDownloadUpdate

    data object Cancelled : TileDownloadUpdate
}

internal class TileDownloadCoordinator(
    private val tileContextRepository: TileContextRepository,
    private val tileContextConfig: TileContextConfig,
    private val callbackExecutor: Executor,
    private val logTag: String,
) {
    private val tileDownloadExecutor: ExecutorService = Executors.newCachedThreadPool()

    private var nextRequestId = 1L
    private val activeDownloads = linkedMapOf<DownloadTileId, ActiveTileDownload>()

    fun startDownload(
        tileId: DownloadTileId,
        estimatedBytes: Long,
        onUpdate: (TileDownloadUpdate) -> Unit,
    ) {
        val requestId = synchronized(this) { nextRequestId++ }
        val cancellation = TileDownloadCancellation()
        val future = tileDownloadExecutor.submit {
            try {
                val cachedSnapshot = tileContextRepository.downloadTile(
                    tileId = tileId,
                    config = tileContextConfig,
                    cancellation = cancellation,
                ) { downloadedBytes, contentLengthBytes ->
                    dispatchProgressIfActive(
                        tileId = tileId,
                        requestId = requestId,
                        update = TileDownloadUpdate.Progress(
                            downloadedBytes = downloadedBytes,
                            actualBytes = contentLengthBytes,
                        ),
                        onUpdate = onUpdate,
                    )
                }
                dispatchTerminalIfActive(
                    tileId = tileId,
                    requestId = requestId,
                    update = TileDownloadUpdate.Success(
                        snapshot = cachedSnapshot.copy(
                            estimatedBytes = estimatedBytes,
                        ),
                    ),
                    onUpdate = onUpdate,
                )
            } catch (_: TileDownloadCancelledException) {
                // Cancellation is handled synchronously by cancelDownload().
            } catch (error: Exception) {
                Log.e(logTag, "Tile context download failed for $tileId", error)
                dispatchTerminalIfActive(
                    tileId = tileId,
                    requestId = requestId,
                    update = TileDownloadUpdate.Error(
                        message = error.message ?: "Download failed",
                    ),
                    onUpdate = onUpdate,
                )
            }
        }
        synchronized(this) {
            activeDownloads[tileId] = ActiveTileDownload(
                requestId = requestId,
                cancellation = cancellation,
                future = future,
            )
        }
    }

    fun cancelDownload(
        tileId: DownloadTileId,
        onUpdate: (TileDownloadUpdate) -> Unit,
    ) {
        val activeDownload = synchronized(this) {
            activeDownloads.remove(tileId)
        } ?: return
        activeDownload.cancellation.cancel()
        activeDownload.future.cancel(true)
        callbackExecutor.execute {
            onUpdate(TileDownloadUpdate.Cancelled)
        }
    }

    fun cancelAll() {
        val activeDownloadsSnapshot = synchronized(this) {
            val snapshot = activeDownloads.values.toList()
            activeDownloads.clear()
            snapshot
        }
        activeDownloadsSnapshot.forEach { activeDownload ->
            activeDownload.cancellation.cancel()
            activeDownload.future.cancel(true)
        }
    }

    fun shutdown() {
        cancelAll()
        tileDownloadExecutor.shutdownNow()
    }

    private fun dispatchProgressIfActive(
        tileId: DownloadTileId,
        requestId: Long,
        update: TileDownloadUpdate.Progress,
        onUpdate: (TileDownloadUpdate) -> Unit,
    ) {
        callbackExecutor.execute {
            if (isActiveDownload(tileId, requestId)) {
                onUpdate(update)
            }
        }
    }

    private fun dispatchTerminalIfActive(
        tileId: DownloadTileId,
        requestId: Long,
        update: TileDownloadUpdate,
        onUpdate: (TileDownloadUpdate) -> Unit,
    ) {
        callbackExecutor.execute {
            if (removeIfActive(tileId, requestId)) {
                onUpdate(update)
            }
        }
    }

    @Synchronized
    private fun isActiveDownload(
        tileId: DownloadTileId,
        requestId: Long,
    ): Boolean {
        return activeDownloads[tileId]?.requestId == requestId
    }

    @Synchronized
    private fun removeIfActive(
        tileId: DownloadTileId,
        requestId: Long,
    ): Boolean {
        val activeDownload = activeDownloads[tileId] ?: return false
        if (activeDownload.requestId != requestId) {
            return false
        }
        activeDownloads.remove(tileId)
        return true
    }
}

private data class ActiveTileDownload(
    val requestId: Long,
    val cancellation: TileDownloadCancellation,
    val future: Future<*>,
)
