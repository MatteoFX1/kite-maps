package app.kite.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.UUID
import kotlin.math.*
import kotlin.random.Random

/** Small saved settings for the engine (the app backs it with SharedPreferences). */
interface KeyValue {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getDouble(key: String): Double = getString(key)?.toDoubleOrNull() ?: 0.0
    fun putDouble(key: String, value: Double) = putString(key, value.toString())
    fun getInt(key: String): Int = getString(key)?.toIntOrNull() ?: 0
    fun putInt(key: String, value: Int) = putString(key, value.toString())

    class Memory : KeyValue {
        private val m = mutableMapOf<String, String>()
        override fun getString(key: String) = synchronized(m) { m[key] }
        override fun putString(key: String, value: String?) { synchronized(m) { if (value == null) m.remove(key) else m[key] = value } }
    }

    companion object {
        @Volatile var store: KeyValue = Memory()
    }
}

/** A live report from Waze drivers: police, a crash, something on the road, a closure. */
data class WazeAlert(
    val id: String,
    val kind: Kind,
    /** Waze's own detail, like "POLICE_HIDING" or "HAZARD_ON_ROAD_POT_HOLE". */
    val subtype: String = "",
    val location: LatLng,
    val street: String? = null,
    val reportedAtMillis: Long = System.currentTimeMillis(),
    val thumbsUp: Int = 0,
    val reliability: Int = 5,
    /** Which way the reporting driver was heading, in degrees, when Waze has it. */
    val heading: Double? = null,
) {
    enum class Kind(val key: String) { POLICE("police"), ACCIDENT("accident"), HAZARD("hazard"), WEATHER("weather"), ROADWORKS("roadworks"), CLOSURE("closure"), JAM("jam") }

    val title: String
        get() = when (subtype) {
            "POLICE_HIDING" -> "Hidden police"
            "POLICE_WITH_MOBILE_CAMERA", "POLICE_MOBILE_CAMERA" -> "Mobile speed camera"
            "ACCIDENT_MAJOR" -> "Major crash"
            "ACCIDENT_MINOR" -> "Minor crash"
            "HAZARD_ON_ROAD_POT_HOLE" -> "Pothole"
            "HAZARD_ON_ROAD_CAR_STOPPED" -> "Car stopped on the road"
            "HAZARD_ON_SHOULDER_CAR_STOPPED" -> "Car stopped on the side"
            "HAZARD_ON_ROAD_ROAD_KILL", "HAZARD_ON_SHOULDER_ANIMALS" -> "Animal on the road"
            "HAZARD_ON_ROAD_OBJECT" -> "Object on the road"
            "HAZARD_ON_ROAD_TRAFFIC_LIGHT_FAULT" -> "Broken traffic light"
            "HAZARD_ON_ROAD_LANE_CLOSED" -> "Lane closed"
            "HAZARD_ON_ROAD_OIL" -> "Oil on the road"
            "HAZARD_ON_ROAD_ICE", "HAZARD_WEATHER_FREEZING_RAIN" -> "Ice on the road"
            "HAZARD_WEATHER_FOG" -> "Fog"
            "HAZARD_WEATHER_HAIL" -> "Hail"
            "HAZARD_WEATHER_FLOOD", "HAZARD_WEATHER_HEAVY_RAIN" -> "Flooding"
            "HAZARD_WEATHER_HEAVY_SNOW" -> "Heavy snow"
            "HAZARD_ON_SHOULDER_MISSING_SIGN" -> "Missing sign"
            "JAM_STAND_STILL_TRAFFIC" -> "Standstill traffic"
            "JAM_HEAVY_TRAFFIC" -> "Heavy traffic"
            else -> when (kind) {
                Kind.POLICE -> "Police"
                Kind.ACCIDENT -> "Crash"
                Kind.HAZARD -> "Hazard"
                Kind.WEATHER -> "Bad weather"
                Kind.ROADWORKS -> "Roadworks"
                Kind.CLOSURE -> "Road closed"
                Kind.JAM -> "Traffic jam"
            }
        }

    /** "Police reported ahead." and the like, for the voice. */
    val spoken: String
        get() = when (kind) {
            Kind.CLOSURE -> "Road closed ahead."
            Kind.JAM -> "Traffic jam ahead."
            else -> "$title reported ahead."
        }
}

