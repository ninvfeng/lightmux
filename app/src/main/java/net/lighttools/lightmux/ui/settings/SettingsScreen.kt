package net.lighttools.lightmux.ui.settings

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import net.lighttools.lightmux.R
import net.lighttools.lightmux.data.AppSettings
import net.lighttools.lightmux.data.LanguageOption
import net.lighttools.lightmux.data.QuickKey
import net.lighttools.lightmux.data.QuickKeySize
import net.lighttools.lightmux.data.TerminalPalette
import net.lighttools.lightmux.data.ThemeOption
import net.lighttools.lightmux.service.BackgroundLimits
import net.lighttools.lightmux.ui.common.BackButton
import net.lighttools.lightmux.ui.common.rememberBackgroundExempt
import net.lighttools.lightmux.ui.terminal.QuickKeyCap
import net.lighttools.lightmux.update.ApkVerdict
import net.lighttools.lightmux.update.ReleaseInfo
import net.lighttools.lightmux.update.ReleaseNotes
import kotlin.math.roundToInt

/**
 * 设置页。版本（点击即检查更新）与项目地址顶在最上面，其余分外观、通用两组。
 *
 * 返回走中央栈（`nav.pop()`），页面内部不写 BackHandler（CLAUDE.md 架构要点 ④）。
 *
 * 语言与主题的生效路径不一样：主题只是 Compose 状态，重组即可；
 * 语言要换 `Resources`，只能靠 Activity 重建（见 MainActivity）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenKeys: () -> Unit,
    onOpenProject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by vm.settings.collectAsState()
    var picker by remember { mutableStateOf<Picker?>(null) }
    val context = LocalContext.current
    val backgroundExempt = rememberBackgroundExempt()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            // 版本 / 项目地址顶在最上面，不套分区标题：这两行是「有事才来」的入口
            // （想知道装的是哪个版本、有没有新版、去哪看源码），而主题、语言这些设一次就不再动。
            VersionRow(
                version = vm.currentVersion,
                state = vm.update,
                onCheck = vm::checkForUpdate,
                onOpenAbout = onOpenAbout,
            )
            SettingRow(
                title = stringResource(R.string.settings_project),
                value = stringResource(R.string.settings_project_value),
                onClick = onOpenProject,
            )
            // 紧跟项目地址：密钥是「配主机时才想起来」的东西，埋在通用组末尾等于让人翻着找。
            SettingRow(
                title = stringResource(R.string.keys_title),
                value = stringResource(R.string.settings_keys_value),
                onClick = onOpenKeys,
            )

            HorizontalDivider()
            SectionHeader(stringResource(R.string.settings_section_appearance))
            SettingRow(
                title = stringResource(R.string.settings_theme),
                value = stringResource(settings.theme.labelRes),
                onClick = { picker = Picker.Theme },
            )
            SettingRow(
                title = stringResource(R.string.settings_palette),
                value = stringResource(settings.palette.labelRes),
                onClick = { picker = Picker.Palette },
            )
            SliderRow(
                label = stringResource(R.string.settings_text_size, settings.terminalTextSizeSp),
                value = settings.terminalTextSizeSp,
                range = AppSettings.MIN_TERMINAL_TEXT_SIZE_SP..AppSettings.MAX_TERMINAL_TEXT_SIZE_SP,
                onChange = vm::setTerminalTextSize,
            ) {
                TextSizePreview(settings.terminalTextSizeSp, settings.palette)
            }
            // 拖的是**键宽**，键高与键距由它推出来（见 [QuickKeySize]）——手指粗细因人而异，
            // 三档拍板总有人嫌大或嫌小，而这个数本来就是连续的。
            SliderRow(
                label = stringResource(R.string.settings_quick_key_size, settings.quickKeyWidthDp),
                value = settings.quickKeyWidthDp,
                range = QuickKeySize.MIN_WIDTH_DP..QuickKeySize.MAX_WIDTH_DP,
                onChange = vm::setQuickKeyWidth,
            ) {
                QuickKeyPreview(settings.quickKeyWidthDp)
            }

            HorizontalDivider()
            SectionHeader(stringResource(R.string.settings_section_general))
            // 排在语言前面：这一条决定的是「切后台之后还连不连得上」，
            // 而转发页那条提示只在受限时出现，授权完就没了——总得有个能翻回来看的地方。
            SettingRow(
                title = stringResource(R.string.settings_background),
                value = stringResource(
                    if (backgroundExempt) R.string.settings_background_allowed
                    else R.string.settings_background_restricted
                ),
                onClick = { BackgroundLimits.requestExemption(context) },
            )
            SettingRow(
                title = stringResource(R.string.settings_language),
                value = stringResource(settings.language.labelRes),
                onClick = { picker = Picker.Language },
            )
        }
    }

    when (picker) {
        Picker.Theme -> ChoiceDialog(
            title = stringResource(R.string.settings_theme),
            options = ThemeOption.entries,
            selected = settings.theme,
            label = { stringResource(it.labelRes) },
            onPick = { vm.setTheme(it); picker = null },
            onDismiss = { picker = null },
        )

        Picker.Language -> ChoiceDialog(
            title = stringResource(R.string.settings_language),
            options = LanguageOption.entries,
            selected = settings.language,
            label = { stringResource(it.labelRes) },
            onPick = { vm.setLanguage(it); picker = null },
            onDismiss = { picker = null },
        )

        Picker.Palette -> ChoiceDialog(
            title = stringResource(R.string.settings_palette),
            options = TerminalPalette.entries,
            selected = settings.palette,
            label = { stringResource(it.labelRes) },
            onPick = { vm.setPalette(it); picker = null },
            onDismiss = { picker = null },
        )

        null -> Unit
    }

    UpdateDialog(
        state = vm.update,
        onInstall = vm::downloadAndInstall,
        onGrantPermission = vm::grantInstallPermission,
        onDismiss = vm::dismissUpdate,
    )
}

/** 哪个选择弹窗开着。用一个枚举而不是几个 boolean，省掉「两个框同时开」这种状态。 */
private enum class Picker { Theme, Language, Palette }

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SettingRow(title: String, value: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = ROW_PADDING),
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 「标题 · 当前值」+ 滑杆 + 实时样例。字号和键宽这两条都长这样。
 *
 * 加 [preview] 的理由：这两个数调完只在**终端页**看得见效果，用户得「设置→终端→嫌小→退回设置」
 * 来回试。样例摆在滑杆底下，拖的时候当场就定了。
 *
 * 不画刻度点（`steps`）：范围是 21 / 25 档，画出来是一排密集小点，既看不清又和样例抢注意力；
 * 取整交给 [roundToInt]，拖动落到哪一档由样例和标题上的数字说了算。
 *
 * 上下留白比别的行还窄：这一条本来就是三层（标题 + 滑杆 + 样例），
 * 再按 [ROW_PADDING] 撑开，两条滑杆就把「语言」挤出屏幕了。
 */
