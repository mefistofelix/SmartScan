package com.smartscan.scan

import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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

/**
 * Page edge detection with OpenCV.
 *
 * Quad candidates come from several sources (two Canny edge maps and an Otsu segmentation). Each
 * candidate must be a plausible convex quadrilateral, and is then scored by how much of its perimeter
 * actually lies on image edges. Without that check, clutter (keyboards, tables, text) produces
 * "quads" that jump around from frame to frame.
 */
object DocumentDetector {

    private const val WORK_SIDE = 360.0
    private const val MIN_AREA = 0.10
    private const val MIN_SUPPORT = 0.55
    private const val MIN_ANGLE = 60.0
    private const val MIN_OPPOSITE_RATIO = 0.5

    /** Below this chroma (|U-128| + |V-128|) a pixel counts as neutral: white / gray paper. */
    private const val PAPER_MAX_CHROMA = 22.0

    /**
     * Returns corners (tl, tr, br, bl) in pixel coordinates of [gray], or null.
     *
     * [chroma] (optional, any size, CV_8U: |U-128| + |V-128|) enables the "paper" source: bright and
     * colorless regions. It finds a white sheet on a busy, colorful background, where edge maps are full
     * of texture, and it then also favors candidates whose inside is paper.
     */
    fun detect(gray: Mat, chroma: Mat? = null): Array<Point>? {
        val maxSide = max(gray.cols(), gray.rows()).toDouble()
        val scale = min(1.0, WORK_SIDE / maxSide)
        val small = Mat()
        if (scale < 1.0) Imgproc.resize(gray, small, Size(), scale, scale, Imgproc.INTER_AREA) else gray.copyTo(small)
        val mats = ArrayList<Mat>()
        try {
            // Closing removes dark text and fine texture inside a light page, leaving its outline.
            val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.morphologyEx(small, small, Imgproc.MORPH_CLOSE, k)
            k.release()
            Imgproc.GaussianBlur(small, small, Size(5.0, 5.0), 0.0)

            val weak = Mat().also { mats += it }
            Imgproc.Canny(small, weak, 20.0, 60.0)
            val strong = Mat().also { mats += it }
            Imgproc.Canny(small, strong, 40.0, 120.0)
            val otsu = Mat().also { mats += it }
            Imgproc.threshold(small, otsu, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)

            // Support map: weak edges, slightly thickened, so a true page border scores close to 1.
            val support = Mat().also { mats += it }
            val dk = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.dilate(weak, support, dk)
            Imgproc.dilate(weak, weak, dk)
            Imgproc.dilate(strong, strong, dk)
            dk.release()

            val w = small.cols()
            val h = small.rows()
            val frameArea = w.toDouble() * h
            val paper = chroma?.let { paperMask(small, it) }?.also { mats += it }
                ?.takeIf { Core.countNonZero(it) >= frameArea * MIN_AREA }
            if (paper != null) {
                // The paper outline also counts as edge support.
                val grad = Mat().also { mats += it }
                val gk = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
                Imgproc.morphologyEx(paper, grad, Imgproc.MORPH_GRADIENT, gk)
                gk.release()
                Core.bitwise_or(support, grad, support)
            }
            var best: Array<Point>? = null
            var bestScore = 0.0
            for (src in listOfNotNull(paper, strong, weak, otsu)) {
                for (q in candidates(src, frameArea)) {
                    if (!plausible(q, w, h, frameArea)) continue
                    val s = support(q, support)
                    if (s < MIN_SUPPORT) continue
                    var score = abs(polyArea(q)) / frameArea * s * s * s
                    if (paper != null) {
                        val f = insideFraction(q, paper)
                        score *= f * f
                    }
                    if (score > bestScore) { bestScore = score; best = q }
                }
            }
            return best?.map { Point(it.x / scale, it.y / scale) }?.toTypedArray()?.let { order(it) }
        } finally {
            small.release()
            mats.forEach { it.release() }
        }
    }

