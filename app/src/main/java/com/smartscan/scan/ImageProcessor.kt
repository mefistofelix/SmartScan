package com.smartscan.scan

import androidx.exifinterface.media.ExifInterface
import com.smartscan.data.PageEntity
import com.smartscan.data.PageFilter
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Perspective correction, deskew, illumination normalization and filters. */
object ImageProcessor {
    const val ORIGINAL_MAX_SIDE = 4000
    const val STORE_MAX_SIDE = 3300
    /** «Qualità max»: originals and pages keep (almost) the full sensor resolution. */
    const val HQ_ORIGINAL_MAX_SIDE = 8200
    const val HQ_STORE_MAX_SIDE = 6000
    const val HQ_EXPORT_MAX_SIDE = 6000
    const val HQ_JPEG_QUALITY = 95
    private const val READ_FLAGS = Imgcodecs.IMREAD_COLOR or Imgcodecs.IMREAD_IGNORE_ORIENTATION

    /** Applies EXIF orientation and caps resolution so the rest of the pipeline sees upright pixels. */
    fun normalizeCapture(src: File, dst: File, hq: Boolean = false) {
        val orientation = runCatching {
            ExifInterface(src.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        var m = Imgcodecs.imread(src.absolutePath, READ_FLAGS)
        require(!m.empty()) { "Immagine non leggibile" }
        val code = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> Core.ROTATE_90_CLOCKWISE
            ExifInterface.ORIENTATION_ROTATE_180 -> Core.ROTATE_180
            ExifInterface.ORIENTATION_ROTATE_270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            else -> -1
        }
        if (code >= 0) {
            val r = Mat(); Core.rotate(m, r, code); m.release(); m = r
        }
        m = limit(m, if (hq) HQ_ORIGINAL_MAX_SIDE else ORIGINAL_MAX_SIDE)
        Imgcodecs.imwrite(dst.absolutePath, m, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, if (hq) 97 else 94))
        m.release()
    }

    /** Detects page corners on a stored (upright) image; result normalized to 0..1. */
    fun detectCorners(path: String): FloatArray? {
        val color = Imgcodecs.imread(path, Imgcodecs.IMREAD_REDUCED_COLOR_4)
        if (color.empty()) return null
        val gray = Mat()
        Imgproc.cvtColor(color, gray, Imgproc.COLOR_BGR2GRAY)
        // Chroma |Cr-128| + |Cb-128|, same meaning as the live camera one (white paper = low).
        val ycc = Mat()
        Imgproc.cvtColor(color, ycc, Imgproc.COLOR_BGR2YCrCb)
        color.release()
        val ch = ArrayList<Mat>()
        Core.split(ycc, ch)
        ycc.release()
        val mid = Mat(gray.size(), CvType.CV_8UC1, org.opencv.core.Scalar(128.0))
        Core.absdiff(ch[1], mid, ch[1])
        Core.absdiff(ch[2], mid, ch[2])
        val chroma = Mat()
        Core.add(ch[1], ch[2], chroma)
        mid.release(); ch.forEach { it.release() }
        val q = DocumentDetector.detect(gray, chroma)
        chroma.release()
        val w = gray.cols().toDouble()
        val h = gray.rows().toDouble()
        gray.release()
        q ?: return null
        val c = FloatArray(8) { i -> if (i % 2 == 0) (q[i / 2].x / w).toFloat() else (q[i / 2].y / h).toFloat() }
        return c.map { it.coerceIn(0f, 1f) }.toFloatArray()
    }

