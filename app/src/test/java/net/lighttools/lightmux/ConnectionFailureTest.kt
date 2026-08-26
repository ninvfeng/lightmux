package net.lighttools.lightmux

import net.lighttools.lightmux.ssh.CredentialLostException
import net.lighttools.lightmux.ssh.HostKeyChangedException
import net.lighttools.lightmux.ssh.ProxyJumpException
import net.lighttools.lightmux.ui.terminal.ConnectionFailure
import net.schmizz.sshj.userauth.UserAuthException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class ConnectionFailureTest {

    @Test
    fun `没有异常就没有失败`() {
        assertNull(ConnectionFailure.of(null))
    }

    @Test
    fun `主机密钥变更带出两个指纹`() {
        val e = HostKeyChangedException("root@a:22", "SHA256:old", "SHA256:new")
        assertEquals(
            ConnectionFailure.HostKeyChanged("root@a:22", "SHA256:old", "SHA256:new"),
            ConnectionFailure.of(e),
        )
    }

    @Test
    fun `认证失败被 sshj 包了几层也认得出来`() {
        val wrapped = IOException("transport failed", IOException("kex", UserAuthException("bad password")))
        assertEquals(ConnectionFailure.AuthFailed, ConnectionFailure.of(wrapped))
    }

    @Test
    fun `凭据解不开不能混进认证失败`() {
        assertEquals(ConnectionFailure.CredentialLost, ConnectionFailure.of(CredentialLostException("h1")))
    }

    @Test
    fun `ssh-agent 暂不支持`() {
        assertEquals(
            ConnectionFailure.AgentUnsupported,
            ConnectionFailure.of(UnsupportedOperationException("no agent")),
        )
    }

    @Test
    fun `跳板机的失败带出是哪一台，内层原因原样保留`() {
        val e = ProxyJumpException("h-bastion", "bastion", UserAuthException("bad password"))
        assertEquals(
            ConnectionFailure.ProxyJumpFailed("h-bastion", "bastion", ConnectionFailure.AuthFailed),
            ConnectionFailure.of(e),
        )
    }

    @Test
    fun `跳板链坏了没有主机 id，用户该去改的是当前主机`() {
        val e = ProxyJumpException(jumpHostId = null, jumpName = null)
        val failure = ConnectionFailure.of(e) as ConnectionFailure.ProxyJumpFailed
        assertNull(failure.jumpHostId)
        assertNull(failure.jumpName)
    }

    @Test
    fun `跳板机的密钥变了要直接透出去——那个对话框是唯一的出路`() {
        val changed = HostKeyChangedException("root@bastion:22", "SHA256:old", "SHA256:new")
        assertEquals(
            ConnectionFailure.HostKeyChanged("root@bastion:22", "SHA256:old", "SHA256:new"),
            ConnectionFailure.of(ProxyJumpException("h-bastion", "bastion", changed)),
        )
    }

    @Test
    fun `认不出来的归 Other 并保留原始信息`() {
        assertEquals(ConnectionFailure.Other("connection refused"), ConnectionFailure.of(IOException("connection refused")))
    }

    @Test
    fun `cause 链再长也只扒有限层，扒不到就归 Other`() {
        var deep: Throwable = UserAuthException("bad password")
        repeat(12) { deep = IOException("wrap $it", deep) }
        assertEquals(ConnectionFailure.Other("wrap 11"), ConnectionFailure.of(deep))
    }

    @Test
    fun `消息为空时用异常类名兜底，不给用户一条空错误`() {
        assertEquals(ConnectionFailure.Other("IOException"), ConnectionFailure.of(IOException()))
    }
}
