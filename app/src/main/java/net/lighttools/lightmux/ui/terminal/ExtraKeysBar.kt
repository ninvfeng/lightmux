package net.lighttools.lightmux.ui.terminal

import android.view.KeyEvent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import net.lighttools.lightmux.R
import net.lighttools.lightmux.data.QuickCommand
import net.lighttools.lightmux.data.KeyNotation
import net.lighttools.lightmux.data.QuickCustomKey
import net.lighttools.lightmux.data.QuickKey
import net.lighttools.lightmux.data.QuickKeySize
import net.lighttools.lightmux.data.QuickSlot
import net.lighttools.lightmux.ui.common.LocalSwipeOpenGuard

/** 栏上弹出的两张表。 */
private enum class QuickSheet { None, Commands, Keys }

/**
 * 快捷栏那点跨导航要活下来的状态——眼下只有横滚位置。
 *
 * 不能用 `rememberScrollState`：终端页每次进入都重建整棵子树（`key(sessionId)` + 中央栈换页），
 * 页面级状态跟着清空，划到末尾的自定义键会一次次弹回开头。滚动位置属于 ViewModel 层
 * （CLAUDE.md 架构要点 ④）。
 *
 * 不按会话分键：这条栏的键位来自全局设置，每个终端看到的是同一排键，
 * 各存一份只会让「切个会话栏就跳回开头」以另一种形式回来。
 */
class QuickBarViewModel : ViewModel() {
    val scroll = ScrollState(0)
}

/**
 * 快捷栏。
 *
 * 它同时是终端页唯一的显式出口——顶栏已经撤了，整个上半屏留给终端，「返回」就是栏上的一格。
 * 软键盘打不出 `Ctrl`，`Ctrl-B` 出不来 tmux 前缀键就废了，所以这条栏属于核心而非装饰。
 *
 * 顺序与内容由用户定（末尾的「管理」），默认见 [net.lighttools.lightmux.data.QuickBar.DEFAULT_KEYS]。
 */
@Composable
fun ExtraKeysBar(
    state: TerminalHostState,
    slots: List<QuickSlot>,
    keyWidthDp: Int,
    commands: List<QuickCommand>,
    onBack: () -> Unit,
    onFiles: () -> Unit,
    onForward: () -> Unit,
    onSlotsChange: (List<QuickSlot>) -> Unit,
    onCustomKeysChange: (List<QuickCustomKey>) -> Unit,
    onCommandsChange: (List<QuickCommand>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sheet by remember { mutableStateOf(QuickSheet.None) }
    val bar: QuickBarViewModel = viewModel()

    /*
     * 划这条栏是要横滚它自己，不是开抽屉。
     *
     * 抽屉的起手判定挂在祖先节点的 Initial pass 上，比这里早，所以拦不住——只能反过来
     * 报一句「这块别接管」（见 [SwipeOpenGuard]）。离开终端页要还回去，否则监控页、文件页
     * 的下半屏会跟着开不了抽屉。
     */
    val guard = LocalSwipeOpenGuard.current
    DisposableEffect(guard) { onDispose { guard.excludedTop = Float.POSITIVE_INFINITY } }

    /*
     * 两张表都是独立窗口，会被软键盘盖住下半截，所以开表前先收键盘、关表后再要回来。
     *
     * 收键盘这半边是必须的；还回来那半边属于尽力而为——表的窗口正在退场时终端可能还没拿回焦点，
     * 那一下 `showSoftInput` 会静默失败。失败也不碍事：点一下终端就出来（`onSingleTapUp`）。
     */
    LaunchedEffect(sheet) {
        if (sheet == QuickSheet.None) state.showKeyboard() else state.hideKeyboard()
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            // Scaffold 不管 bottomBar 的 insets，不加这一下最底下一排键会压在导航栏里。
            // 键盘弹出时 Scaffold 的 imePadding 已经把这段消费掉了，不会叠成两层空白。
            .navigationBarsPadding()
            .onGloballyPositioned { guard.excludedTop = it.boundsInRoot().top },
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        /*
         * M3 给每个可点击组件强制 48dp 的最小触摸目标：键帽画成 26dp，**布局上仍占 48dp**，
         * 多出来的 22dp 表现为「键与键之间的空档」——设置里的滑块因此拖多少都不见效，
         * 键距参数被这条隐形下限整个吃掉了。这条栏的尺寸由 [QuickKeySize] 说了算。
         *
         * 只关这一处：管理表里的键帽和别处的按钮仍守 48dp。
         */
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            val gap = QuickKeySize.gapDp(keyWidthDp).dp
            Row(
                modifier = Modifier.padding(gap),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(bar.scroll),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    slots.forEach { slot ->
                        QuickSlotCap(
                            slot = slot,
                            active = when ((slot as? QuickSlot.Preset)?.key) {
                                QuickKey.Ctrl -> state.modifiers.ctrl
                                QuickKey.Alt -> state.modifiers.alt
                                else -> false
                            },
                            widthDp = keyWidthDp,
                            onClick = {
                                slot.dispatch(state, onBack, onFiles, onForward) { sheet = QuickSheet.Commands }
                            },
                        )
                    }
                }
                /*
                 * 「管理」钉死在最右边，**留在滚动区外**：键一多就得横划才够得着，
                 * 而这一格恰恰是「键太多/太少要调一下」时才去按的——把入口藏在划到底的地方
                 * 等于让想调栏的人先受一次栏的罪。它也因此不参与排序：
                 * 能把自己排没的那一格，排没了就再也改不回来了。
                 */
                KeyCapFrame(widthDp = keyWidthDp, onClick = { sheet = QuickSheet.Keys }) {
                    CapIcon(Icons.Filled.Tune, stringResource(R.string.quick_bar_manage), keyWidthDp)
                }
            }
        }
    }

    when (sheet) {
        QuickSheet.None -> Unit

        QuickSheet.Commands -> QuickCommandsSheet(
            commands = commands,
            // 默认只填进去不替用户回车（误触一条命令跑到线上机器代价太高），
            // 想让某条点一下就跑，在那条命令上单独勾「按下即执行」。
            onPick = {
                state.sendText(it.text)
                if (it.enter) state.sendKeyCode(KeyEvent.KEYCODE_ENTER)
                sheet = QuickSheet.None
            },
            onChange = onCommandsChange,
            onDismiss = { sheet = QuickSheet.None },
        )

        QuickSheet.Keys -> QuickKeysSheet(
            slots = slots,
            onChange = onSlotsChange,
            onCustomKeysChange = onCustomKeysChange,
            onDismiss = { sheet = QuickSheet.None },
        )
    }
}

