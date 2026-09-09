package net.lighttools.lightmux.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/**
 * 全应用的页面集合。
 *
 * 砍掉底部导航后返回键是唯一的层级机制，所以路由用一个**中央栈**而不是各页面自管 BackHandler——
 * 后者每加一个页面就要记得补一条返回路径，漏了不是「返回无效」而是**直接退出 app**。
 * 中央栈让「补返回路径」这件事不可能被忘记。
 */
sealed interface Screen {

    /** 唯一的常驻页：主机 → tmux 会话 → 窗口 三级树 */
    data object Home : Screen

    /** 终端。sessionId 指向 SessionManager 里常驻的会话 */
    data class Terminal(val sessionId: String) : Screen

    /** 主机状态快照 */
    data class Monitor(val hostId: String) : Screen

    /** 端口转发：把服务端的端口映射到手机本地 */
    data class Forward(val hostId: String) : Screen

    /**
     * SFTP 文件浏览，主页那条路进来时的整页形态。
     *
     * 从终端进来的那条路**不进这个栈**：它是叠在终端上的半屏面板（见 `LightmuxRoot` 里的
     * `filesSheet` 与 [net.lighttools.lightmux.ui.files.FilesSheet]）。
     */
    data class Files(val hostId: String, val path: String) : Screen

    /** 小文本文件的应用内编辑 */
    data class FileEdit(val hostId: String, val path: String) : Screen

    /**
     * 主机增改。hostId 为 null 表示新建。
     *
     * @param duplicate 以 hostId 那台主机为模板新建一台。**这个标记必须活过进程重建**：
     *                  恢复成普通编辑的话，用户接着一保存就把源主机覆盖了
     */
    data class HostEdit(val hostId: String?, val duplicate: Boolean = false) : Screen

    /**
     * 内置浏览器，装的是转发出来的那个端口。
     *
     * 不绑主机：地址栏能改，用户可以从转发出来的页面点到任何地方去，再说「这一页属于哪台主机」
     * 就是假的了。
     */
    data class Web(val url: String) : Screen

    data object Settings : Screen

    /** 密钥库：一把钥匙可被多台主机引用 */
    data object Keys : Screen

    data object About : Screen
}

/**
 * 页面栈。栈底恒为 [Screen.Home]，[pop] 在只剩栈底时返回 false，由调用方交还系统（退出 app）。
 */
class Navigator(initial: List<Screen> = listOf(Screen.Home)) {

    var stack by mutableStateOf(initial)
        private set

    val current: Screen get() = stack.last()

    val canPop: Boolean get() = stack.size > 1

    fun push(screen: Screen) {
        stack = stack + screen
    }

    /**
     * 切到某个终端会话：终端之间互切不叠栈，否则连开五个会话后要按五次返回才回得了主页。
     * 栈始终保持 [Home, Terminal] 两层。
     */
    fun switchToTerminal(sessionId: String) {
        stack = listOf(Screen.Home, Screen.Terminal(sessionId))
    }

    fun pop(): Boolean {
        if (!canPop) return false
        stack = stack.dropLast(1)
        return true
    }

    fun popTo(screen: Screen) {
        val index = stack.indexOfLast { it == screen }
        if (index >= 0) stack = stack.subList(0, index + 1)
    }

    companion object {
        /**
         * 进程被回收后恢复到「哪一页」。会话本身不可恢复（连接已断），
         * 所以只恢复非终端页；栈里的 Terminal 一律折回主页，避免打开一个空壳终端。
         */
        val Saver: Saver<Navigator, *> = listSaver(
            save = { nav ->
                nav.stack.mapNotNull { screen ->
                    when (screen) {
                        is Screen.Home -> "home"
                        is Screen.Settings -> "settings"
                        is Screen.Keys -> "keys"
                        is Screen.About -> "about"
                        is Screen.Monitor -> "monitor:${screen.hostId}"
                        is Screen.Forward -> "forward:${screen.hostId}"
                        is Screen.HostEdit -> if (screen.duplicate) "hostCopy:${screen.hostId.orEmpty()}"
                        else "hostEdit:${screen.hostId.orEmpty()}"
                        is Screen.Files -> "files:${screen.hostId}:${screen.path}"
                        // 网页只恢复地址，重新加载一遍。翻过的历史找不回来，但那比把用户
                        // 弹回主页强得多——何况本地端口页刷一下就是原样
                        is Screen.Web -> "web:${screen.url}"
                        // 终端与文件编辑不恢复
                        else -> null
                    }
                }
            },
            restore = { saved ->
                val screens = saved.mapNotNull { s ->
                    when {
                        s == "home" -> Screen.Home
                        s == "settings" -> Screen.Settings
                        s == "keys" -> Screen.Keys
                        s == "about" -> Screen.About
                        s.startsWith("monitor:") -> Screen.Monitor(s.removePrefix("monitor:"))
                        s.startsWith("forward:") -> Screen.Forward(s.removePrefix("forward:"))
                        s.startsWith("web:") -> Screen.Web(s.removePrefix("web:"))
                        s.startsWith("hostEdit:") ->
                            Screen.HostEdit(s.removePrefix("hostEdit:").ifEmpty { null })
                        s.startsWith("hostCopy:") ->
                            Screen.HostEdit(s.removePrefix("hostCopy:").ifEmpty { null }, duplicate = true)
                        s.startsWith("files:") -> {
                            val rest = s.removePrefix("files:")
                            val sep = rest.indexOf(':')
                            if (sep < 0) null
                            else Screen.Files(rest.substring(0, sep), rest.substring(sep + 1))
                        }
                        else -> null
                    }
                }
                Navigator(screens.ifEmpty { listOf(Screen.Home) })
            }
        )
    }
}
