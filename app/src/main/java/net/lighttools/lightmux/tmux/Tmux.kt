package net.lighttools.lightmux.tmux

/**
 * tmux 命令生成与输出解析。**纯 Kotlin，零 Android 依赖**——本机没有真机，
 * 这一层的单测是整个 M2 唯一能自证正确的手段（PRD §6.2）。
 *
 * 调用方（[TmuxRepository]）只负责「把命令丢给 `SshConnection.exec()`、把回来的字符串喂进来」。
 */
object Tmux {

    // ---- 哨兵 ----------------------------------------------------------------
    // 一次 exec 干完探测 + 列会话 + 列窗口，输出靠这几行切段。
    // 用整行精确匹配：会话名/窗口名再怪也造不出这样的整行（它们前面一定有数值字段）。

    const val MARKER_TMUX = "__LM_TMUX__:"
    const val MARKER_PM = "__LM_PM__:"

    /**
     * tmux server 的 pid，**窗口 `@id` 的有效期就绑在它身上**。
     *
     * `@id` 和 `$id` 一样只在单个 server 生命周期内有效，server 重启后编号从头分配。
     * 而我们把整段探测输出缓存进了 DataStore，冷启动会连 `@id` 一起还原出来——
     * 拿着上一个 server 的 `@3` 去 `kill-window`，关掉的是这个 server 里恰好也叫 `@3` 的**另一个窗口**，
     * 而且不可逆。带上 pid，破坏性动作才有得校验（见 [killWindowCommand]）。
     */
    const val MARKER_SERVER = "__LM_SERVER__:"
    const val MARKER_SESSIONS = "__LM_SESSIONS__"
    const val MARKER_WINDOWS = "__LM_WINDOWS__"
    const val MARKER_END = "__LM_END__"
    const val MARKER_RC = "__LM_RC__:"

    /**
     * 取 server pid 的 shell 片段。探测与动作校验必须用**同一个**表达式，
     * 否则两边的取值方式一旦分叉，校验就永远不相等（= 功能全废，还没人看得出来）。
     *
     * stderr 丢掉：没有 server 时 tmux 会往 stderr 吼「no server running」，那不是错误。
     */
    const val SERVER_PID_EXPR = "tmux display-message -p '#{pid}' 2>/dev/null"

    /**
     * 认得出来的包管理器，**按优先级排**：Debian 系上 `apt-get` 与 `yum` 可能同时装着
     * （有人手动装过 yum），排在前面的先中。
     */
    val PACKAGE_MANAGERS = listOf("apt-get", "dnf", "yum", "pacman", "apk", "zypper", "brew", "pkg")

    /**
     * 会话行格式：`已附加客户端数:窗口数:$会话id:会话名`
     *
     * 三条约束绑在一起：
     * - 分隔符用 `:` 不用 TAB——tmux 会把名字里的 TAB 替换成 `_`，用 TAB 分隔就分不清
     *   「名字里本来有 TAB」和「这是分隔符」。
     * - **名字一律放行尾**，因为会话名本身可以包含 `:`（`tmux new -s a:b` 完全合法）。
     * - 名字前面全是数值/id 字段（无 `:`），所以解析时按 `split(limit = N+1)` 取前 N 段，
     *   剩下的整段都是名字。
     */
    const val SESSION_FORMAT = "#{session_attached}:#{session_windows}:#{session_id}:#{session_name}"

    /**
     * 窗口行格式：`是否活动:面板数:索引:@窗口id:$会话id:窗口名`
     *
     * 这里用 `$会话id` 而不是会话名来关联所属会话——一行里只能有**一个**可含 `:` 的自由字段，
     * 而窗口名必须占着行尾那个位置。`$id` 只在这一次输出内部用于关联，不会被缓存下来。
     */
    const val WINDOW_FORMAT =
        "#{window_active}:#{window_panes}:#{window_index}:#{window_id}:#{session_id}:#{window_name}"

