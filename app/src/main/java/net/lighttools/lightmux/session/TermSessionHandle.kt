package net.lighttools.lightmux.session

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.lighttools.lightmux.data.Host
import net.lighttools.lightmux.ssh.SshConnection
import net.lighttools.lightmux.ssh.SshTransport

sealed interface SessionState {

    /** 正在建连。 */
    data object Connecting : SessionState

    /** shell 已就绪。 */
    data object Connected : SessionState

    /**
     * 掉线了，正在退避等下一次重试。
     *
     * 倒计时要显示出来：没有它的话「重连中…」和「卡死了」在用户眼里长得一模一样，
     * 退到 15s 的那一档尤其像死掉。
     *
     * @param attempt      第几次重试，从 1 起
     * @param secondsLeft  距下次重试还剩几秒
     */
    data class Reconnecting(val attempt: Int, val secondsLeft: Int) : SessionState

    /** 终态：远端正常退出、用户放弃，或者失败类型注定重试无用。只能手动重试。 */
    data object Disconnected : SessionState
}

/**
 * 一个常驻终端会话的句柄：把 [TerminalSession]（终端内核）、[SshConnection]（字节与侧通道来源）
 * 和主机元信息绑在一起。
 *
 * 存在的意义是让 UI 只认这一个对象：会话活在 Application 作用域，而 TerminalView 每次进页面都重建，
 * 两者寿命不同，中间必须有一层稳定的引用。
 */
