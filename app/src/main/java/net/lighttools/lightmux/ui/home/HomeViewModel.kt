package net.lighttools.lightmux.ui.home

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.lighttools.lightmux.data.ForwardStore
import net.lighttools.lightmux.data.Host
import net.lighttools.lightmux.data.HostStore
import net.lighttools.lightmux.forward.ForwardManager
import net.lighttools.lightmux.session.SessionManager
import net.lighttools.lightmux.session.SessionState
import net.lighttools.lightmux.session.TermSessionHandle
import net.lighttools.lightmux.tmux.ActionResult
import net.lighttools.lightmux.tmux.HostTmuxState
import net.lighttools.lightmux.tmux.ProbeFreshness
import net.lighttools.lightmux.tmux.ProbeResult
import net.lighttools.lightmux.tmux.Tmux
import net.lighttools.lightmux.tmux.TmuxRepository
import net.lighttools.lightmux.tmux.TmuxSession
import net.lighttools.lightmux.tmux.TmuxWindow

/**
 * 主页状态。
 *
 * **展开状态、滚动位置、探测缓存必须放在这里**，不能放页面 `remember`（PRD §6.3）：
 * 没有导航栏的设计里「进终端 → 返回」是最高频路径，页面级状态每次都会被清空，
 * 树会折回初始态、滚动跳回顶部、刚探测到的会话列表消失。
 */
