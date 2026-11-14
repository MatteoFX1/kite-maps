package app.kite

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import app.kite.core.*
import kotlinx.coroutines.*
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.*

/** The dock's pages. */
enum class AppTab { MAP, SAVED, SETTINGS }

/** Which of your places the "pick a place" sheet is choosing. */
enum class PickKind { HOME, WORK }

enum class SheetDetent { PEEK, HALF, FULL }

enum class SortMode(val label: String) { RELEVANCE("Relevance"), DISTANCE("Distance"), RATING("Rating") }

/** What a home-screen shortcut asked the app to do once it's open. */
enum class LaunchKind { HOME, WORK, CAR }

data class Category(val label: String, val icon: ImageVector, val query: String) {
    companion object {
        /** British words where units are British: petrol, supermarkets, chemists, cash machines. */
        val all: List<Category> get() = if (Fmt.yards) british else american

        val british = listOf(
            Category("Restaurants", Icons.Filled.Restaurant, "restaurants"),
            Category("Coffee", Icons.Filled.LocalCafe, "coffee"),
            Category("Petrol", Icons.Filled.LocalGasStation, "petrol station"),
            Category("Supermarkets", Icons.Filled.ShoppingCart, "supermarket"),
            Category("Pubs", Icons.Filled.SportsBar, "pub"),
            Category("Hotels", Icons.Filled.Hotel, "hotels"),
            Category("Parking", Icons.Filled.LocalParking, "car park"),
            Category("EV charging", Icons.Filled.EvStation, "ev charging station"),
            Category("Chemists", Icons.Filled.LocalPharmacy, "pharmacy"),
            Category("Cash machines", Icons.Filled.LocalAtm, "atm"),
        )
        val american = listOf(
            Category("Restaurants", Icons.Filled.Restaurant, "restaurants"),
            Category("Coffee", Icons.Filled.LocalCafe, "coffee"),
            Category("Gas", Icons.Filled.LocalGasStation, "gas stations"),
            Category("Groceries", Icons.Filled.ShoppingCart, "grocery stores"),
            Category("Hotels", Icons.Filled.Hotel, "hotels"),
            Category("Parking", Icons.Filled.LocalParking, "parking"),
            Category("Pharmacies", Icons.Filled.LocalPharmacy, "pharmacy"),
            Category("ATMs", Icons.Filled.LocalAtm, "atm"),
        )
    }
}

/** Directions to one place: routes for every mode, the one picked, and where from. */
data class DirectionsState(
    val destination: Place,
    /** True when the trip goes from the place back to you. */
    val reversed: Boolean = false,
    val mode: TravelMode,
    val routes: Map<TravelMode, List<Route>> = emptyMap(),
    val loading: Set<TravelMode> = emptySet(),
    val errors: Map<TravelMode, String> = emptyMap(),
    val selected: Int = 0,
    /** Places to stop at on the way, in order. */
    val stops: List<Place> = emptyList(),
    /** The Transit tab is showing (its trips come from Transitous, not the road routers). */
    val transit: Boolean = false,
    val trips: List<TransitTrip>? = null,
    val tripsError: String? = null,
    /** Null for "leave now". */
    val transitTime: Long? = null,
    val arriveBy: Boolean = false,
    val trip: Int = 0,
) {
    val current: List<Route> get() = if (transit) emptyList() else routes[mode] ?: emptyList()
    val route: Route? get() = current.getOrNull(selected) ?: current.firstOrNull()
    val pickedTrip: TransitTrip? get() = if (transit) trips?.let { it.getOrNull(trip) ?: it.firstOrNull() } else null
}

/** The whole app's state; the map and every sheet read it. */
class AppModel {
    val settings = AppSettings
    val location = LocationService
    val scope = MainScope()