    /** Renders [page] into a new file in [dir] and returns its path. */
    fun renderPage(page: PageEntity, dir: File, deskew: Boolean): String {
        val hq = isHighQuality(page.originalPath)
        val m = render(page.originalPath, Corners.parse(page.corners), page.margin, page.filter, page.rotation, deskew, hq)
        val bw = page.filter == PageFilter.BW
        val out = File(dir, "p_${page.id}_${System.nanoTime()}." + if (bw) "png" else "jpg")
        val params = if (bw) MatOfInt(Imgcodecs.IMWRITE_PNG_BILEVEL, 1)
        else MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, if (hq) HQ_JPEG_QUALITY else 90)
        Imgcodecs.imwrite(out.absolutePath, m, params)
        m.release()
        return out.absolutePath
    }

    fun render(
        originalPath: String, corners: FloatArray, margin: Float, filter: PageFilter,
        rotation: Int, deskew: Boolean, hq: Boolean = false,
    ): Mat {
        val src = Imgcodecs.imread(originalPath, READ_FLAGS)
        require(!src.empty()) { "Originale mancante" }
        val c = Corners.expand(corners, margin)
        var m = if (Corners.isFull(c)) src else warp(src, c).also { src.release() }
        if (deskew) m = deskew(m)
        val rot = ((rotation % 360) + 360) % 360
        if (rot != 0) {
            val r = Mat()
            Core.rotate(
                m, r, when (rot) {
                    90 -> Core.ROTATE_90_CLOCKWISE
                    180 -> Core.ROTATE_180
                    else -> Core.ROTATE_90_COUNTERCLOCKWISE
                }
            )
            m.release(); m = r
        }
        m = limit(m, if (hq) HQ_STORE_MAX_SIDE else STORE_MAX_SIDE)
        val out = applyFilter(m, filter)
        if (out !== m) m.release()
        return out
    }

    /**
     * A page was captured with «Qualità max» when its original is larger than the normal cap.
     * Derived from the file, so re-renders and exports keep the quality without a schema change.
     */
    fun isHighQuality(path: String): Boolean {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, o)
        return max(o.outWidth, o.outHeight) > ORIGINAL_MAX_SIDE
    }

    fun limit(m: Mat, maxSide: Int): Mat {
        val s = max(m.cols(), m.rows())
        if (s <= maxSide) return m
        val f = maxSide.toDouble() / s
        val r = Mat()
        Imgproc.resize(m, r, Size(), f, f, Imgproc.INTER_AREA)
        m.release()
        return r
    }

    fun warp(src: Mat, c: FloatArray): Mat {
        val w = src.cols().toDouble()
        val h = src.rows().toDouble()
        val p = (0 until 4).map { Point(c[it * 2] * w, c[it * 2 + 1] * h) }
        fun d(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
        val outW = max(d(p[0], p[1]), d(p[3], p[2])).roundToInt().coerceAtLeast(16)
        val outH = max(d(p[0], p[3]), d(p[1], p[2])).roundToInt().coerceAtLeast(16)
        val srcM = MatOfPoint2f(p[0], p[1], p[2], p[3])
        val dstM = MatOfPoint2f(
            Point(0.0, 0.0), Point(outW - 1.0, 0.0),
            Point(outW - 1.0, outH - 1.0), Point(0.0, outH - 1.0)
        )
        val t = Imgproc.getPerspectiveTransform(srcM, dstM)
        val out = Mat()
        Imgproc.warpPerspective(
            src, out, t, Size(outW.toDouble(), outH.toDouble()),
            Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE
        )
        srcM.release(); dstM.release(); t.release()
        return out
    }

    /** Estimates residual skew from text lines (degrees, OpenCV sign convention). */
    fun estimateSkew(m: Mat): Double? {
        val gray = Mat()
        if (m.channels() == 3) Imgproc.cvtColor(m, gray, Imgproc.COLOR_BGR2GRAY) else m.copyTo(gray)
        val small = limit(gray, 1200)
        val bin = Mat()
        Imgproc.adaptiveThreshold(
            small, bin, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, 25, 15.0
        )
        val kw = max(15, small.cols() / 40).toDouble()
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(kw, 1.0))
        Imgproc.dilate(bin, bin, k)
        k.release()
        val contours = ArrayList<MatOfPoint>()
        val hier = Mat()
        Imgproc.findContours(bin, contours, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        val minLen = small.cols() * 0.15
        val samples = ArrayList<Pair<Double, Double>>() // angle, weight
        for (c in contours) {
            if (c.total() < 5) { c.release(); continue }
            val c2 = MatOfPoint2f(*c.toArray())
            val rect = Imgproc.minAreaRect(c2)
            c2.release(); c.release()
            val pts = arrayOfNulls<Point>(4)
            rect.points(pts)
            val p0 = pts[0]!!; val p1 = pts[1]!!; val p2 = pts[2]!!
            val e1x = p1.x - p0.x; val e1y = p1.y - p0.y
            val e2x = p2.x - p1.x; val e2y = p2.y - p1.y
            val l1 = hypot(e1x, e1y); val l2 = hypot(e2x, e2y)
            val (lx, ly, long, short) = if (l1 >= l2) listOf(e1x, e1y, l1, l2) else listOf(e2x, e2y, l2, l1)
            if (long < minLen || long < short * 5) continue
            var a = Math.toDegrees(atan2(ly, lx))
            if (a > 90) a -= 180
            if (a <= -90) a += 180
            if (abs(a) < 15) samples += a to long
        }
        hier.release(); bin.release(); small.release()
        if (gray !== small) gray.release()
        if (samples.size < 3) return null
        val sorted = samples.sortedBy { it.first }
        val total = sorted.sumOf { it.second }
        var acc = 0.0
        for ((a, w) in sorted) {
            acc += w
            if (acc >= total / 2) return a
        }
        return null
    }

    fun deskew(m: Mat): Mat {
        val angle = estimateSkew(m) ?: return m
        if (abs(angle) < 0.2 || abs(angle) > 15) return m
        val center = Point(m.cols() / 2.0, m.rows() / 2.0)
        val r = Imgproc.getRotationMatrix2D(center, angle, 1.0)
        val out = Mat()
        Imgproc.warpAffine(m, out, r, m.size(), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
        r.release()
        m.release()
        return out
    }

    /** Divides each channel by an estimate of the background (shadows / uneven light removal). */
    /**
     * Divides [ch] by its estimated background (page brightness), so shadows and uneven light go away.
     * [floor] caps the brightening (255 / floor) for large dark or colored areas.
     */
    private fun normalizeIllumination(ch: Mat, floor: Double = 0.0) {
        val small = Mat()
        Imgproc.resize(ch, small, Size(), 0.25, 0.25, Imgproc.INTER_AREA)
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(9.0, 9.0))
        Imgproc.dilate(small, small, k)
        Imgproc.medianBlur(small, small, 21)
        val bg = Mat()
        Imgproc.resize(small, bg, ch.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        if (floor > 0) Core.max(bg, org.opencv.core.Scalar(floor), bg)
        Core.divide(ch, bg, ch, 255.0)
        k.release(); small.release(); bg.release()
    }

    fun applyFilter(m: Mat, f: PageFilter): Mat = when (f) {
        PageFilter.ORIGINAL -> m
        // Lightness only (Lab L): shadows removed and the page whitened, hues and saturation unchanged,
        // so colors stay as in the preview. Per-channel normalization used to shift and saturate colors.
        PageFilter.ENHANCED -> if (m.channels() != 3) m else {
            val lab = Mat()
            Imgproc.cvtColor(m, lab, Imgproc.COLOR_BGR2Lab)
            val chans = ArrayList<Mat>()
            Core.split(lab, chans)
            lab.release()
            normalizeIllumination(chans[0], floor = 150.0)
            chans[0].convertTo(chans[0], -1, 1.1, -20.0)
            val merged = Mat()
            Core.merge(chans, merged)
            chans.forEach { it.release() }
            val out = Mat()
            Imgproc.cvtColor(merged, out, Imgproc.COLOR_Lab2BGR)
            merged.release()
            out
        }
        PageFilter.GRAY -> {
            val g = toGray(m)
            normalizeIllumination(g)
            g.convertTo(g, -1, 1.3, -76.0)
            g
        }
        PageFilter.BW -> {
            val g = toGray(m)
            normalizeIllumination(g)
            Imgproc.GaussianBlur(g, g, Size(3.0, 3.0), 0.0)
            val block = (max(15, max(g.cols(), g.rows()) / 60)) or 1
            val out = Mat()
            Imgproc.adaptiveThreshold(
                g, out, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, block, 12.0
            )
            g.release()
            out
        }
    }

    private fun toGray(m: Mat): Mat {
        val g = Mat()
        if (m.channels() == 1) m.copyTo(g) else Imgproc.cvtColor(m, g, Imgproc.COLOR_BGR2GRAY)
        return g
    }

    /** Reads a processed page for export, resized to [maxSide]. Returns 1 or 3 channel Mat. */
    fun readForExport(path: String, maxSide: Int): Mat {
        var m = Imgcodecs.imread(path, Imgcodecs.IMREAD_UNCHANGED)
        require(!m.empty()) { "Pagina mancante: $path" }
        if (m.channels() == 4) {
            val r = Mat(); Imgproc.cvtColor(m, r, Imgproc.COLOR_BGRA2BGR); m.release(); m = r
        }
        val s = max(m.cols(), m.rows())
        if (s > maxSide) {
            val f = min(1.0, maxSide.toDouble() / s)
            val r = Mat(); Imgproc.resize(m, r, Size(), f, f, Imgproc.INTER_AREA); m.release(); m = r
        }
        return m
    }
}
