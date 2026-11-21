package app.kite

import app.kite.core.LatLng
import app.kite.map.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.*

/** Camera helpers: the iPhone app thinks in "meters across the screen"; MapLibre in zoom levels. */
object MapCam {
    /** The zoom at which [meters] fill [widthDp] of screen at latitude [lat]. */
    fun zoomFor(meters: Double, lat: Double, widthDp: Float): Double {
        val z = log2(40_075_016.686 * cos(lat * PI / 180) * widthDp / (512.0 * max(meters, 1.0)))
        return z.coerceIn(1.0, 20.0)
    }

    /** [padding] is left, top, right, bottom in dp. */
    fun animate(m: MapHandle, pos: CameraPosition, padding: IntArray? = null, ms: Int = 500) {
        val p = if (padding != null) CameraPosition.Builder(pos).padding(padding[0].toDouble(), padding[1].toDouble(), padding[2].toDouble(), padding[3].toDouble()).build()
            else CameraPosition.Builder(pos).padding(0.0, 0.0, 0.0, 0.0).build()
        if (ms <= 0) m.moveCamera(CameraUpdateFactory.newCameraPosition(p))
        else m.animateCamera(CameraUpdateFactory.newCameraPosition(p), ms)
    }

    fun fit(m: MapHandle, scope: CoroutineScope, b: LatLngBounds, padding: IntArray, ms: Int = 600) {
        scope.launch {
            val pos = m.cameraFor(b, padding) ?: return@launch
            val z = min(pos.zoom, 17.5)
            m.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(pos).zoom(z).tilt(0.0).bearing(0.0).padding(0.0, 0.0, 0.0, 0.0).build()), ms)
        }
    }
}
