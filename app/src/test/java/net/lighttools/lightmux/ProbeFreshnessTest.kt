package net.lighttools.lightmux

import net.lighttools.lightmux.tmux.ProbeFreshness
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeFreshnessTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `从没探测过一律要探`() {
        assertTrue(ProbeFreshness.shouldProbe(loading = false, probedAt = 0L, now = now))
        // 冷启动缓存缺失时 now 也可能很小，不该因此就跳过
        assertTrue(ProbeFreshness.shouldProbe(loading = false, probedAt = 0L, now = 1L))
    }

    @Test
    fun `正在探测时不重复发`() {
        // 快照再老也不发：在途的那次回来就会覆盖它
        assertFalse(ProbeFreshness.shouldProbe(loading = true, probedAt = now - 86_400_000L, now = now))
        assertFalse(ProbeFreshness.shouldProbe(loading = true, probedAt = 0L, now = now))
    }

    @Test
    fun `窗口内的新快照不重探`() {
        assertFalse(ProbeFreshness.shouldProbe(loading = false, probedAt = now - 1_000L, now = now))
        assertFalse(ProbeFreshness.shouldProbe(loading = false, probedAt = now - 14_999L, now = now))
    }

    @Test
    fun `窗口外的旧快照要重探`() {
        // 15s 整是边界，属于「该探」那一侧
        assertTrue(ProbeFreshness.shouldProbe(loading = false, probedAt = now - 15_000L, now = now))
        assertTrue(ProbeFreshness.shouldProbe(loading = false, probedAt = now - 60_000L, now = now))
    }

    @Test
    fun `时钟被往回调过也要重探`() {
        // now < probedAt：自动校时或跨时区。不当成「未来的快照」永久跳过
        assertTrue(ProbeFreshness.shouldProbe(loading = false, probedAt = now + 3_600_000L, now = now))
    }
}
