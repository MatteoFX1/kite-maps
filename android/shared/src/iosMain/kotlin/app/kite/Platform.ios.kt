package app.kite

import platform.Foundation.*
import platform.UIKit.*

actual class PrefStore actual constructor(name: String) {
    private val d = NSUserDefaults.standardUserDefaults
    private val prefix = "$name."
    private fun k(key: String) = prefix + key
    actual fun contains(key: String) = d.objectForKey(k(key)) != null
    actual fun getString(key: String): String? = d.stringForKey(k(key))
    actual fun getBoolean(key: String, def: Boolean) = if (contains(key)) d.boolForKey(k(key)) else def
    actual fun getInt(key: String, def: Int) = if (contains(key)) d.integerForKey(k(key)).toInt() else def
    actual fun getStringSet(key: String): Set<String>? = (d.arrayForKey(k(key)))?.mapNotNull { it as? String }?.toSet()
    actual fun putString(key: String, value: String?) { if (value == null) d.removeObjectForKey(k(key)) else d.setObject(value, k(key)) }
    actual fun putBoolean(key: String, value: Boolean) { d.setBool(value, k(key)) }
    actual fun putInt(key: String, value: Int) { d.setInteger(value.toLong(), k(key)) }
    actual fun putStringSet(key: String, value: Set<String>) { d.setObject(value.toList(), k(key)) }
    actual fun remove(key: String) { d.removeObjectForKey(k(key)) }
}

actual fun elapsedRealtime(): Long = (NSProcessInfo.processInfo.systemUptime * 1000).toLong()

actual fun cacheDir(): String =
    (NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String) ?: NSTemporaryDirectory()

actual fun filesDir(): String =
    ((NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).firstOrNull() as? String)
        ?: NSTemporaryDirectory()) + "/Kite"

// Kotlin strings can't go through NSLog's "..." arguments, so the line is made first (with any % doubled).
actual fun klog(tag: String, message: String) { NSLog("$tag: $message".replace("%", "%%")) }

actual fun systemCountry(): String = NSLocale.currentLocale.countryCode ?: ""

actual val isIos: Boolean = true

actual fun haptic(long: Boolean) {
    val g = UIImpactFeedbackGenerator(if (long) UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium else UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)
    g.impactOccurred()
}

actual fun keepScreenOn(on: Boolean) { UIApplication.sharedApplication.idleTimerDisabled = on }
