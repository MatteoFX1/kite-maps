package app.kite

/**
 * The lock screen's next turn on iPhone is a Live Activity, which Apple only lets Swift start. The
 * small Swift part of the iPhone app implements this and hands it to Kite at launch ([LiveTurns.bridge]).
 */
interface LiveTurnBridge {
    fun start(destination: String, state: TurnState)
    fun update(state: TurnState)
    fun end()
}

object LiveTurns {
    /** Set by the iPhone app's Swift side at launch. */
    var bridge: LiveTurnBridge? = null
}

actual object DriveSession {
    private var destination: String? = null
    private var started = false
    private var last: TurnState? = null
    private var lastAt = 0L

    actual fun start(destination: String) {
        this.destination = destination
        started = false
        last = null
        LiveTurns.bridge?.end()
    }

    actual fun show(state: TurnState) {
        val b = LiveTurns.bridge ?: return
        val d = destination ?: return
        if (!started) {
            started = true
            b.start(d, state)
            last = state; lastAt = elapsedRealtime()
            return
        }
        // Only when what's shown changes, and at most every two seconds (except on arrival).
        if (state == last || (elapsedRealtime() - lastAt < 2_000 && !state.arrived)) return
        last = state; lastAt = elapsedRealtime()
        b.update(state)
    }

    actual fun stop() {
        destination = null
        started = false
        last = null
        LiveTurns.bridge?.end()
    }
}
