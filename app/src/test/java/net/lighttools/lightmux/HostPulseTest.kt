package net.lighttools.lightmux

import net.lighttools.lightmux.monitor.HostFacts
import net.lighttools.lightmux.monitor.HostPulse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 主页主机行监控的解析与差值。`/proc` 片段同 [HostFactsTest]，一律写死。 */
class HostPulseTest {

    private fun output(
        uptime: String = "1000.00 3000.00",
        stat: String = "cpu  100 0 100 800 0 0 0 0",
        net: String = "",
        procfs: String = "1",
        end: Boolean = true,
    ) = listOfNotNull(
        "motd 噪音",
        "${HostFacts.MARKER_PROCFS}$procfs",
        HostFacts.MARKER_UPTIME1, uptime,
        HostFacts.MARKER_CPU1, stat,
        HostFacts.MARKER_NET1,
        "Inter-|   Receive                                                |  Transmit",
        " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed",
        net,
        HostFacts.MARKER_MEM,
        "MemTotal:        1000 kB",
        "MemAvailable:     250 kB",
        HostFacts.MARKER_END.takeIf { end },
    ).joinToString("\n")

    private fun netLine(name: String, rx: Long, tx: Long) =
        "  $name: $rx 0 0 0 0 0 0 0 $tx 0 0 0 0 0 0 0"

    @Test
    fun firstSampleHasMemoryOnly() {
        val sample = HostPulse.parse(output())!!
        val reading = HostPulse.reading(null, sample)
        assertNull(reading.cpu)
        assertNull(reading.rxBytesPerSecond)
        assertEquals(0.75f, reading.memory.ratio, 0.001f)
    }

    @Test
    fun cpuAndNetFromTwoSamples() {
        val first = HostPulse.parse(
            output(net = listOf(netLine("eth0", 1000, 500), netLine("docker0", 0, 0)).joinToString("\n"))
        )!!
        val second = HostPulse.parse(
            output(
                uptime = "1005.00 3000.00",
                stat = "cpu  150 0 150 900 0 0 0 0",
                net = listOf(netLine("eth0", 6000, 1500), netLine("docker0", 99999, 99999)).joinToString("\n"),
            )
        )!!
        val reading = HostPulse.reading(first, second)
        // 忙 100 / 总 200
        assertEquals(0.5, reading.cpu!!, 0.001)
        // docker0 是虚拟网卡，不计入；间隔按远端 uptime 的 5 秒算
        assertEquals(1000.0, reading.rxBytesPerSecond!!, 0.001)
        assertEquals(200.0, reading.txBytesPerSecond!!, 0.001)
    }

    @Test
    fun interfaceAppearingMidwayIsIgnored() {
        val first = HostPulse.parse(output(net = netLine("eth0", 0, 0)))!!
        val second = HostPulse.parse(
            output(uptime = "1001.00 0", net = listOf(netLine("eth0", 100, 0), netLine("wlan0", 50000, 0)).joinToString("\n"))
        )!!
        assertEquals(100.0, HostPulse.reading(first, second).rxBytesPerSecond!!, 0.001)
    }

    @Test
    fun rebootedHostGivesNoRate() {
        val first = HostPulse.parse(output(uptime = "1000.00 0"))!!
        val second = HostPulse.parse(output(uptime = "3.00 0"))!!
        val reading = HostPulse.reading(first, second)
        assertNull(reading.cpu)
        assertNull(reading.txBytesPerSecond)
    }

    @Test
    fun counterWrapIsZeroNotNegative() {
        val first = HostPulse.parse(output(net = netLine("eth0", 5000, 5000)))!!
        val second = HostPulse.parse(output(uptime = "1001.00 0", net = netLine("eth0", 10, 10)))!!
        assertEquals(0.0, HostPulse.reading(first, second).rxBytesPerSecond!!, 0.001)
    }

    @Test
    fun unsupportedOrTruncatedIsNull() {
        assertNull(HostPulse.parse(output(procfs = "0")))
        assertNull(HostPulse.parse(output(end = false)))
        assertNull(HostPulse.parse(output(stat = "garbage")))
        assertNull(HostPulse.parse(""))
    }
}
