package com.smartscan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.smartscan.data.PageEntity
import java.io.File

/**
 * Horizontal strip of page thumbnails. Tap opens a page; long press and drag moves it
 * ([onMove] gets the page and how many places it moved, saved on release).
 */
@Composable
fun PageRow(
    pages: List<PageEntity>,
    modifier: Modifier = Modifier,
    width: Dp = 66.dp,
    height: Dp = 88.dp,
    selectedId: Long? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    onTap: (Int) -> Unit,
    onMove: (PageEntity, Int) -> Unit,
    overlay: @Composable BoxScope.(Int, PageEntity) -> Unit = { _, _ -> },
) {
    val listState = rememberLazyListState()
    var dragList by remember { mutableStateOf<List<PageEntity>?>(null) }
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var startIndex by remember { mutableStateOf(-1) }
    val current by rememberUpdatedState(pages)
    // Keep the dragged order until the database emits it (no flash back to the old order).
    LaunchedEffect(pages) { if (draggingId == null) dragList = null }
    val shown = dragList ?: pages

    fun endDrag() {
        val list = dragList
        val id = draggingId
        if (list != null && id != null) {
            val to = list.indexOfFirst { it.id == id }
            val p = current.firstOrNull { it.id == id }
            if (p != null && startIndex >= 0 && to != startIndex) onMove(p, to - startIndex) else dragList = null
        }
        draggingId = null
        dragOffset = 0f
    }

    LazyRow(
        modifier, state = listState,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = contentPadding,
    ) {
        itemsIndexed(shown, key = { _, p -> p.id }) { i, p ->
            val dragging = draggingId == p.id
            Box(
                Modifier.size(width, height)
                    .clickable { onTap(i) }
                    .then(
                        if (dragging) Modifier.zIndex(1f).graphicsLayer {
                            translationX = dragOffset; scaleX = 1.1f; scaleY = 1.1f; shadowElevation = 16f
                        } else Modifier
                    )
                    .pointerInput(p.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                dragList = current
                                draggingId = p.id
                                dragOffset = 0f
                                startIndex = current.indexOfFirst { it.id == p.id }
                            },
                            onDragEnd = { endDrag() },
                            onDragCancel = { endDrag() },
                        ) { change, amount ->
                            change.consume()
                            dragOffset += amount.x
                            val list = dragList ?: return@detectDragGesturesAfterLongPress
                            val visible = listState.layoutInfo.visibleItemsInfo
                            val me = visible.firstOrNull { it.key == p.id } ?: return@detectDragGesturesAfterLongPress
                            val center = me.offset + me.size / 2 + dragOffset
                            val target = visible.firstOrNull {
                                it.key != p.id && center >= it.offset && center <= it.offset + it.size
                            } ?: return@detectDragGesturesAfterLongPress
                            val from = list.indexOfFirst { it.id == p.id }
                            val to = list.indexOfFirst { it.id == target.key }
                            if (from < 0 || to < 0) return@detectDragGesturesAfterLongPress
                            dragList = list.toMutableList().apply { add(to, removeAt(from)) }
                            dragOffset += me.offset - target.offset
                        }
                    },
            ) {
                AsyncImage(
                    model = File(p.processedPath), contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(Color.LightGray)
                        .then(
                            if (p.id == selectedId) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                            else Modifier
                        ),
                )
                overlay(i, p)
            }
        }
    }
}
