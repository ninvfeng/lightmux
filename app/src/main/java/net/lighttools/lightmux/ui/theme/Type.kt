package net.lighttools.lightmux.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import net.lighttools.lightmux.R

/**
 * 等宽字体。内置 JetBrains Mono Regular（OFL 1.1，约 268KB），不用系统等宽。
 *
 * 原因见 [net.lighttools.lightmux.ui.terminal.terminalTypeface]。CJK 仍走系统回退（内置字体没中文）。
 * 粗体交给 fakeBold，所以只打包 Regular 一个字重。
 */
val MonoFamily: FontFamily = FontFamily(Font(R.font.jetbrains_mono_regular))

val LightmuxTypography = Typography(
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelSmall = TextStyle(fontSize = 11.sp),
)
