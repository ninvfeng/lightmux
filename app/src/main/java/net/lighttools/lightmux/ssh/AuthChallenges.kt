package net.lighttools.lightmux.ssh

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.schmizz.sshj.userauth.UserAuthException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 服务端在一次 keyboard-interactive 认证里抛回来的一个追问（RFC 4256）。
 *
 * **故意不是 `data class`**：[AuthChallengeDialog][net.lighttools.lightmux.ui.common.AuthChallengeDialog]
 * 靠 `key(challenge)` 强制重建输入框状态，如果这里用结构相等（同一台主机连续两次问同样一句
 * `Verification code:`——重试时太常见了），两个不同的追问会被判成"同一个"，`key()` 就不会重建，
 * 输入框里会留着上一次的答案。引用相等才能保证每一问都是独一份。
 *
 * [hostId] 只给 [AuthChallenges] 记账用（见 MFA 主机集合），UI 只展示 [hostName] / [name] /
 * [instruction] / [prompt]——服务端原文，不翻译、不改写，只有它自己知道要问什么。
 */
class AuthChallenge(
    val hostId: String,
    val hostName: String,
    val name: String,
    val instruction: String,
    val prompt: String,
    val echo: Boolean,
) {

    /** 应答信箱，容量 1：UI 线程写、Reader 线程读，一问一答，多一格没用。 */
    private val mailbox = ArrayBlockingQueue<Any>(1)

    /** UI 线程调用：用户点了确定。 */
    fun answer(text: String) {
        mailbox.offer(text)
    }

    /** UI 线程 / 前台闸门 / [ChallengeResponder.abort] 都可能调用：取消这一问。 */
    fun cancel() {
        mailbox.offer(Cancelled)
    }

    /**
     * Reader 线程调用，阻塞到有答案 / 取消 / 超时。
     *
     * 返回 null 表示取消或超时——抛不抛异常是调用方（[AuthChallenges.ask]）的决定，这一层只管等。
     */
    fun await(timeoutMs: Long): String? =
        when (val v = mailbox.poll(timeoutMs, TimeUnit.MILLISECONDS)) {
            null, Cancelled -> null
            else -> v as String
        }

    /** 用对象身份而不是字符串哨兵：字符串再怎么挑也可能撞上用户真输入的内容。 */
    private object Cancelled
}

/** 用户取消了追问、等超了、或 app 不在前台。继承 [UserAuthException] 才能被 sshj 的异常表接住并安全投递。 */
class ChallengeCancelledException : UserAuthException("Authentication challenge was cancelled")

/**
 * 认证追问中枢，挂在 [net.lighttools.lightmux.LightmuxApp] 上（同 [KnownHosts] 的注入风格）。
 *
 * 现有代码没有"暂停等 UI 输入"的原语——host key 变更走的是"整次连接失败→用户点信任→重新连接"，
 * 那条路对 OTP 不适用：验证码有效期短，重连还要重走一遍 TCP+KEX。这里改用阻塞队列，
 * **不用 `CompletableDeferred` + `runBlocking`**：Reader 线程被 `interrupt()` 时 `runBlocking`
 * 抛出的是 `InterruptedException`，不是 [UserAuthException]，会穿透 sshj 的异常表打死整条连接。
 */
class AuthChallenges(app: Application) {

    private val _pending = MutableStateFlow<List<AuthChallenge>>(emptyList())
    val pending = _pending.asStateFlow()

    /**
     * 认证过程中真的问过验证码的主机 id。进程级、不落盘——不需要配置项、UI 开关或存储迁移，
     * 见 [ExecPool] 的硬闸门。
     */
    private val mfaHosts = ConcurrentHashMap.newKeySet<String>()

    fun isMfaHost(hostId: String): Boolean = hostId in mfaHosts

    /**
     * 有 resumed 的 Activity 才算"人在"。用计数器而非布尔值：系统分屏之类同时有多个
     * resumed Activity 的场景也要算对。
     */
    private val resumedActivities = java.util.concurrent.atomic.AtomicInteger(0)

    init {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedActivities.incrementAndGet()
            }

            override fun onActivityPaused(activity: Activity) {
                resumedActivities.decrementAndGet()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /**
     * 阻塞到用户回答 / 取消 / 超时。Reader 线程调用，全程不持有任何锁。
     *
     * @throws ChallengeCancelledException 用户点了取消、app 不在前台、或等超了
     */
    fun ask(challenge: AuthChallenge): String {
        mfaHosts.add(challenge.hostId)

        // 人不在就不该有认证发生：TermSessionHandle 的自动重连、ForwardManager 的健康检查、
        // NetworkWatcher 的网络恢复都会在屏幕关着时发起重连，没有这道闸门会让一条 Reader 线程
        // 挂 60 秒，用户几分钟后打开 app 看到一个早已过期的提示。
        if (resumedActivities.get() <= 0) throw ChallengeCancelledException()

        _pending.update { it + challenge }
        try {
            return challenge.await(CHALLENGE_TIMEOUT_MS) ?: throw ChallengeCancelledException()
        } finally {
            _pending.update { it - challenge }
        }
    }

    companion object {
        /**
         * 单跳认证超时，见 [SshConnection] 对 `client.transport.timeoutMs` 的临时抬高。
         * OpenSSH `LoginGraceTime` 默认 120 秒，取 120 会和服务端踢连接撞在同一刻，退半步取 60。
         */
        const val CHALLENGE_TIMEOUT_MS = 60_000L
    }
}
