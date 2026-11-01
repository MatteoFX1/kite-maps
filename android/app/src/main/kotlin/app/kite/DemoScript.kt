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
    val active get() = step != null

    fun start(model: AppModel, s: String) {
        step = s
        Voice.silent = true
        val st = AppSettings
        // Past the first-launch pages, unless that's what's being checked.
        if (s != "welcome" && s != "wazeintro") { st.welcomed = true; st.wazeAsked = true }
        model.scope.launch { run(model, s) }
    }

    private fun center(model: AppModel, lat: Double, lng: Double, zoom: Double) {
        model.map?.moveCamera(CameraUpdateFactory.newLatLngZoom(org.maplibre.android.geometry.LatLng(lat, lng), zoom))
    }

    private suspend fun run(model: AppModel, step: String) {
        val st = AppSettings
        delay(6000)
        when (step) {
            "browse", "icons" -> return
            "welcome" -> { st.welcomed = false; return }
            "wazeintro" -> { st.welcomed = true; st.noGoogle = false; st.wazeAsked = false; return }
            "layers" -> { model.showLayers = true; return }
            "pickhome" -> { model.picking = PickKind.HOME; return }
            "settings" -> { model.tab = AppTab.SETTINGS; return }
            "cameras" -> { center(model, 48.137, 11.575, 12.5); return }
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
        val first = model.visibleResults.firstOrNull() ?: return
        model.select(first)
        delay(5000)
        if (step == "place") return
        model.openDirections(first)
        delay(8000)
        if (step == "directions") return
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
