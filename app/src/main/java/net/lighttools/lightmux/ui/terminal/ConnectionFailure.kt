package net.lighttools.lightmux.ui.terminal

import net.lighttools.lightmux.ssh.CredentialLostException
import net.lighttools.lightmux.ssh.ExecTimeoutException
import net.lighttools.lightmux.ssh.HostKeyChangedException
import net.lighttools.lightmux.ssh.ProxyJumpException
import net.schmizz.sshj.userauth.UserAuthException

/**
 * 连接失败的分类结果。
 *
 * UI 必须按**异常类型**分流而不是按消息文本：主机密钥变更要阻断式弹窗（可能是中间人），
 * 认证失败该把人送去改凭据，其余的给个重试就行。把分类抽成纯函数是为了能单测——
 * 这段逻辑一旦错判，用户要么被无谓吓一跳，要么在真被中间人时只看到一条不起眼的错误条。
 */
sealed interface ConnectionFailure {

    /** 已记录的主机公钥和这次拿到的不一致。**不许静默接受**。 */
    data class HostKeyChanged(
        val endpoint: String,
        val expected: String,
        val actual: String,
    ) : ConnectionFailure

    data object AuthFailed : ConnectionFailure

    /**
     * 存的凭据已经解不开了。
     *
     * 和 [AuthFailed] 分开，是因为出路完全不同：认证失败可能是密码打错了、也可能是用户名不对，
     * 而这里密文已经永久不可解，检查任何东西都没用，只能重新填一次。
     */
    data object CredentialLost : ConnectionFailure

    data object AgentUnsupported : ConnectionFailure

    data object ExecTimeout : ConnectionFailure

    /**
     * 栽在跳板机那一跳上（见 [net.lighttools.lightmux.ssh.ProxyJumpException]）。
     *
     * 带上 [reason] 而不是笼统一句「跳板机连不上」：跳板机密码错和跳板机关机了，
     * 用户的下一步完全不同，而这正是本类存在的理由。
     *
     * @param jumpHostId 出问题的跳板机 id，用户点一下就能直达那台的编辑页；
     *                   为 null 表示跳板链本身配坏了（引用被删、成环、层数过多）
     */
    data class ProxyJumpFailed(
        val jumpHostId: String?,
        val jumpName: String?,
        val reason: ConnectionFailure,
    ) : ConnectionFailure

    data class Other(val message: String?) : ConnectionFailure

    companion object {

        /** 最多往下扒 [MAX_CAUSE_DEPTH] 层。sshj 会把认证异常包进 TransportException，只看最外层必然误判。 */
        private const val MAX_CAUSE_DEPTH = 8

        fun of(error: Throwable?): ConnectionFailure? {
            if (error == null) return null
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth < MAX_CAUSE_DEPTH) {
                when (current) {
                    is HostKeyChangedException ->
                        return HostKeyChanged(current.endpoint, current.expected, current.actual)

                    // 跳板机的密钥变了也得走那个阻断式对话框：异常里带的 endpoint 就是跳板机的，
                    // 用户信任新指纹之后重连即可。包成 ProxyJumpFailed 反而把唯一的出路藏起来。
                    is ProxyJumpException -> {
                        val inner = of(current.cause) ?: Other(null)
                        return inner as? HostKeyChanged
                            ?: ProxyJumpFailed(current.jumpHostId, current.jumpName, inner)
                    }

                    is CredentialLostException -> return CredentialLost
                    is UserAuthException -> return AuthFailed
                    is ExecTimeoutException -> return ExecTimeout
                    is UnsupportedOperationException -> return AgentUnsupported
                }
                current = current.cause.takeIf { it !== current }
                depth++
            }
            return Other(error.message ?: error.javaClass.simpleName)
        }
    }
}
