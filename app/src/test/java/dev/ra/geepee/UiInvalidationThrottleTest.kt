package dev.ra.geepee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiInvalidationThrottleTest {
    @Test
    fun firstThrottledInvalidationFlushesImmediately() {
        val scheduler = FakeUiInvalidationScheduler()
        val clock = FakeClock()
        var invalidationCount = 0
        val throttle = UiInvalidationThrottle(
            minIntervalMillis = 150L,
            scheduler = scheduler,
            clockMillis = clock::nowMillis,
        ) {
            invalidationCount += 1
        }

        throttle.invalidateThrottled()

        assertEquals(1, invalidationCount)
        assertFalse(scheduler.hasPendingTasks())
    }

    @Test
    fun throttledInvalidationSchedulesOnlyOnePendingFlushWithinInterval() {
        val scheduler = FakeUiInvalidationScheduler()
        val clock = FakeClock()
        var invalidationCount = 0
        val throttle = UiInvalidationThrottle(
            minIntervalMillis = 150L,
            scheduler = scheduler,
            clockMillis = clock::nowMillis,
        ) {
            invalidationCount += 1
        }

        throttle.invalidateNow()
        clock.advanceBy(25L)
        scheduler.advanceClockTo(clock.nowMillis())

        throttle.invalidateThrottled()
        throttle.invalidateThrottled()

        assertEquals(1, invalidationCount)
        assertEquals(1, scheduler.pendingTaskCount)

        clock.advanceBy(124L)
        scheduler.advanceClockTo(clock.nowMillis())
        scheduler.runDueTasks()
        assertEquals(1, invalidationCount)

        clock.advanceBy(1L)
        scheduler.advanceClockTo(clock.nowMillis())
        scheduler.runDueTasks()
        assertEquals(2, invalidationCount)
        assertFalse(scheduler.hasPendingTasks())
    }

    @Test
    fun invalidateNowCancelsPendingFlushAndRunsImmediately() {
        val scheduler = FakeUiInvalidationScheduler()
        val clock = FakeClock()
        var invalidationCount = 0
        val throttle = UiInvalidationThrottle(
            minIntervalMillis = 150L,
            scheduler = scheduler,
            clockMillis = clock::nowMillis,
        ) {
            invalidationCount += 1
        }

        throttle.invalidateNow()
        clock.advanceBy(25L)
        scheduler.advanceClockTo(clock.nowMillis())
        throttle.invalidateThrottled()
        assertTrue(scheduler.hasPendingTasks())

        clock.advanceBy(10L)
        scheduler.advanceClockTo(clock.nowMillis())
        throttle.invalidateNow()

        assertEquals(2, invalidationCount)
        assertFalse(scheduler.hasPendingTasks())
    }

    private class FakeClock {
        private var currentMillis = 0L

        fun nowMillis(): Long = currentMillis

        fun advanceBy(deltaMillis: Long) {
            currentMillis += deltaMillis
        }
    }

    private class FakeUiInvalidationScheduler : UiInvalidationScheduler {
        private data class ScheduledTask(
            val task: Runnable,
            val dueAtMillis: Long,
        )

        private val scheduledTasks = linkedMapOf<Runnable, ScheduledTask>()
        private var currentMillis = 0L

        val pendingTaskCount: Int
            get() = scheduledTasks.size

        override fun postDelayed(task: Runnable, delayMillis: Long) {
            scheduledTasks[task] = ScheduledTask(
                task = task,
                dueAtMillis = currentMillis + delayMillis,
            )
        }

        override fun removeCallbacks(task: Runnable) {
            scheduledTasks.remove(task)
        }

        fun hasPendingTasks(): Boolean = scheduledTasks.isNotEmpty()

        fun runDueTasks() {
            val dueTasks = scheduledTasks.values
                .filter { scheduled -> scheduled.dueAtMillis <= currentMillis }
                .sortedBy(ScheduledTask::dueAtMillis)
            dueTasks.forEach { scheduled ->
                scheduledTasks.remove(scheduled.task)
                scheduled.task.run()
            }
        }

        fun advanceClockTo(millis: Long) {
            currentMillis = millis
        }
    }
}
