package net.lighttools.lightmux

import net.lighttools.lightmux.forward.ForwardRules
import net.lighttools.lightmux.forward.ForwardSpec
import net.lighttools.lightmux.forward.PortError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 本地端口挑选与校验的单测。 */
class ForwardRulesTest {

    @Test
    fun `高位端口优先同号——用户只需要记一个数`() {
        assertEquals(3000, ForwardRules.suggestLocalPort(3000))
        assertEquals(8501, ForwardRules.suggestLocalPort(8501))
    }

    @Test
    fun `特权端口按习惯偏移 看得出出处`() {
        assertEquals(8080, ForwardRules.suggestLocalPort(80))
        assertEquals(8443, ForwardRules.suggestLocalPort(443))
        assertEquals(8022, ForwardRules.suggestLocalPort(22))
    }

    @Test
    fun `同号被占就另挑一个 且不会挑到已占用的`() {
        val taken = setOf(3000, 10000, 10001)

        val picked = ForwardRules.suggestLocalPort(3000, taken)

        assertTrue(picked !in taken)
        assertEquals(10002, picked)
    }

    @Test
    fun `建议出来的端口本身必须是合法的本地端口`() {
        // 特权端口加完偏移仍然要落在可绑范围里，别把 1 变成 8001 之外的什么东西
        listOf(1, 22, 80, 443, 1023, 1024, 65535).forEach { remote ->
            assertNull(ForwardRules.validateLocalPort(ForwardRules.suggestLocalPort(remote)))
        }
    }

    @Test
    fun `低于 1024 的本地端口被挡住——Android 上根本绑不了`() {
        assertEquals(PortError.Privileged, ForwardRules.validateLocalPort(80))
        assertEquals(PortError.Privileged, ForwardRules.validateLocalPort(1023))
        assertNull(ForwardRules.validateLocalPort(1024))
    }

    @Test
    fun `越界端口被挡住`() {
        assertEquals(PortError.OutOfRange, ForwardRules.validateLocalPort(0))
        assertEquals(PortError.OutOfRange, ForwardRules.validateLocalPort(65536))
        assertEquals(PortError.OutOfRange, ForwardRules.validateLocalPort(-1))
    }

    @Test
    fun `远端端口没有特权口限制——受限的只有手机这一侧`() {
        assertNull(ForwardRules.validateRemotePort(80))
        assertNull(ForwardRules.validateRemotePort(65535))
        assertEquals(PortError.OutOfRange, ForwardRules.validateRemotePort(0))
    }

    @Test
    fun `本地地址固定指向环回口`() {
        val spec = ForwardSpec(hostId = "h", remotePort = 3000, localPort = 3000)

        assertEquals("http://127.0.0.1:3000", spec.localUrl)
        assertEquals("localhost", spec.remoteHost)
    }

    /**
     * 环回口常量必须是 IPv4 字面量。
     *
     * 这条锁的是 0.1.31 那个 bug 的根：绑端口和拼 URL 用的地址一旦不是同一个协议栈，
     * 转发会「绑得上、状态显示转发中、浏览器却拒绝连接」——不抛异常，纯靠人肉排查。
     * 换成 IPv6 还会顺带拼出 `http://::1:3000` 这种缺方括号的废地址。
     */
    @Test
    fun `环回口常量是 IPv4 字面量`() {
        assertEquals("127.0.0.1", ForwardSpec.LOOPBACK)
        assertFalse("IPv6 字面量进 URL 得套方括号", ForwardSpec.LOOPBACK.contains(":"))
        assertTrue(ForwardSpec(hostId = "h", remotePort = 1, localPort = 8080).localUrl.contains(ForwardSpec.LOOPBACK))
    }
}