    /**
     * 探测命令：一条 exec 拿到「有没有 tmux + 有哪些会话 + 有哪些窗口」。
     *
     * 拆成三条命令发就是三次 channel 往返，手机网络下串起来的延迟很难看。
     *
     * `PATH` 要补一下：exec channel 起的是非交互 shell，不读 `.bashrc` / `.zshrc`，
     * tmux 装在 `/usr/local/bin` 或 homebrew 前缀下时会被误判成「没装」。
     * tmux 自己的 stderr 全部丢掉——「no server running」不是错误，是「没有会话」。
     *
     * **没装的分支顺手把包管理器和 uid 也报回来**（[MARKER_PM]）：这样「没装 tmux」那一行
     * 才给得出一条能直接跑的安装命令。装了 tmux 的机器（绝大多数）一条 `command -v` 都不多跑。
     *
     * server pid 那行也在同一条命令里（[MARKER_SERVER]），没有 server 在跑时它就是空串——
     * 那属于正常情况（等价于「没有会话」），不是错误。
     */
    val PROBE_COMMAND: String = listOf(
        "export PATH=\"\$PATH:/usr/local/bin:/opt/homebrew/bin\"",
        "if command -v tmux >/dev/null 2>&1; then echo ${MARKER_TMUX}1; else echo ${MARKER_TMUX}0; " +
            "pm=''; for p in ${PACKAGE_MANAGERS.joinToString(" ")}; do " +
            "if command -v \$p >/dev/null 2>&1; then pm=\$p; break; fi; done; " +
            "echo \"$MARKER_PM\$pm:\$(id -u)\"; fi",
        "echo \"$MARKER_SERVER\$($SERVER_PID_EXPR)\"",
        "echo $MARKER_SESSIONS",
        "tmux list-sessions -F '$SESSION_FORMAT' 2>/dev/null",
        "echo $MARKER_WINDOWS",
        "tmux list-windows -a -F '$WINDOW_FORMAT' 2>/dev/null",
        "echo $MARKER_END",
    ).joinToString("; ")

    // ---- 命令生成 ------------------------------------------------------------

    /**
     * 单引号包裹，并把名字里的 `'` 换成 `'\''`。
     *
     * 用户真的会起 `my session`、`it's mine` 这种名字，不转义轻则命令失败，
     * 重则把名字里的内容当命令执行。
     */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * `-t` 的目标串。加 `=` 前缀强制精确匹配：tmux 的 target-session 默认允许前缀/通配匹配，
     * 不加 `=` 的话 `kill-session -t dev` 有机会打到 `dev2` 上。
     */
    private fun target(name: String): String = quote("=$name")

    /**
     * 「这台机器上跑的还是当初那个 tmux server 吗」的服务端断言。
     *
     * 校验必须在**远端原子完成**：在本地比对 pid 只能证明「几秒前是同一个 server」，
     * 而 `@id` 这类编号在 server 重启后会被重新发给别的窗口，赌的是不可逆的误删。
     *
     * @param serverId 探测时记下的 server pid，见 [MARKER_SERVER]
     */
    private fun requireServer(serverId: String): String =
        "[ \"\$($SERVER_PID_EXPR)\" = ${quote(serverId)} ]"

    /**
     * attach 命令：**存在则附加，否则创建**，一条命令原子完成。
     *
     * 这是缓存过期的兜底（PRD §4.3）：从三分钟前的快照点进一个已经被 kill 的会话，
     * 最坏结果只是新建了一个同名会话，而不是甩用户一句「会话不存在」。
     *
     * **不加 `-D`**：踢掉别的客户端是用户的决定，走主页的「断开其他客户端」
     * （[detachOthersCommand]），不由 attach 顺手替他做——尤其是自动重连也走这条命令。
     */
    fun attachCommand(name: String): String = "tmux new-session -A -s ${quote(name)}"

    /**
     * 先切窗口再 attach。
     *
     * 和「先走 exec 切窗口、再开终端」相比少一次往返，且冷启动（这台主机还没有连接）时
     * 不需要为了切一个窗口先拨一条连接。`select-window` 失败（缓存里的 `@id` 已经没了）
     * 就静默跳过，用户仍然落在会话里——和 `-A` 的兜底是同一个思路。
     *
     * 切窗口可逆，但切错仍然是错的，所以同样带 [requireServer] 校验：pid 对不上就只 attach 不切。
     * `serverId` 为 null（旧版缓存里没有这一行）时退化成纯 attach——落在会话的当前窗口上，
     * 总好过拿一个来路不明的 `@id` 去赌。
     */
    fun attachWindowCommand(name: String, windowId: String, serverId: String?): String {
        val attach = attachCommand(name)
        if (serverId == null) return attach
        return "${requireServer(serverId)} && tmux select-window -t ${quote(windowId)} 2>/dev/null; $attach"
    }

