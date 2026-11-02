package app.kite

import app.kite.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraUpdateFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp

/**
 * Screenshot runs only (the build starts Kite with an extra "demo" saying which screen): puts
 * the app in a known state so the build's emulator can take a picture of each screen.
 */
object DemoScript {
    var step: String? = null; private set
    /** The map drawer, for the made-up Waze reports. */
    var kite: KiteMap? = null
    val active get() = step != null

    fun start(model: AppModel, s: String) {
        step = s
        Voice.silent = true
        val st = AppSettings
        // Past the first-launch pages, unless that's what's being checked.
        if (s != "welcome" && s != "wazeintro") { st.welcomed = true; st.wazeAsked = true }
        model.scope.launch { run(model, s) }
    }

    private fun center(model: AppModel, lat: Double, lng: Double, zoom: Double, bearing: Double = 0.0) {
        model.map?.moveCamera(CameraUpdateFactory.newCameraPosition(org.maplibre.android.camera.CameraPosition.Builder()
            .target(org.maplibre.android.geometry.LatLng(lat, lng)).zoom(zoom).bearing(bearing).build()))
    }

    private val home get() = Place("demo-home", "Home", LatLng(51.4600, -0.9750), "demo", address = "Home")
    private val pub get() = Place("demo-pub", "The Old Bell Traditional Pub, Restaurant and Rooms by the River", LatLng(51.4545, -0.9690), "demo",
        address = "123 Long Address Road, Somewhere Quite Far Away, Reading")

    /** Moves the car along the drive's current route (a new one after a reroute) at town speed. */
    private suspend fun drive(model: AppModel, leaveAt: Double?) {
        var pos = LocationService.last?.coord ?: return
        LocationService.simulating = true
        val speed = 14.0
        var off = 0
        var offBearing = 0.0
        for (t in 0 until 900) {
            val nav = model.nav ?: break
            if (nav.arrived) break
            val line = nav.route.polyline
            if (line.size < 2) break
            val cum = Geo.cumulative(line)
            val total = cum.last()
            val along = Geo.project(pos, line, cum).along
            if (leaveAt != null && off == 0 && along / total > leaveAt) {
                off = 1
                val ahead = Geo.point(line, kotlin.math.min(along + 30, total), cum)
                offBearing = (Geo.bearing(pos, ahead) + 90) % 360
            }
            val next = if (off in 1..20) {
                off++
                val r = Math.toRadians(offBearing)
                LatLng(pos.lat + speed * kotlin.math.cos(r) / 110_540, pos.lng + speed * kotlin.math.sin(r) / (111_320 * kotlin.math.cos(Math.toRadians(pos.lat))))
            } else Geo.point(line, kotlin.math.min(along + speed, total), cum)
            val course = Geo.bearing(pos, next)
            pos = next
            LocationService.inject(Fix(pos.lat, pos.lng, 5.0, speed, course, 0.0, System.currentTimeMillis()))
            delay(1000)
        }
        android.util.Log.i("KiteTrace", "test drive over: ${if (model.nav == null) "arrived" else "still driving"}")
    }

    /** Made-up Waze reports around Reading, so the badges and the heat map show even if Waze turns the build away. */
    private fun demoWaze(): List<WazeAlert> {
        val c = LatLng(51.4543, -0.9731)
        var seed = 7UL
        fun rnd(): Double { seed = seed * 6364136223846793005UL + 1442695040888963407UL; return (seed shr 11).toDouble() / (1UL shl 53).toDouble() }
        val list = mutableListOf<WazeAlert>()
        val now = System.currentTimeMillis()
        for (i in 0 until 60) {
            // Most police around two hot spots, a few anywhere.
            val spot = when (i % 3) { 0 -> LatLng(51.4620, -0.9950); 1 -> LatLng(51.4480, -0.9500); else -> c }
            list += WazeAlert("demo-p$i", WazeAlert.Kind.POLICE, location = LatLng(spot.lat + (rnd() - 0.5) * 0.012, spot.lng + (rnd() - 0.5) * 0.02),
                reportedAtMillis = now - (rnd() * 7_200_000).toLong())
        }
        WazeAlert.Kind.entries.forEachIndexed { i, k ->
            if (k != WazeAlert.Kind.POLICE) list += WazeAlert("demo-k$i", k, location = LatLng(c.lat + (i - 3) * 0.004, c.lng + 0.01 * (if (i % 2 == 0) 1 else -1)))
        }
        return list
    }

