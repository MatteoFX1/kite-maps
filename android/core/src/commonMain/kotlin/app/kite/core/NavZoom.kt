package app.kite.core

import kotlin.math.max
import kotlin.math.min

/**
 * How far out the driving map sits and how much it tilts, from your speed and the next turn.
 * On a long stretch it pulls back and tilts up so you see far ahead; in the last 300 m before a turn it
 * glides in and flattens so the whole junction and the road you turn onto fill the screen; after the
 * turn it eases back out. Moving faster keeps it further out, so a motorway exit still shows the slip road.
 * Same as the iPhone's NavZoom.
 */
object NavZoom {
    /** Meters across the screen, and the camera's tilt in degrees. */
    data class View(val across: Double, val pitch: Double)

    /** Where the zoom starts closing in before a turn, and where it is fully in. */
    const val NEAR_START = 300.0
    const val NEAR_END = 60.0

    /** The view for driving at [speed] m/s with the next maneuver [toNext] meters away, or a fixed close view on foot or by bike. */
    fun view(speed: Double, toNext: Double?, next: ManeuverType?, driving: Boolean): View {
        if (!driving) return View(300.0, 50.0)
        val v = max(0.0, speed)
        var across = min(1400.0, max(450.0, 300 + v * 30))
        var pitch = 50.0
        if (toNext != null && next != null) {
            val d = toNext
            val turning = next != ManeuverType.STRAIGHT && next != ManeuverType.ARRIVE &&
                next != ManeuverType.DEPART && next != ManeuverType.UNKNOWN
            if (turning && d < NEAR_START) {
                // 1 at 300 m out, 0 from 60 m in: slide from the normal view to the close one.
                val f = max(0.0, min(1.0, (d - NEAR_END) / (NEAR_START - NEAR_END)))
                val close = max(330.0, v * 14)
                if (close < across) across = close + (across - close) * f
                pitch = 35 + 15 * f
            } else {
                // A long way to the next turn: pull back and look further ahead, a bit more the longer it is.
                val f = max(0.0, min(1.0, (d - 600) / 800))
                across = min(1800.0, across * (1 + 0.25 * f))
                pitch = 50 + 5 * f
            }
            // Close to a roundabout, the whole of it and its exits fill the view.
            if (next == ManeuverType.ROUNDABOUT && d < 350) across = min(across, 380.0)
        }
        return View(across, pitch)
    }

    /** How far ahead the next lower limit shows: about 15 seconds at your speed, 200 to 600 m. */
    fun limitHeadsUp(speed: Double): Double = min(600.0, max(200.0, max(0.0, speed) * 15))
}

/** How close the driving map sits, picked in Settings. Normal is the zoom Kite always had. */
enum class MapZoom(val title: String, val scale: Double) {
    CLOSER("Closer", 0.7), NORMAL("Normal", 1.0), WIDER("Wider", 1.4);

    companion object {
        fun of(name: String?): MapZoom = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: NORMAL
    }
}

/** Which way the map looks: tilted toward the road ahead, flat facing ahead, or flat with north up. */
enum class MapLook(val title: String, val detail: String) {
    TILTED("3D view", "Tilted toward the road ahead"),
    FLAT("2D view", "Flat, facing the way you go"),
    NORTH("North up", "Flat, with north at the top");

    /** The next one for the map button: 3D, then 2D, then north up, then round again. */
    val next: MapLook get() = when (this) { TILTED -> FLAT; FLAT -> NORTH; NORTH -> TILTED }

    companion object {
        fun of(name: String?): MapLook = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: TILTED
    }
}

/** This view with the zoom setting and the map button's choice applied. */
fun NavZoom.View.applying(zoom: MapZoom, look: MapLook): NavZoom.View =
    NavZoom.View(across * zoom.scale, if (look == MapLook.TILTED) pitch else 0.0)
