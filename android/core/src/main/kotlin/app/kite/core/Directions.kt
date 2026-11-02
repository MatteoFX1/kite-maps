package app.kite.core

import kotlin.math.*

/**
 * Google's map responses start with an XSSI guard (`)]}'` then a newline) and then a deeply
 * nested positional JSON array with no field names (port of Vela's GoogleResponse).
 */
object GoogleJSON {
    private val guards = listOf(")]}'\n", ")]}',\n", ")]}'", ")]}", "while(1);", "for(;;);")

    fun strip(body: String): String {
        for (g in guards) if (body.startsWith(g)) return body.substring(g.length).trim('\n', '\r')
        return body
    }

    fun parse(body: String): Any? = Json.parse(strip(body))
}

/**
 * Google `/maps/preview/directions` request (port of Vela's DirectionsPb). Routes are at
 * `root[0][1]`; route geometry at `root[0][7][i]` as E7 deltas.
 */
object DirectionsPb {
    const val template = "!1m4!3m2!3d{OLAT}!4d{OLNG}!6e2!1m4!3m2!3d{DLAT}!4d{DLNG}!6e2!3m12!1m3!1d24960.741896132306" +
        "!2d-121.7527808!3d38.554674999999996!2m3!1f0.0!2f0.0!3f0.0!3m2!1i1024!2i768!4f13.1!6m56!1m" +
        "5!18b1!30b1!31m1!1b1!34e1!2m4!5m1!6e2!20e3!39b1!6m27!32i1!49b1!63m0!66b1!85b1!114b1!149b1!" +
        "206b1!209b1!212b1!216b1!222b1!223b1!232b1!234b1!235b1!244b1!246b1!250b1!253b1!260b1!266b1!" +
        "270b1!273b1!279b1!281b1!291m0!10b1!12b1!13b1!14b1!16b1!17m1!3e1!20m5!1e{MODE}!2e3!5e2!6b1!" +
        "14b1!46m1!1b0!96b1!99b1!15i10142!20m28!1m6!1m2!1i0!2i0!2m2!1i530!2i768!1m6!1m2!1i974!2i0!2" +
        "m2!1i1024!2i768!1m6!1m2!1i0!2i0!2m2!1i1024!2i20!1m6!1m2!1i0!2i748!2m2!1i1024!2i768!27b1!40" +
        "i783!47m2!8b1!10e2"

    fun build(
        origin: LatLng, destination: LatLng, mode: TravelMode,
        avoidHighways: Boolean = false, avoidTolls: Boolean = false, waypoints: List<LatLng> = emptyList(),
    ): String {
        var pb = template
        if (waypoints.isNotEmpty()) {
            val i = pb.indexOf("!1m4!3m2!3d{DLAT}!4d{DLNG}!6e2")
            if (i >= 0) pb = pb.substring(0, i) + waypoints.joinToString("") { "!1m4!3m2!3d${it.lat.plain()}!4d${it.lng.plain()}!6e2" } + pb.substring(i)
        }
        val all = listOf(origin, destination) + waypoints
        val re = Regex("!3m12!1m3!1d[0-9.]+!2d-?[0-9.]+!3d-?[0-9.]+")
        re.find(pb)?.let { m ->
            val s = all.minOf { it.lat }
            val n = all.maxOf { it.lat }
            val w = all.minOf { it.lng }
            val e = all.maxOf { it.lng }
            val lat = (s + n) / 2
            val lng = (w + e) / 2
            val span = max((n - s) * 111_320, (e - w) * 111_320 * cos(lat * PI / 180))
            pb = pb.replaceRange(m.range, "!3m12!1m3!1d${max(span * 1.35, 1500.0)}!2d${lng.plain()}!3d${lat.plain()}")
        }
        if (mode == TravelMode.DRIVE && (avoidHighways || avoidTolls)) {
            val key = "!6m56!1m5!18b1!30b1!31m1!1b1!34e1!2m4"
            val i = pb.indexOf(key)
            if (i >= 0) {
                val flags = (if (avoidHighways) "!1b1" else "") + (if (avoidTolls) "!2b1" else "")
                val added = (if (avoidHighways) 1 else 0) + (if (avoidTolls) 1 else 0)
                pb = pb.substring(0, i) + "!6m${56 + added}!1m5!18b1!30b1!31m1!1b1!34e1!2m${4 + added}$flags" + pb.substring(i + key.length)
            }
        }
        return pb.replace("{OLAT}", origin.lat.toString()).replace("{OLNG}", origin.lng.toString())
            .replace("{DLAT}", destination.lat.toString()).replace("{DLNG}", destination.lng.toString())
            .replace("{MODE}", mode.raw.toString())
    }
}

