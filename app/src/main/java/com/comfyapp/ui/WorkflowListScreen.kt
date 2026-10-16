package com.comfyapp.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.comfyapp.AppContainer
import com.comfyapp.R
import com.comfyapp.api.ServerWorkflow
import com.comfyapp.data.WorkflowEntity
import com.comfyapp.model.Workflow
import com.comfyapp.ui.ServerPulse
import com.comfyapp.ui.PulseDot
import com.comfyapp.ui.theme.Accent
import com.comfyapp.ui.theme.Border
import com.comfyapp.ui.theme.Bg
import com.comfyapp.ui.theme.ComfyType
import com.comfyapp.ui.theme.Error
import com.comfyapp.ui.theme.Fg
import com.comfyapp.ui.theme.Muted
import com.comfyapp.ui.theme.OnAccent
import com.comfyapp.ui.theme.Raised
import com.comfyapp.ui.theme.Success
import com.comfyapp.ui.theme.Surface
import com.comfyapp.ui.theme.Warning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Source { LOCAL, SERVER }

/** 一次性 Snackbar 事件；中文文案在 UI 层用 strings_library.xml 拼装。 */
sealed interface Snack {
    data class Imported(val key: String) : Snack
    data class Fail(val read: Boolean, val detail: String?) : Snack
}

class WorkflowListViewModel(private val container: AppContainer) : ViewModel() {
    var snack by mutableStateOf<Snack?>(null)

