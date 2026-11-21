package app.kite

import app.kite.core.*
import app.kite.ui.Banner

/** What the lock screen shows during a drive: the next turn and when you'll arrive. */
data class TurnState(
    /** Android's notification title and text. */
    val title: String,
    val text: String,
    /** The iPhone lock screen's: an SF Symbol name ("arrow.turn.up.left"), "300 yds", "Turn left"... */
    val symbol: String,
    val distance: String,
    val instruction: String,
    val road: String?,
    val arrival: String,
    val arrived: Boolean,
)

/**
 * A drive keeps going with the screen off and shows the next turn on the lock screen: Android's
 * "Driving with Kite" notification, the iPhone's Live Activity. Both are filled from the same [TurnState].
 */
expect object DriveSession {
    fun start(destination: String)
    fun show(state: TurnState)
    fun stop()
}

object TurnStates {
    fun of(n: Navigator): TurnState {
        val p = n.progress
        val m = n.nextManeuver
        val left = p?.remainingSeconds ?: n.route.durationSeconds
        val title: String
        val text: String
        if (n.arrived) {
            title = "You have arrived"; text = n.destination.name
        } else {
            title = if (m == null || p == null) "To ${n.destination.name}" else "${Fmt.distance(p.distanceToNext)} · ${SpokenText.forScreen(m.instruction)}"
            text = if (p == null) "Tap to go back to the map" else "Arrive ${Fmt.arrival(p.remainingSeconds)} · ${Fmt.distance(p.remainingMeters)} to go"
        }
        return TurnState(title, text,
            symbol = m?.let { symbol(it.type) } ?: "arrow.up",
            distance = p?.let { Fmt.distance(it.distanceToNext) } ?: "",
            instruction = if (n.arrived) "You've arrived" else Banner.headline(m),
            road = if (n.arrived) null else m?.road,
            arrival = Fmt.arrival(left),
            arrived = n.arrived)
    }

    /** The iPhone's arrow for a turn (an SF Symbol name). */
    fun symbol(t: ManeuverType): String = when (t) {
        ManeuverType.DEPART, ManeuverType.STRAIGHT, ManeuverType.UNKNOWN -> "arrow.up"
        ManeuverType.ARRIVE -> "mappin.circle.fill"
        ManeuverType.TURN_LEFT -> "arrow.turn.up.left"
        ManeuverType.TURN_RIGHT -> "arrow.turn.up.right"
        ManeuverType.SLIGHT_LEFT, ManeuverType.KEEP_LEFT, ManeuverType.RAMP_LEFT, ManeuverType.FORK_LEFT -> "arrow.up.left"
        ManeuverType.SLIGHT_RIGHT, ManeuverType.KEEP_RIGHT, ManeuverType.RAMP_RIGHT, ManeuverType.FORK_RIGHT -> "arrow.up.right"
        ManeuverType.SHARP_LEFT -> "arrow.down.left"
        ManeuverType.SHARP_RIGHT -> "arrow.down.right"
        ManeuverType.UTURN -> "arrow.uturn.down"
        ManeuverType.MERGE -> "arrow.triangle.merge"
        ManeuverType.ROUNDABOUT -> "arrow.triangle.2.circlepath"
    }
}