object DirectionsParser {
    fun parse(root: Any?, mode: TravelMode): List<Route> {
        val routes = arr(at(root, 0, 1)) ?: return emptyList()
        val geoms = arr(at(root, 0, 7))
        return routes.mapIndexedNotNull { i, r ->
            parseRoute(r, decodeGeometry(geoms?.let { if (i < it.size) it[i] else null }), mode)
        }
    }

    private fun parseRoute(route: Any?, geometry: List<LatLng>?, mode: TravelMode): Route? {
        val summary = at(route, 0) ?: return null
        val distance = gdbl(at(summary, 2, 0)) ?: return null
        val typical = gdbl(at(summary, 3, 0)) ?: return null
        val traffic = gdbl(at(summary, 10, 0, 0))
        val start = coord(at(summary, 7, 3, 2))
        val end = coord(at(summary, 7, 3, 3))
        val line = if ((geometry?.size ?: 0) >= 2) geometry!! else listOfNotNull(start, end)
        if (line.size < 2) return null
        val spans = (arr(at(route, 3, 5, 0)) ?: emptyList()).mapNotNull { s ->
            val level = gint(at(s, 0)) ?: return@mapNotNull null
            val st = gdbl(at(s, 1)) ?: return@mapNotNull null
            val len = gdbl(at(s, 2)) ?: return@mapNotNull null
            if (len > 0 && level >= 1) TrafficSpan(level, st, len) else null
        }.sortedBy { it.startMeters }
        val steps = mutableListOf<Maneuver>()
        collectSteps(route, steps)
        val r = Route(mode, line, distance, typical, traffic, gstr(at(summary, 1)), place(steps, line), spans, source = "google")
        val stepTotal = steps.sumOf { if (it.type == ManeuverType.ARRIVE) 0.0 else it.distanceMeters }
        r.stepsComplete = stepTotal >= Geo.length(line) * 0.85
        return r
    }

    private fun coord(n: Any?): LatLng? {
        val lat = gdbl(at(n, 2)) ?: return null
        val lng = gdbl(at(n, 3)) ?: return null
        return LatLng(lat, lng)
    }

    /** `[0]` latitude deltas, `[1]` longitude deltas, E7, the first absolute. */
    fun decodeGeometry(node: Any?): List<LatLng>? {
        val lat = arr(at(node, 0)) ?: return null
        val lng = arr(at(node, 1)) ?: return null
        if (lat.size != lng.size || lat.size < 2) return null
        var la = 0.0
        var ln = 0.0
        return lat.indices.map { i ->
            la += gdbl(lat[i]) ?: 0.0
            ln += gdbl(lng[i]) ?: 0.0
            LatLng(la / 1e7, ln / 1e7)
        }
    }

