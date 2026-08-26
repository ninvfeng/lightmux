package net.lighttools.lightmux.tmux

/**
 * 一个 tmux 会话。
 *
 * **身份键是 [name] 而不是 `$id`**：`$id` 只在单个 tmux server 的生命周期内有效，
 * server 重启后同名会话会拿到不同的 id，缓存里存 id 必然失效（PRD §4.3）。
 * `$id` 只在**一次探测的输出内部**用来把窗口挂回会话，不出这个作用域。
 */
data class TmuxSession(
    val name: String,
    /** tmux 自报的窗口数。可能比 [windows] 多——列窗口和列会话之间会话可能刚被改动 */
    val windowCount: Int,
    /** 附加在这个会话上的客户端数 */
    val attachedClients: Int,
    val windows: List<TmuxWindow> = emptyList(),
) {
    val attached: Boolean get() = attachedClients > 0
}

/**
 * 会话里的一个窗口。
 *
 * 切窗口用 [id]（`@3`）而不是 `会话名:index`——index 会随着窗口增删移动，
 * 用它切窗口会切到隔壁那个。
 */
data class TmuxWindow(
    val id: String,
    val index: Int,
    val name: String,
    val active: Boolean,
    val panes: Int,
)

/**
 * 一次探测的结果。
 *
 * 四个分支必须分清楚，**尤其不能把解析失败退化成空列表**：空列表会让 UI 显示
 * 「这台机器没有 tmux 会话」，用户会以为自己的会话没了，这是最伤信任的一种 bug。
 */
sealed interface ProbeResult {

    /**
     * 远端没装 tmux（或不在 PATH 里）。
     *
     * @param installer 认出来的包管理器，null = 认不出（或这份输出来自旧版探测命令的缓存）。
     *   带着它，「没装 tmux」这一行才能给出一条能直接跑的安装命令，而不是一句无处下手的结论
     */
    data class NoTmux(val installer: TmuxInstaller? = null) : ProbeResult

    /** 装了 tmux，但确实一个会话都没有。 */
    data object NoSessions : ProbeResult

    /**
     * 至少一个会话。列表非空是这个分支的不变式，空列表一律走 [NoSessions]。
     *
     * @param serverId 采集这份列表时的 tmux server pid，**[TmuxWindow.id] 的有效期就绑在它上面**。
     *   null = 这份数据来自旧版探测输出的缓存，校验不了，破坏性动作必须先重新探测
     */
    data class Sessions(
        val sessions: List<TmuxSession>,
        val serverId: String? = null,
    ) : ProbeResult

    /** 输出看不懂。UI 显示「读取失败 · 重试」，**绝不显示成没有会话**。 */
    data class Malformed(val reason: String) : ProbeResult
}

/**
 * 远端装 tmux 需要知道的两件事：用哪个包管理器、要不要 sudo。
 *
 * @param manager 见 [Tmux.PACKAGE_MANAGERS]
 * @param root 当前登录用户是不是 root（`id -u` 为 0）
 */
data class TmuxInstaller(val manager: String, val root: Boolean)

/**
 * 动作命令（重命名 / kill / detach…）的结果。
 *
 * 退出码来自命令输出里的 `__LM_RC__:` 首行，不是 `ExecResult.exitCode`——
 * 我们发的是 `;` 拼起来的复合命令，channel 的退出码只反映最后一条。
 */
data class ActionResult(val code: Int, val output: String) {

    val ok: Boolean get() = code == 0

    /**
     * 动作**没有执行**：手里这份列表属于上一个 tmux server，里面的 `@id` 已经改指别的窗口了。
     * 调用方该重新探测再让用户决定，而不是当成普通失败甩一条 stderr 出去。
     */
    val stale: Boolean get() = code == STALE

    companion object {
        /** 输出里根本没有 `__LM_RC__:` 行。当失败处理，不能当成功。 */
        const val NO_EXIT_CODE = -1

        /**
         * 服务端 pid 校验没过。挑 9 是因为 tmux 自己只用 0/1，
         * 而 126/127/128+ 是 shell 保留给「命令不可执行 / 被信号杀掉」的，不能混。
         */
        const val STALE = 9
    }
}

/**
 * 主页上一台主机的 tmux 状态。
 *
 * [probe] 与 [error] 是**并存**的：探测失败时保留上一次的结果继续显示（带旧时间戳），
 * 只额外挂一条错误行——把已有内容清空是对用户最不友好的失败方式。
 */
data class HostTmuxState(
    val probe: ProbeResult? = null,
    /** [probe] 的采集时刻（epoch millis）；0 表示还没探测过 */
    val probedAt: Long = 0L,
    val loading: Boolean = false,
    /** 失败的技术细节，UI 拿它当副标题；主标题是本地化的「读取失败 · 重试」 */
    val error: String? = null,
)

/**
 * 缓存时间戳到「N 分钟前」的换算。
 *
 * 只算出**量级与数值**，具体文案交给 `strings.xml`——双语文案不能在这里拼。
 */
object RelativeTime {

    sealed interface Age {
        data object JustNow : Age
        data class Minutes(val value: Int) : Age
        data class Hours(val value: Int) : Age
        data class Days(val value: Int) : Age
    }

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    /** @param ageMs 距今多久。负值（设备时钟被往回调过）按「刚刚」处理，显示「-3 分钟前」更莫名 */
    fun of(ageMs: Long): Age = when {
        ageMs < MINUTE -> Age.JustNow
        ageMs < HOUR -> Age.Minutes((ageMs / MINUTE).toInt())
        ageMs < DAY -> Age.Hours((ageMs / HOUR).toInt())
        else -> Age.Days((ageMs / DAY).toInt())
    }
}

/**
 * 快速切换抽屉拉开时，那次静默刷新该不该发。
 *
 * 抽成纯函数是因为它判错的两种后果在真机上都**看不出来**，只有单测兜得住：
 * 判太松 = 每开合一次抽屉就给同一台机器发一次 exec（PRD §4.3 反复强调的耗电与 fail2ban 风险）；
 * 判太严 = 用户盯着一份过期快照点进去。
 */
object ProbeFreshness {

    /** 多新算「刚刚探过」。只要盖住「切出去又切回来」这个时间量级，15 秒够了。 */
    const val FRESH_WINDOW_MS = 15_000L

    fun shouldProbe(
        loading: Boolean,
        probedAt: Long,
        now: Long,
        windowMs: Long = FRESH_WINDOW_MS,
    ): Boolean {
        if (loading) return false
        if (probedAt <= 0L) return true
        val age = now - probedAt
        // 负龄 = 设备时钟被往回调过（自动校时、跨时区）。当成过期处理，
        // 否则一次校时就能把抽屉永久钉死在某份旧快照上。
        return age < 0L || age >= windowMs
    }
}
