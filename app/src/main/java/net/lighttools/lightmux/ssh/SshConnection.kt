package net.lighttools.lightmux.ssh

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lighttools.lightmux.LightmuxApp
import net.lighttools.lightmux.data.AuthMethod
import net.lighttools.lightmux.data.Host
import net.lighttools.lightmux.data.HostRoute
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.Config
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.common.IOUtils
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.direct.PTYMode
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthMethod as SshjAuthMethod
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.AuthPublickey
import net.schmizz.sshj.userauth.method.ChallengeResponseProvider
import net.schmizz.sshj.userauth.password.PasswordUtils
import net.schmizz.sshj.userauth.password.Resource
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.Security
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** [SshConnection.exec] 的结果。stderr 单独给出，调用方自己决定要不要合并。 */
data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
    /** 退出码非 0 时优先看 stderr，为空再退回 stdout——很多命令把错误写在 stdout 上。 */
    val errorText: String get() = stderr.trim().ifBlank { stdout.trim() }
}

/** [SshConnection.exec] 超时。命令没在期限内结束，channel 已被强制关掉。 */
class ExecTimeoutException(val command: String, val timeoutMs: Long) :
    IOException("Command timed out after ${timeoutMs}ms: $command")

/**
 * 存下来的凭据解不开（见 [net.lighttools.lightmux.data.AuthMethod.credentialLost]）。
 *
 * 在**发起认证之前**就抛，不拿空凭据去撞服务端：撞出来的是一次普通认证失败，
 * 既误导用户去查密码，又白白给对端的 fail2ban 送一条失败记录。
 */
class CredentialLostException(val hostId: String) :
    IOException("Stored credential for host $hostId cannot be decrypted")

/**
 * 私钥的 PEM 格式我们认不出来（典型是 PuTTY 的 `.ppk`）。
 *
 * 单独一个类型是给重连状态机用的：这属于配置错误，重试多少次结果都一样
 * （见 [net.lighttools.lightmux.session.ReconnectDecision]）。
 */
class UnsupportedKeyFormatException(val format: String) :
    IOException("Unsupported private key format: $format")

/**
 * 跳板机那一跳出的问题（见 [net.lighttools.lightmux.data.HostRoute]）。
 *
 * 必须和目标主机自己的失败分开：两者最常见的原因都是「认证失败」，而一句不带来源的
 * 「认证失败」会把用户送去改目标主机的密码——那台的密码是对的，怎么改都连不上。
 *
 * @param jumpHostId 出问题的跳板机 id，为 null 表示**链本身坏了**（引用的主机被删、成环、层数过多）
 * @param jumpName   出问题的跳板机名，同上
 */
class ProxyJumpException(
    val jumpHostId: String?,
    val jumpName: String?,
    cause: Throwable? = null,
) : IOException(
    jumpName?.let { "Failed to connect via jump host $it" } ?: "Jump host chain is broken",
    cause,
) {
    /** 链坏了是配置问题，退避重试多少次都一样（见 [net.lighttools.lightmux.session.ReconnectDecision]）。 */
    val broken: Boolean get() = jumpHostId == null
}

/**
 * 一台主机的一条 SSH 连接。
 *
 * **一条连接多个 channel** 是整个产品的地基：前台终端占一个 shell channel，
 * tmux 列表 / 监控 / 动作走 [exec] 各开一个 exec channel，SFTP 再开一个 subsystem channel。
 * 绝不为了执行一条命令去新拨一条连接——重新握手 + 认证要几百毫秒，
 * 而主页展开一台主机就要探测好几次。
 */
