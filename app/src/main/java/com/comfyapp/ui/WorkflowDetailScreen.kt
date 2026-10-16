package com.comfyapp.ui

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.comfyapp.AppContainer
import com.comfyapp.R
import com.comfyapp.api.ProgressEvent
import com.comfyapp.api.ResultImage
import com.comfyapp.convert.ResolvedWidget
import com.comfyapp.data.ExposedWidget
import com.comfyapp.data.PendingRun
import com.comfyapp.data.WorkflowEntity
import com.comfyapp.model.ObjectInfo
import com.comfyapp.model.WidgetDef
import com.comfyapp.model.WidgetKind
import com.comfyapp.model.WfNode
import com.comfyapp.model.Workflow
import com.comfyapp.model.asBooleanOrNull
import com.comfyapp.model.asDoubleOrNull
import com.comfyapp.model.asString
import com.comfyapp.ui.theme.Accent
import com.comfyapp.ui.theme.Bg
import com.comfyapp.ui.theme.Border
import com.comfyapp.ui.theme.ComfyType
import com.comfyapp.ui.theme.Error
import com.comfyapp.ui.theme.Fg
import com.comfyapp.ui.theme.Muted
import com.comfyapp.ui.theme.OnAccent
import com.comfyapp.ui.theme.Raised
import com.comfyapp.ui.theme.Scrim62
import com.comfyapp.ui.theme.Success
import com.comfyapp.ui.theme.Surface
import com.comfyapp.ui.theme.Warning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.net.URL
import kotlin.random.Random

/** 结果区运行中的占位格数（mock 为 4 格）。 */

/** run-cta: 16 / SemiBold，Body 栈。 */
private val RunCta = ComfyType.BodyStrong.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

/** 运行失败对话框错误详情：mono 11sp。 */
private val MonoError = ComfyType.MonoValue.copy(fontSize = 11.sp)

sealed interface RunState {
    data object Idle : RunState
    data class Running(val value: Int, val max: Int) : RunState
    data object Done : RunState
    data class Error(val message: String) : RunState
}

