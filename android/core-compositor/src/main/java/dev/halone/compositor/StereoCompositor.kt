package dev.halone.compositor

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import dev.halone.compositor.gl.DistortionPass
import dev.halone.compositor.gl.EyeCopier
import dev.halone.compositor.gl.EyeRegion
import dev.halone.compositor.gl.RecordingStream
import dev.halone.compositor.gl.FrameTimestamps
import dev.halone.compositor.gl.ArcQuad
import dev.halone.compositor.gl.BeamQuad
import dev.halone.compositor.gl.BitmapQuad
import dev.halone.compositor.gl.FrameQuad
import dev.halone.compositor.gl.ImmersivePass
import dev.halone.compositor.gl.SolidQuad
import dev.halone.compositor.gl.TexturedQuad3D
import dev.halone.glasses.api.DeviceCalibration
import dev.halone.glasses.api.Eye
import dev.halone.glasses.api.Frustum
import dev.halone.glasses.api.Optics
import dev.halone.glasses.fusion.DisplayClock
import dev.halone.glasses.fusion.HeadPose
import dev.halone.glasses.fusion.RefreshPhase
import dev.halone.glasses.fusion.HeadTracker
import dev.halone.glasses.math.Quaternion
import dev.halone.glasses.math.Vec3
import java.util.concurrent.locks.LockSupport
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws a [Scene] once per eye into a side-by-side frame.
 *
 * The glasses split a 3840x1080 frame into one 1920x1080 half per eye, so
 * "stereo" here is two viewports over one framebuffer with each eye placed
 * where the factory calibration says it is.
 *
 * The projection is **not** a symmetric perspective. The unit's own intrinsics
 * put the principal point about 20 px above centre, which is nearly a degree of
 * vertical asymmetry, and `perspectiveM` cannot express that at any field of
 * view. [projectionMode] keeps the symmetric forms available because the two
 * disagree on this hardware and the disagreement is not yet understood — see
 * `docs/protocol/xreal-hid.md`.
 *
 * The compositor owns the camera, the projection and the frame; it owns nothing
 * about any individual panel. Pose, heading, follow state and texture all live
 * on the [Panel], which is what lets there be more than one.
 */