@Composable
private fun SliderRow(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    preview: @Composable () -> Unit,
) {
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = SLIDER_ROW_PADDING)) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        ThinSlider(value = value, range = range, onChange = onChange)
        // 样例块高度按最大档预留：不定高的话，从 8sp 拖到 28sp 会把下面整页往下顶，
        // 用户以为自己拖歪了。定高之后动的只有样例本身。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = PREVIEW_HEIGHT)
                .padding(bottom = SLIDER_ROW_PADDING),
            contentAlignment = Alignment.CenterStart,
            content = { preview() },
        )
    }
}

/**
 * 细滑杆：4dp 轨道 + 14dp 圆钮，整条 [SLIDER_HEIGHT] 高，即网页上那种滑动条。
 *
 * M3 自带的滑杆是 16dp 粗轨道 + 48dp 触控高，一条顶掉两行文本的位置——设置页统共就这么点地方。
 * 光给 `Modifier.height` 压不下去：48dp 是 [LocalMinimumInteractiveComponentSize] 撑的，
 * 得把它一并关掉。关掉之后触控高度就是滑杆自身的 22dp，横向仍是整行，拖起来够得着。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThinSlider(value: Int, range: IntRange, onChange: (Int) -> Unit) {
    val filled = ((value - range.first).toFloat() / (range.last - range.first)).coerceIn(0f, 1f)
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.surfaceVariant
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.fillMaxWidth().height(SLIDER_HEIGHT),
            // 圆钮不交给 M3 画：它摆 thumb 和 track 这两个槽位用的是各自的对齐规矩，
            // 画出来就是圆钮和轨道一高一低。这里只留一个等宽的透明占位——
            // M3 照旧靠它把轨道两端内缩、算出拖动范围——轨道和圆钮都在 track 槽位里
            // 一笔画完，共用一个坐标系，垂直位置自己说了算。
            thumb = { Spacer(Modifier.width(SLIDER_THUMB).height(SLIDER_HEIGHT)) },
            track = {
                Canvas(Modifier.fillMaxWidth().height(SLIDER_HEIGHT)) {
                    val middle = size.height / 2
                    val thickness = SLIDER_TRACK.toPx()
                    val corner = CornerRadius(thickness / 2)
                    val top = Offset(0f, middle - thickness / 2)
                    drawRoundRect(inactive, top, Size(size.width, thickness), corner)
                    drawRoundRect(active, top, Size(size.width * filled, thickness), corner)
                    drawCircle(active, SLIDER_THUMB.toPx() / 2, Offset(size.width * filled, middle))
                }
            },
        )
    }
}

private val SLIDER_HEIGHT = 22.dp
private val SLIDER_TRACK = 4.dp
private val SLIDER_THUMB = 14.dp

/** 滑杆行的上下留白，比 [ROW_PADDING] 更紧——理由见 [SliderRow]。 */
private val SLIDER_ROW_PADDING = 6.dp

