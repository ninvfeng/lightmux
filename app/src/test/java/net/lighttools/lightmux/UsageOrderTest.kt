package net.lighttools.lightmux

import net.lighttools.lightmux.monitor.ContainerInfo
import net.lighttools.lightmux.monitor.ProcessInfo
import net.lighttools.lightmux.monitor.UsageOrder
import net.lighttools.lightmux.monitor.UsageSort
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 容器 / 进程按占用倒序的单测。
 *
 * 重点在两处容易写错的地方：**内存榜里 CPU 为 0 的进程不能被挤掉**（这正是采集侧发两路 ps 的理由），
 * 以及**停掉的容器必须沉底**——它们没有 stats，按占用排会全挤在 0 那一档。
 */
class UsageOrderTest {

    private fun process(pid: Int, cpu: Double, mem: Double) =
        ProcessInfo(pid = pid, cpuPercent = cpu, memPercent = mem, command = "p$pid")

    private fun container(
        name: String,
        running: Boolean = true,
        cpu: Double? = null,
        mem: Double? = null,
    ) = ContainerInfo(
        name = name,
        image = "img",
        status = if (running) "Up 3 days" else "Exited (0) 2 hours ago",
        running = running,
        cpuPercent = cpu,
        memoryUsed = mem?.let { "1MiB" },
        memoryPercent = mem,
    )

    @Test
    fun `进程按 CPU 倒序`() {
        val list = listOf(process(1, 0.5, 40.0), process(2, 30.0, 1.0), process(3, 10.0, 2.0))
        assertEquals(listOf(2, 3, 1), UsageOrder.processes(list, UsageSort.CPU).map { it.pid })
    }

    @Test
    fun `进程按内存倒序时，CPU 为 0 的大户排最前`() {
        val list = listOf(process(1, 30.0, 1.0), process(2, 0.0, 40.0))
        assertEquals(listOf(2, 1), UsageOrder.processes(list, UsageSort.MEMORY).map { it.pid })
    }

    @Test
    fun `占用相同的进程按 pid 定序，刷新时行序不跳`() {
        val list = listOf(process(9, 0.0, 0.0), process(3, 0.0, 0.0), process(5, 0.0, 0.0))
        assertEquals(listOf(3, 5, 9), UsageOrder.processes(list, UsageSort.CPU).map { it.pid })
    }

    @Test
    fun `进程截到上限`() {
        val list = (1..20).map { process(it, it.toDouble(), 0.0) }
        val top = UsageOrder.processes(list, UsageSort.CPU)
        assertEquals(UsageOrder.PROCESS_LIMIT, top.size)
        assertEquals(20, top.first().pid)
    }

    @Test
    fun `容器按 CPU 倒序，停掉的沉底`() {
        val list = listOf(
            container("idle", cpu = 0.1, mem = 1.0),
            container("dead", running = false),
            container("busy", cpu = 90.0, mem = 2.0),
        )
        assertEquals(
            listOf("busy", "idle", "dead"),
            UsageOrder.containers(list, UsageSort.CPU).map { it.name },
        )
    }

    @Test
    fun `容器按内存倒序`() {
        val list = listOf(
            container("web", cpu = 90.0, mem = 1.0),
            container("db", cpu = 0.2, mem = 50.0),
        )
        assertEquals(listOf("db", "web"), UsageOrder.containers(list, UsageSort.MEMORY).map { it.name })
    }

    @Test
    fun `stats 采不到时退化成按名排序`() {
        // docker stats 超时 / 没权限：所有读数都是 null，这时至少要给个稳定的顺序
        val list = listOf(container("beta"), container("alpha"))
        assertEquals(listOf("alpha", "beta"), UsageOrder.containers(list, UsageSort.CPU).map { it.name })
    }

    @Test
    fun `切换排序键来回一次回到原样`() {
        assertEquals(UsageSort.MEMORY, UsageSort.CPU.toggled())
        assertEquals(UsageSort.CPU, UsageSort.CPU.toggled().toggled())
    }
}
