package net.lighttools.lightmux.monitor

/**
 * 主页主机行上的 CPU / 内存 / 网速。**纯 Kotlin，零 Android 依赖**，可单测。
 *
 * 和监控页的 [HostFacts.PROBE_COMMAND] 不同，这里**不在命令里 `sleep 1`**：主页可能同时开着
 * 好几台，每轮都在主机锁上占一秒，会把 tmux 探测一起拖慢。差值改由两轮轮询之间算——
 * 上一轮的 [Sample] 留在调用方手里，这一轮拿来减。代价是开启后的第一轮只有内存，CPU 与网速
 * 要等第二轮（调用方因此把第二轮提前，见 `HomeViewModel.pulseLoop`）。
 */
object HostPulse {

    /** 只读四个 `/proc` 文件。`/proc/stat` 只要首行（总体那行），核数多的机器上整份能有几十行。 */
    val COMMAND: String = (
        HostFacts.PREAMBLE +
            listOf(
                "echo ${HostFacts.MARKER_UPTIME1}", "cat /proc/uptime 2>/dev/null",
                "echo ${HostFacts.MARKER_CPU1}", "head -n 1 /proc/stat 2>/dev/null",
                "echo ${HostFacts.MARKER_NET1}", "cat /proc/net/dev 2>/dev/null",
                "echo ${HostFacts.MARKER_MEM}", "cat /proc/meminfo 2>/dev/null",
                "echo ${HostFacts.MARKER_END}",
            )
        ).joinToString("; ")

    /** 一轮的原始计数。速率与使用率都要靠两份 [Sample] 做差，单份没有意义。 */
    class Sample(
        /** 取自远端 `/proc/uptime`，而不是本机时钟：网络抖动不该算进采样间隔 */
        val uptime: Double,
        val cpu: LongArray,
        /** 网卡名 → (收, 发) 累计字节。只保留真实网卡，见 [parse] */
        val net: Map<String, Pair<Long, Long>>,
        val memory: MemoryUsage,
    )

    /** 主机行上显示的读数。CPU 与网速为 null = 还只有一轮采样，算不出差值。 */
    data class Reading(
        val cpu: Double?,
        val memory: MemoryUsage,
        val rxBytesPerSecond: Double?,
        val txBytesPerSecond: Double?,
    )

    /** @return null = 输出看不懂，或这台机器没有 `/proc`——两者对主机行来说都只是「不可用」 */
    fun parse(stdout: String): Sample? {
        val lines = stdout.lines().map { it.trimEnd('\r') }
        if (HostFacts.preflight(lines) != null) return null
        val sections = HostFacts.split(lines)
        val uptime = HostFacts.firstDouble(sections[HostFacts.MARKER_UPTIME1]) ?: return null
        val cpu = HostFacts.cpuCounters(sections[HostFacts.MARKER_CPU1])["cpu"] ?: return null
        val memory = HostFacts.parseMemory(sections[HostFacts.MARKER_MEM])?.first ?: return null
        // 虚拟网卡（veth / docker0…）上的流量多半是本机容器之间转的，计进来会把同一份流量算两遍
        val net = HostFacts.netCounters(sections[HostFacts.MARKER_NET1])
            .filterKeys { !HostFacts.isVirtualInterface(it) }
        return Sample(uptime, cpu, net, memory)
    }

    fun reading(previous: Sample?, current: Sample): Reading {
        val interval = previous?.let { current.uptime - it.uptime }
        // 间隔不成立（远端重启过、或两轮挨得太近）时宁可不给速率，也不拿一个编出来的分母去除
        if (previous == null || interval == null || interval < MIN_INTERVAL_SECONDS) {
            return Reading(cpu = null, memory = current.memory, rxBytesPerSecond = null, txBytesPerSecond = null)
        }
        // 只认两轮都在的网卡：中途冒出来的卡没有基准，算进去就是把它开机以来的总量当成这几秒的流量
        var rx = 0L
        var tx = 0L
        current.net.forEach { (name, now) ->
            val before = previous.net[name] ?: return@forEach
            rx += (now.first - before.first).coerceAtLeast(0L)
            tx += (now.second - before.second).coerceAtLeast(0L)
        }
        return Reading(
            cpu = HostFacts.usageBetween(previous.cpu, current.cpu),
            memory = current.memory,
            rxBytesPerSecond = rx / interval,
            txBytesPerSecond = tx / interval,
        )
    }

    private const val MIN_INTERVAL_SECONDS = 0.2
}