    /**
     * 已 attach 的会话切窗口。**`serverId` 为 null 返回 null**：调用方该先刷新列表，
     * 而不是拿一个可能属于上一个 server 的 `@id` 碰运气。
     */
    fun selectWindowCommand(windowId: String, serverId: String?): String? =
        serverId?.let { "${requireServer(it)} || exit ${ActionResult.STALE}; tmux select-window -t ${quote(windowId)}" }

    /**
     * 在已有会话里开一个新窗口。
     *
     * tmux 自己会把新窗口选为当前窗口，所以这条命令跑完不必再 `select-window`，
     * 之后 attach 上去正好落在新窗口里。
     */
    fun newWindowCommand(session: String): String = "tmux new-window -t ${target(session)}"

    /**
     * 没 attach 的会话开新窗口：建窗口与 attach 合成一条登录命令。
     *
     * 和 [attachWindowCommand] 同一个思路——少一次往返，冷启动时也不必先为一条 exec 拨连接。
     * 建窗口失败（会话刚被 kill）就静默跳过，`-A` 会把这个会话重新建出来，用户仍然落在终端里。
     */
    fun newWindowAndAttachCommand(session: String): String =
        "${newWindowCommand(session)} 2>/dev/null; ${attachCommand(session)}"

    /**
     * 关掉一个窗口。用 `@id` 而不是 `会话名:index`——index 会随着窗口增删往前挪，
     * 拿它去 kill 就是在关隔壁那个窗口，而这个动作不可逆。
     *
     * 关掉最后一个窗口会连会话一起结束，那是 tmux 的语义，不在这里拦：
     * 拦了就得替用户决定「你其实想留着这个空会话」，而 tmux 里本来就没有空会话。
     *
     * **`@id` 只在单个 server 生命周期内有效**，而我们的列表可能来自几小时前的缓存，
     * 所以先在远端比一次 server pid，对不上就以 [ActionResult.STALE] 退出、什么都不关。
     * `serverId` 为 null（旧版缓存没有这一行）时干脆不生成命令，让调用方先刷新。
     */
    fun killWindowCommand(windowId: String, serverId: String?): String? =
        serverId?.let { "${requireServer(it)} || exit ${ActionResult.STALE}; tmux kill-window -t ${quote(windowId)}" }

    fun renameSessionCommand(from: String, to: String): String =
        "tmux rename-session -t ${target(from)} ${quote(to)}"

    fun killSessionCommand(name: String): String = "tmux kill-session -t ${target(name)}"

    /** 把附加在这个会话上的其他客户端踢掉（比如桌面上那个把窗口尺寸拖小的）。 */
    fun detachOthersCommand(name: String): String = "tmux detach-client -s ${target(name)}"

    /**
     * 安装 tmux 的命令，认不出包管理器时返回 null（UI 只能让用户自己装）。
     *
     * **这条命令要原样显示给用户确认**，所以宁可长一点也不搞 `if/elif` 的自适应串——
     * 用户得看得懂自己批准的是什么。sudo 逐条加而不是整串加一次：`sudo a && b` 里的 `b`
     * 是不带 sudo 跑的，装个包只成功一半比直接失败更难查。
     */
    fun installCommand(installer: TmuxInstaller): String? {
        val steps = when (installer.manager) {
            // update 不能省：镜像里的包索引常年是空的，直接 install 报「找不到 tmux」
            "apt-get" -> listOf("apt-get update", "apt-get install -y tmux")
            "dnf" -> listOf("dnf install -y tmux")
            "yum" -> listOf("yum install -y tmux")
            "pacman" -> listOf("pacman -Sy --noconfirm tmux")
            "apk" -> listOf("apk add tmux")
            "zypper" -> listOf("zypper install -y tmux")
            "brew" -> listOf("brew install tmux")
            "pkg" -> listOf("pkg install -y tmux")
            else -> return null
        }
        // brew 装在用户前缀下，且**拒绝以 root 运行**，给它加 sudo 是帮倒忙。
        val sudo = if (installer.root || installer.manager == "brew") "" else "sudo "
        return steps.joinToString(" && ") { sudo + it }
    }

