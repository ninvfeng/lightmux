package net.lighttools.lightmux

import com.hierynomus.sshj.common.KeyDecryptionFailedException
import net.lighttools.lightmux.session.ReconnectDecision
import net.lighttools.lightmux.session.reconnectLoginCommand
import net.lighttools.lightmux.ssh.ChallengeCancelledException
import net.lighttools.lightmux.ssh.CredentialLostException
import net.lighttools.lightmux.ssh.HostKeyChangedException
import net.lighttools.lightmux.ssh.ProxyJumpException
import net.lighttools.lightmux.ssh.UnsupportedKeyFormatException
import net.lighttools.lightmux.tmux.Tmux
import net.schmizz.sshj.userauth.UserAuthException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class ReconnectDecisionTest {

    @Test
    fun `用户自己关掉的会话不重连`() {
        assertEquals(
            ReconnectDecision.GiveUp,
            ReconnectDecision.of(closedByUser = true, failure = IOException("connection reset")),
        )
    }

    @Test
    fun `远端正常退出不重连`() {
        // failure 为 null = shell 自己 exit 了 / tmux detach 了，重连只会开出一个用户没要的新 shell
        assertEquals(ReconnectDecision.GiveUp, ReconnectDecision.of(closedByUser = false, failure = null))
    }

    @Test
    fun `网络类失败要重连`() {
        assertEquals(
            ReconnectDecision.Retry,
            ReconnectDecision.of(false, SocketTimeoutException("connect timed out")),
        )
        assertEquals(ReconnectDecision.Retry, ReconnectDecision.of(false, IOException("connection reset")))
    }

    @Test
    fun `认证失败是终态，不能拿错密码去撞 fail2ban`() {
        assertEquals(ReconnectDecision.GiveUp, ReconnectDecision.of(false, UserAuthException("bad password")))
    }

    @Test
    fun `认证异常被 sshj 包了几层照样认得出来`() {
        val wrapped = IOException("transport failed", IOException("kex", UserAuthException("bad password")))
        assertEquals(ReconnectDecision.GiveUp, ReconnectDecision.of(false, wrapped))
    }

    @Test
    fun `主机密钥变更是终态，必须由用户点头`() {
        val e = HostKeyChangedException("root@a:22", "SHA256:old", "SHA256:new")
        assertEquals(ReconnectDecision.GiveUp, ReconnectDecision.of(false, e))
    }

    @Test
    fun `私钥解不开或格式不认识都是终态`() {
        // sshj 0.40 去掉了无参构造（连带砍了对 bcpkix EncryptionException 的依赖），改成显式传消息
        assertEquals(
            ReconnectDecision.GiveUp,
            ReconnectDecision.of(false, KeyDecryptionFailedException(KeyDecryptionFailedException.MESSAGE)),
        )
        assertEquals(ReconnectDecision.GiveUp, ReconnectDecision.of(false, UnsupportedKeyFormatException("PuTTY")))
    }

    @Test
    fun `凭据解不开是终态，退避多久也解不开`() {
        assertEquals(ReconnectDecision.GiveUp, ReconnectDecision.of(false, CredentialLostException("h1")))
    }

    @Test
    fun `验证码追问被取消是终态，不能自动再弹一次同样的对话框`() {
        assertEquals(ReconnectDecision.GiveUp, ReconnectDecision.of(false, ChallengeCancelledException()))
    }

    @Test
    fun `ssh-agent 未实现是终态`() {
        assertEquals(ReconnectDecision.GiveUp, ReconnectDecision.of(false, UnsupportedOperationException()))
    }

    @Test
    fun `跳板链配坏了是终态，改配置之前重试没有意义`() {
        assertEquals(
            ReconnectDecision.GiveUp,
            ReconnectDecision.of(false, ProxyJumpException(jumpHostId = null, jumpName = null)),
        )
    }

    @Test
    fun `跳板机只是连不上，仍然值得重试`() {
        val e = ProxyJumpException("h-bastion", "bastion", SocketTimeoutException("timed out"))
        assertEquals(ReconnectDecision.Retry, ReconnectDecision.of(false, e))
    }

    @Test
    fun `跳板机的密码错了照样是终态`() {
        val e = ProxyJumpException("h-bastion", "bastion", UserAuthException("bad password"))
        assertEquals(ReconnectDecision.GiveUp, ReconnectDecision.of(false, e))
    }

    @Test
    fun `cause 链太深就扒不动了，按可重试处理`() {
        // 宁可多试几次，也不能把一次真实的网络抖动判成终态——那等于让会话白白丢掉
        var deep: Throwable = UserAuthException("bad password")
        repeat(12) { deep = IOException("wrap $it", deep) }
        assertEquals(ReconnectDecision.Retry, ReconnectDecision.of(false, deep))
    }

    @Test
    fun `cause 成环不会把判定卡死`() {
        val a = IOException("a")
        val b = IOException("b")
        a.initCause(b)
        b.initCause(a)
        assertEquals(ReconnectDecision.Retry, ReconnectDecision.of(false, a))
    }

    @Test
    fun `tmux 会话重连时不带 -D，绝不踢掉别的客户端`() {
        // 手机断线是常态，自动重连要是带 -D，用户电脑上的 tmux 每断一次就被 detach 一次
        val cmd = reconnectLoginCommand(tmuxSession = "dev", loginCommand = "tmux new-session -A -s 'dev'")
        assertFalse(cmd!!.contains("-D"))
        assertEquals(Tmux.attachCommand("dev"), cmd)
    }

    @Test
    fun `从窗口进来的会话重连时回到纯 attach`() {
        // 进来时那条命令带着 select-window，重连不该再切一次窗口：用户可能已经手动换过窗口了
        val cmd = reconnectLoginCommand("dev", Tmux.attachWindowCommand("dev", "@3", "1234"))
        assertFalse(cmd!!.contains("select-window"))
        assertEquals(Tmux.attachCommand("dev"), cmd)
    }

    @Test
    fun `会话名里的引号在重连命令里仍被转义`() {
        assertTrue(reconnectLoginCommand("it's mine", null)!!.endsWith("tmux new-session -A -s 'it'\\''s mine'"))
    }

    @Test
    fun `非 tmux 会话原样重发登录命令`() {
        assertEquals("cd /srv && ls", reconnectLoginCommand(tmuxSession = null, loginCommand = "cd /srv && ls"))
        assertEquals(null, reconnectLoginCommand(tmuxSession = null, loginCommand = null))
    }
}
