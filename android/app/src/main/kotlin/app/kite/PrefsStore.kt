package app.kite

import android.content.SharedPreferences
import app.kite.core.KeyValue

/** The engine's small saved values, kept in the app's SharedPreferences under "core.". */
class PrefsStore(private val prefs: SharedPreferences) : KeyValue {
    override fun getString(key: String): String? = prefs.getString("core.$key", null)
    override fun putString(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove("core.$key") else putString("core.$key", value) }.apply()
    }
}
