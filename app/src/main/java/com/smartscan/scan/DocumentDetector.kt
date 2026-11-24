package com.smartscan.scan

import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Normalized quad helpers. Order is always tl, tr, br, bl. */
object Corners {
    val FULL = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)

    fun parse(s: String): FloatArray =
        s.split(',').mapNotNull { it.trim().toFloatOrNull() }.takeIf { it.size == 8 }?.toFloatArray()
            ?: FULL.copyOf()

    fun format(c: FloatArray): String = c.joinToString(",") { "%.5f".format(java.util.Locale.US, it) }

    fun isFull(c: FloatArray, eps: Float = 0.002f): Boolean = c.indices.all { abs(c[it] - FULL[it]) < eps }

    /** Moves every corner away from (margin > 0) or toward (margin < 0) the centroid, clamped to the image. */
    fun expand(c: FloatArray, margin: Float): FloatArray {
        if (margin == 0f) return c.copyOf()
        val cx = (c[0] + c[2] + c[4] + c[6]) / 4f
        val cy = (c[1] + c[3] + c[5] + c[7]) / 4f
        return FloatArray(8) { i ->
            val center = if (i % 2 == 0) cx else cy
            (center + (c[i] - center) * (1f + margin * 2f)).coerceIn(0f, 1f)
        }
    }

    fun maxDelta(a: FloatArray, b: FloatArray): Float {
        var m = 0f
        for (i in 0 until 8) m = max(m, abs(a[i] - b[i]))
        return m
    }

    fun lerp(a: FloatArray, b: FloatArray, t: Float) = FloatArray(8) { a[it] + (b[it] - a[it]) * t }

    /** Area of the quad relative to the frame (0..1). */
    fun area(c: FloatArray): Float {
        var s = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            s += c[i * 2] * c[j * 2 + 1] - c[j * 2] * c[i * 2 + 1]
        }
        return abs(s) / 2f
    }
}

/** Page edge detection with OpenCV (Canny + contours + polygon approximation). */
object DocumentDetector {

