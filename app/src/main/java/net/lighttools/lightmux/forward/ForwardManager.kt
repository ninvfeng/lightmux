package net.lighttools.lightmux.forward

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.lighttools.lightmux.data.Host
import net.lighttools.lightmux.data.HostStore
import net.lighttools.lightmux.session.ReconnectDecision
import net.lighttools.lightmux.session.ReconnectPolicy
import net.lighttools.lightmux.ssh.ExecPool
import net.lighttools.lightmux.ssh.KeyedMutex
import net.lighttools.lightmux.ssh.SshConnection
import net.schmizz.sshj.common.IOUtils
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/** 转发起不来的原因。分类的意义只有一个：**决定还要不要重试**。 */
enum class ForwardFailure {

    /** 本地端口被别的 app 占了。换个端口之前重试多少次都一样。 */
    PortInUse,

    /** 认证 / 密钥 / 配置问题，等同 [ReconnectDecision.GiveUp]。 */
    Rejected,

    /** 主机配置没了（用户把主机删了，但转发还开着）。 */
    HostGone,
}

/** 一条转发此刻的状态。不在 [ForwardManager.states] 里 = 没开。 */
sealed interface ForwardStatus {

    /** 正在建连。 */
    data object Starting : ForwardStatus

    /** 通了，本地端口正在监听。 */
    data object Active : ForwardStatus

    /** 断了，正在退避重连。 */
    data class Retrying(val attempt: Int) : ForwardStatus

    /** 终态，要用户动手才能变。 */
    data class Failed(val reason: ForwardFailure, val detail: String? = null) : ForwardStatus

    /** 还占着资源（进程得活着），[Failed] 不算。 */
    val isLive: Boolean get() = this !is Failed
}

/**
 * 端口转发的运行时，**活在 Application 作用域**。
 *
 * ## 为什么自己拨连接
 *
 * 转发是要挂几个小时的东西，而现成的连接都会被别人收走：[ExecPool.release] 在离开监控页时
 * 关掉池里的连接，终端会话一关也带走它自己那条。蹭任何一条，用户关个终端就会发现
 * 浏览器里的页面刷不出来了，而且**没有任何提示**——转发不像终端，断了不会有人告诉你。
 * 所以这里照着 [net.lighttools.lightmux.sftp.SftpRepository] 的先例自管连接：
 * 一台主机一条，这台主机上的所有转发共用（转发本来就是 channel 级别的，开十条也只要一条连接）。
 *
 * 探测监听端口是另一回事——那是一条几十毫秒的命令，走 [ExecPool] 正合适。
 */
