@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.comfyapp.ui

import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.navigation.NavHostController
import com.comfyapp.AppContainer
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import com.comfyapp.ui.theme.Border
import com.comfyapp.ui.theme.ComfyType
import com.comfyapp.ui.theme.Raised
import com.comfyapp.ui.theme.Bg

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(container: AppContainer, nav: NavHostController, key: String?) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var web by remember { mutableStateOf<WebView?>(null) }
    var wfJson by remember { mutableStateOf<String?>(null) }
    // container state machine: loading (accent 2dp line) → injected → done | failed
    var loadFailed by remember { mutableStateOf<String?>(null) }
    var injected by remember { mutableStateOf(key == null) } // canvas mode needs no injection

    if (key != null) {
        LaunchedEffect(key) {
            wfJson = container.workflows.get(key)?.json
        }
    }

    /** Load OUR workflow into the frontend's graph. Retries until the frontend is ready —
     *  it self-reloads a few times during startup (vite preload recovery). */
    fun injectGraph(wv: WebView, attempt: Int = 0) {
        val raw = wfJson ?: return
        val jsString = container.json.encodeToString(String.serializer(), raw)
        wv.evaluateJavascript(
            """
            (function() {
              // 旧版前端挂 window.app；1.5x 起模块化且不再暴露 app 实例（上游 #7757），
              // 此时注入自然降级为打开前端首页。
              const app = window.app || (window.comfyAPI && window.comfyAPI.app);
              if (!(app && app.graph && app.loadGraphData)) return 'notready';
              const g = JSON.parse($jsString);
              if (g.extra) delete g.extra.ds; // desktop viewport is wrong for a phone screen
              app.loadGraphData(g);
              setTimeout(function() {
                const nodes = app.graph._nodes || app.graph.nodes || [];
                if (!nodes.length) return;
                let x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
                for (const n of nodes) {
                  const p = n.pos, sz = n.size;
                  x0 = Math.min(x0, p[0]); y0 = Math.min(y0, p[1]);
                  x1 = Math.max(x1, p[0] + (sz ? sz[0] : 200));
                  y1 = Math.max(y1, p[1] + (sz ? sz[1] : 100));
                }
                const cv = app.canvas;
                const vw = cv.canvas.clientWidth, vh = cv.canvas.clientHeight;
                const s = Math.min(vw / (x1 - x0 + 80), vh / (y1 - y0 + 80), 1.0);
                cv.ds.scale = s;
                cv.ds.offset = [vw / 2 - (x0 + x1) / 2 * s, vh / 2 - (y0 + y1) / 2 * s];
                cv.setDirty(true, true);
              }, 600);
              return 'ok';
            })()
            """.trimIndent(),
        ) { result ->
            if (result == "\"ok\"") {
                injected = true
            } else if (attempt < 8) {
                wv.postDelayed({ injectGraph(wv, attempt + 1) }, 1500)
            } else {
                injected = true // give up quietly — canvas still usable
            }
        }
    }

    fun saveBack() {
        val wv = web ?: return
        wv.evaluateJavascript(
            "JSON.stringify((window.app || (window.comfyAPI && window.comfyAPI.app) || {}).graph ? (window.app || window.comfyAPI.app).graph.serialize() : null)",
        ) { result ->
            scope.launch {
                val decoded = if (result == null || result == "null") {
                    null
                } else {
                    runCatching { container.json.decodeFromString<String>(result) }.getOrNull()
                }
                when {
                    decoded == null || decoded == "null" -> snackbar.showSnackbar(
                        "前端暂不支持读取，请在 ComfyUI 菜单 Export 后回列表页重新导入",
                    )
                    key != null -> runCatching { container.workflows.updateJson(key, decoded) }
                        .onSuccess { snackbar.showSnackbar("已保存") }
                        .onFailure { snackbar.showSnackbar("保存失败：${it.message}") }
                    else -> runCatching { container.workflows.import("画布工作流", decoded) }
                        .onSuccess { snackbar.showSnackbar("已保存到工作流库") }
                        .onFailure { snackbar.showSnackbar("保存失败：${it.message}") }
                }
            }
        }
    }

    Scaffold(
        containerColor = Bg,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(key?.let { "编辑工作流" } ?: "ComfyUI 画布", style = ComfyType.TitleApp) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Raised),
                    navigationIcon = {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        TextButton(onClick = { saveBack() }) { Text("保存回App") }
                    },
                )
                // 2dp accent progress while loading + injecting
                if (!injected && loadFailed == null) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        trackColor = Border,
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        val failure = loadFailed
        if (failure != null) {
            Column(
                Modifier.fillMaxSize().padding(pad).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = com.comfyapp.ui.theme.Error)
                Spacer(Modifier.height(12.dp))
                Text(failure, style = ComfyType.Caption, color = com.comfyapp.ui.theme.Muted)
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = {
                    loadFailed = null; injected = (key == null); web?.reload()
                }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Spacer(Modifier.padding(2.dp))
                    Text("重试")
                }
            }
        } else {
            AndroidView(
                modifier = Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .border(1.dp, Border)
                    .background(Bg),
                factory = { ctx ->
                    WebView(ctx).apply {
                        // Explicit MATCH_PARENT + deferred load: without this the page lays out
                        // while the view is 0-sized, Chromium pins the initial containing block
                        // to height 0 and every 100vh/-webkit-fill-available in the frontend
                        // collapses (black screen with the DOM fully mounted).
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        WebView.setWebContentsDebuggingEnabled(
                            0 != ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE,
                        )
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                if (key != null) injectGraph(view)
                            }

                            override fun onReceivedError(
                                view: WebView,
                                req: WebResourceRequest,
                                err: WebResourceError,
                            ) {
                                if (req.isForMainFrame) loadFailed = err.description?.toString() ?: "加载失败"
                            }
                        }
                        doOnLayout { loadUrl(container.api.baseUrl) }
                    }
                },
                update = { web = it },
            )
        }
    }
}