class SshConnection(
    private val host: Host,
    private val knownHosts: KnownHosts = KnownHosts(LightmuxApp.instance),
    /**
     * 解析跳板链要用的主机表。默认取 app 里那份快照，所以所有调用点都不必关心跳板的存在。
     *
     * 只在 [Host.proxyJumpId] 非空时才会被调用——绝大多数主机是直连的，不该为它们付任何代价。
     */
    private val allHosts: () -> List<Host> = { LightmuxApp.instance.hostsBlocking() },
    /** 挂在 Application 上的单例（同 [KnownHosts] 的注入风格）——它持有一份进程级的前台闸门状态，不能每条连接各建一份。 */
    private val authChallenges: AuthChallenges = LightmuxApp.instance.authChallenges,
) {

    /** 开 channel 的过程不是线程安全的（分配 channel id + 发包 + 等回应），必须串起来。 */
    private val channelLock = ReentrantLock()

    @Volatile
    private var client: SSHClient? = null

    /** 当前这一跳正在用的 kb-interactive 应答桥，[close] 靠它把挂着的追问唤醒。 */
    @Volatile
    private var responder: ChallengeResponder? = null

    /**
     * 跳板链上那几条连接，按连接顺序。
     *
     * 必须自己攥着：它们不属于任何 channel，目标连接关掉不会带走它们，
     * 漏关就是一条一直挂在堡垒机上的 SSH 连接（对端 `who` 里看得见，也占着 MaxSessions）。
     */
    @Volatile
    private var jumpClients: List<SSHClient> = emptyList()

    /** 和 [SshTransport.close] 一样用原子位：两条线程同时 close 时只有一条该往下走。 */
    private val closed = AtomicBoolean(false)

    val isConnected: Boolean
        get() = !closed.get() && client?.let { it.isConnected && it.isAuthenticated } == true

    /** 首次连接后记录的主机指纹，UI 可展示给用户核对。 */
    @Volatile
    var hostFingerprint: String? = null
        private set

    suspend fun connect() = withContext(Dispatchers.IO) { connectBlocking() }

    /**
     * 阻塞版建连。[SshTransport.start] 本来就跑在自己的线程上，再套一层协程只是浪费。
     *
     * 有跳板机时先把链一台台连起来（每一跳都建立在上一跳的隧道里），最后一跳负责开到目标的隧道。
     * 中途任何一步失败都得把已经建起来的连接**倒序全关掉**：留一半就是几条挂在堡垒机上的空连接。
     */
    @Throws(IOException::class)
    fun connectBlocking() {
        if (isConnected) return
        check(!closed.get()) { "connection already closed" }
        ensureSecurityProvider()

        val opened = mutableListOf<SSHClient>()
        try {
            var via: SSHClient? = null
            for (jump in resolveJumps()) {
                val hop = newClient().also { opened += it }
                try {
                    connectAndAuth(hop, jump, via)
                } catch (e: Exception) {
                    // 包一层是为了让 UI 说得出「是哪一台跳板机的问题」，cause 原样留着，
                    // 重连决策与失败分类仍按内层的真实类型走。
                    throw ProxyJumpException(jump.id, jump.name, e)
                }
                hop.connection.keepAlive.keepAliveInterval = KEEPALIVE_SECONDS
                via = hop
            }
            val main = newClient().also { opened += it }
            connectAndAuth(main, host, via)
            main.connection.keepAlive.keepAliveInterval = KEEPALIVE_SECONDS
            jumpClients = opened.dropLast(1)
            client = main
            if (closed.get()) {
                // 建连期间被并发 close() 抢先跑完：那次 close() 在 `client` 还是 null 的那一刻
                // 结束，什么也没关成。不补这一步，刚建好的这些 SSHClient 就是永远没人关的孤儿
                // 连接——对端 `who` 里看得见，还占着 MaxSessions。清空字段、往下走到统一的清理逻辑。
                client = null
                jumpClients = emptyList()
                throw IOException("SSH connection is not established")
            }
        } catch (e: Throwable) {
            opened.asReversed().forEach { IOUtils.closeQuietly(it) }
            throw e
        }
    }

    /**
     * 解出这台主机的跳板链。链坏了一律抛，**不降级成直连**——目标多半是个内网地址，
     * 绕过跳板直接去撞它，用户得到的是一条看不出所以然的超时。
     */
    private fun resolveJumps(): List<Host> {
        if (host.proxyJumpId == null) return emptyList()
        return when (val route = HostRoute.resolve(host, allHosts())) {
            HostRoute.Route.Direct -> emptyList()
            is HostRoute.Route.Via -> route.jumps
            // 被删 / 成环 / 过深都是配置问题，jumpHostId 给 null 让上层判成终态，别退避重试
            is HostRoute.Route.Missing, HostRoute.Route.Cycle, HostRoute.Route.TooDeep ->
                throw ProxyJumpException(jumpHostId = null, jumpName = null)
        }
    }

    /**
     * 建连 + TOFU 校验 + 认证。三步绑在一起，任何一步失败都不该留下半开的 client。
     *
     * @param target 这一跳要连的主机，可能是跳板机也可能是 [host] 自己
     * @param via    非空则不开 socket，改从这条连接上开一个 direct-tcpip 隧道钻过去（`ssh -J` 就是这么干的）
     */
    private fun connectAndAuth(client: SSHClient, target: Host, via: SSHClient?) {
        val verifier = knownHosts.verifierFor(target.endpoint)
        client.addHostKeyVerifier(verifier)
        try {
            if (via == null) client.connect(target.hostname, target.port)
            else client.connectVia(via.newDirectConnection(target.hostname, target.port))
        } catch (e: Exception) {
            // 指纹不符时 sshj 只会抛「握手失败」，把类型还原出来，UI 才有得可弹。
            throw verifier.mismatch ?: e
        }
        // 指纹只记目标主机的：跳板机的那份归它自己那条记录，UI 上也是在它的会话里核对。
        if (target === host) hostFingerprint = verifier.acceptedFingerprint
        authenticate(client, target)
    }

    /**
     * 零配置回退到 keyboard-interactive：不管主机配的是密码还是私钥，都在后面追加一个
     * kb-interactive 尝试。多数主机服务端压根不提这个方法，这一步等于没做；开了 MFA 的主机
     * 才会真正用上——不新增 `AuthMethod` 枚举项、不碰主机编辑页，配置照旧。
     *
     * 顺序 `[配置的方法, kb-interactive]` 对应的是 `publickey,keyboard-interactive` 这类两步
     * 验证：sshj 在配置的方法拿到 partial success 后会自动往下试列表里的下一个，颠倒顺序会先问
     * 验证码、再问私钥密码，体验不对也不符合服务端期望的顺序。
     */
    private fun authenticate(client: SSHClient, target: Host) {
        if (target.auth.credentialLost) throw CredentialLostException(target.id)
        val configured: SshjAuthMethod? = when (val auth = target.auth) {
            is AuthMethod.Password -> AuthPassword(PasswordUtils.createOneOff(auth.password.toCharArray()))
            is AuthMethod.PrivateKey -> AuthPublickey(keyProvider(auth))
            AuthMethod.Agent ->
                throw UnsupportedOperationException("ssh-agent authentication is not supported yet")
        }

        val newResponder = ChallengeResponder(client, target, authChallenges)
        responder = newResponder
        // connectBlocking() 顶部的 `check(!closed.get())` 通过之后、这一行跑之前，还有一条窗口：
        // 另一条线程这时调 close() 见到的 responder 还是 null，abort() 无从谈起。这里补一次回查，
        // 晚到的 close() 就靠这一步兜底，不会把一次真实的用户取消晾在一边。
        if (closed.get()) {
            newResponder.abort()
            throw IOException("SSH connection is not established")
        }

        // kb-interactive 一问一答要等人看清提示、翻出验证器、敲完数字，几十秒很正常；sshj 默认的
        // 握手级超时（十几秒）等不到这个时间。每一跳都临时抬高、用完立刻恢复到 finally 里——
        // 跳板链场景不这样做的话，后面几跳会继承这次抬高后的超时，等价于把超时越滚越大。
        val transport = client.transport
        val originalTimeoutMs = transport.timeoutMs
        transport.timeoutMs = AuthChallenges.CHALLENGE_TIMEOUT_MS.toInt()
        try {
            client.auth(target.username, listOfNotNull(configured, AuthKeyboardInteractive(newResponder)))
        } catch (e: Exception) {
            // SSHClient#auth 在所有方法都失败后，把最后一个方法的失败重新包成一句通用的
            // "Exhausted available authentication methods"，真正的取消原因被压到 cause 里一层。
            // 直接换回 responder 记的那个真实原因，ConnectionFailure/ReconnectDecision 才不用
            // 越过这层通用包装去扒 cause 链才认得出「这是一次取消，不是一次认证失败」。
            throw newResponder.cancellation ?: e
        } finally {
            transport.timeoutMs = originalTimeoutMs
        }
    }

    /**
     * kb-interactive（RFC 4256）的应答桥。sshj 的认证线程（每条 Transport 一条 Reader 线程）
     * 同步调用这几个回调；我们把每一条追问转成 [AuthChallenge] 扔给 [AuthChallenges]，再阻塞
     * 等 UI 给出答案——这条线程本来就是阻塞式协议状态机的一部分，多等一会儿不算引入新问题。
     *
     * [init] / [getResponse] / [shouldRetry] 全部由同一条 Reader 线程串行调用——一条 Transport
     * 一条 Reader，每一跳有自己的 client + responder。`name` / `instruction` / `passwordUsed` /
     * `retried` 不加 `@Volatile` 就是靠这个前提：破坏它是一个隐蔽的数据竞争，不会在测试里露头。
     * [cancellation] 例外——[abort] 由 [close] 触发，调用线程可能是主线程，跨线程可见性必须靠
     * `@Volatile`；「建挑战 + 记 current」和 [abort] 还要共享同一把 [lock]，`@Volatile` 只保证
     * 可见性、不保证这两步之间不被 abort() 插队。
     */
    private class ChallengeResponder(
        private val client: SSHClient,
        private val target: Host,
        private val authChallenges: AuthChallenges,
    ) : ChallengeResponseProvider {

        private var name: String = ""
        private var instruction: String = ""

        /** 只有第一条真正是密码提示的追问才允许自动填一次，见 [ChallengePrompt.shouldAutofill]。 */
        private var passwordUsed = false

        /** 只允许重试一次：配置的方法(1) + kb-interactive(1) + 重试(1) = 3 次，远低于 OpenSSH 默认 MaxAuthTries=6。 */
        private var retried = false

        private val lock = Any()

        /** 正等着用户回答的那一问；[abort] 要靠它去唤醒挂着的线程。 */
        private var current: AuthChallenge? = null

        @Volatile
        var cancellation: ChallengeCancelledException? = null
            private set

        override fun getSubmethods(): MutableList<String> = mutableListOf()

        override fun init(resource: Resource<*>?, name: String?, instruction: String?) {
            // 平台类型来的参数，一个意外的 null 就是 NPE——NPE 不是 UserAuthException，
            // 会穿透 sshj 的异常表直接打死这条连接，不能让它有机会发生。
            this.name = name.orEmpty()
            this.instruction = instruction.orEmpty()
        }

        override fun getResponse(prompt: String?, echo: Boolean): CharArray {
            val text = prompt.orEmpty()

            val savedPassword = (target.auth as? AuthMethod.Password)?.password
            if (savedPassword != null && ChallengePrompt.shouldAutofill(text, allowedMethods(), passwordUsed)) {
                passwordUsed = true
                return savedPassword.toCharArray()
            }

            val challenge = AuthChallenge(target.id, target.name, name, instruction, text, echo)
            synchronized(lock) {
                // abort() 可能在我们进这个方法之前就已经跑完了——那就不该再弹一条新问题出来。
                cancellation?.let { throw it }
                current = challenge
            }
            // ask() 是阻塞调用，绝不能在持锁的情况下进去：abort() 需要这把锁才能唤醒它。
            try {
                return authChallenges.ask(challenge).toCharArray()
            } catch (e: ChallengeCancelledException) {
                synchronized(lock) { cancellation = e }
                throw e
            }
        }

        override fun shouldRetry(): Boolean {
            if (cancellation != null) return false
            if (retried) return false
            retried = true
            return true
        }

        /** [close] 调用，可能来自主线程——绝不能做 IO，只翻标志位 + 唤醒挂着的挑战。幂等。 */
        fun abort() {
            synchronized(lock) {
                if (cancellation == null) cancellation = ChallengeCancelledException()
                current?.cancel()
            }
        }

        private fun allowedMethods(): Collection<String> =
            runCatching { client.userAuth.allowedMethods }.getOrDefault(emptyList())
    }

    /**
     * 从 PEM 文本造 KeyProvider。
     *
     * 私钥只存在内存与 Keystore 密文里，**不落临时文件**——sshj 的 `loadKeys(String)` 收的是文件路径，
     * 用它就得先把私钥明文写到磁盘，那等于绕开了整套加密存储。
     */
    private fun keyProvider(auth: AuthMethod.PrivateKey): FileKeyProvider {
        val pem = auth.pem
        val format = KeyProviderUtil.detectKeyFileFormat(StringReader(pem), true)
        val provider: FileKeyProvider = Factory.Named.Util.create(
            sharedConfig.fileKeyProviderFactories, format.toString()
        ) ?: throw UnsupportedKeyFormatException(format.toString())
        val finder = auth.passphrase
            ?.takeIf { it.isNotEmpty() }
            ?.let { PasswordUtils.createOneOff(it.toCharArray()) }
        provider.init(StringReader(pem), finder)
        return provider
    }

    /**
     * 带外执行一条命令，新开 exec channel，**不碰前台终端**。
     *
     * 前台可能是全屏 TUI（vim / htop），也可能压根没 attach 到任何东西，
     * 往它注入按键的结果是不可预测的（见 CLAUDE.md「tmux 侧通道纪律」第 7 条）。
     *
     * @throws ExecTimeoutException 超时。命令卡住时必须能自己脱身，否则调用它的 IO 协程会永远挂着
     */
    @Throws(IOException::class)
    fun exec(command: String, timeoutMs: Long = DEFAULT_EXEC_TIMEOUT_MS): ExecResult {
        val ssh = requireClient()
        val session: Session = channelLock.withLock { ssh.startSession() }
        // 输出超过默认窗口（2MB）时，不自动扩窗会让远端写阻塞，而我们正阻塞在读上——死锁。
        session.autoExpand = true

        val timedOut = AtomicBoolean(false)
        val watchdog = execTimer.schedule({
            timedOut.set(true)
            // 关掉 channel 会让下面阻塞中的 read 立刻结束，这是唯一能打断阻塞 IO 的手段。
            // 但它本身是一次真实的 socket 写，对端不回 close 确认时能卡满 sshj 的关闭超时（几十秒），
            // 而定时线程只有一条——掉线时监控轮询和 tmux 探测会同时超时，占住它后面的全得排队。
            // 超时是罕见路径，这里另起一条线程去关很划算。
            Thread { IOUtils.closeQuietly(session) }
                .apply { isDaemon = true; name = "SshExecAbort" }
                .start()
        }, timeoutMs, TimeUnit.MILLISECONDS)

        try {
            val cmd = session.exec(command)
            val stdout = IOUtils.readFully(cmd.inputStream).toString("UTF-8")
            val stderr = IOUtils.readFully(cmd.errorStream).toString("UTF-8")
            cmd.join(timeoutMs, TimeUnit.MILLISECONDS)
            if (timedOut.get()) throw ExecTimeoutException(command, timeoutMs)
            return ExecResult(cmd.exitStatus ?: -1, stdout, stderr)
        } catch (e: Exception) {
            if (timedOut.get()) throw ExecTimeoutException(command, timeoutMs)
            throw e
        } finally {
            // cancel(false)：定时任务要么还没跑、就地作废，要么已经在跑、让它把 channel 关完
            // ——和原来「跑完了再 interrupt 也打断不了 close」的行为一致。
            watchdog.cancel(false)
            IOUtils.closeQuietly(session)
        }
    }

    /** 给前台终端用的交互式 shell，带 PTY。 */
    @Throws(IOException::class)
    fun openShell(cols: Int, rows: Int): ShellChannel {
        val ssh = requireClient()
        val session = channelLock.withLock { ssh.startSession() }
        try {
            session.allocatePTY(TERM_TYPE, cols, rows, 0, 0, PTY_MODES)
            return ShellChannel(session, session.startShell())
        } catch (e: Throwable) {
            IOUtils.closeQuietly(session)
            throw e
        }
    }

    /**
     * 本地端口转发（`ssh -L`）。**阻塞**到 [server] 被关闭为止。
     *
     * 每个进来的本地连接都会在这条 SSH 连接上开一个 direct-tcpip channel——又一次
     * 「一条连接多个 channel」，和 [exec] / [openSftp] 共用同一条隧道，不额外拨号。
     *
     * 停止转发的唯一办法是关掉 [server]：`listen()` 阻塞在 `accept()` 上，
     * 协程 cancel 打断不了阻塞 IO（和 [exec] 里 watchdog 关 channel 是同一个道理）。
     * 连接断掉时它不会立刻醒——要等下一个本地连接开 channel 失败才抛，
     * 所以调用方还得自己盯着连接活性（见 [net.lighttools.lightmux.forward.ForwardManager]）。
     *
     * 这里不取 [channelLock]：`newLocalPortForwarder` 只是造个对象，真正开 channel 发生在
     * 每次 accept 之后、由 sshj 自己完成，锁也管不着；而 `listen()` 一阻塞就是几小时，
     * 持锁进去会把整台主机的 exec 和 SFTP 全堵死。
     */
    @Throws(IOException::class)
    fun forward(remoteHost: String, remotePort: Int, server: ServerSocket) {
        val ssh = requireClient()
        val local = server.localSocketAddress as InetSocketAddress
        val params = Parameters(local.address.hostAddress, local.port, remoteHost, remotePort)
        ssh.newLocalPortForwarder(params, server).listen()
    }

    /**
     * 开一条新的 SFTP subsystem channel，**调用方负责关**。
     *
     * 这里不缓存一个共享实例：`SFTPClient` 不是线程安全的，共享出去就得所有使用者排同一把锁，
     * 而上传一个大文件会把那把锁占住几分钟，浏览目录只能干等
     * （分通道的理由见 [net.lighttools.lightmux.sftp.SftpRepository]）。
     * 一条连接上多开一个 channel 几乎免费，多拨一条连接才贵。
     */
    @Throws(IOException::class)
    fun openSftp(): SFTPClient = channelLock.withLock { requireClient().newSFTPClient() }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        // 先摘引用再关：`closed` / [isConnected] 必须立刻生效，真正的 socket 收尾可以慢一步。
        val main = client
        val jumps = jumpClients
        val activeResponder = responder
        client = null
        jumpClients = emptyList()
        // 关 socket 唤不醒一条正阻塞在挑战上的 Reader 线程——它在等的是 AuthChallenge 的应答队列，
        // 不是这条 socket。abort() 只翻标志位、offer 一个取消哨兵，不做任何 IO，可以放在
        // SshIo.quietly 之前直接调用（close() 常常就在主线程上跑）。
        activeResponder?.abort()
        // 关连接要发 SSH_MSG_DISCONNECT，是一次真实的 socket 写，而调用方遍布主线程
        // （主页收起主机卡片、退出文件页、关会话），必须甩开——原因见 [SshIo]。
        // 跳板链倒着关：先关目标那一条，它占的 channel 才不会拖着上一跳。
        SshIo.quietly {
            IOUtils.closeQuietly(main)
            jumps.asReversed().forEach { IOUtils.closeQuietly(it) }
        }
    }

    private fun requireClient(): SSHClient {
        val ssh = client
        if (closed.get() || ssh == null || !ssh.isConnected) throw IOException("SSH connection is not established")
        return ssh
    }

    private fun newClient() = SSHClient(sharedConfig).apply {
        connectTimeout = CONNECT_TIMEOUT_MS
    }

    companion object {

        private const val TAG = "SshConnection"
        private const val TERM_TYPE = "xterm-256color"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val KEEPALIVE_SECONDS = 30
        const val DEFAULT_EXEC_TIMEOUT_MS = 15_000L

        /**
         * 不指定任何 PTY 模式，用服务端默认值。
         *
         * 回显、行编辑、换行转换这些都由远端 shell 决定，客户端硬塞一套模式反而会和
         * 远端的 stty 设置打架（典型症状：进 vim 后方向键变乱码）。
         */
        private val PTY_MODES = emptyMap<PTYMode, Int>()

        /**
         * [exec] 的超时计时器，全进程一条线程。
         *
         * 以前是每次 exec 起一条线程 sleep 到期，可 exec 是**稳定态里一直在跑**的东西：
         * 监控页 5 秒一轮、抽屉的 tmux 探测、每个动作命令都走它，一分钟十几次线程创建 + interrupt，
         * 而其中 99.99% 的线程什么也没做就被打断了。计时用一条共享线程就够。
         *
         * `removeOnCancelPolicy` 必须开：正常结束时任务是被 cancel 的，不设它，
         * 被取消的任务会一直占在延迟队列里等到它本来的到期时刻才被清掉。
         */
        private val execTimer: ScheduledExecutorService =
            ScheduledThreadPoolExecutor(1) { runnable ->
                Thread(runnable, "SshExecTimer").apply { isDaemon = true }
            }.apply { removeOnCancelPolicy = true }

        /**
         * Config 构造一次要跑完整的算法工厂初始化（几十毫秒），且是只读的，全进程共用一份。
         *
         * 用 [LightmuxSshConfig]（sshj 的 AndroidConfig + 补回被削掉的公钥算法）而不是
         * DefaultConfig：后者会去探测一批 JDK 上才有的密钥算法，在 Android 上要么慢
         * 要么直接 NoClassDefFoundError。
         */
        private val sharedConfig: Config by lazy {
            ensureSecurityProvider()
            LightmuxSshConfig().apply {
                // 移动网络断了不会立刻给 FIN，不发心跳的话断线要等到用户下一次敲键才被发现。
                keepAliveProvider = KeepAliveProvider.HEARTBEAT
            }
        }

        @Volatile
        private var providerReady = false

        /**
         * Android 自带一个被阉割过的 BouncyCastle（provider 名同样是 "BC"，但删掉了大量算法），
         * 不换掉它，sshj 会在 KEX 阶段报 NoSuchAlgorithmException 或 NoClassDefFoundError。
         * 必须先 remove 再 add 完整版。
         */
        @Synchronized
        fun ensureSecurityProvider() {
            if (providerReady) return
            try {
                if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) !is BouncyCastleProvider) {
                    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                    Security.addProvider(BouncyCastleProvider())
                }
                SecurityUtils.setRegisterBouncyCastle(true)
                SecurityUtils.setSecurityProvider(BouncyCastleProvider.PROVIDER_NAME)
            } catch (e: Exception) {
                // 换不成就让 sshj 用平台默认 provider 试，能连上多少算多少，总比开不了 app 强。
                Log.w(TAG, "failed to install BouncyCastle, falling back to platform provider", e)
            }
            providerReady = true
        }
    }
}

