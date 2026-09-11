package net.lighttools.lightmux.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.lighttools.lightmux.data.Host
import net.lighttools.lightmux.session.SessionManager
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * 连的是一台已知需要验证码的主机，而这次拨号不是显式用户动作——拒绝，不弹 OTP 对话框。
 *
 * 见 [ExecPool] 的 MFA 硬闸门：[net.lighttools.lightmux.ui.monitor.MonitorViewModel] 的轮询
 * 每 5 秒探测一次、失败不退避，真去拨的话就是验证码对话框每 5 秒弹一次——而
 * `pam_google_authenticator` 默认拒绝复用刚用过的验证码，第二次尝试注定失败，循环打不破。
 */
class MfaHostConnectionRequiredException(hostId: String) :
    IOException("Host $hostId requires interactive verification; connect explicitly first")

/**
 * 侧通道用的连接池，**活在 Application 作用域**（挂在 `LightmuxApp` 上）。
 *
 * tmux 探测与监控采集是同一件事的两个使用者：都要「拿一条到这台主机的 SSH 连接、
 * 在上面开一个新 channel」。这套「优先复用活连接 + 按主机串行 + 池化」的逻辑
 * 原本长在 `TmuxRepository` 里，第二个使用者出现时抽到这里——是**消除重复**，不是加抽象层。
 *
 * **SFTP 不走这里**：[withConnection] 在 block 跑完之前一直持有这台主机的 Mutex，
 * 而一次上传动辄几分钟，挂进来会把 tmux 探测和监控采集全堵死。
 * 它自己维护连接与通道，见 [net.lighttools.lightmux.sftp.SftpRepository]。
 */
class ExecPool(
    private val sessions: SessionManager,
    /**
     * 别处已经建好、可以蹭的连接（现在只有文件页那条 SFTP 连接）。
     *
     * 用函数注入而不是直接持有 `SftpRepository`：SFTP 不进这个池是刻意的（见类注释），
     * 让池子反过来依赖 sftp 包只会把这层关系搅浑。**不拿所有权**——蹭来的连接由原主人关，
     * 拿到手时可能已经失效，和复用前台终端那条连接是同一种风险。
     */
    private val borrowed: (hostId: String) -> SshConnection? = { null },
    /**
     * 这台主机是不是运行时已经确认过需要验证码——见 [connectionFor] 末尾的硬闸门。
     *
     * 用函数注入而不是直接持有 `AuthChallenges`：这个池子本来就不该知道验证码 UI 怎么问，
     * 它只需要一个「能不能自己拨号」的判断。
     */
    private val isMfaHost: (hostId: String) -> Boolean = { false },
) {

    /**
     * 这台主机上没有终端会话时，我们自己拨的那条连接。
     *
     * 留着复用而不是用完就关：展开一台主机之后紧接着就是刷新、监控、动作这些操作，
     * 每次重新握手 + 认证要几百毫秒，而且短时间内反复连同一台机器容易踩 fail2ban。
     */
    private val pool = ConcurrentHashMap<String, SshConnection>()

    /** 按主机串行。防的是「展开的同时点刷新」各拨一条连接，也顺便让同一台机器上的 exec 有序。 */
    private val locks = KeyedMutex()

    /**
     * 在这台主机的连接上跑一段。
     *
     * @throws java.io.IOException 连不上 / 超时
     */
    suspend fun <T> withConnection(host: Host, block: suspend (SshConnection) -> T): T =
        withContext(Dispatchers.IO) {
            locks[host.id].withLock { block(connectionFor(host)) }
        }

    /**
     * 放掉为这台主机拨的连接。
     *
     * **只关自己拨的那条**：终端会话和文件页的连接都不在池子里，这里关不到——
     * 用户从监控页返回时，前台终端和正传着的文件都不会跟着断。
     * 取锁再关是为了不打断正在跑的 exec。
     */
    suspend fun release(hostId: String) {
        locks[hostId].withLock { pool.remove(hostId)?.close() }
    }

    /**
     * 现在有没有一条能直接用的连接。
     *
     * 给「自动发起的探测」当闸门用：抽屉拉开时的静默刷新只肯蹭现成的连接，
     * 蹭不到就老老实实显示缓存——为渲染一个列表去握手认证，等于把
     * 「绝不自动全量探测」（PRD §4.3）悄悄作废。判断口径必须和 [connectionFor] 的前三步一致。
     */
    fun hasLiveConnection(hostId: String): Boolean =
        sessions.forHost(hostId).any { it.connection.isConnected } ||
            pool[hostId]?.isConnected == true ||
            borrowed(hostId)?.isConnected == true

    /**
     * 拿一条能用的连接。优先复用前台终端那条（PRD §4.3 第 3 点），其次池子里的，
     * 再次是别处借来的，都没有才拨新的。
     *
     * 借来的排在自建的后面：自己拨的那条生命周期归我们管，蹭的那条随时可能被原主人关掉。
     */
    private fun connectionFor(host: Host): SshConnection {
        sessions.forHost(host.id)
            .firstOrNull { it.connection.isConnected }
            ?.let { return it.connection }

        pool[host.id]?.let { pooled ->
            if (pooled.isConnected) return pooled
            // 断了的连接不能再 connect，只能丢掉重来。
            pool.remove(host.id)
            pooled.close()
        }

        // 蹭来的连接也要查活性：这一步以前不查，靠的是唯一那个注入实现自己在
        // `SftpRepository.liveConnection` 里带了 takeIf——一条写在别的类里的隐式契约，
        // 加第二个来源就会破，而破的表现是「拿到一条死连接、exec 直接抛」，
        // 且再也不会去拨新的（下面那段永远走不到）。
        borrowed(host.id)?.takeIf { it.isConnected }?.let { return it }

        // 走到这里说明前三步全落空，真要新拨一条连接了——MFA 主机的硬闸门卡在这一步，
        // 而不是卡在 withConnection 入口：主机第一次连接时还不知道它需要验证码（运行时才学得到，
        // 见 AuthChallenges.isMfaHost），不能拦住它唯一一次学习的机会。
        if (isMfaHost(host.id)) throw MfaHostConnectionRequiredException(host.id)

        val fresh = SshConnection(host)
        fresh.connectBlocking()
        pool[host.id] = fresh
        return fresh
    }
}