/** 样例块的高度。按最大档（28sp 的字、48dp 的键帽）留出来，拖动时不抖。 */
private val PREVIEW_HEIGHT = 52.dp

/**
 * 字号样例。**连配色一起还原**——配色就在上面一行，两条一起调时能互相看出效果。
 *
 * 样例文本固定是条 tmux 命令，中英两份一样：它演示的是字号和等宽字形，翻译了反而失真。
 */
@Composable
private fun TextSizePreview(sizeSp: Int, palette: TerminalPalette) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = palette.previewColor("background", Color.Black),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = stringResource(R.string.settings_text_size_sample),
                color = palette.previewColor("foreground", Color.White),
                fontFamily = FontFamily.Monospace,
                fontSize = sizeSp.sp,
                maxLines = 1,
            )
        }
    }
}

/**
 * 取配色里的一个色号。[TerminalPalette.Default] 是空表（vendored 内核自带的 xterm 默认值），
 * 取不到就回退到黑底白字——那正是 xterm 默认的前后景。
 */
private fun TerminalPalette.previewColor(key: String, fallback: Color): Color =
    colors[key]?.let { runCatching { Color(it.toColorInt()) }.getOrNull() } ?: fallback

/**
 * 键位样例。直接摆真键帽（[QuickKeyCap]）而不是画几个方块：键宽只直接管**单字符键**，
 * 多字符键（`Ctrl`）由文字自己撑开、只跟着缩留白（见 [QuickKeySize]），
 * 拿假方块演示会让人以为拖到底所有键都能变那么窄。
 *
 * 底色用 `surfaceVariant`：键帽自身是 `surface` 色，直接摆在设置页上就是白底白键，看不出边界。
 */
@Composable
private fun QuickKeyPreview(widthDp: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        val gap = QuickKeySize.gapDp(widthDp).dp
        Row(
            modifier = Modifier.padding(gap),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PREVIEW_KEYS.forEach { QuickKeyCap(key = it, widthDp = widthDp) }
        }
    }
}

/** 样例里摆哪几个键：先两个多字符的（宽度由文字撑开），再三个单字符的（拖动时变化最明显）。 */
private val PREVIEW_KEYS = listOf(QuickKey.Esc, QuickKey.Ctrl, QuickKey.Up, QuickKey.Left, QuickKey.Right)

/** 行的上下留白。设置项本身是两行文字，够高了，靠留白撑开只会让一屏放不下几条。 */
private val ROW_PADDING = 8.dp

/**
 * 版本 + 检查更新，一行。
 *
 * 「装的是哪个版本」和「有没有新版」本来就是同一个问题的两半，拆成两行等于让用户自己对照着看。
 * 合完主体点击是检查更新——这是这行唯一的动作；关于页（Apache-2.0 要求的 NOTICE 在那儿，
 * 见 [AboutScreen]）退成行尾的图标，低频但不能没有。
 */
