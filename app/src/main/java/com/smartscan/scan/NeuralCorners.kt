package com.smartscan.scan

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Neural document corner detector: DocAligner LC100 heatmap model (DocsaidLab, Apache-2.0,
 * assets/docaligner_lcnet100.onnx, license in assets/licenses).
 *
 * Input "img": 1x3x256x256 float, BGR, values 0..1, whole frame resized (no padding).
 * Output "heatmap": 1xCxHxW after a sigmoid; the first 4 channels are the 4 corners.
 * It sees the page as a whole, so it works on busy backgrounds, with objects on the page and with worn
 * edges, where edge-based OpenCV detection fails.
 */
object NeuralCorners {
    private const val TAG = "NeuralCorners"
    private const val SIDE = 256
    /** A corner whose heatmap peak is below this is not trusted (DocAligner uses 0.3). */
    private const val MIN_PEAK = 0.3f

    @Volatile private var session: OrtSession? = null
    private var env: OrtEnvironment? = null
    private var inputName = "img"
    private var outputName = "heatmap"
    private var calls = 0
    private var totalMs = 0L
    /** Heatmap peak of each corner in the last [detect] call (diagnostics). */
    val lastPeaks = FloatArray(4)
    /** Input padding (fraction of the frame on each side) and how it is filled. Tuned with [CornerDebug] on real
     * photos: no padding missed pages filling the frame (peaks ~0), 12% replicated border gave 0.8-0.95. */
    var padding = 0.12f
    var borderType = org.opencv.core.Core.BORDER_REPLICATE
    var borderFill = 128.0

    val ready: Boolean get() = session != null

    /** Loads the model once; safe to call from a background thread. Failures leave OpenCV as the detector. */
    @Synchronized
    fun init(context: Context) {
        if (session != null) return
        try {
            val bytes = context.assets.open("docaligner_lcnet100.onnx").use { it.readBytes() }
            val e = OrtEnvironment.getEnvironment()
            fun options(xnnpack: Boolean, threads: Int) = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                if (xnnpack) {
                    // XNNPACK: ARM-optimized kernels; ORT recommends one ORT thread when XNNPACK has its own pool.
                    setIntraOpNumThreads(1)
                    addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                } else {
                    setIntraOpNumThreads(threads)
                }
            }
            // Phones differ a lot (big.LITTLE cores, XNNPACK support): measure a few setups and keep the fastest.
            val dummy = FloatArray(3 * SIDE * SIDE) { 0.5f }
            var best: OrtSession? = null
            var bestMs = Long.MAX_VALUE
            var bestName = ""
            for ((xnn, threads) in listOf(false to 2, false to 4, true to 2, true to 4)) {
                val s = try { e.createSession(bytes, options(xnn, threads)) } catch (t: Throwable) {
                    Log.w(TAG, "setup xnnpack=$xnn threads=$threads not available", t); continue
                }
                val ms = try { bench(e, s, dummy) } catch (t: Throwable) { Long.MAX_VALUE }
                Log.i(TAG, "setup xnnpack=$xnn threads=$threads: $ms ms")
                if (ms < bestMs) { best?.close(); best = s; bestMs = ms; bestName = "xnnpack=$xnn threads=$threads" }
                else s.close()
            }
            val s = best ?: e.createSession(bytes, options(false, 2))
            Log.i(TAG, "using $bestName ($bestMs ms)")
            inputName = s.inputNames.firstOrNull() ?: inputName
            outputName = s.outputNames.firstOrNull { it == "heatmap" } ?: s.outputNames.first()
            env = e
            session = s
            Log.i(TAG, "model loaded: in=$inputName out=$outputName")
        } catch (t: Throwable) {
            Log.e(TAG, "model not available, falling back to OpenCV", t)
        }
    }

    /** Median time of a few runs after a warm-up. */
    private fun bench(e: OrtEnvironment, s: OrtSession, input: FloatArray): Long {
        val name = s.inputNames.first()
        val times = ArrayList<Long>()
        repeat(6) { i ->
            OnnxTensor.createTensor(e, FloatBuffer.wrap(input), longArrayOf(1, 3, SIDE.toLong(), SIDE.toLong())).use { t ->
                val t0 = SystemClock.elapsedRealtime()
                s.run(mapOf(name to t)).close()
                if (i >= 2) times += SystemClock.elapsedRealtime() - t0
            }
        }
        return times.sorted()[times.size / 2]
    }

    /**
     * Detects the 4 corners in [bgr] (CV_8UC3, upright). Returns normalized tl,tr,br,bl (0..1, may be
     * slightly outside the frame) or null when the model is not confident.
     */
    fun detect(bgr: Mat, pad: Float = padding, border: Int = borderType, fill: Double = borderFill): FloatArray? {
        val s = session ?: return null
        val e = env ?: return null
        val t0 = SystemClock.elapsedRealtime()
        // The frame is shrunk into the middle of the input with a border around it: the model misses corners that
        // touch the image edge (a document filling the frame), it was trained with background around pages.
        val inner = Math.round(SIDE / (1 + 2 * pad))
        val off = (SIDE - inner) / 2
        val resized = Mat()
        Imgproc.resize(bgr, resized, Size(inner.toDouble(), inner.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val small = if (off == 0 && inner == SIDE) resized else Mat().also {
            org.opencv.core.Core.copyMakeBorder(
                resized, it, off, SIDE - inner - off, off, SIDE - inner - off, border,
                org.opencv.core.Scalar(fill, fill, fill),
            )
            resized.release()
        }
        val px = ByteArray(SIDE * SIDE * 3)
        small.get(0, 0, px)
        small.release()
        // HWC BGR bytes -> CHW float 0..1
        val plane = SIDE * SIDE
        val input = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val o = i * 3
            input[i] = (px[o].toInt() and 0xFF) / 255f
            input[plane + i] = (px[o + 1].toInt() and 0xFF) / 255f
            input[2 * plane + i] = (px[o + 2].toInt() and 0xFF) / 255f
        }
        val tensor = OnnxTensor.createTensor(e, FloatBuffer.wrap(input), longArrayOf(1, 3, SIDE.toLong(), SIDE.toLong()))
        try {
            s.run(mapOf(inputName to tensor)).use { res ->
                val out = res.get(outputName).orElse(null) as? OnnxTensor ?: return null
                val shape = out.info.shape // 1, C, H, W
                if (shape.size != 4 || shape[1] < 4) return null
                val h = shape[2].toInt()
                val w = shape[3].toInt()
                val buf = out.floatBuffer
                val data = FloatArray(buf.remaining())
                buf.get(data)
                val result = FloatArray(8)
                lastPeaks.fill(0f)
                for (c in 0 until 4) {
                    var m = 0f
                    for (i in c * h * w until (c + 1) * h * w) if (data[i] > m) m = data[i]
                    lastPeaks[c] = m
                }
                for (c in 0 until 4) {
                    val p = peak(data, c * h * w, w, h) ?: return null
                    result[c * 2] = (p.first * SIDE - off) / inner
                    result[c * 2 + 1] = (p.second * SIDE - off) / inner
                }
                return orderNormalized(result)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "inference", t)
            return null
        } finally {
            tensor.close()
            val dt = SystemClock.elapsedRealtime() - t0
            totalMs += dt
            if (++calls % 50 == 0) { Log.i(TAG, "avg ${totalMs / calls} ms over $calls frames"); }
        }
    }

    /** Sub-pixel peak of one heatmap channel as normalized (x, y), or null when it is too weak. */
    private fun peak(d: FloatArray, off: Int, w: Int, h: Int): Pair<Float, Float>? {
        var best = off
        for (i in off until off + w * h) if (d[i] > d[best]) best = i
        val pk = d[best]
        if (pk < MIN_PEAK) return null
        val bx = (best - off) % w
        val by = (best - off) / w
        // Weighted centroid of the strong part of the blob around the peak.
        var sx = 0.0; var sy = 0.0; var sw = 0.0
        val r = 3
        for (y in max(0, by - r)..min(h - 1, by + r)) for (x in max(0, bx - r)..min(w - 1, bx + r)) {
            val v = d[off + y * w + x]
            if (v >= pk * 0.5f) { sx += v * x; sy += v * y; sw += v }
        }
        val cx = if (sw > 0) sx / sw else bx.toDouble()
        val cy = if (sw > 0) sy / sw else by.toDouble()
        return ((cx + 0.5) / w).toFloat() to ((cy + 0.5) / h).toFloat()
    }

    /** Orders normalized corners as tl, tr, br, bl (the model order is not relied upon). */
    private fun orderNormalized(c: FloatArray): FloatArray {
        val pts = Array(4) { org.opencv.core.Point(c[it * 2].toDouble(), c[it * 2 + 1].toDouble()) }
        val o = DocumentDetector.order(pts)
        return FloatArray(8) { i -> (if (i % 2 == 0) o[i / 2].x else o[i / 2].y).toFloat() }
    }
}

