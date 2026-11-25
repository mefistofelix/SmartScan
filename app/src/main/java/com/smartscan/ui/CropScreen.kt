package com.smartscan.ui

import com.smartscan.str

import com.smartscan.R

import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartscan.container
import com.smartscan.data.PageEntity
import com.smartscan.scan.Corners
import com.smartscan.scan.ImageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class CropViewModel(app: Application, pageId: Long) : AndroidViewModel(app) {
    private val c = app.container
    val page = MutableStateFlow<PageEntity?>(null)
    val bitmap = MutableStateFlow<ImageBitmap?>(null)
    val corners = MutableStateFlow(Corners.FULL.copyOf())
    val margin = MutableStateFlow(0f)
    val saving = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            val p = c.repo.page(pageId) ?: return@launch
            page.value = p
            corners.value = Corners.parse(p.corners)
            margin.value = p.margin
            bitmap.value = withContext(Dispatchers.IO) {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(p.originalPath, o)
                var sample = 1
                while (max(o.outWidth, o.outHeight) / sample > 1600) sample *= 2
                BitmapFactory.decodeFile(p.originalPath, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }
        }
    }

    fun autoDetect() = viewModelScope.launch {
        val p = page.value ?: return@launch
        val c2 = withContext(Dispatchers.Default) { ImageProcessor.detectCorners(p.originalPath) }
        corners.value = c2 ?: Corners.FULL.copyOf()
    }

    fun full() { corners.value = Corners.FULL.copyOf(); margin.value = 0f }

    fun apply(onDone: () -> Unit) = viewModelScope.launch {
        val p = page.value ?: return@launch
        saving.value = true
        runCatching {
            c.repo.rerender(p.copy(corners = Corners.format(corners.value), margin = margin.value), c.settings.autoDeskew)
        }
        saving.value = false
        onDone()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(pageId: Long, onClose: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: CropViewModel = viewModel(key = "crop_$pageId") { CropViewModel(app, pageId) }
    val bmp by vm.bitmap.collectAsStateWithLifecycle()
    val corners by vm.corners.collectAsStateWithLifecycle()
    val margin by vm.margin.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(str(R.string.crop_title)) },
            navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, str(R.string.back)) } },
            actions = { Button(onClick = { vm.apply(onClose) }, enabled = !saving, modifier = Modifier.padding(end = 8.dp)) { Text(str(R.string.apply)) } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
                val b = bmp
                if (b == null || saving) CircularProgressIndicator()
                else CropEditor(b, corners, margin, { vm.corners.value = it }, Modifier.fillMaxSize().padding(16.dp))
            }
            Text(
                str(R.string.crop_margin, (margin * 100).roundToInt()),
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 8.dp)
            )
            Slider(value = margin, onValueChange = { vm.margin.value = it }, valueRange = -0.05f..0.15f, modifier = Modifier.padding(horizontal = 16.dp))
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { vm.autoDetect() }, modifier = Modifier.weight(1f)) { Text(str(R.string.detect_edges)) }
                OutlinedButton(onClick = { vm.full() }, modifier = Modifier.weight(1f)) { Text(str(R.string.full_page)) }
            }
        }
    }
}

/** Draggable 4-corner editor with a magnifier loupe while dragging. */
@Composable
fun CropEditor(
    bitmap: ImageBitmap,
    corners: FloatArray,
    margin: Float,
    onChange: (FloatArray) -> Unit,
    modifier: Modifier = Modifier,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var dragIdx by remember { mutableIntStateOf(-1) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    val current by rememberUpdatedState(corners)
    val primary = MaterialTheme.colorScheme.primary
    val bw = bitmap.width.toFloat()
    val bh = bitmap.height.toFloat()

    fun geom(): FloatArray {
        val s = if (size.width == 0) 1f else min(size.width / bw, size.height / bh)
        val dw = bw * s; val dh = bh * s
        return floatArrayOf((size.width - dw) / 2, (size.height - dh) / 2, dw, dh, s)
    }

    Canvas(
        modifier.onSizeChanged { size = it }.pointerInput(bitmap, size) {
            detectDragGestures(
                onDragStart = { p ->
                    val g = geom()
                    var best = -1
                    var bestD = 56.dp.toPx()
                    for (i in 0 until 4) {
                        val d = hypot(g[0] + current[i * 2] * g[2] - p.x, g[1] + current[i * 2 + 1] * g[3] - p.y)
                        if (d < bestD) { bestD = d; best = i }
                    }
                    dragIdx = best
                    dragPos = p
                },
                onDragEnd = { dragIdx = -1 },
                onDragCancel = { dragIdx = -1 },
            ) { change, amount ->
                if (dragIdx >= 0) {
                    change.consume()
                    dragPos += amount
                    val g = geom()
                    val c = current.copyOf()
                    c[dragIdx * 2] = ((dragPos.x - g[0]) / g[2]).coerceIn(0f, 1f)
                    c[dragIdx * 2 + 1] = ((dragPos.y - g[1]) / g[3]).coerceIn(0f, 1f)
                    onChange(c)
                }
            }
        }
    ) {
        val g = geom()
        val ox = g[0]; val oy = g[1]; val dw = g[2]; val dh = g[3]
        drawImage(
            bitmap, dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
            dstSize = IntSize(dw.roundToInt(), dh.roundToInt())
        )
        fun pt(c: FloatArray, i: Int) = Offset(ox + c[i * 2] * dw, oy + c[i * 2 + 1] * dh)
        fun quad(c: FloatArray) = Path().apply {
            moveTo(pt(c, 0).x, pt(c, 0).y); for (i in 1..3) lineTo(pt(c, i).x, pt(c, i).y); close()
        }
        drawPath(quad(corners), primary.copy(alpha = 0.15f))
        drawPath(quad(corners), primary, style = Stroke(2.5.dp.toPx()))
        if (margin != 0f) drawPath(
            quad(Corners.expand(corners, margin)), Color.White,
            style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 10f)))
        )
        for (i in 0 until 4) {
            drawCircle(Color.White, 13.dp.toPx(), pt(corners, i))
            drawCircle(primary, 10.dp.toPx(), pt(corners, i))
        }
        if (dragIdx >= 0) {
            val p = pt(corners, dragIdx)
            val r = 56.dp.toPx()
            val cx = if (p.x < size.width / 2f) size.width - r - 8.dp.toPx() else r + 8.dp.toPx()
            val cy = r + 8.dp.toPx()
            val zoom = 2.5f
            val srcSide = (2 * r / (g[4] * zoom)).roundToInt().coerceIn(8, min(bitmap.width, bitmap.height))
            val sx = (corners[dragIdx * 2] * bw - srcSide / 2f).roundToInt().coerceIn(0, bitmap.width - srcSide)
            val sy = (corners[dragIdx * 2 + 1] * bh - srcSide / 2f).roundToInt().coerceIn(0, bitmap.height - srcSide)
            val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(cx, cy), r)) }
            clipPath(circle) {
                drawImage(
                    bitmap, srcOffset = IntOffset(sx, sy), srcSize = IntSize(srcSide, srcSide),
                    dstOffset = IntOffset((cx - r).roundToInt(), (cy - r).roundToInt()),
                    dstSize = IntSize((2 * r).roundToInt(), (2 * r).roundToInt())
                )
            }
            drawCircle(Color.White, r, Offset(cx, cy), style = Stroke(2.dp.toPx()))
            drawLine(primary, Offset(cx - 12, cy), Offset(cx + 12, cy), 2f)
            drawLine(primary, Offset(cx, cy - 12), Offset(cx, cy + 12), 2f)
        }
    }
}
