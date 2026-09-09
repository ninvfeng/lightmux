package net.lighttools.lightmux.ui.theme

import androidx.compose.ui.graphics.Color

val Background = Color(0xFF101418)
val Surface = Color(0xFF171C22)
val SurfaceVariant = Color(0xFF1F262E)
val Foreground = Color(0xFFD6DEE8)
val ForegroundDim = Color(0xFF8A96A5)
val Accent = Color(0xFF4EC9B0)
val AccentDim = Color(0xFF3A8C7C)
val AccentLight = Color(0xFF00796B)
val Danger = Color(0xFFE05252)

/**
 * 正向结论（目前只有「测试连接成功」）。
 *
 * 不复用 M3 的 tertiaryContainer：主题没定义 tertiary，基线值是**粉红**，
 * 一条粉红横条摆在失败时那条红条的同一个位置，用户第一眼分不出这次到底通没通。
 * 也不复用主色的青绿——那是全局强调色，标题栏和 chip 都是它，结论条会糊进去。
 */
val Success = Color(0xFF2E7D32)
