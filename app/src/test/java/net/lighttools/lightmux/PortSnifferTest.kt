package net.lighttools.lightmux

import net.lighttools.lightmux.forward.PortSniffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端输出嗅探的单测。
 *
 * 样本照抄真实工具打出来的那几行（含箭头、方括号、行尾斜杠），不做美化——
 * 这个解析器唯一的价值就是活在真实输出里。误报比漏报更贵：提示条一旦开始乱弹，
 * 用户学会的第一件事就是无视它。
 */
class PortSnifferTest {

    @Test
    fun `认出常见 dev server 的地址`() {
        val hints = PortSniffer.scan("  ➜  Local:   http://localhost:5173/")
        assertEquals(1, hints.size)
        assertEquals(5173, hints[0].port)
        assertEquals("localhost", hints[0].target)
        assertEquals("/", hints[0].path)
    }

    @Test
    fun `环回 通配与 IPv6 都归一成 localhost`() {
        listOf("http://127.0.0.1:3000", "http://0.0.0.0:8000", "http://[::1]:11434", "http://[::]:9000")
            .forEach { line ->
                val hint = PortSniffer.scan(line).single()
                assertEquals(line, "localhost", hint.target)
            }
    }

    @Test
    fun `内网地址原样留着 那台主机就是跳板`() {
        val hint = PortSniffer.scan("Network: http://192.168.1.5:5173/").single()
        assertEquals("192.168.1.5", hint.target)
        assertEquals(5173, hint.port)
    }

    @Test
    fun `同一个端口只提一条 且优先环回`() {
        val output = """
            ➜  Local:   http://localhost:5173/
            ➜  Network: http://192.168.1.5:5173/
        """.trimIndent()
        val hint = PortSniffer.scan(output).single()
        assertEquals("localhost", hint.target)
    }

    @Test
    fun `Network 先出现时也认得出 环回仍然盖掉它`() {
        val output = """
            Network: http://192.168.1.5:5173/
            Local:   http://127.0.0.1:5173/
        """.trimIndent()
        assertEquals("localhost", PortSniffer.scan(output).single().target)
    }

    @Test
    fun `带查询串的地址要留住路径 不然 Jupyter 开出来是登录页`() {
        val hint = PortSniffer.scan(
            "http://127.0.0.1:8888/lab?token=8f3a9c1d"
        ).single()
        assertEquals("/lab?token=8f3a9c1d", hint.path)
    }

    @Test
    fun `句末的标点不算路径的一部分`() {
        assertEquals("/api", PortSniffer.scan("see http://localhost:3000/api.").single().path)
        assertEquals("", PortSniffer.scan("open http://localhost:3000.").single().path)
    }

    @Test
    fun `非 http 协议照样认 数据库端口也要转`() {
        val hint = PortSniffer.scan("redis://127.0.0.1:6379").single()
        assertEquals(6379, hint.port)
        assertEquals("localhost", hint.target)
    }

    @Test
    fun `裸的地址端口对也认`() {
        val hint = PortSniffer.scan("Server listening at 127.0.0.1:8080").single()
        assertEquals(8080, hint.port)
    }

    @Test
    fun `折行的 URL 接回来之后仍是一条`() {
        // 屏幕文本已经由 getSelectedText 拼过行了，这里验的是拼完不会多出别的东西
        assertEquals(1, PortSniffer.scan("http://localhost:3000/very/long/path").size)
    }

    @Test
    fun `时间戳不是地址`() {
        assertTrue(PortSniffer.scan("2026-08-21 12:30:45 INFO started").isEmpty())
    }

    @Test
    fun `域名不提示 转发它没有意义`() {
        assertTrue(PortSniffer.scan("cloning from https://github.com:443/a/b").isEmpty())
    }

    @Test
    fun `八位组越界的不是地址`() {
        assertTrue(PortSniffer.scan("999.1.1.1:8080").isEmpty())
    }

    @Test
    fun `端口越界的不认 也不能截出半截来`() {
        assertTrue(PortSniffer.scan("127.0.0.1:99999").isEmpty())
        assertTrue(PortSniffer.scan("127.0.0.1:123456").isEmpty())
        assertTrue(PortSniffer.scan("127.0.0.1:0").isEmpty())
    }

    @Test
    fun `更长的词里截出来的半截不算`() {
        assertTrue(PortSniffer.scan("version 1.2.3.4.5:80 released").isEmpty())
        assertTrue(PortSniffer.scan("ssh admin@127.0.0.1:22").isEmpty())
    }

    @Test
    fun `一屏刷出一堆地址时有上限`() {
        val flood = (3000..3020).joinToString("\n") { "http://127.0.0.1:$it" }
        assertTrue(PortSniffer.scan(flood).size <= 6)
    }

    @Test
    fun `空输入不炸`() {
        assertTrue(PortSniffer.scan("").isEmpty())
        assertNull(PortSniffer.scan("no address here").firstOrNull())
    }
}