/** A report placed along a route. */
data class PlacedAlert(val alert: WazeAlert, val along: Double)

object Waze {
    /** How the last ask went, for Settings' status line: when, the HTTP code (0 = no network), how many reports. */
    data class Status(val atMillis: Long, val code: Int, val count: Int, val viaPage: Boolean = false, val types: Map<String, Int> = emptyMap()) {
        val summary: String
            get() = if (types.isEmpty()) "nothing"
            else types.entries.sortedWith(compareBy({ -it.value }, { it.key })).joinToString(" · ") { "${it.value} ${typeName(it.key, it.value)}" }
    }

    fun typeName(type: String, n: Int): String = when (type) {
        "POLICE", "POLICEMAN" -> "police"
        "ACCIDENT" -> if (n == 1) "crash" else "crashes"
        "HAZARD", "WEATHERHAZARD" -> if (n == 1) "hazard" else "hazards"
        "ROAD_CLOSED" -> if (n == 1) "road closure" else "road closures"
        "JAM" -> if (n == 1) "jam" else "jams"
        "CONSTRUCTION" -> "roadworks"
        else -> type.lowercase()
    }

    fun kind(type: String, subtype: String): WazeAlert.Kind? = when (type) {
        "POLICE", "POLICEMAN" -> WazeAlert.Kind.POLICE
        "ACCIDENT" -> WazeAlert.Kind.ACCIDENT
        "ROAD_CLOSED" -> WazeAlert.Kind.CLOSURE
        "JAM" -> WazeAlert.Kind.JAM
        "CONSTRUCTION" -> WazeAlert.Kind.ROADWORKS
        "WEATHERHAZARD", "HAZARD" -> when {
            subtype == "HAZARD_ON_ROAD_CONSTRUCTION" -> WazeAlert.Kind.ROADWORKS
            subtype.startsWith("HAZARD_WEATHER") || subtype == "HAZARD_ON_ROAD_ICE" -> WazeAlert.Kind.WEATHER
            else -> WazeAlert.Kind.HAZARD
        }
        else -> null
    }

    /** The live map's JSON (closures only since October 2026), kept for parsing old-style answers. */
    fun parse(data: ByteArray): List<WazeAlert>? {
        val root = Json.obj(data) ?: return null
        val items = root["alerts"].asMaps() ?: emptyList()
        return items.mapNotNull { a ->
            val loc = a["location"].asMap() ?: return@mapNotNull null
            val x = loc["x"].num() ?: return@mapNotNull null
            val y = loc["y"].num() ?: return@mapNotNull null
            val type = a["type"] as? String ?: return@mapNotNull null
            val sub = a["subtype"] as? String ?: ""
            val kind = kind(type, sub) ?: return@mapNotNull null
            WazeAlert(a["uuid"] as? String ?: a["id"] as? String ?: "$type$x,$y", kind, sub, LatLng(y, x),
                (a["street"] as? String)?.ifEmpty { null }, a["pubMillis"].num()?.toLong() ?: System.currentTimeMillis(),
                a["nThumbsUp"].int() ?: 0, a["reliability"].int() ?: 5, a["magvar"].num())
        }
    }

    /** Where each report sits along [line]: close to the road, and made by someone driving the same way. */
    fun project(alerts: List<WazeAlert>, line: List<LatLng>, maxOffset: Double = 40.0): List<PlacedAlert> {
        if (line.size < 2) return emptyList()
        val cum = Geo.cumulative(line)
        val out = mutableListOf<PlacedAlert>()
        for (a in alerts) {
            val p = Geo.project(a.location, line, cum)
            if (p.offset > maxOffset) continue
            val h = a.heading
            if (h != null && a.kind != WazeAlert.Kind.CLOSURE) {
                val ahead = Geo.point(line, p.along + 30, cum)
                val behind = Geo.point(line, max(p.along - 30, 0.0), cum)
                if (Geo.diff(h, Geo.bearing(behind, ahead)) > 110) continue
            }
            out.add(PlacedAlert(a, p.along))
        }
        return out.sortedBy { it.along }
    }
}

/** A box: south, west, north, east. */
data class Box(val s: Double, val w: Double, val n: Double, val e: Double)

