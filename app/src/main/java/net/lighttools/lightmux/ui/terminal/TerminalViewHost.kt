package net.lighttools.lightmux.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.launch
import net.lighttools.lightmux.LightmuxApp
import net.lighttools.lightmux.R
import com.termux.terminal.KeyHandler
import net.lighttools.lightmux.data.AppSettings
import net.lighttools.lightmux.data.KeyNotation
import net.lighttools.lightmux.data.KeyStroke
import net.lighttools.lightmux.data.NamedKey
import net.lighttools.lightmux.session.TermSessionHandle
import kotlin.math.roundToInt

/**
 * 终端页的 View 侧脏活全在这里：创建 [TerminalView]、实现两个 client 接口、生命周期收尾。
 *
 * 单独一层的理由是寿命不同——会话活在 Application 作用域，[TerminalView] 每次进页面都要新建一个
 * （PRD §6.2）。中间这层状态对象跟着页面走，负责把两者接上又能干净断开。
 */
@Stable
class TerminalHostState(
    private val context: Context,
    val handle: TermSessionHandle,
    /** 捏合缩放改完字号后落盘，不然退出 app 就丢了。 */
    private val onTextSizeChanged: (Int) -> Unit = {},
) {

    var view: TerminalView? = null
        private set

    /** 粘滞修饰键。View 的 client 会同步读它，所以只能在主线程改。 */
    var modifiers by mutableStateOf(StickyModifiers.NONE)
        private set

    /** 是否处于文本选择模式。终端页要靠它决定返回键先做什么。 */
    var selecting by mutableStateOf(false)
        private set

    /**
     * 屏幕更新的节拍，端口嗅探拿它当触发信号（见 [PortHintBar]）。
     *
     * 用它而不是定时轮询：终端多数时候是静止的，为一个「有没有新地址」的问题每秒醒一次不值当。
     * 选 StateFlow 是图它自带合并——刷屏的构建日志会把这中间几百次更新收敛成一次。
     */
    private val _screenTicks = MutableStateFlow(0L)
    val screenTicks: StateFlow<Long> = _screenTicks.asStateFlow()

    /**
     * 当前屏幕上的文本，外加一小段滚屏历史。**只能在主线程调用**——emulator 归主线程独占。
     *
     * 带上历史是因为嗅探是节流跑的：dev server 打完地址紧接着刷一串日志，
     * 等这边醒过来时那行地址早滚上去了。行号越界由 `getSelectedText` 自己夹住。
     */
    fun screenText(scrollbackRows: Int = SNIFF_SCROLLBACK_ROWS): String {
        val emulator = handle.session.emulator ?: return ""
        return runCatching {
            // joinBackLines：折行的 URL 要接回来，不然 80 列一断就认不出是同一个地址了
            emulator.screen.getSelectedText(
                0,
                -scrollbackRows,
                emulator.mColumns,
                emulator.mRows - 1,
                true,
                false,
            )
        }.getOrElse { "" }
    }

    fun toggleCtrl() {
        modifiers = modifiers.toggleCtrl()
    }

    fun toggleAlt() {
        modifiers = modifiers.toggleAlt()
    }

    /** Esc / Tab / 方向键这类走 keyCode 的键。 */
    fun sendKeyCode(keyCode: Int) {
        val v = view ?: return
        // handleKeyCode 里会直接取 emulator，尺寸还没测出来时它是 null，先挡住。
        if (v.mEmulator == null) return
        if (v.isSelectingText) v.stopTextSelectionMode()
        v.handleKeyCode(keyCode, modifiers.keyMod())
        modifiers = modifiers.consumed()
    }

    /**
     * `-` `|` `~` `/` 这类字面量键。
     *
     * 走 [TerminalView.inputCodePoint] 而不是直接往 session 写字节：Ctrl 的字符变换
     * （`Ctrl-/` → 0x1F 之类）实现在那一层，绕过去粘滞 Ctrl 对这些键就失效了。
     */
    fun sendChar(c: Char) {
        val v = view ?: return
        if (v.isSelectingText) v.stopTextSelectionMode()
        v.inputCodePoint(c.code, modifiers.ctrl, modifiers.alt)
        modifiers = modifiers.consumed()
    }

    /**
     * 组合键（`^C` `^D`…）。Ctrl 恒为真，不看粘滞态。
     *
     * 粘滞态照样清掉：用户按下粘滞 Ctrl 又改点了 `^C`，那一下的意图就是 `^C` 本身，
     * 留着修饰键会让下一个字符莫名其妙也带上 Ctrl。
     */
    fun sendCtrlChar(c: Char) {
        val v = view ?: return
        if (v.isSelectingText) v.stopTextSelectionMode()
        v.inputCodePoint(c.code, true, modifiers.alt)
        modifiers = modifiers.consumed()
    }

    /**
     * 自定义键的按键序列（[KeyNotation]）：逐击发出，修饰键全按序列里写明的来。
     *
     * 粘滞键**不叠加**到序列上——`C-b d` 的每一击发什么是用户写死的，叠上去等于改写他的意思；
     * 但照样清掉（同 [sendCtrlChar]）。具名键走 [TerminalView.handleKeyCode]，让 `C-Up` `M-Left`
     * 这类带修饰的方向键由 [KeyHandler] 拼出 `\033[1;5A`；字符走 [TerminalView.inputCodePoint]，
     * Ctrl 的字符变换与 Alt 的 ESC 前缀都在那一层。
     */
    fun sendKeys(strokes: List<KeyStroke>) {
        val v = view ?: return
        if (v.mEmulator == null) return
        if (v.isSelectingText) v.stopTextSelectionMode()
        modifiers = StickyModifiers.NONE
        strokes.forEach { stroke ->
            val named = stroke.named
            if (named != null) {
                v.handleKeyCode(named.keyCode(), stroke.keyMod())
            } else {
                val c = stroke.char ?: return@forEach
                v.inputCodePoint((if (stroke.shift) c.uppercaseChar() else c).code, stroke.ctrl, stroke.alt)
            }
        }
    }

    /**
     * 快捷命令：整串填进终端，**不替用户回车**。
     *
     * 走 `emulator.paste` 而不是逐字符 [sendChar]：那一层带括号粘贴（DECSET 2004）与换行归一，
     * 直接灌字符会让 vim 把它当成连续键入而疯狂缩进。
     */
    fun sendText(text: String) {
        val v = view ?: return
        if (v.isSelectingText) v.stopTextSelectionMode()
        v.mEmulator?.paste(text)
    }

    fun stopSelection() {
        view?.stopTextSelectionMode()
    }

    /**
     * 设置页改完外观后刷新当前这块 View。
     *
     * 配色的落地在 [TerminalAppearance] 里（它要照顾到没在前台渲染的会话），这里只补两件
     * 只有前台 View 才做得到的事：换字号（会重算行列并 resize 远端）、重画一帧。
     */
    fun applyAppearance(settings: AppSettings, sessions: List<TermSessionHandle>) {
        TerminalAppearance.apply(settings, sessions)
        val v = view ?: return
        v.setTextSize(textSizePx)
        v.onScreenUpdated()
    }

    fun showKeyboard() {
        val v = view ?: return
        v.requestFocus()
        val imm = v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
    }

    /** 快速切换抽屉拉开时收键盘：抽屉挂在根上，吃不到终端 Scaffold 的 imePadding，会被盖住下半截。 */
    fun hideKeyboard() {
        val v = view ?: return
        val imm = v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    internal fun attach(v: TerminalView) {
        view = v
        v.setTerminalViewClient(viewClient)
        v.setTextSize(textSizePx)
        v.setTypeface(terminalTypeface(v.context))
        // 终端页开着就别让屏幕灭——用户经常盯着一条正在跑的命令。
        v.keepScreenOn = true
        v.isFocusable = true
        v.isFocusableInTouchMode = true
        // 关键：attach 到 SessionManager 里既有的会话，绝不新建。
        // 已有 emulator 时 updateSize 只 resize，滚屏历史与连接照旧（TerminalSession 里保证）。
        v.attachSession(handle.session)
        v.requestFocus()
    }

    internal fun detach() {
        view?.keepScreenOn = false
        view = null
    }

    /** 终端内核 → UI。挂到 [TermSessionHandle.client] 上，页面走了就摘掉，会话继续跑。 */
    internal val sessionClient: TerminalSessionClient = object : TerminalSessionClient {

        override fun onTextChanged(changedSession: TerminalSession) {
            view?.onScreenUpdated()
            _screenTicks.value++
        }

        // 标题由 [TermSessionHandle] 自己记着给主页用；终端页没有顶栏，这里不需要镜像一份
        override fun onTitleChanged(changedSession: TerminalSession) = Unit

        override fun onSessionFinished(finishedSession: TerminalSession) {
            // 状态由 handle.state 驱动 UI，这里不用做事。
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            clip.setPrimaryClip(ClipData.newPlainText("lightmux", text ?: return))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            val text = clip.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
            // 走 emulator.paste 才有括号粘贴（DECSET 2004）与换行归一，直接 write 会让 vim 疯狂缩进。
            view?.mEmulator?.paste(text)
        }

        override fun onBell(session: TerminalSession) = Unit

        override fun onColorsChanged(session: TerminalSession) {
            view?.onScreenUpdated()
        }

        override fun onTerminalCursorStateChange(state: Boolean) = Unit

        override fun getTerminalCursorStyle(): Int? = null

        override fun logError(tag: String?, message: String?) {
            Log.e(tag ?: TAG, message.orEmpty())
        }

        override fun logWarn(tag: String?, message: String?) {
            Log.w(tag ?: TAG, message.orEmpty())
        }

        override fun logInfo(tag: String?, message: String?) {
            Log.i(tag ?: TAG, message.orEmpty())
        }

        override fun logDebug(tag: String?, message: String?) {
            Log.d(tag ?: TAG, message.orEmpty())
        }

        override fun logVerbose(tag: String?, message: String?) {
            Log.v(tag ?: TAG, message.orEmpty())
        }

        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Log.e(tag ?: TAG, message.orEmpty(), e)
        }

        override fun logStackTrace(tag: String?, e: Exception?) {
            Log.e(tag ?: TAG, "", e)
        }
    }

    /** View → UI。粘滞修饰键就是从这里被 [TerminalView] 读走的。 */
    private val viewClient: TerminalViewClient = object : TerminalViewClient {

        /** 捏合缩放。入参是累计缩放系数，返回值会被 View 存回去，所以改完字号必须归 1。 */
        override fun onScale(scale: Float): Float {
            if (scale < 0.9f || scale > 1.1f) {
                val next = (TerminalAppearance.textSizeSp * scale).roundToInt()
                    .coerceIn(
                        AppSettings.MIN_TERMINAL_TEXT_SIZE_SP,
                        AppSettings.MAX_TERMINAL_TEXT_SIZE_SP,
                    )
                if (next != TerminalAppearance.textSizeSp) {
                    TerminalAppearance.textSizeSp = next
                    view?.setTextSize(textSizePx)
                    onTextSizeChanged(next)
                }
                return 1.0f
            }
            return scale
        }

        override fun onSingleTapUp(e: MotionEvent) {
            showKeyboard()
        }

        /** 返回键留给导航。中央页面栈是唯一的层级机制，把它吃掉等于让用户出不去。 */
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false

        /** 保持上游默认的 TYPE_NULL 输入法模式，兼容性问题留到 M4 做成设置项。 */
        override fun shouldEnforceCharBasedInput(): Boolean = false

        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

        override fun isTerminalViewSelected(): Boolean = true

        override fun copyModeChanged(copyMode: Boolean) {
            selecting = copyMode
        }

        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false

        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
            // 硬件键盘走 keyCode 通路，抬手即消费掉粘滞键。软键盘走 onCodePoint，那边另有一处。
            modifiers = modifiers.consumed()
            return false
        }

        override fun onLongPress(event: MotionEvent): Boolean = false

        override fun readControlKey(): Boolean = modifiers.ctrl

        override fun readAltKey(): Boolean = modifiers.alt

        override fun readShiftKey(): Boolean = false

        override fun readFnKey(): Boolean = false

        /**
         * 在这里消费粘滞键是安全的：[TerminalView.inputCodePoint] 已经先把 ctrl/alt 读进局部变量
         * 才调本方法，清掉不影响这一次的字符变换。返回 false = 不拦截按键。
         */
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
            modifiers = modifiers.consumed()
            return false
        }

        override fun onEmulatorSet() = Unit

        override fun logError(tag: String?, message: String?) = sessionClient.logError(tag, message)

        override fun logWarn(tag: String?, message: String?) = sessionClient.logWarn(tag, message)

        override fun logInfo(tag: String?, message: String?) = sessionClient.logInfo(tag, message)

        override fun logDebug(tag: String?, message: String?) = sessionClient.logDebug(tag, message)

        override fun logVerbose(tag: String?, message: String?) = sessionClient.logVerbose(tag, message)

        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) =
            sessionClient.logStackTraceWithMessage(tag, message, e)

        override fun logStackTrace(tag: String?, e: Exception?) = sessionClient.logStackTrace(tag, e)
    }

    /** [TerminalView.setTextSize] 收的是像素，设置项存的是 sp，换算点只此一处。 */
    private val textSizePx: Int
        get() = (context.resources.displayMetrics.density * TerminalAppearance.textSizeSp).roundToInt()

    private companion object {
        const val TAG = "TerminalView"

        /**
         * 嗅探时多看几行历史。
         *
         * 40 行 ≈ 一屏半，够补上「地址打出来之后被日志顶上去」的那一小段，
         * 又不至于让每次扫描都去啃整个滚屏缓冲（默认几千行）。
         */
        const val SNIFF_SCROLLBACK_ROWS = 40
    }
}

