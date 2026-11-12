@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.kite

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kite.core.*
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Kite's colors: a deep navy night look with one bright teal for "go".
 * Sized and colored for a phone in a car holder. The same values as the iPhone app.
 */
object Vela {
    val teal = Color(0xFF0F766E)
    val tealLight = Color(0xFF2DD4BF)
    val routeBlue = Color(0xFF2F7BFF)
    val navGreen = Color(0xFF0F766E)
    val navBand = Color(0xFF0B4F49)
    val open = Color(0xFF16A34A)
    val openDark = Color(0xFF4ADE80)
    val closed = Color(0xFFDC2626)
    val closedDark = Color(0xFFF87171)
    val star = Color(0xFFF9AB00)
    val camera = Color(0xFFFF8A00)
    val police = Color(0xFF2F7BFF)

    fun accent(dark: Boolean) = if (dark) tealLight else Color(0xFF14B8A6)
    fun onAccent(@Suppress("UNUSED_PARAMETER") dark: Boolean) = Color(0xFF04241F)
    fun accentText(dark: Boolean) = if (dark) tealLight else teal
    fun sheet(dark: Boolean) = if (dark) Color(0xFF151F30) else Color.White
    fun surface(dark: Boolean) = if (dark) Color(0xFF1A2638) else Color.White
    fun ground(dark: Boolean) = if (dark) Color(0xFF0E1726) else Color(0xFFF2F5F9)
    fun chip(dark: Boolean) = if (dark) Color(0xFF243349) else Color(0xFFEDF1F6)
    fun tonal(dark: Boolean) = if (dark) Color(0xFF12403C) else Color(0xFFCCFBF1)
    fun onTonal(dark: Boolean) = if (dark) Color(0xFF99F6E4) else Color(0xFF134E4A)
    fun muted(dark: Boolean) = if (dark) Color(0xFF94A3B8) else Color(0xFF64748B)
    fun primary(dark: Boolean) = if (dark) Color.White else Color(0xFF0F172A)
}

/** Whether the screen is in dark colors right now. */
val LocalDark = staticCompositionLocalOf { true }

/** The rounded type, like the iPhone's SF Rounded. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Rounded: FontFamily = FontFamily(
    listOf(400, 500, 600, 700, 800, 900).map { w ->
        Font(R.font.nunito, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    }
)

/** Text sizes matching iOS's at the "xLarge" type size the iPhone app never goes below. */
object KType {
    val largeTitle = 36.sp
    val title = 30.sp
    val title2 = 24.sp
    val title3 = 22.sp
    val headline = 19.sp
    val body = 19.sp
    val callout = 18.sp
    val subheadline = 17.sp
    val footnote = 15.sp
    val caption = 14.sp
    val caption2 = 13.sp
}

