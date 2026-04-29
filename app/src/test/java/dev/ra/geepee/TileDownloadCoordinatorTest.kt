package dev.ra.geepee

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileDownloadCoordinatorTest {
    @Test
    fun startDownloadDeliversSuccessForImmediatelyCompletingWorker() {
        val tileId = DownloadTileId(zoom = 10, x = 512, y = 512)
        val terminalLatch = CountDownLatch(1)
        val updates = mutableListOf<TileDownloadUpdate>()
        val coordinator = TileDownloadCoordinator(
            downloadWorker = TileDownloadWorker { _, onProgress, _ ->
                onProgress(120L, 120L)
                TileDownloadSnapshot(
                    status = TileDownloadStatus.Cached,
                    estimatedBytes = 120L,
                    actualBytes = 120L,
                )
            },
            callbackExecutor = Runnable::run,
            logTag = "TileDownloadCoordinatorTest",
        )

        try {
            coordinator.startDownload(tileId, estimatedBytes = 120L) { update ->
                updates += update
                if (update is TileDownloadUpdate.Success) {
                    terminalLatch.countDown()
                }
            }

            assertTrue(terminalLatch.await(2, TimeUnit.SECONDS))
            assertTrue(updates.any { it is TileDownloadUpdate.Progress })
            assertEquals(1, updates.count { it is TileDownloadUpdate.Success })
        } finally {
            coordinator.shutdown()
        }
    }

    @Test
    fun startDownloadSupersedesEarlierInFlightDownloadForSameTile() {
        val tileId = DownloadTileId(zoom = 10, x = 512, y = 512)
        val startedFirst = CountDownLatch(1)
        val allowFirstToFinish = CountDownLatch(1)
        val successLatch = CountDownLatch(1)
        val workerCalls = AtomicInteger(0)
        val updates = mutableListOf<TileDownloadUpdate>()
        val coordinator = TileDownloadCoordinator(
            downloadWorker = TileDownloadWorker { _, _, cancellation ->
                when (workerCalls.incrementAndGet()) {
                    1 -> {
                        startedFirst.countDown()
                        while (!cancellation.isCancelled) {
                            if (allowFirstToFinish.await(20, TimeUnit.MILLISECONDS)) {
                                break
                            }
                        }
                        if (cancellation.isCancelled) {
                            cancellation.throwIfCancelled()
                        }
                        TileDownloadSnapshot(
                            status = TileDownloadStatus.Cached,
                            estimatedBytes = 111L,
                            actualBytes = 111L,
                        )
                    }
                    else -> TileDownloadSnapshot(
                        status = TileDownloadStatus.Cached,
                        estimatedBytes = 222L,
                        actualBytes = 222L,
                    )
                }
            },
            callbackExecutor = Runnable::run,
            logTag = "TileDownloadCoordinatorTest",
        )

        try {
            coordinator.startDownload(tileId, estimatedBytes = 111L) { update ->
                updates += update
            }
            assertTrue(startedFirst.await(2, TimeUnit.SECONDS))

            coordinator.startDownload(tileId, estimatedBytes = 222L) { update ->
                updates += update
                if (update is TileDownloadUpdate.Success) {
                    successLatch.countDown()
                }
            }

            assertTrue(successLatch.await(2, TimeUnit.SECONDS))
            allowFirstToFinish.countDown()

            assertEquals(2, workerCalls.get())
            val successSnapshots = updates
                .filterIsInstance<TileDownloadUpdate.Success>()
                .map(TileDownloadUpdate.Success::snapshot)
            assertEquals(1, successSnapshots.size)
            assertEquals(222L, successSnapshots.single().actualBytes)
            assertFalse(successSnapshots.any { it.actualBytes == 111L })
        } finally {
            allowFirstToFinish.countDown()
            coordinator.shutdown()
        }
    }

    @Test
    fun startDownloadReportsTooLargeSeparatelyFromGenericErrors() {
        val tileId = DownloadTileId(zoom = 10, x = 512, y = 512)
        val terminalLatch = CountDownLatch(1)
        val updates = mutableListOf<TileDownloadUpdate>()
        val coordinator = TileDownloadCoordinator(
            downloadWorker = TileDownloadWorker { _, _, _ ->
                throw TileDownloadTooLargeException("Tile response too large")
            },
            callbackExecutor = Runnable::run,
            logTag = "TileDownloadCoordinatorTest",
        )

        try {
            coordinator.startDownload(tileId, estimatedBytes = 120L) { update ->
                updates += update
                if (update is TileDownloadUpdate.TooLarge) {
                    terminalLatch.countDown()
                }
            }

            assertTrue(terminalLatch.await(2, TimeUnit.SECONDS))
            assertEquals(1, updates.count { it is TileDownloadUpdate.TooLarge })
            assertEquals(0, updates.count { it is TileDownloadUpdate.Error })
        } finally {
            coordinator.shutdown()
        }
    }
}
