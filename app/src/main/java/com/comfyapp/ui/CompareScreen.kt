package com.comfyapp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.navigation.NavHostController
import coil.ImageLoader
import coil.request.ImageRequest
import com.comfyapp.AppContainer
import com.comfyapp.R
import com.comfyapp.data.ResultEntity
import com.comfyapp.ui.theme.Accent
import com.comfyapp.ui.theme.Bg
import com.comfyapp.ui.theme.Border
import com.comfyapp.ui.theme.ComfyType
import com.comfyapp.ui.theme.Fg
import com.comfyapp.ui.theme.Muted
import com.comfyapp.ui.theme.OnAccent
import com.comfyapp.ui.theme.Raised
import com.comfyapp.ui.theme.Scrim62
import com.comfyapp.ui.theme.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

// ---- params 快照解析（查看器与对比共用） ----

internal fun parseParams(json: Json, raw: String): Map<String, JsonElement> =
    runCatching { json.decodeFromString<Map<String, JsonElement>>(raw) }.getOrDefault(emptyMap())

/** 参数值原文；prompt 类值截断到 200 字符。 */
internal fun paramValueText(key: String, v: JsonElement): String {
    val text = when (v) {
        is JsonPrimitive -> v.content
        else -> v.toString()
    }
    return if (key.contains("prompt", ignoreCase = true) && text.length > 200) text.take(200) + "…" else text
}

private enum class Mode { SIDE, SPLIT, FADE, SCRUB }

private val snapPoints = floatArrayOf(0.25f, 0.5f, 0.75f)

private suspend fun decodeCapped(context: android.content.Context, loader: ImageLoader, file: File): Bitmap? =
    runCatching {
        val request = ImageRequest.Builder(context)
            .data(file)
            .size(2048, 2048)
            // 硬件位图无法读回像素做导出合成
            .allowHardware(false)
            .build()
        loader.execute(request).drawable?.toBitmap()
    }.getOrNull()

