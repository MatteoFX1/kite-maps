package app.kite.ui

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import app.kite.*
import app.kite.core.*
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.ceil

/** The panorama, drawn by the phone's graphics chip (OpenGL): each pixel looks up its spot of the picture. */
@Composable
actual fun PanoramaSurface(state: PanoState, modifier: Modifier) {
    val renderer = remember(state) { PanoRenderer(state) }
    AndroidView(modifier = modifier, factory = { c ->
        GLSurfaceView(c).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            renderer.view = this
        }
    })
    // A new picture, or a new way of looking: draw again.
    LaunchedEffect(state) {
        snapshotFlow { listOf(state.heading, state.pitch, state.fov, state.image) }.collect {
            renderer.update()
        }
    }
}

/**
 * Street View drawn by the app itself: the panorama's tiles stitched into one picture, then each
 * pixel on screen looks up the right spot of it (no sphere needed). A low-detail image comes
 * first, then a sharp one.
 */
class PanoRenderer(private val state: PanoState) : GLSurfaceView.Renderer {
    private val pano = state.pano
    var view: GLSurfaceView? = null
    @Volatile private var heading = state.heading
    @Volatile private var pitch = 0.0
    @Volatile private var fov = 80.0
    @Volatile private var pending: Bitmap? = null
    private var shown: ImageBitmap? = null
    private var program = 0
    private var texture = 0
    private var hasImage = false
    private var aspect = 1f
    private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }

    /** Copies the latest view and picture for the drawing thread, and asks for a frame. */
    fun update() {
        heading = state.heading; pitch = state.pitch; fov = state.fov
        state.image?.let { if (it !== shown) { shown = it; pending = it.asAndroidBitmap() } }
        view?.requestRender()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        program = link(VERTEX, FRAGMENT)
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); texture = t[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        hasImage = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspect = width.toFloat() / height.coerceAtLeast(1)
    }

    override fun onDrawFrame(gl: GL10?) {
        pending?.let { b ->
            pending = null
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
            hasImage = true
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (!hasImage) return
        GLES20.glUseProgram(program)
        val pos = GLES20.glGetAttribLocation(program, "pos")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad)
        // Half the field of view across the shorter side, like a camera's.
        val t = Math.tan(Math.toRadians(fov / 2)).toFloat()
        val tx = if (aspect >= 1) t * aspect else t
        val ty = if (aspect >= 1) t else t / aspect
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "span"), tx, ty)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "yaw"), Math.toRadians(heading - pano.heading).toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "pitch"), Math.toRadians(pitch).toFloat())
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "pano"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun link(v: String, f: String): Int {
        fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }
        return GLES20.glCreateProgram().also { p ->
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, v))
            GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, f))
            GLES20.glLinkProgram(p)
        }
    }

    companion object {
        private const val VERTEX = """
attribute vec2 pos;
varying vec2 ndc;
void main() { ndc = pos; gl_Position = vec4(pos, 0.0, 1.0); }
"""
        // The ray through each pixel, turned by where you look, gives a compass heading and a height:
        // the panorama's own heading sits in the middle of the picture.
        private const val FRAGMENT = """
precision highp float;
varying vec2 ndc;
uniform sampler2D pano;
uniform vec2 span;
uniform float yaw;
uniform float pitch;
const float PI = 3.14159265;
void main() {
    vec3 d = normalize(vec3(ndc.x * span.x, ndc.y * span.y, 1.0));
    float cp = cos(pitch), sp = sin(pitch);
    d = vec3(d.x, d.y * cp + d.z * sp, -d.y * sp + d.z * cp);
    float lon = atan(d.x, d.z) + yaw;
    float lat = asin(clamp(d.y, -1.0, 1.0));
    vec2 uv = vec2(fract(lon / (2.0 * PI) + 0.5), 0.5 - lat / PI);
    gl_FragColor = texture2D(pano, uv);
}
"""
    }
}