class WorkflowDetailViewModel(
    private val container: AppContainer,
    val key: String,
    private val seedFlags: SharedPreferences,
    private val fromResultId: Long = -1L,
) : ViewModel() {
    val api get() = container.api
    val resultsRepo get() = container.results
    private val clientId = java.util.UUID.randomUUID().toString()
    private val converter get() = container.converter

    var entity by mutableStateOf<WorkflowEntity?>(null)
    var wf by mutableStateOf<Workflow?>(null)
    var info by mutableStateOf<Map<String, ObjectInfo>?>(null)
    var infoError by mutableStateOf<String?>(null)
    var runState by mutableStateOf<RunState>(RunState.Idle)
    var message by mutableStateOf<String?>(null)
    var runningNode by mutableStateOf<String?>(null)
        private set
    /** 上一次运行的结果（结果区网格数据源；新运行开始时清空）。 */
    var lastImages by mutableStateOf<List<ResultImage>>(emptyList())
        private set
    var lastResultIds by mutableStateOf<List<Long>>(emptyList())
        private set

    /** 本次会话的历次运行结果（进程存活期内；跨会话历史在结果中心）。最新在前。 */
    val sessionRuns = mutableStateListOf<com.comfyapp.data.ResultEntity>()
    val values = mutableStateMapOf<String, JsonElement>()
    val randomSeeds = mutableStateMapOf<String, Boolean>()

    val exposed: StateFlow<List<ExposedWidget>> = container.exposed.exposed(key)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var seedAttempted = false

    /** 已收尾的 promptId：WS 事件与恢复轮询可能并发到达，防止重复 capture。 */
    private val finalizedPids = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            val e = container.workflows.get(key) ?: run { message = "工作流不存在"; return@launch }
            entity = e
            wf = runCatching { container.json.decodeFromString<Workflow>(e.json) }.getOrNull()
                ?: run { message = "工作流解析失败"; return@launch }
            // 已保存的参数默认值先入表；下面的 fromResultId 结果参数仍优先覆盖，
            // exposed.collect 的默认值 seeding 只补缺，顺序天然正确。
            e.paramDefaults?.let { pd ->
                runCatching { container.json.decodeFromString<Map<String, JsonElement>>(pd) }
                    .getOrNull()?.let { values.putAll(it) }
            }
            // 用相同参数再跑：结果参数先入表，后面的 objectInfo/默认值 seeding 只补缺，不覆盖。
            if (fromResultId >= 0) {
                container.results.get(fromResultId)?.let { re ->
                    runCatching { container.json.decodeFromString<Map<String, JsonElement>>(re.params) }
                        .getOrNull()?.let { values.putAll(it) }
                }
            }
            info = try {
                api.objectInfo()
            } catch (ex: Exception) {
                infoError = "加载节点定义失败：${ex.message}"
                null
            } ?: return@launch
            seedIfNeeded()
            resumePendingRuns()
            // Keep values seeded for newly exposed widgets (added from the node tab).
            exposed.collect { list ->
                for (x in list) {
                    val k = "${x.nodeId}:${x.widgetName}"
                    if (k !in values) initialValue(x)?.let { values[k] = it }
                }
            }
        }
    }

    /** Auto-seed exposure on first open: image pickers, multiline text, seeds, whole subgraphs. */
    private suspend fun seedIfNeeded() {
        if (seedAttempted) return
        seedAttempted = true
        val w = wf ?: return
        val oi = info ?: return
        val current = container.exposed.exposed(key).first()
        if (seedFlags.getBoolean(key, false) || current.isNotEmpty()) return
        val out = ArrayList<ExposedWidget>()
        var order = 0
        for (n in w.nodes.filter { it.mode == 0 }) {
            val isSubgraph = w.definitions?.subgraph(n.type) != null
            for (r in converter.resolveWidgets(n, w, oi)) {
                val d = r.def
                if (isSubgraph || d.imageUpload || d.controlAfterGenerate ||
                    (d.kind == WidgetKind.STRING && d.multiline)
                ) {
                    out += ExposedWidget(
                        workflowKey = key,
                        nodeId = n.id.toString(),
                        widgetName = d.name,
                        sortOrder = order++,
                    )
                }
            }
        }
        runCatching { container.exposed.replace(key, out) }
        seedFlags.edit().putBoolean(key, true).apply()
    }

    fun resolveAll(node: WfNode): List<ResolvedWidget> {
        val w = wf ?: return emptyList()
        val oi = info ?: return emptyList()
        return converter.resolveWidgets(node, w, oi)
    }

    fun resolved(nodeId: String, widgetName: String): ResolvedWidget? {
        val node = wf?.node(nodeId.toLongOrNull() ?: return null) ?: return null
        return resolveAll(node).firstOrNull { it.def.name == widgetName }
    }

    private fun initialValue(x: ExposedWidget): JsonElement? =
        resolved(x.nodeId, x.widgetName)?.let { it.value ?: it.def.default }

    /** 字段副标题 "<类型> · #<id>"；子图实例显示子图名。 */
    fun nodeOrigin(nodeIdStr: String): String {
        val node = nodeIdStr.toLongOrNull()?.let { wf?.node(it) } ?: return nodeIdStr
        val type = wf?.definitions?.subgraphs?.firstOrNull { it.id == node.type }?.name
            ?.takeIf { it.isNotBlank() } ?: node.type
        return "$type · #${node.id}"
    }

    /** 进度事件节点名："23:9" → 子图 #23 内部节点 9 的类型；普通 id → 节点类型；否则原样。 */
    fun nodeDisplayName(id: String): String {
        val w = wf ?: return id
        val outerId = id.substringBefore(':').toLongOrNull() ?: return id
        if (':' in id) {
            val innerId = id.substringAfter(':').toLongOrNull()
            val host = w.node(outerId)
            val sg = host?.let { h -> w.definitions?.subgraphs?.firstOrNull { it.id == h.type } }
            return innerId?.let { i -> sg?.nodes?.firstOrNull { it.id == i }?.type } ?: id
        }
        return w.node(outerId)?.type ?: id
    }

    fun toggleExposed(nodeId: String, widgetName: String, add: Boolean) {
        viewModelScope.launch {
            val cur = container.exposed.exposed(key).first()
            val k = "$nodeId:$widgetName"
            val list = if (add) {
                if (cur.any { it.nodeId == nodeId && it.widgetName == widgetName }) {
                    cur
                } else {
                    cur + ExposedWidget(
                        workflowKey = key,
                        nodeId = nodeId,
                        widgetName = widgetName,
                        sortOrder = (cur.maxOfOrNull { it.sortOrder } ?: -1) + 1,
                    )
                }
            } else {
                values.remove(k)
                randomSeeds.remove(k)
                cur.filterNot { it.nodeId == nodeId && it.widgetName == widgetName }
            }
            runCatching { container.exposed.replace(key, list) }
                .onFailure { message = "更新失败：${it.message}" }
        }
    }

    fun upload(cr: ContentResolver, uri: Uri, valueKey: String) {
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    cr.openInputStream(uri)?.use { it.readBytes() }
                }
                if (bytes == null) {
                    message = "读取图片失败"
                    return@launch
                }
                val name = queryDisplayName(cr, uri) ?: "upload_${System.currentTimeMillis()}.png"
                val mime = cr.getType(uri) ?: "image/png"
                values[valueKey] = JsonPrimitive(api.uploadImage(name, bytes, mime).name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "上传失败：${e.message}"
            }
        }
    }

    fun run() {
        val w = wf ?: return
        val oi = info ?: return
        viewModelScope.launch {
            try {
                val overrides = HashMap(values)
                for ((k, rand) in randomSeeds) {
                    if (rand) overrides[k] = JsonPrimitive(Random.nextLong(0L, 1L shl 48))
                }
                lastImages = emptyList()
                lastResultIds = emptyList()
                runningNode = null
                runState = RunState.Running(0, 1)
                val pid = api.queuePrompt(converter.convert(w, oi, overrides), clientId).promptId
                val t0 = System.currentTimeMillis()
                // 落盘 pending：进程被杀后凭服务器 history 恢复（失败仅损失恢复能力，不影响本次运行）。
                runCatching {
                    container.pendingRuns.insert(
                        PendingRun(pid, key, entity?.let { it.displayName ?: it.name } ?: key, t0)
                    )
                }
                launch {
                    container.events.events(clientId).collect { ev ->
                        when (ev) {
                            is ProgressEvent.Progress ->
                                if (ev.promptId == pid) {
                                    runState = RunState.Running(ev.value, ev.max)
                                    ev.nodeId?.let { runningNode = nodeDisplayName(it) }
                                }
                            is ProgressEvent.Executing ->
                                if (ev.promptId == pid) {
                                    ev.nodeId?.let { runningNode = nodeDisplayName(it) }
                                }
                            is ProgressEvent.ExecutionSuccess -> if (ev.promptId == pid) {
                                finalizeRun(pid, t0, overrides)
                                cancel()
                            }
                            is ProgressEvent.ExecutionError ->
                                if (ev.promptId == pid || ev.promptId == null) {
                                    runCatching { container.pendingRuns.delete(pid) }
                                    runState = RunState.Error(ev.message)
                                    cancel()
                                }
                            else -> {}
                        }
                    }
                }.join()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runState = RunState.Error(e.message ?: e.toString())
            }
        }
    }

    /** 运行成功收尾：capture 入库 → 更新结果区/会话列表 → 清除 pending 落盘。WS 事件与恢复轮询共用。 */
    private suspend fun finalizeRun(pid: String, t0: Long, overrides: Map<String, JsonElement>) {
        if (!finalizedPids.add(pid)) return
        val imgs = runCatching {
            api.resultImages(api.history(pid), pid)
        }.getOrDefault(emptyList())
        val dur = System.currentTimeMillis() - t0
        val ids = runCatching {
            container.results.capture(
                promptId = pid,
                workflowKey = key,
                workflowName = entity?.let { it.displayName ?: it.name } ?: key,
                images = imgs,
                inputImage = firstInputImage(),
                paramsJson = buildJsonObject {
                    overrides.forEach { (k, v) -> put(k, v) }
                }.toString(),
                durationMs = dur,
            )
        }.getOrDefault(emptyList())
        lastImages = imgs
        lastResultIds = ids
        if (ids.isNotEmpty()) {
            sessionRuns.addAll(0, ids.mapNotNull { runCatching { container.results.get(it) }.getOrNull() })
        }
        runState = RunState.Done
        container.pendingRuns.delete(pid)
    }

    /** 进程被杀后的恢复：凭落盘 pending 轮询服务器 history（服务器才是 source of truth），
     *  出结果即收尾；连续 10 分钟无果则清除并报错。与用户新发起的 run 互不干扰（不同 pid）。 */
    private fun resumePendingRuns() {
        viewModelScope.launch {
            val pendings = runCatching { container.pendingRuns.forWorkflow(key) }.getOrDefault(emptyList())
            if (pendings.isEmpty()) return@launch
            runState = RunState.Running(0, 1)
            for (p in pendings) {
                launch {
                    val deadline = System.currentTimeMillis() + 10 * 60_000L
                    while (isActive) {
                        val done = runCatching {
                            api.resultImages(api.history(p.promptId), p.promptId)
                        }.getOrDefault(emptyList()).isNotEmpty()
                        if (done) {
                            // 恢复场景没有表单值快照，paramsJson 存空对象。
                            finalizeRun(p.promptId, p.startedAt, emptyMap())
                            return@launch
                        }
                        if (System.currentTimeMillis() > deadline) {
                            finalizedPids.add(p.promptId) // 超时后即使 WS 事件迟到也不再收尾
                            runCatching { container.pendingRuns.delete(p.promptId) }
                            runState = RunState.Error("服务器无此任务记录")
                            return@launch
                        }
                        delay(2500)
                    }
                }
            }
        }
    }

    /** 第一个暴露的 imageUpload widget 的当前值 → 服务器 input 引用（供结果缓存 i2i 对比）。 */
    private suspend fun firstInputImage(): ResultImage? {
        val list = container.exposed.exposed(key).first().sortedBy { it.sortOrder }
        for (x in list) {
            val r = resolved(x.nodeId, x.widgetName) ?: continue
            if (r.def.imageUpload) {
                values["${x.nodeId}:${x.widgetName}"].strOrEmpty().takeIf { it.isNotEmpty() }
                    ?.let { return ResultImage(it, "", "input", "") }
            }
        }
        return null
    }

    fun interrupt() {
        viewModelScope.launch { runCatching { api.interrupt() } }
    }

    fun dismissRun() {
        runState = RunState.Idle
    }

    /** 当前表单值快照（不含 randomSeeds 随机化，存用户看到的原值）。 */
    private fun valuesJson(): String =
        buildJsonObject { values.forEach { (k, v) -> put(k, v) } }.toString()

    /** 另存为：复制到新 key 并把当前表单值写为其默认参数；回调返回新 key 供导航。 */
    fun duplicateWithParams(onNewKey: (String) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val newKey = container.workflows.duplicate(key) ?: error("工作流不存在")
                container.workflows.saveDefaults(newKey, valuesJson())
                newKey
            }.fold(
                onSuccess = onNewKey,
                onFailure = { message = "另存失败：${it.message}" },
            )
        }
    }

    /** 保存当前参数为本工作流的默认值；复用现有 message → Snackbar 轻提示。 */
    fun saveDefaults() {
        viewModelScope.launch {
            runCatching { container.workflows.saveDefaults(key, valuesJson()) }
                .onSuccess { message = "已保存为默认参数" }
                .onFailure { message = "保存失败：${it.message}" }
        }
    }
}