@Composable
fun KiteTheme(content: @Composable () -> Unit) {
    val dark = when (AppSettings.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val scheme = if (dark) darkColorScheme(
        primary = Vela.tealLight, onPrimary = Vela.onAccent(true), background = Vela.ground(true),
        surface = Vela.sheet(true), onSurface = Color.White, onBackground = Color.White,
        surfaceVariant = Vela.chip(true), onSurfaceVariant = Vela.muted(true),
        secondaryContainer = Vela.tonal(true), onSecondaryContainer = Vela.onTonal(true),
    ) else lightColorScheme(
        primary = Vela.teal, onPrimary = Color.White, background = Vela.ground(false),
        surface = Color.White, onSurface = Vela.primary(false), onBackground = Vela.primary(false),
        surfaceVariant = Vela.chip(false), onSurfaceVariant = Vela.muted(false),
        secondaryContainer = Vela.tonal(false), onSecondaryContainer = Vela.onTonal(false),
    )
    val base = Typography()
    val type = Typography(
        displayLarge = base.displayLarge.copy(fontFamily = Rounded),
        headlineLarge = base.headlineLarge.copy(fontFamily = Rounded),
        headlineMedium = base.headlineMedium.copy(fontFamily = Rounded),
        titleLarge = base.titleLarge.copy(fontFamily = Rounded, fontSize = KType.title3),
        titleMedium = base.titleMedium.copy(fontFamily = Rounded, fontSize = KType.headline),
        titleSmall = base.titleSmall.copy(fontFamily = Rounded),
        bodyLarge = base.bodyLarge.copy(fontFamily = Rounded, fontSize = KType.body),
        bodyMedium = base.bodyMedium.copy(fontFamily = Rounded, fontSize = KType.subheadline),
        bodySmall = base.bodySmall.copy(fontFamily = Rounded, fontSize = KType.footnote),
        labelLarge = base.labelLarge.copy(fontFamily = Rounded, fontSize = KType.callout),
        labelMedium = base.labelMedium.copy(fontFamily = Rounded),
        labelSmall = base.labelSmall.copy(fontFamily = Rounded),
    )
    CompositionLocalProvider(LocalDark provides dark) {
        MaterialTheme(colorScheme = scheme, typography = type) {
            CompositionLocalProvider(LocalTextStyle provides TextStyle(fontFamily = Rounded, fontSize = KType.body)) {
                content()
            }
        }
    }
}

/** Primary and secondary text colors for the current look. */
val primaryText: Color @Composable get() = Vela.primary(LocalDark.current)
val secondaryText: Color @Composable get() = Vela.muted(LocalDark.current)

object Fmt {
    private val country: String get() = Locale.getDefault().country
    private val autoImperial: Boolean get() = country in setOf("US", "GB", "LR", "MM", "UK")

    val metric: Boolean
        get() = when (AppSettings.units) {
            UnitsMode.METRIC -> true
            UnitsMode.IMPERIAL, UnitsMode.YARDS -> false
            UnitsMode.AUTO -> !autoImperial
        }

    /** Short distances in yards, the British way. */
    val yards: Boolean
        get() = when (AppSettings.units) {
            UnitsMode.YARDS -> true
            UnitsMode.AUTO -> country == "GB" || country == "UK"
            else -> false
        }

    private fun r(x: Double) = x.roundToInt()

    fun distance(m: Double): String {
        if (metric) {
            if (m < 950) return "${r(m / 10) * 10} m"
            return if (m < 9_950) String.format(Locale.US, "%.1f km", m / 1000) else "${r(m / 1000)} km"
        }
        if (yards) {
            val yd = m * 1.09361
            if (yd < 500) return "${max(r(yd / 10) * 10, 10)} yd"
        } else {
            val ft = m * 3.28084
            if (ft < 1000) return "${max(r(ft / 50) * 50, 50)} ft"
        }
        val mi = m / 1609.344
        return if (mi < 9.95) String.format(Locale.US, "%.1f mi", mi) else "${r(mi)} mi"
    }

    /** Words for the voice: "400 feet", "1.2 miles". */
    fun spokenDistance(m: Double): String {
        if (metric) {
            if (m < 950) return "${r(m / 50) * 50} meters"
            val km = m / 1000
            return if (km < 1.05) "1 kilometer" else String.format(Locale.US, "%.1f kilometers", km)
        }
        if (yards) {
            val yd = m * 1.09361
            if (yd < 500) return "${max(r(yd / 50) * 50, 50)} yards"
        } else {
            val ft = m * 3.28084
            if (ft < 1000) return "${max(r(ft / 100) * 100, 100)} feet"
        }
        val mi = m / 1609.344
        if (mi < 0.3) return if (yards) "a quarter of a mile" else "a quarter mile"
        if (mi < 0.6) return "half a mile"
        if (mi < 0.9) return "three quarters of a mile"
        return if (mi < 1.05) "1 mile" else String.format(Locale.US, "%.1f miles", mi)
    }

    /** A limit for the voice: "30 miles per hour" (to the posted 5), "50 kilometers per hour". */
    fun spokenSpeed(kmh: Double): String =
        if (metric) "${max(5, r(kmh / 5) * 5)} kilometers per hour" else "${postedMph(kmh)} miles per hour"

    /**
     * A limit in mph as the sign shows it, to the nearest 5. Never 0: a road mapped "walk" (about
     * 7 km/h, a car park or a living street) would round down to a "0" sign; the lowest sign is 5.
     */
    fun postedMph(kmh: Double): Int = max(5, r(kmh / 1.609344 / 5) * 5)

    /** A speed in the user's units, without the unit. */
    fun speed(kmh: Double): Int = r(if (metric) kmh else kmh / 1.609344)

    val speedUnit: String get() = if (metric) "km/h" else "mph"

    fun duration(s: Double): String {
        val mins = max(1, r(s / 60))
        if (mins < 60) return "$mins min"
        val h = mins / 60; val m = mins % 60
        return if (m == 0) "$h h" else "$h h $m min"
    }

    fun shortDuration(s: Double): String {
        val mins = max(1, r(s / 60))
        if (mins < 60) return "$mins min"
        val h = mins / 60; val m = mins % 60
        return if (m == 0) "${h}h" else "${h}h ${m}m"
    }

    fun arrival(s: Double): String = time(Date(System.currentTimeMillis() + (s * 1000).toLong()))

    fun time(d: Date): String = android.text.format.DateFormat.getTimeFormat(KiteApp.app).format(d)

    fun count(n: Int): String = java.text.NumberFormat.getIntegerInstance().format(n)
}

@Composable
fun statusColor(p: Place): Color {
    val dark = LocalDark.current
    if (p.permanentlyClosed) return if (dark) Vela.closedDark else Vela.closed
    return when (p.openNow) {
        true -> if (dark) Vela.openDark else Vela.open
        false -> if (dark) Vela.closedDark else Vela.closed
        null -> secondaryText
    }
}

/** "Open" in green, then "· Closes 9:30 PM" in gray, the way Vela splits Google's status. */
@Composable
fun StatusText(place: Place, fontSize: androidx.compose.ui.unit.TextUnit = KType.subheadline) {
    val c = statusColor(place)
    val gray = secondaryText
    if (place.permanentlyClosed) {
        Text("Permanently closed", color = c, fontSize = fontSize)
    } else place.statusText?.let { s ->
        val parts = s.split(" · ")
        Text(buildAnnotatedString {
            withStyle(SpanStyle(color = c)) { append(parts[0]) }
            if (parts.size > 1) withStyle(SpanStyle(color = gray)) { append(" · " + parts.drop(1).joinToString(" · ")) }
        }, fontSize = fontSize)
    }
}

@Composable
fun Stars(rating: Double, size: Dp = 13.dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        for (i in 0 until 5) {
            val fill = rating - i
            Icon(
                when { fill >= 0.75 -> Icons.Filled.Star; fill >= 0.25 -> Icons.AutoMirrored.Filled.StarHalf; else -> Icons.Outlined.StarOutline },
                null, tint = Vela.star, modifier = Modifier.size(size),
            )
        }
    }
}

