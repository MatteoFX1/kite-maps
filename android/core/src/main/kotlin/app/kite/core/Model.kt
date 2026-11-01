package app.kite.core

import java.net.URLEncoder
import java.text.Normalizer
import java.util.UUID

data class AboutSection(val title: String, val items: List<String>)

/** One place, as the place sheet shows it. Every field but name and location is optional:
 *  Google sends a different subset per business, and Photon sends almost none. */
data class Place(
    var id: String,
    var name: String,
    var location: LatLng,
    /** Where the place came from: "google", "osm", "postcodes.io". */
    var source: String,
    var category: String? = null,
    var address: String? = null,
    var rating: Double? = null,
    var reviewCount: Int? = null,
    var priceText: String? = null,
    var website: String? = null,
    var phone: String? = null,
    var statusText: String? = null,
    var openNow: Boolean? = null,
    var permanentlyClosed: Boolean = false,
    var hours: List<String> = emptyList(),
    var photoUrls: List<String> = emptyList(),
    var featuredReview: String? = null,
    var editorialSummary: String? = null,
    var about: List<AboutSection> = emptyList(),
    var featureId: String? = null,
    var placeId: String? = null,
    var distanceMeters: Double? = null,
) {
    /** The same nearby named feature across map and search providers. */
    fun matchesNearby(other: Place): Boolean {
        if (id == other.id) return true
        val a = fold(name.trim())
        val b = fold(other.name.trim())
        if (a.isEmpty() || a != b) return false
        return location.distanceTo(other.location) <= 150
    }

    /** A Google Maps link for the place, for sharing and for opening it in Google Maps. */
    val shareUrl: String
        get() {
            placeId?.let {
                return "https://www.google.com/maps/search/?api=1&query=" + enc(name) + "&query_place_id=" + enc(it)
            }
            return "https://www.google.com/maps/search/?api=1&query=${location.lat},${location.lng}"
        }

    companion object {
        fun fold(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}

enum class TravelMode(val raw: Int, val label: String, val osrmBackend: String, val osrmProfile: String) {
    DRIVE(0, "Drive", "routed-car", "driving"),
    BIKE(1, "Bike", "routed-bike", "bike"),
    WALK(2, "Walk", "routed-foot", "foot"),
}

enum class ManeuverType {
    DEPART, ARRIVE, STRAIGHT,
    TURN_LEFT, TURN_RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, SHARP_LEFT, SHARP_RIGHT, UTURN,
    MERGE, RAMP_LEFT, RAMP_RIGHT, FORK_LEFT, FORK_RIGHT, KEEP_LEFT, KEEP_RIGHT,
    ROUNDABOUT, UNKNOWN,
}

/** One lane: where it goes ("left", "straight", "slight right"…) and whether it's a lane for this maneuver. */
data class Lane(val indications: List<String>, val valid: Boolean)

data class Maneuver(
    var type: ManeuverType,
    var instruction: String,
    /** Where on the route line the maneuver happens. */
    var location: LatLng,
    /** Meters from the route start to this maneuver, along the line. */
    var alongMeters: Double,
    /** Length of the step this maneuver starts. */
    var distanceMeters: Double,
    var road: String? = null,
    var laneHint: String? = null,
    /** The lanes on the way into this maneuver, left to right, when OpenStreetMap maps them. */
    var lanes: List<Lane> = emptyList(),
    /** Which exit to take at a roundabout. */
    var exit: Int? = null,
    /** The roundabout's real layout, for drawing it. */
    var roundabout: RoundaboutShape? = null,
    /** The point where the route leaves a roundabout, shown with that roundabout's picture. */
    var leavesRoundabout: Boolean = false,
    /** Only the road's name changes here: kept for the road name, never shown or said. */
    var silent: Boolean = false,
    /** A slip road Google names without saying which side it leaves on. */
    var sideUnknown: Boolean = false,
)

/** The posted limit from [startMeters] on, in km/h; null where it isn't mapped. */
data class SpeedLimit(val startMeters: Double, val kmh: Double?)

/** A congested stretch of the route: level 1 slow, 2 heavy, 3 stopped. */
data class TrafficSpan(val level: Int, val startMeters: Double, val lengthMeters: Double)

data class Route(
    var mode: TravelMode,
    var polyline: List<LatLng>,
    var distanceMeters: Double,
    /** Seconds without traffic. */
    var typicalSeconds: Double,
    /** Seconds in current traffic, when Google knows it. */
    var trafficSeconds: Double?,
    var summary: String?,
    var maneuvers: MutableList<Maneuver>,
    var trafficSpans: List<TrafficSpan> = emptyList(),
    /** Speed limits along the route, from OpenStreetMap. */
    var speedLimits: List<SpeedLimit> = emptyList(),
    /** "google" or "osm". */
    var source: String,
    /** False when Google left steps out (it folds long motorway stretches away), so the turns can't be placed. */
    var stepsComplete: Boolean = true,
    val id: String = UUID.randomUUID().toString(),
) {
    val durationSeconds: Double get() = trafficSeconds ?: typicalSeconds

    /** The limit at [meters] along the route. */
    fun speedLimit(meters: Double): Double? = speedLimits.lastOrNull { it.startMeters <= meters }?.kmh

    /** How much slower than usual: 0 green, 1 amber, 2 red. */
    val delayLevel: Int
        get() {
            val t = trafficSeconds ?: return 0
            if (typicalSeconds <= 0) return 0
            val r = t / typicalSeconds
            return if (r > 1.35) 2 else if (r > 1.12) 1 else 0
        }

    /** A copy whose steps can be changed without touching this one (Swift structs copy on assignment). */
    fun deep(): Route = copy(maneuvers = maneuvers.map { it.copy() }.toMutableList())
}

fun String.capitalizedFirst(): String = if (isEmpty()) this else substring(0, 1).uppercase() + substring(1)

fun String.nilIfBlank(): String? = if (isBlank()) null else this