// ---- value helpers ----

private fun JsonElement?.strOrEmpty(): String =
    this?.let { runCatching { asString() }.getOrNull() } ?: ""

private fun fmtNum(d: Double, isInt: Boolean): String =
    if (isInt || d == Math.floor(d)) d.toLong().toString() else d.toString()

private fun numOf(d: Double, isInt: Boolean): JsonElement =
    if (isInt) JsonPrimitive(d.toLong()) else JsonPrimitive(d)

private fun snap(d: Double, step: Double?, min: Double, max: Double): Double {
    var x = d
    if (step != null && step > 0) x = min + Math.round((d - min) / step) * step
    return x.coerceIn(min, max)
}

private fun previewValue(v: JsonElement?): String {
    if (v == null) return "—"
    val s = runCatching { v.asString() }.getOrElse { v.toString() }
    return s.ifEmpty { "—" }.take(40)
}

private fun WidgetDef.typeLabel(): String = when {
    kind == WidgetKind.INT -> "int"
    kind == WidgetKind.FLOAT -> "float"
    kind == WidgetKind.STRING -> "string"
    kind == WidgetKind.BOOLEAN -> "bool"
    kind == WidgetKind.COMBO && imageUpload -> "image"
    kind == WidgetKind.COMBO -> "combo"
    else -> "input"
}

