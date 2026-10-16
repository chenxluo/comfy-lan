package com.comfyapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
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
import com.comfyapp.api.ApiException
import com.comfyapp.ui.theme.Accent
import com.comfyapp.ui.theme.Border
import com.comfyapp.ui.theme.Bg
import com.comfyapp.ui.theme.ComfyType
import com.comfyapp.ui.theme.Error
import com.comfyapp.ui.theme.Fg
import com.comfyapp.ui.theme.Muted
import com.comfyapp.ui.theme.OnAccent
import com.comfyapp.ui.theme.Surface
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 连接错误：空地址 / 失败（文案在 UI 层用资源拼装）。 */
sealed interface ConnectError {
    data object Empty : ConnectError
    data class Failed(val detail: String?) : ConnectError
}

class ServerSetupViewModel(private val container: AppContainer) : ViewModel() {
    var url by mutableStateOf("")
    var busy by mutableStateOf(false)
    var error by mutableStateOf<ConnectError?>(null)

    val recentUrls = container.settings.recentUrls
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun connect(onOk: () -> Unit) {
        if (busy) return
        val normalized = url.trim()
            .let { if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it" }
            .trimEnd('/')
        if (normalized == "http://") {
            error = ConnectError.Empty
            return
        }
        busy = true
        error = null
        viewModelScope.launch {
            try {
                container.api.baseUrl = normalized
                container.api.systemStats()
                container.settings.rememberUrl(normalized)
                container.settings.setServerUrl(normalized)
                onOk()
            } catch (e: ApiException) {
                error = ConnectError.Failed(e.message)
            } catch (e: Exception) {
                error = ConnectError.Failed(e.message)
            } finally {
                busy = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSetupScreen(container: AppContainer, nav: NavHostController) {
    val vm: ServerSetupViewModel = viewModel(
        factory = viewModelFactory { initializer { ServerSetupViewModel(container) } },
    )
    val recent by vm.recentUrls.collectAsStateWithLifecycle()
    val err = vm.error

    // 图纸网格纹理（#screen-connect 唯一允许出场的一屏）
    Box(Modifier.fillMaxSize().background(Bg)) {
        Canvas(Modifier.fillMaxSize()) {
            val step = 24.dp.toPx()
            val line = 1.dp.toPx()
            val grid = Border.copy(alpha = 0.35f)
            var x = 0f
            while (x <= size.width) {
                drawLine(grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = line)
                x += step
            }
            var y = 0f
            while (y <= size.height) {
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = line)
                y += step
            }
        }

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.connect_title),
                            style = ComfyType.TitleApp,
                            color = Fg,
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
        ) { pad ->
            Column(
                modifier = Modifier.padding(pad).fillMaxSize().padding(horizontal = 20.dp),
            ) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.connect_addr_label),
                    style = ComfyType.Body,
                    color = Fg,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = vm.url,
                    onValueChange = {
                        vm.url = it
                        vm.error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            stringResource(R.string.connect_placeholder),
                            style = ComfyType.MonoValue,
                            color = Muted,
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    textStyle = ComfyType.MonoValue.copy(color = Fg),
                    isError = err != null,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Surface,
                        unfocusedContainerColor = Surface,
                        focusedBorderColor = if (err != null) Error else Accent,
                        unfocusedBorderColor = if (err != null) Error else Border,
                        focusedTextColor = Fg,
                        unfocusedTextColor = Fg,
                        cursorColor = Accent,
                    ),
                )

                if (recent.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(recent) { u ->
                            PillChip(
                                text = u,
                                style = ComfyType.MonoValue,
                                contentColor = Fg,
                                stroke = Border,
                                onClick = {
                                    vm.url = u
                                    vm.error = null
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        vm.connect {
                            nav.navigate("workflows") { popUpTo("setup") { inclusive = true } }
                        }
                    },
                    enabled = !vm.busy,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent,
                        contentColor = OnAccent,
                        disabledContainerColor = Accent.copy(alpha = 0.38f),
                        disabledContentColor = OnAccent,
                    ),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    if (vm.busy) {
                        // accent 环画在 accent 底上不可见，用 on-accent
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = OnAccent,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.connect_button), style = ComfyType.BodyStrong)
                }

                if (err != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = when (err) {
                            ConnectError.Empty -> stringResource(R.string.connect_empty)
                            is ConnectError.Failed ->
                                if (err.detail.isNullOrBlank()) stringResource(R.string.connect_failed_generic)
                                else stringResource(R.string.connect_failed, err.detail)
                        },
                        color = Error,
                        style = ComfyType.Caption,
                    )
                }

                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.connect_footer),
                    style = ComfyType.MonoMeta,
                    color = Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
                )
            }
        }
    }
}

/** 圆角 999 胶囊 chip：来源过滤 / 最近地址共用。触控 ≥48dp。 */
@Composable
internal fun PillChip(
    text: String,
    style: TextStyle,
    contentColor: Color,
    selected: Boolean = false,
    stroke: Color = Border,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) Accent else Color.Transparent)
            .border(1.dp, if (selected) Accent else stroke, shape)
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text,
            style = style,
            color = if (selected) OnAccent else contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 260.dp),
        )
        trailing?.invoke()
    }
}