/**
 * Diagnostics (adb only): `adb shell am start -n com.smartscan/.MainActivity --ez debugCorners true`
 * runs the detector on every stored original photo and logs, per photo, the corner heatmap peaks, the raw
 * model corners and whether the shape check accepted them (tag NeuralDebug).
 */
object CornerDebug {
    fun run(context: Context) = Thread {
        NeuralCorners.init(context)
        val files = java.io.File(context.filesDir, "docs").walkTopDown()
            .filter { it.isFile && it.name.startsWith("orig_") }.toList().sortedBy { it.lastModified() }
        Log.i("NeuralDebug", "photos: ${files.size}")
        for (f in files) {
            val bgr = org.opencv.imgcodecs.Imgcodecs.imread(f.absolutePath, org.opencv.imgcodecs.Imgcodecs.IMREAD_REDUCED_COLOR_4)
            if (bgr.empty()) continue
            val variants = listOf(
                "none" to Triple(0f, org.opencv.core.Core.BORDER_CONSTANT, 0.0),
                "black12" to Triple(0.12f, org.opencv.core.Core.BORDER_CONSTANT, 0.0),
                "gray12" to Triple(0.12f, org.opencv.core.Core.BORDER_CONSTANT, 128.0),
                "repl12" to Triple(0.12f, org.opencv.core.Core.BORDER_REPLICATE, 0.0),
                "gray20" to Triple(0.20f, org.opencv.core.Core.BORDER_CONSTANT, 128.0),
            )
            val per = variants.joinToString(" ") { (name, v) ->
                NeuralCorners.detect(bgr, v.first, v.second, v.third)
                name + "=" + NeuralCorners.lastPeaks.joinToString("/") { "%.2f".format(java.util.Locale.US, it) }
            }
            val raw = NeuralCorners.detect(bgr)
            val peaks = per
            val gray = Mat()
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
            val ok = DocumentDetector.detectNormalized(bgr, gray)
            Log.i(
                "NeuralDebug",
                "${f.parentFile?.name}/${f.name} ${bgr.cols()}x${bgr.rows()} peaks=[$peaks] " +
                    "raw=${raw?.joinToString { "%.2f".format(java.util.Locale.US, it) }} accepted=${ok != null}",
            )
            bgr.release(); gray.release()
        }
        Log.i("NeuralDebug", "done")
    }.start()
}