// ---- screen ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowDetailScreen(container: AppContainer, nav: NavHostController, key: String, fromResultId: Long = -1L) {
    val context = LocalContext.current
    val seedFlags = remember { context.getSharedPreferences("seed_flags", Context.MODE_PRIVATE) }
    val vm: WorkflowDetailViewModel = viewModel(
        factory = viewModelFactory {
            initializer { WorkflowDetailViewModel(container, key, seedFlags, fromResultId) }
        },
    )
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }

    fun doSave(img: ResultImage) {
        scope.launch {
            val err = saveToGallery(context, vm.api.viewUrl(img))
            snackbar.showSnackbar(err ?: context.getString(R.string.detail_saved))
        }
    }

    var pending by remember { mutableStateOf<ResultImage?>(null) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val p = pending
        pending = null
        if (granted && p != null) doSave(p)
        else if (!granted) {
            scope.launch { snackbar.showSnackbar(context.getString(R.string.detail_save_need_permission)) }
        }
    }

    fun save(img: ResultImage) {
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pending = img
            permLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            doSave(img)
        }
    }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
                title = { Text(vm.entity?.let { it.displayName ?: it.name } ?: stringResource(R.string.detail_title_fallback)) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.detail_back))
                    }
                },
                actions = {
                    IconButton(onClick = { nav.navigate("edit/$key") }) {
                        Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.detail_edit))
                    }
                    var menuOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.detail_more))
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            containerColor = Raised,
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.detail_save_as_new), style = ComfyType.Body) },
                                onClick = {
                                    menuOpen = false
                                    vm.duplicateWithParams { newKey -> nav.navigate("workflow/$newKey") }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.detail_save_defaults), style = ComfyType.Body) },
                                onClick = {
                                    menuOpen = false
                                    vm.saveDefaults()
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = { RunBar(vm) },
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                Snackbar(
                    data,
                    containerColor = Raised,
                    contentColor = Fg,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.border(1.dp, Border, RoundedCornerShape(12.dp)),
                )
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            DetailTabs(tab) { tab = it }
            when (tab) {
                0 -> UsageTab(vm, onSave = ::save, onOpenResult = { id -> nav.navigate("viewer/$id") }, onOpenAll = { nav.navigate("results") })
                else -> NodesTab(vm)
            }
        }
    }

    val st = vm.runState
    if (st is RunState.Error) {
        RunFailedDialog(st.message) { vm.dismissRun() }
    }
}

@Composable
private fun DetailTabs(tab: Int, onSelect: (Int) -> Unit) {
    TabRow(
        selectedTabIndex = tab,
        containerColor = Bg,
        contentColor = Fg,
        divider = {},
        indicator = { positions ->
            if (tab < positions.size) {
                val pos = positions[tab]
                val left by animateDpAsState(pos.left, tween(200), label = "tabLeft")
                val width by animateDpAsState(pos.width, tween(200), label = "tabWidth")
                Box(
                    Modifier
                        .fillMaxWidth()
                        .wrapContentSize(Alignment.BottomStart)
                        .offset(x = left)
                        .width(width)
                        .height(2.dp)
                        .background(Accent),
                )
            }
        },
    ) {
        for (i in 0..1) {
            val label = stringResource(if (i == 0) R.string.detail_tab_usage else R.string.detail_tab_nodes)
            val color by animateColorAsState(if (tab == i) Fg else Muted, tween(200), label = "tabColor$i")
            Tab(selected = tab == i, onClick = { onSelect(i) }, text = { Text(label, style = ComfyType.BodyStrong, color = color) })
        }
    }
}

// ---- 使用 tab ----