@Composable
private fun VersionRow(
    version: String,
    state: UpdateUiState,
    onCheck: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val value = when (state) {
        UpdateUiState.Idle -> stringResource(R.string.update_check_hint)
        UpdateUiState.Checking -> stringResource(R.string.update_checking)
        UpdateUiState.UpToDate -> stringResource(R.string.update_up_to_date)
        is UpdateUiState.Available -> stringResource(R.string.update_available, state.release.version)
        is UpdateUiState.Downloading -> stringResource(R.string.update_downloading, state.percent)
        is UpdateUiState.NeedsPermission -> stringResource(R.string.update_needs_permission)
        is UpdateUiState.Failed -> state.verdict?.let { stringResource(it.labelRes) }
            ?: stringResource(R.string.update_failed, state.reason.orEmpty())

        is UpdateUiState.InstallFailed -> stringResource(R.string.update_install_failed, state.message)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onCheck)
                .padding(horizontal = 16.dp, vertical = ROW_PADDING),
        ) {
            Text(
                text = stringResource(R.string.about_version, version),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // 失败那两支拼的是网络异常 / 系统安装器原样吐回来的话，长度不受控——
                // 这一行在整页最上面，撑成五六行会把下面所有设置项顶下去。
                // 留两行：看得出是哪类失败，再详细的等日志。
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onOpenAbout, modifier = Modifier.padding(end = 4.dp)) {
            Icon(Icons.Outlined.Info, stringResource(R.string.settings_about))
        }
    }
    if (state is UpdateUiState.Downloading) {
        LinearProgressIndicator(
            progress = { state.percent / 100f },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = option == selected, onClick = { onPick(option) })
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Text(label(option))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * 更新流程里需要用户拍板的两步：确认下载、去开「安装未知应用」。
 * 其余状态（检查中、已最新、失败）只在行内显示，不打断用户。
 */
@Composable
private fun UpdateDialog(
    state: UpdateUiState,
    onInstall: (ReleaseInfo) -> Unit,
    onGrantPermission: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is UpdateUiState.Available -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.update_available, state.release.version)) },
            // 说明可能有几十行（整段 CHANGELOG），必须自己滚：AlertDialog 不管内容超高，
            // 撑爆的结果是「下载并安装」被顶出屏幕，用户只能取消。
            text = {
                val notes = remember(state.release.notes) { ReleaseNotes.format(state.release.notes) }
                Text(
                    text = notes.ifBlank { stringResource(R.string.update_no_notes) },
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { onInstall(state.release) }) {
                    Text(stringResource(R.string.update_download))
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )

        is UpdateUiState.NeedsPermission -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.update_needs_permission)) },
            text = { Text(stringResource(R.string.update_needs_permission_message)) },
            confirmButton = {
                TextButton(onClick = onGrantPermission) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )

        else -> Unit
    }
}

private val ThemeOption.labelRes: Int
    get() = when (this) {
        ThemeOption.Follow -> R.string.settings_follow_system
        ThemeOption.Light -> R.string.settings_theme_light
        ThemeOption.Dark -> R.string.settings_theme_dark
    }

private val LanguageOption.labelRes: Int
    get() = when (this) {
        LanguageOption.Follow -> R.string.settings_follow_system
        LanguageOption.Chinese -> R.string.settings_language_zh
        LanguageOption.English -> R.string.settings_language_en
    }

private val TerminalPalette.labelRes: Int
    get() = when (this) {
        TerminalPalette.Default -> R.string.settings_palette_default
        TerminalPalette.SolarizedDark -> R.string.settings_palette_solarized_dark
        TerminalPalette.GruvboxDark -> R.string.settings_palette_gruvbox_dark
    }

private val ApkVerdict.labelRes: Int
    get() = when (this) {
        ApkVerdict.Ok -> R.string.update_verify_ok
        ApkVerdict.Unreadable -> R.string.update_verify_unreadable
        ApkVerdict.PackageMismatch -> R.string.update_verify_package
        ApkVerdict.NotNewer -> R.string.update_verify_not_newer
        ApkVerdict.SignatureMismatch -> R.string.update_verify_signature
        ApkVerdict.TooLarge -> R.string.update_too_large
    }
