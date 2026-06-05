/*
 * Spektrafilm for Android — GPU LUT preview (experimental). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * An OpenGL ES 3.0 preview surface that applies the current film look to a linear
 * proxy via a baked 3D LUT, the way Lightroom keeps its loupe instant: the CPU
 * engine bakes a 33^3 `.cube` (SpektraEngine.bakeCubeLut) only when look params
 * change, and the GPU trilinearly samples it every frame — so pan/zoom and slider
 * nudges are immediate instead of one full CPU re-render per settle.
 *
 * SCOPE / FIDELITY: a 3D LUT captures only the POINTWISE tone/colour transform.
 * Grain, halation, diffusion glare, DIR-coupler diffusion and scanner unsharp are
 * spatial/stochastic and are forced OFF in the bake (see bakeCubeLut docs), so this
 * path is a fast *look* proxy; the full CPU render (and every export) still applies
 * them exactly. This is why the feature is opt-in and default-OFF.
 *
 * STATUS: compiles against GLES 3.0 (minSdk 24 guarantees it). NOT yet verified on a
 * real GPU in this environment — must be device-tested before it is enabled by
 * default. Wired behind AppSettings.gpuPreview; the editor falls back to the CPU
 * bitmap path whenever this is off or a LUT/proxy is unavailable.
 */
package com.spectrafilm.app

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.spectrafilm.engine.LinearImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Parsed 3D LUT: [size]^3 RGB triples in blue-fastest order (matching .cube and the
 * engine's bakeCubeLut output), as a flat float array length size^3 * 3.
 */
class CubeLut(val size: Int, val rgb: FloatArray) {
    init {
        require(size in 2..256) { "implausible LUT size $size" }
        require(rgb.size == size * size * size * 3) { "LUT data ${rgb.size} != ${size}^3*3" }
    }

    companion object {
        /**
         * Parse Adobe/Resolve `.cube` text (as emitted by SpektraEngine.bakeCubeLut).
         * Tolerates comments (`#`), the `LUT_3D_SIZE N` header, optional `DOMAIN_*`
         * and `TITLE` lines, and N^3 whitespace-separated RGB rows. Returns null on
         * any malformed input so the caller can fall back to the CPU path.
         */
        fun parse(cube: String): CubeLut? = runCatching {
            var size = -1
            val vals = ArrayList<Float>(35937 * 3)
            for (raw in cube.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                when {
                    line.startsWith("LUT_3D_SIZE") ->
                        size = line.substringAfter("LUT_3D_SIZE").trim().toInt()
                    line.startsWith("TITLE") || line.startsWith("DOMAIN_") ||
                        line.startsWith("LUT_1D_SIZE") -> { /* metadata: ignore */ }
                    else -> {
                        val p = line.split(Regex("\\s+"))
                        if (p.size >= 3) {
                            vals.add(p[0].toFloat()); vals.add(p[1].toFloat()); vals.add(p[2].toFloat())
                        }
                    }
                }
            }
            if (size < 2 || vals.size != size * size * size * 3) return null
            CubeLut(size, vals.toFloatArray())
        }.getOrNull()
    }
}

/**
 * Compose host for the GPU LUT preview. Re-uploads the proxy/LUT when [proxy] or
 * [lut] identity changes. The caller is responsible for only showing this when both
 * are non-null and the experimental setting is on; otherwise it shows the CPU bitmap.
 *
 * [onUnavailable] is invoked (once, on the main thread) if the GL program fails to
 * build — i.e. the device/driver can't run this path — so the caller can fall back to
 * the CPU bitmap instead of showing a black surface. This is what makes the feature
 * safe to enable by default: a GL failure degrades gracefully to the proven CPU path.
 */
@Composable
fun GpuLutPreview(
    proxy: LinearImage,
    lut: CubeLut,
    modifier: Modifier = Modifier,
    onUnavailable: () -> Unit = {},
) {
    val cb = rememberUpdatedState(onUnavailable)
    val renderer = remember { LutRenderer { cb.value() } }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                setEGLContextClientVersion(3)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            }
        },
        update = { view ->
            renderer.submit(proxy, lut)
            view.requestRender()
        },
    )
}

/**
 * GLES 3.0 renderer: full-screen quad samples the linear proxy texture, looks the
 * colour up in the 3D LUT (trilinear), writes display RGB. Texture uploads are
 * deferred to the GL thread via [submit] + pending flags.
 */
