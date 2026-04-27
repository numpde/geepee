package dev.ra.geepee

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlinx.coroutines.withTimeoutOrNull

internal data class RouteCanvasTapPolicy(
    val maxDoubleTapDistancePx: Float,
)

private sealed interface PressOutcome {
    data class Up(val position: Offset) : PressOutcome
    data class LongPress(val position: Offset) : PressOutcome
    data object Cancelled : PressOutcome
}

internal suspend fun PointerInputScope.detectRouteCanvasTapGestures(
    policy: RouteCanvasTapPolicy,
    onTap: (Offset) -> Unit,
    onDoubleTap: (Offset) -> Unit,
    onLongPress: (Offset) -> Unit = {},
) {
    awaitEachGesture {
        var pendingDown = awaitFirstDown()
        while (true) {
            when (val firstPressOutcome = awaitPressOutcome(pendingDown)) {
                PressOutcome.Cancelled -> return@awaitEachGesture
                is PressOutcome.LongPress -> {
                    onLongPress(firstPressOutcome.position)
                    waitForUpOrCancellation()
                    return@awaitEachGesture
                }
                is PressOutcome.Up -> {
                    val secondDown = awaitSecondDownOrNull()
                    if (secondDown == null) {
                        onTap(firstPressOutcome.position)
                        return@awaitEachGesture
                    }
                    if (!isBoundedDoubleTap(
                            firstTapPosition = firstPressOutcome.position,
                            secondTapPosition = secondDown.position,
                            maxDistancePx = policy.maxDoubleTapDistancePx,
                        )
                    ) {
                        onTap(firstPressOutcome.position)
                        pendingDown = secondDown
                        continue
                    }
                    when (awaitPressOutcome(secondDown)) {
                        PressOutcome.Cancelled -> {
                            onTap(firstPressOutcome.position)
                            return@awaitEachGesture
                        }
                        is PressOutcome.LongPress -> {
                            onTap(firstPressOutcome.position)
                            onLongPress(secondDown.position)
                            waitForUpOrCancellation()
                            return@awaitEachGesture
                        }
                        is PressOutcome.Up -> {
                            onDoubleTap(secondDown.position)
                            return@awaitEachGesture
                        }
                    }
                }
            }
        }
    }
}

private suspend fun AwaitPointerEventScope.awaitPressOutcome(
    down: PointerInputChange,
): PressOutcome {
    val longPressTimeoutMillis = viewConfiguration.longPressTimeoutMillis
    return withTimeoutOrNull(longPressTimeoutMillis) {
        val up = waitForUpOrCancellation()
        if (up == null) {
            PressOutcome.Cancelled
        } else {
            PressOutcome.Up(up.position)
        }
    } ?: PressOutcome.LongPress(down.position)
}

private suspend fun AwaitPointerEventScope.awaitSecondDownOrNull(): PointerInputChange? {
    return withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
        awaitFirstDown()
    }
}

internal fun isBoundedDoubleTap(
    firstTapPosition: Offset,
    secondTapPosition: Offset,
    maxDistancePx: Float,
): Boolean {
    val dx = firstTapPosition.x - secondTapPosition.x
    val dy = firstTapPosition.y - secondTapPosition.y
    return dx * dx + dy * dy <= maxDistancePx * maxDistancePx
}
