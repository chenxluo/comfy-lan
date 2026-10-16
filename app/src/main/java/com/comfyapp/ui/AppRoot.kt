package com.comfyapp.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.comfyapp.AppContainer
import com.comfyapp.ui.theme.Bg
import com.comfyapp.ui.theme.Raised
import kotlinx.coroutines.delay

/** 服务器连通状态：null=未知, true=已连接, false=断开 */
val ServerPulse = mutableStateOf<Boolean?>(null)

@Composable
fun AppRoot(container: AppContainer) {
    var start by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        container.settings.serverUrl.collect { url ->
            if (url != null) container.api.baseUrl = url.trimEnd('/')
            if (start == null) start = if (url == null) "setup" else "workflows"
        }
    }
    val startDestination = start ?: return

    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    // 服务器脉冲：库页顶栏与断线提示共用（15s 轮询 /system_stats）
    LaunchedEffect(container.api.baseUrl) {
        while (true) {
            ServerPulse.value = runCatching { container.api.systemStats(); true }.getOrDefault(false)
            delay(if (ServerPulse.value == true) 15_000 else 5_000)
        }
    }

    Scaffold(
        containerColor = Bg,
        // 子屏各自带 Scaffold/TopAppBar，外层不再吃 systemBars（否则双重 padding）
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        bottomBar = {
            if (route == "workflows" || route == "results") {
                NavigationBar(containerColor = Raised, tonalElevation = 0.dp) {
                    NavigationBarItem(
                        selected = route == "workflows",
                        onClick = { nav.navigateTab(container, "workflows") },
                        icon = { Icon(Icons.Rounded.GridView, contentDescription = null) },
                        label = { Text("工作流") },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = Color.Transparent,
                        ),
                    )
                    NavigationBarItem(
                        selected = route == "results",
                        onClick = { nav.navigateTab(container, "results") },
                        icon = { Icon(Icons.Rounded.Image, contentDescription = null) },
                        label = { Text("结果") },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = Color.Transparent,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        NavHost(
            navController = nav,
            startDestination = startDestination,
            modifier = Modifier.background(Bg),
        ) {
            composable("setup") { ServerSetupScreen(container, nav) }
            composable("workflows") {
                WorkflowListScreen(container, nav, Modifier.padding(pad))
            }
            composable("results") {
                ResultsScreen(container, nav, Modifier.padding(pad))
            }
            composable(
                "workflow/{key}?fromResult={id}",
                arguments = listOf(
                    androidx.navigation.navArgument("key") { },
                    androidx.navigation.navArgument("id") { defaultValue = -1L },
                ),
            ) { entry ->
                WorkflowDetailScreen(
                    container, nav,
                    key = entry.arguments?.getString("key").orEmpty(),
                    fromResultId = entry.arguments?.getLong("id") ?: -1L,
                )
            }
            composable("edit/{key}") { entry ->
                EditScreen(container, nav, entry.arguments?.getString("key"))
            }
            composable("canvas") { EditScreen(container, nav, null) }
            composable(
                "viewer/{id}",
                arguments = listOf(androidx.navigation.navArgument("id") { type = androidx.navigation.NavType.LongType }),
            ) { entry ->
                ImageViewerScreen(container, nav, entry.arguments?.getLong("id") ?: -1L)
            }
            composable(
                "compare/{id}",
                arguments = listOf(androidx.navigation.navArgument("id") { type = androidx.navigation.NavType.LongType }),
            ) { entry ->
                CompareScreen(container, nav, entry.arguments?.getLong("id") ?: -1L)
            }
        }
    }
}

private fun NavHostController.navigateTab(container: AppContainer, route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** 顶栏服务器脉冲点：connected = accent 扩散动画；断开 = muted 静止。 */
@Composable
fun PulseDot(connected: Boolean?, modifier: Modifier = Modifier) {
    val color = if (connected == true) com.comfyapp.ui.theme.Accent else com.comfyapp.ui.theme.Muted
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 2.1f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Restart),
        label = "pulseScale",
    )
    Box(modifier.size(8.dp)) {
        if (connected == true) {
            Box(
                Modifier
                    .size(8.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale; alpha = 1f - (scale - 1f) / 1.1f }
                    .background(color.copy(alpha = 0.5f), CircleShape),
            )
        }
        Box(Modifier.size(8.dp).background(color, CircleShape))
    }
}
