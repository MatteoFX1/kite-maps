package app.kite

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.kite.core.*
import java.util.Locale
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

enum class UnitsMode(val label: String) {
    AUTO("Automatic"), METRIC("Kilometers"), IMPERIAL("Miles and feet"), YARDS("Miles and yards (UK)")
}

/** Everything in Settings, kept between launches. Each value is Compose state, so screens redraw when it changes. */
object AppSettings {
    private val d: SharedPreferences by lazy { KiteApp.app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }

    private class Pref<T>(val key: String, val read: (SharedPreferences) -> T, val write: (SharedPreferences.Editor, T) -> Unit) :
        ReadWriteProperty<Any?, T> {
        private var state: androidx.compose.runtime.MutableState<T>? = null
        private fun s() = state ?: mutableStateOf(read(d)).also { state = it }
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = s().value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            s().value = value
            d.edit().also { write(it, value) }.apply()
        }
    }

    private fun bool(key: String, def: () -> Boolean) =
        Pref(key, { if (it.contains(key)) it.getBoolean(key, false) else def() }, { e, v -> e.putBoolean(key, v) })
    private fun bool(key: String, def: Boolean = false) = bool(key) { def }
    private fun int(key: String, def: Int) = Pref(key, { it.getInt(key, def) }, { e, v -> e.putInt(key, v) })
    private fun place(key: String) = Pref(key, { p -> p.getString(key, null)?.let { PlaceCodec.decode(it) } },
        { e, v -> if (v == null) e.remove(key) else e.putString(key, PlaceCodec.encode(v)) })
    private fun places(key: String) = Pref(key, { p -> p.getString(key, null)?.let { PlaceCodec.decodeList(it) } ?: emptyList() },
        { e, v -> e.putString(key, PlaceCodec.encodeList(v)) })

    var theme: ThemeMode by Pref("theme", { p -> ThemeMode.entries.firstOrNull { it.name == p.getString("theme", null) } ?: ThemeMode.SYSTEM },
        { e, v -> e.putString("theme", v.name) })
    var units: UnitsMode by Pref("units", { p -> UnitsMode.entries.firstOrNull { it.name == p.getString("units", null) } ?: UnitsMode.AUTO },
        { e, v -> e.putString("units", v.name) })
    var voice by bool("voice", true)
    /** The voice button's middle setting, "Alerts only": remembered with `voice`, so the next drive starts the same. */
    var voiceAlertsOnly by bool("voiceAlertsOnly")
    var traffic by bool("traffic", true)
    var satellite by bool("satellite")
    /** Hill shading, and metro/rail lines in their colors. */
    var terrain by bool("terrain")
    var transitLines by bool("transitLines")
    var avoidTolls by bool("avoidTolls")
    var avoidHighways by bool("avoidHighways")
    /** "Use Kite without Google": search and routes from open data only. */
    var noGoogle by bool("noGoogle")
    var speedCameras by bool("speedCameras", true)
    var surveillanceCameras by bool("surveillanceCameras")
    var plateCameras by bool("plateCameras", true)
    /** Say "Speed camera ahead" while navigating. On for new users, except where it's against the law. */
    var cameraWarnings by bool("cameraWarnings") { !camerasRestricted }
    /** Say so when driving over the posted limit. Off by default, like Vela. */
    var speedingAlert by bool("speedingAlert")
    /** Live reports from Waze drivers. Off until the person says yes on the Waze screen. */
    var waze by bool("waze")
    var wazeAsked by bool("wazeAsked")
    var wazeKinds: Set<WazeAlert.Kind> by Pref("wazeKinds",
        { p -> p.getStringSet("wazeKinds", null)?.mapNotNull { k -> WazeAlert.Kind.entries.firstOrNull { it.key == k } }?.toSet()
            ?: setOf(WazeAlert.Kind.POLICE, WazeAlert.Kind.ACCIDENT, WazeAlert.Kind.HAZARD, WazeAlert.Kind.WEATHER) },
        { e, v -> e.putStringSet("wazeKinds", v.map { it.key }.toSet()) })
    var wazeOnMap by bool("wazeOnMap", true)
    var wazeVoice by bool("wazeVoice", true)
    /** Reports older than this are ignored, in minutes; 0 keeps everything Waze still lists. */
    var wazeMaxAge by int("wazeMaxAge", 120)
    /** The police heat map: on from the start, browsing and driving (Matteo: "heat map always on"). */
    var wazeHeatmap by bool("wazeHeatmap", true)
    var policeRisk by bool("policeRisk", true)
    var policeAircraft by bool("policeAircraft")
    var parkingButton by bool("parkingButton", true)
    /** Save where the car is when a drive ends, without pressing anything. */
    var autoParking by bool("autoParking", true)
    /** The drive in progress, kept so it can be offered again if the app closes halfway. */
    var trip: SavedTrip? by Pref("trip", { p -> p.getString("trip", null)?.let { SavedTrip.decode(it) } },
        { e, v -> if (v == null) e.remove("trip") else e.putString("trip", v.encode()) })
    /** Live camera photos (GitHub #15): camera dots on the map, and the cameras along a route before you go. */
    var liveCams by bool("liveCams", true)
    /** UK road traffic cameras (National Highways, TfL, Traffic Scotland, Traffic Wales, TrafficWatch NI). */
    var liveRoadCams by bool("liveRoadCams", true)
    /** Windy's scenic webcams, worldwide. */
    var liveWebcams by bool("liveWebcams", true)
    /** The row of camera photos along the route, before pressing Go. */
    var liveCamsOnRoute by bool("liveCamsOnRoute", true)
    var home: Place? by place("home")
    var work: Place? by place("work")
    /** Where the car is parked, with when in `statusText`. */
    var parking: Place? by place("parking")
    var welcomed by bool("welcomed")
    var defaultMode: TravelMode by Pref("defaultMode", { p -> TravelMode.entries.firstOrNull { it.raw == p.getInt("defaultMode", 0) } ?: TravelMode.DRIVE },
        { e, v -> e.putInt("defaultMode", v.raw) })
    var saved: List<Place> by places("saved")
    var recents: List<String> by Pref("recents", { p -> p.getString("recents", null)?.let { (Json.parse(it) as? List<*>)?.filterIsInstance<String>() } ?: emptyList() },
        { e, v -> e.putString("recents", Json.write(v)) })
    /** Places you opened lately, newest first. */
    var recentPlaces: List<Place> by places("recentPlaces")

    /** Countries where apps may not warn of speed cameras (France, Germany, Switzerland and a few more). */
    val camerasRestricted: Boolean get() = Locale.getDefault().country in setOf("FR", "DE", "CH", "CY", "SK", "TR")

    /** Waze belongs to Google, so "Use Kite without Google" turns it off too. */
    val wazeOn: Boolean get() = waze && !noGoogle
    /** Ask about Waze once: not to someone who chose no Google, since Waze is Google's. */
    val needsWazeChoice: Boolean get() = !wazeAsked && !noGoogle
    val wazePolice: Boolean get() = Waze.shares.contains(WazeAlert.Kind.POLICE)

    /** Live camera dots on the map: road cameras, and Windy's webcams when Kite has a key. */
    val showsRoadCams: Boolean get() = liveCams && liveRoadCams
    val showsWebcams: Boolean get() = liveCams && liveWebcams && WindyKey.present

    fun wants(k: WazeAlert.Kind) = wazeOn && wazeKinds.contains(k)

    /** Whether this report should show and warn: a wanted kind, and not too old. */
    fun shows(a: WazeAlert): Boolean =
        wants(a.kind) && (wazeMaxAge == 0 || (System.currentTimeMillis() - a.reportedAtMillis) <= wazeMaxAge * 60_000L)

    fun isSaved(p: Place) = saved.any { it.matchesNearby(p) }

    fun toggleSaved(p: Place) {
        saved = if (isSaved(p)) saved.filterNot { it.matchesNearby(p) } else listOf(p) + saved
    }

    fun renameSaved(id: String, name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        saved = saved.map { if (it.id == id) it.copy(name = n) else it }
    }

    fun addRecentPlace(p: Place) {
        if (listOf("pin:", "link:", "parking", "tap:", "demo").any { p.id.startsWith(it) }) return
        val slim = p.copy(distanceMeters = null, photoUrls = p.photoUrls.take(1), about = emptyList(), featuredReview = null)
        recentPlaces = (listOf(slim) + recentPlaces.filter { it.id != p.id && it.name != p.name }).take(15)
    }

    fun addRecent(q: String) {
        recents = (listOf(q) + recents.filterNot { it.equals(q, ignoreCase = true) }).take(12)
    }
}