    /** Returns corners (tl, tr, br, bl) in pixel coordinates of [gray], or null. */
    fun detect(gray: Mat): Array<Point>? {
        val maxSide = max(gray.cols(), gray.rows()).toDouble()
        val scale = if (maxSide > 480) 480.0 / maxSide else 1.0
        val small = Mat()
        if (scale < 1.0) Imgproc.resize(gray, small, Size(), scale, scale, Imgproc.INTER_AREA) else gray.copyTo(small)
        try {
            Imgproc.GaussianBlur(small, small, Size(5.0, 5.0), 0.0)
            // Closing removes text and fine texture inside the page, leaving the outline.
            val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
            Imgproc.morphologyEx(small, small, Imgproc.MORPH_CLOSE, k)
            k.release()

            val med = median(small)
            val lower = max(10.0, 0.66 * med)
            val upper = max(lower + 20.0, min(255.0, 1.33 * med))
            val edges = Mat()
            Imgproc.Canny(small, edges, lower, upper)
            val dk = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.dilate(edges, edges, dk)
            dk.release()

            val contours = ArrayList<MatOfPoint>()
            val hier = Mat()
            Imgproc.findContours(edges, contours, hier, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            hier.release()
            edges.release()

            val quad = bestQuad(contours, small.total().toDouble())
            contours.forEach { it.release() }
            return quad?.map { Point(it.x / scale, it.y / scale) }?.toTypedArray()?.let { order(it) }
        } finally {
            small.release()
        }
    }

    private fun bestQuad(contours: List<MatOfPoint>, frameArea: Double): Array<Point>? {
        val minArea = frameArea * 0.12
        val candidates = contours
            .filter { val r = Imgproc.boundingRect(it); r.area() > minArea }
            .map { c ->
                val pts = c.toArray()
                val hullIdx = MatOfInt()
                Imgproc.convexHull(c, hullIdx)
                val hull = MatOfPoint2f(*hullIdx.toArray().map { pts[it] }.toTypedArray())
                hullIdx.release()
                hull to abs(Imgproc.contourArea(hull))
            }
            .filter { it.second > minArea }
            .sortedByDescending { it.second }

        var result: Array<Point>? = null
        for ((hull, _) in candidates) {
            if (result != null) break
            val peri = Imgproc.arcLength(hull, true)
            for (eps in doubleArrayOf(0.02, 0.03, 0.05, 0.08)) {
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(hull, approx, eps * peri, true)
                if (approx.total() == 4L && abs(Imgproc.contourArea(approx)) > minArea) {
                    result = approx.toArray()
                    approx.release()
                    break
                }
                approx.release()
            }
        }
        candidates.forEach { it.first.release() }
        return result
    }

    private fun median(m: Mat): Double {
        val hist = Mat()
        val ch = MatOfInt(0)
        val bins = MatOfInt(256)
        val range = MatOfFloat(0f, 256f)
        Imgproc.calcHist(listOf(m), ch, Mat(), hist, bins, range)
        val half = m.total() / 2.0
        var acc = 0.0
        var result = 127.0
        for (i in 0 until 256) {
            acc += hist.get(i, 0)[0]
            if (acc >= half) { result = i.toDouble(); break }
        }
        hist.release(); ch.release(); bins.release(); range.release()
        return result
    }

    fun order(p: Array<Point>): Array<Point> {
        val tl = p.minBy { it.x + it.y }
        val br = p.maxBy { it.x + it.y }
        val tr = p.minBy { it.y - it.x }
        val bl = p.maxBy { it.y - it.x }
        return arrayOf(tl, tr, br, bl)
    }

    /** Variance of the Laplacian: higher = sharper. */
    fun sharpness(gray: Mat): Double {
        val small = Mat()
        val scale = min(1.0, 480.0 / max(gray.cols(), gray.rows()))
        Imgproc.resize(gray, small, Size(), scale, scale, Imgproc.INTER_AREA)
        val lap = Mat()
        Imgproc.Laplacian(small, lap, CvType.CV_64F)
        val mean = MatOfDouble()
        val std = MatOfDouble()
        Core.meanStdDev(lap, mean, std)
        val s = std.get(0, 0)[0]
        small.release(); lap.release(); mean.release(); std.release()
        return s * s
    }

    /** Tiny 32x24 thumbnail used to notice that the page in front of the camera changed. */
    fun signature(gray: Mat): ByteArray {
        val t = Mat()
        Imgproc.resize(gray, t, Size(32.0, 24.0), 0.0, 0.0, Imgproc.INTER_AREA)
        val out = ByteArray(32 * 24)
        t.get(0, 0, out)
        t.release()
        return out
    }

    fun signatureDiff(a: ByteArray, b: ByteArray): Double {
        if (a.size != b.size || a.isEmpty()) return 255.0
        var s = 0L
        for (i in a.indices) s += abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
        return s.toDouble() / a.size
    }
}

/** Converts the Y plane of a YUV ImageProxy into an upright grayscale Mat. */
fun ImageProxy.toUprightGray(buffer: ByteArray? = null): Mat {
    val plane = planes[0]
    val buf = plane.buffer
    val w = width
    val h = height
    val rowStride = plane.rowStride
    val data = if (buffer != null && buffer.size == w * h) buffer else ByteArray(w * h)
    if (rowStride == w) {
        buf.rewind()
        buf.get(data, 0, w * h)
    } else {
        for (y in 0 until h) {
            buf.position(y * rowStride)
            buf.get(data, y * w, w)
        }
    }
    val mat = Mat(h, w, CvType.CV_8UC1)
    mat.put(0, 0, data)
    val code = when (imageInfo.rotationDegrees) {
        90 -> Core.ROTATE_90_CLOCKWISE
        180 -> Core.ROTATE_180
        270 -> Core.ROTATE_90_COUNTERCLOCKWISE
        else -> return mat
    }
    val r = Mat()
    Core.rotate(mat, r, code)
    mat.release()
    return r
}
