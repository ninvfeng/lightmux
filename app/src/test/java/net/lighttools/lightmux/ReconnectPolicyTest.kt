package net.lighttools.lightmux

import net.lighttools.lightmux.session.ReconnectPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectPolicyTest {

    @Test
    fun `默认退避序列为 1s 2s 4s 8s 然后封顶 15s`() {
        val policy = ReconnectPolicy()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L), (1..5).map { policy.nextDelayMs() })
    }

    @Test
    fun `封顶后不再增长`() {
        val policy = ReconnectPolicy()
        repeat(4) { policy.nextDelayMs() }
        repeat(50) { assertEquals(15_000L, policy.nextDelayMs()) }
        assertEquals(54, policy.attempts)
    }

    @Test
    fun `reset 后重新从 base 开始`() {
        val policy = ReconnectPolicy()
        repeat(3) { policy.nextDelayMs() }
        assertEquals(3, policy.attempts)

        policy.reset()
        assertEquals(0, policy.attempts)
        assertEquals(1_000L, policy.nextDelayMs())
        assertEquals(2_000L, policy.nextDelayMs())
    }

    @Test
    fun `网络恢复时下一次立即重试，之后回到正常退避`() {
        val policy = ReconnectPolicy()
        repeat(5) { policy.nextDelayMs() } // 已退到封顶

        policy.resetForNetworkRestored()
        assertEquals(0, policy.attempts)
        assertEquals(0L, policy.nextDelayMs())
        // 这一枪不计入 attempts：打空了也该从 1s 重新退避
        assertEquals(0, policy.attempts)
        assertEquals(1_000L, policy.nextDelayMs())
        assertEquals(2_000L, policy.nextDelayMs())
    }

    @Test
    fun `reset 会清掉未消费的立即重试标记`() {
        val policy = ReconnectPolicy()
        policy.resetForNetworkRestored()
        policy.reset()
        assertEquals(1_000L, policy.nextDelayMs())
    }

    @Test
    fun `自定义 base 与封顶生效`() {
        val policy = ReconnectPolicy(baseDelayMs = 500, maxDelayMs = 3_000)
        assertEquals(listOf(500L, 1_000L, 2_000L, 3_000L, 3_000L), (1..5).map { policy.nextDelayMs() })
    }

    @Test
    fun `极多次尝试不会因左移溢出变成负数或归零`() {
        val policy = ReconnectPolicy()
        repeat(200) { policy.nextDelayMs() }
        assertEquals(15_000L, policy.nextDelayMs())
    }
}