/**
 * 一格做什么。自定义键就是「把那串文本送进终端」，勾了 [QuickCustomKey.enter] 再补一个回车；
 * 序列模式（[QuickCustomKey.keys]）则逐击发出。解析不过的序列**什么都不发**——
 * 存盘前弹窗已经拦过一道，走到这里的只会是别处写坏的数据，发半截比不发更糟。
 *
 * 走 [TerminalHostState.sendText] 而不是逐字符发：文本里的 `-`、`|` 不该被粘滞 Ctrl 改写。
 */
private fun QuickSlot.dispatch(
    state: TerminalHostState,
    onBack: () -> Unit,
    onFiles: () -> Unit,
    onForward: () -> Unit,
    onCommands: () -> Unit,
) {
    when (this) {
        is QuickSlot.Preset -> key.dispatch(state, onBack, onFiles, onForward, onCommands)

        is QuickSlot.Custom -> if (key.keys) {
            KeyNotation.parse(key.text)?.let(state::sendKeys)
        } else {
            state.sendText(key.text)
            if (key.enter) state.sendKeyCode(KeyEvent.KEYCODE_ENTER)
        }
    }
}

/**
 * 一格键做什么。
 *
 * 末尾的 `else` 覆盖所有字面量键：**键帽画什么就发什么**，所以往 [QuickKey] 里加个符号键
 * 不用动这里。走 [TerminalHostState.sendChar] 而不是直接写字节，是为了让粘滞 `Ctrl` 对它们也生效。
 */
private fun QuickKey.dispatch(
    state: TerminalHostState,
    onBack: () -> Unit,
    onFiles: () -> Unit,
    onForward: () -> Unit,
    onCommands: () -> Unit,
) {
    // 组合键先接走：它们的 label 是 `^C` 这样两个字符，落到末尾的 else 会发出一个 `^`
    ctrlChar?.let {
        state.sendCtrlChar(it)
        return
    }
    when (this) {
        QuickKey.Back -> onBack()
        QuickKey.Commands -> onCommands()
        QuickKey.Files -> onFiles()
        QuickKey.Forward -> onForward()
        QuickKey.Enter -> state.sendKeyCode(KeyEvent.KEYCODE_ENTER)
        QuickKey.Esc -> state.sendKeyCode(KeyEvent.KEYCODE_ESCAPE)
        QuickKey.Tab -> state.sendKeyCode(KeyEvent.KEYCODE_TAB)
        QuickKey.Ctrl -> state.toggleCtrl()
        QuickKey.Alt -> state.toggleAlt()
        QuickKey.Up -> state.sendKeyCode(KeyEvent.KEYCODE_DPAD_UP)
        QuickKey.Down -> state.sendKeyCode(KeyEvent.KEYCODE_DPAD_DOWN)
        QuickKey.Left -> state.sendKeyCode(KeyEvent.KEYCODE_DPAD_LEFT)
        QuickKey.Right -> state.sendKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)
        QuickKey.Home -> state.sendKeyCode(KeyEvent.KEYCODE_MOVE_HOME)
        QuickKey.End -> state.sendKeyCode(KeyEvent.KEYCODE_MOVE_END)
        QuickKey.PageUp -> state.sendKeyCode(KeyEvent.KEYCODE_PAGE_UP)
        QuickKey.PageDown -> state.sendKeyCode(KeyEvent.KEYCODE_PAGE_DOWN)
        QuickKey.Del -> state.sendKeyCode(KeyEvent.KEYCODE_FORWARD_DEL)
        else -> state.sendChar(label!!.first())
    }
}

