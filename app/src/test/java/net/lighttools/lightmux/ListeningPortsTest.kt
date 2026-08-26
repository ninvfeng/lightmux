package net.lighttools.lightmux

import net.lighttools.lightmux.forward.ListeningPorts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 监听端口解析的单测。
 *
 * 样本取自真实机器上的 `ss -tlnp` / `netstat -tlnp`（含那一大截空白对齐），
 * 不做「整理成好看的两列」这种美化——解析器要活在真实输出里，而真实输出就是这么脏。
 */
class ListeningPortsTest {

    private val ssOutput = """
        State  Recv-Q Send-Q              Local Address:Port  Peer Address:PortProcess
        LISTEN 0      128                     127.0.0.1:36295      0.0.0.0:*    users:(("code-a5b5009513",pid=7522,fd=11))
        LISTEN 0      4096                    127.0.0.1:19090      0.0.0.0:*    users:(("mihomo",pid=1066437,fd=3))
        LISTEN 0      4096                   127.0.0.54:53         0.0.0.0:*    users:(("systemd-resolve",pid=957,fd=17))
        LISTEN 0      4096                 100.66.10.10:33064      0.0.0.0:*    users:(("tailscaled",pid=1300,fd=21))
        LISTEN 0      4096                      0.0.0.0:8883       0.0.0.0:*    users:(("docker-proxy",pid=4074,fd=8))
        LISTEN 0      4096                        [::1]:11434         [::]:*    users:(("ollama",pid=999,fd=3))
        LISTEN 0      511                             *:80                *:*    users:(("nginx",pid=456,fd=6))
    """.trimIndent()

    private val netstatOutput = """
        Active Internet connections (only servers)
        Proto Recv-Q Send-Q Local Address           Foreign Address         State       PID/Program name
        tcp        0      0 127.0.0.1:36295         0.0.0.0:*               LISTEN      7522/code-a5b500951
        tcp        0      0 127.0.0.1:19090         0.0.0.0:*               LISTEN      1066437/mihomo
        tcp        0      0 0.0.0.0:8883            0.0.0.0:*               LISTEN      4074/docker-proxy
        tcp6       0      0 :::80                   :::*                    LISTEN      456/nginx
        tcp6       0      0 ::1:11434               :::*                    LISTEN      -
    """.trimIndent()

    @Test
    fun `ss 输出解析出端口 地址与进程名`() {
        val ports = ListeningPorts.parse(ssOutput)

        assertEquals(7, ports.size)
        val redis = ports.first { it.port == 19090 }
        assertEquals("127.0.0.1", redis.address)
        assertEquals("mihomo", redis.process)
    }

    @Test
    fun `表头不会被当成一条记录`() {
        // 表头里有 `Local Address:Port` 这种带冒号的字段，靠「行内必须有 LISTEN」挡掉
        assertTrue(ListeningPorts.parse(ssOutput).none { it.address.contains("Address") })
        assertTrue(ListeningPorts.parse(netstatOutput).none { it.process == "Program" })
    }

    @Test
    fun `环回端口排在前面——它们才是非转发不可的那些`() {
        val ports = ListeningPorts.parse(ssOutput)
        val firstPublic = ports.indexOfFirst { !it.loopbackOnly }

        assertTrue(ports.take(firstPublic).all { it.loopbackOnly })
        // 环回段内部按端口号升序：53 < 11434 < 19090 < 36295
        assertEquals(listOf(53, 11434, 19090, 36295), ports.take(firstPublic).map { it.port })
    }

    @Test
    fun `IPv6 环回算环回 通配不算`() {
        val ports = ListeningPorts.parse(ssOutput).associateBy { it.port }

        assertTrue(ports.getValue(11434).loopbackOnly)
        assertEquals("::1", ports.getValue(11434).address)
        assertTrue(!ports.getValue(80).loopbackOnly)
        // 127.0.0.54 也在环回段里，不能只认 127.0.0.1
        assertTrue(ports.getValue(53).loopbackOnly)
    }

    @Test
    fun `对端地址不会被认成本地地址`() {
        // 每行都有个 `0.0.0.0:*`，端口位不是数字，必须被跳过
        assertTrue(ListeningPorts.parse(ssOutput).none { it.port == 0 })
        assertEquals(5, ListeningPorts.parse(netstatOutput).size)
    }

    @Test
    fun `netstat 输出同样能解析`() {
        val ports = ListeningPorts.parse(netstatOutput).associateBy { it.port }

        assertEquals("mihomo", ports.getValue(19090).process)
        assertEquals("nginx", ports.getValue(80).process)
        assertEquals("::", ports.getValue(80).address)
        // netstat 的 IPv6 环回写成 `::1:11434`，端口要从最后一个冒号切
        assertTrue(ports.getValue(11434).loopbackOnly)
    }

    @Test
    fun `拿不到进程名不影响解析`() {
        // 非 root 时 netstat 那一列是个 `-`
        assertNull(ListeningPorts.parse(netstatOutput).first { it.port == 11434 }.process)
    }

    @Test
    fun `同一端口的 IPv4 与 IPv6 只留一条 且取更宽的绑定`() {
        val dual = """
            LISTEN 0 128 127.0.0.1:3000 0.0.0.0:*
            LISTEN 0 128     [::]:3000     [::]:*
        """.trimIndent()

        val ports = ListeningPorts.parse(dual)

        assertEquals(1, ports.size)
        // 既然有一个绑到了通配地址，这个端口就不是「非转发不可」的
        assertTrue(!ports.single().loopbackOnly)
    }

    @Test
    fun `两个工具都没有时返回空列表`() {
        assertTrue(ListeningPorts.parse("").isEmpty())
        assertTrue(ListeningPorts.parse("sh: 1: ss: not found").isEmpty())
    }
}
