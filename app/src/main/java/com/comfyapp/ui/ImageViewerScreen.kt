package com.comfyapp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.comfyapp.AppContainer
import com.comfyapp.R
import com.comfyapp.data.ResultEntity
import com.comfyapp.ui.theme.Accent
import com.comfyapp.ui.theme.Bg
import com.comfyapp.ui.theme.Border
import com.comfyapp.ui.theme.ComfyType
import com.comfyapp.ui.theme.Fg
import com.comfyapp.ui.theme.Muted
import com.comfyapp.ui.theme.Raised
import com.comfyapp.ui.theme.Scrim62
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Two-finger pinch zoom (1..[maxScale]) + pan, applied around the gesture centroid.
 * Single-finger events stay unconsumed so siblings (pager swipe, divider drag, erase)
 * keep working — unless [panWhenZoomed], which pans with one finger while zoomed in.
 */
internal fun Modifier.pinchZoom(
    scale: androidx.compose.runtime.MutableFloatState,
    offset: androidx.compose.runtime.MutableState<Offset>,
    maxScale: Float = 5f,
    panWhenZoomed: Boolean = false,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var active = false
        var prevZoom = scale.floatValue
        var prevOffset = offset.value
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.count { it.pressed }
            if (pressed == 0) break
            val zoomedIn = panWhenZoomed && scale.floatValue > 1f
            if (!active && pressed >= 2) {
                active = true
                prevZoom = scale.floatValue
                prevOffset = offset.value
            }
            val takeOver = active || zoomedIn
            if (takeOver) {
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                val centroid = event.calculateCentroid()
                val newZoom = (prevZoom * zoomChange).coerceIn(1f, maxScale)
                event.changes.forEach { if (it.pressed) it.consume() }
                if (newZoom <= 1f) {
                    scale.floatValue = 1f
                    offset.value = Offset.Zero
                } else {
                    // keep the point under the centroid fixed while zooming
                    var newOffset = prevOffset + centroid * (prevZoom - newZoom) + panChange
                    val maxT = Offset(size.width * (newZoom - 1) / 2f, size.height * (newZoom - 1) / 2f)
                    newOffset = Offset(newOffset.x.coerceIn(-maxT.x, maxT.x), newOffset.y.coerceIn(-maxT.y, maxT.y))
                    scale.floatValue = newZoom
                    offset.value = newOffset
                }
                prevZoom = scale.floatValue
                prevOffset = offset.value
            }
        }
    }
}

