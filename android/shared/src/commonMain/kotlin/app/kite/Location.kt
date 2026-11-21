package app.kite

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.kite.core.LatLng

/** A GPS fix as the app uses it. */
data class Fix(
    val lat: Double, val lng: Double,
    val accuracy: Double, val speed: Double, val course: Double, val altitude: Double, val timeMillis: Long,
    /**
     * When the fix was taken by the phone's own running timer (not its clock), for how old it is:
     * a clock a few seconds out showed "GPS lost" with GPS working.
     */
    val elapsedMs: Long = elapsedRealtime(),
) {
    /** How old the fix is, in milliseconds. */
    val ageMs get() = elapsedRealtime() - elapsedMs
    val coord get() = LatLng(lat, lng)
    /** Meters per second; negative when unknown. */
    val speedKnown get() = speed >= 0
    val courseKnown get() = course >= 0
}

/** What the phone says about Kite's location access. */
data class LocationAccess(val authorized: Boolean, val reducedAccuracy: Boolean, val systemOff: Boolean)

/**
 * The phone's own GPS (Android's LocationManager, Apple's Core Location), with no Google Play
 * Services. Kite's rules about which fixes to use are in [LocationService], the same on both.
 */
expect object GpsHardware {
    /** The current permission and the phone's Location switch; [onChange] is called when either flips. */
    fun access(onChange: () -> Unit): LocationAccess
    /** Asks the phone for Kite's location permission (and notifications on Android). */
    fun ask()
    /** Starts fixes every [intervalMs] (0 = as fast as the GPS gives them), replacing any earlier request. */
    fun request(intervalMs: Long, navigating: Boolean, onFix: (Fix, fromGps: Boolean) -> Unit)
    fun removeUpdates()
    /** The newest position the phone already knows, if any. */
    fun lastKnown(): Fix?
    /** Kite's page in the phone's Settings, where location can be turned back on. */
    fun openSettings()
}

/**
 * GPS for search bias and navigation. The map draws the dot from this too.
 */
object LocationService {
    var last by mutableStateOf<Fix?>(null); private set
    var authorized by mutableStateOf(false); private set
    var denied by mutableStateOf(false)
    /** The phone's own Location switch is off (Kite's permission can still be on). */
    var systemOff by mutableStateOf(false); private set
    var reducedAccuracy by mutableStateOf(false); private set
    var onUpdate: ((Fix) -> Unit)? = null

    private var started = false
    private var navigating = false

    /** Test drives: fixes come from a made-up drive, not the GPS. */
    var simulating = false

    fun inject(f: Fix) { last = f; onUpdate?.invoke(f) }

    private fun handle(f: Fix, fromGps: Boolean) {
        if (simulating) return
        if (elapsedRealtime() - f.elapsedMs > 120_000) return
        // A network fix is only taken while GPS hasn't given anything for a while.
        val prev = last
        if (!fromGps && prev != null && prev.accuracy < f.accuracy && f.elapsedMs - prev.elapsedMs < 10_000) return
        last = f
        onUpdate?.invoke(f)
    }

    fun refreshPermission() {
        val a = GpsHardware.access { refreshPermission() }
        authorized = a.authorized
        reducedAccuracy = a.reducedAccuracy
        val wasOff = systemOff
        systemOff = a.systemOff
        // Switched back on while Kite was away: ask every provider again.
        if (wasOff && !systemOff && started) request()
        if (authorized) denied = false
        // Only while Kite is on screen or driving: the Location switch flipped with Kite in the
        // background mustn't turn GPS back on.
        if (authorized && (visible || navigating)) start()
    }

    /** Asks for location access (once the welcome screens are done). */
    fun ask() = GpsHardware.ask()

    /** Kite is on screen (set by each phone's shell). */
    var visible = false

    private fun request() = GpsHardware.request(if (navigating) 0 else 1000, navigating) { f, gps -> handle(f, gps) }

    fun start() {
        if (!authorized) return
        if (started) return
        started = true
        request()
        // At most 2 minutes old: an older one is somewhere already left.
        val best = GpsHardware.lastKnown()
        if (best != null && last == null && elapsedRealtime() - best.elapsedMs < 2 * 60_000) last = best
    }

    /** Full-rate GPS for a drive (it keeps running with the screen off). */
    fun setNavigating(on: Boolean) {
        navigating = on
        if (started) request()
    }

    fun stop() {
        if (navigating) return
        started = false
        GpsHardware.removeUpdates()
        // Back later, perhaps somewhere else: the last fix isn't where the phone is any more.
        last = null
    }

    fun openSettings() = GpsHardware.openSettings()
}

/** Whether the phone has an internet connection, so a failed search or route can say why. */
object Connectivity {
    var online by mutableStateOf(true)
        internal set

    fun start() = NetworkWatch.start { v -> online = v }

    /** "No internet connection." when offline, else [fallback]. */
    fun explain(fallback: String) = if (online) fallback else "No internet connection. Check your signal and try again."
}

/** Tells [Connectivity] when the phone goes on or offline (always on the main thread). */
expect object NetworkWatch {
    fun start(onChange: (Boolean) -> Unit)
}