@Composable
private fun UsageTab(vm: WorkflowDetailViewModel, onSave: (ResultImage) -> Unit, onOpenResult: (Long) -> Unit, onOpenAll: () -> Unit) {
    val exposed by vm.exposed.collectAsStateWithLifecycle()
    if (vm.wf == null || vm.info == null) {
        LoadingOrError(vm)
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        val sorted = exposed.sortedBy { it.sortOrder }
        if (sorted.isEmpty()) {
            item(key = "empty") {
                Column(Modifier.padding(top = 32.dp)) {
                    Text(stringResource(R.string.detail_empty_title), style = ComfyType.TitleCard, color = Fg)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.detail_empty_hint), style = ComfyType.Caption, color = Muted)
                }
            }
        }
        items(sorted, key = { "${it.nodeId}:${it.widgetName}" }) { x -> ExposedRow(vm, x) }

        val running = vm.runState is RunState.Running
        val images = vm.lastImages
        if (running || images.isNotEmpty()) {
            item(key = "results") {
                ResultsSection(vm, running, images, onSave, onOpenResult, onOpenAll)
            }
        }
    }
}

@Composable
private fun LoadingOrError(vm: WorkflowDetailViewModel) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (vm.infoError != null) {
            Text(vm.infoError!!, style = ComfyType.Body, color = Error, modifier = Modifier.padding(32.dp))
        } else {
            CircularProgressIndicator(Modifier.padding(32.dp), color = Accent)
        }
    }
}

@Composable
private fun ExposedRow(vm: WorkflowDetailViewModel, x: ExposedWidget) {
    val r = vm.resolved(x.nodeId, x.widgetName) ?: return
    val label = x.label.ifBlank { x.widgetName }
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = ComfyType.Body, color = Fg)
        Text(vm.nodeOrigin(x.nodeId), style = ComfyType.MonoMeta, color = Muted)
        Spacer(Modifier.height(8.dp))
        WidgetControl(vm, "${x.nodeId}:${x.widgetName}", r.def, label)
    }
}

@Composable
private fun WidgetControl(vm: WorkflowDetailViewModel, k: String, def: WidgetDef, label: String) {
    when {
        def.controlAfterGenerate -> SeedControl(vm, k, def)
        def.kind == WidgetKind.INT || def.kind == WidgetKind.FLOAT ->
            if (def.min != null && def.max != null && def.max > def.min) {
                RangedNumControl(vm, k, def)
            } else {
                PlainNumControl(vm, k, def)
            }
        def.kind == WidgetKind.STRING -> StringControl(vm, k, def)
        def.kind == WidgetKind.BOOLEAN -> BoolControl(vm, k, def, label)
        def.kind == WidgetKind.COMBO && def.imageUpload -> ImageControl(vm, k, def)
        def.kind == WidgetKind.COMBO -> ComboControl(vm, k, def)
        else -> {}
    }
}

@Composable
private fun comfySwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = OnAccent,
    checkedTrackColor = Accent,
    checkedBorderColor = Accent,
    uncheckedThumbColor = Muted,
    uncheckedTrackColor = Border,
    uncheckedBorderColor = Border,
)

@Composable
private fun RangedNumControl(vm: WorkflowDetailViewModel, k: String, def: WidgetDef) {
    val isInt = def.kind == WidgetKind.INT
    val min = def.min ?: 0.0
    val max = def.max ?: 1.0
    val haptic = LocalHapticFeedback.current
    val v = (vm.values[k]?.asDoubleOrNull() ?: def.default?.asDoubleOrNull() ?: min).coerceIn(min, max)
    var text by remember { mutableStateOf(fmtNum(v, isInt)) }
    LaunchedEffect(v) {
        val t = text.toDoubleOrNull()
        if (t == null || t != v) text = fmtNum(v, isInt)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = v.toFloat(),
            onValueChange = { f ->
                val d = snap(f.toDouble(), def.step, min, max)
                if (d != v) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                vm.values[k] = numOf(d, isInt)
                text = fmtNum(d, isInt)
            },
            valueRange = min.toFloat()..max.toFloat(),
            steps = def.step?.takeIf { it > 0 }
                ?.let { ((max - min) / it).toInt() - 1 }?.coerceAtLeast(0) ?: 0,
            colors = SliderDefaults.colors(
                thumbColor = Fg,
                activeTrackColor = Accent,
                inactiveTrackColor = Border,
            ),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = text,
            onValueChange = { t ->
                text = t
                t.toDoubleOrNull()?.let { vm.values[k] = numOf(it.coerceIn(min, max), isInt) }
            },
            modifier = Modifier.width(96.dp),
            textStyle = ComfyType.MonoValue,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (isInt) KeyboardType.Number else KeyboardType.Decimal,
            ),
        )
    }
}

@Composable
private fun PlainNumControl(vm: WorkflowDetailViewModel, k: String, def: WidgetDef) {
    val isInt = def.kind == WidgetKind.INT
    val v = vm.values[k]?.asDoubleOrNull() ?: def.default?.asDoubleOrNull() ?: 0.0
    var text by remember { mutableStateOf(fmtNum(v, isInt)) }
    LaunchedEffect(v) { if (text.toDoubleOrNull() != v) text = fmtNum(v, isInt) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            t.toDoubleOrNull()?.let { vm.values[k] = numOf(it, isInt) }
        },
        modifier = Modifier.fillMaxWidth(),
        textStyle = ComfyType.MonoValue,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isInt) KeyboardType.Number else KeyboardType.Decimal,
        ),
    )
}

