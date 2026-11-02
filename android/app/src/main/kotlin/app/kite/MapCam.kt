package app.kite

import app.kite.core.LatLng
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.*

fun LatLng.ml() = org.maplibre.android.geometry.LatLng(lat, lng)
fun org.maplibre.android.geometry.LatLng.kite() = LatLng(latitude, longitude)

/** Camera helpers: the iPhone app thinks in "meters across the screen"; MapLibre in zoom levels. */
object MapCam {
    /** The zoom at which [meters] fill [widthDp] of screen at latitude [lat]. */
    fun zoomFor(meters: Double, lat: Double, widthDp: Float): Double {
        val z = log2(40_075_016.686 * cos(lat * PI / 180) * widthDp / (512.0 * max(meters, 1.0)))
        return z.coerceIn(1.0, 20.0)
    }

    private fun density() = KiteApp.app.resources.displayMetrics.density

    /** [padding] is left, top, right, bottom in dp. */
    fun animate(m: MapLibreMap, pos: CameraPosition, padding: IntArray? = null, ms: Int = 500) {
        val p = if (padding != null) {
            val d = density()
            CameraPosition.Builder(pos).padding(padding[0] * d.toDouble(), padding[1] * d.toDouble(), padding[2] * d.toDouble(), padding[3] * d.toDouble()).build()
        } else CameraPosition.Builder(pos).padding(0.0, 0.0, 0.0, 0.0).build()
        if (ms <= 0) m.moveCamera(CameraUpdateFactory.newCameraPosition(p))
        else m.animateCamera(CameraUpdateFactory.newCameraPosition(p), ms)
    }

    fun fit(m: MapLibreMap, b: LatLngBounds, padding: IntArray, ms: Int = 600) {
        val d = density()
        val px = padding.map { (it * d).toInt() }
        val pos = m.getCameraForLatLngBounds(b, intArrayOf(px[0], px[1], px[2], px[3]), 0.0, 0.0) ?: return
        val z = min(pos.zoom, 17.5)
        m.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(pos).zoom(z).tilt(0.0).bearing(0.0).padding(0.0, 0.0, 0.0, 0.0).build()), ms)
    }
}
