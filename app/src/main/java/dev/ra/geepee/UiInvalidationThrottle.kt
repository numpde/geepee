package dev.ra.geepee

internal interface UiInvalidationScheduler {
    fun postDelayed(task: Runnable, delayMillis: Long)

    fun removeCallbacks(task: Runnable)
}

internal class UiInvalidationThrottle(
    private val minIntervalMillis: Long,
    private val scheduler: UiInvalidationScheduler,
    private val clockMillis: () -> Long,
    private val onInvalidate: () -> Unit,
) {
    private var lastInvalidatedAtMillis = Long.MIN_VALUE
    private var pending = false
    private val delayedInvalidation = Runnable {
        pending = false
        invalidateAt(clockMillis())
    }

    fun invalidateNow() {
        cancel()
        invalidateAt(clockMillis())
    }

    fun invalidateThrottled() {
        if (pending) {
            return
        }
        val nowMillis = clockMillis()
        val dueAtMillis = if (lastInvalidatedAtMillis == Long.MIN_VALUE) {
            nowMillis
        } else {
            maxOf(lastInvalidatedAtMillis + minIntervalMillis, nowMillis)
        }
        val delayMillis = dueAtMillis - nowMillis
        if (delayMillis <= 0L) {
            invalidateAt(nowMillis)
            return
        }
        pending = true
        scheduler.postDelayed(delayedInvalidation, delayMillis)
    }

    fun cancel() {
        if (!pending) {
            return
        }
        pending = false
        scheduler.removeCallbacks(delayedInvalidation)
    }

    private fun invalidateAt(timestampMillis: Long) {
        lastInvalidatedAtMillis = timestampMillis
        onInvalidate()
    }
}
