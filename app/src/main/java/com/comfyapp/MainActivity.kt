package com.comfyapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.comfyapp.ui.AppRoot
import com.comfyapp.ui.theme.ComfyLanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 内容绘制到状态栏/导航栏后面；各屏 Scaffold 自行消费 insets
        enableEdgeToEdge()
        setContent {
            ComfyLanTheme {
                AppRoot((application as ComfyApp).container)
            }
        }
    }
}
