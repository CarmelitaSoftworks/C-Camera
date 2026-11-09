package com.carmelita.ccamera.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Orientación del teléfono integrada desde el giroscopio, con historial para consultarla en el
 * instante exacto de cada fotograma (relojes: elapsedRealtimeNanos, el de los sensores).
 */
class GyroTracker(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val gyro: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val n = 512
    private val ts = LongArray(n)
    private val ax = FloatArray(n); private val ay = FloatArray(n); private val az = FloatArray(n)
    private var count = 0
    private var head = 0
    private var x = 0f; private var y = 0f; private var z = 0f
    private var last = 0L

    val available get() = gyro != null

    fun start(handler: Handler) { gyro?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler) } }
    fun stop() { sm?.unregisterListener(this) }

    @Synchronized
    override fun onSensorChanged(e: SensorEvent) {
        if (last != 0L) {
            val dt = ((e.timestamp - last) / 1e9f).coerceIn(0f, 0.05f)
            x += e.values[0] * dt; y += e.values[1] * dt; z += e.values[2] * dt
        }
        last = e.timestamp
        ts[head] = e.timestamp; ax[head] = x; ay[head] = y; az[head] = z
        head = (head + 1) % n
        if (count < n) count++
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit

    /** Orientación integrada (rad) en [tNs], interpolando entre muestras; null si no hay datos. */
    @Synchronized
    fun at(tNs: Long): Triple<Float, Float, Float>? {
        if (count == 0) return null
        var newer = -1
        var older = -1
        for (k in 0 until count) {
            val i = (head - 1 - k + n) % n
            if (ts[i] >= tNs) newer = i else { older = i; break }
        }
        return when {
            older < 0 -> Triple(ax[newer], ay[newer], az[newer])
            newer < 0 -> Triple(ax[older], ay[older], az[older])   // fotograma más nuevo que el último dato
            else -> {
                val f = ((tNs - ts[older]).toFloat() / (ts[newer] - ts[older]).coerceAtLeast(1)).coerceIn(0f, 1f)
                Triple(ax[older] + (ax[newer] - ax[older]) * f, ay[older] + (ay[newer] - ay[older]) * f, az[older] + (az[newer] - az[older]) * f)
            }
        }
    }
}

/**
 * Estabilizador digital de vídeo de C Camera: la cámara entrega los fotogramas a una textura de
 * OpenGL; cada uno se dibuja recortado (zoom [ZOOM]) y desplazado/girado para anular el temblor
 * que mide el giroscopio, en la vista previa y en el grabador. Se usa cuando el teléfono no tiene
 * estabilización electrónica de vídeo propia (o si el usuario la elige).
 */