    private suspend fun run(model: AppModel, step: String) {
        val st = AppSettings
        delay(6000)
        when (step) {
            "browse", "icons" -> return
            "puckflat", "puckpitch" -> {
                // The car arrow alone on the map, to check it draws the same flat and tilted.
                val m = model.map ?: return
                val at = org.maplibre.android.geometry.LatLng(51.4543, -0.9731)
                m.moveCamera(CameraUpdateFactory.newCameraPosition(org.maplibre.android.camera.CameraPosition.Builder()
                    .target(at).zoom(17.0).tilt(if (step == "puckpitch") 50.0 else 0.0).bearing(30.0).build()))
                val style = m.style ?: return
                (style.getSource(NavGlide.PUCK) as? org.maplibre.android.style.sources.GeoJsonSource)?.setGeoJson(
                    org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.Point.fromLngLat(at.longitude, at.latitude))
                        .apply { addNumberProperty("bearing", 30.0) })
                return
            }
            "welcome" -> { st.welcomed = false; return }
            "wazeintro" -> { st.welcomed = true; st.noGoogle = false; st.wazeAsked = false; return }
            "layers" -> { model.showLayers = true; return }
            "pickhome" -> { model.picking = PickKind.HOME; return }
            "settings", "wazesettings", "diagnostics" -> { if (step == "wazesettings") st.waze = true; model.tab = AppTab.SETTINGS; return }
            "transit" -> {
                // Reading to London Paddington: trains.
                val to = Place("demo-paddington", "London Paddington", LatLng(51.5154, -0.1755), "demo")
                model.select(to); delay(2000)
                model.openDirections(to)
                model.showTransit()
                return
            }
            "cameras" -> { center(model, 48.137, 11.575, 12.5); return }
            "rail" -> {
                // Central London with the train and tube lines and hill shading on.
                st.transitLines = true; st.terrain = true
                center(model, 51.515, -0.12, 12.6); return
            }
            "wazedemo", "wazetap" -> {
                st.waze = true; st.wazeHeatmap = true; st.wazeOnMap = true
                st.wazeKinds = WazeAlert.Kind.entries.toSet(); st.wazeMaxAge = 0
                val list = demoWaze()
                center(model, 51.4543, -0.9731, 13.6)
                delay(2000)
                kite?.showDemoWaze(list)
                if (step == "wazetap") {
                    delay(1000)
                    model.wazeInfo = list[list.size - 6].copy(subtype = "ACCIDENT_MAJOR", street = "Oxford Road", thumbsUp = 4, reliability = 9,
                        reportedAtMillis = System.currentTimeMillis() - 18 * 60_000)
                }
                return
            }
            "longname", "longdir" -> {
                // A place with a very long name, its sheet and then directions to it.
                val p = pub.copy(category = "Traditional British pub and restaurant")
                model.select(p)
                if (step == "longdir") { delay(2000); model.openDirections(p) }
                return
            }
            "searchfocus" -> {
                // The search box open with home set and the police heat map on: the legend must hide.
                st.waze = true; st.wazeHeatmap = true; st.home = home; return
            }
            "locationoff" -> return
            "drive", "drivewrong" -> {
                // A test drive: Reading town centre to the stadium, the car moved along the route
                // second by second; "drivewrong" leaves the route halfway to check it finds a new one.
                val to = Place("demo-stadium", "Select Car Leasing Stadium", LatLng(51.4222, -0.9827), "demo")
                model.select(to); delay(2000)
                model.openDirections(to); delay(10000)
                model.startNavigation(); delay(3000)
                drive(model, if (step == "drivewrong") 0.45 else null)
                return
            }
            "transituk" -> {
                // A long trip with many changes: a real plan to central London.
                val to = Place("demo-london", "London", LatLng(51.5072, -0.1276), "demo")
                model.select(to); delay(2000)
                model.openDirections(to)
                model.showTransit()
                model.detent = SheetDetent.FULL
                return
            }
            "wazelink" -> { model.search("https://waze.com/ul/hgcpuvpk4"); return }
            "googlelink" -> {
                model.search("Big Ben https://www.google.com/maps/place/Big+Ben/@51.5007292,-0.1272003,17z/data=!3m1!4b1!4m6!3m5!1s0x487604c38c8cd1d9:0xb78f2474b9a45aa9!8m2!3d51.5007292!4d-0.1246254!16zL20vMGNrcA")
                return
            }
            "mine" -> {
                // Home, work and a parked car around Reading, as icons on the map.
                st.home = home
                st.work = Place("demo-work", "University of Reading", LatLng(51.4414, -0.9418), "demo")
                st.parking = Place("parking:demo", "Parked car", LatLng(51.4560, -0.9690), "osm", category = "Parked at 9:41 AM")
                center(model, 51.452, -0.962, 13.8, bearing = 35.0); return
            }
            "board" -> { model.select(Place("demo-reading", "Reading Station", LatLng(51.4588, -0.9719), "demo", category = "Train station")); return }
            "streetview" -> {
                // A fixed place in the town centre, so every run looks the same way.
                model.select(Place("demo-townhall", "Reading Town Hall", LatLng(51.4566, -0.9694), "demo", category = "Concert hall")); return
            }
            "wazemap" -> {
                st.waze = true; st.wazeOnMap = true; st.wazeHeatmap = true
                st.wazeKinds = WazeAlert.Kind.entries.toSet()
                center(model, 51.507, -0.12, 12.3); return
            }
            "saved" -> {
                st.saved = listOf(
                    Place("demo-pub", "The Old Bell Traditional Pub, Restaurant and Rooms by the River", LatLng(51.4545, -0.9690), "demo",
                        category = "Pub", address = "123 Long Address Road, Somewhere Quite Far Away, Reading"),
                    Place("demo-cafe", "Workhouse Coffee", LatLng(51.4560, -0.9700), "demo"))
                st.home = Place("demo-home", "Home", LatLng(51.4600, -0.9750), "demo", address = "Home")
                model.tab = AppTab.SAVED; return
            }
            "roundabout", "uk" -> {
                st.units = UnitsMode.YARDS
                val to = Place("demo-pitlochry", "Pitlochry", LatLng(56.7030, -3.7340), "demo")
                model.select(to); delay(2000)
                model.openDirections(to); delay(10000)
                model.startNavigation(); delay(5000)
                if (step == "roundabout") model.nav?.showDemoRoundabout()
                model.nav?.showDemoAlerts()
                if (step == "uk") model.nav?.showDemoSpeed(96.6, 57.0)
                return
            }
        }
        model.search("restaurants", title = "Restaurants")
        delay(8000)
        if (step == "search") return
        if (step == "listscroll") { model.detent = SheetDetent.FULL; return }
        val first = model.visibleResults.firstOrNull() ?: return
        model.select(first)
        delay(5000)
        if (step == "place") return
        model.openDirections(first)
        if (step == "stops") model.visibleResults.drop(1).firstOrNull()?.let { model.addStop(it) }
        delay(8000)
        if (step == "directions" || step == "stops") return
        if (step == "addstop") { model.addingStop = true; return }
        if (step !in setOf("nav", "camalert", "wazealert", "allalerts", "endtrip")) return
        model.startNavigation()
        delay(4000)
        when (step) {
            "camalert" -> model.nav?.showDemoAlerts()
            "wazealert" -> model.nav?.showDemoWaze()
            "allalerts" -> { model.nav?.showDemoAlerts(); model.nav?.showDemoWaze() }
        }
    }
}

/** Screenshot runs only: the map's own pictures on plain backgrounds, to check they draw right. */
@androidx.compose.runtime.Composable
fun DemoIcons() {
    val pics = listOf(MapIcons.puck(false), MapIcons.puck(true), MapIcons.turnHead(), MapIcons.waze(WazeAlert.Kind.POLICE))
    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize()) {
        for (bg in listOf(androidx.compose.ui.graphics.Color(0xFFF2F0EC), androidx.compose.ui.graphics.Color(0xFF1E293B))) {
            androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.weight(1f).fillMaxWidth().background(bg)) {
                pics.forEach { androidx.compose.foundation.Image(it.asImageBitmap(), null, androidx.compose.ui.Modifier.size(96.dp)) }
            }
        }
    }
}
