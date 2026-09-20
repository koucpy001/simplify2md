package io.github.koucpy001.simplify2md.bridge

import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/**
 * Transport core for the Kotlin-to-JS bridge: dispatch decisions, the pending
 * registry, the ready gate and error shaping. It has no Android dependency so
 * every rule here runs under plain JVM JUnit tests; [Bridge] supplies the WebView
 * glue (coroutine scopes and `evaluateJavascript`).
 */
class BridgeTransport(
    private val effects: Effects,
    handlers: Map<String, BridgeHandler> = emptyMap(),
) {

    /** Side effects the glue performs; unit tests record calls synchronously. */
    interface Effects {
        fun resolve(requestId: String, json: String)
        fun reject(requestId: String, message: String)
        fun emit(name: String, payloadJson: String)
    }

    /** Result of [decide]. */
    sealed interface CallDecision {
        data class Reject(val message: String) : CallDecision
        data class Dispatch(val handler: BridgeHandler, val args: JSONArray) : CallDecision
    }

    private val handlers = ConcurrentHashMap<String, BridgeHandler>(handlers)
    private val pending = PendingRegistry()
    private val readyQueue = ReadyEventQueue()

    /**
     * Invoked ONCE per JS context when [markReady] first flips ready (after the
     * queued events were drained). The host uses it to flush the cold-start
     * shared-text buffer (todo 18): that buffer must be dispatched after
     * bridge-ready but does NOT travel through the queue itself. [reset] clears
     * the flag so a reloaded page notifies again.
     */
    var readyListener: (() -> Unit)? = null
    private var readyListenerFired = false

    fun registerHandler(method: String, handler: BridgeHandler) {
        require(BridgeMethods.isKnown(method)) { "not a whitelisted bridge method: $method" }
        handlers[method] = handler
    }

    /**
     * Validates and plans a call. Unknown, malformed, unimplemented, duplicate
     * and over-capacity calls are rejected without invoking anything; a valid
     * call is tracked and returned for the glue to execute on the IO dispatcher.
     */
    fun decide(requestId: String, method: String, argsJson: String?): CallDecision {
        if (requestId.isEmpty()) return CallDecision.Reject("invalid requestId")
        if (!BridgeMethods.isKnown(method)) {
            return CallDecision.Reject("unknown bridge method: $method")
        }
        val args = BridgeCodec.parseArgs(argsJson).getOrElse {
            return CallDecision.Reject(it.message ?: "invalid bridge arguments")
        }
        val handler = handlers[method]
            ?: return CallDecision.Reject("bridge method not implemented: $method")
        return when (pending.track(requestId)) {
            TrackOutcome.DUPLICATE -> CallDecision.Reject("duplicate requestId: $requestId")
            TrackOutcome.CAPACITY_EXCEEDED -> CallDecision.Reject("bridge overloaded")
            TrackOutcome.TRACKED -> CallDecision.Dispatch(handler, args)
        }
    }

    /** Rejects a call that [decide] refused, without touching the registry. */
    fun rejectNow(requestId: String, message: String) {
        effects.reject(requestId, message)
    }

    /**
     * Settles a dispatched call. False means the registry entry is gone (the
     * Activity was reset while the handler was in flight) and the callback must
     * be dropped so nothing crashes on a stale request.
     */
    fun complete(requestId: String, result: Result<Any?>): Boolean {
        if (!pending.settle(requestId)) return false
        result.fold(
            onSuccess = { effects.resolve(requestId, BridgeCodec.encode(it)) },
            onFailure = { effects.reject(requestId, errorMessage(it)) },
        )
        return true
    }

    /** Queues [name] until ready, or emits it immediately when already ready. */
    fun emitEvent(name: String, payloadJson: String = "null") {
        if (!readyQueue.offer(BridgeEvent(name, payloadJson))) {
            effects.emit(name, payloadJson)
        }
    }

    /** Marks JS ready and replays events queued while it was not. */
    fun markReady() {
        for (event in readyQueue.markReady()) {
            effects.emit(event.name, event.payloadJson)
        }
        if (!readyListenerFired) {
            readyListenerFired = true
            readyListener?.invoke()
        }
    }

    /**
     * Drops the registry and clears the ready flag while keeping undispatched
     * events for a later ready. Used on Activity destroy and process recreation
     * so no Promise is ever left permanently pending.
     */
    fun reset() {
        pending.clear()
        readyQueue.reset()
        readyListenerFired = false
    }

    fun isReady(): Boolean = readyQueue.isReady

    fun pendingCount(): Int = pending.size

    companion object {
        /** Go error semantics for a user cancel; the frontend matches `/cancelled/i`. */
        const val CANCELLED_MESSAGE: String = BridgeCancelledException.MESSAGE

        fun errorMessage(t: Throwable): String = when (t) {
            is BridgeCancelledException -> BridgeCancelledException.MESSAGE
            else -> t.message?.takeIf { it.isNotEmpty() } ?: t.javaClass.simpleName
        }
    }
}
