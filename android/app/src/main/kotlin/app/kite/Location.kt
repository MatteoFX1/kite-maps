package app.kite

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import app.kite.core.LatLng

/** A GPS fix as the app uses it. */
data class Fix(
    val lat: Double, val lng: Double,
    val accuracy: Double, val speed: Double, val course: Double, val altitude: Double, val timeMillis: Long,
) {
    val coord get() = LatLng(lat, lng)
    /** Meters per second; negative when unknown. */
    val speedKnown get() = speed >= 0
    val courseKnown get() = course >= 0
}

/**
 * GPS for search bias and navigation, straight from the phone's GPS (no Google Play Services,
 * so it works on GrapheneOS). The map draws the dot from this too.
 */
object LocationService {
    var last by mutableStateOf<Fix?>(null); private set
    var authorized by mutableStateOf(false); private set
    var denied by mutableStateOf(false)
    /** The phone's own Location switch is off (Kite's permission can still be on). */
    var systemOff by mutableStateOf(false); private set
    var reducedAccuracy by mutableStateOf(false); private set
    var onUpdate: ((Fix) -> Unit)? = null

    private val lm by lazy { KiteApp.app.getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    private var started = false
    private var navigating = false

    private val listener = LocationListener { loc -> handle(loc) }

    /** Test drives on the build's emulator: fixes come from a made-up drive, not the GPS. */
    var simulating = false

    fun inject(f: Fix) { last = f; onUpdate?.invoke(f) }

    private fun handle(loc: Location) {
        if (simulating) return
        if (!loc.hasAccuracy() && loc.provider != LocationManager.GPS_PROVIDER) return
        if (System.currentTimeMillis() - loc.time > 120_000) return
        // A network fix is only taken while GPS hasn't given anything for a while.
        val prev = last
        if (loc.provider != LocationManager.GPS_PROVIDER && prev != null && prev.accuracy < loc.accuracy &&
            loc.time - prev.timeMillis < 10_000) return
        val f = Fix(
            loc.latitude, loc.longitude, loc.accuracy.toDouble(),
            if (loc.hasSpeed()) loc.speed.toDouble() else -1.0,
            // A bearing the phone itself rates as rough (45° or worse, e.g. between tall buildings) isn't
            // used, like the iPhone app: it can look like driving the wrong way.
            if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 0.5 &&
                (!loc.hasBearingAccuracy() || loc.bearingAccuracyDegrees < 45)) loc.bearing.toDouble() else -1.0,
            loc.altitude, loc.time,
        )
        last = f
        onUpdate?.invoke(f)
    }

    fun refreshPermission() {
        val c = KiteApp.app
        val fine = ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        authorized = fine || coarse
        reducedAccuracy = coarse && !fine
        watchSwitch()
        val wasOff = systemOff
        systemOff = !androidx.core.location.LocationManagerCompat.isLocationEnabled(lm)
        // Switched back on while Kite was away: ask every provider again, the network one too.
        if (wasOff && !systemOff && started) request(if (navigating) 0 else 1000)
        if (authorized) denied = false
        if (authorized) start()
    }

    private var watching = false

    /** The Location switch flipped from the quick settings while Kite stays open: notice it straight away. */
    private fun watchSwitch() {
        if (watching) return
        watching = true
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { refreshPermission() }
        }
        androidx.core.content.ContextCompat.registerReceiver(KiteApp.app, receiver,
            android.content.IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!authorized) return
        if (started) return
        started = true
        request(if (navigating) 0 else 1000)
        val best = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
        // At most 2 minutes old, like the iPhone: an older one is somewhere already left.
        if (best != null && last == null && System.currentTimeMillis() - best.time < 2 * 60_000) {
            last = Fix(best.latitude, best.longitude, best.accuracy.toDouble(), -1.0, -1.0, best.altitude, best.time)
        }
    }

    @SuppressLint("MissingPermission")
    private fun request(intervalMs: Long) {
        runCatching { lm.removeUpdates(listener) }
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (runCatching { lm.isProviderEnabled(p) }.getOrDefault(false) || p == LocationManager.GPS_PROVIDER) {
                runCatching { lm.requestLocationUpdates(p, intervalMs, 0f, listener, Looper.getMainLooper()) }
            }
        }
    }

    /** Full-rate GPS for a drive (the drive service keeps it running with the screen off). */
    fun setNavigating(on: Boolean) {
        navigating = on
        if (started) request(if (on) 0 else 1000)
    }

    fun stop() {
        if (navigating) return
        started = false
        runCatching { lm.removeUpdates(listener) }
        // Back later, perhaps somewhere else: the last fix isn't where the phone is any more.
        last = null
    }

    /** Kite's page in the phone's Settings, where location can be turned back on. */
    fun openSettings(c: Context) {
        c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", c.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Whether the phone has an internet connection, so a failed search or route can say why. */
object Connectivity {
    var online by mutableStateOf(true); private set

    fun start(c: Context) {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        online = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { post(true) }
            // Wi-Fi to mobile data can report the old network lost after the new one is up.
            override fun onLost(network: Network) {
                post(cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
            }
        })
    }

    private fun post(v: Boolean) = android.os.Handler(Looper.getMainLooper()).post { online = v }

    /** "No internet connection." when offline, else [fallback]. */
    fun explain(fallback: String) = if (online) fallback else "No internet connection. Check your signal and try again."
}