    fun collectSteps(n: Any?, out: MutableList<Maneuver>) {
        if (n is List<*>) {
            val node = n.firstOrNull() as? List<*>
            if (node != null) {
                foldedStep(node, gstr(at(n, 2, 1)))?.let { out.add(it); return }
                val s = gstr(at(node, 1))
                if (s != null && s.contains("<step ") && node.size > 2) {
                    val m = parseStep(s)
                    val icon = gstr(at(n, 2, 1))
                    if (m.sideUnknown && icon != null) {
                        if (icon.contains("-left")) { m.type = ManeuverType.RAMP_LEFT; m.sideUnknown = false }
                        else if (icon.contains("-right")) { m.type = ManeuverType.RAMP_RIGHT; m.sideUnknown = false }
                    }
                    out.add(m)
                    return
                }
            }
            for (e in n) collectSteps(e, out)
        } else if (n is String && n.contains("<step ")) {
            out.add(parseStep(n))
        }
    }

    private val turnWord = Regex("^\\s*((slight|sharp)\\s+)?(left|right)\\s*$", RegexOption.IGNORE_CASE)

    /** A step Google sends with no `<step>` text: the long motorway stretch inside a folded group. */
    fun foldedStep(a: List<*>, icon: String?): Maneuver? {
        if (a.size <= 3 || gint(at(a, 0)) != 0) return null
        val markup = a[1] as? String ?: return null
        if (markup.isNotEmpty()) return null
        val meters = gdbl(at(a, 2, 0)) ?: return null
        if (gstr(at(a, 2, 1)) == null || gdbl(at(a, 3, 0)) == null || gstr(at(a, 3, 1)) == null) return null
        var text = ""
        var road: String? = null
        for (e in a) {
            val pieces = e as? List<*> ?: continue
            if (pieces.isEmpty() || !pieces.all { gint(at(it, 0)) != null && gstr(at(it, 1, 0)) != null }) continue
            for (p in pieces) {
                val t = gstr(at(p, 1, 0)) ?: ""
                if (gint(at(p, 1, 1)) == 1 && road == null && !turnWord.containsMatchIn(t)) road = t
                text += t
            }
            break
        }
        val kind = (icon ?: "").split(" ").lastOrNull { it.startsWith("dir-tt-") }?.drop(7) ?: ""
        val token = kind.uppercase().replace("-", "_")
        val side = if (kind.contains("left")) "LEFT" else if (kind.contains("right")) "RIGHT" else null
        val severity = if (kind.contains("slight")) "SLIGHT" else if (kind.contains("sharp")) "SHARP" else null
        val m = parseStep("<step meters='${meters.toInt()}'>$text</step>")
        m.type = mapType(token.ifEmpty { null }, side, severity)
        m.road = road
        if (text.isBlank()) m.instruction = phrase(m.type)
        return m
    }

    fun phrase(t: ManeuverType): String = when (t) {
        ManeuverType.TURN_LEFT -> "Turn left"
        ManeuverType.TURN_RIGHT -> "Turn right"
        ManeuverType.SLIGHT_LEFT -> "Slight left"
        ManeuverType.SLIGHT_RIGHT -> "Slight right"
        ManeuverType.SHARP_LEFT -> "Sharp left"
        ManeuverType.SHARP_RIGHT -> "Sharp right"
        ManeuverType.UTURN -> "Make a U-turn"
        ManeuverType.MERGE -> "Merge"
        ManeuverType.RAMP_LEFT -> "Take the slip road on the left"
        ManeuverType.RAMP_RIGHT -> "Take the slip road on the right"
        ManeuverType.FORK_LEFT, ManeuverType.KEEP_LEFT -> "Keep left"
        ManeuverType.FORK_RIGHT, ManeuverType.KEEP_RIGHT -> "Keep right"
        ManeuverType.ROUNDABOUT -> "At the roundabout, continue"
        else -> "Continue"
    }

    private val laneRe = Regex("^(Use (the .+? lanes?|any lane)) to ", RegexOption.IGNORE_CASE)

