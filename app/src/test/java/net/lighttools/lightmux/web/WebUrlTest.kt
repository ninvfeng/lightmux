package net.lighttools.lightmux.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 地址栏输入的规范化。
 *
 * 这一段的错法都很安静：补错协议、把端口号当成主机名，用户看到的只是「打不开」，
 * 而地址栏里那行字看着完全正常。
 */
class WebUrlTest {

    @Test
    fun `纯数字当成本机端口`() {
        assertEquals("http://127.0.0.1:3000", WebUrl.normalize("3000"))
        assertEquals("http://127.0.0.1:8080", WebUrl.normalize(" 8080 "))
    }

    @Test
    fun `越界的数字不当端口`() {
        // 65536 不是端口，但也不该被补成 http://65536 —— 那是个能发出去的请求，只会超时
        assertNull(WebUrl.normalize("65536"))
        assertNull(WebUrl.normalize("0"))
    }

    @Test
    fun `没写协议补 http`() {
        assertEquals("http://127.0.0.1:3000/admin", WebUrl.normalize("127.0.0.1:3000/admin"))
        assertEquals("http://localhost:5173", WebUrl.normalize("localhost:5173"))
    }

    @Test
    fun `写了协议原样保留`() {
        assertEquals("https://example.com", WebUrl.normalize("https://example.com"))
        assertEquals("http://127.0.0.1:9000", WebUrl.normalize("http://127.0.0.1:9000"))
    }

    @Test
    fun `主机名后面的冒号是端口不是协议`() {
        assertEquals("http://myhost:8080", WebUrl.normalize("myhost:8080"))
    }

    @Test
    fun `空输入没有目标`() {
        assertNull(WebUrl.normalize(""))
        assertNull(WebUrl.normalize("   "))
    }

    @Test
    fun `显示时砍掉 http 前缀与根路径的斜杠`() {
        assertEquals("127.0.0.1:3000", WebUrl.display("http://127.0.0.1:3000/"))
        assertEquals("127.0.0.1:3000", WebUrl.display("http://127.0.0.1:3000"))
    }

    @Test
    fun `路径末尾的斜杠留着`() {
        // 根路径的斜杠没有信息量，路径里的有：/docs/ 和 /docs 在不少服务上不是一个东西
        assertEquals("127.0.0.1:3000/docs/", WebUrl.display("http://127.0.0.1:3000/docs/"))
    }

    @Test
    fun `https 前缀不砍`() {
        // 那把锁是安全信息，砍掉等于把「这条连接是加密的」抹成「不知道」
        assertEquals("https://example.com/", WebUrl.display("https://example.com/"))
    }
}
