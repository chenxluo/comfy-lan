@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.comfyapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.comfyapp.R

// ---- core six + states ----
// Values are oklch() converted to sRGB hex (oklch → oklab → linear sRGB → sRGB,
// gamut-clamped) per comfy-lan-tokens.md §1; regenerate if tokens change:
// bg oklch(0.19 0.02 250) surface .23 raised .26 fg .95 muted .70 border .30
// accent oklch(0.78 0.13 195) success oklch(0.78 0.14 165) warning oklch(0.82 0.13 85)
// error oklch(0.68 0.19 25)
val Bg = Color(0xFF0D151C)
val Surface = Color(0xFF161E26)
val Raised = Color(0xFF1D252D)
val Fg = Color(0xFFEAEFF5)
val Muted = Color(0xFF95A0AB)
val Border = Color(0xFF262F38)
val Accent = Color(0xFF1AD1D1)
val OnAccent = Color(0xFF0D151C)
val Success = Color(0xFF49D3A1)
val Warning = Color(0xFFEBBD57)
val Error = Color(0xFFF75D59)

/** 62% black over content — result meta bars, compare tags. */
val Scrim62 = Color(0x9E000000)

val ComfyLanScheme = darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    primaryContainer = Raised,
    onPrimaryContainer = Accent,
    background = Bg,
    onBackground = Fg,
    surface = Bg,
    onSurface = Fg,
    surfaceVariant = Surface,
    onSurfaceVariant = Muted,
    outline = Border,
    error = Error,
    onError = Fg,
)

// ---- fonts ----

val DisplayFamily = FontFamily(
    Font(R.font.space_grotesk, weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.space_grotesk, weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.space_grotesk, weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.space_grotesk, weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))),
)

val MonoFamily = FontFamily(
    Font(R.font.jetbrains_mono, weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.jetbrains_mono, weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))),
)

// ---- type scale (tokens.md §2) ----

object ComfyType {
    /** title-app: 顶栏标题、空状态标题 */
    val TitleApp = TextStyle(fontFamily = DisplayFamily, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    /** title-card: 卡片名 */
    val TitleCard = TextStyle(fontFamily = DisplayFamily, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    /** body 15 */
    val Body = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal)
    /** body-strong 15 Medium — buttons */
    val BodyStrong = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium)
    /** caption 12 muted */
    val Caption = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal)
    /** mono-value 14 tabular — 参数值输入框、slider 读数 */
    val MonoValue = TextStyle(fontFamily = MonoFamily, fontSize = 14.sp,
        fontFeatureSettings = "tnum")
    /** mono-meta 11 tabular — 节点来源、时间戳、进度 */
    val MonoMeta = TextStyle(fontFamily = MonoFamily, fontSize = 11.sp,
        fontFeatureSettings = "tnum")
}

@Composable
fun ComfyLanTheme(content: @Composable () -> Unit) {
    // 单一深色主题（tokens.md §6.1），无浅色、无动态取色
    MaterialTheme(
        colorScheme = ComfyLanScheme,
        typography = androidx.compose.material3.Typography(
            titleLarge = ComfyType.TitleApp,
            titleMedium = ComfyType.TitleCard,
            bodyLarge = ComfyType.Body,
            labelLarge = ComfyType.BodyStrong,
            labelSmall = ComfyType.Caption,
        ),
        content = content,
    )
}