    fun parseStep(s: String): Maneuver {
        fun attr(pattern: String): String? {
            val m = Regex(pattern, RegexOption.IGNORE_CASE).find(s)?.value ?: return null
            val q1 = m.indexOf('\'')
            if (q1 < 0) return null
            return m.substring(q1 + 1).dropLast(1)
        }
        val token = attr("maneuver='[^']+'")
        val side = attr("side='[^']+'")
        val severity = attr("\\btype='[^']+'")
        val meters = attr("meters='[0-9]+'")?.toDoubleOrNull() ?: 0.0
        val road = Regex("<road>[^<]+</road>").find(s)?.value?.replace("<road>", "")?.replace("</road>", "")
        var text = s.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ")
            .replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&amp;", "&").trim()
        var lane: String? = null
        laneRe.find(text)?.let { r ->
            lane = r.value.replace(" to ", "").trim()
            text = text.removeRange(r.range)
        }
        text = text.capitalizedFirst()
        val m = Maneuver(mapType(token, side, severity), text.ifEmpty { "Continue" }, LatLng(0.0, 0.0), 0.0, meters, road, lane)
        if ((token?.uppercase() ?: "") in setOf("ON_RAMP", "OFF_RAMP", "RAMP") && side == null) {
            m.type = ManeuverType.RAMP_LEFT
            m.sideUnknown = true
        }
        if (m.type == ManeuverType.ROUNDABOUT) m.exit = exitNumber(m.instruction)
        return m
    }