    var query by mutableStateOf("")
    /** Autocomplete while typing: Google's, or OpenStreetMap's without Google. */
    var suggestions by mutableStateOf(SuggestParser.Result())
    private var suggestTask: Job? = null
    var results by mutableStateOf<List<Place>>(emptyList())
    var resultsTitle by mutableStateOf("")
    var selected by mutableStateOf<Place?>(null)
    var searching by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)
    var detent by mutableStateOf(SheetDetent.HALF)
    var openNowOnly by mutableStateOf(false)
    var rating4Only by mutableStateOf(false)
    var sort by mutableStateOf(SortMode.RELEVANCE)
    var directions by mutableStateOf<DirectionsState?>(null)
    var nav by mutableStateOf<Navigator?>(null)
    var showLayers by mutableStateOf(false)
    var tab by mutableStateOf(AppTab.MAP)
    /** Live Waze reports in view, for the "Live from Waze" chip; null until Waze answers. */
    var wazeLiveCount by mutableStateOf<Int?>(null)
    /** The police risk around you (0 to 100), for the chip on the map. */
    var areaRisk by mutableStateOf<Int?>(null)
    /** A Waze report tapped on the map, shown in a small card. */
    var wazeInfo by mutableStateOf<WazeAlert?>(null)
    var surveillanceInfo by mutableStateOf<SurveillanceCamera?>(null)
    /** The live camera whose photo is open (tapped on the map or in a route's camera row). */
    var liveCam by mutableStateOf<LiveCam?>(null)
    /** The route's cameras in order, when the open one was picked from the route's row (for next/previous). */
    var liveCamRoute by mutableStateOf<List<LiveCam>>(emptyList())
    /** The "Add stop" search sheet. */
    var addingStop by mutableStateOf(false)
    var picking by mutableStateOf<PickKind?>(null)
    var showSteps by mutableStateOf(false)
    /** "Start" on a place: begin the drive as soon as its route arrives. */
    var autoStart by mutableStateOf(false)
    /** A drive that was under way when the app last closed, offered again on the home map. */
    var resumeOffer by mutableStateOf<SavedTrip?>(null)
    /** This drive's parking spot was saved by itself on arriving. */
    var autoParked by mutableStateOf(false)
    private var tripSavedAt = 0L
    /** Bumped when the map should redraw overlays that don't come from a state value. */
    var mapTick by mutableIntStateOf(0)
    /** Which way the map faces, for the compass button. */
    var mapHeading by mutableStateOf(0.0)
    /** Street View opened from a place. */
    var streetView by mutableStateOf<StreetViewRequest?>(null)

    var map: MapLibreMap? = null
        set(v) { field = v; glide.map = v }
    /** The map view's size in dp, for framing. */
    var mapWidthDp = 400f
    var mapHeightDp = 800f
    /** Moves the nav arrow and camera every frame between GPS fixes. */
    val glide = NavGlide()
    private var routesRequest = 0
    private var transitRequest = 0
    private var transitLoadedAt = 0L
    private var searchTask: Job? = null

    init {
        location.onUpdate = { locationChanged(it) }
        // A drive cut short (the app closed, the phone restarted): offer it again, for two hours.
        settings.trip?.let { t ->
            if (!DemoScript.active && System.currentTimeMillis() - t.atMillis < 2 * 3600_000L) resumeOffer = t else settings.trip = null
        }
    }

    /** "Carry on": the same place, stops and way of travelling, started as soon as the route arrives. */
    fun resumeTrip() {
        val t = resumeOffer ?: return
        resumeOffer = null
        settings.trip = null
        selected = null
        results = emptyList()
        directions = DirectionsState(destination = t.destination, mode = TravelMode.entries.firstOrNull { it.raw == t.mode } ?: TravelMode.DRIVE,
            stops = t.stops)
        detent = SheetDetent.HALF
        autoStart = true
        loadAllModes()
    }

    fun dismissResume() {
        resumeOffer = null
        settings.trip = null
    }

    private fun rememberTrip(n: Navigator) {
        tripSavedAt = System.currentTimeMillis()
        settings.trip = SavedTrip(n.destination, n.stops, n.route.mode.raw, tripSavedAt)
    }

    fun edit(f: (DirectionsState) -> DirectionsState) { directions?.let { directions = f(it) } }

    val userLatLng: LatLng? get() = location.last?.coord

    // Search

    val visibleResults: List<Place>
        get() {
            var r = results
            if (openNowOnly) r = r.filter { it.openNow == true }
            if (rating4Only) r = r.filter { (it.rating ?: 0.0) >= 4 }
            return when (sort) {
                SortMode.RELEVANCE -> r
                SortMode.DISTANCE -> r.sortedBy { it.distanceMeters ?: Double.MAX_VALUE }
                SortMode.RATING -> r.sortedByDescending { it.rating ?: 0.0 }
            }
        }

    val viewport: Pair<LatLng, Double?>
        get() {
            val m = map ?: return Pair(userLatLng ?: LatLng(51.5074, -0.1278), null)
            val c = m.cameraPosition.target ?: return Pair(userLatLng ?: LatLng(51.5074, -0.1278), null)
            val b = m.projection.visibleRegion.latLngBounds
            val span = LatLng(b.latitudeSouth, b.longitudeWest).distanceTo(LatLng(b.latitudeNorth, b.longitudeEast)) / 1.4
            return Pair(LatLng(c.latitude, c.longitude), if (span.isFinite() && span > 0) span else null)
        }

    fun search(text: String? = null, title: String? = null) {
        val q = (text ?: query).trim()
        if (q.isEmpty()) return
        if (title == null && MapLinks.looksLikeLink(q)) { openLink(q); return }
        query = title ?: q
        settings.addRecent(title ?: q)
        searchTask?.cancel()
        selected = null
        directions = null
        searching = true
        notice = null
        openNowOnly = false
        rating4Only = false
        sort = SortMode.RELEVANCE
        val vp = viewport
        val useGoogle = !settings.noGoogle
        val user = userLatLng
        searchTask = scope.launch {
            val r = PlaceSearch.search(q, vp.first, vp.second, useGoogle)
            // Cleared or replaced while it was loading: leave the screen to the newer one.
            ensureActive()
            var places = r.places
            if (user != null) places = places.map { it.copy(distanceMeters = user.distanceTo(it.location)) }
            searching = false
            resultsTitle = title ?: q
            results = places
            if (places.isEmpty()) {
                notice = Connectivity.explain("No results for \"$q\" here. Try another word, or move the map.")
            } else if (r.googleError != null && useGoogle) {
                notice = "Google didn't answer, so these results are from OpenStreetMap."
            }
            if (places.size == 1) select(places[0]) else {
                detent = SheetDetent.HALF
                frame(places.map { it.location })
            }
        }
    }

    fun typed(text: String) {
        suggestTask?.cancel()
        val q = text.trim()
        if (q.length < 2 || MapLinks.looksLikeLink(q)) { suggestions = SuggestParser.Result(); return }
        val vp = viewport
        val useGoogle = !settings.noGoogle
        val user = userLatLng
        suggestTask = scope.launch {
            delay(250)
            var places = emptyList<Place>()
            var queries = emptyList<String>()
            if (useGoogle) {
                runCatching { GoogleClient.shared.suggest(q, vp.first, vp.second ?: 20_000.0) }.getOrNull()?.let {
                    places = it.places; queries = it.queries
                }
            }
            if (places.isEmpty() && queries.isEmpty()) Postcodes.lookup(q)?.let { places = listOf(it) }
            if (places.isEmpty() && queries.isEmpty()) places = runCatching { Photon.search(q, vp.first, 8) }.getOrDefault(emptyList())
            ensureActive()
            places = PlaceSearch.nearFirst(places, vp.first)
            if (user != null) places = places.map { it.copy(distanceMeters = user.distanceTo(it.location)) }
            val was = suggestions
            suggestions = SuggestParser.Result(
                SuggestParser.steady(places, was.places) { it.id },
                SuggestParser.steady(queries, was.queries) { it },
            )
        }
    }

    /** A pasted Google Maps, Waze or Apple Maps link (or coordinates): show the spot it points at. */
    fun openLink(text: String) {
        searchTask?.cancel()
        suggestTask?.cancel()
        suggestions = SuggestParser.Result()
        selected = null
        directions = null
        results = emptyList()
        notice = null
        searching = true
        query = "Opening link…"
        searchTask = scope.launch {
            val target = runCatching { MapLinks.resolve(text) }.getOrNull()
            searching = false
            when (target) {
                is MapLinks.Target.Spot -> if (target.name != null) {
                    query = target.name!!
                    resolve(Place(id = "link:${target.at.lat},${target.at.lng}", name = target.name!!, location = target.at, source = "osm"))
                } else {
                    // Keep where it is in the box, not an empty "Where to?" as if nothing was found.
                    query = String.format(java.util.Locale.US, "%.5f, %.5f", target.at.lat, target.at.lng)
                    dropPin(target.at, "Shared location")
                }
                is MapLinks.Target.Search -> {
                    target.near?.let { n -> map?.moveCamera(CameraUpdateFactory.newLatLngZoom(org.maplibre.android.geometry.LatLng(n.lat, n.lng), 14.0)) }
                    search(target.text)
                }
                null -> {
                    query = ""
                    notice = Connectivity.explain("Couldn't find a location in that link.")
                }
            }
        }
    }

    /** A place picked from the suggestions: show it now, then fill in its details from Google. */
    fun openSuggestion(place: Place) {
        suggestTask?.cancel()
        searchTask?.cancel()
        searching = false
        notice = null
        settings.addRecent(place.name)
        query = place.name
        results = emptyList()
        directions = null
        resolve(place)
    }

    fun clearSearch() {
        searchTask?.cancel()
        query = ""
        results = emptyList()
        resultsTitle = ""
        selected = null
        searching = false
        notice = null
    }

    fun select(place: Place, focus: Boolean = true) {
        var p = place
        settings.addRecentPlace(place)
        if (p.distanceMeters == null) userLatLng?.let { p = p.copy(distanceMeters = it.distanceTo(p.location)) }
        selected = p
        detent = SheetDetent.HALF
        if (!focus) return
        val m = map ?: return
        val zoom = max(MapCam.zoomFor(900.0, p.location.lat, mapWidthDp), min(m.cameraPosition.zoom, 18.0).let { if (it > 16) it else 0.0 })
        MapCam.animate(m, CameraPosition.Builder().target(p.location.ml()).zoom(zoom).tilt(0.0).bearing(0.0).build(),
            padding = intArrayOf(0, 120, 0, (mapHeightDp * 0.45f).toInt()), ms = 600)
    }

    fun closePlace() {
        selected = null
        if (results.isNotEmpty()) detent = SheetDetent.HALF
    }

    /** A tap on a place the basemap draws: look it up on Google by name at that spot. */
    fun resolveTapped(name: String, point: LatLng) {
        resolve(Place(id = "tap:${point.lat},${point.lng}", name = name, location = point, source = "osm"))
    }

    /** Show [placeholder] right away, then swap in Google's full place found at the same spot. */
    private fun resolve(placeholder0: Place) {
        val point = placeholder0.location
        val placeholder = placeholder0.copy(distanceMeters = userLatLng?.distanceTo(point))
        select(placeholder, focus = !placeholder.id.startsWith("tap:"))
        if (settings.noGoogle) return
        val q = listOfNotNull(placeholder.name, placeholder.address).joinToString(" ")
        val pid = placeholder.id
        scope.launch {
            val found = runCatching { GoogleClient.shared.search(q, point, 1_000.0, pages = 1) }.getOrDefault(emptyList())
            if (selected?.id != pid) return@launch
            // The same place by name too, not just the nearest: a café next door mustn't take its place.
            val best = found.filter { it.location.distanceTo(point) < 150 && similarNames(it.name, placeholder.name) }
                .minByOrNull { it.location.distanceTo(point) } ?: return@launch
            var b = best
            if (pid.startsWith("tap:")) b = b.copy(location = point)
            b = b.copy(distanceMeters = userLatLng?.distanceTo(b.location))
            selected = b
            settings.addRecentPlace(b)
        }
    }

    /**
     * A saved place: its "Open · Closes 10 pm" was true the day it was saved, not now. Show it
     * without that, then fill in today's from Google (left out with no internet).
     */
    fun openSaved(saved: Place) {
        val p = saved.copy(statusText = null, openNow = null)
        select(p)
        if (settings.noGoogle) return
        val q = listOfNotNull(p.name, p.address).joinToString(" ")
        scope.launch {
            val found = runCatching { GoogleClient.shared.search(q, p.location, 1_000.0, pages = 1) }.getOrDefault(emptyList())
            val fresh = found.firstOrNull { it.location.distanceTo(p.location) < 150 && similarNames(it.name, p.name) } ?: return@launch
            if (selected?.id == p.id) selected = selected?.copy(statusText = fresh.statusText, openNow = fresh.openNow,
                hours = fresh.hours, permanentlyClosed = fresh.permanentlyClosed)
        }
    }

    /** "Costa" and "Costa Coffee", "St Mary's Church" and "St. Mary's": every word of one name is in the other. */
    private fun similarNames(a: String, b: String): Boolean {
        fun words(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val x = words(a); val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return false
        // Whole words, so "Costa" isn't "Costas" and "Royal Hotel" isn't "Royal Cafe".
        return y.containsAll(x) || x.containsAll(y)
    }

    // My places

    /** A home-screen shortcut: directions home or to work, or back to the car. */
    fun perform(kind: LaunchKind) {
        scope.launch {
            repeat(20) { if (userLatLng == null) delay(250) }
            val target = when (kind) {
                LaunchKind.HOME -> settings.home
                LaunchKind.WORK -> settings.work
                LaunchKind.CAR -> settings.parking
            }
            if (target == null) {
                if (kind == LaunchKind.CAR) { nav?.say("No parking spot saved yet.") ?: run { notice = "No parking spot saved yet. Tap P on the map when you park." } }
                // Mid-drive, keep the trip going: a picker on top of it, cancelled, would lose it.
                else if (nav != null) nav?.say("No ${if (kind == LaunchKind.HOME) "home" else "work"} address saved yet. Set it in Saved after this drive.")
                else picking = if (kind == LaunchKind.HOME) PickKind.HOME else PickKind.WORK
                return@launch
            }
            // Only now that there's somewhere to go does the current drive end.
            if (nav != null) endNavigation()
            openDirections(target)
            if (kind == LaunchKind.CAR) edit { it.copy(mode = TravelMode.WALK) }
        }
    }

    /** Home, work, the parked car or a saved place, by id, for a tap on its map icon. */
    fun myPlace(id: String): Place? =
        listOfNotNull(settings.home, settings.work, settings.parking).firstOrNull { it.id == id } ?: settings.saved.firstOrNull { it.id == id }

    fun setHome(p: Place?) {
        settings.home = p?.copy(distanceMeters = null)
        if (p != null) notice = "Saved as your home."
    }

    fun setWork(p: Place?) {
        settings.work = p?.copy(distanceMeters = null)
        if (p != null) notice = "Saved as your work."
    }

    /** Remember where the car is: here, now, with the street name when one is found. */
    fun saveParking(quiet: Boolean = false) {
        val me = userLatLng ?: run {
            if (quiet) return
            notice = if (location.denied) "Location is off for Kite. Turn it on in Settings to save where you parked."
            else "Still finding your location. Try again in a moment."
            return
        }
        val p = Place(id = "parking:${System.currentTimeMillis() / 1000}", name = "Parked car", location = me, source = "osm",
            category = "Parked at ${Fmt.time(java.util.Date())}")
        settings.parking = p
        if (!quiet) notice = "Parking spot saved. Tap the P button to find your car."
        scope.launch {
            val found = Photon.reverse(me) ?: return@launch
            if (settings.parking?.id != p.id) return@launch
            settings.parking = settings.parking?.copy(address = listOfNotNull(found.name, found.address).joinToString(", "))
        }
    }

    fun clearParking() {
        if (selected?.id == settings.parking?.id) selected = null
        settings.parking = null
    }

    /** Long press: a dropped pin with the address there. */
    fun dropPin(point: LatLng, name: String = "Dropped pin") {
        val pin = Place(id = "pin:${point.lat},${point.lng}", name = name, location = point, source = "osm",
            category = String.format(java.util.Locale.US, "%.5f, %.5f", point.lat, point.lng))
        select(pin)
        scope.launch {
            val found = Photon.reverse(point) ?: return@launch
            if (selected?.id != pin.id) return@launch
            selected = pin.copy(address = listOfNotNull(found.name, found.address).joinToString(", "))
        }
    }

    // Directions

    fun openDirections(place: Place) {
        if (resumeOffer != null) dismissResume()
        autoStart = false
        directions = DirectionsState(destination = place, mode = settings.defaultMode)
        detent = SheetDetent.HALF
        loadAllModes()
    }

    fun closeDirections() {
        autoStart = false
        directions = null
        showSteps = false
        selected?.let { select(it) }
    }

    fun reverseDirections() {
        edit { it.copy(reversed = !it.reversed, routes = emptyMap(), selected = 0, trips = null, trip = 0) }
        loadAllModes()
    }

    fun setMode(m: TravelMode) {
        edit { it.copy(transit = false, mode = m, selected = 0) }
        settings.defaultMode = m
        frameRoute()
    }

    fun addStop(p: Place) {
        val d = directions ?: return
        if (d.stops.size >= 8) return
        edit { it.copy(stops = it.stops + p) }
        reloadRoutes()
    }

    fun removeStop(i: Int) {
        val d = directions ?: return
        if (i !in d.stops.indices) return
        edit { it.copy(stops = it.stops.filterIndexed { j, _ -> j != i }) }
        reloadRoutes()
    }

    /** 0 leave now, 1 depart at, 2 arrive by. */
    fun setTransitTime(kind: Int, atMillis: Long) {
        edit { it.copy(transitTime = if (kind == 0) null else atMillis, arriveBy = kind == 2) }
        loadTransit()
    }

    fun showTransit() {
        edit { it.copy(transit = true) }
        // "Leave now" times found a while ago may have left already: look again.
        if (directions?.transitTime == null && System.currentTimeMillis() - transitLoadedAt > 120_000) loadTransit()
        frameRoute()
    }

    fun selectTrip(i: Int) { edit { it.copy(trip = i) }; frameRoute() }

    fun selectRoute(i: Int) { edit { it.copy(selected = i) }; mapTick++ }

    fun reloadRoutes() {
        if (directions == null) return
        edit { it.copy(routes = emptyMap(), errors = emptyMap(), selected = 0) }
        loadAllModes()
    }

    private fun loadAllModes() {
        val d = directions ?: return
        val me = userLatLng
        if (me == null) {
            val why = if (location.denied) LOCATION_OFF_TEXT
                else if (location.systemOff) "Location is off on this phone. Turn it on in Settings to get directions from where you are."
                else "Waiting for your location…"
            edit { it.copy(errors = TravelMode.entries.associateWith { why }, tripsError = why, trips = emptyList()) }
            if (!location.denied) {
                location.start()
                scope.launch {
                    repeat(40) { if (userLatLng == null) delay(250) }
                    if (userLatLng != null && directions?.destination?.id == d.destination.id && directions?.routes?.isEmpty() == true) reloadRoutes()
                }
            }
            return
        }
        val (from, to) = if (d.reversed) d.destination.location to me else me to d.destination.location
        val via = (if (d.reversed) d.stops.reversed() else d.stops).map { it.location }
        routesRequest++
        val req = routesRequest
        val hw = settings.avoidHighways; val tolls = settings.avoidTolls; val google = !settings.noGoogle
        // Already driving (asked at a red light, say): no route that starts with a U-turn.
        val moving = location.last?.let { l -> if (l.speed >= 4 && l.courseKnown) l.course else null }
        val heading = if (d.reversed) null else moving
        for (m in TravelMode.entries) {
            edit { it.copy(loading = it.loading + m) }
            scope.launch {
                val r = runCatching {
                    Directions.routes(from, to, m, hw, tolls, google, via, if (m == TravelMode.DRIVE) heading else null, alternatives = true)
                }.getOrElse { Directions.Result(emptyList(), it.message) }
                if (directions?.destination?.id != d.destination.id || routesRequest != req) return@launch
                edit {
                    var e = it.errors
                    if (r.routes.isEmpty()) e = e + (m to Connectivity.explain(
                        if (r.error == "No route found.") "No route found." else "Couldn't get a route just now. Try again in a moment."))
                    // The fastest first, so it's the one picked (a bike route marked "Slower route" was picked
                    // while one 5 min faster sat below it).
                    it.copy(loading = it.loading - m, routes = it.routes + (m to r.routes.sortedBy { x -> x.durationSeconds }), errors = e)
                }
                if (m == directions?.mode) {
                    frameRoute()
                    if (autoStart && r.routes.isNotEmpty()) {
                        autoStart = false
                        startNavigation()
                    }
                }
            }
        }
        loadTransit()
    }

    /** Transit trips for the current trip, leaving now or at the picked time. */
    fun loadTransit() {
        val d = directions ?: return
        val me = userLatLng ?: return
        transitLoadedAt = System.currentTimeMillis()
        // A new request either way, so one still loading can't put back trips without the stops.
        transitRequest++
        // The planner can't go via stops: rather say so than show trains for a different trip.
        if (d.stops.isNotEmpty()) {
            edit { it.copy(trips = emptyList(), tripsError = "Public transport can't go via stops. Remove the stops to see trains and buses.") }
            return
        }
        val (from, to) = if (d.reversed) d.destination.location to me else me to d.destination.location
        val destId = d.destination.id
        val req = transitRequest
        edit { it.copy(trips = null, tripsError = null, trip = 0) }
        scope.launch {
            try {
                val trips = Transitous.plan(from, to, d.transitTime, d.arriveBy)
                if (directions?.destination?.id != destId || transitRequest != req) return@launch
                edit { it.copy(trips = trips, tripsError = if (trips.isEmpty()) "No transit trips found here." else null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (directions?.destination?.id != destId || transitRequest != req) return@launch
                edit { it.copy(trips = emptyList(), tripsError = Connectivity.explain("Couldn't reach the transit planner.")) }
            }
            if (directions?.transit == true) frameRoute()
        }
    }

    fun frameRoute() {
        directions?.pickedTrip?.let { t ->
            if (t.path.size >= 2) frame(t.path, bottomFraction = 0.42f)
            mapTick++
            return
        }
        val r = directions?.route ?: run { mapTick++; return }
        frame(r.polyline, bottomFraction = 0.42f, top = 170 + (directions?.stops?.size ?: 0) * 42)
    }

    /** Police risk within about a kilometre of you, from remembered and live Waze police reports. */
    fun updateAreaRisk(live: List<WazeAlert>) {
        val fix = location.last
        if (!settings.wazeOn || !settings.policeRisk || !settings.wazePolice || location.reducedAccuracy || fix == null ||
            fix.accuracy > 200 || fix.ageMs > 120_000) { areaRisk = null; return }
        val here = fix.coord
        val d = 0.0096 / max(cos(here.lat * PI / 180), 0.2) * 0.62
        val line = listOf(LatLng(here.lat, here.lng - d), LatLng(here.lat, here.lng + d))
        val near = live.count { it.kind == WazeAlert.Kind.POLICE && it.location.distanceTo(here) < 1_500 }
        areaRisk = PoliceRisk.score(line, WazeHistory.reports, near, radius = 900.0)
    }

    // Navigation

    fun startNavigation() {
        val d = directions ?: return
        val route = d.route ?: return
        if (d.reversed) return
        val n = Navigator(route, d.destination, d.stops, scope)
        nav = n
        n.onFinished = { if (nav === n) endNavigation() }
        // In a tunnel the Navigator carries on along the route by itself: move the arrow with it.
        n.onEstimate = { f ->
            if (nav === n) {
                n.handle(f, estimated = true)
                followCamera(n, f, animated = true)
                driveNote(n)
                mapTick++
            }
        }
        resumeOffer = null
        autoParked = false
        rememberTrip(n)
        location.setNavigating(true)
        DriveService.start(KiteApp.app)
        location.last?.let { n.handle(it); followCamera(n, it, animated = false) }
    }

    fun endNavigation() {
        nav?.stop()
        glide.stop()
        nav = null
        settings.trip = null
        location.setNavigating(false)
        DriveService.stop(KiteApp.app)
        directions = null
        showSteps = false
        resetCamera()
        mapTick++
    }

    fun recenterNav() {
        val n = nav ?: return
        n.following = true
        location.last?.let { followCamera(n, it, animated = true) }
    }

    /** Show the whole remaining trip; the camera stops following until re-centered. */
    fun overviewNav() {
        val n = nav ?: return
        n.following = false
        val line = n.progress?.let { Geo.slice(n.route.polyline, it.alongMeters, Geo.length(n.route.polyline)) } ?: n.route.polyline
        frame(line, bottomFraction = 0.25f, top = 200)
    }

    private fun locationChanged(f: Fix) {
        if (location.reducedAccuracy || f.accuracy > 200) areaRisk = null
        val n = nav ?: return
        n.handle(f)
        followCamera(n, f, animated = true)
        tripCheck(n)
        driveNote(n)
        mapTick++
    }

    /** The next turn and arrival time on the drive's notification (Android only: the iPhone has no such thing to fill). */
    private fun driveNote(n: Navigator) {
        val p = n.progress ?: return
        if (n.arrived) {
            DriveService.show(KiteApp.app, "You have arrived", n.destination.name)
            return
        }
        val m = n.nextManeuver
        val turn = if (m == null) "To ${n.destination.name}" else "${Fmt.distance(p.distanceToNext)} · ${SpokenText.forScreen(m.instruction)}"
        DriveService.show(KiteApp.app, turn, "Arrive ${Fmt.arrival(p.remainingSeconds)} · ${Fmt.distance(p.remainingMeters)} to go")
    }

    private fun tripCheck(n: Navigator) {
        if (n.arrived) {
            if (settings.trip != null) settings.trip = null
            // Where you parked, without having to find the P button while getting out of the car.
            if (!autoParked && n.route.mode == TravelMode.DRIVE && settings.autoParking && settings.parkingButton && userLatLng != null) {
                autoParked = true
                saveParking(quiet = true)
            }
        } else if (System.currentTimeMillis() - tripSavedAt > 60_000 || settings.trip?.stops?.size != n.stops.size) {
            rememberTrip(n)
        }
    }

    private fun followCamera(n: Navigator, f: Fix, animated: Boolean) {
        val p = n.progress
        val speed = max(0.0, f.speed)
        var across = if (n.route.mode == TravelMode.DRIVE) min(1400.0, max(450.0, 300 + speed * 30)) else 300.0
        // Close to a roundabout, zoom in so the whole of it and its exits fill the view.
        if (n.nextManeuver?.type == ManeuverType.ROUNDABOUT && p != null && p.distanceToNext < 350) across = min(across, 380.0)
        glide.fix(n, f, across, jump = !animated)
    }

    // Map

    fun centerOnUser(ctx: android.content.Context) {
        val m = map
        val loc = location.last
        if (m == null || loc == null) {
            if (location.denied) LocationService.openSettings(ctx) else location.start()
            return
        }
        MapCam.animate(m, CameraPosition.Builder().target(loc.coord.ml()).zoom(MapCam.zoomFor(1200.0, loc.lat, mapWidthDp))
            .tilt(0.0).bearing(0.0).build(), ms = 500)
    }

    private fun resetCamera() {
        val m = map ?: return
        m.setPadding(0, 0, 0, 0)
        val c = m.cameraPosition
        MapCam.animate(m, CameraPosition.Builder(c).tilt(0.0).bearing(0.0).padding(0.0, 0.0, 0.0, 0.0).build(), ms = 500)
    }

    fun frame(points: List<LatLng>, bottomFraction: Float = 0.45f, top: Int = 130) {
        val m = map ?: return
        if (points.isEmpty()) return
        if (points.size == 1) {
            MapCam.animate(m, CameraPosition.Builder().target(points[0].ml()).zoom(MapCam.zoomFor(1200.0, points[0].lat, mapWidthDp))
                .tilt(0.0).bearing(0.0).build(), ms = 300)
            return
        }
        val b = LatLngBounds.Builder().apply { points.forEach { include(it.ml()) } }.build()
        MapCam.fit(m, b, intArrayOf(40, top, 40, (mapHeightDp * bottomFraction + 20).toInt()), ms = 600)
    }

    companion object {
        const val LOCATION_OFF_TEXT = "Location is off for Kite. Turn it on in Settings to get directions from where you are."
    }
}

/** Street View asked for from a place: where, and what to call it. */
data class StreetViewRequest(val name: String, val pano: StreetView.Panorama, val heading: Double)
