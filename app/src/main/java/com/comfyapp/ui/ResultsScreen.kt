package com.comfyapp.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.comfyapp.AppContainer
import com.comfyapp.R
import com.comfyapp.data.ResultEntity
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
import com.comfyapp.ui.theme.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---- 相册写入（结果中心 / 查看器 / 对比导出共用） ----

/** Writes an image into the public gallery under Pictures/ComfyLAN. API 29+ goes through
 *  MediaStore (no permission needed); older devices write the file directly and need
 *  WRITE_EXTERNAL_STORAGE, which callers must have granted. */
internal fun writeToGallery(
    context: Context,
    displayName: String,
    mime: String,
    write: (OutputStream) -> Unit,
): Uri? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ComfyLAN")
        }
        val uri = context.contentResolver
            .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use(write)
        uri
    } else {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ComfyLAN")
        if (!dir.exists() && !dir.mkdirs()) return null
        val out = File(dir, displayName)
        out.outputStream().use(write)
        MediaScannerConnection.scanFile(context, arrayOf(out.absolutePath), arrayOf(mime), null)
        Uri.fromFile(out)
    }
}

internal fun imageMime(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    else -> "image/jpeg"
}

/** Runs [block] once gallery write permission is available (requests it on API < 29). */
@Composable
internal fun rememberGalleryPermission(): ((() -> Unit)) -> Unit {
    val context = LocalContext.current
    var action by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val a = action
        action = null
        if (granted) a?.invoke()
    }
    return remember(launcher) { { block ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        ) block() else {
            action = block
            launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    } }
}

private val cellDate = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

private sealed interface Filter {
    data object All : Filter
    data object I2I : Filter
    data class Wf(val name: String) : Filter
}

class ResultsViewModel(private val container: AppContainer) : ViewModel() {
    val results = container.results.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var cacheBytes by mutableStateOf(0L)
        private set
    /** Snackbar events for the UI: saved image count / "a deletion happened" tick. */
    var savedCount by mutableStateOf<Int?>(null)
    var deletedTick by mutableIntStateOf(0)
        private set

    init {
        refreshCache()
    }

    fun refreshCache() {
        viewModelScope.launch { cacheBytes = container.results.cacheSizeBytes() }
    }

    fun delete(ids: List<Long>) {
        viewModelScope.launch {
            container.results.delete(ids)
            refreshCache()
            deletedTick++
        }
    }

    fun clearAll() = delete(results.value.map { it.id })

