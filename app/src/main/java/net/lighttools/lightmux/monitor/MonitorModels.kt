package net.lighttools.lightmux.monitor

import java.util.Locale

/**
 * 一次采集拿到的全部指标。
 *
 * 可选项一律用 `null` / 空列表表示「这台机器上采不到」，**绝不拿 0 充数**（PRD §4.4）：
 * 一条 0% 的进度条和一条「不可用」在用户眼里是两回事，前者会被当成真实读数。
 *
 * [cpu] 例外：它的 `null` 不代表「采不到」，而是「首屏快采（[HostFacts.parseQuick]）
 * 还没轮到全量采集」——CPU 使用率必须靠两次 `/proc/stat` 采样的差值，快采只发一次
 * 就拿不到这个数。全量 [HostFacts.parse] 成功时 `cpu` 恒不为 null；读不出 `/proc/stat`
 * 时整次采集判 [FactsResult.Malformed]，同样不会产出 `cpu = null` 的快照。
 */
data class HostSnapshot(
    val system: SystemInfo,
    val uptimeSeconds: Long,
    val load: LoadAverage,
    val cpu: CpuUsage?,
    val memory: MemoryUsage,
    /** 没配 swap 的机器为 null。显示一条 0/0 的条只会让人以为读错了 */
    val swap: MemoryUsage? = null,
    /** 没有独立显卡、或驱动工具不在 PATH 里，都是空——同样不影响整次采集成立 */
    val gpus: List<GpuInfo> = emptyList(),
    val disks: List<DiskUsage> = emptyList(),
    val interfaces: List<NetInterface> = emptyList(),
    /** 本机 IPv4，已剔除回环与虚拟网卡上的地址。没有 `ip` 也没有 `hostname -I` 时为空 */
    val addresses: List<String> = emptyList(),
    /** busybox 的 ps 不认 `-eo`，拿不到就是空——这不影响整次采集成立 */
    val processes: List<ProcessInfo> = emptyList(),
    /** 没装容器引擎、或当前用户不在 docker 组，都是空——同样不影响整次采集成立 */
    val containers: List<ContainerInfo> = emptyList(),
)

/** 三项都可能为 null：容器里常常没有 `/etc/os-release`，精简镜像里连 `hostname` 都没有。 */
data class SystemInfo(
    val distro: String? = null,
    val kernel: String? = null,
    val hostname: String? = null,
)

data class LoadAverage(val one: Double, val five: Double, val fifteen: Double)

/**
 * CPU 使用率，取值 0..1。
 *
 * 必须由**两次 `/proc/stat` 采样的差值**算出——`/proc/stat` 里是开机以来的累计时间，
 * 单次快照只能算出「开机至今的平均使用率」，那个数字几乎恒定，毫无意义。
 */
data class CpuUsage(val total: Double, val cores: List<Double> = emptyList()) {
    val coreCount: Int get() = cores.size
}

/** 内存 / swap 共用。单位一律 byte，`/proc/meminfo` 的 kB 在解析时就换算掉。 */
data class MemoryUsage(val totalBytes: Long, val usedBytes: Long) {

    val availableBytes: Long get() = (totalBytes - usedBytes).coerceAtLeast(0L)