@Composable
fun ImageViewerScreen(container: AppContainer, nav: NavHostController, resultId: Long) {
    val context = LocalContext.current
    val results = container.results
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val withPermission = rememberGalleryPermission()

    val entities by produceState<List<ResultEntity>?>(null) {
        value = results.observe().first()
    }

    val list = entities
    if (list == null) {
        Box(Modifier.fillMaxSize().background(Bg))
        return
    }
    val startIdx = list.indexOfFirst { it.id == resultId }
    if (list.isEmpty() || startIdx < 0) {
        Box(Modifier.fillMaxSize().background(Bg))
        LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }

    val pager = rememberPagerState(initialPage = startIdx, pageCount = { list.size })
    // 打开即记为已查看；翻页同样计入 LRU
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { results.touchViewed(list[it].id) }
    }

    val current = list[pager.currentPage]
    val inputOk = current.inputRef != null && results.inputFileOf(current)?.exists() == true

    var sheetOpen by remember { mutableStateOf(false) }
    val params = remember(current.params) { parseParams(container.json, current.params) }

    Box(Modifier.fillMaxSize().background(Bg)) {
        HorizontalPager(
            state = pager,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            ViewerPage(e = list[page], file = results.fileOf(list[page]))
        }

        // 顶部半透明栏：返回 / 页码与工作流名 / 保存 / 对比
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Scrim62, Color.Transparent)))
                .statusBarsPadding()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(
                    Icons.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.viewer_back_cd),
                    tint = Fg,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.viewer_page_fmt, pager.currentPage + 1, list.size),
                    style = ComfyType.MonoMeta,
                    color = Fg,
                )
                Text(current.workflowName, style = ComfyType.TitleCard, color = Fg, maxLines = 1)
            }
            IconButton(onClick = {
                val e = current
                withPermission {
                    scope.launch(Dispatchers.IO) {
                        val f = results.fileOf(e)
                        val ok = f.exists() && writeToGallery(context, "ComfyLAN_${e.file}", imageMime(f.name)) { out ->
                            f.inputStream().use { it.copyTo(out) }
                        } != null
                        if (ok) withContext(Dispatchers.Main) {
                            snackbar.showSnackbar(context.getString(R.string.results_saved_fmt, 1))
                        }
                    }
                }
            }) {
                Icon(
                    Icons.Rounded.FileDownload,
                    contentDescription = stringResource(R.string.action_save),
                    tint = Fg,
                )
            }
            if (current.isI2i) {
                TextButton(onClick = {
                    if (inputOk) {
                        nav.navigate("compare/${current.id}")
                    } else {
                        scope.launch { snackbar.showSnackbar(context.getString(R.string.input_missing)) }
                    }
                }) {
                    Text(
                        stringResource(R.string.viewer_compare),
                        style = ComfyType.BodyStrong,
                        // disabled = fg 38%（tokens §5）；仍可点按以提示原因
                        color = if (inputOk) Accent else Fg.copy(alpha = 0.38f),
                    )
                }
            }
        }

        // 参数抽屉遮罩
        if (sheetOpen) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(Scrim62)
                    .pointerInput(Unit) { detectTapGestures { sheetOpen = false } },
            )
        }

        // 参数抽屉
        AnimatedVisibility(
            visible = sheetOpen,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(Raised)
                    .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {}
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.viewer_params), style = ComfyType.TitleCard, color = Fg, modifier = Modifier.weight(1f))
                    IconButton(onClick = { sheetOpen = false }) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.compare_close_cd), tint = Muted)
                    }
                }
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    params.forEach { (k, v) ->
                        Text(
                            "$k: ${paramValueText(k, v)}",
                            style = ComfyType.MonoValue,
                            color = Fg,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
                TextButton(
                    onClick = {
                        val key = current.workflowKey ?: return@TextButton
                        sheetOpen = false
                        nav.navigate("workflow/$key?fromResult=${current.id}")
                    },
                    enabled = current.workflowKey != null,
                ) {
                    Text(
                        stringResource(R.string.viewer_rerun),
                        style = ComfyType.BodyStrong,
                        color = if (current.workflowKey != null) Accent else Fg.copy(alpha = 0.38f),
                    )
                }
            }
        }

        // 拉起抽屉的把手（点按或上滑）
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 20.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Raised)
                .border(1.dp, Border, RoundedCornerShape(999.dp))
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, dragAmount ->
                        change.consume()
                        if (dragAmount < 0) sheetOpen = true
                    }
                }
                .clickable { sheetOpen = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = null, tint = Muted, modifier = Modifier.height(18.dp).width(18.dp))
            Text(stringResource(R.string.viewer_params), style = ComfyType.Body, color = Fg)
        }

        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 84.dp),
        )
    }
}

@Composable
private fun ViewerPage(e: ResultEntity, file: File) {
    val scale = remember { mutableFloatStateOf(1f) }
    val offset = remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .fillMaxSize()
            .pinchZoom(scale, offset, panWhenZoomed = true)
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    scope.launch {
                        val target = if (scale.floatValue > 1.2f) 1f else 2.5f
                        animate(
                            initialValue = scale.floatValue,
                            targetValue = target,
                            animationSpec = tween(180),
                        ) { v, _ -> scale.floatValue = v }
                        if (target == 1f) offset.value = Offset.Zero
                    }
                })
            }
            .graphicsLayer {
                scaleX = scale.floatValue
                scaleY = scale.floatValue
                translationX = offset.value.x
                translationY = offset.value.y
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = file,
            contentDescription = e.workflowName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