@Composable
private fun StringControl(vm: WorkflowDetailViewModel, k: String, def: WidgetDef) {
    OutlinedTextField(
        value = vm.values[k].strOrEmpty(),
        onValueChange = { vm.values[k] = JsonPrimitive(it) },
        modifier = Modifier.fillMaxWidth(),
        minLines = if (def.multiline) 3 else 1,
    )
}

@Composable
private fun BoolControl(vm: WorkflowDetailViewModel, k: String, def: WidgetDef, label: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = ComfyType.Body, color = Fg, modifier = Modifier.weight(1f))
        Switch(
            checked = vm.values[k]?.asBooleanOrNull() ?: def.default?.asBooleanOrNull() ?: false,
            onCheckedChange = { vm.values[k] = JsonPrimitive(it) },
            colors = comfySwitchColors(),
        )
    }
}

@Composable
private fun SeedControl(vm: WorkflowDetailViewModel, k: String, def: WidgetDef) {
    val v = vm.values[k]?.asDoubleOrNull() ?: def.default?.asDoubleOrNull() ?: 0.0
    var text by remember { mutableStateOf(v.toLong().toString()) }
    LaunchedEffect(v) { if (text.toDoubleOrNull() != v) text = v.toLong().toString() }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { t ->
                text = t
                t.toDoubleOrNull()?.let { vm.values[k] = JsonPrimitive(it.toLong()) }
            },
            modifier = Modifier.weight(1f),
            textStyle = ComfyType.MonoValue,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Text(
            stringResource(R.string.detail_random),
            modifier = Modifier.padding(start = 12.dp, end = 4.dp),
            style = ComfyType.Body,
            color = Fg,
        )
        Switch(
            checked = vm.randomSeeds[k] == true,
            onCheckedChange = { vm.randomSeeds[k] = it },
            colors = comfySwitchColors(),
        )
        IconButton(onClick = {
            vm.randomSeeds[k] = false
            vm.values[k] = JsonPrimitive(Random.nextLong(0L, 1L shl 48))
        }) {
            Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.detail_randomize_once), tint = Fg)
        }
    }
}

@Composable
private fun ImageControl(vm: WorkflowDetailViewModel, k: String, def: WidgetDef) {
    val context = LocalContext.current
    // SAF OpenDocument：系统 DocumentsUI 能列出 MediaStore 索引的所有图片类型（含 webp），
    // Photo Picker 的 PickVisualMedia 不显示 webp。无需存储权限。
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            vm.upload(context.contentResolver, uri, k)
        }
    }
    var menu by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GhostButton(stringResource(R.string.detail_pick_image), Modifier.weight(1f)) {
                pick.launch(arrayOf("image/*"))
            }
            if (def.options.isNotEmpty()) {
                Box(Modifier.weight(1f)) {
                    GhostButton(stringResource(R.string.detail_pick_from_server), Modifier.fillMaxWidth()) { menu = true }
                    DropdownMenu(
                        expanded = menu,
                        onDismissRequest = { menu = false },
                        containerColor = Raised,
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        def.options.forEach { o ->
                            DropdownMenuItem(
                                text = { Text(o, style = ComfyType.Body) },
                                onClick = {
                                    vm.values[k] = JsonPrimitive(o)
                                    menu = false
                                },
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val cur = vm.values[k].strOrEmpty()
        Text(
            if (cur.isEmpty()) stringResource(R.string.detail_current_none)
            else stringResource(R.string.detail_current, cur),
            style = ComfyType.MonoMeta,
            color = Muted,
        )
        if (cur.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            AsyncImage(
                model = vm.api.viewUrl(ResultImage(cur, "", "input", "")),
                contentDescription = cur,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(Surface),
                contentScale = ContentScale.Crop,
                placeholder = ColorPainter(Surface),
                error = ColorPainter(Surface),
            )
        }
    }
}

@Composable
private fun ComboControl(vm: WorkflowDetailViewModel, k: String, def: WidgetDef) {
    var menu by remember { mutableStateOf(false) }
    val cur = vm.values[k].strOrEmpty()
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { menu = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = Fg),
            border = BorderStroke(1.dp, Border),
        ) {
            Text(
                cur.ifEmpty { stringResource(R.string.detail_combo_placeholder) },
                style = ComfyType.BodyStrong,
                color = if (cur.isEmpty()) Muted else Fg,
            )
        }
        DropdownMenu(
            expanded = menu,
            onDismissRequest = { menu = false },
            containerColor = Raised,
            shape = RoundedCornerShape(20.dp),
        ) {
            def.options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(o, style = ComfyType.Body) },
                    onClick = {
                        vm.values[k] = JsonPrimitive(o)
                        menu = false
                    },
                )
            }
        }
    }
}

// ---- 结果区 ----

@Composable
private fun ResultsSection(
    vm: WorkflowDetailViewModel,
    running: Boolean,
    images: List<ResultImage>,
    onSave: (ResultImage) -> Unit,
    onOpenResult: (Long) -> Unit,
    onOpenAll: () -> Unit,
) {
    // 同 promptId 为一次运行；groupBy 保序（最新在前），reversed 后仍最新在前
    val runs = vm.sessionRuns.groupBy { it.promptId }.values.toList()
    val timeFmt = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.detail_results), style = ComfyType.Body, color = Fg)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.detail_session_runs, runs.size),
                style = ComfyType.MonoMeta,
                color = Muted,
            )
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.detail_all_results),
                style = ComfyType.BodyStrong,
                color = Accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenAll)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (running) {
                item(key = "running") { ResultPlaceholder(Modifier.width(150.dp)) }
            }
            items(runs, key = { it.first().promptId }) { run ->
                RunCard(
                    file = vm.resultsRepo.fileOf(run.first()),
                    extra = run.size - 1,
                    time = timeFmt.format(java.util.Date(run.first().createdAt)),
                    onClick = { onOpenResult(run.first().id) },
                )
            }
        }
    }
}