class DigitalStabilizer(
    context: Context,
    /** Tamaño de los fotogramas de la cámara (= tamaño del vídeo). */
    private val size: Size,
    private val focalPx: Float,
    private val front: Boolean,
    private val sensorOrientation: Int,
    /** ¿Los timestamps de la cámara usan el reloj de los sensores (REALTIME)? */
    private val realtimeTimestamps: Boolean,
) {
    private val thread = HandlerThread("ccamera-eis").apply { start() }
    private val handler = Handler(thread.looper)
    private val gyro = GyroTracker(context)
    private val path = SmoothPath()

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texId = 0
    private lateinit var input: SurfaceTexture
    /** Superficie que se da a la cámara como salida. */
    lateinit var inputSurface: Surface
        private set

    /**
     * Una salida. [pre] (mat3 por columnas) pasa de la coordenada de esta salida a la de la imagen
     * en vertical: identidad para la vista previa; para el grabador, que quiere los fotogramas en la
     * orientación del sensor (el giro va en los metadatos del vídeo), gira SENSOR_ORIENTATION.
     */
    private class Target(val surface: Surface, val egl: EGLSurface, val w: Int, val h: Int, val record: Boolean, val pre: FloatArray)
    private val targets = ArrayList<Target>()
    private val texMatrix = FloatArray(16)
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); rewind() }
    @Volatile private var released = false
    /** Fotogramas dibujados en la vista previa (para comprobar que arrancó bien). */
    @Volatile var previewFrames = 0
        private set

    val available get() = gyro.available

    /** Tamaño de la imagen en vertical (el búfer del sensor girado SENSOR_ORIENTATION). */
    private val uprightW = if (sensorOrientation % 180 == 0) size.width else size.height
    private val uprightH = if (sensorOrientation % 180 == 0) size.height else size.width

    /** Prepara EGL y la textura de entrada (bloquea hasta que está lista). */
    fun start() {
        runOnGl {
            setupEgl()
            program = buildProgram()
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            texId = t[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            input = SurfaceTexture(texId)
            input.setDefaultBufferSize(size.width, size.height)
            input.setOnFrameAvailableListener({ drawFrame() }, handler)
            inputSurface = Surface(input)
        }
        gyro.start(handler)
    }

    /** Añade una salida (vista previa o grabador). Devuelve false si EGL no pudo usarla. */
    fun addOutput(surface: Surface, w: Int, h: Int, record: Boolean): Boolean {
        var ok = false
        runOnGl {
            // La superficie puede tardar en quedar libre de la sesión anterior (sobre todo viniendo
            // del modo foto, donde la tenía la cámara): hasta ~1,5 s.
            var egl = EGL14.EGL_NO_SURFACE
            for (attempt in 0 until 30) {
                egl = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
                if (egl != EGL14.EGL_NO_SURFACE) break
                Thread.sleep(50)
            }
            if (egl != EGL14.EGL_NO_SURFACE) {
                // Tamaño real de la superficie (la de la vista previa puede venir girada respecto al pedido).
                val qw = IntArray(1); val qh = IntArray(1)
                EGL14.eglQuerySurface(display, egl, EGL14.EGL_WIDTH, qw, 0)
                EGL14.eglQuerySurface(display, egl, EGL14.EGL_HEIGHT, qh, 0)
                val tw = if (qw[0] > 0) qw[0] else w
                val th = if (qh[0] > 0) qh[0] else h
                targets += Target(surface, egl, tw, th, record, if (record) sensorToUpright(sensorOrientation) else IDENTITY)
                ok = true
            }
            else Log.w(TAG, "eglCreateWindowSurface falló (0x${Integer.toHexString(EGL14.eglGetError())})")
        }
        return ok
    }

    private fun drawFrame() {
        if (released) return
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
        input.updateTexImage()
        input.getTransformMatrix(texMatrix)
        val frameNs = input.timestamp
        val sensorNs = if (realtimeTimestamps) frameNs else frameNs + (SystemClock.elapsedRealtimeNanos() - System.nanoTime())
        val o = gyro.at(sensorNs)
        val stab = if (o != null) {
            val (jx, jy, jz) = path.update(sensorNs, o.first, o.second, o.third)
            // La cámara ya entrega la textura girada a vertical (su matriz de transformación lo
            // incluye), así que el temblor se calcula y se corrige en la imagen vertical.
            val m = StabilizationMath.contentMotion(jx, jy, jz, focalPx, front, sensorOrientation = 0)
            StabilizationMath.sampleMatrix(m, uprightW, uprightH, ZOOM)
        } else {
            StabilizationMath.sampleMatrix(StabilizationMath.Motion(0f, 0f, 0f), uprightW, uprightH, ZOOM)
        }
        for (t in targets) {
            if (!EGL14.eglMakeCurrent(display, t.egl, t.egl, context)) continue
            GLES20.glViewport(0, 0, t.w, t.h)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, texMatrix, 0)
            GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(program, "uStab"), 1, false, stab, 0)
            GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(program, "uPre"), 1, false, t.pre, 0)
            val pos = GLES20.glGetAttribLocation(program, "aPos")
            GLES20.glEnableVertexAttribArray(pos)
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            if (t.record) EGLExt.eglPresentationTimeANDROID(display, t.egl, frameNs)
            if (EGL14.eglSwapBuffers(display, t.egl) && !t.record) previewFrames++
        }
    }

    private fun setupEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        check(EGL14.eglInitialize(display, v, 0, v, 1)) { "eglInitialize" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) { "eglChooseConfig" }
        config = configs[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
    }

    private fun buildProgram(): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: ${GLES20.glGetShaderInfoLog(s)}" }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, VERTEX))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
        GLES20.glLinkProgram(p)
        return p
    }

    private fun runOnGl(block: () -> Unit) {
        val latch = CountDownLatch(1)
        var error: Throwable? = null
        handler.post { try { block() } catch (t: Throwable) { error = t } finally { latch.countDown() } }
        latch.await(5, TimeUnit.SECONDS)
        error?.let { throw it }
    }

    fun release() {
        if (released) return
        released = true
        gyro.stop()
        handler.post {
            for (t in targets) EGL14.eglDestroySurface(display, t.egl)
            targets.clear()
            runCatching { inputSurface.release() }
            runCatching { input.release() }
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            thread.quitSafely()
        }
    }

    companion object {
        private const val TAG = "CCamera"
        /** Recorte para tener margen de compensación (~9 % por lado), como la estabilización del teléfono. */
        const val ZOOM = 1.2f
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

        /**
         * mat3 (por columnas) que lleva una coordenada (u, v; v hacia arriba) de un fotograma en
         * orientación del sensor a la imagen vertical (= el búfer girado [o] grados en sentido horario).
         */
        fun sensorToUpright(o: Int): FloatArray = when ((o % 360 + 360) % 360) {
            90 -> floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 1f, 1f)     // (u, v) -> (v, 1 - u)
            180 -> floatArrayOf(-1f, 0f, 0f, 0f, -1f, 0f, 1f, 1f, 1f)   // (1 - u, 1 - v)
            270 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 1f, 0f, 1f)    // (1 - v, u)
            else -> IDENTITY.copyOf()
        }

        private const val VERTEX = """
            attribute vec2 aPos;
            uniform mat4 uTex;
            uniform mat3 uStab;
            uniform mat3 uPre;
            varying vec2 vTex;
            void main() {
                vec3 up = uPre * vec3(aPos * 0.5 + 0.5, 1.0);
                vec3 s = uStab * vec3(up.xy, 1.0);
                vTex = (uTex * vec4(s.xy, 0.0, 1.0)).xy;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """
        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex0;
            varying vec2 vTex;
            void main() { gl_FragColor = texture2D(uTex0, vTex); }
        """
    }
}