/** 一格键帽，预设与自定义都走它。管理表里也用，所以 [onClick] 可以为 null。 */
@Composable
internal fun QuickSlotCap(
    slot: QuickSlot,
    active: Boolean = false,
    widthDp: Int = QuickKeySize.SHEET_WIDTH_DP,
    onClick: (() -> Unit)? = null,
) = when (slot) {
    is QuickSlot.Preset -> QuickKeyCap(slot.key, active, widthDp, onClick)

    is QuickSlot.Custom -> KeyCapFrame(widthDp = widthDp, onClick = onClick) {
        CapLabel(slot.key.label, active, widthDp)
    }
}

/**
 * 一格键帽。管理表里也用它，所以 [onClick] 可以为 null（那时只是个静态样子）。
 *
 * @param active 粘滞修饰键按下中。高亮是唯一的反馈——没有它用户不知道下一个键会不会带 Ctrl
 * @param widthDp 只有栏上那排跟设置的滑块走；管理表里的样品固定用 [QuickKeySize.SHEET_WIDTH_DP]，
 *   那是给人看清楚「这是哪个键」的，不该跟着一起缩
 */
@Composable
internal fun QuickKeyCap(
    key: QuickKey,
    active: Boolean = false,
    widthDp: Int = QuickKeySize.SHEET_WIDTH_DP,
    onClick: (() -> Unit)? = null,
) {
    val label = key.label
    KeyCapFrame(widthDp = widthDp, active = active, onClick = onClick) {
        if (label != null) {
            CapLabel(label, active, widthDp)
        } else {
            // 「返回」「命令」「文件」「转发」是动作不是按键，画符号说不清，用图标
            when (key) {
                QuickKey.Back ->
                    CapIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), widthDp)

                QuickKey.Files ->
                    CapIcon(Icons.Filled.Folder, stringResource(R.string.files), widthDp)

                QuickKey.Forward ->
                    CapIcon(
                        Icons.Filled.SettingsEthernet,
                        stringResource(R.string.forward_title),
                        widthDp,
                    )

                else ->
                    CapIcon(Icons.AutoMirrored.Filled.List, stringResource(R.string.quick_commands), widthDp)
            }
        }
    }
}

/** 键帽上的字。宽标签（`PgDn`、自定义的 `claude`）靠 [KeyCapFrame] 的最小尺寸自己撑开。 */
@Composable
internal fun CapLabel(label: String, active: Boolean, widthDp: Int) {
    Text(
        text = label,
        modifier = Modifier.padding(horizontal = QuickKeySize.labelPadDp(widthDp).dp),
        textAlign = TextAlign.Center,
        maxLines = 1,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
    )
}

/** 图标跟着键帽缩，否则默认 26dp 的键面上会挤成一个几乎顶到边的方块。 */
@Composable
private fun CapIcon(icon: ImageVector, contentDescription: String, widthDp: Int) {
    Icon(icon, contentDescription, modifier = Modifier.size(QuickKeySize.iconDp(widthDp).dp))
}

/** 键帽的壳。尺寸是**下限**不是定值：`PgDn` 这类宽标签自己撑开，不会被裁字。 */
@Composable
internal fun KeyCapFrame(
    widthDp: Int,
    active: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(5.dp)
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val onColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val body = @Composable {
        Box(
            modifier = Modifier.defaultMinSize(
                minWidth = QuickKeySize.clamp(widthDp).dp,
                minHeight = QuickKeySize.capHeightDp(widthDp).dp,
            ),
            contentAlignment = Alignment.Center,
            content = { content() },
        )
    }
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = color, contentColor = onColor) { body() }
    } else {
        Surface(shape = shape, color = color, contentColor = onColor) { body() }
    }
}
