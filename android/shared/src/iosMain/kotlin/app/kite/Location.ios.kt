package app.kite

import kotlinx.cinterop.*
import platform.CoreLocation.*
import platform.Foundation.*
import platform.Network.*
import platform.UIKit.*
import platform.darwin.*

actual object GpsHardware {
    private val manager = CLLocationManager()
    private var onFix: ((Fix, Boolean) -> Unit)? = null
    private var onChange: (() -> Unit)? = null

    private val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            for (l in didUpdateLocations) (l as? CLLocation)?.let { loc ->
                if (loc.horizontalAccuracy < 0) return@let
                onFix?.invoke(fix(loc), loc.horizontalAccuracy <= 100)
            }
        }

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) { onChange?.invoke() }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {}
    }

    init {
        manager.delegate = delegate
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.activityType = CLActivityTypeAutomotiveNavigation
        manager.pausesLocationUpdatesAutomatically = false
    }

    private fun fix(loc: CLLocation): Fix {
        // The fix's age by the clock, turned into the phone's running timer.
        val ageMs = (-loc.timestamp.timeIntervalSinceNow * 1000).toLong()
        val speed = if (loc.speed >= 0) loc.speed else -1.0
        // A heading the phone itself rates as rough (45° or worse) isn't used: it can look like driving the wrong way.
        val course = if (loc.course >= 0 && loc.speed > 0.5 && (loc.courseAccuracy < 0 || loc.courseAccuracy < 45)) loc.course else -1.0
        return Fix(loc.coordinate.useContents { latitude }, loc.coordinate.useContents { longitude }, loc.horizontalAccuracy,
            speed, course, loc.altitude, (loc.timestamp.timeIntervalSince1970 * 1000).toLong(), elapsedRealtime() - maxOf(0L, ageMs))
    }

    actual fun access(onChange: () -> Unit): LocationAccess {
        this.onChange = onChange
        val s = manager.authorizationStatus
        val ok = s == kCLAuthorizationStatusAuthorizedWhenInUse || s == kCLAuthorizationStatusAuthorizedAlways
        val reduced = ok && manager.accuracyAuthorization == CLAccuracyAuthorization.CLAccuracyAuthorizationReducedAccuracy
        return LocationAccess(ok, reduced, !CLLocationManager.locationServicesEnabled())
    }

    actual fun ask() {
        if (manager.authorizationStatus == kCLAuthorizationStatusNotDetermined) manager.requestWhenInUseAuthorization()
    }

    actual fun request(intervalMs: Long, navigating: Boolean, onFix: (Fix, Boolean) -> Unit) {
        this.onFix = onFix
        manager.distanceFilter = if (navigating) kCLDistanceFilterNone else 5.0
        // A drive keeps going with the screen off, like on Android with its drive notification.
        manager.allowsBackgroundLocationUpdates = navigating
        manager.showsBackgroundLocationIndicator = navigating
        manager.startUpdatingLocation()
    }

    actual fun removeUpdates() { manager.stopUpdatingLocation() }

    actual fun lastKnown(): Fix? = manager.location?.let { fix(it) }

    actual fun openSettings() {
        NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let { UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any>(), null) }
    }
}

actual object NetworkWatch {
    private var monitor: nw_path_monitor_t = null

    actual fun start(onChange: (Boolean) -> Unit) {
        if (monitor != null) return
        val m = nw_path_monitor_create()
        monitor = m
        nw_path_monitor_set_update_handler(m) { path ->
            val ok = nw_path_get_status(path) == nw_path_status_satisfied
            dispatch_async(dispatch_get_main_queue()) { onChange(ok) }
        }
        nw_path_monitor_set_queue(m, dispatch_get_main_queue())
        nw_path_monitor_start(m)
    }
}