class ForwardManager(
    private val hostStore: HostStore,
    private val execPool: ExecPool,
    private val scope: CoroutineScope,
) {

    private val _states = MutableStateFlow<Map<String, ForwardStatus>>(emptyMap())
    val states: StateFlow<Map<String, ForwardStatus>> = _states.asStateFlow()

    /** 有没有转发占着资源。前台服务的存活条件之一（见 `LightmuxApp.hasLiveWork`）。 */
    val hasLiveForward: Flow<Boolean> = states.map { m -> m.values.any { it.isLive } }

    private val running = ConcurrentHashMap<String, Running>()

    /** 每台主机一条专用连接。 */
    private val links = ConcurrentHashMap<String, SshConnection>()

    private val linkLocks = KeyedMutex()

    /** 只在 [ensureHealthCheck] / [keepChecking] 这对同步方法里读写，所以不用 `@Volatile`。 */
    private var healthJob: Job? = null

    /**
     * 正在跑的一条转发。
     *
     * [socket] 单独拿出来是因为**停止转发的唯一办法是关掉它**：`listen()` 阻塞在 `accept()` 上，
     * 协程 cancel 打断不了阻塞 IO。重连时会换一个新的 ServerSocket，所以这里存的是个可变引用。
     */
    private class Running(val spec: ForwardSpec) {
        val socket = AtomicReference<ServerSocket?>()

        /**
         * 已经被 [stop] 摘掉。
         *
         * 有 [job] 还要这个标志，是因为 [stop] 关 socket 那一下可能**扑空**：它 `getAndSet(null)`
         * 的时候 loop 也许刚 `bind()` 完、还没把 server 交上来。那之后 loop 手里就攥着一个
         * 没人认领的 ServerSocket 继续往下走，而它接下来经过的 `Mutex.withLock`（复用现成连接时
         * 走不挂起的快路径）和 `forward()`（阻塞 IO）**都不看 cancellation**，径直阻塞进
         * `accept()`——`running` 里已经没这条转发，[wakeDeadLinks] 也扫不到它，端口就此长住。
         * 所以得留一个 loop 自己能主动查的位。
         */
        val stopped = AtomicBoolean(false)

        /**
         * 不是 `lateinit`：[start] 是先把条目放进 `running` 再回填 job 的，中间那一瞬 [stop]
         * 若拿到它就会 `UninitializedPropertyAccessException`。眼下所有调用点都在主线程，
         * 这条路走不到，但把它写成可空只多一个 `?`，就永远不用再验证这个前提。
         */
        @Volatile
        var job: Job? = null
    }

    /** 幂等：已经在跑的转发再点一次不会拨第二条连接。 */
    fun start(spec: ForwardSpec) {
        val run = Running(spec)
        if (running.putIfAbsent(spec.id, run) != null) return
        setStatus(spec.id, ForwardStatus.Starting)
        run.job = scope.launch(Dispatchers.IO) { loop(spec, run) }
        ensureHealthCheck()
    }

    fun stop(id: String) {
        val run = running.remove(id)
        if (run == null) {
            // 终态（Failed）不在 running 里，但状态还留着给 UI 显示原因。删除时得把它一并擦掉，
            // 否则状态表里会攒下一堆已经不存在的转发，网络监听也跟着一直吊在那儿。
            _states.update { it - id }
            return
        }
        // 置位必须排在最前面：下面那两步都可能对还没启动完的 loop 落空（socket 没交上来、
        // 阻塞 IO 不理会 cancel），标志是 loop 唯一能自己查出「我已经被停了」的东西。
        run.stopped.set(true)
        // 先 cancel 再关 socket：顺序反过来的话，loop 会把「socket 关了」当成断线，
        // 白白进一次退避重连才发现自己已经被取消。
        run.job?.cancel()
        IOUtils.closeQuietly(run.socket.getAndSet(null))
        _states.update { it - id }
        // 连接的释放不在这儿做：这里发起的 releaseIfIdle 会跑在 loop 拨号之前（见 [loop]），
        // 统一交给 loop 的出口。
    }

    fun stopAll() = running.keys.toList().forEach(::stop)

    /**
     * 主机被删掉时清场。**必须停**：转发页是按主机进的，主机没了页面也就进不去，
     * 留下的隧道会一直连着一台用户以为已经删干净了的机器，还攥着它的凭据。
     */
    fun stopForHost(hostId: String) =
        running.values.filter { it.spec.hostId == hostId }.map { it.spec.id }.forEach(::stop)

    fun isRunning(id: String): Boolean = running.containsKey(id)

    /** 这台主机上活着的转发条数，主页拿它显示角标。 */
    fun liveCount(hostId: String): Int =
        running.values.count { it.spec.hostId == hostId && _states.value[it.spec.id]?.isLive == true }

    /**
     * 探测远端正在监听哪些端口。
     *
     * 走 [ExecPool] 而不是转发自己那条连接：探测多半发生在**还没有任何转发**的时候，
     * 为列一次端口去拨一条专用连接，等于把「用户还没点开就先连上去」这件事做实了。
     */
    suspend fun probe(host: Host): List<ListeningPort> = execPool.withConnection(host) { conn ->
        ListeningPorts.parse(conn.exec(ListeningPorts.PROBE_COMMAND).stdout)
    }

    /**
     * 网络恢复了：把死掉的连接立刻踢一脚，别等健康检查那一轮。
     *
     * 和 [net.lighttools.lightmux.session.SessionManager.onNetworkRestored] 是同一个道理，
     * 差别是转发没有「用户正盯着」的压力，所以只处理已经断掉的，不动正常的那些。
     */
    fun onNetworkRestored() = wakeDeadLinks()

    /**
     * 一条转发的完整生命周期，**连接的释放只在这里收口**。
     *
     * 早先是 [stop] 自己发起 `releaseIfIdle` 的，那会漏：`stop` 先把条目从 `running` 里摘掉，
     * 再去 `releaseIfIdle`——而此刻 loop 可能还没走到 `connectionFor`（堵在开头那个 `hostStore.get`
     * 的 DataStore 读上，或者干脆卡在 `bind()` 这种 cancel 根本打不断的阻塞 IO 里），一条连接都还没拨。
     * 于是 `releaseIfIdle` 抢先拿到锁、发现 `links` 是空的、什么也没关就散了，
     * 紧接着 loop 才拨号并把新连接塞进 `links`。此后 `running` 里再没有这台主机的条目，
     * 没人会再调 `releaseIfIdle`，[wakeDeadLinks] 又只关 socket 不关连接——这条已认证的 SSH 连接
     * 连同它 30 秒一次的心跳线程一直活到进程结束。触发它只需要：对一台响应慢的主机点开转发，
     * 等不及又点了停止。
     *
     * 放在出口就没有这个窗口了：loop 无论怎么结束（被 cancel、[fail] 终态、正常退出），
     * 拨出去的连接都已经在 `links` 里，这一下必定看得见。
     */
    private suspend fun loop(spec: ForwardSpec, run: Running) {
        try {
            serve(spec, run)
        } finally {
            // NonCancellable 不能省：走到这儿多半正是因为被 cancel，不脱开的话
            // releaseIfIdle 里的 withLock 会立刻抛 CancellationException，等于没释放。
            // 多调一次是安全的——releaseIfIdle 自己会先看这台主机上还有没有别的转发在跑。
            withContext(NonCancellable) { releaseIfIdle(spec.hostId) }
        }
    }

    private suspend fun serve(spec: ForwardSpec, run: Running) {
        val host = hostStore.get(spec.hostId)
        if (host == null) {
            fail(run, ForwardFailure.HostGone)
            return
        }
        val policy = ReconnectPolicy()
        while (coroutineContext.isActive) {
            var server: ServerSocket? = null
            try {
                server = bind(spec.localPort)
                run.socket.set(server)
                // [stop] 可能正好挤在 bind 和上面这一行之间，那它的 getAndSet(null) 就扑了个空、
                // 什么也没关。这一步不查，下面的 connectionFor 一旦复用到现成的活连接（同一台主机上
                // 还有别的转发在跑），`Mutex.withLock` 走快路径既不挂起也不检查 cancellation，
                // 就直接阻塞进 accept() 再也出不来了——协程和本地端口一起烂掉，用户重开同一条转发
                // 只会撞 BindException，被判成 PortInUse 终态，除了重启 app 没有别的出路。
                // server 交给下面的 finally 关。
                if (run.stopped.get()) return
                val connection = connectionFor(host)
                setStatus(spec.id, ForwardStatus.Active)
                policy.reset()
                // 阻塞到 server 被关：用户停止、或健康检查发现连接死了来逼它重来。
                connection.forward(spec.remoteHost, spec.remotePort, server)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BindException) {
                // 端口被别的 app 占着，退避多久都一样，只能等用户换一个。
                fail(run, ForwardFailure.PortInUse, e.message)
                return
            } catch (e: Throwable) {
                if (!coroutineContext.isActive) return
                if (ReconnectDecision.of(closedByUser = false, failure = e) == ReconnectDecision.GiveUp) {
                    fail(run, ForwardFailure.Rejected, e.message)
                    return
                }
                Log.d(TAG, "forward ${spec.localPort} dropped, will retry", e)
            } finally {
                run.socket.compareAndSet(server, null)
                IOUtils.closeQuietly(server)
            }
            if (!coroutineContext.isActive) return
            // 能走到这儿说明这一轮没了：要么抛了个值得重试的异常，要么 listen 自己返回了
            // （ServerSocket 被关）。两种都当断线处理——**包括正常返回**，否则 listen 一旦
            // 立刻返回，这个 while 就成了空转的死循环。
            setStatus(spec.id, ForwardStatus.Retrying(policy.attempts + 1))
            delay(policy.nextDelayMs())
        }
    }

    /**
     * 只绑环回口。
     *
     * 绑 `0.0.0.0` 会把服务器上那个「只监听 127.0.0.1」的服务原样摊给手机所在的整个
     * Wi-Fi——用户连一次咖啡店热点就等于把内网数据库开给同网段的人，而他完全不会意识到
     * 自己做了这件事。要开放得是个显式选项，不能是默认。
     */
    private fun bind(port: Int): ServerSocket = ServerSocket().apply {
        // 上一轮的连接可能还在 TIME_WAIT 里挂着，不设这个，断线重连会撞 BindException,
        // 而那会被判成「端口被占」这种终态。
        reuseAddress = true
        bind(InetSocketAddress(loopbackV4(), port))
    }

    /**
     * IPv4 环回口。
     *
     * **不能用 `InetAddress.getLoopbackAddress()`**——它在 Android 上返回 `::1`，
     * 而 [ForwardSpec.localUrl] 给用户的是 `127.0.0.1`，两边错开协议栈就成了
     * 「显示转发中、浏览器连不上」（原委见 [ForwardSpec.LOOPBACK]）。
     * 用 `getByAddress` 直接拿字节构造，不碰任何名字解析。
     */
    private fun loopbackV4(): InetAddress =
        InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    private suspend fun connectionFor(host: Host): SshConnection = linkLocks[host.id].withLock {
        links[host.id]?.let { existing ->
            if (existing.isConnected) return@withLock existing
            // 断了的连接不能再 connect，只能丢掉重来（同 ExecPool）。
            links.remove(host.id)
            existing.close()
        }
        SshConnection(host).also {
            it.connectBlocking()
            links[host.id] = it
        }
    }

    /** 这台主机上一条转发都不剩了就把连接放掉，别让它一直挂在那儿耗着对端的 sshd。 */
    private suspend fun releaseIfIdle(hostId: String) {
        if (running.values.any { it.spec.hostId == hostId }) return
        linkLocks[hostId].withLock { links.remove(hostId)?.close() }
    }

    /**
     * 定期看连接还活着没有。
     *
     * 非做不可的原因：`listen()` **发现不了断线**——它阻塞在 `accept()` 上，要等下一个本地连接
     * 进来、开 channel 失败才会抛。没有这一轮检查，用户切了趟地铁回来打开浏览器，
     * 看到的是一次连接失败，得自己刷新第二次才好；而他多半会先怀疑是 app 坏了。
     */
    @Synchronized
    private fun ensureHealthCheck() {
        if (healthJob?.isActive == true) return
        healthJob = scope.launch {
            while (keepChecking()) {
                delay(HEALTH_INTERVAL_MS)
                wakeDeadLinks()
            }
        }
    }

    /**
     * 检查线程的退场判定，**和 [ensureHealthCheck] 共用同一把锁**——两边合起来才是原子的交接。
     *
     * 分开写会漏掉一条转发：旧 job 跑完 [wakeDeadLinks]、正要判定「没转发了，退」的这一瞬间，
     * 它的 `isActive` 还是 true，[start] 一看「检查还活着」就直接返回，紧接着旧 job 退场——
     * 新加的这条转发从此没有任何健康检查。而它是**唯一**能发现「链路已经死了但 accept() 还傻等着」
     * 的机制，缺了它用户切完地铁回来只会看到一次连接失败，而且不会自愈。
     *
     * 收进一把锁后只剩两种顺序：要么 [start] 先进（它是先 `running.putIfAbsent` 再调
     * [ensureHealthCheck] 的，所以这里必然看得见新条目，判定继续跑），要么这里先进、
     * 把 `healthJob` 摘成 null，[start] 就会重新起一个。
     *
     * 没选「常驻单协程 + 空转 delay」是因为那样一条转发都没有时也得每 15 秒醒一次，
     * 而这个类活在 Application 作用域里，绝大多数时候一条转发都没有。
     */
    @Synchronized
    private fun keepChecking(): Boolean {
        if (running.isNotEmpty()) return true
        healthJob = null
        return false
    }

    private fun wakeDeadLinks() {
        running.values.groupBy { it.spec.hostId }.forEach { (hostId, runs) ->
            if (links[hostId]?.isConnected != false) return@forEach
            // 关掉 ServerSocket 是唯一能把 loop 从 accept 里叫醒的手段，它醒来就会去重连。
            runs.forEach { IOUtils.closeQuietly(it.socket.getAndSet(null)) }
        }
    }

    private fun setStatus(id: String, status: ForwardStatus) {
        // 已经被 stop 摘掉的转发不该再写回状态：loop 醒过来时可能比 stop 慢一拍，
        // 写回去就成了一条永远停不掉的幽灵记录。
        if (!running.containsKey(id)) return
        _states.update { it + (id to status) }
    }

    /**
     * 终态：把它从 running 里摘掉，但状态留着给 UI 显示原因。
     *
     * 不用管连接——三个调用点都在 [serve] 里且调完就 return，[loop] 的出口会释放。
     *
     * 摘表和写状态必须是一个原子动作，否则 [fail] 自己就成了 [setStatus] 挡的那种幽灵写：
     * 它跑在 IO 线程的 loop 里，而 [start] / [stop] 跑在主线程。先 remove 再 update 的话，
     * 中间那一瞬用户重开同一条转发（新 [Running] 已入表、`Starting` 已写下），
     * 紧接着这里的 `Failed` 就把它盖掉了——UI 显示失败，loop 却在正常建连；更糟的是
     * `Failed.isLive == false` 会让 `LightmuxApp.hasLiveWork` 判成「没活儿」，
     * 前台服务可能就在新转发建连的窗口里 `stopSelf`。
     *
     * `compute` 对这个 key 是持锁的，并发的 `putIfAbsent` / `remove` 都得等在外面；
     * 而比对 [run] 是同一个实例，保证只有「自己这一轮」才有资格写终态：
     * 被 [stop] 摘掉的、或已经被新一轮顶掉的，都原样放回去不动。
     */
    private fun fail(run: Running, reason: ForwardFailure, detail: String? = null) {
        running.compute(run.spec.id) { id, current ->
            if (current !== run) return@compute current
            _states.update { it + (id to ForwardStatus.Failed(reason, detail)) }
            null
        }
    }

    private companion object {
        const val TAG = "ForwardManager"

        /**
         * 15 秒。SSH 心跳是 30 秒一次（见 [SshConnection]），检查得比它密才有意义；
         * 再密就是纯粹的空转，`isConnected` 只是读个标志位，但唤醒 CPU 是有代价的。
         */
        const val HEALTH_INTERVAL_MS = 15_000L
    }
}
