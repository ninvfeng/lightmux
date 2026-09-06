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
     * 会话行格式：`已附加客户端数:窗口数:会话组大小:组内客户端数:$会话id:会话名`
     *
     * 三条约束绑在一起：
     * - 分隔符用 `:` 不用 TAB——tmux 会把名字里的 TAB 替换成 `_`，用 TAB 分隔就分不清
     *   「名字里本来有 TAB」和「这是分隔符」。
     * - **名字一律放行尾**，因为会话名本身可以包含 `:`（`tmux new -s a:b` 完全合法）。
     * - 名字前面全是数值/id 字段（无 `:`），所以解析时按 `split(limit = N+1)` 取前 N 段，
     *   剩下的整段都是名字。
     *
     * 两个组字段是给镜像会话用的（见 [mirrorName]），**不能改用 `#{session_group}`**：
     * 那个字段的值是组名，而组名就是某个会话名，一样可以包含 `:`——一行里只容得下
     * 行尾那一个自由字段。`#{session_group_size}` 是数值，不在组里时为空串。
     */
    const val SESSION_FORMAT =
        "#{session_attached}:#{session_windows}:#{session_group_size}:#{session_group_attached}:" +
            "#{session_id}:#{session_name}"

    /**
     * 镜像会话的名字后缀（见 [mirrorName]）。
     *
     * 不含 `*?[]`：tmux 的 target 匹配是「先精确、再 fnmatch、再前缀」，而
     * `set-option` / `set-hook` 这类命令的 `-t` **不认 `=` 精确前缀**。实测里
     * `set-option -t 'dev [lightmux]' destroy-unattached on` 会被 fnmatch 当成字符类，
     * 打到用户一个叫 `dev x` 的会话上，那个会话下次 detach 时就没了。
     * 圆括号不是 fnmatch 元字符，用它。
     */
    const val MIRROR_SUFFIX = " (lightmux)"

    /**
     * 「另开一份视图」用的分组镜像会话名。
     *
     * 同一个组里的会话**共享窗口集合，但各有各的当前窗口和尺寸**——这正是手机需要的：
     * 电脑上那个客户端还开着的时候，两边 attach 同一个会话会按 `window-size`（tmux 3.1 起
     * 默认 `latest`）跟着最近活动的客户端来回改尺寸，每交替输入一次就整屏重排一次。
     * 而且手机在主页点一下窗口，电脑那块屏会跟着一起切走。镜像会话把这两条一起解决。
     *
     * 只在**确实有别的客户端连着**时才建（见 [attachCommand]）：没人跟你抢的时候
     * 多一个会话纯属在用户的 `tmux ls` 里添乱。
     */
    fun mirrorName(session: String): String = session + MIRROR_SUFFIX

    /**
     * 窗口行格式：`是否活动:面板数:索引:@窗口id:$会话id:窗口名`
     *
     * 这里用 `$会话id` 而不是会话名来关联所属会话——一行里只能有**一个**可含 `:` 的自由字段，
     * 而窗口名必须占着行尾那个位置。`$id` 只在这一次输出内部用于关联，不会被缓存下来。
     */
    const val WINDOW_FORMAT =
        "#{window_active}:#{window_panes}:#{window_index}:#{window_id}:#{session_id}:#{window_name}"

    /**
     * 侧通道命令统一的 `PATH` 补丁。**探测和动作必须共用同一份**。
     *
     * exec channel 起的是非交互 shell，不读 `.bashrc` / `.zshrc`，tmux 装在 `/usr/local/bin`
     * （FreeBSD、源码编译）或 homebrew 前缀下时，这里根本看不见它。只补探测那半边的后果很隐蔽：
     * 主页照常列出会话和窗口（探测成功），点下去却每次都 `tmux: not found` ——
     * 动作命令头一句 server pid 断言就不成立，一律以 [ActionResult.STALE] 退出，
     * 表现是「这台服务器上窗口切不动，只反复提示列表已过期」。
     */
    const val PATH_FIX = "export PATH=\"\$PATH:/usr/local/bin:/opt/homebrew/bin\""

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
        PATH_FIX,
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
     * 「这个会话上已经有别的客户端」的服务端断言。
     *
     * 不用 `display-message -p -t <会话> '#{session_attached}'`——实测那条在没有客户端
     * 可供渲染格式串时返回空串，有没有人连都一样，判据直接失效。`list-clients -t` 是
     * 直接列出客户端，有输出就是有人连着。
     */
    private fun hasOtherClient(name: String): String =
        "[ -n \"\$(tmux list-clients -t ${target(name)} 2>/dev/null)\" ]"

    /**
     * 建镜像会话并 attach 的 **单条 tmux 命令列表**。三条纪律缠在一起，改之前先读完：
     *
     * 1. `set-option` **必须和 `attach-session` 在同一次 tmux 调用里**。分成两次调用的话，
     *    中间那一瞬镜像会话既设了 `destroy-unattached on` 又还没人 attach，server 的下一轮
     *    检查就把它销毁了（实测踩过，表现是「镜像建了又没了、退回直连」）。
     * 2. `set-option` / `select-window` / `new-window` 一律**不带 `-t`**。它们的 `-t` 是
     *    target-pane，既不认 `=` 精确前缀又会 fnmatch 到别的会话上（见 [MIRROR_SUFFIX]）；
     *    而在 `new-session -d` 之后，不带 `-t` 的命令正好落在刚建出来的那个会话上（实测）。
     * 3. `\;` 前后的空格不能省，那是 tmux 的命令分隔符，shell 要把反斜杠原样交给 tmux。
     *
     * **不加 `-A`**：名字被占时 `-A` 会直接 attach 那个会话，而占用者可能是用户自己起的同名会话，
     * 那就 attach 到了完全无关的内容上。没有 `-A` 时名字被占只是建不出来、退回直连——
     * 断线重连撞上还没被回收的旧镜像就属于这种，结果等于改动前的行为（跟着抢一下尺寸），
     * 旧镜像那个死客户端一被 tmux 发现就会连镜像一起收掉，下次连接自愈。
     *
     * @param extra 夹在建会话与 attach 之间的额外 tmux 命令（切窗口 / 开新窗口）
     */
    private fun mirrorAttach(name: String, extra: List<String>): String {
        val mirror = mirrorName(name)
        val steps = listOf("new-session -d -t ${target(name)} -s ${quote(mirror)}") + extra +
            listOf("set-option destroy-unattached on", "attach-session -t ${target(mirror)}")
        return "tmux " + steps.joinToString(" \\; ") + " 2>/dev/null"
    }

    /** 不走镜像时的 attach，同样包成一次 tmux 调用，好让整条链只靠 `&&` / `||` 串起来。 */
    private fun directAttach(name: String, extra: List<String>): String =
        "tmux " + (extra + "attach-session -t ${target(name)}").joinToString(" \\; ") + " 2>/dev/null"

    /**
     * 三段式 attach：**镜像 → 直连 → 新建**，全靠 `||` 串，中间不许出现 `;`。
     *
     * `A || B || C` 是短路的：前一段返回 0（attach 正常返回意味着用户 detach 了）后面就不跑。
     * 早先写成 `A && B || C; D` 那样，`;` 后面那段在镜像 attach 成功之后照样会执行，
     * 用户一 detach 就被原地重新 attach 回原会话——分支等于白写。
     *
     * 最后那段 `new-session -A` 是缓存过期的兜底（PRD §4.3）：从三分钟前的快照点进一个
     * 已经被 kill 的会话，最坏结果只是新建了一个同名会话，而不是甩用户一句「会话不存在」。
     *
     * **不加 `-D`**：踢掉别的客户端是用户的决定，走主页的「断开其他客户端」
     * （[detachOthersCommand]），不由 attach 顺手替他做——尤其是自动重连也走这条命令。
     *
     * @param mirrorExtra 镜像分支里夹在建会话与 attach 之间的 tmux 命令（**不带 `-t`**，
     *   靠「刚建完的会话就是当前会话」定位）
     * @param directExtra 直连分支里的对应命令（要自带 `-t`）
     * @param guard 整条链前面的额外守卫，为空则没有
     */
    private fun attachChain(
        name: String,
        mirrorExtra: List<String> = emptyList(),
        directExtra: List<String> = emptyList(),
        guard: String? = null,
    ): String {
        val prefix = guard?.let { "$it && " }.orEmpty()
        val mirror = "$prefix${hasOtherClient(name)} && ${mirrorAttach(name, mirrorExtra)}"
        val direct = "$prefix${directAttach(name, directExtra)}"
        return "$mirror || $direct || tmux new-session -A -s ${quote(name)}"
    }

    /**
     * attach 命令。会话上已经有别的客户端时改走分组镜像会话（[mirrorName]），躲开尺寸互抢。
     */
    fun attachCommand(name: String): String = attachChain(name)

    /**
     * 先切窗口再 attach。
     *
     * 和「先走 exec 切窗口、再开终端」相比少一次往返，且冷启动（这台主机还没有连接）时
     * 不需要为了切一个窗口先拨一条连接。`select-window` 失败（缓存里的 `@id` 已经没了）
     * 就让这一段整体失败、落到最后的 `new-session -A`，用户仍然落在会话里。
     *
     * 切窗口可逆，但切错仍然是错的，所以同样带 [requireServer] 校验：pid 对不上就只 attach 不切。
     * `serverId` 为 null（旧版缓存里没有这一行）时退化成纯 attach——落在会话的当前窗口上，
     * 总好过拿一个来路不明的 `@id` 去赌。
     *
     * 走镜像会话时切窗口挪进 tmux 命令列表里：切的是**镜像自己的**当前窗口，
     * 电脑上那块屏不会跟着一起跳走——那正是镜像会话要解决的另一半问题。
     */
    fun attachWindowCommand(name: String, windowId: String, serverId: String?): String {
        if (serverId == null) return attachCommand(name)
        return attachChain(
            name = name,
            mirrorExtra = listOf("select-window -t ${quote(windowId)}"),
            directExtra = listOf("select-window -t ${quote(windowId)}"),
            guard = requireServer(serverId),
        )
    }

    /**
     * 侧通道动作的目标：**优先打在镜像会话上**（见 [mirrorName]）。
     *
     * 手机连着的时候可能待在镜像里，对原会话下 `select-window` 就是把电脑上那块屏一起拽走；
     * 而光给 `select-window` 一个 `@id`（不带会话前缀）在成组时是**歧义**的——实测 tmux
     * 会挑「最近活动的那个会话」，也就是说切到谁头上全看运气。
     *
     * 镜像不存在（这次没走镜像分支，或者已经被 `destroy-unattached` 收掉）时第一条失败，
     * 落回原会话。会话名里带 `:` 时前缀写法会被 tmux 从第一个 `:` 处切开、解析失败，
     * 一样落回原会话——退化成改动前的行为，不会打错人。
     */
    private fun preferMirror(mirrorCommand: String, fallback: String): String =
        "$mirrorCommand 2>/dev/null || $fallback"

    /**
     * 已 attach 的会话切窗口。**`serverId` 为 null 返回 null**：调用方该先刷新列表，
     * 而不是拿一个可能属于上一个 server 的 `@id` 碰运气。
     */
    fun selectWindowCommand(session: String, windowId: String, serverId: String?): String? = serverId?.let {
        val select = preferMirror(
            mirrorCommand = "tmux select-window -t ${quote("=${mirrorName(session)}:$windowId")}",
            fallback = "tmux select-window -t ${quote(windowId)}",
        )
        "${requireServer(it)} || exit ${ActionResult.STALE}; $select"
    }

    /**
     * 在已有会话里开一个新窗口。
     *
     * tmux 自己会把新窗口选为当前窗口，所以这条命令跑完不必再 `select-window`，
     * 之后 attach 上去正好落在新窗口里。
     *
     * 打在镜像上时新窗口照样 link 进整个组（组共享窗口集合），电脑那边窗口列表里也有它，
     * 只是不会被拽过去——见 [preferMirror]。
     */
    fun newWindowCommand(session: String): String = preferMirror(
        mirrorCommand = "tmux new-window -t ${target(mirrorName(session))}",
        fallback = "tmux new-window -t ${target(session)}",
    )

    /**
     * 没 attach 的会话开新窗口：建窗口与 attach 合成一条登录命令。
     *
     * 和 [attachWindowCommand] 同一个思路——少一次往返，冷启动时也不必先为一条 exec 拨连接。
     * 建窗口失败（会话刚被 kill）就落到链尾的 `new-session -A`，把会话重新建出来——
     * 新会话本来就自带一个窗口，用户要的「多一个窗口」并没有落空。
     *
     * 镜像分支里的 `new-window` 不带 `-t`：新窗口会 link 进整个组，但只在镜像会话里被选中，
     * 电脑上那个客户端不会被拽到新窗口去。
     */
    fun newWindowAndAttachCommand(session: String): String = attachChain(
        name = session,
        mirrorExtra = listOf("new-window"),
        directExtra = listOf("new-window -t ${target(session)}"),
    )

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
     *
     * 前面那段 [PATH_FIX] 不能省：探测补了 PATH 而动作没补时，两边对「有没有 tmux」的判断会分叉。
     */
    fun action(command: String): String =
        "$PATH_FIX; out=\$($command 2>&1); rc=\$?; echo \"$MARKER_RC\$rc\"; printf '%s\\n' \"\$out\""

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
        val visible = hideMirrors(sessions)

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
            sessions = visible.map { session ->
                session.copy(windows = windows[session.name].orEmpty().sortedBy { it.index })
            },
            serverId = parseServerId(lines, tmuxIndex, sessionsIndex),
        )
    }

    /**
     * 把本 app 建的镜像会话从列表里藏掉——它和原会话是同一份内容，列出来只会让用户
     * 以为自己多了一个会话，还得猜该点哪个。
     *
     * **只藏「原会话还在」的那种**：原会话被 kill 掉之后，镜像里那些窗口就只剩这一个入口了，
     * 再藏就是把用户的窗口藏没了。
     */
    private fun hideMirrors(sessions: List<TmuxSession>): List<TmuxSession> {
        val names = sessions.mapTo(mutableSetOf()) { it.name }
        return sessions.filterNot { candidate ->
            candidate.grouped &&
                candidate.name.endsWith(MIRROR_SUFFIX) &&
                candidate.name.removeSuffix(MIRROR_SUFFIX) in names
        }
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

    /**
     * @return `$会话id` to 会话（窗口稍后填）
     *
     * **两种宽度都要认**：DataStore 里躺着的是上一版探测输出，只有 4 段（没有那两个组字段）。
     * 认死 6 段等于升级当天所有人的会话列表全变成「读取失败」，而组字段拿不到的后果
     * 只是「不认得镜像会话」——那台机器上本来也没有本 app 建的镜像。
     *
     * 分辨新旧看的是 **`$id` 落在第几段**，不是段数：名字可以带 `:`，旧格式的
     * `0:1:$0:a:b:c` 一样会被切成 6 段，按段数判就成了新格式，然后组字段那关过不去，
     * 整份列表判 Malformed——用户看到的是「读取失败」，会以为会话没了。
     */
    private fun parseSessionLine(line: String): Pair<String, TmuxSession>? {
        val parts = line.split(":", limit = 6)
        // 新格式这一段是 #{session_group_size}，数值或空串，不可能以 $ 开头。
        if (parts.size >= 3 && parts[2].startsWith('$')) {
            val old = line.split(":", limit = 4)
            if (old.size != 4) return null
            return session(old[0], old[1], "", "", old[2], old[3])
        }
        if (parts.size != 6) return null
        return session(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5])
    }

    private fun session(
        attached: String,
        windowCount: String,
        groupSize: String,
        groupAttached: String,
        id: String,
        name: String,
    ): Pair<String, TmuxSession>? {
        val clients = attached.toNonNegativeIntOrNull() ?: return null
        val windows = windowCount.toNonNegativeIntOrNull() ?: return null
        // 不在组里时 tmux 给的是空串，那不是畸形，是「没有组」。
        val size = groupSize.toGroupFieldOrNull() ?: return null
        val groupClients = groupAttached.toGroupFieldOrNull() ?: return null
        val sessionId = id.takeIf { it.length > 1 && it.startsWith('$') } ?: return null
        val sessionName = name.takeIf { it.isNotEmpty() } ?: return null
        return sessionId to TmuxSession(
            name = sessionName,
            windowCount = windows,
            // 镜像会话把手机那个客户端算在自己头上，原会话看上去就成了「没人连」。
            // 组内客户端数才是用户想知道的那个数：这个会话现在到底有几块屏在看。
            attachedClients = maxOf(clients, groupClients),
            grouped = size > 0,
        )
    }

    private fun String.toGroupFieldOrNull(): Int? = if (isEmpty()) 0 else toNonNegativeIntOrNull()

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
