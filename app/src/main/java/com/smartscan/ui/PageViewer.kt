package com.smartscan.ui

import com.smartscan.str

import com.smartscan.R

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.request.ImageRequest
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Canvas
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import coil.imageLoader
import coil.request.SuccessResult
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Largest side decoded for the viewer: native resolution for normal pages, enough to judge HQ pages. */
private const val VIEWER_MAX_SIDE = 4096
private const val MAX_ZOOM = 8f

/**
 * Full-screen page viewer: swipe between pages, pinch or double tap to zoom, drag to pan.
 * Pages are decoded near full resolution so the scan quality can be checked.
 */
@Composable
fun PageViewer(paths: List<String>, start: Int, notes: List<String> = emptyList(), onDismiss: () -> Unit) {
    if (paths.isEmpty()) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val pager = rememberPagerState(initialPage = start.coerceIn(0, paths.lastIndex)) { paths.size }
        var zoomed by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed, key = { paths[it] }) { i ->
                ZoomableImage(paths[i], active = i == pager.currentPage) { zoomed = it }
            }
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(50))
                        .background(Color(0x99000000)),
                ) { Icon(Icons.Filled.Close, str(R.string.close), tint = Color.White) }
                notes.getOrNull(pager.currentPage)?.takeIf { it.isNotBlank() }?.let { n ->
                    Text(
                        n, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 56.dp)
                            .clip(RoundedCornerShape(8.dp)).background(Color(0x99000000))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                if (paths.size > 1) Text(
                    "${pager.currentPage + 1} / ${paths.size}",
                    color = Color.White, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                        .clip(RoundedCornerShape(50)).background(Color(0x99000000))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ZoomableImage(path: String, active: Boolean, onZoomed: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    if (!active && scale != 1f) {
        scale = 1f
        offset = Offset.Zero
    }

    var image by remember(path) { mutableStateOf<ImageBitmap?>(null) }

    /** Image size on screen at zoom 1 (fit inside the viewport). */
    fun fitted(): Size {
        val img = image ?: return Size(size.width.toFloat(), size.height.toFloat())
        val f = min(size.width / img.width.toFloat(), size.height / img.height.toFloat())
        return Size(img.width * f, img.height * f)
    }

    fun clamp(o: Offset, s: Float): Offset {
        val fit = fitted()
        val mx = max(0f, (fit.width * s - size.width) / 2f)
        val my = max(0f, (fit.height * s - size.height) / 2f)
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    fun set(s: Float, o: Offset) {
        scale = s.coerceIn(1f, MAX_ZOOM)
        offset = if (scale == 1f) Offset.Zero else clamp(o, scale)
        onZoomed(scale > 1f)
    }

    val ctx = LocalContext.current
    LaunchedEffect(path) {
        val req = ImageRequest.Builder(ctx).data(File(path)).size(VIEWER_MAX_SIDE).build()
        val d = (ctx.imageLoader.execute(req) as? SuccessResult)?.drawable
        image = (d as? BitmapDrawable)?.bitmap?.asImageBitmap()
    }
    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { t ->
                    val center = Offset(size.width / 2f, size.height / 2f)
                    if (scale > 1f) set(1f, Offset.Zero) else set(3f, (center - t) * 2f)
                })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        if (zoom != 1f || scale > 1f) {
                            val c = event.calculateCentroid() - Offset(size.width / 2f, size.height / 2f)
                            val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                            val z = newScale / scale
                            set(newScale, (offset - c) * z + c + event.calculatePan())
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        val img = image
        if (img == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        } else {
            // Only the visible part of the bitmap is drawn, at screen size: no huge scaled layer,
            // which some GPUs render black at high zoom.
            Canvas(Modifier.fillMaxSize()) {
                val fit = fitted()
                val k = fit.width * scale / img.width
                val x0 = (size.width - fit.width * scale) / 2f + offset.x
                val y0 = (size.height - fit.height * scale) / 2f + offset.y
                val left = max(0f, x0)
                val top = max(0f, y0)
                val right = min(size.width.toFloat(), x0 + fit.width * scale)
                val bottom = min(size.height.toFloat(), y0 + fit.height * scale)
                if (right <= left || bottom <= top) return@Canvas
                val sx = ((left - x0) / k).toInt().coerceIn(0, img.width - 1)
                val sy = ((top - y0) / k).toInt().coerceIn(0, img.height - 1)
                val sw = ((right - left) / k).roundToInt().coerceIn(1, img.width - sx)
                val sh = ((bottom - top) / k).roundToInt().coerceIn(1, img.height - sy)
                drawImage(
                    img,
                    srcOffset = IntOffset(sx, sy), srcSize = IntSize(sw, sh),
                    dstOffset = IntOffset((x0 + sx * k).roundToInt(), (y0 + sy * k).roundToInt()),
                    dstSize = IntSize((sw * k).roundToInt(), (sh * k).roundToInt()),
                    filterQuality = FilterQuality.High,
                )
            }
        }
    }
}