/** A drive in progress: where to, by way of where, and when it was last being driven. */
data class SavedTrip(val destination: Place, val stops: List<Place>, val mode: Int, val atMillis: Long) {
    fun encode() = Json.write(mapOf("destination" to PlaceCodec.toMap(destination), "stops" to stops.map { PlaceCodec.toMap(it) },
        "mode" to mode, "at" to atMillis))

    companion object {
        fun decode(s: String): SavedTrip? {
            val m = Json.obj(s) ?: return null
            val to = (m["destination"] as? Map<*, *>)?.let { d -> PlaceCodec.fromMap(d.entries.associate { it.key.toString() to it.value }) } ?: return null
            return SavedTrip(to, m["stops"].asMaps()?.mapNotNull { PlaceCodec.fromMap(it) } ?: emptyList(),
                m["mode"].int() ?: 0, m["at"].num()?.toLong() ?: 0)
        }
    }
}

/** Places to and from JSON for saving. */
object PlaceCodec {
    fun toMap(p: Place): Map<String, Any?> = linkedMapOf(
        "id" to p.id, "name" to p.name, "lat" to p.location.lat, "lng" to p.location.lng, "source" to p.source,
        "category" to p.category, "address" to p.address, "rating" to p.rating, "reviewCount" to p.reviewCount,
        "priceText" to p.priceText, "website" to p.website, "phone" to p.phone, "statusText" to p.statusText,
        "openNow" to p.openNow, "permanentlyClosed" to p.permanentlyClosed, "hours" to p.hours, "photoUrls" to p.photoUrls,
        "featuredReview" to p.featuredReview, "editorialSummary" to p.editorialSummary,
        "about" to p.about.map { mapOf("title" to it.title, "items" to it.items) },
        "featureId" to p.featureId, "placeId" to p.placeId, "distanceMeters" to p.distanceMeters,
    ).filterValues { it != null }

