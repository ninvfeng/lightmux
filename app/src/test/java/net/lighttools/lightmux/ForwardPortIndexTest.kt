package net.lighttools.lightmux

import net.lighttools.lightmux.forward.ForwardSpec
import net.lighttools.lightmux.forward.ForwardStatus
import net.lighttools.lightmux.ui.forward.ForwardRow
import net.lighttools.lightmux.ui.forward.takenRemotePorts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「已添加」索引的单测。
 *
 * 这层索引是拿来替掉每个端口行一次 `rows.any { it.spec.remotePort == port }` 的，
 * 所以要锁的不是它算得快，而是**它和那句 any 给出的答案逐个端口一模一样**——
 * 判错一个端口，用户看到的就是「添加」按钮点不动，或者同一个端口被转发两遍。
 */
class ForwardPortIndexTest {

    private fun row(remotePort: Int, localPort: Int = remotePort, status: ForwardStatus? = null) =
        ForwardRow(
            spec = ForwardSpec(hostId = "h", remotePort = remotePort, localPort = localPort),
            status = status,
        )

    @Test
    fun `与逐行 any 的判断逐个端口一致`() {
        val rows = listOf(
            row(3000),
            row(80, localPort = 8080),
            // 同一个远端端口配两条（转到不同本地口）是允许的，索引不能被重复项带偏
            row(3000, localPort = 3001, status = ForwardStatus.Active),
            row(65535),
        )
        val taken = takenRemotePorts(rows)

        (0..65535).forEach { port ->
            assertEquals(
                "端口 $port 的判断和 any {} 不一致",
                rows.any { it.spec.remotePort == port },
                port in taken,
            )
        }
    }

    @Test
    fun `一条转发都没有时是空集合——所有端口都该能添加`() {
        val taken = takenRemotePorts(emptyList())

        assertTrue(taken.isEmpty())
        assertTrue(22 !in taken)
    }
}
