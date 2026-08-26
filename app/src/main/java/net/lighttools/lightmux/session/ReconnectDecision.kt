package net.lighttools.lightmux.session

import com.hierynomus.sshj.common.KeyDecryptionFailedException
import net.lighttools.lightmux.ssh.CredentialLostException
import net.lighttools.lightmux.ssh.HostKeyChangedException
import net.lighttools.lightmux.ssh.ProxyJumpException
import net.lighttools.lightmux.ssh.UnsupportedKeyFormatException
import net.lighttools.lightmux.tmux.Tmux
import net.schmizz.sshj.userauth.UserAuthException

/**
 * 断线之后要不要再试。
 *
 * 独立成纯函数是因为判错的代价不对称：该重试的判成终态，用户以为会话丢了；
 * 不该重试的判成重试，客户端会拿着错密码每隔几秒撞一次服务端，
 * 十几次之后 IP 就进了对端的 fail2ban——那是要人工去解封的。
 */
enum class ReconnectDecision {

    /** 值得再试：网络抖动、服务端临时不可达这类会自己好的问题。 */
    Retry,

    /** 终态：重试一百次结果也一样，只能等用户改配置。 */
    GiveUp;

    companion object {

        /** 和 [net.lighttools.lightmux.ui.terminal.ConnectionFailure] 一致：sshj 会层层包装，只看最外层必然误判。 */
        private const val MAX_CAUSE_DEPTH = 8

        /**
         * @param closedByUser 用户主动关掉的会话。它不是「断线」，重连等于用户关不掉会话
         * @param failure      本次传输的失败原因；为 null 表示远端 shell 自己正常退出了
         *                     （`exit`、tmux detach），这时重连只会开出一个用户没要的新 shell
         */
        fun of(closedByUser: Boolean, failure: Throwable?): ReconnectDecision {
            if (closedByUser || failure == null) return GiveUp
            var current: Throwable? = failure
            var depth = 0
            while (current != null && depth < MAX_CAUSE_DEPTH) {
                when (current) {
                    // 跳板链本身配坏了（引用的主机被删、绕成环）。跳板机只是连不上的话不算，
                    // 那和目标主机连不上一样值得重试，所以继续往下扒它真正的原因。
                    is ProxyJumpException -> if (current.broken) return GiveUp

                    // 密钥变更可能是中间人，必须由用户点头才继续，不能自己偷偷重连上去
                    is HostKeyChangedException,
                    // 密码错、私钥解不开、格式不认识：换凭据之前重试多少次都是同一个结果
                    is UserAuthException,
                    is KeyDecryptionFailedException,
                    is UnsupportedKeyFormatException,
                    // 密文永久不可解，退避多久再试都一样解不开
                    is CredentialLostException,
                    // ssh-agent 还没实现
                    is UnsupportedOperationException,
                    -> return GiveUp
                }
                current = current.cause.takeIf { it !== current }
                depth++
            }
            return Retry
        }
    }
}

/**
 * 重连时该发的登录命令。
 *
 * tmux 会话走**普通 attach，不带 `-D`**。手机上断线是家常便饭，每断一次就把电脑上那个
 * 客户端踢下去，等于「手机进地铁 = 桌面 tmux 被 detach」，代价远大于它想省的那点麻烦。
 * 踢别人这件事保留为主页上的显式动作（「断开其他客户端」），不由自动重连替用户决定。
 *
 * 代价是服务端那个尚未超时的僵尸客户端会多挂一会儿。tmux 3.1 起 `window-size` 默认
 * `latest`——尺寸跟最近活动的客户端走，新客户端一有输入就说了算，不会被僵尸压小；
 * 更老的 tmux 才会取最小值，那种情况下让用户自己去点「断开其他客户端」。
 *
 * 非 tmux 会话重连后就是一个全新的 shell，原来的 shell 状态在服务端已经随 PTY 一起没了。
 * 这是 SSH 的固有限制，**不假装能恢复**：原样重发登录命令，把用户送到同一个起点，仅此而已。
 */
fun reconnectLoginCommand(tmuxSession: String?, loginCommand: String?): String? =
    if (tmuxSession != null) Tmux.attachCommand(tmuxSession) else loginCommand
