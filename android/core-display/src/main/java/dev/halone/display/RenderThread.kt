package dev.halone.display

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLSurfaceView
import android.view.Surface

/**
 * Drives a [GLSurfaceView.Renderer] with one GL context for its whole life,
 * whatever window it draws into.
 *
 * `GLSurfaceView` ties the context to its view, so a glasses display that went
 * away took every texture with it: each window's content lives in one, and
 * none could be bound again to a new context. Here the context outlives the
 * windows. [setSurface] swaps the one being drawn to; between windows the
 * thread waits with the context current on a 1×1 pbuffer, and everything in
 * it stays valid.
 *
 * [GLSurfaceView.Renderer.onSurfaceCreated] runs once, for the context;
 * [GLSurfaceView.Renderer.onSurfaceChanged] runs for every new window.
 */
class RenderThread(
    private val renderer: GLSurfaceView.Renderer,
    private val log: (String) -> Unit,
) {
    private val lock = Object()
    private var thread: Thread? = null
    private var running = false

    private var wanted: Surface? = null
    private var wantedWidth = 0
    private var wantedHeight = 0
    private var surfaceChanged = false

    /** The surface the thread currently draws to; [setSurface] waits for it to let go. */
    private var holding: Surface? = null

    private lateinit var eglDisplay: EGLDisplay
    private lateinit var eglConfig: EGLConfig
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var idleSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    fun start() = synchronized(lock) {
        if (running) return
        running = true
        thread = Thread(::run, "halone-render").also { it.start() }
    }

    /**
     * Draws into [surface] from the next frame, or stops drawing while it is
     * null. Returns once the thread has let go of the previous surface, so a
     * caller in `surfaceDestroyed` may let it be destroyed.
     */
    fun setSurface(surface: Surface?, width: Int = 0, height: Int = 0) {
        synchronized(lock) {
            wanted = surface
            wantedWidth = width
            wantedHeight = height
            surfaceChanged = true
            lock.notifyAll()
            awaitRelease { holding != null && holding !== surface }
        }
    }

    /**
     * Stops drawing into [surface] if it is the one drawn to, and returns once
     * it is let go. A window being replaced may report its surface destroyed
     * after the next one has arrived, so this leaves any other surface alone.
     */
    fun releaseSurface(surface: Surface) {
        synchronized(lock) {
            if (wanted === surface) {
                wanted = null
                surfaceChanged = true
                lock.notifyAll()
            }
            awaitRelease { holding === surface }
        }
    }

    private inline fun awaitRelease(stillHeld: () -> Boolean) {
        val deadline = System.currentTimeMillis() + RELEASE_TIMEOUT_MS
        while (running && stillHeld()) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            lock.wait(left)
        }
    }

    fun stop() {
        val ending = synchronized(lock) {
            running = false
            lock.notifyAll()
            thread.also { thread = null }
        }
        ending?.join(STOP_TIMEOUT_MS)
    }

    private fun run() {
        if (!createContext()) {
            synchronized(lock) { running = false; lock.notifyAll() }
            return
        }
        renderer.onSurfaceCreated(null, null)
        try {
            while (true) {
                val change = synchronized(lock) {
                    while (running && !surfaceChanged && holding == null) lock.wait()
                    if (!running) return
                    if (surfaceChanged) {
                        surfaceChanged = false
                        Triple(wanted, wantedWidth, wantedHeight)
                    } else {
                        null
                    }
                }
                if (change != null) attach(change.first, change.second, change.third)
                if (windowSurface == EGL14.EGL_NO_SURFACE) continue
                renderer.onDrawFrame(null)
                if (!EGL14.eglSwapBuffers(eglDisplay, windowSurface)) {
                    log("  render: swap failed (0x%x); waiting for a new surface".format(EGL14.eglGetError()))
                    attach(null, 0, 0)
                }
            }
        } finally {
            attach(null, 0, 0)
            destroyContext()
        }
    }

    private fun attach(surface: Surface?, width: Int, height: Int) {
        if (windowSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, idleSurface, idleSurface, eglContext)
            EGL14.eglDestroySurface(eglDisplay, windowSurface)
            windowSurface = EGL14.EGL_NO_SURFACE
        }
        synchronized(lock) {
            holding = null
            lock.notifyAll()
        }
        if (surface == null || !surface.isValid) return
        val created = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (created == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(eglDisplay, created, created, eglContext)) {
            log("  render: no window surface (0x%x)".format(EGL14.eglGetError()))
            if (created != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, created)
            EGL14.eglMakeCurrent(eglDisplay, idleSurface, idleSurface, eglContext)
            return
        }
        windowSurface = created
        synchronized(lock) { holding = surface }
        renderer.onSurfaceChanged(null, width, height)
    }

    private fun createContext(): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return fail("no EGL display")
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 16,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            return fail("no EGL config")
        }
        eglConfig = configs[0]!!
        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) return fail("no GL context")
        idleSurface = EGL14.eglCreatePbufferSurface(
            eglDisplay, eglConfig, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        if (idleSurface == EGL14.EGL_NO_SURFACE) return fail("no pbuffer")
        if (!EGL14.eglMakeCurrent(eglDisplay, idleSurface, idleSurface, eglContext)) return fail("context not current")
        return true
    }

    private fun destroyContext() {
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (idleSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, idleSurface)
        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
        idleSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        EGL14.eglReleaseThread()
    }

    private fun fail(what: String): Boolean {
        log("  render: $what (0x%x)".format(EGL14.eglGetError()))
        return false
    }

    private companion object {
        const val RELEASE_TIMEOUT_MS = 2_000L
        const val STOP_TIMEOUT_MS = 2_000L
    }
}
