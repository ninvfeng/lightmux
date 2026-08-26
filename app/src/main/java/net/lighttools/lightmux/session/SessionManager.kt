package net.lighttools.lightmux.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import net.lighttools.lightmux.data.Host
import net.lighttools.lightmux.ssh.SshConnection
import net.lighttools.lightmux.ssh.SshTransport
import java.util.UUID

/**
 * 所有活着的终端会话的注册表，**活在 Application 作用域**。
 *
 * 这条纪律是多会话卖点的地基：会话若跟着导航栈销毁，用户从终端返回主页再进来，
 * 连接和滚屏就没了（见 PRD §6.2）。所以它既不是 ViewModel 也不是 Activity 的成员。
 */
class SessionManager {

    private val _sessions = MutableStateFlow<List<TermSessionHandle>>(emptyList())
    val sessions: StateFlow<List<TermSessionHandle>> = _sessions.asStateFlow()

    /**
     * 开一个新会话。
     *
     * @param tmuxSession 要 attach 的 tmux 会话名，仅作元信息记录；实际 attach 命令由调用方拼进 [loginCommand]
     * @param loginCommand 登录后自动执行的命令，默认取主机配置
     */
    fun create(
        host: Host,
        tmuxSession: String? = null,
        loginCommand: String? = host.loginCommand,
        transcriptRows: Int = DEFAULT_TRANSCRIPT_ROWS,
    ): TermSessionHandle {
        val handle = TermSessionHandle(
            id = UUID.randomUUID().toString(),
            host = host,
            tmuxSession = tmuxSession,
            loginCommand = loginCommand,
            // 传工厂而不是现成的 transport：重连时要造全新的连接，句柄自己就能完成，不必回头找 manager。
            // 登录命令由句柄现给——重连时 tmux 会话要换成带 `-D` 的接管式 attach。
            newTransport = { command, onConnected ->
                SshTransport(
                    connection = SshConnection(host),
                    loginCommand = command,
                    onConnected = onConnected,
                )
            },
            transcriptRows = transcriptRows,
        )
        // 用 update 而不是 `value = value + handle`：后者是读改写两步。调用点大多在主线程，
        // 但「通知栏点全部断开」和「页面里新建会话」是两个入口，不值得为它去论证时序。
        _sessions.update { it + handle }
        return handle
    }

    fun get(id: String): TermSessionHandle? = _sessions.value.firstOrNull { it.id == id }

    /** 某台主机上的会话，主页树要按主机分组展示。 */
    fun forHost(hostId: String): List<TermSessionHandle> = _sessions.value.filter { it.host.id == hostId }

    fun close(id: String) {
        val handle = get(id) ?: return
        handle.close()
        _sessions.update { it - handle }
    }

    /** 先原子摘走整张表再逐个关：边遍历边有人 [create] 的话，新会话会被这一轮顺手关掉。 */
    fun closeAll() {
        _sessions.getAndUpdate { emptyList() }.forEach { it.close() }
    }

    /** 网络恢复了：正在退避的会话全部抢跑一次，别让用户干等剩下的十几秒。 */
    fun onNetworkRestored() {
        _sessions.value.forEach { it.onNetworkRestored() }
    }

    companion object {
        /** TerminalEmulator 的默认值，够翻十几屏；可调滚屏行数放设置页（M4）。 */
        const val DEFAULT_TRANSCRIPT_ROWS = 2000
    }
}
