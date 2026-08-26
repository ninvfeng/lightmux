package net.lighttools.lightmux

import net.lighttools.lightmux.data.AuthMethod
import net.lighttools.lightmux.data.Host
import net.lighttools.lightmux.data.HostRoute
import org.junit.Assert.assertEquals
import org.junit.Test

class HostRouteTest {

    private fun host(id: String, jump: String? = null) = Host(
        id = id,
        name = id,
        hostname = "$id.example.com",
        username = "root",
        auth = AuthMethod.Password("x"),
        proxyJumpId = jump,
    )

    @Test
    fun `没配跳板就是直连`() {
        val a = host("a")
        assertEquals(HostRoute.Route.Direct, HostRoute.resolve(a, listOf(a)))
    }

    @Test
    fun `单跳`() {
        val bastion = host("bastion")
        val target = host("target", jump = "bastion")
        assertEquals(
            HostRoute.Route.Via(listOf(bastion)),
            HostRoute.resolve(target, listOf(bastion, target)),
        )
    }

    @Test
    fun `多跳按连接顺序排列——先连最外面那台`() {
        val outer = host("outer")
        val inner = host("inner", jump = "outer")
        val target = host("target", jump = "inner")
        val route = HostRoute.resolve(target, listOf(target, inner, outer)) as HostRoute.Route.Via
        assertEquals(listOf("outer", "inner"), route.jumps.map { it.id })
    }

    @Test
    fun `跳板机被删了要说出来，不能悄悄降级成直连`() {
        val target = host("target", jump = "gone")
        assertEquals(HostRoute.Route.Missing("gone"), HostRoute.resolve(target, listOf(target)))
    }

    @Test
    fun `两台互为跳板不会把连接线程转死`() {
        val a = host("a", jump = "b")
        val b = host("b", jump = "a")
        assertEquals(HostRoute.Route.Cycle, HostRoute.resolve(a, listOf(a, b)))
    }

    @Test
    fun `自己当自己的跳板也算环`() {
        val a = host("a", jump = "a")
        assertEquals(HostRoute.Route.Cycle, HostRoute.resolve(a, listOf(a)))
    }

    @Test
    fun `链太长直接拒绝`() {
        // target -> h5 -> h4 -> h3 -> h2 -> h1 -> h0，跳板数 6 > MAX_HOPS
        val chain = (0..5).map { host("h$it", jump = if (it == 0) null else "h${it - 1}") }
        val target = host("target", jump = "h5")
        assertEquals(HostRoute.Route.TooDeep, HostRoute.resolve(target, chain + target))
    }

    @Test
    fun `刚好到上限仍然可用`() {
        val chain = (0..4).map { host("h$it", jump = if (it == 0) null else "h${it - 1}") }
        val target = host("target", jump = "h4")
        val route = HostRoute.resolve(target, chain + target) as HostRoute.Route.Via
        assertEquals(HostRoute.MAX_HOPS, route.jumps.size)
    }

    @Test
    fun `候选里没有自己`() {
        val a = host("a")
        val b = host("b")
        assertEquals(listOf("b"), HostRoute.candidates("a", listOf(a, b)).map { it.id })
    }

    @Test
    fun `候选里没有会绕成环的主机`() {
        // b 已经以 a 为跳板，那么 a 就不能再选 b——否则 a 和 b 互相指
        val a = host("a")
        val b = host("b", jump = "a")
        val c = host("c")
        assertEquals(listOf("c"), HostRoute.candidates("a", listOf(a, b, c)).map { it.id })
    }

    @Test
    fun `隔着一跳绕回来的也排掉`() {
        val a = host("a")
        val b = host("b", jump = "a")
        val c = host("c", jump = "b")
        assertEquals(emptyList<String>(), HostRoute.candidates("a", listOf(a, b, c)).map { it.id })
    }

    @Test
    fun `新建主机时谁都能当跳板`() {
        val a = host("a")
        val b = host("b", jump = "a")
        assertEquals(listOf("a", "b"), HostRoute.candidates(null, listOf(a, b)).map { it.id })
    }

    @Test
    fun `表里已经有环时，候选计算也得能停下来`() {
        // a 和 b 互指（存量数据可能是这样），给 c 算候选时不能在这个环里绕不出来
        val a = host("a", jump = "b")
        val b = host("b", jump = "a")
        val c = host("c")
        assertEquals(listOf("a", "b"), HostRoute.candidates("c", listOf(a, b, c)).map { it.id })
    }
}