/**
 * Live Waze reports (police, crashes, hazards...) the way the Waze app itself gets them: its "RT"
 * protocol, with an anonymous account Waze hands out (no name, email or phone).
 *
 * Ported from SABRE Plus for Highway Radar (github.com/nicglazkov/highway-radar-sabre-plus),
 * Copyright (c) 2026 highway-radar-sabre-plus contributors, MIT licence (see
 * licenses/SABRE-Plus-LICENSE.txt). Only the fetch path: Kite never reports to Waze.
 *
 * Gentle on Waze: one account kept on the phone (at most 3 new ones a day), one session reused,
 * a handful of boxes per ask.
 */
class WazeLive {
    companion object {
        val shared = WazeLive()
        @Volatile var lastStatus: Waze.Status? = null
        @Volatile var bytes: Long = 0
        /** Another server instead of Waze's, for testing what Kite does when Waze is unreachable. */
        @Volatile var hostOverride: String? = null
        const val protocolVersion = 234
        const val appVersion = "5.17.1.0"

        /** Waze's servers: the Americas, Israel, and the rest of the world (the UK). */
        fun region(p: LatLng): String {
            if (p.lng >= -170 && p.lng <= -52 && p.lat >= -15 && p.lat <= 73) return "na"
            if (p.lng >= 34 && p.lng <= 36 && p.lat >= 29.5 && p.lat <= 33.5) return "il"
            return "row"
        }

        fun box(p: LatLng, meters: Double): Box {
            val dLat = meters / 110_574
            val dLng = meters / (cos(p.lat * PI / 180) * 111_320)
            return Box(p.lat - dLat, p.lng - dLng, p.lat + dLat, p.lng + dLng)
        }

        fun seeMe() = "SeeMe,1,2,T,T,T,1,-1,1,7"

        /** "MapDisplayed,": the box's corners, its middle, a constant, the corners again. */
        fun mapDisplayed(b: Box): String {
            fun f(v: Double) = String.format(java.util.Locale.US, "%.6f", v)
            val corners = listOf(f(b.w), f(b.n), f(b.e), f(b.n), f(b.e), f(b.s), f(b.w), f(b.s)).joinToString(",")
            return "MapDisplayed,$corners,${f((b.w + b.e) / 2)},${f((b.s + b.n) / 2)},67186,$corners"
        }

        /** One line of a body: "ProtoBase64," + a Batch holding one element. */
        fun line(field: Int, payload: ByteArray): String {
            val el = ProtoWriter().apply { message(field, payload) }
            val batch = ProtoWriter().apply { message(1001, el.data) }
            return "ProtoBase64," + Base64.getEncoder().encodeToString(batch.data)
        }

        data class Device(val make: String, val model: String, val os: String, val w: Int, val h: Int)

        val devices = listOf(
            Device("samsung", "SM-S928B", "16-SDK36", 1440, 3088),
            Device("samsung", "SM-A546B", "15-SDK35", 1080, 2340),
            Device("Google", "Pixel 9 Pro", "16-SDK36", 1280, 2856),
            Device("Google", "Pixel 8", "15-SDK35", 1080, 2400),
            Device("OnePlus", "CPH2451", "15-SDK35", 1240, 2772),
            Device("Xiaomi", "2201117TG", "14-SDK34", 1220, 2712),
            Device("motorola", "moto g84", "15-SDK35", 1080, 2400),
            Device("Nothing", "A065", "15-SDK35", 1080, 2412),
        )

        fun clientInfo(device: Int, installation: String, near: LatLng): String {
            val d = devices[device % devices.size]
            val lng = near.lng + Random.nextDouble(-500.0, 500.0) / (cos(near.lat * PI / 180) * 111_320)
            val lat = near.lat + Random.nextDouble(-500.0, 500.0) / 110_574
            val pos = ProtoWriter().apply {
                int32(101, (lng * 1_000_000).roundToInt())
                int32(102, (lat * 1_000_000).roundToInt())
            }
            val display = ProtoWriter().apply { varint(1, 2); varint(2, d.w.toLong()); varint(3, d.h.toLong()) }
            val ci = ProtoWriter().apply {
                varint(1, protocolVersion.toLong())
                string(3, appVersion)
                message(4, pos.data)
                string(5, d.make)
                string(6, d.model)
                string(11, d.os)
                string(16, "en")
                string(17, installation)
                varint(18, 50)
                varint(19, 1)
                message(24, display.data)
                string(25, "en")
                string(26, UUID.randomUUID().toString().lowercase())
                varint(28, System.currentTimeMillis())
                varint(31, 5)
            }
            return line(2184, ci.data)
        }

        class Element {
            var oldCommand: String? = null
            var errorCode: Int? = null
            var errorText: String? = null
            var registered: Pair<String, String>? = null
            var loginSuccess: Session? = null
            var loginErrorType: Int? = null
            var alert: WazeAlert? = null
        }

        fun parseBatch(data: ByteArray): List<Element> {
            val fields = ProtoReader.fields(data) ?: throw Failure.Other("unreadable answer")
            return fields.filter { it.number == 1001 }.mapNotNull { f -> f.bytes?.let { element(it) } }
        }

        fun element(data: ByteArray): Element? {
            val fields = ProtoReader.fields(data) ?: return null
            val el = Element()
            for (f in fields) {
                when (f.number) {
                    2001 -> el.oldCommand = f.string
                    2003 -> {
                        val sub = f.bytes?.let { ProtoReader.fields(it) } ?: emptyList()
                        el.errorCode = sub.firstOrNull { it.number == 10101 }?.value?.toInt() ?: 0
                        el.errorText = sub.firstOrNull { it.number == 10102 }?.string ?: ""
                    }
                    2220 -> {
                        val sub = f.bytes?.let { ProtoReader.fields(it) } ?: emptyList()
                        el.registered = (sub.firstOrNull { it.number == 1 }?.string ?: "") to (sub.firstOrNull { it.number == 2 }?.string ?: "")
                    }
                    2224 -> {
                        val sub = f.bytes?.let { ProtoReader.fields(it) } ?: emptyList()
                        el.loginErrorType = (sub.firstOrNull { it.number == 2 }?.value ?: 0).toInt()
                    }
                    2745 -> {
                        val sub = f.bytes?.let { ProtoReader.fields(it) } ?: emptyList()
                        for (r in sub) {
                            val inner = r.bytes?.let { ProtoReader.fields(it) } ?: emptyList()
                            if (r.number == 1) {
                                el.loginSuccess = Session(inner.firstOrNull { it.number == 1 }?.value ?: 0, inner.firstOrNull { it.number == 3 }?.string ?: "")
                            } else if (r.number == 2) {
                                el.loginErrorType = (inner.firstOrNull { it.number == 2 }?.value ?: 0).toInt()
                            }
                        }
                    }
                    2708 -> f.bytes?.let { ProtoReader.fields(it) }?.firstOrNull { it.number == 1 }?.bytes?.let { el.alert = alert(it) }
                }
            }
            return el
        }

        /** A RealtimeAlert: its kind, place, heading, time, street and thumbs up. */
        fun alert(data: ByteArray): WazeAlert? {
            val f = ProtoReader.fields(data) ?: return null
            val info = f.firstOrNull { it.number == 2 }?.bytes?.let { ProtoReader.fields(it) } ?: return null
            val pos = info.firstOrNull { it.number == 3 }?.bytes?.let { ProtoReader.fields(it) } ?: return null
            val lngRaw = pos.firstOrNull { it.number == 101 }?.value ?: return null
            val latRaw = pos.firstOrNull { it.number == 102 }?.value ?: return null
            val type = typeNames[(info.firstOrNull { it.number == 1 }?.value ?: 0).toInt()] ?: ""
            val sub = subtypeNames[(info.firstOrNull { it.number == 2 }?.value ?: 0).toInt()] ?: ""
            val kind = Waze.kind(type, sub) ?: return null
            val lng = lngRaw.toInt() / 1_000_000.0
            val lat = latRaw.toInt() / 1_000_000.0
            val report = f.firstOrNull { it.number == 3 }?.bytes?.let { ProtoReader.fields(it) } ?: emptyList()
            val seconds = report.firstOrNull { it.number == 4 }?.value ?: 0
            val address = report.firstOrNull { it.number == 8 }?.bytes?.let { ProtoReader.fields(it) } ?: emptyList()
            val street = address.firstOrNull { it.number == 2 }?.string?.ifEmpty { null }
            val heading = info.firstOrNull { it.number == 6 }?.let { it.value.toInt().toDouble() }
            val numeric = f.firstOrNull { it.number == 1 }?.value ?: 0
            val id = f.firstOrNull { it.number == 6 }?.string?.ifEmpty { null } ?: "rt${java.lang.Long.toUnsignedString(numeric)}"
            return WazeAlert(id, kind, sub, LatLng(lat, lng), street,
                if (seconds > 0) seconds * 1000 else System.currentTimeMillis(),
                (report.firstOrNull { it.number == 9 }?.value ?: 0).toInt(),
                heading = heading?.takeIf { it >= 0 && it < 360 })
        }

        fun typeKey(k: WazeAlert.Kind): String = when (k) {
            WazeAlert.Kind.POLICE -> "POLICE"
            WazeAlert.Kind.ACCIDENT -> "ACCIDENT"
            WazeAlert.Kind.HAZARD -> "HAZARD"
            WazeAlert.Kind.WEATHER -> "WEATHERHAZARD"
            WazeAlert.Kind.ROADWORKS -> "CONSTRUCTION"
            WazeAlert.Kind.CLOSURE -> "ROAD_CLOSED"
            WazeAlert.Kind.JAM -> "JAM"
        }

        val typeNames = mapOf(2 to "POLICE", 3 to "ACCIDENT", 4 to "JAM", 6 to "HAZARD", 8 to "CONSTRUCTION", 13 to "ROAD_CLOSED",
            14 to "ROAD_CLOSED", 100 to "WEATHERHAZARD", 101 to "HAZARD")

        val subtypeNames = mapOf(
            201 to "POLICE_VISIBLE", 202 to "POLICE_HIDING", 203 to "POLICE_WITH_MOBILE_CAMERA",
            301 to "ACCIDENT_MINOR", 302 to "ACCIDENT_MAJOR",
            401 to "JAM_MODERATE_TRAFFIC", 402 to "JAM_HEAVY_TRAFFIC", 403 to "JAM_STAND_STILL_TRAFFIC", 404 to "JAM_LIGHT_TRAFFIC",
            601 to "HAZARD_ON_ROAD", 602 to "HAZARD_ON_SHOULDER", 603 to "HAZARD_WEATHER", 604 to "HAZARD_ON_ROAD_OBJECT",
            605 to "HAZARD_ON_ROAD_POT_HOLE", 606 to "HAZARD_ON_ROAD_ROAD_KILL", 607 to "HAZARD_ON_SHOULDER_CAR_STOPPED",
            608 to "HAZARD_ON_SHOULDER_ANIMALS", 609 to "HAZARD_ON_SHOULDER_MISSING_SIGN", 610 to "HAZARD_WEATHER_FOG",
            611 to "HAZARD_WEATHER_HAIL", 612 to "HAZARD_WEATHER_HEAVY_RAIN", 613 to "HAZARD_WEATHER_HEAVY_SNOW",
            614 to "HAZARD_WEATHER_FLOOD", 619 to "HAZARD_WEATHER_FREEZING_RAIN", 620 to "HAZARD_ON_ROAD_LANE_CLOSED",
            621 to "HAZARD_ON_ROAD_OIL", 622 to "HAZARD_ON_ROAD_ICE", 623 to "HAZARD_ON_ROAD_CONSTRUCTION",
            624 to "HAZARD_ON_ROAD_CAR_STOPPED", 625 to "HAZARD_ON_ROAD_TRAFFIC_LIGHT_FAULT", 626 to "HAZARD_ON_ROAD_EMERGENCY_VEHICLE",
            1301 to "ROAD_CLOSED_HAZARD", 1302 to "ROAD_CLOSED_CONSTRUCTION", 1303 to "ROAD_CLOSED_EVENT",
            2001 to "HAZARD_ON_ROAD_ICE", 2002 to "HAZARD_ON_ROAD_LANE_CLOSED", 2003 to "HAZARD_ON_ROAD_LANE_CLOSED",
            2004 to "HAZARD_ON_ROAD_LANE_CLOSED", 2005 to "HAZARD_ON_ROAD_LANE_CLOSED",
        )

        /** Errors Waze sends inside a 200 answer. */
        fun check(batch: List<Element>): List<Element> {
            for (el in batch) {
                el.errorCode?.let { code ->
                    val text = (el.errorText ?: "").lowercase()
                    if (text.contains("relogin") || text.contains("unknown userid") || text.contains("secretkey missing") || text.contains("secret key missing")) throw Failure.Expired(text)
                    if (code in 400..499) throw Failure.Rejected("$code $text")
                    if (code >= 500) throw Failure.Other("$code $text")
                }
                val t = el.loginErrorType
                if (t != null && el.loginSuccess == null) throw loginFailure(t)
            }
            return batch
        }

        /** Waze's own trouble isn't the account's fault: don't throw the account away. */
        fun loginFailure(type: Int): Failure = if (type == 0 || type == 2) Failure.Other("login error $type") else Failure.Rejected("login error $type")
    }

