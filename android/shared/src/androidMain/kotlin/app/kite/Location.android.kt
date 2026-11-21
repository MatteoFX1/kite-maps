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
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat

actual object GpsHardware {
    private val app get() = AndroidHost.app
    private val lm by lazy { app.getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    private var onFix: ((Fix, Boolean) -> Unit)? = null
    private val listener = LocationListener { loc -> handle(loc) }
    private var watching = false
    /** Set by the Android shell: asks for permissions with the activity's launcher. */
    var asker: ((Array<String>) -> Unit)? = null

    private fun handle(loc: Location) {
        if (!loc.hasAccuracy() && loc.provider != LocationManager.GPS_PROVIDER) return
        onFix?.invoke(fix(loc), loc.provider == LocationManager.GPS_PROVIDER)
    }

    private fun fix(loc: Location) = Fix(
        loc.latitude, loc.longitude, loc.accuracy.toDouble(),
        if (loc.hasSpeed()) loc.speed.toDouble() else -1.0,
        // A bearing the phone itself rates as rough (45° or worse, e.g. between tall buildings) isn't
        // used, like the iPhone app: it can look like driving the wrong way.
        if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 0.5 &&
            (!loc.hasBearingAccuracy() || loc.bearingAccuracyDegrees < 45)) loc.bearing.toDouble() else -1.0,
        loc.altitude, loc.time, takenAt(loc),
    )

    /** When [loc] was taken, by the phone's running timer; from its clock time only if the timer's is missing. */
    private fun takenAt(loc: Location): Long =
        if (loc.elapsedRealtimeNanos > 0) loc.elapsedRealtimeNanos / 1_000_000
        else SystemClock.elapsedRealtime() - (System.currentTimeMillis() - loc.time)

    actual fun access(onChange: () -> Unit): LocationAccess {
        val fine = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!watching) {
            watching = true
            // The Location switch flipped from the quick settings while Kite stays open: notice it straight away.
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) { onChange() }
            }
            ContextCompat.registerReceiver(app, receiver, android.content.IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        return LocationAccess(fine || coarse, coarse && !fine, !androidx.core.location.LocationManagerCompat.isLocationEnabled(lm))
    }

    actual fun ask() {
        val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        asker?.invoke(list.toTypedArray())
    }

    @SuppressLint("MissingPermission")
    actual fun request(intervalMs: Long, navigating: Boolean, onFix: (Fix, Boolean) -> Unit) {
        this.onFix = onFix
        runCatching { lm.removeUpdates(listener) }
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (runCatching { lm.isProviderEnabled(p) }.getOrDefault(false) || p == LocationManager.GPS_PROVIDER) {
                runCatching { lm.requestLocationUpdates(p, intervalMs, 0f, listener, Looper.getMainLooper()) }
            }
        }
    }

    actual fun removeUpdates() { runCatching { lm.removeUpdates(listener) } }

    @SuppressLint("MissingPermission")
    actual fun lastKnown(): Fix? {
        val best = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos } ?: return null
        return Fix(best.latitude, best.longitude, best.accuracy.toDouble(), -1.0, -1.0, best.altitude, best.time, takenAt(best))
    }

    actual fun openSettings() {
        app.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

actual object NetworkWatch {
    actual fun start(onChange: (Boolean) -> Unit) {
        val cm = AndroidHost.app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        fun now() = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        onChange(now())
        val main = android.os.Handler(Looper.getMainLooper())
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { main.post { onChange(true) } }
            // Wi-Fi to mobile data can report the old network lost after the new one is up.
            override fun onLost(network: Network) { main.post { onChange(now()) } }
        })
    }
}