@Composable
fun rememberTerminalHostState(handle: TermSessionHandle): TerminalHostState {
    val context = LocalContext.current
    val app = context.applicationContext as LightmuxApp
    return remember(handle) {
        TerminalHostState(context, handle) { sp ->
            // 用 Application 的作用域：页面可能在写盘完成前就被销毁了
            app.scope.launch { app.settingsStore.setTerminalTextSize(sp) }
        }
    }
}

/**
 * 把 [TerminalView] 放进 Compose 树。
 *
 * 每次进页面 factory 都会造一个全新的 View 并 attach 到既有会话上——这是常驻会话的关键路径，
 * 别改成把 View 缓存起来复用，那样 Compose 的重组与 View 的父子关系会打架。
 */
@Composable
fun TerminalViewHost(state: TerminalHostState, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as LightmuxApp

    // 初值取同步快照而不是 AppSettings()：拿默认值垫一帧会让终端先闪一下内置配色再跳到用户选的那套。
    val settings by app.settingsStore.settings
        .collectAsState(initial = remember { app.settingsStore.blockingSnapshot() })
    val sessions by app.sessionManager.sessions.collectAsState()

    AndroidView(
        modifier = modifier,
        factory = { ctx -> TerminalView(ctx, null).also { state.attach(it) } },
        onRelease = { state.detach() },
    )

    // 设置页改完外观，正开着的这个会话要立刻变，而不是等下次重进页面。
    LaunchedEffect(settings, sessions) { state.applyAppearance(settings, sessions) }

    DisposableEffect(state) {
        state.handle.client = state.sessionClient
        // 页面走了就摘掉回调：会话继续跑，只是没人渲染（TermSessionHandle 里会把回调吞掉）。
        onDispose { state.handle.client = null }
    }
}