class HomeViewModel(
    private val hostStore: HostStore,
    private val sessions: SessionManager,
    private val tmux: TmuxRepository,
    private val forwards: ForwardManager,
    private val forwardStore: ForwardStore,
) : ViewModel() {

    val hosts: StateFlow<List<Host>> =
        hostStore.hosts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val liveSessions: StateFlow<List<TermSessionHandle>> = sessions.sessions

    /**
     * 主机 id → 这台机器上还连着的会话数，主机行的 ●N 角标读它。
     *
     * **必须在这一层聚合成流**：`handle.state` 是 StateFlow，在 Composable 里直接读 `.value`
     * 拿到的只是一个裸值，Compose 不知道它变过——断线、重连时角标不会自己刷新，
     * 得等别的原因引发重组才跟着跳一次（那时显示的还是那一刻的值，照样可能是错的）。
     *
     * 用 flatMapLatest 而不是让每一行各自 `collectAsState`：会话列表本身会增删，
     * 收集器的数量就会跟着行数漂移；而角标要的只是一个数，一处算完各行取用即可。
     * `liveSessions` 是 [SessionManager] 的流，会话被关掉时 handle 会从列表里消失，
     * 上一轮那些收集器随 flatMapLatest 一起取消，不会漏。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val activeSessionCounts: StateFlow<Map<String, Int>> = liveSessions
        .flatMapLatest { handles ->
            // combine 对空列表永远不发射，会把角标钉死在上一轮的值上——最后一个会话关掉时
            // 角标就再也不消失了。空列表必须自己给一个空结果。
            if (handles.isEmpty()) flowOf(emptyMap())
            else combine(handles.map { handle -> handle.state.map { handle.host.id to it } }) { states ->
                states.filter { (_, state) -> state != SessionState.Disconnected }
                    .groupingBy { (hostId, _) -> hostId }
                    .eachCount()
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * 主机 id → 这台机器上的**裸会话**（没 attach 到 tmux 的那些），主机展开后的那几行读它。
     *
     * 分组必须在这一层做一次。原先是主页每一行各自 `filter` 一遍全量会话，
     * 复杂度 O(主机数 × 会话数)，而且会话列表一有变动，每一行都要重算一次自己的那一份。
     * 查不到的主机拿到的是空列表（调用方 `orEmpty()`），不是 null——没有会话的主机照样要渲染。
     */
    val bareSessionsByHost: StateFlow<Map<String, List<TermSessionHandle>>> = liveSessions
        .map { handles -> handles.filter { it.tmuxSession == null }.groupBy { it.host.id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * 还占着资源的转发条数，「全部断开」的按钮显隐与确认文案读它。
     *
     * 一台主机上可以有多条转发，[ForwardManager.states] 里也留着已终态（Failed）的条目，
     * 所以不能拿 map 的大小当数——终态那些早就不占端口了，说要断它们只会让人以为还连着。
     */
    val liveForwardCount: StateFlow<Int> = forwards.states
        .map { states -> states.values.count { it.isLive } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    var expandedHosts by mutableStateOf(emptySet<String>())
        private set

    /** 展开了窗口的 tmux 会话，键见 [sessionKey]。 */
    var expandedSessions by mutableStateOf(emptySet<String>())
        private set

    /** 每台主机的 tmux 状态。冷启动先塞缓存，展开时再被真实探测覆盖。 */
    var tmuxStates by mutableStateOf(emptyMap<String, HostTmuxState>())
        private set

    /**
     * 有没有主机正在探测中——下拉刷新的转圈读它。
     *
     * 走 `derivedStateOf` 而不是让页面自己遍历 [tmuxStates]：那样任何一台主机的探测状态一变
     * （连探测结果落地也算）都会把整张列表所在的作用域拖着重组，而这个值只有「转 / 不转」两态。
     * 只看 [tmuxStates] 不再叠一层主机过滤是等价的：`loading` 只由 [probe] 置位，
     * 而它拿到的一定是列表里的主机；[deleteHost] 会把删掉那台的条目一并摘掉。
     */
    val refreshing: Boolean by derivedStateOf { tmuxStates.values.any { it.loading } }

    /** 动作失败的提示文本，UI 弹一条就清掉。 */
    var actionError by mutableStateOf<String?>(null)
        private set

    /**
     * 「列表已过期、已重新读取」的置位。
     *
     * 只置位不带文案：这条是给人看的提示，得走 `strings.xml`，而 ViewModel 拿不到 Context。
     * 和 [actionError] 分开是因为它**不是错误**——动作被拦下正是它该做的事。
     */
    var staleNotice by mutableStateOf(false)
        private set

    /** 列表滚动位置。跟着 ViewModel 走才能在返回主页时停在原处。 */
    val listState = LazyListState()

    init {
        // 冷启动只读缓存，**不发起任何网络请求**（PRD §4.3）：为渲染主页去拨所有主机
        // 是耗电、慢、还容易触发对端 fail2ban 的灾难。
        viewModelScope.launch { tmuxStates = tmux.cachedStates() + tmuxStates }
    }

    fun stateOf(hostId: String): HostTmuxState? = tmuxStates[hostId]

    fun isSessionExpanded(hostId: String, name: String) = sessionKey(hostId, name) in expandedSessions

    fun toggleSessionExpanded(hostId: String, name: String) {
        val key = sessionKey(hostId, name)
        expandedSessions = if (key in expandedSessions) expandedSessions - key else expandedSessions + key
    }

    /**
     * 展开 = 探测；折叠 = 放掉连接。**折叠状态下什么都不做**。
     */
    fun toggleExpanded(hostId: String) {
        if (hostId in expandedHosts) {
            expandedHosts = expandedHosts - hostId
            viewModelScope.launch { tmux.release(hostId) }
        } else {
            expandedHosts = expandedHosts + hostId
            hosts.value.firstOrNull { it.id == hostId }?.let(::probe)
        }
    }

    /** 顶栏 ⟳ 与下拉刷新：只刷**已展开**的主机，绝不自动全量探测。 */
    fun refreshExpanded() {
        hosts.value.filter { it.id in expandedHosts }.forEach(::probe)
    }

    // ---- 快速切换抽屉 ---------------------------------------------------------

    /**
     * 抽屉拉开：当前主机自动展开并静默刷新一次，其他主机只显示缓存。
     *
     * 展开状态和主页**共用一份**——抽屉装的就是主页那个 Composable。副作用是拉一次抽屉，
     * 回主页会看见这台主机是展开的；它正是用户此刻待着的那台，展开着反而对。
     *
     * 这里不走 [toggleExpanded]：那条路展开就拨号探测，而抽屉是滑一下就自动弹出来的，
     * 自动的动作不许拨号（见 [silentProbe]）。
     */
    fun openSwitcher(hostId: String, tmuxSession: String?) {
        expandedHosts = expandedHosts + hostId
        // 当前会话顺手展开窗口——抽屉的存在理由就是切窗口，让用户再点一下箭头没道理
        if (tmuxSession != null) expandedSessions = expandedSessions + sessionKey(hostId, tmuxSession)
        silentProbe(hostId)
    }

    /**
     * 静默刷新的三道闸门：在途、节流、**零拨号**。
     *
     * 第三道最关键：`ExecPool.connectionFor` 找不到可复用的连接时会实打实拨一条新的。
     * 为渲染一个抽屉去握手认证，等于把 PRD §4.3「绝不自动全量探测」悄悄作废。
     * 蹭不到就老实显示缓存（带时间戳）——终端会话、侧通道池、文件页那条 SFTP 连接都算数。
     */
    private fun silentProbe(hostId: String) {
        val host = hosts.value.firstOrNull { it.id == hostId } ?: return
        val state = tmuxStates[hostId]
        val stale = ProbeFreshness.shouldProbe(
            loading = state?.loading == true,
            probedAt = state?.probedAt ?: 0L,
            now = System.currentTimeMillis(),
        )
        if (stale && tmux.hasLiveConnection(hostId)) probe(host)
    }

    fun probe(host: Host) {
        if (tmuxStates[host.id]?.loading == true) return
        update(host.id) { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val outcome = runCatching { tmux.probe(host) }
            update(host.id) { previous ->
                outcome.fold(
                    onSuccess = { result ->
                        when (result) {
                            // 解析失败不覆盖上一次的好数据：宁可显示一份带「读取失败」的旧快照，
                            // 也不能让树塌成空的——那等于告诉用户「你的会话没了」。
                            is ProbeResult.Malformed ->
                                previous.copy(loading = false, error = result.reason)

                            else -> HostTmuxState(
                                probe = result,
                                probedAt = System.currentTimeMillis(),
                            )
                        }
                    },
                    onFailure = { error ->
                        previous.copy(loading = false, error = error.shortMessage())
                    },
                )
            }
        }
    }

    // ---- 进终端 --------------------------------------------------------------

    /**
     * attach 一个 tmux 会话并返回终端会话 id。
     *
     * 已经 attach 着的直接回到那个终端：再 attach 一次会多出一个客户端，
     * 两个客户端会按最小的那个协商窗口尺寸，两边都被压小。
     */
    fun attach(host: Host, name: String): String {
        liveAttachment(host.id, name)?.let { return it.id }
        return sessions.create(
            host = host,
            tmuxSession = name,
            loginCommand = Tmux.attachCommand(name),
        ).id
    }

    /**
     * 进入某个窗口。
     *
     * 已经 attach 着就走侧通道 `select-window` 再切回那个终端；没 attach 才新开会话，
     * 切窗口和 attach 合成一条登录命令，省一次往返，冷启动时也不必先拨一条连接。
     */
    fun openWindow(host: Host, name: String, window: TmuxWindow): String {
        liveAttachment(host.id, name)?.let { handle ->
            viewModelScope.launch {
                runCatching { tmux.selectWindow(host, name, window.id, serverIdOf(host.id)) }
                    // 拦下了就重探：用户马上会回到主页，那时该看到的是这个 server 的真实窗口
                    .onSuccess { staleNotice = it.stale; if (it.stale) probe(host) }
                    .onFailure { actionError = it.shortMessage() }
            }
            return handle.id
        }
        return sessions.create(
            host = host,
            tmuxSession = name,
            loginCommand = Tmux.attachWindowCommand(name, window.id, serverIdOf(host.id)),
        ).id
    }

    /**
     * 在这个会话里开一个新窗口，并落到那个窗口的终端上。
     *
     * 已经 attach 着就走侧通道（前台可能是全屏 TUI，不许注入按键），tmux 建完会自己把新窗口
     * 选为当前窗口，所以回到原来那个终端看到的就是新窗口；没 attach 才把建窗口和 attach
     * 合成一条登录命令，省一次往返。
     */
    fun newWindow(host: Host, session: TmuxSession): String {
        liveAttachment(host.id, session.name)?.let { handle ->
            act(host) { tmux.newWindow(host, session.name) }
            return handle.id
        }
        return sessions.create(
            host = host,
            tmuxSession = session.name,
            loginCommand = Tmux.newWindowAndAttachCommand(session.name),
        ).id
    }

    /**
     * 新建 tmux 会话 = attach 一个还不存在的名字。`new-session -A` 本来就是「有则附加、无则新建」，
     * 两条路径共用一条命令，也就不存在「新建时撞上同名会话」这个分支。
     */
    fun newTmuxSession(host: Host, name: String): String? =
        name.trim().takeIf { it.isNotEmpty() }?.let { attach(host, it) }

    /** 新建会话输入框的默认名，避开这台机器上已有的名字。 */
    fun suggestedSessionName(hostId: String): String {
        val existing = (tmuxStates[hostId]?.probe as? ProbeResult.Sessions)?.sessions?.map { it.name }
        return Tmux.defaultSessionName(existing.orEmpty())
    }

    // ---- 装 tmux -------------------------------------------------------------

    /** 这台主机能一键装 tmux 吗？返回要执行的命令，**原样显示给用户确认**；null = 认不出包管理器。 */
    fun installCommand(hostId: String): String? =
        (tmuxStates[hostId]?.probe as? ProbeResult.NoTmux)?.installer?.let(Tmux::installCommand)

    /**
     * 装 tmux **走终端而不是侧通道**。
     *
     * 三个理由，任一个都足够：sudo 多半要密码，包管理器可能弹交互询问，`apt-get update`
     * 在慢机器上远超侧通道的 8 秒超时。而且这是我们代用户敲的一条改动系统的命令，
     * 它跑出来的每一行都该让人看见——后台悄悄执行、只回一句「失败」是最糟的做法。
     */
    fun installTmux(host: Host, command: String): String =
        sessions.create(host, loginCommand = command).id

    /** 打开终端：这台主机上已经有活着的会话就直接回去，没有才新建。 */
    fun openTerminal(host: Host): String {
        val alive = sessions.forHost(host.id).firstOrNull { it.state.value != SessionState.Disconnected }
        return (alive ?: sessions.create(host)).id
    }

    fun newTerminal(host: Host): String = sessions.create(host).id

    fun closeSession(sessionId: String) {
        sessions.close(sessionId)
    }

    /**
     * 和通知栏那个「全部断开」同一件事：关掉所有会话**和所有端口转发**。
     *
     * 转发要一起断，是因为它和会话一样是「一直挂着的连接」，而且更隐蔽——它没有页面在前台，
     * 断没断全靠通知栏那行字。用户点「全部断开」的意思就是「别再连着我的服务器了」，
     * 留下几条还活着的隧道等于没听懂。
     *
     * 传输队列不碰：那是一件有终点的事，正传到一半的大文件不该被顺手掐了。
     */
    fun disconnectAll() {
        sessions.closeAll()
        forwards.stopAll()
    }

    // ---- tmux 动作 -----------------------------------------------------------

    fun renameSession(host: Host, session: TmuxSession, newName: String) {
        val target = newName.trim()
        if (target.isEmpty() || target == session.name) return
        act(host) { tmux.rename(host, session.name, target) }
    }

    fun killSession(host: Host, session: TmuxSession) = act(host) { tmux.kill(host, session.name) }

    /**
     * 关窗口走侧通道。
     *
     * 关的可能正是本 app 前台那个终端所在的窗口——那时远端会话自己退出，终端页照常收到断开，
     * 不必在这里替它做什么；而往前台注入 `exit` 则会在全屏 TUI 上炸开。
     *
     * 手里的 `@id` 可能是几小时前缓存下来的，那时的 tmux server 早没了——所以带上 server pid，
     * 让远端自己判「这个 `@3` 还是不是当初那个 `@3`」，详见 [Tmux.killWindowCommand]。
     */
    fun killWindow(host: Host, window: TmuxWindow) =
        act(host) { tmux.killWindow(host, window.id, serverIdOf(host.id)) }

    fun detachOthers(host: Host, session: TmuxSession) =
        act(host) { tmux.detachOthers(host, session.name) }

    fun clearActionError() {
        actionError = null
        staleNotice = false
    }

    /** 动作跑完立刻重探一次：列表和远端状态之间不留窗口期。 */
    private fun act(host: Host, block: suspend () -> ActionResult) {
        viewModelScope.launch {
            val outcome = runCatching { block() }
            // 被服务端拦下不是失败，是这次改动的全部目的：动作根本没执行，
            // 甩一句 `exit 9` 只会让用户以为出了故障。每次动作都重置，横幅才不会赖着不走。
            staleNotice = outcome.getOrNull()?.stale == true
            actionError = outcome.fold(
                onSuccess = { if (it.ok || it.stale) null else it.output.ifBlank { "exit ${it.code}" } },
                onFailure = { it.shortMessage() },
            )
            probe(host)
        }
    }

    /** 这份列表是哪个 tmux server 的；null = 旧缓存，`@id` 无从校验。 */
    private fun serverIdOf(hostId: String): String? =
        (tmuxStates[hostId]?.probe as? ProbeResult.Sessions)?.serverId

    fun deleteHost(hostId: String) {
        // 先把这台主机上的会话与转发关掉：连接对象里还揣着已删主机的凭据，留着既没用也不该留。
        sessions.forHost(hostId).forEach { sessions.close(it.id) }
        forwards.stopForHost(hostId)
        expandedHosts = expandedHosts - hostId
        tmuxStates = tmuxStates - hostId
        viewModelScope.launch {
            tmux.forget(hostId)
            forwardStore.deleteForHost(hostId)
            hostStore.delete(hostId)
        }
    }

    /** 拖拽排序落盘：整表按新顺序覆盖，见 [HostStore.replaceAll]。 */
    fun reorderHosts(newOrder: List<Host>) {
        viewModelScope.launch { hostStore.replaceAll(newOrder) }
    }

    private fun liveAttachment(hostId: String, name: String): TermSessionHandle? =
        sessions.forHost(hostId).firstOrNull {
            it.tmuxSession == name && it.state.value != SessionState.Disconnected
        }

    private fun update(hostId: String, transform: (HostTmuxState) -> HostTmuxState) {
        tmuxStates = tmuxStates + (hostId to transform(tmuxStates[hostId] ?: HostTmuxState()))
    }

    private fun Throwable.shortMessage(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    private companion object {
        /** 会话名可以包含 `:`，所以键的分隔符得用一个名字里不可能出现的 NUL。 */
        fun sessionKey(hostId: String, name: String) = "$hostId\u0000$name"
    }
}