    /** "take the 3rd exit" is 3. */
    fun exitNumber(text: String): Int? {
        val words = listOf("first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6, "seventh" to 7, "eighth" to 8)
        val lower = text.lowercase()
        Regex("(\\d+)(st|nd|rd|th) exit").find(lower)?.let { return it.groupValues[1].toIntOrNull() }
        for ((w, n) in words) if (lower.contains("$w exit")) return n
        return null
    }

    fun mapType(token: String?, side: String?, severity: String?): ManeuverType {
        val left = side?.uppercase() == "LEFT"
        val right = side?.uppercase() == "RIGHT"
        val slight = severity?.uppercase() == "SLIGHT"
        val sharp = severity?.uppercase() == "SHARP"
        fun lr(l: ManeuverType, r: ManeuverType, f: ManeuverType) = if (left) l else if (right) r else f
        val t = token?.uppercase() ?: ""
        return when (t) {
            "DEPART" -> ManeuverType.DEPART
            "DESTINATION", "ARRIVE" -> ManeuverType.ARRIVE
            "TURN_LEFT" -> ManeuverType.TURN_LEFT
            "TURN_RIGHT" -> ManeuverType.TURN_RIGHT
            "TURN_SLIGHT_LEFT" -> ManeuverType.SLIGHT_LEFT
            "TURN_SLIGHT_RIGHT" -> ManeuverType.SLIGHT_RIGHT
            "TURN_SHARP_LEFT" -> ManeuverType.SHARP_LEFT
            "TURN_SHARP_RIGHT" -> ManeuverType.SHARP_RIGHT
            "TURN" -> when {
                slight -> lr(ManeuverType.SLIGHT_LEFT, ManeuverType.SLIGHT_RIGHT, ManeuverType.STRAIGHT)
                sharp -> lr(ManeuverType.SHARP_LEFT, ManeuverType.SHARP_RIGHT, ManeuverType.STRAIGHT)
                else -> lr(ManeuverType.TURN_LEFT, ManeuverType.TURN_RIGHT, ManeuverType.STRAIGHT)
            }
            "UTURN", "UTURN_LEFT", "UTURN_RIGHT" -> ManeuverType.UTURN
            "STRAIGHT", "CONTINUE", "NAME_CHANGE" -> ManeuverType.STRAIGHT
            "MERGE", "MERGE_LEFT", "MERGE_RIGHT" -> ManeuverType.MERGE
            "FORK", "FORK_LEFT", "FORK_RIGHT" -> lr(ManeuverType.FORK_LEFT, ManeuverType.FORK_RIGHT, ManeuverType.STRAIGHT)
            "ON_RAMP", "OFF_RAMP", "RAMP", "RAMP_LEFT", "ON_RAMP_LEFT", "OFF_RAMP_LEFT",
            "RAMP_RIGHT", "ON_RAMP_RIGHT", "OFF_RAMP_RIGHT" -> lr(ManeuverType.RAMP_LEFT, ManeuverType.RAMP_RIGHT, ManeuverType.RAMP_LEFT)
            "KEEP", "KEEP_LEFT", "KEEP_RIGHT" -> lr(ManeuverType.KEEP_LEFT, ManeuverType.KEEP_RIGHT, ManeuverType.STRAIGHT)
            else -> if (t.contains("ROUNDABOUT")) ManeuverType.ROUNDABOUT
            else if (left || right) lr(ManeuverType.TURN_LEFT, ManeuverType.TURN_RIGHT, ManeuverType.UNKNOWN) else ManeuverType.UNKNOWN
        }
    }

    /** Put the steps on the line from the end backwards: each one starts its step's length before the next one. */
    fun place(steps: List<Maneuver>, line: List<LatLng>): MutableList<Maneuver> {
        if (steps.isEmpty() || line.size < 2) return steps.toMutableList()
        val cum = Geo.cumulative(line)
        val total = cum.last()
        val body = steps.map { it.copy() }.toMutableList()
        if (body.lastOrNull()?.type == ManeuverType.ARRIVE) body.removeAt(body.size - 1)
        val stepTotal = body.sumOf { it.distanceMeters }
        val scale = if (stepTotal > total && stepTotal > 0) total / stepTotal else 1.0
        var end = total
        val out = mutableListOf<Maneuver>()
        for (m in body.reversed()) {
            val start = max(0.0, end - m.distanceMeters * scale)
            m.alongMeters = start
            m.location = Geo.point(line, start, cum)
            if (m.sideUnknown) { m.type = rampSide(line, cum, start); m.sideUnknown = false }
            out.add(0, m)
            end = start
        }
        val first = out.firstOrNull()
        if (first != null && first.alongMeters > 15) {
            val ahead = Geo.point(line, min(30.0, first.alongMeters), cum)
            out.add(0, Maneuver(ManeuverType.DEPART, "Head " + compass(Geo.bearing(line[0], ahead)), line[0], 0.0, first.alongMeters))
        }
        val last = steps.last()
        out.add(Maneuver(ManeuverType.ARRIVE, if (last.type == ManeuverType.ARRIVE) last.instruction else "You have arrived",
            line[line.size - 1], total, 0.0))
        return out
    }

    /** Which side a slip road leaves on, from the route line's bend; where it hardly bends, the side traffic keeps to. */
    fun rampSide(line: List<LatLng>, cum: DoubleArray, along: Double): ManeuverType {
        val total = cum.last()
        fun heading(a: Double, b: Double): Double? {
            val p = Geo.point(line, max(0.0, a), cum)
            val q = Geo.point(line, min(total, b), cum)
            return if (p.distanceTo(q) > 3) Geo.bearing(p, q) else null
        }
        val left = Geo.drivesOnLeft(Geo.point(line, along, cum))
        val a = heading(along - 60, along)
        val b = heading(along + 30, along + 150)
        if (a == null || b == null) return if (left) ManeuverType.RAMP_LEFT else ManeuverType.RAMP_RIGHT
        val turn = ((b - a + 540) % 360) - 180
        if (turn <= -15) return ManeuverType.RAMP_LEFT
        if (turn >= 15) return ManeuverType.RAMP_RIGHT
        return if (left) ManeuverType.RAMP_LEFT else ManeuverType.RAMP_RIGHT
    }

    fun compass(bearing: Double): String {
        val names = listOf("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest")
        return names[floor((bearing + 22.5) / 45).toInt() % 8]
    }
}