    /** Bright and neutral pixels of [small] (already closed: text removed), cleaned up. Caller releases. */
    private fun paperMask(small: Mat, chroma: Mat): Mat {
        val ch = Mat()
        Imgproc.resize(chroma, ch, small.size(), 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.blur(ch, ch, Size(5.0, 5.0))
        val neutral = Mat()
        Imgproc.threshold(ch, neutral, PAPER_MAX_CHROMA, 255.0, Imgproc.THRESH_BINARY_INV)
        // Bright: close to the brightest part of the scene (paper is usually the whitest thing in view), so
        // pale colored areas and gray shadows are left out.
        val px = ByteArray(small.cols() * small.rows())
        small.get(0, 0, px)
        val hist = IntArray(256)
        for (b in px) hist[b.toInt() and 0xFF]++
        var acc = 0
        var p90 = 255
        for (v in 255 downTo 0) { acc += hist[v]; if (acc >= px.size / 10) { p90 = v; break } }
        val bright = Mat()
        Imgproc.threshold(small, bright, max(p90 * 0.82, 110.0), 255.0, Imgproc.THRESH_BINARY)
        val mask = Mat()
        Core.bitwise_and(neutral, bright, mask)
        // Opening with a larger kernel cuts thin bridges between the page and nearby light areas.
        val ko = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(7.0, 7.0))
        val kc = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, ko)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kc, Point(-1.0, -1.0), 2)
        ko.release(); kc.release(); ch.release(); neutral.release(); bright.release()
        return mask
    }

    /** Fraction of the quad's inside that is paper in [mask]. */
    private fun insideFraction(q: Array<Point>, mask: Mat): Double {
        val poly = Mat.zeros(mask.size(), CvType.CV_8UC1)
        val pts = MatOfPoint(*q.map { Point(Math.round(it.x).toDouble(), Math.round(it.y).toDouble()) }.toTypedArray())
        Imgproc.fillPoly(poly, listOf(pts), org.opencv.core.Scalar(255.0))
        val inside = Core.countNonZero(poly)
        Core.bitwise_and(poly, mask, poly)
        val paper = Core.countNonZero(poly)
        poly.release(); pts.release()
        return if (inside == 0) 0.0 else paper.toDouble() / inside
    }

    /** 4-point approximations of every large contour of [bin], directly and through its convex hull. */
    private fun candidates(bin: Mat, frameArea: Double): List<Array<Point>> {
        val contours = ArrayList<MatOfPoint>()
        val hier = Mat()
        Imgproc.findContours(bin, contours, hier, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        hier.release()
        val out = ArrayList<Array<Point>>()
        val minArea = frameArea * MIN_AREA
        for (c in contours) {
            if (Imgproc.boundingRect(c).area() < minArea) continue
            val pts = c.toArray()
            val curve = MatOfPoint2f(*pts)
            approx4(curve, doubleArrayOf(0.015, 0.025))?.let { out += it }
            val hullIdx = MatOfInt()
            Imgproc.convexHull(c, hullIdx)
            val hull = MatOfPoint2f(*hullIdx.toArray().map { pts[it] }.toTypedArray())
            hullIdx.release()
            if (abs(Imgproc.contourArea(hull)) >= minArea) {
                approx4(hull, doubleArrayOf(0.02, 0.035, 0.05))?.let { out += it }
            }
            hull.release()
            curve.release()
        }
        contours.forEach { it.release() }
        return out
    }

    private fun approx4(curve: MatOfPoint2f, eps: DoubleArray): Array<Point>? {
        val peri = Imgproc.arcLength(curve, true)
        for (e in eps) {
            val a = MatOfPoint2f()
            Imgproc.approxPolyDP(curve, a, e * peri, true)
            val n = a.total()
            val r = if (n == 4L) a.toArray() else null
            a.release()
            if (r != null) return r
            if (n < 4L) return null
        }
        return null
    }

    /** Convex, large enough, sane angles and sides, and not just the frame border. */
    private fun plausible(q: Array<Point>, w: Int, h: Int, frameArea: Double): Boolean {
        if (abs(polyArea(q)) < frameArea * MIN_AREA) return false
        val ip = MatOfPoint(*q.map { Point(Math.round(it.x).toDouble(), Math.round(it.y).toDouble()) }.toTypedArray())
        val convex = Imgproc.isContourConvex(ip)
        ip.release()
        if (!convex) return false
        // Any rectangle of any proportions, seen in perspective: every angle within 90° ± 30°, and opposite
        // sides of similar length (a tilted phone shortens the far side, but not by more than half).
        val minSide = 0.12 * sqrt(frameArea)
        val sides = DoubleArray(4)
        for (i in 0 until 4) {
            val a = q[(i + 3) % 4]
            val b = q[i]
            val c = q[(i + 1) % 4]
            sides[i] = hypot(c.x - b.x, c.y - b.y)
            if (sides[i] < minSide) return false
            val ang = angle(a, b, c)
            if (ang < MIN_ANGLE || ang > 180.0 - MIN_ANGLE) return false
        }
        for (i in 0 until 2) {
            val r = min(sides[i], sides[i + 2]) / max(sides[i], sides[i + 2])
            if (r < MIN_OPPOSITE_RATIO) return false
        }
        val m = 3.0
        val onBorder = q.count { it.x < m || it.y < m || it.x > w - 1 - m || it.y > h - 1 - m }
        return onBorder < 4
    }

    /** Fraction of the quad perimeter that lies on edge pixels of [edges]. */
    private fun support(q: Array<Point>, edges: Mat): Double {
        val w = edges.cols()
        val h = edges.rows()
        val data = ByteArray(w * h)
        edges.get(0, 0, data)
        var hit = 0
        var total = 0
        for (i in 0 until 4) {
            val a = q[i]
            val b = q[(i + 1) % 4]
            val len = hypot(b.x - a.x, b.y - a.y)
            val n = max(8, (len / 3).toInt())
            // Skip the ends: corners are often rounded, folded or covered by fingers.
            for (s in 1 until n) {
                val t = s.toDouble() / n
                if (t < 0.06 || t > 0.94) continue
                val x = (a.x + (b.x - a.x) * t).toInt().coerceIn(0, w - 1)
                val y = (a.y + (b.y - a.y) * t).toInt().coerceIn(0, h - 1)
                total++
                if (data[y * w + x].toInt() != 0) hit++
            }
        }
        return if (total == 0) 0.0 else hit.toDouble() / total
    }

    private fun angle(a: Point, b: Point, c: Point): Double {
        val v1x = a.x - b.x; val v1y = a.y - b.y
        val v2x = c.x - b.x; val v2y = c.y - b.y
        val d = hypot(v1x, v1y) * hypot(v2x, v2y)
        if (d == 0.0) return 0.0
        return Math.toDegrees(acos(((v1x * v2x + v1y * v2y) / d).coerceIn(-1.0, 1.0)))
    }

    private fun polyArea(q: Array<Point>): Double {
        var s = 0.0
        for (i in q.indices) {
            val j = (i + 1) % q.size
            s += q[i].x * q[j].y - q[j].x * q[i].y
        }
        return s / 2.0
    }

    /** Orders 4 points clockwise (in image coordinates), starting from the top-left one. */
    fun order(p: Array<Point>): Array<Point> {
        val cx = p.sumOf { it.x } / p.size
        val cy = p.sumOf { it.y } / p.size
        val sorted = p.sortedBy { atan2(it.y - cy, it.x - cx) }
        val start = sorted.indices.minBy { sorted[it].x + sorted[it].y }
        return Array(4) { sorted[(start + it) % 4] }
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

/**
 * Chroma magnitude |U-128| + |V-128| of a YUV_420_888 ImageProxy, upright, at half resolution.
 * Low values are neutral colors (white / gray paper). [buffer] is reused between frames when it fits.
 */
fun ImageProxy.toUprightChroma(buffer: ByteArray? = null): Mat {
    val u = planes[1]
    val v = planes[2]
    val cw = width / 2
    val chh = height / 2
    val ub = u.buffer
    val vb = v.buffer
    val ups = u.pixelStride
    val vps = v.pixelStride
    val urs = u.rowStride
    val vrs = v.rowStride
    val out = if (buffer != null && buffer.size == cw * chh) buffer else ByteArray(cw * chh)
    for (y in 0 until chh) {
        val uo = y * urs
        val vo = y * vrs
        val oo = y * cw
        for (x in 0 until cw) {
            val uu = (ub.get(uo + x * ups).toInt() and 0xFF) - 128
            val vv = (vb.get(vo + x * vps).toInt() and 0xFF) - 128
            out[oo + x] = minOf(255, kotlin.math.abs(uu) + kotlin.math.abs(vv)).toByte()
        }
    }
    val mat = Mat(chh, cw, CvType.CV_8UC1)
    mat.put(0, 0, out)
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