    /**
     * 新建会话时填进输入框的默认名。
     *
     * 不学 tmux 用数字（`0`、`1`）：本产品的会话列表是给人扫一眼认的，
     * 一排数字谁也认不出哪个是哪个，`main` 至少是个能被改名的起点。
     */
    fun defaultSessionName(existing: Collection<String>): String {
        if (DEFAULT_SESSION_NAME !in existing) return DEFAULT_SESSION_NAME
        var index = 2
        while ("$DEFAULT_SESSION_NAME$index" in existing) index++
        return "$DEFAULT_SESSION_NAME$index"
    }

    const val DEFAULT_SESSION_NAME = "main"

    /**
     * 把动作命令包成「首行退出码 + 原始输出」。
     *
     * 不能指望 `ExecResult.exitCode`：我们发的常是 `;` 拼起来的复合命令，channel 拿到的
     * 是最后一条的退出码。stderr 合进 stdout，失败原因（`can't find session` 之类）才能带回 UI。
     */
    fun action(command: String): String =
        "out=\$($command 2>&1); rc=\$?; echo \"$MARKER_RC\$rc\"; printf '%s\\n' \"\$out\""

    // ---- 输出解析 ------------------------------------------------------------

    /**
     * 解析 [PROBE_COMMAND] 的 stdout。
     *
     * 任何一步对不上都返回 [ProbeResult.Malformed]，**绝不退化成空列表**。
     */
    fun parseProbe(stdout: String): ProbeResult {
        // 远端 shell 的 motd / rc 脚本可能在我们的输出前面吐东西，所以一切从哨兵开始认。
        val lines = stdout.lines().map { it.trimEnd('\r') }

        val tmuxIndex = lines.indexOfFirst { it.startsWith(MARKER_TMUX) }
        if (tmuxIndex < 0) return ProbeResult.Malformed("missing $MARKER_TMUX")
        val sessionsIndex = lines.indexOfFrom(tmuxIndex, MARKER_SESSIONS)
        if (sessionsIndex < 0) return ProbeResult.Malformed("missing $MARKER_SESSIONS")
        val windowsIndex = lines.indexOfFrom(sessionsIndex, MARKER_WINDOWS)
        if (windowsIndex < 0) return ProbeResult.Malformed("missing $MARKER_WINDOWS")
        val endIndex = lines.indexOfFrom(windowsIndex, MARKER_END)
        if (endIndex < 0) return ProbeResult.Malformed("missing $MARKER_END")

        val present = lines[tmuxIndex].removePrefix(MARKER_TMUX).trim()
        if (present != "1") {
            // 只认 "1"。既不是 1 也不是 0 说明这行不是我们发的那个 echo，宁可报读取失败。
            return if (present == "0") ProbeResult.NoTmux(parseInstaller(lines, tmuxIndex, sessionsIndex))
            else ProbeResult.Malformed("bad tmux marker: $present")
        }

        val sessions = mutableListOf<TmuxSession>()
        val byId = mutableMapOf<String, String>() // $会话id -> 会话名，只在本次解析内有效
        for (line in lines.subList(sessionsIndex + 1, windowsIndex)) {
            if (line.isBlank()) continue // shell 的空行是噪音，不是数据
            val parsed = parseSessionLine(line) ?: return ProbeResult.Malformed("bad session line: $line")
            byId[parsed.first] = parsed.second.name
            sessions += parsed.second
        }
        if (sessions.isEmpty()) return ProbeResult.NoSessions

        val windows = mutableMapOf<String, MutableList<TmuxWindow>>()
        for (line in lines.subList(windowsIndex + 1, endIndex)) {
            if (line.isBlank()) continue
            val parsed = parseWindowLine(line) ?: return ProbeResult.Malformed("bad window line: $line")
            // 找不到归属会话说明两条命令之间会话被改动了（列完会话后有人 kill）。
            // 这是竞态不是格式错误，丢掉这个窗口就行，报「读取失败」反而是误伤。
            val owner = byId[parsed.first] ?: continue
            windows.getOrPut(owner) { mutableListOf() } += parsed.second
        }

        return ProbeResult.Sessions(
            sessions = sessions.map { session ->
                session.copy(windows = windows[session.name].orEmpty().sortedBy { it.index })
            },
            serverId = parseServerId(lines, tmuxIndex, sessionsIndex),
        )
    }

