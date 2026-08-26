package net.lighttools.lightmux

import net.lighttools.lightmux.forward.ForwardSpec
import net.lighttools.lightmux.forward.ListeningPort
import net.lighttools.lightmux.forward.PortFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortFilterTest {

    private val redis = ListeningPort(port = 6379, address = "127.0.0.1", process = "redis-server")
    private val nginx = ListeningPort(port = 80, address = "0.0.0.0", process = null)
    private val spec = ForwardSpec(hostId = "h", remotePort = 3000, localPort = 8081, label = "vite")

    @Test
    fun `空查询放行全部`() {
        assertTrue(PortFilter.matches("", redis))
        assertTrue(PortFilter.matches("   ", redis))
        assertTrue(PortFilter.matches("", spec))
    }

    @Test
    fun `按端口号匹配`() {
        assertTrue(PortFilter.matches("6379", redis))
        assertFalse(PortFilter.matches("6378", redis))
    }

    @Test
    fun `端口号打一半也算 用户常只记得后半截`() {
        assertTrue(PortFilter.matches("379", redis))
        assertTrue(PortFilter.matches("63", redis))
    }

    @Test
    fun `按进程名匹配 且不分大小写`() {
        assertTrue(PortFilter.matches("redis", redis))
        assertTrue(PortFilter.matches("REDIS", redis))
        assertFalse(PortFilter.matches("postgres", redis))
    }

    @Test
    fun `进程名探不到时不误伤 只是少一个可匹配的字段`() {
        assertTrue(PortFilter.matches("80", nginx))
        assertFalse(PortFilter.matches("nginx", nginx))
    }

    @Test
    fun `按监听地址匹配`() {
        assertTrue(PortFilter.matches("127.0.0.1", redis))
        assertTrue(PortFilter.matches("0.0.0.0", nginx))
    }

    @Test
    fun `查询词两端的空白不算数`() {
        assertTrue(PortFilter.matches("  redis  ", redis))
    }

    @Test
    fun `转发行按两个端口号 备注 远端主机匹配`() {
        assertTrue(PortFilter.matches("3000", spec))
        assertTrue(PortFilter.matches("8081", spec))
        assertTrue(PortFilter.matches("vite", spec))
        assertTrue(PortFilter.matches("localhost", spec))
        assertFalse(PortFilter.matches("9000", spec))
    }

    @Test
    fun `没有备注的转发行不因此崩掉`() {
        val bare = ForwardSpec(hostId = "h", remotePort = 5432, localPort = 5432)
        assertTrue(PortFilter.matches("5432", bare))
        assertFalse(PortFilter.matches("pg", bare))
    }
}
