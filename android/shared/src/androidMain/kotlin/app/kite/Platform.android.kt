package app.kite

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import java.lang.ref.WeakReference
import java.util.Locale

/** Set by the Android shell at launch: the app, and the activity on screen. */
@SuppressLint("StaticFieldLeak")
object AndroidHost {
    lateinit var app: Application
    private var activityRef: WeakReference<Activity>? = null
    var activity: Activity?
        get() = activityRef?.get()
        set(v) { activityRef = v?.let { WeakReference(it) } }
}

actual class PrefStore actual constructor(name: String) {
    private val p: SharedPreferences = AndroidHost.app.getSharedPreferences(name, Context.MODE_PRIVATE)
    actual fun contains(key: String) = p.contains(key)
    actual fun getString(key: String): String? = p.getString(key, null)
    actual fun getBoolean(key: String, def: Boolean) = p.getBoolean(key, def)
    actual fun getInt(key: String, def: Int) = p.getInt(key, def)
    actual fun getStringSet(key: String): Set<String>? = p.getStringSet(key, null)
    actual fun putString(key: String, value: String?) { p.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
    actual fun putBoolean(key: String, value: Boolean) { p.edit().putBoolean(key, value).apply() }
    actual fun putInt(key: String, value: Int) { p.edit().putInt(key, value).apply() }
    actual fun putStringSet(key: String, value: Set<String>) { p.edit().putStringSet(key, value).apply() }
    actual fun remove(key: String) { p.edit().remove(key).apply() }
}

actual fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()

actual fun cacheDir(): String = AndroidHost.app.cacheDir.path

actual fun filesDir(): String = AndroidHost.app.filesDir.path

actual fun klog(tag: String, message: String) { Log.i(tag, message) }

actual fun systemCountry(): String = Locale.getDefault().country

actual val isIos: Boolean = false

actual fun haptic(long: Boolean) {
    AndroidHost.activity?.window?.decorView?.performHapticFeedback(
        if (long) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.CLOCK_TICK)
}

actual fun keepScreenOn(on: Boolean) {
    val w = AndroidHost.activity?.window ?: return
    if (on) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}