    val ratio: Float
        get() = if (totalBytes <= 0L) 0f
        else (usedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
}

/**
 * 一块 GPU。
 *
 * 除名字外每一项都可能为 null：直通给虚拟机的卡报不出 `utilization.gpu`，
 * AMD 的 sysfs 也不一定挂得出温度。采不到就是 null，不拿 0 充数，理由同 [HostSnapshot]。
 *
 * 显存统一存 byte——nvidia-smi 给的是 MiB，sysfs 给的是 byte，换算在解析时就做掉，
 * 免得显示层还要记得这块数据是哪条命令采来的。
 */
data class GpuInfo(
    val name: String,
    /** 算力占用，取值 0..1。这是驱动直接给的瞬时值，不像 CPU 那样要自己算差值 */
    val utilization: Double? = null,
    val memoryUsedBytes: Long? = null,
    val memoryTotalBytes: Long? = null,
    val temperatureCelsius: Int? = null,
) {

    /** null = 显存读不到。别退化成 0f：一条空的进度条会被当成「显存没被占用」 */
    val memoryRatio: Float?
        get() {
            val used = memoryUsedBytes ?: return null
            val total = memoryTotalBytes ?: return null
            if (total <= 0L) return null
            return (used.toDouble() / total).toFloat().coerceIn(0f, 1f)
        }
}

data class DiskUsage(
    val device: String,
    val mountPoint: String,
    val totalBytes: Long,
    val usedBytes: Long,
    val availableBytes: Long,
) {
    /**
     * 分母用 `已用 + 可用` 而不是总量——这是 `df` 的 Capacity 语义。
     * ext4 默认给 root 留 5%，用总量当分母算出来的百分比会比 `df` 少几个点，对不上用户在终端里看到的数。
     */
    val ratio: Float
        get() {
            val denominator = usedBytes + availableBytes
            return if (denominator <= 0L) 0f
            else (usedBytes.toDouble() / denominator).toFloat().coerceIn(0f, 1f)
        }
}

/** 网卡速率。是两次 `/proc/net/dev` 的差值除以采样间隔，不是累计量。 */
data class NetInterface(
    val name: String,
    val rxBytesPerSecond: Double,
    val txBytesPerSecond: Double,
    /** 这张卡上的 IPv4。没跑 `ip addr`（或这张卡没配地址）时为 null */
    val address: String? = null,
    /**
     * veth / docker0 / tun 这类。
     *
     * 一台跑容器的机器上这种卡能有十几张，且流量多半是本机进程之间转的，
     * 和「这台机器和外界之间在传多少」不是一回事——UI 默认把它们收起来。
     */
    val virtual: Boolean = false,
)

data class ProcessInfo(
    val pid: Int,
    val cpuPercent: Double,
    val memPercent: Double,
    val command: String,
)

/**
 * 一个容器。
 *
 * [status] 保留 docker 的英文原文（`Up 3 days`、`Exited (0) 2 hours ago`），既不翻译也不解析成时长：
 * 用户在终端里 `docker ps` 看到的就是这一串，两边对得上比译得好看重要。
 *
 * 三个 stats 字段来自 `docker stats --no-stream`，**停掉的容器一律为 null**（它根本不在 stats 输出里），
 * stats 超时或没权限时同样是 null——不拿 0 充数，理由同 [HostSnapshot]。
 * [memoryUsed] 保留 docker 的原文（`2.5MiB`）而不换算成 `formatBytes` 的写法，同样是为了和终端对得上。
 */
data class ContainerInfo(
    val name: String,
    val image: String,
    val status: String,
    val running: Boolean,
    val cpuPercent: Double? = null,
    val memoryUsed: String? = null,
    val memoryPercent: Double? = null,
) {
    val hasStats: Boolean get() = cpuPercent != null || memoryUsed != null
}

/**
 * 容器 / 进程列表的排序键。
 *
 * 只有倒序一个方向：用户翻这两段就是为了找「谁在吃资源」，占用最低的那几行没人看。
 */
enum class UsageSort {
    CPU,
    MEMORY;

    fun toggled(): UsageSort = if (this == CPU) MEMORY else CPU
}

/**
 * 按占用倒序排列容器与进程。
 *
 * 抽成不带 Android 依赖的 object 是为了能单测（CLAUDE.md 架构要点 ⑤）。
 */
object UsageOrder {

    /**
     * 进程显示上限。
     *
     * 采集侧给的是 CPU、内存两路 Top 合并去重后的十几条（见 `HostFacts.PROCESS_COMMAND`），
     * 这里再截到一屏看得完的量——监控页是「扫一眼」的地方，真要翻进程该去开终端。
     */
    const val PROCESS_LIMIT = 7