    fun saveToGallery(context: Context, ids: List<Long>) {
        viewModelScope.launch(Dispatchers.IO) {
            var n = 0
            for (id in ids) {
                val e = container.results.get(id) ?: continue
                val f = container.results.fileOf(e)
                if (!f.exists()) continue
                val saved = runCatching {
                    writeToGallery(context, "ComfyLAN_${e.file}", imageMime(f.name)) { out ->
                        f.inputStream().use { it.copyTo(out) }
                    }
                }.getOrNull()
                if (saved != null) n++
            }
            savedCount = n
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(container: AppContainer, nav: NavHostController, modifier: Modifier) {
    val vm: ResultsViewModel = viewModel(
        factory = viewModelFactory { initializer { ResultsViewModel(container) } },
    )
    val all by vm.results.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val withPermission = rememberGalleryPermission()

    var filter by remember { mutableStateOf<Filter>(Filter.All) }
    val selection = remember { mutableStateListOf<Long>() }
    val selecting = selection.isNotEmpty()
    var confirmClear by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(vm.savedCount) {
        val n = vm.savedCount ?: return@LaunchedEffect
        vm.savedCount = null
        if (n > 0) snackbar.showSnackbar(context.getString(R.string.results_saved_fmt, n))
    }
    LaunchedEffect(vm.deletedTick) {
        if (vm.deletedTick > 0) snackbar.showSnackbar(context.getString(R.string.results_deleted))
    }

    val fmtMb = String.format(Locale.US, "%.1f", vm.cacheBytes / 1048576.0)

    Scaffold(
        modifier = modifier,
        containerColor = Bg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selecting) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
                    navigationIcon = {
                        IconButton(onClick = { selection.clear() }) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.results_exit_selection),
                                tint = Fg,
                            )
                        }
                    },
                    title = {
                        Text(
                            stringResource(R.string.selection_title_fmt, selection.size),
                            style = ComfyType.TitleApp,
                            color = Fg,
                        )
                    },
                    actions = {
                        val ids = selection.toList()
                        TextButton(onClick = {
                            withPermission { vm.saveToGallery(context, ids) }
                            selection.clear()
                        }) {
                            Text(stringResource(R.string.action_save), style = ComfyType.BodyStrong, color = Accent)
                        }
                        TextButton(onClick = {
                            vm.delete(selection.toList())
                            selection.clear()
                        }) {
                            Text(stringResource(R.string.action_delete), style = ComfyType.BodyStrong, color = Error)
                        }
                    },
                )
            } else {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
                    title = {
                        Column {
                            Text(stringResource(R.string.results_title), style = ComfyType.TitleApp, color = Fg)
                            Text(
                                stringResource(R.string.results_count_fmt, all.size, fmtMb),
                                style = ComfyType.MonoMeta,
                                color = Muted,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Rounded.CleaningServices,
                                contentDescription = stringResource(R.string.results_cache_mgmt),
                                tint = Fg,
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            containerColor = Raised,
                            shape = RoundedCornerShape(20.dp),
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.results_clear_cache),
                                        style = ComfyType.BodyStrong,
                                        color = Error,
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    confirmClear = true
                                },
                            )
                        }
                    },
                )
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (all.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.results_empty_title), style = ComfyType.TitleApp, color = Fg)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.results_empty_message),
                            style = ComfyType.Caption,
                            color = Muted,
                        )
                        Spacer(Modifier.height(24.dp))
                        TextButton(onClick = {
                            nav.navigate("workflows") { launchSingleTop = true }
                        }) {
                            Text(stringResource(R.string.results_go_workflows), style = ComfyType.BodyStrong, color = Accent)
                        }
                    }
                }
            } else {
                val names = all.map { it.workflowName }.distinct()
                val filtered = when (val f = filter) {
                    Filter.All -> all
                    Filter.I2I -> all.filter { it.isI2i }
                    is Filter.Wf -> all.filter { it.workflowName == f.name }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(stringResource(R.string.results_filter_all), filter is Filter.All) { filter = Filter.All }
                    FilterChip(stringResource(R.string.results_filter_i2i), filter is Filter.I2I) { filter = Filter.I2I }
                    names.forEach { n ->
                        FilterChip(n, filter is Filter.Wf && (filter as? Filter.Wf)?.name == n) { filter = Filter.Wf(n) }
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(120.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered, key = { it.id }) { e ->
                        ResultCell(
                            e = e,
                            file = container.results.fileOf(e),
                            selecting = selecting,
                            selected = e.id in selection,
                            dateText = remember(e.createdAt) { cellDate.format(Date(e.createdAt)) },
                            onTap = {
                                if (selecting) {
                                    if (e.id in selection) selection.remove(e.id) else selection.add(e.id)
                                } else {
                                    scope.launch { container.results.touchViewed(e.id) }
                                    nav.navigate("viewer/${e.id}")
                                }
                            },
                            onLongTap = {
                                if (!selecting) selection.add(e.id)
                            },
                        )
                    }
                }
            }
        }
    }

    BackHandler(enabled = selecting) { selection.clear() }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = Surface,
            shape = RoundedCornerShape(20.dp),
            title = { Text(stringResource(R.string.results_clear_title), style = ComfyType.TitleCard, color = Fg) },
            text = { Text(stringResource(R.string.results_clear_message), style = ComfyType.Body, color = Muted) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    vm.clearAll()
                }) {
                    Text(stringResource(R.string.results_clear_cache), style = ComfyType.BodyStrong, color = Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.action_cancel), style = ComfyType.BodyStrong, color = Muted)
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultCell(
    e: ResultEntity,
    file: File,
    selecting: Boolean,
    selected: Boolean,
    dateText: String,
    onTap: () -> Unit,
    onLongTap: () -> Unit,
) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(Surface)
            // loading placeholder = the Surface block itself; image crossfades in over it
            .then(
                if (selected) Modifier.border(2.dp, Accent, RoundedCornerShape(16.dp))
                else Modifier.border(1.dp, Border, RoundedCornerShape(16.dp))
            )
            .combinedClickable(onClick = onTap, onLongClick = onLongTap),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(file)
                .crossfade(150)
                .build(),
            contentDescription = "${e.workflowName} $dateText",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (selecting && selected) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(20.dp)
                    .background(Accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = OnAccent, modifier = Modifier.size(14.dp))
            }
        } else if (e.isI2i) {
            Text(
                stringResource(R.string.results_filter_i2i),
                style = ComfyType.MonoMeta.copy(fontWeight = FontWeight.Bold),
                color = OnAccent,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(Accent, RoundedCornerShape(999.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Text(
            dateText,
            style = ComfyType.MonoMeta,
            color = Fg,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .background(Scrim62, RoundedCornerShape(999.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) Accent else Color.Transparent)
            .border(1.dp, if (selected) Accent else Border, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = ComfyType.Body, color = if (selected) OnAccent else Muted)
    }
}
