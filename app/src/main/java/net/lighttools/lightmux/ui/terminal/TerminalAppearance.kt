package net.lighttools.lightmux.ui.terminal

import com.termux.terminal.TerminalColors
import net.lighttools.lightmux.data.AppSettings
import net.lighttools.lightmux.data.TerminalPalette
import net.lighttools.lightmux.session.TermSessionHandle
import java.util.Properties

/**
 * 终端外观的生效点。
 *
 * 存在的理由是「已经开着的会话也要立刻变色」：配色表在 vendored 内核里有两份——
 * 全局的 `TerminalColors.COLOR_SCHEME`（新建会话从它拷）和每个 emulator 自己的 `mCurrentColors`
 * （OSC 4 可以在运行期改，所以必须是拷贝）。只写全局那份的话，改配色只对新会话生效，
 * 用户会以为设置没保存。
 *
 * 全走 vendored 的**公开 API**（`updateWith` / `reset`），一行都没有改 `terminal-emulator`。
 */
object TerminalAppearance {

    /**
     * 当前字号（sp）。捏合缩放和设置页都改它，[TerminalHostState] 建 View 时读它。
     * 放在这里而不是各页面的 state 里：切到别的会话再回来，字号不该变回去。
     */
    @Volatile
    var textSizeSp: Int = AppSettings.DEFAULT_TERMINAL_TEXT_SIZE_SP

    /**
     * 应用一份设置。幂等，可以被多个观察者重复调用。
     *
     * @param sessions 当前活着的全部会话，包括没在前台渲染的那些
     */
    fun apply(settings: AppSettings, sessions: List<TermSessionHandle>) {
        textSizeSp = settings.terminalTextSizeSp
        applyPalette(settings.palette, sessions)
    }

    private fun applyPalette(palette: TerminalPalette, sessions: List<TermSessionHandle>) {
        val props = Properties()
        palette.colors.forEach { (key, value) -> props.setProperty(key, value) }
        // updateWith 先 reset 再套覆盖项，所以从深色配色切回 Default 时残留的色值会被清干净。
        TerminalColors.COLOR_SCHEME.updateWith(props)
        sessions.forEach { it.session.emulator?.mColors?.reset() }
    }
}
