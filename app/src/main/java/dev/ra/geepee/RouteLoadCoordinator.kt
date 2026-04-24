package dev.ra.geepee

import java.util.concurrent.Executor

internal data class RouteLoadRequest<RouteRef>(
    val routeRef: RouteRef,
    val displayName: String?,
    val rememberSelection: Boolean,
    val fromRestore: Boolean,
)

internal sealed interface RouteLoadOutcome {
    data class Success(
        val loadedRoute: LoadedRoute,
    ) : RouteLoadOutcome

    data class Failure(
        val issueMessage: String,
        val clearRememberedRoute: Boolean,
    ) : RouteLoadOutcome
}

internal class RouteLoadCoordinator<RouteRef>(
    private val loadRoute: (RouteRef, String?) -> LoadedRoute,
    private val rememberRoute: (RouteRef, String) -> Unit,
    private val workExecutor: Executor,
    private val callbackExecutor: Executor,
    private val logFailure: (RouteRef, Throwable) -> Unit,
) {
    fun load(
        request: RouteLoadRequest<RouteRef>,
        onOutcome: (RouteLoadOutcome) -> Unit,
    ) {
        workExecutor.execute {
            val outcome = try {
                val loadedRoute = loadRoute(request.routeRef, request.displayName)
                if (request.rememberSelection) {
                    rememberRoute(request.routeRef, loadedRoute.displayName)
                }
                RouteLoadOutcome.Success(loadedRoute)
            } catch (error: Exception) {
                logFailure(request.routeRef, error)
                val reason = error.message ?: "Could not read that GPX file."
                RouteLoadOutcome.Failure(
                    issueMessage = "${error.javaClass.simpleName}: $reason",
                    clearRememberedRoute = request.fromRestore,
                )
            }

            callbackExecutor.execute {
                onOutcome(outcome)
            }
        }
    }
}