class TermSessionHandle internal constructor(
    val id: String,
    val host: Host,
    /** attach 的 tmux 会话名。跨连接的会话身份用 name 而不是 `$id`（`$id` 只在单个 tmux server 生命周期内有效）。 */
    val tmuxSession: String?,
    /** 会话是怎么起来的。重连要照着它把用户送回同一个起点，见 [reconnectLoginCommand]。 */
    private val loginCommand: String?,
    private val newTransport: (loginCommand: String?, onConnected: () -> Unit) -> SshTransport,
    transcriptRows: Int,
) {

    /**
     * 重连的调度线程。
     *
     * 跟着 [close] 一起取消，所以不用 Application 的作用域——会话被关掉后还在倒计时的重连
     * 是纯粹的资源泄漏。用 Main：[TerminalSession] 的回调本来就在主线程上，
     * 状态迁移与 `reconnect()` 都放同一条线程才不用为 emulator 的字段再想一遍可见性。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val policy = ReconnectPolicy()

    private var reconnectJob: Job? = null

    /** 用户主动关的会话不重连——否则用户就再也关不掉它了。 */
    @Volatile
    private var closedByUser = false

    private val _state = MutableStateFlow<SessionState>(SessionState.Connecting)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /**
     * UI 层挂上来的终端回调。
     *
     * 必须走这里而不是直接 `session.updateTerminalSessionClient()`：本类要靠 `onSessionFinished`
     * 维护 [state]，UI 若把 client 整个换掉，状态就再也不更新了。
     */
    @Volatile
    var client: TerminalSessionClient? = null

    @Volatile
    private var transport: SshTransport = newTransport(loginCommand, ::onConnected)

    /** 供 tmux 侧通道 / 监控 / SFTP 复用的同一条连接。 */
    val connection: SshConnection get() = transport.connection

    /** 最后一次连接失败的原因，UI 按类型分流（主机密钥变更 / 认证失败 / 跳板丢失）。 */
    val failure: Throwable? get() = transport.failure

    val session: TerminalSession = TerminalSession(transport, transcriptRows, Delegate())

    /** 显示名。默认主机名，之后由终端标题或 tmux 会话名覆盖。 */
    @Volatile
    var title: String = tmuxSession?.let { "${host.name}: $it" } ?: host.name

    /**
     * 往会话里送一段文本，等同于用户自己敲进去的。
     *
     * 走 [session] 而不是 `TerminalView`：从文件页「输入到终端」回来时视图正在重建，
     * 那一刻没有视图可用。字节该发给连接，与谁在渲染无关。
     */
    fun sendText(text: String) {
        val bytes = text.toByteArray()
        session.write(bytes, 0, bytes.size)
    }

    /**
     * 手动重连（「立即重试」、改完凭据、信任了新主机密钥之后）。
     *
     * 退避计数清零：用户亲手点的这一下，等 15 秒才动是说不过去的。
     */
    fun reconnect() {
        if (_state.value == SessionState.Connecting) return
        reconnectJob?.cancel()
        reconnectJob = null
        policy.reset()
        openTransport()
    }

    /** 「放弃」：停掉倒计时，落到终态，用户想回来时再手动重试。 */
    fun abandon() {
        reconnectJob?.cancel()
        reconnectJob = null
        _state.value = SessionState.Disconnected
    }

    /**
     * 网络恢复。正在退避的会话立刻抢跑一次，**这一枪不计入重试次数**（见 [ReconnectPolicy]）。
     *
     * 已经放弃的会话不碰：它们多半是认证或密钥问题，网络好了也一样连不上。
     *
     * 和 [onConnected] 一样先切回 [scope]：`registerDefaultNetworkCallback` 的回调跑在
     * ConnectivityManager 自己的线程上，直接在那儿改 [policy] 和 [reconnectJob] 会和主线程的
     * [scheduleReconnect] 打架——Wi-Fi 切蜂窝恰好撞上退避倒计时，主线程那次 `cancel()`
     * 就可能看不见这边刚写进去的 Job，剩一个孤儿倒计时跑到 [openTransport]，
     * 同一个 tmux 会话被 attach 两次，两个客户端按最小的那个协商窗口尺寸，两边都被压小。
     */
    fun onNetworkRestored() {
        scope.launch {
            if (_state.value !is SessionState.Reconnecting) return@launch
            policy.resetForNetworkRestored()
            scheduleReconnect()
        }
    }

    fun close() {
        closedByUser = true
        reconnectJob?.cancel()
        reconnectJob = null
        session.finishIfRunning()
        transport.close()
        _state.value = SessionState.Disconnected
        scope.cancel()
    }

    /**
     * 回调来自传输线程，所以要先切回 [scope]：[ReconnectPolicy] 是普通字段没做同步，
     * 让它只被主线程碰过是最省事的保证。会话已关时 scope 已取消，这一枪自然被丢掉。
     */
    private fun onConnected() {
        scope.launch {
            _state.value = SessionState.Connected
            policy.reset()
        }
    }

    /**
     * 通道关了。按失败类型分流：值得重试的排队退避，其余直接落终态。
     *
     * 认证类失败绝不能进重试循环——密码错还每隔几秒撞一次，十几次之后这个 IP
     * 就进了对端的 fail2ban，那要人工去解封。
     */
    private fun onTransportClosed() {
        when (ReconnectDecision.of(closedByUser, transport.failure)) {
            ReconnectDecision.GiveUp -> _state.value = SessionState.Disconnected
            ReconnectDecision.Retry -> scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var left = policy.nextDelayMs()
            // 立即重试那一枪不推进 attempts，但 UI 上它仍是「第 1 次」。
            val attempt = policy.attempts.coerceAtLeast(1)
            while (left > 0) {
                // 向上取整：还剩 200ms 时显示「0 秒后重试」会让人以为卡住了。
                _state.value = SessionState.Reconnecting(attempt, ((left + 999) / 1000).toInt())
                val step = minOf(left, TICK_MS)
                delay(step)
                left -= step
            }
            openTransport()
        }
    }

    /**
     * 换一条新连接重连，**保留滚屏历史**（靠 [TerminalSession.reconnect]）。
     *
     * 不复用旧的 [SshConnection]：它的 SSHClient 断开后不能再 connect，
     * 而且残留的 SFTP / exec channel 也得跟着一起丢掉。
     */
    private fun openTransport() {
        if (closedByUser) return
        _state.value = SessionState.Connecting
        val next = newTransport(reconnectLoginCommand(tmuxSession, loginCommand), ::onConnected)
        transport = next
        session.reconnect(next)
    }

    /**
     * 把终端内核的回调转给当前 UI，顺便截下 [TerminalSessionClient.onSessionFinished] 更新状态。
     * UI 不在时（用户退到主页）全部吞掉即可——会话照跑，只是没人渲染。
     */
    private inner class Delegate : TerminalSessionClient {

        override fun onTextChanged(changedSession: TerminalSession) {
            client?.onTextChanged(changedSession)
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            changedSession.title?.takeIf { it.isNotBlank() }?.let { title = it }
            client?.onTitleChanged(changedSession)
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            client?.onSessionFinished(finishedSession)
            onTransportClosed()
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            client?.onCopyTextToClipboard(session, text)
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            client?.onPasteTextFromClipboard(session)
        }

        override fun onBell(session: TerminalSession) {
            client?.onBell(session)
        }

        override fun onColorsChanged(session: TerminalSession) {
            client?.onColorsChanged(session)
        }

        override fun onTerminalCursorStateChange(state: Boolean) {
            client?.onTerminalCursorStateChange(state)
        }

        override fun getTerminalCursorStyle(): Int? = client?.terminalCursorStyle

        override fun logError(tag: String?, message: String?) {
            client?.logError(tag, message)
        }

        override fun logWarn(tag: String?, message: String?) {
            client?.logWarn(tag, message)
        }

        override fun logInfo(tag: String?, message: String?) {
            client?.logInfo(tag, message)
        }

        override fun logDebug(tag: String?, message: String?) {
            client?.logDebug(tag, message)
        }

        override fun logVerbose(tag: String?, message: String?) {
            client?.logVerbose(tag, message)
        }

        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            client?.logStackTraceWithMessage(tag, message, e)
        }

        override fun logStackTrace(tag: String?, e: Exception?) {
            client?.logStackTrace(tag, e)
        }
    }

    private companion object {
        /** 倒计时刻度。对齐到 1 秒，UI 上的数字每秒正好掉一格。 */
        const val TICK_MS = 1_000L
    }
}