    val workflows = container.workflows.list()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** key -> (活跃节点数, 参数数)；列表每次变更整体解析一次。 */
    val stats: StateFlow<Map<String, Pair<Int, Int>>> = container.workflows.list()
        .map { list ->
            list.associate { e ->
                e.key to runCatching { parseStats(e.json) }.getOrDefault(0 to 0)
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private fun parseStats(json: String): Pair<Int, Int> {
        val wf = container.json.decodeFromString(Workflow.serializer(), json)
        val active = wf.nodes.filter { it.mode == 0 }
        return active.size to active.sumOf {
            (it.widgetsValuesNamed?.size)
                ?: ((it.widgetsValues as? kotlinx.serialization.json.JsonArray)?.size ?: 0)
        }
    }

    // ---- 服务器分区 ----
    var serverHidden by mutableStateOf(false)   // api.serverWorkflows() = null → 服务器太旧
    var serverLoading by mutableStateOf(false)
    var serverError by mutableStateOf(false)
    var serverLoaded by mutableStateOf(false)
    var serverList by mutableStateOf<List<ServerWorkflow>>(emptyList())

    fun loadServer() {
        if (serverHidden || serverLoading || (serverLoaded && !serverError)) return
        viewModelScope.launch {
            serverLoading = true
            serverError = false
            try {
                when (val r = container.api.serverWorkflows()) {
                    null -> serverHidden = true
                    else -> {
                        serverList = r
                        serverLoaded = true
                    }
                }
            } catch (e: Exception) {
                serverError = true
            } finally {
                serverLoading = false
            }
        }
    }

    fun importServer(path: String) {
        viewModelScope.launch {
            runCatching {
                val name = path.substringAfterLast('/').removeSuffix(".json")
                container.workflows.import(name, container.api.userdataFile("workflows/$path"))
            }.fold(
                onSuccess = { snack = Snack.Imported(it) },
                onFailure = { snack = Snack.Fail(read = false, detail = it.message) },
            )
        }
    }

    fun import(name: String, json: String) {
        viewModelScope.launch {
            runCatching { container.workflows.import(name, json) }
                .fold(
                    onSuccess = { snack = Snack.Imported(it) },
                    onFailure = { snack = Snack.Fail(read = false, detail = it.message) },
                )
        }
    }

    // ---- 复制：衍生工作流（行+暴露配置+参数默认值），列表 Flow 自动刷新 ----
    fun duplicate(key: String) {
        viewModelScope.launch { runCatching { container.workflows.duplicate(key) } }
    }

    // ---- 软删除：先出列表，Snackbar 撤销期过后才落库 ----
    var pendingDelete by mutableStateOf<WorkflowEntity?>(null)

    fun requestDelete(e: WorkflowEntity) {
        if (pendingDelete != null) confirmDelete() // 上一条撤销期未结束：先落实
        pendingDelete = e
    }

    fun undoDelete() {
        pendingDelete = null
    }

    fun confirmDelete() {
        val e = pendingDelete ?: return
        pendingDelete = null
        viewModelScope.launch { container.workflows.delete(e.key) }
    }
}

/** Same package: also used by WorkflowDetailScreen for image upload names. */
internal fun queryDisplayName(cr: ContentResolver, uri: Uri): String? = runCatching {
    cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
    }
}.getOrNull()

private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun fmtDate(modified: Long): String =
    dateFmt.format(Date(if (modified > 1_000_000_000_000L) modified else modified * 1000))

private fun fmtSize(bytes: Long): String = when {
    bytes >= 1 shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576f)
    bytes >= 1 shl 10 -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
    else -> "$bytes B"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowListScreen(container: AppContainer, nav: NavHostController, modifier: Modifier = Modifier) {
    val vm: WorkflowListViewModel = viewModel(
        factory = viewModelFactory { initializer { WorkflowListViewModel(container) } },
    )
    val workflows by vm.workflows.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val serverUrl by container.settings.serverUrl.collectAsStateWithLifecycle(initialValue = null)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val snackOk = remember { mutableStateOf(false) }
    var source by remember { mutableStateOf(Source.LOCAL) }

    val localList = workflows.filter { it.key != vm.pendingDelete?.key }
    val undoLabel = stringResource(R.string.library_undo)

    LaunchedEffect(vm.snack) {
        when (val s = vm.snack) {
            is Snack.Imported -> {
                snackOk.value = true
                val r = snackbar.showSnackbar(
                    context.getString(R.string.library_imported),
                    actionLabel = context.getString(R.string.library_open),
                    duration = SnackbarDuration.Short,
                )
                if (r == SnackbarResult.ActionPerformed) nav.navigate("workflow/${s.key}")
            }
            is Snack.Fail -> {
                snackOk.value = false
                val tpl = if (s.read) R.string.library_read_failed else R.string.library_import_failed
                snackbar.showSnackbar(context.getString(tpl, s.detail ?: ""), duration = SnackbarDuration.Short)
            }
            null -> return@LaunchedEffect
        }
        vm.snack = null
    }

    fun onDelete(e: WorkflowEntity) = scope.launch {
        vm.requestDelete(e)
        snackOk.value = true
        val result = withTimeoutOrNull(5_000) {
            snackbar.showSnackbar(
                context.getString(R.string.library_deleted),
                actionLabel = undoLabel,
                duration = SnackbarDuration.Indefinite,
            )
        }
        if (result == SnackbarResult.ActionPerformed) {
            vm.undoDelete()
        } else {
            // 5s 到点：showSnackbar 被取消但撤销条还挂着，收掉再落库
            snackbar.currentSnackbarData?.takeIf { it.visuals.actionLabel == undoLabel }?.dismiss()
            vm.confirmDelete()
        }
    }

    var renaming by remember { mutableStateOf<WorkflowEntity?>(null) }
    var renameText by remember(renaming) { mutableStateOf(renaming?.displayName ?: renaming?.name ?: "") }
    renaming?.let { target ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = Surface,
            shape = RoundedCornerShape(20.dp),
            title = { Text(stringResource(R.string.library_rename_title), style = ComfyType.TitleCard) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    textStyle = ComfyType.Body,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = renameText.trim()
                    if (n.isNotEmpty()) scope.launch {
                        container.workflows.rename(target.key, n)
                    }
                    renaming = null
                }) { Text(stringResource(R.string.library_rename_ok), color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) {
                    Text(stringResource(R.string.library_rename_cancel), color = Muted)
                }
            },
        )
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: error("无法读取文件")
                        val name = queryDisplayName(context.contentResolver, uri) ?: "workflow.json"
                        name to bytes
                    }
                }.fold(
                    onSuccess = { (name, bytes) -> vm.import(name, bytes.toString(Charsets.UTF_8)) },
                    onFailure = { vm.snack = Snack.Fail(read = true, detail = it.message) },
                )
            }
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PulseDot(ServerPulse.value)
                        Column {
                            Text(
                                stringResource(R.string.library_title),
                                style = ComfyType.TitleApp,
                                color = Fg,
                            )
                            Text(
                                serverUrl ?: stringResource(R.string.library_not_connected),
                                style = ComfyType.MonoMeta,
                                color = Muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { nav.navigate("canvas") }) {
                        Icon(
                            Icons.Rounded.DesktopWindows,
                            contentDescription = stringResource(R.string.library_canvas_cd),
                            tint = Fg,
                        )
                    }
                    IconButton(onClick = { nav.navigate("setup") }) {
                        Icon(
                            Icons.Rounded.Settings,
                            contentDescription = stringResource(R.string.library_settings_cd),
                            tint = Fg,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = Fg),
            )
        },
        snackbarHost = { ComfySnackbarHost(snackbar, snackOk.value) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { pick.launch(arrayOf("application/json")) },
                shape = RoundedCornerShape(16.dp),
                containerColor = Accent,
                contentColor = OnAccent,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.library_fab_cd))
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PillChip(
                    text = stringResource(R.string.library_local) + " · ${localList.size}",
                    style = ComfyType.Body,
                    contentColor = Muted,
                    selected = source == Source.LOCAL,
                    onClick = { source = Source.LOCAL },
                )
                if (!vm.serverHidden) {
                    PillChip(
                        text = when {
                            vm.serverLoading -> stringResource(R.string.library_server_loading)
                            vm.serverLoaded -> stringResource(R.string.library_server) + " · ${vm.serverList.size}"
                            else -> stringResource(R.string.library_server)
                        },
                        style = ComfyType.Body,
                        contentColor = Muted,
                        selected = source == Source.SERVER,
                        onClick = {
                            source = Source.SERVER
                            vm.loadServer() // 首次点选拉取；失败态再点即重试
                        },
                        trailing = if (vm.serverError) {
                            {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Warning),
                                )
                            }
                        } else null,
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    source == Source.LOCAL && localList.isEmpty() -> item {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                            ) {
                                Text(
                                    stringResource(R.string.library_empty_title),
                                    style = ComfyType.TitleApp,
                                    color = Fg,
                                )
                                Text(
                                    stringResource(R.string.library_empty_hint),
                                    style = ComfyType.Caption,
                                    color = Muted,
                                )
                                Spacer(Modifier.height(4.dp))
                                GhostButton(stringResource(R.string.library_go_canvas)) {
                                    nav.navigate("canvas")
                                }
                            }
                        }
                    }
                    source == Source.SERVER && vm.serverLoaded && vm.serverList.isEmpty() -> item {
                        Text(
                            stringResource(R.string.library_server_empty),
                            style = ComfyType.Caption,
                            color = Muted,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 24.dp),
                        )
                    }
                    source == Source.LOCAL -> items(localList, key = { it.key }) { e ->
                        val s = stats[e.key] ?: (0 to 0)
                        WfCard(
                            name = e.displayName ?: e.name,
                            meta = stringResource(
                                R.string.library_meta, s.first, s.second,
                                dateFmt.format(Date(e.updatedAt)),
                            ),
                            onClick = { nav.navigate("workflow/${e.key}") },
                        ) {
                            IconButton(onClick = { renaming = e }, modifier = Modifier.size(48.dp)) {
                                Icon(
                                    Icons.Rounded.Edit,
                                    contentDescription = stringResource(R.string.library_rename_cd),
                                    tint = Muted,
                                    modifier = Modifier.size(19.dp),
                                )
                            }
                            IconButton(onClick = { vm.duplicate(e.key) }, modifier = Modifier.size(48.dp)) {
                                Icon(
                                    Icons.Rounded.ContentCopy,
                                    contentDescription = stringResource(R.string.library_duplicate_cd),
                                    tint = Muted,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            IconButton(onClick = { onDelete(e) }, modifier = Modifier.size(48.dp)) {
                                Icon(
                                    Icons.Rounded.Delete,
                                    contentDescription = stringResource(R.string.library_delete_cd),
                                    tint = Error,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                    else -> items(vm.serverList, key = { it.path }) { w ->
                        WfCard(
                            name = w.path.substringAfterLast('/').removeSuffix(".json"),
                            meta = stringResource(
                                R.string.library_server_meta,
                                fmtSize(w.size), fmtDate(w.modified),
                            ),
                        ) {
                            IconButton(onClick = { vm.importServer(w.path) }, modifier = Modifier.size(48.dp)) {
                                Icon(
                                    Icons.Rounded.Download,
                                    contentDescription = stringResource(R.string.library_import_server_cd),
                                    tint = Accent,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Snackbar 样式（#components）：Raised 底 + Border 1dp + radius 10dp；成功带 Success 对勾。 */
@Composable
private fun ComfySnackbarHost(host: SnackbarHostState, success: Boolean) {
    SnackbarHost(host) { data ->
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Raised,
            border = BorderStroke(1.dp, Border),
            shadowElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (success) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = Success,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    data.visuals.message,
                    style = ComfyType.Body,
                    color = Fg,
                    modifier = Modifier.weight(1f),
                )
                data.visuals.actionLabel?.let { label ->
                    TextButton(
                        onClick = { data.performAction() },
                        colors = ButtonDefaults.textButtonColors(contentColor = Accent),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(label, style = ComfyType.BodyStrong)
                    }
                }
            }
        }
    }
}

/** surface 卡 + radius-card；名称 title-card、meta mono-meta。 */
@Composable
private fun WfCard(
    name: String,
    meta: String,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Surface,
        border = BorderStroke(1.dp, Border),
    ) {
        Row(
            modifier = (onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
                .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = ComfyType.TitleCard,
                    color = Fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    meta,
                    style = ComfyType.MonoMeta,
                    color = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            trailing()
        }
    }
}

/** ghost 次按钮：透明底 + Border 描边，高 48dp。 */
@Composable
private fun GhostButton(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, Border),
    ) {
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
            Text(text, style = ComfyType.BodyStrong, color = Fg)
        }
    }
}