class StereoCompositor(
    private val tracker: HeadTracker,
    optics: Optics,
    val scene: Scene,
    private val log: (String) -> Unit,
) : GLSurfaceView.Renderer {

    /**
     * Fallback eye separation, used only when there is no calibration; with one
     * the eye positions come from the unit's own measurements instead.
     */
    var ipdMetres = 0.063f
        set(value) {
            field = value
            projectionDirty = true
        }

    /**
     * Per-unit factory calibration. Null keeps the legacy symmetric projection
     * and the [ipdMetres] guess.
     */
    var calibration: DeviceCalibration? = null
        set(value) {
            field = value
            projectionDirty = true
        }

    /**
     * Which projection to build. Exists because the calibrated field of view
     * and the one measured by nulling a world-locked plane disagree by 3.5°,
     * and only a wearer can settle it.
     */
    enum class ProjectionMode {
        /** The unit's own asymmetric frustum. */
        CALIBRATED,

        /** The same field of view, re-centred. Isolates the asymmetry alone. */
        SYMMETRISED,

        /** [verticalFovDegrees], symmetric — what we shipped before. */
        LEGACY,
    }

    @Volatile
    var projectionMode: ProjectionMode = ProjectionMode.CALIBRATED
        set(value) {
            field = value
            projectionDirty = true
        }

    /** Scales the calibrated frustum; starts at the device's default and can be swept while worn. */
    @Volatile
    var fovScale: Float = optics.calibratedFovScale
        set(value) {
            field = value
            projectionDirty = true
        }

    /** The frustum actually in use per eye, for diagnostics. */
    var activeFrustum: Frustum? = null
        private set

    /**
     * Whether to correct the lens through the unit's own distortion grid.
     *
     * Costs a render-to-texture pass per eye and buys up to 0.44° at the
     * extreme corners, about 0.07° across most of the frame. Kept switchable
     * because that is small enough that a wearer should get to judge it, and
     * because a warp that is subtly wrong is worse than none.
     */
    @Volatile
    var distortionCorrection: Boolean = true
        set(value) {
            field = value
            projectionDirty = true
        }

    /** Extra field of view rendered so the warp has corners to sample. */
    var overfill: Float = 0f
        private set

    /**
     * Warps the finished frame to where the head is when each row is actually
     * lit, instead of shearing the scene as it is drawn.
     *
     * Both correct the same artefact — the display paints top to bottom over a
     * whole frame, so world-locked content leans during head motion — but the
     * shear is a screen-space approximation applied per panel, while this is
     * the rotation itself applied to everything that was drawn, chrome and
     * cursor included, and it resamples the tracker after the scene is
     * submitted rather than before. The two are mutually exclusive: with this
     * on, the shear is left at zero or the frame is corrected twice.
     *
     * Requires the offscreen pass, which is also what the lens correction uses.
     */
    @Volatile
    var reprojection: Boolean = true
        set(value) {
            field = value
            projectionDirty = true
        }

    /**
     * The head rate the reprojection keeps margin for, in degrees per second.
     *
     * The warp reaches outside the rendered frame by whatever the head turned
     * through, so above this the leading edge runs out of texture and repeats
     * its last column. Paid for in pixels: the offscreen target grows with it.
     */
    @Volatile
    var reprojectionRateDegreesPerSecond: Float = 45f
        set(value) {
            field = value
            projectionDirty = true
        }

    /**
     * Hardware display timing, when the glasses report it.
     *
     * Without this the warp aims at a constant: the prediction horizon the
     * pipeline was tuned against, which is right on average and wrong by up to
     * a whole refresh either side, depending on where in the refresh cycle the
     * frame happened to be submitted. That error is a sawtooth, not a bias, so
     * no amount of tuning removes it. With this the phase is measured and only
     * the fixed part is left to calibrate — see [scanoutOffsetMs].
     */
    @Volatile
    var displayClock: DisplayClock? = null

    /**
     * How many refreshes ahead of now the frame being drawn will be scanned
     * out. An integer, because the answer is a refresh rather than a duration.
     *
     * **[measured]** Four, by blind sweep while worn: 3 / 4 / 5 refreshes
     * (59.6 / 76.2 / 92.8 ms) with the middle clearly the least wrong. The
     * previous 5 was not measured — it was chosen to put the mean horizon on a
     * perceptual bracket taken back when the scanout correction was an
     * approximate shear, and an artefact that hides latency also hides
     * over-prediction.
     */
    @Volatile
    var refreshesAhead: Int = 4

    /**
     * What is left over after counting refreshes: the part of the path from a
     * refresh boundary to light in the eye that vsync says nothing about — GPU
     * work already queued, the DP link, the panel's own response.
     *
     * Still a tuned constant, but a small one, and no longer carrying the phase
     * error that dominated it. The default lands the mean horizon on the 102 ms
     * this pipeline was perceptually tuned to, so switching hardware timing on
     * changes the phase and nothing else.
     */
    @Volatile
    var scanoutOffsetMs: Float = 5f

    /**
     * Where in the refresh cycle to sample the pose and draw, as a fraction of
     * a period by the glasses' clock; null draws as soon as the last swap
     * returns. Drawing later shortens the horizon by the wait, but only if the
     * frame still makes the same refresh — [presentTiming] is what says so.
     */
    @Volatile
    var drawPhase: Float? = null

    /**
     * Holds each frame until SurfaceFlinger has latched the one before it.
     * Without it the loop is paced by buffer back-pressure, which keeps the
     * queue full: measured, ~2.8 refreshes of every frame's latency are spent
     * waiting in it. **[measured]** Worn blind against back-pressure it felt
     * the same, with no hitches once settled; toggling it mid-session drains
     * or refills the queue and hitches once.
     */
    @Volatile
    var paceOnLatch = true

    /**
     * [refreshesAhead] for a latch-paced frame. **[measured]** Pacing takes
     * exactly two refreshes out of pose-to-present (54.9 -> 21.2 ms), and at 2
     * the aim error matches back-pressure at 4 to within 0.05 ms, so the
     * wearer-tuned relationship to the photon is kept.
     */
    @Volatile
    var pacedRefreshesAhead: Int = 2

    private val pacing: Boolean get() = paceOnLatch && presentTimingAvailable

    /** Frames that gave up waiting for the previous latch. */
    @Volatile
    var latchWaitTimeouts = 0L
        private set

    private var lastFrameId: Long? = null

    /** Written on the GL thread, read by whoever asks. */
    val presentTiming = PresentTiming()

    @Volatile
    var presentTimingAvailable = false
        private set

    /** The horizon the last frame actually aimed at, in ms. */
    @Volatile
    var lastHorizonMs = 0f
        private set

    /**
     * The horizon's spread since [resetHorizonStats], in ms.
     *
     * This is the measurement that decides whether aiming at a refresh beats
     * aiming at a constant. Our own draw is vsync-throttled, so the phase
     * should be steady; a horizon that ranges over a whole refresh means the
     * frame is landing on a boundary and flapping between two answers, which
     * would be worse than the constant it replaced.
     *
     * Min and max alone cannot tell that apart from a single stalled frame, so
     * [horizonOutliers] counts the frames that actually left the band. It is
     * the honest statistic of the two: one hitch in a thousand frames moves the
     * range by a whole refresh and means nothing.
     */
    @Volatile
    var horizonMinMs = Float.MAX_VALUE
        private set

    @Volatile
    var horizonMaxMs = -Float.MAX_VALUE
        private set

    /** Running mean of the horizon, for [horizonOutliers] to measure against. */
    @Volatile
    var horizonMeanMs = 0f
        private set

    /** Frames whose horizon sat more than a quarter of a refresh off the mean. */
    @Volatile
    var horizonOutliers = 0L
        private set

    @Volatile
    var horizonSamples = 0L
        private set

    /** How often the frame has genuinely drifted onto a later refresh. */
    val refreshCrossings: Long get() = refreshPhase.crossings

    /**
     * Clears the statistics but not the phase lock: re-locking costs a second
     * of settling, and a caller measuring one condition after another wants
     * fresh numbers, not a fresh transient.
     */
    fun resetHorizonStats() {
        horizonMinMs = Float.MAX_VALUE
        horizonMaxMs = -Float.MAX_VALUE
        horizonMeanMs = 0f
        horizonOutliers = 0
        horizonSamples = 0
    }

    /**
     * How old the newest IMU sample was when the last warp was computed, in ms.
     *
     * Visible only because the display and the IMU share a clock: it is the
     * delivery lag of the sensor stream, which the tuned horizon used to
     * swallow along with everything else.
     */
    @Volatile
    var lastSampleAgeMs = 0f
        private set

    /** Where the frame sits in the refresh cycle, 0 at a boundary. */
    @Volatile
    var lastRefreshPhase = 0f
        private set

    /** True when the last frame's horizon came from hardware timing. */
    @Volatile
    var horizonFromHardware = false
        private set

    /** How far the last frame's warp moved the image, in degrees. */
    @Volatile
    var lastReprojectionDegrees = 0f
        private set

    /**
     * Scales the scanout shear correction. 1.0 assumes the panel scans a full
     * frame, top row first; 0 disables it.
     *
     * Negative values invert it, which is how the panel's actual scan direction
     * gets determined — it is not something the display reports, and guessing
     * wrong doubles the artefact instead of cancelling it.
     *
     * Also scales the span between the reprojection's two poses, so a wearer can
     * sweep the same question either way.
     */
    @Volatile
    var scanoutCompensation = optics.scanoutCompensation

    /**
     * The display's refresh rate, for when the glasses do not report their
     * timing ([displayClock] is null). It sets the frame time the prediction
     * horizon counts and the span the scanout correction spreads across.
     *
     * Assumed 60 Hz before this existed. At 120 Hz (RayNeo GT glasses in 2D)
     * that doubled the scanout span, so every head movement was warped across
     * twice the time the panel actually takes to light — a smear while
     * looking around — and added a frame of over-prediction on top.
     */
    @Volatile
    var refreshHz: Float = 60f
        set(value) {
            field = if (value.isFinite() && value > 1f) value else 60f
            projectionDirty = true
        }

    private val frameTimeMs: Float get() = 1000f / refreshHz

    private val scanoutSeconds: Float get() = 1f / refreshHz

    /**
     * Rendered vertical field of view per eye.
     *
     * A spec sheet figure and the projection an eye actually needs are not the
     * same number, and the consequence of getting it wrong is subtle rather than
     * obvious. A world-locked flat plane seen off-axis must foreshorten by a
     * particular amount; if the projection disagrees with the optics, near and
     * far edges are displaced differently and the plane appears to *rotate* as
     * the gaze crosses it, reversing direction either side of centre.
     *
     * That makes "the panel stops rotating when I look off to the side" a far
     * more sensitive test of this value than measuring the field of view
     * directly, so it stays tunable while wearing the glasses.
     */
    @Volatile
    var verticalFovDegrees: Float = optics.verticalFovDegrees
        set(value) {
            field = value
            projectionDirty = true
        }

    /**
     * The vertical field the frame is actually rendered with. Differs from
     * [verticalFovDegrees], the symmetric figure panels are sized against,
     * whenever the unit's calibration is in use.
     */
    val renderedVerticalFovDegrees: Float
        get() = frustumFor(Eye.LEFT, 16f / 9f).verticalDegrees

    /**
     * Draws a frame around each panel.
     *
     * Deliberately drawn by the compositor rather than by page content: which
     * panel has focus and whether it is pinned are shell state, and a wearer
     * with two panels showing the same page has no other way to tell them apart
     * or to know which one an action will land on. Compositor chrome also
     * survives a hung or blank engine, which page-drawn chrome does not.
     */
    var showChrome = true

    /**
     * Off by default: panels should look nailed to the world, not follow you.
     *
     * Also what glasses that world-lock the image themselves need (RayNeo's
     * on-board "pinned" mode): the scene is drawn fixed to the face and the
     * glasses move the whole image. Everything that would turn the scene with
     * the head — the view, the late warp, the scanout shear, following — then
     * stands still, or the head's motion is compensated twice. The gaze still
     * follows the head, since that is where the wearer is looking within the
     * image the glasses have moved.
     */
    @Volatile
    var lockToHead = false

    /**
     * Where the pointer is aimed, in canonical world coordinates. Null means fall
     * back to head gaze.
     *
     * Every input source — head gaze, phone-as-pointer, later a mouse — reduces
     * to a direction here, so the hit test and cursor are written once.
     */
    @Volatile
    var pointerDirection: Vec3? = null

    @Volatile
    var lastHit: SceneHit? = null
        private set

    /** Held in front of the wearer over everything else while set. */
    @Volatile
    var notice: Notice? = null

    /** How the pointer is drawn, by what moves it. */
    @Volatile
    var cursorStyle = CursorStyle.RETICLE

    /** The arrow's pictures, by shape; without them the arrow is drawn as the ring. */
    @Volatile
    var cursorImages: Map<CursorShape, CursorImage> = emptyMap()

    /**
     * The direction the last frame aimed with, whatever the source. Grabbing a
     * panel needs the same aim the cursor was drawn with, not a fresh sample
     * from a pointer that has moved since.
     */
    @Volatile
    var lastAim: Vec3 = Vec3(0f, 0f, -1f)
        private set

    /** Head yaw as the last frame rendered it, in radians. */
    @Volatile
    var headYawRadians = 0f
        private set

    /** Head pitch as the last frame rendered it, in radians; positive is up. */
    @Volatile
    var headPitchRadians = 0f
        private set

    @Volatile
    var framesDrawn = 0L
        private set

    // A long frame freezes the scene relative to the head: the rendered pose
    // stops updating while the head keeps moving, so world-locked content
    // momentarily behaves as if it were head-locked and then snaps back. That is
    // a very different artefact from lag or overshoot, and it needs measuring
    // rather than inferring from how it looks.
    @Volatile
    var longFrames = 0
        private set

    @Volatile
    var worstFrameMs = 0f
        private set

    @Volatile
    private var projectionDirty = true

    private var lastFrameNanos = 0L
    private var framesSeen = 0L

    /** Takes the left eye as drawn before the lens warp, once, at the end of the next frame; [onCaptured] runs on the render thread. */
    fun captureLeftEye(onCaptured: (EyePicture) -> Unit) {
        captureRequest = onCaptured
    }

    @Volatile
    private var captureRequest: ((EyePicture) -> Unit)? = null

    /** Where to copy the left eye every frame while a recording runs, or null. */
    @Volatile
    var recording: RecordingTarget? = null

    private val eyeCopier = EyeCopier()
    private var recordingStream: RecordingStream? = null
    private var lastRecordedNs = 0L

    /** A video behind every panel, or null; replacing or clearing it releases the old one's texture. */
    @Volatile
    var immersive: ImmersiveLayer? = null

    private var attachedImmersive: ImmersiveLayer? = null
    private val immersivePass = ImmersivePass()
    private val immersiveTransform = FloatArray(16)

    private val quad = TexturedQuad3D()
    private val solid = SolidQuad()
    private val frame = FrameQuad()
    private val bitmapQuad = BitmapQuad()
    private val beam = BeamQuad()
    private val cursorTextures = CursorTextures()
    private val beamOrigin = FloatArray(4)
    private val arc = ArcQuad()
    private val titleBars = TitleBarPainter()
    private val notices = NoticePainter()
    private val noticeLocal = FloatArray(16)
    private val noticeModel = FloatArray(16)
    private var glReady = false

    private var frameWidth = 0
    private var frameHeight = 0

    /**
     * True when the surface is not two eyes side by side but one image the
     * glasses show to both — a 2D mode, such as RayNeo glasses offer without
     * a host command for 3D. One centred eye is drawn across the whole frame:
     * no depth, but no squashed pair of half-images in each eye either, and
     * these modes often run faster than the side-by-side one.
     */
    @Volatile
    private var mono = false

    /** Whether the frame is two eyes side by side, known once the surface is. */
    val stereo: Boolean? get() = if (frameWidth == 0) null else !mono

    /** Told on the render thread whenever the surface's shape sets [stereo]. */
    @Volatile
    var onStereoChanged: ((Boolean) -> Unit)? = null

    /** Width of what one rendered eye covers: half the frame, or all of it in [mono]. */
    private val eyeWidth: Int get() = if (mono) frameWidth else frameWidth / 2

    private val eyeCount: Int get() = if (mono) 1 else 2
    private var clipPerRadianX = 0f
    private var clipPerRadianY = 0f

    /** One projection per eye: the two frustums are not the same. */
    private val projections = Array(2) { FloatArray(16) }

    /** Per-eye offset and rotation relative to the head frame. */
    private val eyePose = Array(2) { FloatArray(16) }
    private val eyeRotation = FloatArray(16)

    /** One offscreen target and mesh per eye; the two lenses differ. */
    private val distortion = Array(2) { DistortionPass() }
    private var distortionReady = false
    private var lensCorrected = false

    /** The frustum each eye was rendered with, overfill included. */
    private val renderFrustum = arrayOfNulls<Frustum>(2)

    /** Each eye's own rotation relative to the head, as the warp needs it. */
    private val eyeRotationQuaternion = Array(2) { Quaternion.IDENTITY }

    private val refreshPhase = RefreshPhase()

    private val rotationStart = FloatArray(9)
    private val rotationEnd = FloatArray(9)
    private val tangentScale = FloatArray(2)
    private val tangentOffset = FloatArray(2)

    init {
        // So the first frame has something valid even if it lands before the
        // surface is sized and `rebuildProjection` has had a chance to run.
        rebuildEyePoses()
    }
    private val headMatrix = FloatArray(16)
    private val headForward = FloatArray(3)
    private val eyePosition = FloatArray(3)
    private val worldFromEye = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val cursorModel = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val mvp = FloatArray(16)
    private val texTransform = FloatArray(16)
    private val forwardMatrix = FloatArray(16)

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Texture names whose panels have gone; only the render thread may free them. */
    private val abandonedTextures = java.util.concurrent.ConcurrentLinkedQueue<Int>()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        quad.initialise()
        immersivePass.initialise()
        frame.initialise()
        bitmapQuad.initialise()
        beam.initialise()
        arc.initialise()
        solid.initialise()
        // The offscreen targets themselves are sized later, once the surface
        // dimensions are known.
        distortion.forEach { it.initialise() }
        glReady = true
        log("  compositor: gl ready")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        presentTimingAvailable = FrameTimestamps.enable()
        lastFrameId = null
        frameWidth = width
        frameHeight = height
        mono = width < 2 * height
        projectionDirty = true
        rebuildProjection()
        onStereoChanged?.invoke(!mono)
        log(
            if (mono) "  compositor: frame ${width}x$height, mono (one image for both eyes)"
            else "  compositor: frame ${width}x$height, per-eye ${width / 2}x$height"
        )
    }

    override fun onDrawFrame(gl: GL10?) {
        if (projectionDirty) rebuildProjection()
        waitForPreviousLatch()
        // The clear is what dequeues a buffer, and that is where the loop really
        // blocks. Waiting before it only moves the block, so wait after it.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        waitForGlassesRefresh()
        waitForDrawPhase()
        val drawNs = System.nanoTime()

        // Once per frame, before anything reads it: the phase lock must advance
        // exactly as often as frames do, and both the render pose and the warp
        // are aimed at the answer.
        val photon = photonTarget()
        val pose = renderPose(photon)
        val aim = pointerDirection ?: forwardOf(pose.orientation)
        // Before following runs, so a held panel is already where the wearer's
        // hand has put it when the rest of the arrangement is resolved against
        // it.
        lastAim = aim
        scene.updateGrab(aim)
        scene.updateResize(aim)
        advanceFrameClock(if (lockToHead) Quaternion.IDENTITY else pose.orientation)

        quaternionToMatrix(if (lockToHead) Quaternion.IDENTITY else pose.orientation, headMatrix)
        lastHit = scene.hitTest(aim)

        // Rotation that elapses across one scanout, in the head's own frame.
        // Yaw about canonical +Y moves content horizontally; pitch about +X
        // moves it vertically. Zero when the warp is doing this properly at the
        // end of the frame, or the same rotation is applied twice.
        val warping = reprojection && distortionReady && !lockToHead
        val scanSeconds = if (warping || lockToHead) 0f else scanoutSeconds
        val scanYaw = -pose.angularVelocity.y * scanSeconds * scanoutCompensation
        val scanPitch = pose.angularVelocity.x * scanSeconds * scanoutCompensation

        val halfWidth = eyeWidth
        val panels = scene.panels

        val overlays = listOfNotNull(scene.dock, scene.launcher, scene.popup)
        for (panel in panels) {
            attachIfNeeded(panel)
        }
        overlays.forEach { attachIfNeeded(it) }
        val background = attachImmersive()
        freeAbandonedTextures()
        titleBars.forget(panels.mapTo(HashSet()) { it.id }).forEach { abandonedTextures += it }
        val stacked = scene.drawOrder()

        for (eye in 0 until eyeCount) {
            if (distortionReady) {
                // Everything below now draws into an offscreen image that is
                // slightly wider than the eye, and is warped onto the display
                // once both eyes are done.
                distortion[eye].begin()
            } else {
                GLES20.glViewport(eye * halfWidth, 0, halfWidth, frameHeight)
            }

            // worldFromEye = headRotation * eyePose; the view matrix is its
            // inverse. Doing it this way keeps the offset in head space, so the
            // eyes stay level with the head when it rolls — and carries each
            // eye's own small rotation, which the factory measures separately
            // and which differ from each other by nearly a degree of pitch.
            Matrix.multiplyMM(worldFromEye, 0, headMatrix, 0, eyePose[eye], 0)
            Matrix.invertM(view, 0, worldFromEye, 0)
            Matrix.multiplyMM(viewProjection, 0, projections[eye], 0, view, 0)

            val focused = scene.focused
            val grabbed = scene.grabbed
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            background?.let { drawImmersive(it, eye, viewProjection) }
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            val windows = scene.windowsOpacity
            val chrome = showChrome && windows > 0f
            for (panel in stacked) {
                if (chrome) drawChrome(panel, viewProjection, panel === focused, panel === grabbed, windows)
                if (!drawContent(panel, eye, viewProjection, scanYaw, scanPitch, windows)) continue
                if (chrome) drawTitleBar(panel, viewProjection, panel === focused || panel === grabbed, windows)
                if (chrome && windows >= 1f) drawResizeHandle(panel, viewProjection)
            }
            scene.resizing?.let { drawResizePreview(it, viewProjection) }
            scene.dock?.let { drawContent(it, eye, viewProjection, scanYaw, scanPitch, scene.dockOpacity) }
            scene.launcher?.let { drawContent(it, eye, viewProjection, scanYaw, scanPitch, scene.launcherOpacity) }
            scene.popup?.let { drawContent(it, eye, viewProjection, scanYaw, scanPitch, if (scene.popupShown) 1f else 0f) }
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)

            drawCursor(viewProjection)
            notice?.let { drawNotice(it, viewProjection) }
        }

        if (distortionReady) {
            // After both eyes, not between them: the warp wants the newest pose
            // there is, and rendering the second eye is more of the delay it
            // exists to absorb.
            updateReprojection(pose, photon, warping)
            for (eye in 0 until eyeCount) {
                distortion[eye].drawTo(eye * halfWidth, 0, halfWidth, frameHeight)
            }
        }

        val leftEye = leftEyeRegion(halfWidth)
        captureRequest?.let { request ->
            captureRequest = null
            request(EyePicture(leftEye.width, leftEye.height, eyeCopier.read(leftEye)))
        }
        recordFrame(leftEye)

        if (presentTimingAvailable) recordPresentTiming(drawNs, photon)
        framesDrawn++
    }

    /** The left eye without the overfill around it, in its offscreen target or, unwarped, the frame's left half. */
    private fun leftEyeRegion(halfWidth: Int): EyeRegion {
        val target = distortion.firstOrNull()?.takeIf { it.ready } ?: return EyeRegion(0, 0, 0, halfWidth, frameHeight)
        return EyeRegion(
            target.framebufferId,
            (target.textureWidth - halfWidth) / 2,
            (target.textureHeight - frameHeight) / 2,
            halfWidth,
            frameHeight,
        )
    }

    /** Hands the left eye to the recording, at most at its frame rate; the drawing is the stream's thread's. */
    private fun recordFrame(leftEye: EyeRegion) {
        val target = recording
        val current = recordingStream
        if (current?.target !== target) {
            current?.let {
                it.stop()
                it.target.release()
            }
            recordingStream = target?.let { RecordingStream(it, log) }?.takeIf { it.start() }
            if (target != null && recordingStream == null) {
                target.release()
                recording = null
            }
        }
        val stream = recordingStream ?: return
        if (stream.failed) {
            recording = null
            return
        }
        val now = System.nanoTime()
        if (now - lastRecordedNs < 1_000_000_000L / stream.target.maxFramesPerSecond) return
        stream.offer(leftEye, now)
        lastRecordedNs = now
    }

    private var lastWaitedNs = 0L
    private var lastLatchSeenLateNs = 0L

    private fun waitForPreviousLatch() {
        lastWaitedNs = 0
        lastLatchSeenLateNs = 0
        if (!pacing) return
        val id = lastFrameId ?: return
        val startNs = System.nanoTime()
        val deadline = startNs + LATCH_WAIT_LIMIT_NS
        while (true) {
            val now = System.nanoTime()
            if (now >= deadline) {
                latchWaitTimeouts++
                break
            }
            val times = FrameTimestamps.query(id) ?: break
            if (times.latchNs != FrameTimes.PENDING) {
                if (times.latchNs > 0) lastLatchSeenLateNs = now - times.latchNs
                break
            }
            LockSupport.parkNanos(LATCH_POLL_NS)
        }
        lastWaitedNs = System.nanoTime() - startNs
    }

    /**
     * One frame per glasses refresh, each started on a refresh boundary by the
     * glasses' own clock. For a rate the phone's screen cannot keep in step
     * with: Android hands frames over on the phone screen's beat, so drawing
     * on that beat gives the glasses frames unevenly, while drawing on theirs
     * gives each refresh at most one.
     */
    @Volatile
    var paceToGlasses = false

    private var pacedRefreshNs = 0L

    private fun waitForGlassesRefresh() {
        if (!paceToGlasses) return
        val clock = displayClock?.takeIf { it.ready && it.beaconAgeNs < MAX_BEACON_AGE_NS } ?: return
        val now = clock.deviceNow()
        val next = clock.refreshAfter(maxOf(now, pacedRefreshNs + (clock.periodNs / 2).toLong()))
        pacedRefreshNs = next
        val waitNs = next - now
        if (waitNs > 0 && waitNs < 2 * clock.periodNs) LockSupport.parkNanos(waitNs)
    }

    private fun waitForDrawPhase() {
        val target = drawPhase ?: return
        val clock = displayClock?.takeIf { it.ready && it.beaconAgeNs < MAX_BEACON_AGE_NS } ?: return
        val waitNs = ((target - clock.phaseAt(clock.deviceNow())) * clock.periodNs).toLong()
        if (waitNs > 0 && waitNs < clock.periodNs) LockSupport.parkNanos(waitNs)
    }

    private fun recordPresentTiming(drawNs: Long, photonDeviceNs: Long?) {
        val frameId = FrameTimestamps.nextFrameId() ?: return
        lastFrameId = frameId
        val clock = displayClock
        val aimedNs = if (photonDeviceNs != null && clock != null) {
            photonDeviceNs - clock.offsetNs - (scanoutOffsetMs * 1e6).toLong()
        } else {
            null
        }
        presentTiming.submitted(frameId, drawNs, aimedNs, lastWaitedNs, lastLatchSeenLateNs)
        presentTiming.resolve(FrameTimestamps::query)
    }

    /**
     * Runs the engine handshake for a panel that has joined the scene since the
     * last frame.
     *
     * Phase one has to happen here, because this is the only place with a
     * current GL context; phase two is posted to the UI thread because every
     * engine so far asserts it.
     */
    private fun attachIfNeeded(panel: Panel) {
        if (!glReady) return
        if (panel.textureId == 0) {
            val textureId = quad.newTexture()
            panel.textureId = textureId
            panel.surfaceTexture = panel.engine.createTexture(textureId)
            log("  compositor: panel '${panel.id}' bound to texture $textureId")
            mainHandler.post { panel.engine.attach() }
        }
        val layer = panel.engine.topLayer ?: return
        if (panel.layerTextureId != 0) return
        val textureId = quad.newTexture()
        panel.layerTextureId = textureId
        panel.layerSurfaceTexture = layer.engine.createTexture(textureId)
        log("  compositor: panel '${panel.id}' top layer bound to texture $textureId")
        mainHandler.post { layer.engine.attach() }
    }

    /**
     * Tears a panel down: the engine's producer first, then the consumer, then
     * the texture name. Releasing the consumer while a producer still holds the
     * `Surface` is what makes a buffer queue abandon noisily.
     */
    fun detach(panel: Panel) {
        panel.engine.close()
        val textureId = panel.detachTexture()
        if (textureId != 0) abandonedTextures += textureId
        val layerTextureId = panel.detachLayerTexture()
        if (layerTextureId != 0) abandonedTextures += layerTextureId
    }

    /** The panel handshake for [immersive]: a texture here, the `SurfaceTexture` to the UI thread. */
    private fun attachImmersive(): ImmersiveLayer? {
        val wanted = immersive
        val current = attachedImmersive
        if (current != null && current !== wanted) {
            val id = current.detachTexture()
            if (id != 0) abandonedTextures += id
            attachedImmersive = null
        }
        if (wanted == null || !glReady) return null
        if (wanted.textureId == 0) {
            val textureId = quad.newTexture()
            val texture = android.graphics.SurfaceTexture(textureId)
            wanted.textureId = textureId
            wanted.surfaceTexture = texture
            attachedImmersive = wanted
            log("  compositor: immersive layer bound to texture $textureId")
            mainHandler.post { wanted.deliver(texture) }
        }
        return wanted
    }

    private fun drawImmersive(layer: ImmersiveLayer, eye: Int, viewProjection: FloatArray) {
        val texture = layer.surfaceTexture ?: return
        if (eye == 0) texture.updateTexImage()
        texture.getTransformMatrix(immersiveTransform)
        headForward[0] = -headMatrix[8]
        headForward[1] = -headMatrix[9]
        headForward[2] = -headMatrix[10]
        eyePosition[0] = worldFromEye[12]
        eyePosition[1] = worldFromEye[13]
        eyePosition[2] = worldFromEye[14]
        immersivePass.draw(layer, layer.textureId, immersiveTransform, viewProjection, headForward, eyePosition, eye)
    }

    private fun freeAbandonedTextures() {
        while (true) {
            val id = abandonedTextures.poll() ?: return
            GLES20.glDeleteTextures(1, intArrayOf(id), 0)
            log("  compositor: freed texture $id")
        }
    }

    /**
     * How far out to draw a panel, which is not quite where it is.
     *
     * A held panel is lifted a little toward the wearer. Two panels at the same
     * radius that cross each other intersect as *planes*: each occludes half the
     * other along the seam, which reads as the panels clipping through one
     * another rather than as one passing in front. Depth is unavailable for
     * *resolving* an overlap — the optics form one virtual image distance, so
     * parking a panel nearer sets vergence against accommodation — but this is
     * not a resting place, it is a lift for as long as the panel is in the
     * wearer's hand, and it has to clear the ~8 cm of depth a panel's own width
     * spans at 4 m. At 15 cm the vergence change is under 0.04 degrees, and
     * being drawn whole rather than sliced is what makes it look picked up.
     */
    private fun drawDistanceOf(panel: Panel): Float =
        panel.pose.distanceMetres -
            if (panel === scene.grabbed) GRAB_LIFT_METRES else 0f

    /**
     * The cursor is sized for [SIZE_REFERENCE_METRES] and scaled with
     * distance, so like the panel it keeps its angle. Worn at 1 m, fixed
     * metres made it and the old border four times too big.
     */
    private fun sizeScaleOf(panel: Panel) = panel.pose.distanceMetres / SIZE_REFERENCE_METRES

    /**
     * A thin rounded border just outside the panel, following its corners.
     * Colour says pinned or following, brightness says focused.
     */
    private fun drawChrome(
        panel: Panel,
        viewProjection: FloatArray,
        focused: Boolean,
        grabbed: Boolean,
        opacity: Float,
    ) {
        val pose = panel.pose
        val metresPerPx = pose.widthMetres / panel.widthPx
        val marginPx = FRAME_GAP_PX + FRAME_THICKNESS_PX + 1f
        Matrix.setIdentityM(cursorModel, 0)
        Matrix.rotateM(cursorModel, 0, Math.toDegrees(pose.yawRadians.toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.rotateM(cursorModel, 0, Math.toDegrees(pose.pitchRadians.toDouble()).toFloat(), 1f, 0f, 0f)
        Matrix.translateM(cursorModel, 0, 0f, 0f, -drawDistanceOf(panel))
        Matrix.scaleM(
            cursorModel, 0,
            pose.widthMetres + 2f * marginPx * metresPerPx,
            pose.heightMetres + 2f * marginPx * metresPerPx,
            1f,
        )
        Matrix.multiplyMM(mvp, 0, viewProjection, 0, cursorModel, 0)

        val alpha = (if (focused || grabbed) 1f else UNFOCUSED_CHROME) * opacity
        val (r, g, b) = when {
            // Held panels get their own colour rather than a brighter one: while
            // dragging, the wearer needs to know which panel is coming with them
            // at a glance, and pinned-or-following is the state they are about
            // to change anyway.
            grabbed -> Triple(1f, 1f, 1f)
            panel.follow.enabled -> Triple(1f, 0.72f, 0.2f)
            else -> Triple(0.25f, 0.8f, 1f)
        }
        frame.draw(
            mvp, panel.widthPx.toFloat(), panel.heightPx.toFloat(), marginPx,
            panel.cornerRadiusPx, FRAME_GAP_PX, FRAME_THICKNESS_PX, r, g, b, alpha,
        )
    }

    /**
     * Draws a panel's page; false if it has no texture yet. An invisible panel
     * still takes its frame, or its producer would stall waiting for buffers.
     */
    private fun drawContent(
        panel: Panel,
        eye: Int,
        viewProjection: FloatArray,
        scanYaw: Float,
        scanPitch: Float,
        alpha: Float,
    ): Boolean {
        val texture = panel.surfaceTexture ?: return false
        // Once per eye is one updateTexImage too many, but the second
        // call is a no-op unless the producer queued a frame mid-frame,
        // and the transform matrix has to be current for both.
        // While a window eases into a new size its last picture is held and
        // stretched, so the page never shows half laid out.
        val frozen = scene.isAnimatingResize(panel)
        if (eye == 0 && !frozen) texture.updateTexImage()
        val layer = panel.engine.topLayer
        val layerTexture = panel.layerSurfaceTexture
        if (eye == 0 && !frozen) layerTexture?.updateTexImage()
        if (alpha <= 0f) return true

        val height = panel.heightPx.toFloat()
        val band = layer?.visibleHeightPx?.toFloat() ?: 0f
        texture.getTransformMatrix(texTransform)
        val stereo = panel.stereoRegion.takeIf { layer == null }
        drawPart(panel, viewProjection, panel.textureId, band, height - band, 1f, scanYaw, scanPitch, alpha, stereo, eye)
        if (band > 0f && layer != null && layerTexture != null) {
            layerTexture.getTransformMatrix(texTransform)
            val crop = band / layer.engine.heightPx
            drawPart(panel, viewProjection, panel.layerTextureId, 0f, band, crop, scanYaw, scanPitch, alpha)
        }
        return true
    }

    private val partRect = FloatArray(4)

    /** One full-width band of a window, [top] and [partHeight] in its pixels from its top edge. */
    private fun drawPart(
        panel: Panel,
        viewProjection: FloatArray,
        textureId: Int,
        top: Float,
        partHeight: Float,
        cropV: Float,
        scanYaw: Float,
        scanPitch: Float,
        alpha: Float,
        stereo: StereoRegion? = null,
        eye: Int = 0,
    ) {
        val pose = panel.pose
        val width = panel.widthPx.toFloat()
        val height = panel.heightPx.toFloat()
        val metresPerPx = pose.widthMetres / width
        Matrix.setIdentityM(model, 0)
        Matrix.rotateM(model, 0, Math.toDegrees(pose.yawRadians.toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.rotateM(model, 0, Math.toDegrees(pose.pitchRadians.toDouble()).toFloat(), 1f, 0f, 0f)
        Matrix.translateM(model, 0, 0f, -(top + partHeight / 2f - height / 2f) * metresPerPx, -drawDistanceOf(panel))
        Matrix.scaleM(model, 0, width * metresPerPx, partHeight * metresPerPx, 1f)
        Matrix.multiplyMM(mvp, 0, viewProjection, 0, model, 0)
        partRect[0] = 0f
        partRect[1] = top
        partRect[2] = width
        partRect[3] = partHeight
        quad.draw(
            mvp, textureId, texTransform,
            scanYaw = scanYaw, scanPitch = scanPitch,
            clipPerRadianX = clipPerRadianX, clipPerRadianY = clipPerRadianY,
            widthPx = width, heightPx = height,
            cornerRadiusPx = panel.cornerRadiusPx,
            alpha = alpha,
            rectPx = partRect,
            cropV = cropV,
            stereo = stereo,
            eye = eye,
        )
    }

    /** The handle at the corner the pointer is near, brighter when the pointer is on it. */
    private fun drawResizeHandle(panel: Panel, viewProjection: FloatArray) {
        if (scene.resizing != null || scene.grabbed != null || scene.isAnimatingResize(panel)) return
        val hit = lastHit
        val onHandle = hit?.takeIf { it.panel === panel }?.part?.corner
        val corner = onHandle ?: scene.pointOnPanel(panel, lastAim)
            ?.let { ResizeHandles.cornerNear(panel.widthPx, panel.heightPx, it.x, it.y) }
            ?: return
        if (onHandle == null && hit != null && hit.panel !== panel && hit.part == HitPart.CONTENT) return

        val pose = panel.pose
        val width = panel.widthPx.toFloat()
        val height = panel.heightPx.toFloat()
        val metresPerPx = pose.widthMetres / width
        val outside = ResizeHandles.GAP_PX + ResizeHandles.THICKNESS_PX + 2f
        val inside = panel.cornerRadiusPx + ResizeHandles.EXTEND_PX
        val toCentre = (inside - outside) / 2f
        val point = corner.pointPx(width, height)
        val centreX = point.x - corner.sx * toCentre
        val centreY = point.y + corner.sy * toCentre
        Matrix.setIdentityM(cursorModel, 0)
        Matrix.rotateM(cursorModel, 0, Math.toDegrees(pose.yawRadians.toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.rotateM(cursorModel, 0, Math.toDegrees(pose.pitchRadians.toDouble()).toFloat(), 1f, 0f, 0f)
        Matrix.translateM(
            cursorModel, 0,
            (centreX - width / 2f) * metresPerPx,
            -(centreY - height / 2f) * metresPerPx,
            -drawDistanceOf(panel),
        )
        val size = (inside + outside) * metresPerPx
        Matrix.scaleM(cursorModel, 0, size * -corner.sx, size * corner.sy, 1f)
        Matrix.multiplyMM(mvp, 0, viewProjection, 0, cursorModel, 0)
        arc.draw(
            mvp, outside, inside, panel.cornerRadiusPx, ResizeHandles.GAP_PX, ResizeHandles.THICKNESS_PX,
            alpha = if (onHandle != null) 1f else HANDLE_NEAR_ALPHA,
        )
    }

    /** The size a resize will make, outlined from the corner that stays put. */
    private fun drawResizePreview(drag: ResizeDrag<Panel>, viewProjection: FloatArray) {
        val panel = drag.panel
        val pose = panel.pose
        val width = panel.widthPx.toFloat()
        val height = panel.heightPx.toFloat()
        val metresPerPx = pose.widthMetres / width
        val fixed = drag.corner.opposite
        val fixedPoint = fixed.pointPx(width, height)
        val centreX = fixedPoint.x - fixed.sx * drag.widthPx / 2f
        val centreY = fixedPoint.y + fixed.sy * drag.heightPx / 2f
        val margin = PREVIEW_THICKNESS_PX + 1f
        Matrix.setIdentityM(cursorModel, 0)
        Matrix.rotateM(cursorModel, 0, Math.toDegrees(pose.yawRadians.toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.rotateM(cursorModel, 0, Math.toDegrees(pose.pitchRadians.toDouble()).toFloat(), 1f, 0f, 0f)
        Matrix.translateM(
            cursorModel, 0,
            (centreX - width / 2f) * metresPerPx,
            -(centreY - height / 2f) * metresPerPx,
            -drawDistanceOf(panel) * (1f - CURSOR_STANDOFF_FRACTION),
        )
        Matrix.scaleM(cursorModel, 0, (drag.widthPx + 2f * margin) * metresPerPx, (drag.heightPx + 2f * margin) * metresPerPx, 1f)
        Matrix.multiplyMM(mvp, 0, viewProjection, 0, cursorModel, 0)
        frame.draw(
            mvp, drag.widthPx.toFloat(), drag.heightPx.toFloat(), margin,
            panel.cornerRadiusPx, 0f, PREVIEW_THICKNESS_PX, 1f, 1f, 1f, 0.9f,
        )
    }

    /** The pill under the panel, in the panel's own plane. */
    private fun drawTitleBar(panel: Panel, viewProjection: FloatArray, active: Boolean, opacity: Float) {
        val hit = lastHit
        val texture = titleBars.texture(panel, hovered = hit?.part?.takeIf { hit.panel === panel }, shape = scene.shapeButtons)
        val pose = panel.pose
        val metresPerPx = pose.widthMetres / panel.widthPx
        val bar = TitleBar.bar(panel.widthPx, panel.heightPx)
        Matrix.setIdentityM(cursorModel, 0)
        Matrix.rotateM(cursorModel, 0, Math.toDegrees(pose.yawRadians.toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.rotateM(cursorModel, 0, Math.toDegrees(pose.pitchRadians.toDouble()).toFloat(), 1f, 0f, 0f)
        Matrix.translateM(
            cursorModel, 0,
            (bar.left + bar.width / 2f - panel.widthPx / 2f) * metresPerPx,
            -(bar.top + bar.height / 2f - panel.heightPx / 2f) * metresPerPx,
            -drawDistanceOf(panel),
        )
        Matrix.scaleM(cursorModel, 0, bar.width * metresPerPx, bar.height * metresPerPx, 1f)
        Matrix.multiplyMM(mvp, 0, viewProjection, 0, cursorModel, 0)
        bitmapQuad.draw(mvp, texture, (if (active) 1f else INACTIVE_TITLE_BAR) * opacity)
    }

    /**
     * The pointer where it rests: on the nearest panel it hits, or, between
     * panels, at their distance along the pointer, so it reads as being in the
     * same place as they are. Head gaze between panels has nothing to show.
     */
    private fun drawCursor(viewProjection: FloatArray) {
        val hit = lastHit
        val metresPerPx = when {
            hit != null -> placeOnPanel(hit)
            else -> pointerDirection?.let { placeBetweenPanels(it) }
        } ?: return
        val style = cursorStyle

        // The cursor is part of its panel's surface, so depth testing it against
        // that same surface can only ever go wrong. It is drawn on the *nearest*
        // hit panel, which is what keeps a nearer panel from being drawn through:
        // the ray never reaches the panel behind it.
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        if (style == CursorStyle.BEAM) drawBeam(viewProjection)

        val shape = hit?.takeIf { it.part == HitPart.CONTENT }?.panel?.engine?.cursorShape ?: CursorShape.DEFAULT
        val arrow = if (style == CursorStyle.ARROW) cursorTextures.arrow(cursorImages, shape) else null
        if (style == CursorStyle.ARROW && shape == CursorShape.NONE) {
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            return
        }
        val texture = arrow?.first ?: cursorTextures.ring()
        val hotspotX = arrow?.second?.hotspotX ?: 0.5f
        val hotspotY = arrow?.second?.hotspotY ?: 0.5f
        val size = (if (arrow != null) ARROW_PX else RING_PX) * metresPerPx
        Matrix.scaleM(cursorModel, 0, size, size, 1f)
        // The picture's hotspot, not its middle, lands on the point.
        Matrix.translateM(cursorModel, 0, 0.5f - hotspotX, hotspotY - 0.5f, 0f)
        Matrix.multiplyMM(mvp, 0, viewProjection, 0, cursorModel, 0)
        bitmapQuad.draw(mvp, texture)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    /**
     * Puts [cursorModel] at the hit, on the panel's plane, nudged toward the
     * viewer so it is not z-fighting with the page it sits on; answers the
     * panel's metres per pixel, which the cursor is sized in.
     */
    private fun placeOnPanel(hit: SceneHit): Float {
        val panel = hit.panel
        val pose = panel.pose
        val u = hit.hit.x / panel.engine.widthPx - 0.5f
        val v = 0.5f - hit.hit.y / panel.engine.heightPx
        Matrix.setIdentityM(cursorModel, 0)
        Matrix.rotateM(cursorModel, 0, Math.toDegrees(pose.yawRadians.toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.rotateM(cursorModel, 0, Math.toDegrees(pose.pitchRadians.toDouble()).toFloat(), 1f, 0f, 0f)
        Matrix.translateM(
            cursorModel, 0,
            u * pose.widthMetres,
            v * pose.heightMetres,
            -drawDistanceOf(panel) * (1f - CURSOR_STANDOFF_FRACTION),
        )
        return pose.widthMetres / panel.engine.widthPx
    }

    /** Puts [cursorModel] along [direction] at the panels' distance, facing the viewer; null for no direction. */
    private fun placeBetweenPanels(direction: Vec3): Float? {
        val length = direction.length
        if (length < 1e-6f) return null
        val d = direction * (1f / length)
        val reference = scene.panels.firstOrNull() ?: scene.launcher ?: scene.dock
        val distance = reference?.pose?.distanceMetres ?: SIZE_REFERENCE_METRES
        val yaw = Math.toDegrees(kotlin.math.atan2(-d.x, -d.z).toDouble()).toFloat()
        val pitch = Math.toDegrees(kotlin.math.asin(d.y.coerceIn(-1f, 1f)).toDouble()).toFloat()
        Matrix.setIdentityM(cursorModel, 0)
        Matrix.rotateM(cursorModel, 0, yaw, 0f, 1f, 0f)
        Matrix.rotateM(cursorModel, 0, pitch, 1f, 0f, 0f)
        Matrix.translateM(cursorModel, 0, 0f, 0f, -distance)
        return reference?.let { it.pose.widthMetres / it.engine.widthPx * distance / it.pose.distanceMetres }
            ?: (CURSOR_METRES * distance / SIZE_REFERENCE_METRES / RING_PX)
    }

    /**
     * The gyro pointer's beam: from below the view, as if from the hand that
     * holds the phone, to where [cursorModel] puts the cursor, fading as it
     * goes so the ring at its end is what the eye lands on.
     */
    private fun drawBeam(viewProjection: FloatArray) {
        val target = Vec3(cursorModel[12], cursorModel[13], cursorModel[14])
        Matrix.multiplyMV(beamOrigin, 0, headMatrix, 0, BEAM_ORIGIN, 0)
        val origin = Vec3(beamOrigin[0], beamOrigin[1], beamOrigin[2])
        beam.draw(
            viewProjection,
            origin,
            target,
            fromWidth = origin.length * BEAM_WIDTH_PER_METRE,
            toWidth = target.length * BEAM_WIDTH_PER_METRE,
            fromAlpha = BEAM_ALPHA_NEAR,
            toAlpha = BEAM_ALPHA_FAR,
            r = 1f,
            g = 1f,
            b = 1f,
        )
    }

    private fun drawNotice(notice: Notice, viewProjection: FloatArray) {
        val distance = scene.panels.firstOrNull()?.pose?.distanceMetres ?: SIZE_REFERENCE_METRES
        val metresPerPx = distance * NOTICE_HEIGHT_PER_METRE / NoticePainter.HEIGHT_PX
        val barWidth = NoticePainter.WIDTH_PX - 2f * NoticePainter.BAR_INSET_PX
        val filled = barWidth * notice.progress.coerceIn(0f, 1f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        noticeMvp(NoticePainter.WIDTH_PX / 2f, NoticePainter.HEIGHT_PX / 2f, NoticePainter.WIDTH_PX, NoticePainter.HEIGHT_PX, metresPerPx, distance, viewProjection)
        bitmapQuad.draw(mvp, notices.texture(notice.text))
        noticeMvp(NoticePainter.WIDTH_PX / 2f, NoticePainter.BAR_MID_Y_PX, barWidth, NoticePainter.BAR_HEIGHT_PX, metresPerPx, distance, viewProjection)
        solid.draw(mvp, 0.18f, 0.18f, 0.18f, 0.18f)
        if (filled > 0f) {
            noticeMvp(NoticePainter.BAR_INSET_PX + filled / 2f, NoticePainter.BAR_MID_Y_PX, filled, NoticePainter.BAR_HEIGHT_PX, metresPerPx, distance, viewProjection)
            solid.draw(mvp, 0.36f, 0.62f, 1f)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    /** A rectangle of the notice card, in its pixels, held at [distance] in front of the head. */
    private fun noticeMvp(
        centreXPx: Float,
        centreYPx: Float,
        widthPx: Float,
        heightPx: Float,
        metresPerPx: Float,
        distance: Float,
        viewProjection: FloatArray,
    ) {
        Matrix.setIdentityM(noticeLocal, 0)
        Matrix.translateM(
            noticeLocal, 0,
            (centreXPx - NoticePainter.WIDTH_PX / 2f) * metresPerPx,
            -(centreYPx - NoticePainter.HEIGHT_PX / 2f) * metresPerPx,
            -distance,
        )
        Matrix.scaleM(noticeLocal, 0, widthPx * metresPerPx, heightPx * metresPerPx, 1f)
        Matrix.multiplyMM(noticeModel, 0, headMatrix, 0, noticeLocal, 0)
        Matrix.multiplyMM(mvp, 0, viewProjection, 0, noticeModel, 0)
    }

    /** Frame timing, long-frame accounting, and one step of smooth follow. */
    private fun advanceFrameClock(orientation: Quaternion) {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0f else (now - lastFrameNanos) / 1e9f
        lastFrameNanos = now

        framesSeen++
        val frameMs = dt * 1000f
        if (frameMs > LONG_FRAME_MS && framesSeen > SETTLING_FRAMES) {
            longFrames++
            if (frameMs > worstFrameMs) worstFrameMs = frameMs
            log("  LONG FRAME %.1f ms (%d so far, worst %.1f)".format(frameMs, longFrames, worstFrameMs))
        }

        val (yawDegrees, pitchDegrees) = orientation.toEulerDegrees()
        val headYaw = Math.toRadians(yawDegrees.toDouble()).toFloat()
        headYawRadians = headYaw
        headPitchRadians = Math.toRadians(pitchDegrees.toDouble()).toFloat()
        scene.gazePitchRadians = headPitchRadians
        scene.advanceFollow(headYaw, dt)
    }

    private fun rebuildProjection() {
        if (frameWidth == 0 || frameHeight == 0) return
        // Per eye the frame is half as wide, unless one image serves both.
        val eyeAspect = eyeWidth.toFloat() / frameHeight

        rebuildDistortion()

        for (index in 0 until 2) {
            val eye = if (index == 0) Eye.LEFT else Eye.RIGHT
            val frustum = frustumFor(eye, eyeAspect).scaledBy(fovScale).expandedBy(overfill)
            renderFrustum[index] = frustum
            // Frustum bounds are tangents at unit distance; `frustumM` wants
            // them on the near plane.
            Matrix.frustumM(
                projections[index], 0,
                frustum.left * NEAR_PLANE_M, frustum.right * NEAR_PLANE_M,
                frustum.bottom * NEAR_PLANE_M, frustum.top * NEAR_PLANE_M,
                NEAR_PLANE_M, FAR_PLANE_M,
            )
            Reprojection.tangentScale(frustum, tangentScale)
            Reprojection.tangentOffset(frustum, tangentOffset)
            distortion[index].setTangentMapping(tangentScale, tangentOffset)
        }

        // What the eye actually sees, which is the frustum before the overfill
        // margin was added — the margin exists for the warp to sample and is
        // never visible.
        val visible = frustumFor(Eye.LEFT, eyeAspect).scaledBy(fovScale)
        activeFrustum = if (calibration == null) null else visible
        rebuildEyePoses()

        // Clip-space units per radian, from the half angles actually rendered —
        // the overfilled ones, because this scales content inside the offscreen
        // image rather than on the display.
        val rendered = renderFrustum[0]!!
        val halfV = Math.toRadians(rendered.verticalDegrees / 2.0)
        val halfH = Math.toRadians(rendered.horizontalDegrees / 2.0)
        clipPerRadianY = (1.0 / Math.tan(halfV)).toFloat()
        clipPerRadianX = (1.0 / Math.tan(halfH)).toFloat()
        projectionDirty = false

        log(
            "  compositor: %s, vertical %.2f°, horizontal %.2f° (vertical asymmetry %.2f°)".format(
                if (calibration == null) "uncalibrated" else projectionMode.name.lowercase(),
                visible.verticalDegrees,
                visible.horizontalDegrees,
                visible.verticalAsymmetryDegrees,
            )
        )
        log(
            if (distortionReady) {
                "  compositor: %s%s, %.1f%% overfill, %dx%d offscreen".format(
                    if (lensCorrected) "lens correction on" else "lens correction off",
                    if (reprojection) ", reprojection on" else "",
                    100 * overfill, distortion[0].textureWidth, distortion[0].textureHeight,
                )
            } else {
                "  compositor: lens correction off, reprojection off"
            }
        )
        calibration?.let { log("  ${it.describe().replace("\n", "\n  ")}") }
    }

    private fun rebuildDistortion() {
        val left = calibration?.left
        val right = calibration?.right
        val leftGrid = left?.distortion
        val rightGrid = right?.distortion
        val lens = distortionCorrection && leftGrid != null && rightGrid != null

        if (frameWidth == 0 || (!lens && !reprojection)) {
            overfill = 0f
            distortionReady = false
            lensCorrected = false
            return
        }

        // The grid's coordinates are in the calibration's own display
        // resolution, which need not match the surface we were handed.
        val lensNeeded = if (lens) {
            maxOf(
                DistortionMesh.requiredOverfill(leftGrid!!, left!!.widthPx, left.heightPx),
                DistortionMesh.requiredOverfill(rightGrid!!, right!!.widthPx, right.heightPx),
            ) + OVERFILL_MARGIN
        } else {
            0f
        }
        overfill = maxOf(lensNeeded, reprojectionOverfill())

        val textureWidth = (eyeWidth * (1f + 2f * overfill)).toInt()
        val textureHeight = (frameHeight * (1f + 2f * overfill)).toInt()

        for (index in 0 until 2) {
            val eye = if (index == 0) left else right
            val grid = if (index == 0) leftGrid else rightGrid
            distortion[index].setMesh(
                if (lens && eye != null && grid != null) {
                    DistortionMesh.build(grid, eye.widthPx, eye.heightPx, overfill)
                } else {
                    DistortionMesh.identity(overfill)
                }
            )
            runCatching { distortion[index].resize(textureWidth, textureHeight) }
                .onFailure { log("  compositor: offscreen pass unavailable — ${it.message}") }
        }
        distortionReady = distortion.all { it.ready }
        lensCorrected = lens && distortionReady
    }

    /**
     * Margin the warp needs, measured against the frustum it will warp — which
     * is not yet built, so the unexpanded one stands in. The difference is a
     * couple of percent of a couple of percent.
     */
    private fun reprojectionOverfill(): Float {
        if (!reprojection) return 0f
        val eyeAspect = eyeWidth.toFloat() / frameHeight
        val frustum = frustumFor(Eye.LEFT, eyeAspect).scaledBy(fovScale)
        return Reprojection.overfillFor(
            frustum,
            Math.toRadians(reprojectionRateDegreesPerSecond.toDouble()).toFloat(),
            scanoutSeconds,
        )
    }

    /**
     * Points the warp at the pose each row will actually be lit at.
     *
     * The horizon is measured backwards from the photon the renderer was aiming
     * at, not forwards from now: the last row keeps the prediction horizon the
     * whole pipeline was tuned against, the first row sits one scanout earlier,
     * and whatever the tracker learned while the scene was being drawn is
     * subtracted rather than added. Same target, less extrapolation.
     */
    /**
     * The device time at which the first row of the frame being drawn will be
     * lit, or null when the glasses are not saying.
     */
    private fun photonTarget(): Long? {
        val clock = displayClock?.takeIf { it.ready && it.beaconAgeNs < MAX_BEACON_AGE_NS }
            ?: return null
        val deviceNow = clock.deviceNow()
        val phase = refreshPhase.update(clock.phaseAt(deviceNow))
        lastRefreshPhase = phase
        val ahead = if (pacing) pacedRefreshesAhead else refreshesAhead
        val aheadNs = ((ahead - phase) * clock.periodNs).toLong()
        return deviceNow + aheadNs + (scanoutOffsetMs * 1e6).toLong()
    }

    /**
     * The pose to draw the scene at.
     *
     * With a rotation-only warp on 3DoF hardware the reprojection is exact, so
     * this does not decide what the wearer sees — the warp carries whatever is
     * drawn to the display pose. What it decides is how far the warp has to
     * reach outside the frame, so it aims at the *middle* of the scanout and
     * leaves the warp half a span either way. Wrong by a frame and the edges
     * start sampling margin that is not there.
     */
    private fun renderPose(photonDeviceNs: Long?): HeadPose {
        val clock = displayClock
        if (photonDeviceNs == null || clock == null) return tracker.predictedPose(frameTimeMs)
        val latest = tracker.pose
        val midScanoutNs = photonDeviceNs + (clock.periodNs * scanoutCompensation / 2.0).toLong()
        return tracker.poseAfterMs((midScanoutNs - latest.timestampNs) / 1e6f)
    }

    private fun updateReprojection(
        renderPose: HeadPose,
        photonDeviceNs: Long?,
        enabled: Boolean,
    ) {
        if (!enabled) {
            distortion.forEach { it.clearReprojection() }
            lastReprojectionDegrees = 0f
            return
        }

        val latest = tracker.pose
        val clock = displayClock
        val scanSpanMs = scanoutSpanMs(clock.takeIf { photonDeviceNs != null }) * scanoutCompensation

        val firstRowMs: Float
        if (photonDeviceNs != null && clock != null) {
            // Measured, and against the newest sample there is rather than the
            // one the scene was drawn with — the whole point of warping late is
            // that the tracker has learned something since.
            firstRowMs = (photonDeviceNs - latest.timestampNs) / 1e6f
            lastSampleAgeMs = (clock.deviceNow() - latest.timestampNs) / 1e6f
            if (!horizonFromHardware) {
                // The first beacons take seconds to arrive, so every session
                // starts on the tuned constant. Those frames are a different
                // measurement and would otherwise be counted as this one's
                // outliers.
                horizonFromHardware = true
                resetHorizonStats()
            }
        } else {
            // A reconnected stream can restart its clock, which would otherwise
            // buy an arbitrary amount of freshness and warp the frame to nothing.
            val horizonMs = tracker.predictionHorizonMs(frameTimeMs)
            val freshenedMs = ((latest.timestampNs - renderPose.timestampNs) / 1_000_000f)
                .coerceIn(0f, maxOf(horizonMs, 0f))
            firstRowMs = horizonMs - freshenedMs - scanSpanMs
            horizonFromHardware = false
        }
        val lastRowMs = firstRowMs + scanSpanMs
        lastHorizonMs = lastRowMs
        if (lastRowMs < horizonMinMs) horizonMinMs = lastRowMs
        if (lastRowMs > horizonMaxMs) horizonMaxMs = lastRowMs
        horizonSamples++
        if (horizonSamples == 1L) {
            horizonMeanMs = lastRowMs
        } else {
            if (Math.abs(lastRowMs - horizonMeanMs) > scanSpanMs / 4f) horizonOutliers++
            horizonMeanMs += (lastRowMs - horizonMeanMs) * HORIZON_MEAN_ALPHA
        }

        val start = tracker.poseAfterMs(firstRowMs).orientation
        val end = tracker.poseAfterMs(lastRowMs).orientation

        for (index in 0 until 2) {
            val eye = eyeRotationQuaternion[index]
            Reprojection.rotation(renderPose.orientation, start, eye, rotationStart)
            Reprojection.rotation(renderPose.orientation, end, eye, rotationEnd)
            distortion[index].setReprojection(rotationStart, rotationEnd)
        }
        lastReprojectionDegrees = angleBetweenDegrees(renderPose.orientation, end)
    }

    /**
     * One refresh, measured where the display will say so and assumed at 60 Hz
     * where it will not.
     */
    private fun scanoutSpanMs(clock: DisplayClock?): Float =
        if (clock != null) (clock.periodNs / 1e6).toFloat() else scanoutSeconds * 1000f

    private fun angleBetweenDegrees(a: Quaternion, b: Quaternion): Float {
        val cosHalf = Math.abs(a.dot(b)).coerceAtMost(1f)
        return Math.toDegrees(2.0 * Math.acos(cosHalf.toDouble())).toFloat()
    }

    private fun frustumFor(eye: Eye, eyeAspect: Float): Frustum {
        val calibrated = calibration?.eyes?.get(eye)?.frustum
        return when {
            calibrated == null || projectionMode == ProjectionMode.LEGACY -> {
                val halfV = Math.tan(Math.toRadians(verticalFovDegrees / 2.0)).toFloat()
                val halfH = halfV * eyeAspect
                Frustum(-halfH, halfH, -halfV, halfV)
            }
            projectionMode == ProjectionMode.SYMMETRISED -> calibrated.symmetrised()
            else -> calibrated
        }
    }

    /**
     * Where each eye sits and points relative to the head.
     *
     * The calibrated positions are relative to the *IMU*, which is off to one
     * side of both eyes, so they are re-expressed about their own midpoint —
     * with no positional tracking, only the separation between the eyes is
     * meaningful, and an absolute offset would just translate the whole world.
     */
    private fun rebuildEyePoses() {
        val calibration = this.calibration
        val left = calibration?.left
        val right = calibration?.right

        if (left == null || right == null) {
            for (index in 0 until 2) {
                // In mono the one image is seen from between the eyes.
                val sign = if (mono) 0f else if (index == 0) -1f else 1f
                Matrix.setIdentityM(eyePose[index], 0)
                Matrix.translateM(eyePose[index], 0, sign * ipdMetres / 2f, 0f, 0f)
                eyeRotationQuaternion[index] = Quaternion.IDENTITY
            }
            return
        }

        val headFromImu = calibration.headFromImu
        val imuFromHead = headFromImu.conjugate()
        val midpoint = (left.positionFromImu + right.positionFromImu) * 0.5f

        for (index in 0 until 2) {
            val eye = if (index == 0) left else right
            val offset = imuFromHead.rotate(eye.positionFromImu - midpoint)
            eyeRotationQuaternion[index] = eye.rotationFromHead(headFromImu)
            quaternionToMatrix(eyeRotationQuaternion[index], eyeRotation)
            Matrix.setIdentityM(eyePose[index], 0)
            Matrix.translateM(eyePose[index], 0, offset.x, offset.y, offset.z)
            Matrix.multiplyMM(eyePose[index], 0, eyePose[index].copyOf(), 0, eyeRotation, 0)
        }
        // In mono the one image is seen from between the eyes.
        if (mono) for (i in 12..14) eyePose[0][i] = 0f
    }

    /** Forward in the canonical frame is -Z: the negated third column. */
    private fun forwardOf(orientation: Quaternion): Vec3 {
        quaternionToMatrix(orientation, forwardMatrix)
        return Vec3(-forwardMatrix[8], -forwardMatrix[9], -forwardMatrix[10])
    }

    companion object {

        private const val CURSOR_METRES = 0.03f

        /** The arrow's picture, which leaves room around the arrow itself, in panel pixels. */
        private const val ARROW_PX = 40f

        /** The ring's picture, in panel pixels. */
        private const val RING_PX = 26f

        /** Where the beam starts, in head space: below and in front of the eyes, out of view. */
        private val BEAM_ORIGIN = floatArrayOf(0.06f, -0.35f, -0.25f, 1f)

        /** The beam's width over its distance from the eyes: a constant, thin line. */
        private const val BEAM_WIDTH_PER_METRE = 0.0025f
        private const val BEAM_ALPHA_NEAR = 0.8f
        private const val BEAM_ALPHA_FAR = 0.08f

        /** The notice card's height for each metre of distance: about 3.4°, a sixth of the view. */
        private const val NOTICE_HEIGHT_PER_METRE = 0.06f
        private const val SIZE_REFERENCE_METRES = 4f
        /**
         * A little more overfill than the grid strictly asks for. The corners
         * sample right at the edge otherwise, where bilinear filtering reaches
         * half a texel further than the coordinate does.
         */
        private const val OVERFILL_MARGIN = 0.005f

        private const val NEAR_PLANE_M = 0.5f
        private const val FAR_PLANE_M = 200f

        /**
         * Beyond this the beacons have stopped and the refresh grid is a guess,
         * so the tuned horizon is the safer answer. Several beacon intervals,
         * since on this hardware they are 4.3 s apart.
         */
        private const val MAX_BEACON_AGE_NS = 20_000_000_000L

        /** Two refreshes at 60 Hz: past this the latch is not coming and waiting only drops frames. */
        private const val LATCH_WAIT_LIMIT_NS = 33_000_000L
        private const val LATCH_POLL_NS = 250_000L

        /** Slow enough that a hitch shows up as an outlier rather than moving the mean. */
        private const val HORIZON_MEAN_ALPHA = 0.02f

        /**
         * How far a held panel is lifted toward the wearer, in metres. Enough to
         * clear the depth a panel's own width spans, so a carried panel passes
         * in front of its neighbours instead of intersecting them.
         */
        private const val GRAB_LIFT_METRES = 0.15f

        /** Cursor standoff as a fraction of panel distance, so it scales. */
        private const val CURSOR_STANDOFF_FRACTION = 0.004f

        /** The border, in the panel's pixels: the simulator's. */
        private const val FRAME_GAP_PX = 4f
        private const val FRAME_THICKNESS_PX = 3f

        /** A resize handle the pointer is only near, rather than on. */
        private const val HANDLE_NEAR_ALPHA = 0.55f

        /** The outline of the size a resize will make. */
        private const val PREVIEW_THICKNESS_PX = 4f

        /** An unfocused window's title bar, as in the simulator. */
        private const val INACTIVE_TITLE_BAR = 0.55f

        /** An unfocused panel's border is present but quiet. */
        private const val UNFOCUSED_CHROME = 0.3f

        /** Two frames at 60 Hz; beyond this the scene visibly stops tracking. */
        private const val LONG_FRAME_MS = 33f

        /** Startup frames are always long; counting them would be noise. */
        private const val SETTLING_FRAMES = 30

        /** Column-major, as OpenGL wants it. */
        fun quaternionToMatrix(q: Quaternion, out: FloatArray) {
            val (w, x, y, z) = q
            out[0] = 1f - 2f * (y * y + z * z)
            out[1] = 2f * (x * y + w * z)
            out[2] = 2f * (x * z - w * y)
            out[3] = 0f
            out[4] = 2f * (x * y - w * z)
            out[5] = 1f - 2f * (x * x + z * z)
            out[6] = 2f * (y * z + w * x)
            out[7] = 0f
            out[8] = 2f * (x * z + w * y)
            out[9] = 2f * (y * z - w * x)
            out[10] = 1f - 2f * (x * x + y * y)
            out[11] = 0f
            out[12] = 0f
            out[13] = 0f
            out[14] = 0f
            out[15] = 1f
        }
    }
}
