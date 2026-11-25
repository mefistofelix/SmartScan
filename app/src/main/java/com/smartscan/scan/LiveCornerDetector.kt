package com.smartscan.scan

import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import java.util.concurrent.Executors

/**
 * Live corners for the camera preview, smooth at the camera frame rate although the neural model is slower.
 *
 * - The neural model runs on its own thread, on the newest frame, whenever it is idle.
 * - Every frame, the last known corners are moved with sparse optical flow (Lucas-Kanade), which is cheap and
 *   follows the hand movement.
 * - When a model result arrives it is tracked from the frame it was computed on to the current frame, then it
 *   replaces the tracked corners, so drift never builds up.
 *
 * Without the model (still loading or failed) it falls back to synchronous OpenCV detection.
 * [process] is called on the analyzer thread only; [release] when the scanner closes.
 */
class LiveCornerDetector {
    // Background priority: the model must never steal the CPU from the preview and the UI.
    private val modelExec = Executors.newSingleThreadExecutor { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "corner-model")
    }
    private var lastLaunch = 0L
    @Volatile private var busy = false
    private val lock = Any()
    /** Finished model result (normalized corners or null = no document) and the small gray of its frame. */
    private var pending: Pair<FloatArray?, Mat>? = null
    private var prev: Mat? = null
    private var corners: FloatArray? = null
    @Volatile private var released = false

    fun process(bgr: Mat, gray: Mat): FloatArray? {
        if (!NeuralCorners.ready) {
            reset()
            return DocumentDetector.detectNormalized(bgr, gray)
        }
        val small = Mat()
        val f = TRACK_WIDTH / gray.cols()
        Imgproc.resize(gray, small, Size(), f, f, Imgproc.INTER_AREA)

        val result = synchronized(lock) { pending.also { pending = null } }
        corners = if (result != null) {
            val (c, frameGray) = result
            val tracked = c?.let { track(frameGray, small, it) }
            frameGray.release()
            tracked
        } else {
            corners?.let { c -> prev?.let { p -> track(p, small, c) } }
        }

        val now = android.os.SystemClock.elapsedRealtime()
        if (!busy && !released && now - lastLaunch >= MIN_MODEL_INTERVAL_MS) {
            busy = true
            lastLaunch = now
            val b = bgr.clone()
            val g = small.clone()
            modelExec.execute {
                val r = try { DocumentDetector.detectNormalized(b, b) } catch (t: Throwable) { null } finally { b.release() }
                synchronized(lock) {
                    pending?.second?.release()
                    if (released) g.release() else pending = r to g
                }
                busy = false
            }
        }
        prev?.release()
        prev = small
        return corners
    }

    /** Moves normalized corners from [from] to [to] (same size) with pyramidal Lucas-Kanade; null if lost. */
    private fun track(from: Mat, to: Mat, c: FloatArray): FloatArray? {
        if (from.size() != to.size()) return null
        val w = to.cols().toDouble()
        val h = to.rows().toDouble()
        val inside = BooleanArray(4) { i -> c[i * 2] in 0f..1f && c[i * 2 + 1] in 0f..1f }
        val src = MatOfPoint2f(*Array(4) { i ->
            Point((c[i * 2] * w).coerceIn(0.0, w - 1), (c[i * 2 + 1] * h).coerceIn(0.0, h - 1))
        })
        val dst = MatOfPoint2f()
        val status = MatOfByte()
        val err = MatOfFloat()
        try {
            Video.calcOpticalFlowPyrLK(
                from, to, src, dst, status, err, Size(21.0, 21.0), 3,
                TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 20, 0.03),
            )
            val st = status.toArray()
            val s = src.toArray()
            val d = dst.toArray()
            if (st.size != 4 || d.size != 4) return null
            // Mean motion of the corners that were tracked, applied to the lost ones (out of view or occluded).
            var mx = 0.0; var my = 0.0; var n = 0
            for (i in 0 until 4) if (st[i].toInt() != 0 && inside[i]) { mx += d[i].x - s[i].x; my += d[i].y - s[i].y; n++ }
            if (n < 2) return null
            mx /= n; my /= n
            val out = FloatArray(8)
            for (i in 0 until 4) {
                val ok = st[i].toInt() != 0 && inside[i]
                out[i * 2] = if (ok) (d[i].x / w).toFloat() else c[i * 2] + (mx / w).toFloat()
                out[i * 2 + 1] = if (ok) (d[i].y / h).toFloat() else c[i * 2 + 1] + (my / h).toFloat()
            }
            return out
        } finally {
            src.release(); dst.release(); status.release(); err.release()
        }
    }

    /** Forgets the current corners (e.g. after a capture or a mode change). */
    fun reset() {
        corners = null
        prev?.release()
        prev = null
    }

    fun release() {
        released = true
        reset()
        synchronized(lock) { pending?.second?.release(); pending = null }
        modelExec.shutdown()
    }

    private companion object {
        /** Width of the gray frame used for optical flow. */
        const val TRACK_WIDTH = 320.0
        /** At most ~4 model runs per second; optical flow covers the frames in between. */
        const val MIN_MODEL_INTERVAL_MS = 250L
    }
}
