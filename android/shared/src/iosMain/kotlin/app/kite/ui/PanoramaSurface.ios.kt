package app.kite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import kotlin.math.PI
import kotlin.math.tan

/** The panorama drawn by the phone's graphics chip through Skia: each pixel looks up its spot of the picture. */
@Composable
actual fun PanoramaSurface(state: PanoState, modifier: Modifier) {
    val effect = remember { RuntimeEffect.makeForShader(SKSL) }
    val bitmap = state.image
    val image = remember(bitmap) { bitmap?.let { Image.makeFromBitmap(it.asSkiaBitmap()) } }
    Canvas(modifier) {
        val img = image ?: return@Canvas
        val w = size.width; val h = size.height
        val aspect = w / h.coerceAtLeast(1f)
        // Half the field of view across the shorter side, like a camera's.
        val t = tan(state.fov * PI / 360).toFloat()
        val tx = if (aspect >= 1) t * aspect else t
        val ty = if (aspect >= 1) t else t / aspect
        val b = RuntimeShaderBuilder(effect)
        b.uniform("size", w, h)
        b.uniform("span", tx, ty)
        b.uniform("yaw", ((state.heading - state.pano.heading) * PI / 180).toFloat())
        b.uniform("pitch", (state.pitch * PI / 180).toFloat())
        b.uniform("panoSize", img.width.toFloat(), img.height.toFloat())
        b.child("pano", img.makeShader(FilterTileMode.REPEAT, FilterTileMode.CLAMP, FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE), null))
        drawIntoCanvas { c ->
            c.nativeCanvas.drawRect(Rect.makeWH(w, h), Paint().apply { shader = b.makeShader() })
        }
    }
}

// The ray through each pixel, turned by where you look, gives a compass heading and a height:
// the panorama's own heading sits in the middle of the picture. The same maths as Android's.
private const val SKSL = """
uniform shader pano;
uniform float2 size;
uniform float2 span;
uniform float yaw;
uniform float pitch;
uniform float2 panoSize;
const float PI = 3.14159265;
half4 main(float2 p) {
    float2 ndc = float2(p.x / size.x * 2.0 - 1.0, 1.0 - p.y / size.y * 2.0);
    float3 d = normalize(float3(ndc.x * span.x, ndc.y * span.y, 1.0));
    float cp = cos(pitch);
    float sp = sin(pitch);
    d = float3(d.x, d.y * cp + d.z * sp, -d.y * sp + d.z * cp);
    float lon = atan(d.x, d.z) + yaw;
    float lat = asin(clamp(d.y, -1.0, 1.0));
    float2 uv = float2(fract(lon / (2.0 * PI) + 0.5), 0.5 - lat / PI);
    return pano.eval(uv * panoSize);
}
"""
