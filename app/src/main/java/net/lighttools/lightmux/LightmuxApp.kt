package net.lighttools.lightmux

import android.app.Application
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.lighttools.lightmux.data.ForwardStore
import net.lighttools.lightmux.data.Host
import net.lighttools.lightmux.data.HostStore
import net.lighttools.lightmux.data.SettingsStore
import net.lighttools.lightmux.data.SshKeyStore
import net.lighttools.lightmux.forward.ForwardManager
import net.lighttools.lightmux.monitor.MonitorRepository
import net.lighttools.lightmux.session.NetworkWatcher
import net.lighttools.lightmux.session.SessionManager
import net.lighttools.lightmux.sftp.SftpRepository
import net.lighttools.lightmux.sftp.TransferQueue
import net.lighttools.lightmux.ssh.ExecPool
import net.lighttools.lightmux.tmux.TmuxCache
import net.lighttools.lightmux.tmux.TmuxRepository
import net.lighttools.lightmux.ui.terminal.TerminalAppearance
import net.lighttools.lightmux.update.UpdateChecker
import net.lighttools.lightmux.update.UpdateInstaller

/**
 * Application 入口。
 *
 * 会话必须活在 Application 作用域而不是 Activity/导航作用域——这是多会话常驻的地基，
 * 详见 [net.lighttools.lightmux.session.SessionManager]。
 */
class LightmuxApp : Application() {

    /** 与进程同寿的作用域，只用来做「必须一直跑」的订阅，页面级异步一律走 ViewModel。 */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val keyStore: SshKeyStore by lazy { SshKeyStore(this) }

    val hostStore: HostStore by lazy { HostStore(this, keyStore) }

    /** [hostsBlocking] 的缓存，由 [onCreate] 里的订阅刷新。 */
    @Volatile
    private var hostCache: List<Host> = emptyList()

    /**
     * 同步拿一份主机表。
     *
     * 只给连接层解析跳板链用（[net.lighttools.lightmux.data.HostRoute]）：那是一条已经阻塞着的
     * IO 线程，为读一份几十条的列表去挂协程反而更绕。缓存空着只可能发生在订阅还没发第一帧时，
     * 这时退回读一次 DataStore——**不拿空表当答案**，那会让跳板引用变成「主机被删了」。
     */
    fun hostsBlocking(): List<Host> =
        hostCache.ifEmpty { runBlocking { hostStore.snapshot() }.also { hostCache = it } }

    val settingsStore: SettingsStore by lazy { SettingsStore(this) }

    val sessionManager = SessionManager()

    val updateChecker = UpdateChecker()

    val updateInstaller: UpdateInstaller by lazy { UpdateInstaller(this) }

    /**
     * 所有侧通道（tmux / 监控）共用的连接池。
     *
     * 只能有一个实例：两个池子会各拨一条连接到同一台主机，白白多一次握手认证，
     * 也让「按主机串行」失效。
     *
     * SFTP 的连接不进池（原因见 [SftpRepository]），但**可以被蹭**：用户待在文件页时
     * 那条连接就在手边，让侧通道看得见它，快速切换抽屉才有机会显示真实的会话列表，
     * 而不是一份带时间戳的旧缓存。lambda 是惰性的，不会在这里提前把 [sftpRepository] 造出来。
     */
    val execPool = ExecPool(sessionManager) { sftpRepository.liveConnection(it) }

    /** tmux 侧通道。探测缓存不该跟着主页 ViewModel 走，所以挂在这里。 */
    val tmuxRepository: TmuxRepository by lazy { TmuxRepository(TmuxCache(this), execPool) }

    val monitorRepository: MonitorRepository by lazy { MonitorRepository(execPool) }

    /** SFTP 自己管连接，不进 [execPool]，原因见 [SftpRepository] 的类注释。 */
    val sftpRepository: SftpRepository by lazy { SftpRepository(sessionManager) }

    /** 传输队列必须比页面活得久：传一个大文件时用户理应能退出文件页去干别的。 */
    val transferQueue: TransferQueue by lazy { TransferQueue(sftpRepository, contentResolver, scope) }

    /** 端口转发的配置表。只存配置不存运行状态，冷启动不会自己去拨号。 */
    val forwardStore: ForwardStore by lazy { ForwardStore(this) }

    /**
     * 端口转发。和 [transferQueue] 同理必须比页面活得久——用户开完转发就该去浏览器里用它，
     * 而不是被迫把 lightmux 停在转发页上。连接也自己管，理由见 [net.lighttools.lightmux.forward.ForwardManager]。
     */
    val forwardManager: ForwardManager by lazy { ForwardManager(hostStore, execPool, scope) }

    /**
     * 有没有「必须让进程活着」的活儿。前台服务的开关看它。
     *
     * 会话按**列表成员**算而不是按连接状态：一条断开的会话仍然攥着几千行滚屏历史，
     * 进程被回收就一起没了。用户按下「关闭会话」之前，它就是要保住的东西。
     */
    val hasLiveWork: Flow<Boolean> by lazy {
        combine(
            sessionManager.sessions,
            transferQueue.transfers,
            forwardManager.states,
        ) { sessions, transfers, forwards ->
            sessions.isNotEmpty() || transfers.any { it.active } || forwards.values.any { it.isLive }
        }
    }

    /**
     * 网络恢复监听。**只在有会话或转发时挂着**：`NetworkCallback` 由系统持有强引用，
     * 常驻注册等于一条跟着进程走的泄漏，而两者都没有时它也没有任何用处。
     */
    private val networkWatcher by lazy {
        NetworkWatcher(this) {
            sessionManager.onNetworkRestored()
            forwardManager.onNetworkRestored()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // vendored 模块不依赖 app 资源，断线文案由这里注入。
        TerminalSession.sDisconnectedLabel = getString(R.string.session_disconnected)
        hostStore.hosts.onEach { hostCache = it }.launchIn(scope)
        combine(sessionManager.sessions, forwardManager.states) { sessions, forwards ->
            // 失败终态的转发不算：它已经不重连了，为它吊着一个系统回调没有意义。
            sessions.isNotEmpty() || forwards.values.any { it.isLive }
        }
            .onEach { if (it) networkWatcher.start() else networkWatcher.stop() }
            .launchIn(scope)
        // 终端外观在这里落地而不是只在终端页：后台挂着的会话也得跟着换配色，
        // 否则用户切回去会看到一个没变的会话，以为设置只对新会话生效。
        settingsStore.settings
            .onEach {
                withContext(Dispatchers.Main) {
                    TerminalAppearance.apply(it, sessionManager.sessions.value)
                }
            }
            .launchIn(scope)
    }

    companion object {
        lateinit var instance: LightmuxApp
            private set
    }
}