    data class Session(val id: Long, val secret: String)
    private data class Account(val user: String, val pass: String, val device: Int, val installation: String)

    sealed class Failure(msg: String) : Exception(msg) {
        /** The account is no good any more: make a new one. */
        class Rejected(msg: String) : Failure(msg)
        /** The session ended: log in again with the same account. */
        class Expired(msg: String) : Failure(msg)
        class Other(msg: String) : Failure(msg)
    }

    private val mutex = Mutex()
    private var account: Account? = null
    private var session: Session? = null
    private var seq = 1
    private var lastAsk = 0L
    private var handshaken = 0L
    private var http = makeClient()
    private var region = "row"
    private var backoffUntil = 0L
    private var failures = 0
    private val cache = LinkedHashMap<String, WazeAlert>()
    private val kv get() = KeyValue.store
    private var loadedAccount = false

    private fun makeClient() = Http.client(20, Http.MemoryCookies())

    private fun loadAccount() {
        if (loadedAccount) return
        loadedAccount = true
        val saved = Json.obj(kv.getString("wazeLiveAccount") ?: return) ?: return
        account = Account(saved["user"] as? String ?: return, saved["pass"] as? String ?: return,
            saved["device"].int() ?: 0, saved["installation"] as? String ?: return)
    }

    /** Reports in [boxes], plus what Waze already sent this session. Null when Waze couldn't be asked. */
    suspend fun alerts(boxes: List<Box>, near: LatLng): List<WazeAlert>? = mutex.withLock {
        loadAccount()
        if (boxes.isEmpty() || System.currentTimeMillis() < backoffUntil) return@withLock null
        try {
            try {
                refresh(boxes, near)
            } catch (e: Failure.Expired) {
                session = null
                refresh(boxes, near)
            } catch (e: Failure.Rejected) {
                if (!canRegister()) throw e
                account = null
                session = null
                kv.putString("wazeLiveAccount", null)
                refresh(boxes, near)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withLock failed(e)
        }
        val list = cache.values.filter { a ->
            boxes.any { a.location.lat >= it.s - 0.01 && a.location.lat <= it.n + 0.01 && a.location.lng >= it.w - 0.015 && a.location.lng <= it.e + 0.015 }
        }
        val types = mutableMapOf<String, Int>()
        for (a in list) types[typeKey(a.kind)] = (types[typeKey(a.kind)] ?: 0) + 1
        lastStatus = Waze.Status(System.currentTimeMillis(), 200, list.size, types = types)
        failures = 0
        list
    }

    /** Reports along a route: the line in pieces of about 10 km, one box each, plus a small box around [here]. */
    suspend fun alongRoute(line: List<LatLng>, here: LatLng): List<WazeAlert>? {
        if (line.size < 2) return emptyList()
        val d = 0.012
        val boxes = mutableListOf(Box(here.lat - d, here.lng - d * 1.6, here.lat + d, here.lng + d * 1.6))
        for (piece in Overpass.pieces(line, 10_000.0).take(4)) {
            val pad = 0.004
            boxes.add(Box(piece.minOf { it.lat } - pad, piece.minOf { it.lng } - pad, piece.maxOf { it.lat } + pad, piece.maxOf { it.lng } + pad))
        }
        return alerts(boxes, here)
    }

    private fun failed(error: Exception): List<WazeAlert>? {
        failures += 1
        backoffUntil = System.currentTimeMillis() + (min(30 * 2.0.pow(failures - 1), 600.0) * 1000).toLong()
        val code = when (error) {
            is Failure.Rejected -> 401
            is Failure.Expired -> 440
            is IOException -> 0
            else -> -1
        }
        lastStatus = Waze.Status(System.currentTimeMillis(), code, 0)
        return null
    }

    private suspend fun refresh(boxes: List<Box>, near: LatLng) {
        val r = region(near)
        if (r != region) {
            region = r
            session = null
            cache.clear()
        }
        if (account == null) register(near)
        if (session == null || System.currentTimeMillis() - lastAsk > 95_000) login(near)
        val s = session
        if (s != null && s.id != handshaken) {
            cache.clear()
            val b = box(near, 1_800.0)
            merge(command(listOf(seeMe(), "SetMood,1", "Location,${near.lng},${near.lat}", mapDisplayed(b)).joinToString("\n")))
            handshaken = s.id
        }
        for (b in boxes) {
            val cx = (b.w + b.e) / 2
            val cy = (b.s + b.n) / 2
            val hx = (b.e - b.w) / 2 * 0.75
            val hy = (b.n - b.s) / 2 * 0.75
            merge(command(mapDisplayed(Box(cy - hy, cx - hx, cy + hy, cx + hx))))
        }
    }

    private fun merge(batch: List<Element>) {
        for (el in batch) {
            val old = el.oldCommand?.trim()
            if (old != null && old.startsWith("RmAlert,")) cache.remove(old.removePrefix("RmAlert,").trim())
            el.alert?.let { cache[it.id] = it }
        }
    }

    private fun url(path: String): String {
        hostOverride?.let { return "https://$it/rtserver/distrib/$path" }
        val host = when (region) {
            "na" -> "rt-xlb-am.waze.com"
            "il" -> "rt-xlb-il.waze.com"
            else -> "rt-xlb-row.waze.com"
        }
        return "https://$host/rtserver/distrib/$path"
    }

    private suspend fun register(near: LatLng) {
        if (!canRegister()) throw Failure.Other("no new accounts today")
        val device = Random.nextInt(devices.size)
        val install = UUID.randomUUID().toString().lowercase()
        val body = listOf(clientInfo(device, install, near), line(2219, ByteArray(0))).joinToString("\n")
        val (data, code) = post("static", body, mapOf("User-Agent" to appVersion, "x-waze-network-version" to "3"))
        if (code >= 400 || data.isEmpty()) throw Failure.Other("register HTTP $code")
        val ok = check(parseBatch(data)).firstOrNull { it.registered != null }?.registered
        if (ok == null || ok.first.isEmpty() || ok.second.isEmpty()) throw Failure.Other("register: no account in the answer")
        val acc = Account(ok.first, ok.second, device, install)
        account = acc
        kv.putString("wazeLiveAccount", Json.write(mapOf("user" to acc.user, "pass" to acc.pass, "device" to acc.device, "installation" to acc.installation)))
        noteRegistration()
    }

    private suspend fun login(near: LatLng) {
        val acc = account ?: throw Failure.Other("login before register")
        http = makeClient()
        val cred = ProtoWriter().apply { string(1, acc.user); string(2, acc.pass) }
        val req = ProtoWriter().apply { message(1, cred.data); varint(3, 0) }
        val body = listOf(clientInfo(acc.device, acc.installation, near), line(2744, req.data), line(2108, ByteArray(0))).joinToString("\n")
        seq = 1
        val (data, code) = post("login", body, mapOf("User-Agent" to "waze/$appVersion", "cache-control" to "no-cache",
            "x-waze-network-version" to "3", "x-waze-wait-timeout" to "8500"))
        if (code in 400..499) throw Failure.Rejected("login HTTP $code")
        if (code >= 400 || data.isEmpty()) throw Failure.Other("login HTTP $code")
        for (el in check(parseBatch(data))) {
            el.loginErrorType?.let { throw loginFailure(it) }
            el.loginSuccess?.let { s ->
                if (s.id == 0L || s.secret.isEmpty()) throw Failure.Other("login: empty session")
                session = s
                seq = 2
                lastAsk = System.currentTimeMillis()
                return
            }
        }
        throw Failure.Rejected("login: no session in the answer")
    }

    private suspend fun command(body: String): List<Element> {
        val s = session ?: throw Failure.Expired("ask before login")
        val uid = ProtoWriter().apply { varint(1, s.id); string(2, s.secret) }
        val (data, code) = post("command", body, mapOf("User-Agent" to appVersion, "cache-control" to "no-cache",
            "x-waze-network-version" to "3", "x-waze-wait-timeout" to "10500", "uid" to Base64.getEncoder().encodeToString(uid.data)))
        if (code in 400..499) throw Failure.Expired("command HTTP $code")
        if (code >= 400 || data.isEmpty()) throw Failure.Other("command HTTP $code")
        val batch = check(parseBatch(data))
        lastAsk = System.currentTimeMillis()
        return batch
    }

    private suspend fun post(path: String, body: String, headers: Map<String, String>): Pair<ByteArray, Int> {
        val h = headers.toMutableMap()
        h["sequence-number"] = seq.toString()
        seq += 1
        val bytesOut = body.toByteArray()
        val r = Http.post(url(path), bytesOut, "binary/octet-stream", h, http)
        bytes += bytesOut.size + r.body.size
        return r.body to r.code
    }

    /** At most 3 new accounts a day, so a run of refusals can't use up Waze's daily allowance. */
    private fun canRegister(): Boolean {
        val start = kv.getDouble("wazeLiveRegDay")
        if (System.currentTimeMillis() / 1000.0 - start > 86_400) return true
        return kv.getInt("wazeLiveRegCount") < 3
    }

    private fun noteRegistration() {
        val now = System.currentTimeMillis() / 1000.0
        if (now - kv.getDouble("wazeLiveRegDay") > 86_400) {
            kv.putDouble("wazeLiveRegDay", now)
            kv.putInt("wazeLiveRegCount", 0)
        }
        kv.putInt("wazeLiveRegCount", kv.getInt("wazeLiveRegCount") + 1)
    }
}

/** Just enough protobuf to write Waze's requests: varints and length-delimited fields. */
class ProtoWriter {
    private val out = ByteArrayOutputStream()
    val data: ByteArray get() = out.toByteArray()