    fun processes(
        list: List<ProcessInfo>,
        sort: UsageSort,
        limit: Int = PROCESS_LIMIT,
    ): List<ProcessInfo> = list
        .sortedWith(
            compareByDescending<ProcessInfo> { it.usage(sort) }
                // 占用相同（一堆 0.0 的内核线程）时按 pid 定序，否则每 5 秒刷新行序都在跳
                .thenBy { it.pid }
        )
        .take(limit)

    /**
     * 容器：停掉的一律沉底。
     *
     * 它们本来就没有 stats，按占用排等于全塞在 0 那一档，混在中间只会打断
     * 「谁在吃资源」这条线索。运行中的按选中的键倒序；stats 整个采不到（超时 / 没权限）时
     * 所有键都是 null，自然退化成按名排序，和以前的行为一致。
     */
    fun containers(list: List<ContainerInfo>, sort: UsageSort): List<ContainerInfo> = list
        .sortedWith(
            compareByDescending<ContainerInfo> { it.running }
                .thenByDescending { it.usage(sort) ?: -1.0 }
                .thenBy { it.name }
        )

    private fun ProcessInfo.usage(sort: UsageSort): Double =
        if (sort == UsageSort.CPU) cpuPercent else memPercent

    private fun ContainerInfo.usage(sort: UsageSort): Double? =
        if (sort == UsageSort.CPU) cpuPercent else memoryPercent
}

/**
 * 一次采集的结果。
 *
 * 三个分支必须分清楚：**解析失败绝不退化成一份全零的快照**——用户会把 0% 的 CPU
 * 和 0 字节的内存当成真实读数，那比明说「读取失败」有害得多。
 */
sealed interface FactsResult {

    data class Ok(val snapshot: HostSnapshot) : FactsResult

    /**
     * 这台机器没有 `/proc`（macOS / *BSD）。
     *
     * 首版只支持 Linux 是明确取舍：sysctl 那套输出格式与字段名和 /proc 毫无共同点，
     * 兼容它等于再写一套解析器加一套测试，收益不足。
     */
    data object Unsupported : FactsResult

    /** 输出看不懂。UI 显示「采集失败 · 重试」，并保留上一次的数据继续展示。 */
    data class Malformed(val reason: String) : FactsResult
}

/**
 * 运行时长拆成天/时/分。
 *
 * 只出**数值**，「天」「小时」这些词交给 `strings.xml`——双语文案不能在这里拼。
 * 和 `RelativeTime` 是同一个理由。
 */
object Uptime {

    data class Parts(val days: Long, val hours: Long, val minutes: Long)

    fun of(seconds: Long): Parts {
        val total = seconds.coerceAtLeast(0L)
        return Parts(
            days = total / 86_400L,
            hours = total % 86_400L / 3_600L,
            minutes = total % 3_600L / 60L,
        )
    }
}

private val BYTE_UNITS = listOf("B", "KiB", "MiB", "GiB", "TiB", "PiB")

/**
 * 人类可读的字节数。
 *
 * KiB/MiB 这些符号是国际通用写法，不进 `strings.xml`（同附加键栏的键帽）。
 * 用 [Locale.US] 格式化：跟随系统 locale 会在阿拉伯语环境下输出印度数字，和旁边的数值对不上。
 */
fun formatBytes(bytes: Long): String {
    var value = bytes.coerceAtLeast(0L).toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < BYTE_UNITS.lastIndex) {
        value /= 1024.0
        unit++
    }
    // 字节级别不需要小数：「512.0 B」比「512 B」更啰嗦也更难读
    return if (unit == 0) "${value.toLong()} ${BYTE_UNITS[0]}"
    else String.format(Locale.US, "%.1f %s", value, BYTE_UNITS[unit])
}

fun formatRate(bytesPerSecond: Double): String =
    "${formatBytes(bytesPerSecond.coerceAtLeast(0.0).toLong())}/s"

/** 百分比只保留一位小数：CPU 每 5 秒跳一次，两位小数纯粹是噪音。 */
fun formatPercent(ratio: Double): String = String.format(Locale.US, "%.1f", ratio * 100.0)

fun formatLoad(value: Double): String = String.format(Locale.US, "%.2f", value)