@Composable
private fun RunCard(file: java.io.File, extra: Int, time: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .width(150.dp)
            .aspectRatio(1f)
            .clip(shape)
            .border(1.dp, Border, shape)
            .background(Surface)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = file,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            time,
            style = ComfyType.MonoMeta,
            color = Fg,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .background(Scrim62, RoundedCornerShape(5.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        if (extra > 0) {
            Text(
                "+$extra",
                style = ComfyType.MonoMeta,
                color = OnAccent,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(Accent, RoundedCornerShape(5.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** 45° 斜纹占位格（radius-card、虚线占位直至 /history 返回）。 */
@Composable
private fun ResultPlaceholder(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(Surface)
            .resultStripes()
            .border(1.dp, Border, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.detail_result_placeholder), style = ComfyType.MonoMeta, color = Muted)
    }
}

private fun Modifier.resultStripes(): Modifier = drawBehind {
    val step = 10.dp.toPx()
    var x = -size.height
    while (x < size.width) {
        drawLine(
            Border.copy(alpha = 0.45f),
            Offset(x, size.height),
            Offset(x + size.height, 0f),
            strokeWidth = 1.dp.toPx(),
        )
        x += step
    }
}


/** meta 条半透明黑底（token: scrim 62%），见上方保存按钮。 */

// ---- 运行条 ----

@Composable
private fun RunBar(vm: WorkflowDetailViewModel) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .shadow(12.dp, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
            .background(Raised, shape)
            .border(1.dp, Border, shape)
            .padding(12.dp),
    ) {
        val st = vm.runState
        when (st) {
            is RunState.Done -> DoneBadge(vm)
            is RunState.Running -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = shape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Accent,
                            contentColor = OnAccent,
                            disabledContainerColor = Accent.copy(alpha = 0.5f),
                            disabledContentColor = OnAccent,
                        ),
                    ) {
                        PlayTriangle()
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.detail_running), style = RunCta)
                    }
                    GhostButton(stringResource(R.string.detail_cancel)) { vm.interrupt() }
                }
                Spacer(Modifier.height(10.dp))
                FlowProgress(if (st.max > 0) st.value.toFloat() / st.max else 0f)
                Spacer(Modifier.height(6.dp))
                val nodePart = vm.runningNode?.let { " · $it" } ?: ""
                Text(
                    stringResource(R.string.detail_progress, st.value, st.max, nodePart),
                    style = ComfyType.MonoMeta,
                    color = Muted,
                )
            }
            else -> {
                Button(
                    onClick = { vm.run() },
                    enabled = vm.wf != null && vm.info != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = shape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent,
                        contentColor = OnAccent,
                        disabledContainerColor = Accent.copy(alpha = 0.38f),
                        disabledContentColor = OnAccent,
                    ),
                ) {
                    PlayTriangle()
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.detail_run), style = RunCta)
                }
            }
        }
    }
}

/** 10×12dp 实心播放三角（自定义图标 per tokens §4）。 */
@Composable
private fun PlayTriangle() {
    Box(
        Modifier
            .size(10.dp, 12.dp)
            .drawBehind {
                val p = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, size.height / 2f)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(p, OnAccent)
            },
    )
}

/** accent 填充 + 其上流动虚线（1.2s linear 无限），轨道 border 色，高 3dp。 */
@Composable
private fun FlowProgress(frac: Float) {
    val phase by rememberInfiniteTransition(label = "flow").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "flowPhase",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(1.5.dp))
            .background(Border)
            .drawBehind {
                val w = size.width * frac.coerceIn(0f, 1f)
                if (w > 0f) {
                    drawRect(Accent, size = Size(w, size.height))
                    val periodPx = 16.dp.toPx()
                    val dashPx = 6.dp.toPx()
                    var x = -phase * periodPx
                    while (x < w) {
                        drawRect(
                            Bg.copy(alpha = 0.55f),
                            topLeft = Offset(x, 0f),
                            size = Size(dashPx, size.height),
                        )
                        x += periodPx
                    }
                }
            },
    )
}

@Composable
private fun DoneBadge(vm: WorkflowDetailViewModel) {
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(Unit) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        delay(400)
        vm.dismissRun()
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Success, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.detail_done), style = RunCta, color = Success)
    }
}

@Composable
private fun RunFailedDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        shape = RoundedCornerShape(20.dp),
        title = { Text(stringResource(R.string.detail_run_failed), style = ComfyType.TitleCard, color = Fg) },
        text = {
            Box(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                SelectionContainer { Text(message, style = MonoError, color = Fg) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.detail_close), style = ComfyType.BodyStrong, color = Accent)
            }
        },
    )
}

@Composable
private fun GhostButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = Fg),
        border = BorderStroke(1.dp, Border),
    ) {
        Text(text, style = ComfyType.BodyStrong)
    }
}