/**
 * 一个交互式 shell channel。只是把 sshj 的两个对象包起来，避免上层直接摸 sshj 的类型。
 */
class ShellChannel internal constructor(
    private val session: Session,
    private val shell: Session.Shell,
) {

    val input: InputStream get() = shell.inputStream
    val errorInput: InputStream get() = shell.errorStream
    val output: OutputStream get() = shell.outputStream
    val isOpen: Boolean get() = shell.isOpen

    /** 远端退出码；channel 还没关时为 null。 */
    val exitStatus: Int? get() = runCatching { (shell as? Session.Command)?.exitStatus }.getOrNull()

    fun resize(cols: Int, rows: Int) {
        runCatching { shell.changeWindowDimensions(cols, rows, 0, 0) }
    }

    /**
     * 阻塞到 channel 关闭。**连接是断的还是远端自己退的，全靠它抛不抛异常来区分。**
     *
     * 以前这里裹了一层 `runCatching`，于是掉线和 `exit` 在上层长得一模一样：
     * 读线程的异常本来就被吞了（见 [SshTransport.copy]），join 再把连接异常也吞掉，
     * `failure` 就永远是 null，[net.lighttools.lightmux.session.ReconnectDecision] 一律判 GiveUp——
     * 整套指数退避自动重连对最常见的「网络断了」形同虚设，用户只看得到「已断开」和一个手动重连按钮。
     *
     * 用户主动关会话时 close 会让 join 抛，但那条路径上 `closed` 已经置位，上层不会当成故障。
     */
    @Throws(IOException::class)
    fun awaitClose() {
        shell.join()
    }

    fun close() {
        IOUtils.closeQuietly(shell, session)
    }
}