private class EraseState(val bmp: Bitmap, val canvas: Canvas) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    fun resetFrom(src: Bitmap) {
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        canvas.drawBitmap(src, 0f, 0f, null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(container: AppContainer, nav: NavHostController, resultId: Long) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val results = container.results
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val withPermission = rememberGalleryPermission()
    val haptics = LocalHapticFeedback.current

    var entity by remember { mutableStateOf<ResultEntity?>(null) }
    LaunchedEffect(resultId) {
        entity = results.get(resultId)
        if (entity == null) nav.popBackStack() // 行已被 LRU 清理
    }
    val e = entity
    if (e == null) {
        Box(Modifier.fillMaxSize().background(Bg))
        return
    }

    val inputFile = results.inputFileOf(e)
    val outputFile = results.fileOf(e)

    // 原图已被清理：空态 + 返回
    if (inputFile == null || !inputFile.exists()) {
        Scaffold(
            containerColor = Bg,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
                    navigationIcon = {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.compare_close_cd), tint = Fg)
                        }
                    },
                    title = {
                        Text(
                            stringResource(R.string.compare_title_fmt, e.workflowName),
                            style = ComfyType.TitleApp,
                            color = Fg,
                        )
                    },
                )
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.input_missing), style = ComfyType.Body, color = Muted)
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { nav.popBackStack() }) {
                        Text(stringResource(R.string.compare_missing_back), style = ComfyType.BodyStrong, color = Accent)
                    }
                }
            }
        }
        return
    }

    // 双图解码（较长边 2048px 上限，避免 OOM）
    var inputBmp by remember(e.id) { mutableStateOf<Bitmap?>(null) }
    var outputBmp by remember(e.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(e.id) {
        val loader = ImageLoader(context)
        val (i, o) = withContext(Dispatchers.IO) {
            decodeCapped(context, loader, inputFile) to decodeCapped(context, loader, outputFile)
        }
        inputBmp = i
        outputBmp = o
    }

    // 刮除层：输入图的可变副本，指尖以 CLEAR 擦除露出输出
    val erase = remember(inputBmp) {
        inputBmp?.let { src ->
            val wb = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                ?: return@let null
            android.graphics.Canvas(wb).drawBitmap(src, 0f, 0f, null)
            EraseState(wb, Canvas(wb))
        }
    }
    var eraseTick by remember { mutableIntStateOf(0) }
    var brushDp by remember { mutableIntStateOf(64) }

    var mode by rememberSaveable { mutableStateOf(Mode.SPLIT) }
    var fade by remember { mutableFloatStateOf(100f) }
    // 分割线位置：拖动直写，点击/双击经 spring 滑动（单一自定义手势检测器，
    // tap 与 drag 拆成两个 pointerInput 时 detectDragGestures 不触发——真机实测）
    var frac by remember { mutableFloatStateOf(0.5f) }
    var glideTarget by remember { mutableStateOf<Float?>(null) }
    var lastTapMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(glideTarget) {
        val t = glideTarget ?: return@LaunchedEffect
        animate(
            initialValue = frac,
            targetValue = t.coerceIn(0.03f, 0.97f),
            animationSpec = spring(dampingRatio = 0.85f, stiffness = 380f),
        ) { v, _ -> frac = v }
        glideTarget = null
    }
    fun glideTo(target: Float) {
        glideTarget = target
    }

    var stageW by remember { mutableIntStateOf(1) }
    val zoom = remember { mutableFloatStateOf(1f) }
    val zoomOffset = remember { mutableStateOf(Offset.Zero) }
    fun snapped(f: Float): Float {
        for (t in snapPoints) {
            if (abs(f - t) < 0.03f) {
                if (abs(frac - t) > 0.0001f) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                return t
            }
        }
        return f
    }

    val inB = inputBmp
    val outB = outputBmp
    val params = remember(e.params) { parseParams(container.json, e.params) }

    Scaffold(
        containerColor = Bg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.compare_close_cd), tint = Fg)
                    }
                },
                title = {
                    Text(
                        stringResource(R.string.compare_title_fmt, e.workflowName),
                        style = ComfyType.TitleApp,
                        color = Fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    val tagInput = stringResource(R.string.compare_tag_input)
                    val tagOutput = stringResource(R.string.compare_tag_output)
                    IconButton(onClick = {
                        val ib = inputBmp ?: return@IconButton
                        val ob = outputBmp ?: return@IconButton
                        val er = erase?.bmp
                        val m = mode
                        val f = frac
                        val fd = fade
                        withPermission {
                            scope.launch(Dispatchers.IO) {
                                val bmp = composeExport(ib, ob, er, m, f, fd, Border.toArgb(), Accent.toArgb(), tagInput, tagOutput)
                                val ok = writeToGallery(
                                    context,
                                    "ComfyLAN_compare_${e.id}_${System.currentTimeMillis()}.png",
                                    "image/png",
                                ) { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) } != null
                                if (ok) withContext(Dispatchers.Main) {
                                    snackbar.showSnackbar(context.getString(R.string.compare_exported))
                                }
                            }
                        }
                    }) {
                        Icon(Icons.Rounded.IosShare, contentDescription = stringResource(R.string.compare_export_cd), tint = Fg)
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // ---- 舞台 ----
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val i = inB
                val o = outB
                if (i == null || o == null) {
                    // 网格/加载骨架：surface 色块，无转圈
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(20.dp)
                            .background(Surface, RoundedCornerShape(16.dp)),
                    )
                } else {
                    val dispW = max(i.width, o.width).toFloat()
                    val dispH = max(i.height, o.height).toFloat()
                    val lastErase = remember { mutableStateOf(Offset.Zero) }

                    Box(
                        Modifier
                            .aspectRatio(dispW / dispH)
                            .onSizeChanged { stageW = it.width }
                            .pinchZoom(zoom, zoomOffset)
                            .then(
                                when (mode) {
                                    Mode.SPLIT -> Modifier.pointerInput(Unit) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            val downX = down.position.x
                                            var lastX = downX
                                            var dragging = false
                                            var taken = false // 手柄等内层已接管本手势
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                if (change.isConsumed) {
                                                    // 内层（手柄拖动）消费了位移：本手势不属于舞台层，
                                                    // 抬起时也不得当作 tap 触发 glide 把分割线拉回去
                                                    taken = true
                                                    break
                                                }
                                                if (!change.pressed) {
                                                    if (dragging) frac = snapped(frac)
                                                    if (!dragging && !taken) {
                                                        // tap: glide to tap x; tap again within 300ms: center
                                                        val now = change.uptimeMillis
                                                        if (now - lastTapMs < 300L) {
                                                            lastTapMs = 0L
                                                            glideTo(0.5f)
                                                        } else {
                                                            lastTapMs = now
                                                            glideTo(downX / size.width)
                                                        }
                                                    }
                                                    break
                                                }
                                                if (!dragging && abs(change.position.x - downX) > viewConfiguration.touchSlop) {
                                                    dragging = true
                                                    glideTarget = null // cancel in-flight glide
                                                }
                                                if (dragging) {
                                                    change.consume()
                                                    val dx = change.position.x - lastX
                                                    frac = (frac + dx / size.width).coerceIn(0.02f, 0.98f)
                                                } else if (!change.pressed) {
                                                    // unreachable; kept for clarity
                                                }
                                                lastX = change.position.x
                                            }
                                        }
                                    }

                                    Mode.FADE -> Modifier.pointerInput(Unit) {
                                        detectTapGestures(onDoubleTap = { fade = if (fade > 0f) 0f else 100f })
                                    }

                                    Mode.SCRUB -> Modifier.pointerInput(erase) {
                                        val wb = erase ?: return@pointerInput
                                        detectDragGestures(
                                            onDragStart = { lastErase.value = it },
                                            onDrag = { change, _ ->
                                                change.consume()
                                                val sx = wb.bmp.width / size.width.toFloat()
                                                val p = change.position
                                                wb.paint.strokeWidth = brushDp * density.density * sx
                                                wb.canvas.drawLine(
                                                    lastErase.value.x * sx,
                                                    lastErase.value.y * sx,
                                                    p.x * sx,
                                                    p.y * sx,
                                                    wb.paint,
                                                )
                                                lastErase.value = p
                                                eraseTick++
                                            },
                                        )
                                    }

                                    Mode.SIDE -> Modifier
                                }
                            )
                            .graphicsLayer {
                                scaleX = zoom.floatValue
                                scaleY = zoom.floatValue
                                translationX = zoomOffset.value.x
                                translationY = zoomOffset.value.y
                            },
                    ) {
                        when (mode) {
                            Mode.SIDE -> {
                                Row(Modifier.fillMaxSize()) {
                                    Box(Modifier.weight(1f).fillMaxHeight()) {
                                        Image(i.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.matchParentSize())
                                    }
                                    Box(Modifier.width(2.dp).fillMaxHeight().background(Border))
                                    Box(Modifier.weight(1f).fillMaxHeight()) {
                                        Image(o.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.matchParentSize())
                                    }
                                }
                            }

                            Mode.SPLIT -> {
                                Image(i.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.matchParentSize())
                                Box(
                                    Modifier
                                        .matchParentSize()
                                        .drawWithContent {
                                            clipRect(left = frac * size.width) { this@drawWithContent.drawContent() }
                                        },
                                ) {
                                    Image(o.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.matchParentSize())
                                }
                                // 2dp accent 分割线
                                Box(
                                    Modifier
                                        .align(Alignment.CenterStart)
                                        .offset { IntOffset((frac * stageW).roundToInt() - 1, 0) }
                                        .width(2.dp)
                                        .fillMaxHeight()
                                        .background(Accent),
                                )
                                // 34dp 手柄：自带拖动检测（舞台层手势竞争复杂，手柄直拖最可靠）
                                Box(
                                    Modifier
                                        .align(Alignment.CenterStart)
                                        .offset { IntOffset((frac * stageW).roundToInt(), 0) }
                                        .offset(x = -17.dp)
                                        .size(44.dp) // 34dp 视觉 + 触控余量
                                        .pointerInput(Unit) {
                                            detectDragGestures(
                                                onDragEnd = {
                                                    // 抬手才吸附；拖动途中吸附会把低速拖动永远锁在吸附点
                                                    frac = snapped(frac)
                                                },
                                            ) { change, _ ->
                                                // positionChange() 在 consume() 之后会返回 0 —— 必须先取位移再消费
                                                val dx = change.positionChange().x
                                                change.consume()
                                                val w = stageW.takeIf { it > 1 } ?: return@detectDragGestures
                                                glideTarget = null
                                                frac = (frac + dx / w).coerceIn(0.02f, 0.98f)
                                            }
                                        },
                                ) {
                                    Box(
                                        Modifier
                                            .size(34.dp)
                                            .background(Accent, CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Rounded.CompareArrows,
                                            contentDescription = stringResource(R.string.compare_handle_cd),
                                            tint = OnAccent,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }

                            Mode.FADE -> {
                                Image(i.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.matchParentSize())
                                Image(
                                    o.asImageBitmap(),
                                    null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.matchParentSize().graphicsLayer { alpha = fade / 100f },
                                )
                            }

                            Mode.SCRUB -> {
                                val iw = i.width.toFloat() / i.height
                                // 位图被原地擦改：以 eraseTick 为 key 重建 ImageBitmap wrapper 触发重绘
                                val eraseImg = remember(eraseTick) { erase?.bmp?.asImageBitmap() }
                                // 以输入图纵横比精确对位，双图同尺寸铺满
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Box(Modifier.aspectRatio(iw).fillMaxSize()) {
                                        Canvas(Modifier.fillMaxSize()) {
                                            drawImage(o.asImageBitmap(), dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
                                        }
                                        Canvas(
                                            Modifier
                                                .fillMaxSize()
                                                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
                                        ) {
                                            drawImage(
                                                eraseImg!!,
                                                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 常驻角标（不随缩放移动）
                CompareTag(stringResource(R.string.compare_tag_input), Modifier.align(Alignment.TopStart).padding(8.dp))
                CompareTag(stringResource(R.string.compare_tag_output), Modifier.align(Alignment.TopEnd).padding(8.dp))
            }

            // ---- 信息行：参数快照 ----
            if (params.isNotEmpty()) {
                Text(
                    params.entries.joinToString(" · ") { "${it.key}: ${paramValueText(it.key, it.value)}" },
                    style = ComfyType.MonoMeta,
                    color = Muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }

            // ---- 模式附属控件 ----
            if (mode == Mode.FADE) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Slider(
                        value = fade,
                        onValueChange = { fade = it },
                        valueRange = 0f..100f,
                        colors = SliderDefaults.colors(
                            thumbColor = Fg,
                            activeTrackColor = Fg,
                            inactiveTrackColor = Border,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        fade.roundToInt().toString(),
                        style = ComfyType.MonoValue,
                        color = Fg,
                        modifier = Modifier.width(36.dp),
                    )
                }
            }
            if (mode == Mode.SCRUB) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(32, 64, 128).forEach { d ->
                        val sel = brushDp == d
                        Box(
                            Modifier
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (sel) Raised else androidx.compose.ui.graphics.Color.Transparent)
                                .then(if (sel) Modifier.border(1.dp, Border, RoundedCornerShape(8.dp)) else Modifier)
                                .clickable { brushDp = d }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                d.toString(),
                                style = if (sel) ComfyType.BodyStrong else ComfyType.Body,
                                color = if (sel) Fg else Muted,
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        erase?.let {
                            it.resetFrom(inputBmp!!)
                            eraseTick++
                        }
                    }) {
                        Text(stringResource(R.string.compare_reset), style = ComfyType.BodyStrong, color = Accent)
                    }
                }
            }

            // ---- 模式分段控件（不用 accent） ----
            SegmentedControl(
                options = listOf(
                    stringResource(R.string.compare_mode_side) to Mode.SIDE,
                    stringResource(R.string.compare_mode_split) to Mode.SPLIT,
                    stringResource(R.string.compare_mode_fade) to Mode.FADE,
                    stringResource(R.string.compare_mode_scrub) to Mode.SCRUB,
                ),
                selected = mode,
                onSelect = { mode = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .navigationBarsPadding(),
            )
        }
    }
}

@Composable
private fun CompareTag(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = ComfyType.MonoMeta,
        color = Fg,
        modifier = modifier
            .background(Scrim62, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun SegmentedControl(
    options: List<Pair<String, Mode>>,
    selected: Mode,
    onSelect: (Mode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (label, m) ->
            val sel = m == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (sel) Raised else androidx.compose.ui.graphics.Color.Transparent)
                    .then(if (sel) Modifier.border(1.dp, Border, RoundedCornerShape(8.dp)) else Modifier)
                    .clickable { onSelect(m) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = if (sel) ComfyType.BodyStrong else ComfyType.Body, color = if (sel) Fg else Muted)
            }
        }
    }
}

/** 把当前模式的 "两图状态" 合成为一张导出位图（android.graphics，IO 线程执行）。 */
private fun composeExport(
    input: Bitmap,
    output: Bitmap,
    erased: Bitmap?,
    mode: Mode,
    frac: Float,
    fade: Float,
    borderArgb: Int,
    accentArgb: Int,
    tagInput: String,
    tagOutput: String,
): Bitmap {
    val w = max(input.width, output.width)
    val h = max(input.height, output.height)
    val gap = max(2, w / 1000)
    val bmpW = if (mode == Mode.SIDE) w * 2 + gap else w
    val bmp = Bitmap.createBitmap(bmpW, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    val full = RectF(0f, 0f, w.toFloat(), h.toFloat())

    when (mode) {
        Mode.SIDE -> {
            c.drawBitmap(input, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), paint)
            c.drawBitmap(output, null, RectF((w + gap).toFloat(), 0f, (w * 2 + gap).toFloat(), h.toFloat()), paint)
            c.drawRect(RectF(w.toFloat(), 0f, (w + gap).toFloat(), h.toFloat()), Paint().apply { color = borderArgb })
            drawTag(c, 0f, 0f, 0x9E000000.toInt(), android.graphics.Color.WHITE, tagInput)
            drawTag(c, (w + gap).toFloat(), 0f, 0x9E000000.toInt(), android.graphics.Color.WHITE, tagOutput)
        }

        Mode.SPLIT -> {
            c.drawBitmap(input, null, full, paint)
            val x = frac * w
            c.save()
            c.clipRect(x, 0f, w.toFloat(), h.toFloat())
            c.drawBitmap(output, null, full, paint)
            c.restore()
            val half = max(1f, w / 1000f)
            c.drawRect(RectF(x - half, 0f, x + half, h.toFloat()), Paint().apply { color = accentArgb })
            drawTag(c, 0f, 0f, 0x9E000000.toInt(), android.graphics.Color.WHITE, tagInput)
            drawTag(c, w - tagWidth(c, tagOutput), 0f, 0x9E000000.toInt(), android.graphics.Color.WHITE, tagOutput)
        }

        Mode.FADE -> {
            c.drawBitmap(input, null, full, paint)
            val alphaPaint = Paint(paint).apply { alpha = (fade * 2.55f).roundToInt().coerceIn(0, 255) }
            c.drawBitmap(output, null, full, alphaPaint)
        }

        Mode.SCRUB -> {
            c.drawBitmap(output, null, full, paint)
            erased?.let { c.drawBitmap(it, null, full, paint) }
        }
    }
    return bmp
}

private fun tagWidth(c: Canvas, text: String): Float {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = c.height / 22f }
    return p.measureText(text) + c.height / 30f * 2
}

private fun drawTag(c: Canvas, left: Float, top: Float, bg: Int, fg: Int, text: String) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = c.height / 22f; color = fg }
    val padX = c.height / 60f
    val padY = c.height / 110f
    val textH = p.descent() - p.ascent()
    val rect = RectF(left, top, left + p.measureText(text) + padX * 2, top + textH + padY * 2)
    c.drawRoundRect(rect, c.height / 50f, c.height / 50f, Paint().apply { color = bg })
    c.drawText(text, rect.left + padX, rect.top + padY - p.ascent(), p)
}