private class LutRenderer(private val onUnavailable: () -> Unit) : GLSurfaceView.Renderer {
    @Volatile private var pendingProxy: LinearImage? = null
    @Volatile private var pendingLut: CubeLut? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var reportedFail = false

    private var program = 0
    private var proxyTex = 0
    private var lutTex = 0
    private var proxyW = 0
    private var proxyH = 0
    private var viewW = 0
    private var viewH = 0
    private var haveProxy = false
    private var haveLut = false

    fun submit(proxy: LinearImage, lut: CubeLut) {
        pendingProxy = proxy
        pendingLut = lut
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram(VERT, FRAG)
        if (program == 0) {
            // Shader compile/link failed on this device/driver — tell the caller so it can
            // fall back to the CPU bitmap rather than leave a black surface up.
            if (!reportedFail) { reportedFail = true; mainHandler.post(onUnavailable) }
            return
        }
        val tex = IntArray(2)
        GLES30.glGenTextures(2, tex, 0)
        proxyTex = tex[0]
        lutTex = tex[1]
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        pendingProxy?.let { uploadProxy(it); pendingProxy = null }
        pendingLut?.let { uploadLut(it); pendingLut = null }

        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (!haveProxy || !haveLut || program == 0) return

        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, proxyTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uProxy"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uLut"), 1)
        // Letterbox: fit the proxy's aspect into the surface (CLAMP bars stay black) so the
        // image is never stretched — matching the CPU ContentScale.Fit preview.
        var sx = 1f
        var sy = 1f
        if (proxyW > 0 && proxyH > 0 && viewW > 0 && viewH > 0) {
            val imgA = proxyW.toFloat() / proxyH
            val viewA = viewW.toFloat() / viewH
            if (viewA > imgA) sx = imgA / viewA else sy = viewA / imgA
        }
        GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "uScale"), sx, sy)
        // Full-screen quad (triangle strip) from gl_VertexID — no VBO needed.
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun uploadProxy(img: LinearImage) {
        proxyW = img.width
        proxyH = img.height
        // Interleaved RGB float32 -> RGB16F texture (filterable in GLES3). The driver
        // converts GL_FLOAT source to the half-float internal format on upload.
        val fb = img.data.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, proxyTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGB16F, proxyW, proxyH, 0,
            GLES30.GL_RGB, GLES30.GL_FLOAT, fb,
        )
        haveProxy = true
    }

    private fun uploadLut(lut: CubeLut) {
        // bakeCubeLut emits blue-fastest (B varies fastest, then G, then R). GL's
        // glTexImage3D expects the first axis (width=R here) varying fastest, so we
        // map LUT axes to (width=B, height=G, depth=R) and sample tex(b,g,r) below —
        // keeping the engine's ordering without a CPU re-shuffle.
        val n = lut.size
        val fb: FloatBuffer = ByteBuffer.allocateDirect(lut.rgb.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(lut.rgb); fb.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F, n, n, n, 0,
            GLES30.GL_RGB, GLES30.GL_FLOAT, fb,
        )
        haveLut = true
    }

    private fun buildProgram(vsrc: String, fsrc: String): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vsrc)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fsrc)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, vs)
        GLES30.glAttachShader(p, fs)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) { GLES30.glDeleteProgram(p); return 0 }
        GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        return s
    }

    companion object {
        // Full-screen quad from gl_VertexID (triangle strip, 4 verts); flip V so texture
        // row 0 is on top, and apply the letterbox scale so aspect is preserved.
        private const val VERT = """#version 300 es
            uniform vec2 uScale;
            out vec2 vUv;
            void main() {
                float x = float(gl_VertexID & 1);
                float y = float((gl_VertexID >> 1) & 1);
                vUv = vec2(x, 1.0 - y);
                gl_Position = vec4((vec2(x, y) * 2.0 - 1.0) * uScale, 0.0, 1.0);
            }
        """
        private const val FRAG = """#version 300 es
            precision highp float;
            precision highp sampler3D;
            in vec2 vUv;
            uniform sampler2D uProxy;
            uniform sampler3D uLut;
            out vec4 fragColor;
            void main() {
                vec3 lin = texture(uProxy, vUv).rgb;
                vec3 c = clamp(lin, 0.0, 1.0);
                // LUT axes are (B,G,R) fastest->slowest (see uploadLut), so index (b,g,r).
                vec3 outc = texture(uLut, vec3(c.b, c.g, c.r)).rgb;
                fragColor = vec4(outc, 1.0);
            }
        """
    }
}