    fun raw(v0: Long) {
        var v = v0
        while ((v and 0x7FL.inv()) != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    fun varint(field: Int, v: Long) { raw((field shl 3).toLong()); raw(v) }

    /** int32 as protobuf writes it: a negative one sign-extended to 64 bits. */
    fun int32(field: Int, v: Int) = varint(field, v.toLong())

    fun message(field: Int, bytes: ByteArray) {
        raw(((field shl 3) or 2).toLong())
        raw(bytes.size.toLong())
        out.write(bytes)
    }

    fun string(field: Int, s: String) = message(field, s.toByteArray(Charsets.UTF_8))
}

/** Reads a message's top-level fields; null when the bytes aren't protobuf. */
object ProtoReader {
    class Field(val number: Int, val value: Long, val bytes: ByteArray? = null) {
        val string: String? get() = bytes?.let { String(it, Charsets.UTF_8) }
    }

    fun fields(b: ByteArray): List<Field>? {
        var i = 0
        val out = mutableListOf<Field>()
        fun varint(): Long? {
            var v = 0L
            var shift = 0
            while (i < b.size && shift < 64) {
                val byte = b[i].toInt() and 0xff
                i++
                v = v or ((byte and 0x7F).toLong() shl shift)
                if (byte < 0x80) return v
                shift += 7
            }
            return null
        }
        while (i < b.size) {
            val key = varint() ?: return null
            val number = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> out.add(Field(number, varint() ?: return null))
                1 -> {
                    if (i + 8 > b.size) return null
                    var v = 0L
                    for (k in 0 until 8) v = v or ((b[i + k].toLong() and 0xff) shl (8 * k))
                    i += 8
                    out.add(Field(number, v))
                }
                2 -> {
                    val len = varint() ?: return null
                    if (len < 0 || len > b.size - i) return null
                    out.add(Field(number, 0, b.copyOfRange(i, i + len.toInt())))
                    i += len.toInt()
                }
                5 -> {
                    if (i + 4 > b.size) return null
                    var v = 0L
                    for (k in 0 until 4) v = v or ((b[i + k].toLong() and 0xff) shl (8 * k))
                    i += 4
                    out.add(Field(number, v))
                }
                else -> return null
            }
        }
        return out
    }
}
