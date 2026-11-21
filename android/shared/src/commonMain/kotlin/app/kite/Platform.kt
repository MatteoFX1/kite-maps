package app.kite

// What each phone does its own way, behind one shape the shared app uses. The Android versions are
// in androidMain, the iPhone ones in iosMain; everything else in Kite is the same code on both.

/** Small saved values (settings), in SharedPreferences on Android and UserDefaults on iPhone. */
expect class PrefStore(name: String) {
    fun contains(key: String): Boolean
    fun getString(key: String): String?
    fun getBoolean(key: String, def: Boolean): Boolean
    fun getInt(key: String, def: Int): Int
    fun getStringSet(key: String): Set<String>?
    fun putString(key: String, value: String?)
    fun putBoolean(key: String, value: Boolean)
    fun putInt(key: String, value: Int)
    fun putStringSet(key: String, value: Set<String>)
    fun remove(key: String)
}

/** Milliseconds from the phone's running timer (not its clock): for how old something is. */
expect fun elapsedRealtime(): Long

/** A folder for files Kite can rebuild (cached roads, cameras...). */
expect fun cacheDir(): String

/** A line in the phone's log (logcat on Android, the console on iPhone). */
expect fun klog(tag: String, message: String)

/** The phone's country, like "GB". */
expect fun systemCountry(): String

/** Which phone this is. */
expect val isIos: Boolean

/** A short buzz: a tick for a tap on the map, or a longer one for a long press. */
expect fun haptic(long: Boolean = false)

/** The screen stays on while driving. */
expect fun keepScreenOn(on: Boolean)

/** Things Kite hands to the rest of the phone: links, sharing, the clipboard, Settings. */
expect object Phone {
    /** Opens a link in whatever app handles it (browser, dialer, another maps app). */
    fun open(url: String)
    fun share(text: String)
    /** Shares a file Kite made (the diagnostics report). */
    fun shareFile(path: String, mime: String, title: String)
    fun copy(text: String)
    /** Whether something text-like was copied, without reading it (so the phone shows no paste notice). */
    fun hasCopiedText(): Boolean
    fun paste(): String?
    /** The phone's own Location settings (location turned off for the whole phone). */
    fun openLocationSettings()
    /** "0.8.0 (80)". */
    val appVersion: String
    val appId: String
    /** "Google Pixel 10 Pro, Android 16" / "iPhone, iOS 26.1". */
    val deviceName: String
}

/** Times and numbers the way the phone is set to show them (12 or 24 hour clock, separators). */
expect object LocalFormat {
    fun time(epochMillis: Long): String
    fun dateTime(epochMillis: Long): String
    fun count(n: Int): String
    /** "AM", "PM" in the phone's language. */
    val amPm: List<String>
    val is24Hour: Boolean
}

/** Search by voice. Says what was heard, or null when nothing was (or the phone can't listen). */
expect object Dictation {
    fun available(): Boolean
    fun listen(onResult: (String?) -> Unit)
    fun stop()
}

/** Degrees to radians. */
fun toRadians(deg: Double): Double = deg * kotlin.math.PI / 180.0

/** A folder for files Kite keeps (police history...); not cleared by the phone. */
expect fun filesDir(): String