// ---- 节点 tab ----

@Composable
private fun NodesTab(vm: WorkflowDetailViewModel) {
    val exposed by vm.exposed.collectAsStateWithLifecycle()
    val wf = vm.wf
    if (wf == null || vm.info == null) {
        LoadingOrError(vm)
        return
    }
    val active = wf.nodes.filter { it.mode == 0 }.sortedBy { it.id }
    val mutedCount = wf.nodes.count { it.mode != 0 }
    val grouped = active.size > 8
    var query by remember { mutableStateOf("") }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    val filtered = if (query.isBlank()) active
    else active.filter { n -> n.type.contains(query, true) || n.id.toString().contains(query) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (mutedCount > 0) {
            item(key = "banner") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Surface)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.Info, contentDescription = null, tint = Warning, modifier = Modifier.size(16.dp))
                    Text(stringResource(R.string.detail_nodes_disabled, mutedCount), style = ComfyType.Body, color = Warning)
                }
            }
        }
        if (grouped) {
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.detail_search_nodes), style = ComfyType.Body, color = Muted) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    textStyle = ComfyType.Body,
                )
            }
            filtered.groupBy { it.type }.forEach { (type, nodes) ->
                val isCollapsed = collapsed[type] == true
                item(key = "h:$type") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { collapsed[type] = !isCollapsed },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("$type · ${nodes.size}", style = ComfyType.MonoMeta, color = Muted, modifier = Modifier.weight(1f))
                        Icon(
                            if (isCollapsed) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowUp,
                            contentDescription = null,
                            tint = Muted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (!isCollapsed) {
                    items(nodes, key = { it.id }) { n -> NodeCard(vm, n, exposed) }
                }
            }
        } else {
            items(filtered, key = { it.id }) { n -> NodeCard(vm, n, exposed) }
        }
    }
}

@Composable
private fun NodeCard(vm: WorkflowDetailViewModel, n: WfNode, exposed: List<ExposedWidget>) {
    val resolved = remember(n.id) { vm.resolveAll(n) }
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Surface)
            .border(1.dp, Border, shape)
            .padding(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(n.type, style = ComfyType.TitleCard, color = Fg, modifier = Modifier.weight(1f))
                Text("#${n.id}", style = ComfyType.MonoMeta, color = Muted)
            }
            resolved.forEach { r -> WidgetRow(vm, n, r, exposed) }
            if (resolved.isEmpty()) {
                Text(
                    stringResource(R.string.detail_no_widgets),
                    style = ComfyType.Caption,
                    color = Muted,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun WidgetRow(vm: WorkflowDetailViewModel, n: WfNode, r: ResolvedWidget, exposed: List<ExposedWidget>) {
    val linked = n.inputs.firstOrNull { it.widget?.name == r.def.name }?.link != null
    val checked = exposed.any { it.nodeId == n.id.toString() && it.widgetName == r.def.name }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .alpha(if (linked) 0.38f else 1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = !linked) { vm.toggleExposed(n.id.toString(), r.def.name, !checked) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NodeCheckbox(checked = checked)
            Text(r.def.name, style = ComfyType.Body, color = Fg)
            Text(
                previewValue(r.value),
                style = ComfyType.MonoMeta,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
            Text(r.def.typeLabel(), style = ComfyType.MonoMeta, color = Muted)
        }
        if (linked) {
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .border(1.dp, Border, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(stringResource(R.string.detail_connected), style = ComfyType.Caption, color = Muted)
            }
        }
    }
}

/** 18dp 圆角 4 自定义勾选框；选中 = accent 底 + on-accent 勾线。 */
@Composable
private fun NodeCheckbox(checked: Boolean) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .size(18.dp)
            .clip(shape)
            .let { if (checked) it.background(Accent) else it.border(1.5.dp, Border, shape) }
            .drawBehind {
                if (checked) {
                    val p = Path().apply {
                        moveTo(size.width * 0.18f, size.height * 0.55f)
                        lineTo(size.width * 0.42f, size.height * 0.8f)
                        lineTo(size.width * 0.85f, size.height * 0.22f)
                    }
                    drawPath(p, OnAccent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                }
            },
    )
}

/** Saves via /view GET + MediaStore. Returns error message or null on success. */
private suspend fun saveToGallery(context: Context, url: String): String? =
    withContext(Dispatchers.IO) {
        runCatching {
            val ext = when {
                url.contains(".jpg", true) || url.contains(".jpeg", true) -> "jpg"
                url.contains(".webp", true) -> "webp"
                else -> "png"
            }
            val mime = "image/$ext"
            val bytes = URL(url).openStream().use { it.readBytes() }
            val name = "comfy_${System.currentTimeMillis()}.$ext"
            val cr = context.contentResolver
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ComfyLAN")
                }
                val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                    ?: error(context.getString(R.string.detail_mediastore_insert_failed))
                cr.openOutputStream(uri)!!.use { it.write(bytes) }
            } else {
                val dir = java.io.File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "ComfyLAN",
                ).apply { mkdirs() }
                val f = java.io.File(dir, name)
                f.writeBytes(bytes)
                MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), arrayOf(mime), null)
            }
            null
        }.getOrElse { context.getString(R.string.detail_save_failed, it.message ?: it.toString()) }
    }
