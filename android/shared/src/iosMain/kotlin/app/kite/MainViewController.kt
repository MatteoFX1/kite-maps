package app.kite

import androidx.compose.ui.window.ComposeUIViewController
import app.kite.core.Json
import platform.Foundation.NSData
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.UIKit.UIViewController

/**
 * The whole iPhone app, for the small Swift shell to show: the same [KiteScreen] as on Android.
 * Called once, at launch.
 */
fun MainViewController(): UIViewController {
    OldSettings.bringOver()
    Kite.start()
    demoFromLaunch()
    return ComposeUIViewController { KiteTheme { KiteScreen() } }
}

/** Kite is on screen again / out of sight (from the Swift shell's scene phase). */
fun kiteForeground() = Kite.foreground()
fun kiteBackground() = Kite.background()

/** Siri and the Shortcuts app: "home", "work" or "car". */
fun kitePerform(kind: String) {
    val k = LaunchKind.entries.firstOrNull { it.name.equals(kind, ignoreCase = true) } ?: return
    Kite.model.perform(k)
}

/** A map link opened in Kite. */
fun kiteOpenLink(text: String) = Kite.model.openLink(text)

/** The screenshot scripts: `-demo <step>` when the simulator starts Kite (never on a real phone's normal launch). */
private fun demoFromLaunch() {
    val args = NSProcessInfo.processInfo.arguments.mapNotNull { it as? String }
    val i = args.indexOf("-demo")
    if (i >= 0 && i + 1 < args.size) DemoScript.start(Kite.model, args[i + 1])
}

/**
 * The iPhone app before 0.8.0 was written in Swift and kept its settings under plain names
 * ("voice", "home"...). On the first launch of the shared app they're copied across once, so
 * nobody loses their Home, Work, saved places or choices.
 */
internal object OldSettings {
    private val d = NSUserDefaults.standardUserDefaults
    private const val DONE = "settings.broughtOverFromSwift"

    private val bools = listOf(
        "voice", "voiceAlertsOnly", "traffic", "satellite", "terrain", "transitLines", "avoidTolls", "avoidHighways",
        "noGoogle", "speedCameras", "surveillanceCameras", "plateCameras", "cameraWarnings", "speedingAlert",
        "waze", "wazeAsked", "wazeOnMap", "wazeVoice", "wazeHeatmap", "policeRisk", "policeAircraft",
        "liveCams", "liveRoadCams", "liveWebcams", "liveCamsOnRoute", "parkingButton", "autoDrive", "cleanCar", "welcomed",
    )
    /** Swift kept these as lower-case words ("dark", "yards"); the shared app as names ("DARK", "YARDS"). */
    private val words = listOf("theme", "units", "mapZoom", "driveLook")
    private val places = listOf("home", "work", "parking")
    private val placeLists = listOf("saved", "recentPlaces")

    fun bringOver() {
        if (d.boolForKey(DONE)) return
        val s = PrefStore("settings")
        runCatching {
            for (k in bools) if (d.objectForKey(k) != null) s.putBoolean(k, d.boolForKey(k))
            // Someone who had saved places in the old app had already seen the welcome.
            if (d.objectForKey("saved") != null) s.putBoolean("welcomed", true)
            for (k in words) d.stringForKey(k)?.let { s.putString(k, it.uppercase()) }
            if (d.objectForKey("wazeMaxAge") != null) s.putInt("wazeMaxAge", d.integerForKey("wazeMaxAge").toInt())
            if (d.objectForKey("defaultMode") != null) s.putInt("defaultMode", d.integerForKey("defaultMode").toInt())
            (d.arrayForKey("wazeKinds"))?.mapNotNull { it as? String }?.let { s.putStringSet("wazeKinds", it.toSet()) }
            (d.arrayForKey("recents"))?.mapNotNull { it as? String }?.let { s.putString("recents", Json.write(it)) }
            for (k in places) jsonOf(k)?.let { Json.obj(it) }?.let(::place)?.let { s.putString(k, Json.write(it)) }
            for (k in placeLists) jsonOf(k)?.let { Json.parse(it) as? List<*> }?.let { l ->
                s.putString(k, Json.write(l.mapNotNull { (it as? Map<*, *>)?.let(::place) }))
            }
        }.onFailure { klog("Kite", "Old settings: ${it.message}") }
        d.setBool(true, DONE)
    }

    /** Swift saved places as JSON data. */
    private fun jsonOf(key: String): String? {
        val data: NSData = d.dataForKey(key) ?: return null
        @Suppress("CAST_NEVER_SUCCEEDS")
        return NSString.create(data = data, encoding = NSUTF8StringEncoding) as String?
    }

    /** Swift's place has its spot as "location": {lat, lng}; the shared app's has "lat" and "lng" beside the name. */
    private fun place(m: Map<*, *>): Map<String, Any?>? {
        val loc = m["location"] as? Map<*, *> ?: return null
        val out = LinkedHashMap<String, Any?>()
        for ((k, v) in m) if (k is String && k != "location" && v != null) out[k] = v
        out["lat"] = (loc["lat"] as? Number)?.toDouble() ?: return null
        out["lng"] = (loc["lng"] as? Number)?.toDouble() ?: return null
        return PlaceCodec.fromMap(out)?.let { PlaceCodec.toMap(it) }
    }
}