    fun fromMap(m: Map<String, Any?>): Place? {
        val lat = m["lat"].num() ?: return null
        val lng = m["lng"].num() ?: return null
        return Place(
            id = m["id"].text() ?: return null, name = m["name"].text() ?: "", location = LatLng(lat, lng),
            source = m["source"].text() ?: "osm", category = m["category"].text(), address = m["address"].text(),
            rating = m["rating"].num(), reviewCount = m["reviewCount"].int(), priceText = m["priceText"].text(),
            website = m["website"].text(), phone = m["phone"].text(), statusText = m["statusText"].text(),
            openNow = m["openNow"] as? Boolean, permanentlyClosed = m["permanentlyClosed"] as? Boolean ?: false,
            hours = m["hours"].asList()?.filterIsInstance<String>() ?: emptyList(),
            photoUrls = m["photoUrls"].asList()?.filterIsInstance<String>() ?: emptyList(),
            featuredReview = m["featuredReview"].text(), editorialSummary = m["editorialSummary"].text(),
            about = m["about"].asMaps()?.map { AboutSection(it["title"].text() ?: "", it["items"].asList()?.filterIsInstance<String>() ?: emptyList()) } ?: emptyList(),
            featureId = m["featureId"].text(), placeId = m["placeId"].text(), distanceMeters = m["distanceMeters"].num(),
        )
    }

    fun encode(p: Place) = Json.write(toMap(p))
    fun decode(s: String) = Json.obj(s)?.let { fromMap(it) }
    fun encodeList(l: List<Place>) = Json.write(l.map { toMap(it) })
    fun decodeList(s: String) = Json.parse(s).asMaps()?.mapNotNull { fromMap(it) } ?: emptyList()
}