/** [NamedKey] 到 Android keyCode。映射放这一层，解析器那边才不用引 Android。 */
private fun NamedKey.keyCode(): Int = when (this) {
    NamedKey.Enter -> KeyEvent.KEYCODE_ENTER
    NamedKey.Escape -> KeyEvent.KEYCODE_ESCAPE
    NamedKey.Tab -> KeyEvent.KEYCODE_TAB
    NamedKey.Backspace -> KeyEvent.KEYCODE_DEL
    NamedKey.Delete -> KeyEvent.KEYCODE_FORWARD_DEL
    NamedKey.Insert -> KeyEvent.KEYCODE_INSERT
    NamedKey.Home -> KeyEvent.KEYCODE_MOVE_HOME
    NamedKey.End -> KeyEvent.KEYCODE_MOVE_END
    NamedKey.PageUp -> KeyEvent.KEYCODE_PAGE_UP
    NamedKey.PageDown -> KeyEvent.KEYCODE_PAGE_DOWN
    NamedKey.Up -> KeyEvent.KEYCODE_DPAD_UP
    NamedKey.Down -> KeyEvent.KEYCODE_DPAD_DOWN
    NamedKey.Left -> KeyEvent.KEYCODE_DPAD_LEFT
    NamedKey.Right -> KeyEvent.KEYCODE_DPAD_RIGHT
    NamedKey.F1 -> KeyEvent.KEYCODE_F1
    NamedKey.F2 -> KeyEvent.KEYCODE_F2
    NamedKey.F3 -> KeyEvent.KEYCODE_F3
    NamedKey.F4 -> KeyEvent.KEYCODE_F4
    NamedKey.F5 -> KeyEvent.KEYCODE_F5
    NamedKey.F6 -> KeyEvent.KEYCODE_F6
    NamedKey.F7 -> KeyEvent.KEYCODE_F7
    NamedKey.F8 -> KeyEvent.KEYCODE_F8
    NamedKey.F9 -> KeyEvent.KEYCODE_F9
    NamedKey.F10 -> KeyEvent.KEYCODE_F10
    NamedKey.F11 -> KeyEvent.KEYCODE_F11
    NamedKey.F12 -> KeyEvent.KEYCODE_F12
}

private fun KeyStroke.keyMod(): Int =
    (if (ctrl) KeyHandler.KEYMOD_CTRL else 0) or
        (if (alt) KeyHandler.KEYMOD_ALT else 0) or
        (if (shift) KeyHandler.KEYMOD_SHIFT else 0)

/**
 * 终端字体：内置的 JetBrains Mono Regular，取不到才退回系统等宽。
 *
 * 不用 [Typeface.MONOSPACE]（Android 上是 Droid Sans Mono）有两条实打实的理由：
 * 一是它**不含制表符**（U+2500 系列），htop / vim 的分割线只能回退到别的字体，宽度与格宽对不上，
 * `TerminalRenderer` 会把整段横向拉伸来补偿，线条就歪了；
 * 二是它字面偏小（x-height 0.53em），同样 0.6em 的格子里 `l`、`i` 只剩一根光竖线，密排时发虚。
 *
 * [ResourcesCompat.getFont] 自带解析缓存，每次进终端页新建 View 时调一次不会重复读盘。
 */
internal fun terminalTypeface(context: Context): Typeface =
    ResourcesCompat.getFont(context, R.font.jetbrains_mono_regular) ?: Typeface.MONOSPACE