    /**
     * 解析 [MARKER_SERVER] 行。**缺这一行不算畸形**——DataStore 里躺着的旧版探测输出就没有它，
     * 判 Malformed 等于把用户上一次的会话列表整个抹掉，比丢一个校验用的 pid 严重得多。
     * 拿到 null 的后果只是「破坏性动作要先刷新一次」，那是安全的一侧。
     */
    private fun parseServerId(lines: List<String>, from: Int, to: Int): String? = lines
        .subList(from + 1, to)
        .firstOrNull { it.startsWith(MARKER_SERVER) }
        ?.removePrefix(MARKER_SERVER)
        ?.trim()
        ?.takeIf { it.isNotEmpty() } // 没有 server 在跑时这一行就是空的，属正常

    /**
     * 解析 [MARKER_PM] 行（`__LM_PM__:apt-get:0`）。
     *
     * 认不出包管理器、或者这份输出压根来自旧版本的探测命令（缓存里躺着的就是），
     * 一律返回 null——没有安装命令可给不是错误，UI 退回「自己装吧」就是了。
     */
    private fun parseInstaller(lines: List<String>, from: Int, to: Int): TmuxInstaller? {
        val line = lines.subList(from + 1, to).firstOrNull { it.startsWith(MARKER_PM) } ?: return null
        val parts = line.removePrefix(MARKER_PM).trim().split(":")
        if (parts.size != 2) return null
        val manager = parts[0].takeIf { it in PACKAGE_MANAGERS } ?: return null
        return TmuxInstaller(manager = manager, root = parts[1] == "0")
    }

    /** @return `$会话id` to 会话（窗口稍后填） */
    private fun parseSessionLine(line: String): Pair<String, TmuxSession>? {
        val parts = line.split(":", limit = 4)
        if (parts.size != 4) return null
        val attached = parts[0].toNonNegativeIntOrNull() ?: return null
        val windowCount = parts[1].toNonNegativeIntOrNull() ?: return null
        val id = parts[2].takeIf { it.length > 1 && it.startsWith('$') } ?: return null
        val name = parts[3].takeIf { it.isNotEmpty() } ?: return null
        return id to TmuxSession(name = name, windowCount = windowCount, attachedClients = attached)
    }

    /** @return `$会话id` to 窗口 */
    private fun parseWindowLine(line: String): Pair<String, TmuxWindow>? {
        val parts = line.split(":", limit = 6)
        if (parts.size != 6) return null
        val active = parts[0].toNonNegativeIntOrNull() ?: return null
        val panes = parts[1].toNonNegativeIntOrNull() ?: return null
        val index = parts[2].toNonNegativeIntOrNull() ?: return null
        val windowId = parts[3].takeIf { it.length > 1 && it.startsWith('@') } ?: return null
        val sessionId = parts[4].takeIf { it.length > 1 && it.startsWith('$') } ?: return null
        // 窗口名可以是空串（`tmux rename-window ''`），空名字不算畸形。
        val window = TmuxWindow(
            id = windowId,
            index = index,
            name = parts[5],
            active = active != 0,
            panes = panes,
        )
        return sessionId to window
    }

    /**
     * 解析 [action] 包出来的输出。
     *
     * 不要求 `__LM_RC__:` 真的在第一行——远端 shell 的 motd 可能抢在前面，
     * 所以是「找到第一条 rc 行」，它之后的全部算命令输出。
     */
    fun parseAction(stdout: String): ActionResult {
        val lines = stdout.lines().map { it.trimEnd('\r') }
        val index = lines.indexOfFirst { it.startsWith(MARKER_RC) }
        if (index < 0) {
            // 没拿到退出码就当失败：把「不知道成没成」当成功，用户会看着没被 kill 的会话消失在列表里。
            return ActionResult(ActionResult.NO_EXIT_CODE, stdout.trim())
        }
        val code = lines[index].removePrefix(MARKER_RC).trim().toIntOrNull()
            ?: return ActionResult(ActionResult.NO_EXIT_CODE, stdout.trim())
        val output = lines.subList(index + 1, lines.size).joinToString("\n").trim()
        return ActionResult(code, output)
    }

    private fun List<String>.indexOfFrom(from: Int, marker: String): Int {
        for (i in from + 1 until size) if (this[i] == marker) return i
        return -1
    }

    private fun String.toNonNegativeIntOrNull(): Int? = toIntOrNull()?.takeIf { it >= 0 }
}