/** "4.7 ★★★★★ (1,341) · 0.2 mi · Sushi restaurant · $10–20" */
@Composable
fun PlaceMetaLine(place: Place, showStars: Boolean = true, here: LatLng? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        place.rating?.let { r ->
            Text(String.format(Locale.US, "%.1f", r), color = primaryText, fontSize = KType.subheadline)
            if (showStars) Stars(r) else Icon(Icons.Filled.Star, null, tint = Vela.star, modifier = Modifier.size(12.dp))
            place.reviewCount?.let { Text("(${Fmt.count(it)})", color = secondaryText, fontSize = KType.subheadline) }
        }
        val meters = here?.distanceTo(place.location) ?: place.distanceMeters
        val rest = listOfNotNull(meters?.let { Fmt.distance(it) }, place.category, place.priceText)
        if (rest.isNotEmpty()) {
            Text((if (place.rating != null) "· " else "") + rest.joinToString(" · "), color = secondaryText,
                fontSize = KType.subheadline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Arrow for a maneuver. */
fun maneuverIcon(t: ManeuverType): ImageVector = when (t) {
    ManeuverType.DEPART, ManeuverType.STRAIGHT, ManeuverType.UNKNOWN -> Icons.Filled.ArrowUpward
    ManeuverType.ARRIVE -> Icons.Filled.Place
    ManeuverType.TURN_LEFT -> Icons.Filled.TurnLeft
    ManeuverType.TURN_RIGHT -> Icons.Filled.TurnRight
    ManeuverType.SLIGHT_LEFT, ManeuverType.KEEP_LEFT, ManeuverType.RAMP_LEFT, ManeuverType.FORK_LEFT -> Icons.Filled.TurnSlightLeft
    ManeuverType.SLIGHT_RIGHT, ManeuverType.KEEP_RIGHT, ManeuverType.RAMP_RIGHT, ManeuverType.FORK_RIGHT -> Icons.Filled.TurnSlightRight
    ManeuverType.SHARP_LEFT -> Icons.Filled.TurnSharpLeft
    ManeuverType.SHARP_RIGHT -> Icons.Filled.TurnSharpRight
    ManeuverType.UTURN -> Icons.Filled.UTurnLeft
    ManeuverType.MERGE -> Icons.Filled.Merge
    ManeuverType.ROUNDABOUT -> Icons.Filled.RoundaboutLeft
}

/** Frosted look for everything floating over the map: the surface color, a thin bright edge and a soft shadow. */
@Composable
fun Modifier.glass(shape: Shape): Modifier {
    val dark = LocalDark.current
    return this
        .shadow(if (dark) 10.dp else 8.dp, shape, ambientColor = Color.Black.copy(alpha = if (dark) 0.45f else 0.16f),
            spotColor = Color.Black.copy(alpha = if (dark) 0.45f else 0.16f))
        .clip(shape)
        .background(Vela.surface(dark).copy(alpha = if (dark) 0.94f else 0.96f))
        .border(0.75.dp, Color.White.copy(alpha = if (dark) 0.08f else 0.5f), shape)
}

/** Round buttons floating on the map (layers, location, compass). */
@Composable
fun MapFab(icon: ImageVector, tint: Color? = null, size: Dp = 56.dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.size(size).glass(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint ?: primaryText, modifier = Modifier.size(size * 0.46f))
    }
}

/** Pill buttons in sheets: filled teal for the main one, tonal for the rest. */
@Composable
fun PillButton(title: String, icon: ImageVector, filled: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val dark = LocalDark.current
    Row(
        modifier.height(52.dp).clip(CircleShape)
            .background(if (filled) Vela.accent(dark) else Vela.tonal(dark))
            .clickable(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        val c = if (filled) Vela.onAccent(dark) else Vela.onTonal(dark)
        Icon(icon, null, tint = c, modifier = Modifier.size(20.dp))
        Text(title, color = c, fontWeight = FontWeight.Bold, fontSize = KType.body, maxLines = 1)
    }
}

@Composable
fun CircleIconButton(icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.size(44.dp).clip(CircleShape).background(Vela.chip(LocalDark.current)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = primaryText, modifier = Modifier.size(20.dp))
    }
}
