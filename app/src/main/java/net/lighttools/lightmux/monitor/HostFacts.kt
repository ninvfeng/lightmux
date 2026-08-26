package net.lighttools.lightmux.monitor

/**
 * 主机指标的采集命令与输出解析。**纯 Kotlin，零 Android 依赖**——和 `Tmux` 一样，
 * 本机没有真机，这一层的单测是 M3a 唯一能自证正确的手段（PRD §6.2）。
 *
 * 两条贯穿始终的原则：
 * - **直读 `/proc`**，不碰 `top` / `free` / `vmstat`。那几个工具的输出格式在
 *   coreutils / busybox / procps 各版本之间都不一样，解析它们等于给自己埋一堆发行版专属 bug。
 * - **一次 exec 采完**，中间夹一次 `sleep 1` 拿到第二组采样。CPU 与网卡速率都是**差值**，
 *   单次快照算出来的是「开机至今的平均值」，那个数字几乎不动，没有任何参考价值。
 */
object HostFacts {

    // ---- 哨兵 ----------------------------------------------------------------
    // 输出靠这些整行标记切段。命令输出里不可能出现同样的整行（挂载点、网卡名再怪也带不上下划线围栏）。

    /** `1` = 有 `/proc/stat`；`0` = 没有（macOS / *BSD），直接判 [FactsResult.Unsupported] */
    const val MARKER_PROCFS = "__LM_PROCFS__:"

    const val MARKER_UPTIME1 = "__LM_UP1__"
    const val MARKER_CPU1 = "__LM_CPU1__"
    const val MARKER_NET1 = "__LM_NET1__"
    const val MARKER_UPTIME2 = "__LM_UP2__"
    const val MARKER_CPU2 = "__LM_CPU2__"
    const val MARKER_NET2 = "__LM_NET2__"
    const val MARKER_LOAD = "__LM_LOAD__"
    const val MARKER_MEM = "__LM_MEM__"
    const val MARKER_DISK = "__LM_DISK__"
    const val MARKER_ADDR = "__LM_ADDR__"
    const val MARKER_SYSTEM = "__LM_SYS__"
    const val MARKER_PS = "__LM_PS__"
    const val MARKER_DOCKER = "__LM_DOCKER__"

    /** 容器段内部的二级哨兵：前半是 `ps -a`，后半是 `stats`。docker 不存在时这行压根不出现 */
    const val MARKER_CSTATS = "__LM_CSTATS__"

    const val MARKER_END = "__LM_END__"

    /** 系统信息段里靠前缀区分行，而不是靠行号——`uname` 或 `hostname` 缺一个，行号就全错位了。 */
    const val PREFIX_KERNEL = "__LM_K__"
    const val PREFIX_HOSTNAME = "__LM_H__"

    private val MARKERS = setOf(
        MARKER_UPTIME1, MARKER_CPU1, MARKER_NET1,
        MARKER_UPTIME2, MARKER_CPU2, MARKER_NET2,
        MARKER_LOAD, MARKER_MEM, MARKER_DISK, MARKER_ADDR, MARKER_SYSTEM,
        MARKER_PS, MARKER_DOCKER, MARKER_CSTATS, MARKER_END,
    )

    /**
     * 采集命令。
     *
     * 顺序是有讲究的：两组采样（uptime + stat + net/dev）夹着 `sleep 1`，其余静态指标
     * 放在第二组之后——把它们塞在两组采样之间会拉长实际采样间隔，速率算出来偏低。
     *
     * `uptime` 也采两次：`sleep 1` 只是「至少 1 秒」，网络拥塞时可能是 1.4 秒，
     * 拿真实间隔当分母，速率才不会虚高。
     *
     * `ps` 那条**允许失败**：busybox 的 ps 不认 `-eo`，段空了就是没有进程数据，
     * 不能因此判定整次采集失败（见 [parse]）。
     */
    val PROBE_COMMAND: String = listOf(
        // exec channel 是非交互 shell，不读 rc 文件，PATH 里可能没有 sbin
        "export PATH=\"\$PATH:/usr/sbin:/sbin:/usr/local/bin\"",
        "if [ -r /proc/stat ]; then echo ${MARKER_PROCFS}1; else echo ${MARKER_PROCFS}0; fi",
        "echo $MARKER_UPTIME1", "cat /proc/uptime 2>/dev/null",
        "echo $MARKER_CPU1", "cat /proc/stat 2>/dev/null",
        "echo $MARKER_NET1", "cat /proc/net/dev 2>/dev/null",
        "sleep 1",
        "echo $MARKER_UPTIME2", "cat /proc/uptime 2>/dev/null",
        "echo $MARKER_CPU2", "cat /proc/stat 2>/dev/null",
        "echo $MARKER_NET2", "cat /proc/net/dev 2>/dev/null",
        "echo $MARKER_LOAD", "cat /proc/loadavg 2>/dev/null",
        "echo $MARKER_MEM", "cat /proc/meminfo 2>/dev/null",
        // -P 强制 POSIX 输出：不加它，长设备名会把一行拆成两行，解析必错
        "echo $MARKER_DISK", "df -P -k 2>/dev/null",
        "echo $MARKER_ADDR", ADDRESS_COMMAND,
        "echo $MARKER_SYSTEM",
        "echo \"$PREFIX_KERNEL\$(uname -sr 2>/dev/null)\"",
        "echo \"$PREFIX_HOSTNAME\$(hostname 2>/dev/null || cat /proc/sys/kernel/hostname 2>/dev/null)\"",
        "grep -m1 '^PRETTY_NAME=' /etc/os-release 2>/dev/null || head -n 1 /etc/issue 2>/dev/null",
        "echo $MARKER_PS", PROCESS_COMMAND,
        "echo $MARKER_DOCKER", CONTAINER_COMMAND,
        "echo $MARKER_END",
    ).joinToString("; ")

    /**
     * 进程 Top。**发两路**：CPU 倒序一路、内存倒序一路，合并后按 pid 去重（见 [parseProcesses]）。
     *
     * 只发 CPU 那一路的话，UI 上「按内存排序」就只能在 CPU 前几名里排——
     * 真正吃内存的进程往往 CPU 是 0，压根不在那份榜单上，排出来的结果是错的。
     * 多一次 `ps` 的代价可以忽略：它和后面那条 `docker stats` 共用同一条 exec，
     * 而 stats 本身就要一两秒。
     *
     * busybox 的 ps 不认 `-eo`，两路一起失败，段为空——不影响整次采集成立（见 [parse]）。
     */
    private const val PROCESS_COMMAND =
        "ps -eo pid,pcpu,pmem,comm --sort=-pcpu 2>/dev/null | head -n 8; " +
            "ps -eo pid,pcpu,pmem,comm --sort=-pmem 2>/dev/null | head -n 8"

    /**
     * 本机 IPv4。
     *
     * `scope global` 一次过滤掉回环和 link-local，省得在 Kotlin 里再判一遍网段。
     * `-o` 让每个地址占一整行——不加它，`ip addr` 是缩进的多行块，解析要跟着状态机走。
     *
     * 退化到 `hostname -I`：容器和精简镜像里常常没有 iproute2。代价是拿不到网卡名，
     * 于是分不出哪个地址属于虚拟网卡（见 [parseAddresses]）。
     */
    private const val ADDRESS_COMMAND =
        "ip -o -4 addr show scope global 2>/dev/null || hostname -I 2>/dev/null"

    /**
     * 容器列表 + 资源占用。docker 优先，没有再试 podman（RHEL 系默认只装 podman）。
     *
     * **必须套 `timeout`**：dockerd 卡死时 `docker ps` 会一直吊在 socket 上，而它和 CPU、内存
     * 共用同一条 exec——一个僵住的守护进程能把整页监控一起拖到超时。机器上没有 `timeout`
     * （精简的 busybox 环境）时这行直接 command not found，容器段为空，其余指标照常。
     * 这个取舍是有意的：宁可少一块，不能整页坏掉。
     *
     * 分隔符用 `|`：容器名、镜像名、状态串都不可能含它，而镜像名天然带 `:`（tag）和 `/`（registry），
     * 拿 `:` 分隔必错——和 tmux 侧通道用 `:` 是相反的结论，因为那边的字段里没有镜像名。
     * 状态不取 `{{.State}}`：那是 docker 20.10 才有的字段，老版本上整条命令会报模板错误一无所获，
     * 从 `{{.Status}}` 的首词（Up / Exited / Created…）判运行与否一样准。
     *
     * `stats --no-stream` 是这条命令里最贵的一步（要在守护进程侧采一轮 cgroup，通常 1～2 秒），
     * 所以 `timeout` 给到 6 秒且**排在 `ps` 之后**：stats 挂了至少还剩一份容器列表。
     * 它只列运行中的容器，停掉的那些自然没有读数——这正是 [ContainerInfo] 里那几个字段可为 null 的原因。
     *
     * `head -n 50` 只是防爆：几十个容器的机器该去用真正的编排工具，不该在手机上翻。
     */
    private const val CONTAINER_COMMAND =
        "for __lm_ce in docker podman; do command -v \$__lm_ce >/dev/null 2>&1 || continue; " +
            "timeout 4 \$__lm_ce ps -a --format '{{.Names}}|{{.Image}}|{{.Status}}' 2>/dev/null | head -n 50; " +
            "echo $MARKER_CSTATS; " +
            "timeout 6 \$__lm_ce stats --no-stream --no-trunc " +
            "--format '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}|{{.MemPerc}}' 2>/dev/null | head -n 50; " +
            "break; done"

    /**
     * 公网 IP。**不在 [PROBE_COMMAND] 里**，由 [MonitorRepository.probePublicIp] 单独发一次。
     *
     * 两个原因：一是它要走外网，慢的时候好几秒，塞进 5 秒一轮的采集里等于每轮都拖一次；
     * 二是公网 IP 几乎不变，采一次就够，没必要每 5 秒去刷别人家的免费服务。
     *
     * 这条命令会让**远端主机**向第三方发一个 HTTP 请求——这是拿公网出口 IP 的唯一办法
     * （网卡上挂的多半是 NAT 后的内网地址），所以只在用户打开监控页时发，且只发一次。
     *
     * 两个端点封顶 6 秒（`--max-time 3` × 2）。返回的东西不做 shell 侧校验，
     * 交给 [parsePublicAddress] 判——运营商的劫持页也会让 curl 成功退出。
     */
    val PUBLIC_IP_COMMAND: String = listOf(
        "export PATH=\"\$PATH:/usr/sbin:/sbin:/usr/local/bin\"",
        "for __lm_u in https://api.ipify.org https://ifconfig.me/ip; do " +
            "{ curl -fsS --max-time 3 \"\$__lm_u\" || wget -qO- --timeout=3 \"\$__lm_u\"; } " +
            "2>/dev/null && break; done",
        "echo",
    ).joinToString("; ")

    // ---- 解析 ----------------------------------------------------------------

    /**
     * 解析 [PROBE_COMMAND] 的 stdout。
     *
     * **必需段**（uptime / loadavg / 两次 stat / meminfo）缺失或畸形一律 [FactsResult.Malformed]；
     * **可选段**（磁盘 / 网卡 / swap / 进程 / 系统信息）缺了就是空，属正常。
     */
    fun parse(stdout: String): FactsResult {
        val lines = stdout.lines().map { it.trimEnd('\r') }

        // 远端 shell 的 motd / rc 脚本会在我们的输出前面吐东西，一切从哨兵开始认。
        val procfsLine = lines.firstOrNull { it.startsWith(MARKER_PROCFS) }
            ?: return FactsResult.Malformed("missing $MARKER_PROCFS")
        when (procfsLine.removePrefix(MARKER_PROCFS).trim()) {
            "1" -> Unit
            "0" -> return FactsResult.Unsupported
            // 既不是 1 也不是 0，说明这行根本不是我们发的那个 echo
            else -> return FactsResult.Malformed("bad procfs marker")
        }
        // 没有结束哨兵 = 输出被截断（超时、连接断在半路），后半段的缺失都不可信
        if (lines.none { it == MARKER_END }) return FactsResult.Malformed("missing $MARKER_END")

        val sections = split(lines)

        val uptime = firstDouble(sections[MARKER_UPTIME2]) ?: firstDouble(sections[MARKER_UPTIME1])
        ?: return FactsResult.Malformed("bad /proc/uptime")
        val load = parseLoad(sections[MARKER_LOAD]) ?: return FactsResult.Malformed("bad /proc/loadavg")
        val cpu = parseCpu(sections[MARKER_CPU1], sections[MARKER_CPU2])
            ?: return FactsResult.Malformed("bad /proc/stat")
        val memory = parseMemory(sections[MARKER_MEM]) ?: return FactsResult.Malformed("bad /proc/meminfo")

        // 采样间隔取两次 uptime 的差；读不出来（只有一次 uptime）就退回名义值
        val before = firstDouble(sections[MARKER_UPTIME1])
        val after = firstDouble(sections[MARKER_UPTIME2])
        val interval = if (before != null && after != null && after - before > MIN_INTERVAL_SECONDS) {
            after - before
        } else {
            NOMINAL_INTERVAL_SECONDS
        }

        val addresses = parseAddresses(sections[MARKER_ADDR])
        val interfaces = parseNet(sections[MARKER_NET1], sections[MARKER_NET2], interval, addresses.byInterface)

        return FactsResult.Ok(
            HostSnapshot(
                system = parseSystem(sections[MARKER_SYSTEM]),
                uptimeSeconds = uptime.toLong(),
                load = load,
                cpu = cpu,
                memory = memory.first,
                swap = memory.second,
                disks = parseDisks(sections[MARKER_DISK]),
                interfaces = interfaces,
                addresses = addresses.local,
                processes = parseProcesses(sections[MARKER_PS]),
                containers = parseContainers(sections[MARKER_DOCKER], sections[MARKER_CSTATS]),
            )
        )
    }

    /** 按哨兵切段。段缺失与段为空是两回事，但对调用方等价——都拿不到数据。 */
    private fun split(lines: List<String>): Map<String, List<String>> {
        val sections = mutableMapOf<String, MutableList<String>>()
        var current: MutableList<String>? = null
        for (line in lines) {
            if (line == MARKER_END) break
            if (line in MARKERS) {
                current = sections.getOrPut(line) { mutableListOf() }
                continue
            }
            current?.add(line)
        }
        return sections
    }

    // ---- /proc/stat ----------------------------------------------------------

    /**
     * 两次采样算 CPU 使用率。核数就是 `cpuN` 行数。
     *
     * @return null 表示总体那行（`cpu `）读不出来——这是必需项
     */
    private fun parseCpu(before: List<String>?, after: List<String>?): CpuUsage? {
        val first = cpuCounters(before)
        val second = cpuCounters(after)
        val total = usageBetween(first["cpu"], second["cpu"]) ?: return null

        // 按 cpu0、cpu1… 顺序取，而不是遍历 map：显示时的核序必须和机器上的编号一致
        val cores = mutableListOf<Double>()
        while (true) {
            val key = "cpu${cores.size}"
            val core = usageBetween(first[key], second[key]) ?: break
            cores += core
        }
        return CpuUsage(total = total, cores = cores)
    }

    /**
     * `cpu`/`cpuN` 行 → 计数器数组。
     *
     * 只取前 8 个字段（user nice system idle iowait irq softirq steal）：后面的 guest / guest_nice
     * 已经被计入 user / nice，全加一遍等于把虚拟机的时间算两次，使用率会偏低。
     */
    private fun cpuCounters(lines: List<String>?): Map<String, LongArray> {
        val result = mutableMapOf<String, LongArray>()
        lines?.forEach { line ->
            if (!line.startsWith("cpu")) return@forEach
            val parts = line.trim().split(WHITESPACE)
            val name = parts.firstOrNull() ?: return@forEach
            val values = parts.drop(1).map { it.toLongOrNull() ?: return@forEach }
            // 至少要有到 idle 为止的四个字段，否则算不出使用率
            if (values.size < 4) return@forEach
            result[name] = values.take(8).toLongArray()
        }
        return result
    }

    private fun usageBetween(before: LongArray?, after: LongArray?): Double? {
        if (before == null || after == null) return null
        val totalDelta = after.sum() - before.sum()
        // 两次采样完全相同（或计数器被重置）时报 0：负数和 NaN 都会直接画坏进度条
        if (totalDelta <= 0L) return 0.0
        val idleDelta = (idleOf(after) - idleOf(before)).coerceAtLeast(0L)
        return (1.0 - idleDelta.toDouble() / totalDelta).coerceIn(0.0, 1.0)
    }

    /** idle + iowait：等 IO 的时间片上没有指令在跑，算「空闲」才和 top 的口径一致。 */
    private fun idleOf(counters: LongArray): Long =
        counters[3] + (counters.getOrNull(4) ?: 0L)

    // ---- /proc/meminfo -------------------------------------------------------

    /** @return 内存 to swap（没配 swap 时 swap 为 null）；null 表示连 MemTotal 都读不出来 */
    private fun parseMemory(lines: List<String>?): Pair<MemoryUsage, MemoryUsage?>? {
        val kb = mutableMapOf<String, Long>()
        lines?.forEach { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) return@forEach
            val value = line.substring(colon + 1).trim().split(WHITESPACE).firstOrNull()?.toLongOrNull()
                ?: return@forEach
            kb[line.substring(0, colon)] = value
        }

        val total = kb["MemTotal"]?.takeIf { it > 0L } ?: return null
        // MemAvailable 是内核自己算的「不触发换页就能拿到多少」，比 free+buffers+cached 准得多；
        // 它是 3.14 才有的字段，老内核只能退化。
        val available = kb["MemAvailable"]
            ?: kb["MemFree"]?.let { it + (kb["Buffers"] ?: 0L) + (kb["Cached"] ?: 0L) }
            ?: return null
        val memory = MemoryUsage(
            totalBytes = total * 1024L,
            usedBytes = (total - available.coerceIn(0L, total)) * 1024L,
        )

        val swapTotal = kb["SwapTotal"] ?: 0L
        val swap = if (swapTotal <= 0L) null else MemoryUsage(
            totalBytes = swapTotal * 1024L,
            usedBytes = (swapTotal - (kb["SwapFree"] ?: 0L).coerceIn(0L, swapTotal)) * 1024L,
        )
        return memory to swap
    }

    // ---- df ------------------------------------------------------------------

    /**
     * `df -P -k` 的输出。表头行会因为第二列不是数字而被自然丢掉，不必特判。
     *
     * 伪文件系统（tmpfs / overlay / snap 的 loop 设备…）一概过滤：它们要么是内存，
     * 要么是只读镜像，占用率对用户没有任何决策价值，还会把真正的磁盘挤出屏幕。
     */
    private fun parseDisks(lines: List<String>?): List<DiskUsage> = lines.orEmpty().mapNotNull { line ->
        val parts = line.trim().split(WHITESPACE)
        if (parts.size < 6) return@mapNotNull null
        val blocks = parts[1].toLongOrNull() ?: return@mapNotNull null
        val used = parts[2].toLongOrNull() ?: return@mapNotNull null
        val available = parts[3].toLongOrNull() ?: return@mapNotNull null
        if (blocks <= 0L) return@mapNotNull null
        // 挂载点可以带空格（df -P 会原样输出），所以第 6 段往后全是挂载点
        val mountPoint = parts.subList(5, parts.size).joinToString(" ")
        if (isPseudoFilesystem(parts[0], mountPoint)) return@mapNotNull null
        DiskUsage(
            device = parts[0],
            mountPoint = mountPoint,
            totalBytes = blocks * 1024L,
            usedBytes = used * 1024L,
            availableBytes = available * 1024L,
        )
    }.distinctBy { it.mountPoint }

    private fun isPseudoFilesystem(device: String, mountPoint: String): Boolean =
        device in PSEUDO_DEVICES ||
            device.startsWith("/dev/loop") ||
            PSEUDO_MOUNT_PREFIXES.any { mountPoint == it || mountPoint.startsWith("$it/") }

    // ---- /proc/net/dev -------------------------------------------------------

    /**
     * 两次采样算速率。
     *
     * `lo` 一律过滤（回环流量只反映本机进程间通信），零流量网卡也过滤——
     * 一台机器上动辄十几个 veth / docker0 / br-xxxx，全列出来真正的网卡就被埋了。
     */
    private fun parseNet(
        before: List<String>?,
        after: List<String>?,
        intervalSeconds: Double,
        addressByInterface: Map<String, String>,
    ): List<NetInterface> {
        val first = netCounters(before)
        return netCounters(after).mapNotNull { (name, current) ->
            if (current.first == 0L && current.second == 0L) return@mapNotNull null
            // 采样中途才出现的网卡没有基准，报速率就是编数据
            val previous = first[name] ?: return@mapNotNull null
            NetInterface(
                name = name,
                rxBytesPerSecond = rate(previous.first, current.first, intervalSeconds),
                txBytesPerSecond = rate(previous.second, current.second, intervalSeconds),
                address = addressByInterface[name],
                virtual = isVirtualInterface(name),
            )
        }
    }

    /**
     * 名字前缀判虚拟网卡。
     *
     * 靠名字而不是 sysfs 里那个 device 软链是否存在：后者要多跑一圈 shell，
     * 而这些前缀是内核和容器运行时定死的命名规则，稳得很。
     *
     * **`br0` 这类裸网桥不算虚拟**：KVM 宿主机上它就是唯一的对外通路，
     * 收起来等于把这台机器真实的网络流量藏了；docker 建的网桥固定叫 `br-<hash>`，带横杠，认得出来。
     */
    fun isVirtualInterface(name: String): Boolean =
        VIRTUAL_PREFIXES.any { name.startsWith(it) }

    // ---- ip addr -------------------------------------------------------------

    /**
     * @param byInterface 网卡名 → IPv4。`hostname -I` 退化路径下为空（那条命令不给网卡名）
     * @param local 概览里「本地 IP」那一行要显示的地址，已剔除虚拟网卡上的
     */
    private data class Addresses(
        val byInterface: Map<String, String> = emptyMap(),
        val local: List<String> = emptyList(),
    )

    /**
     * `ip -o -4 addr show scope global` 或 `hostname -I` 的输出。
     *
     * 两种格式靠有没有 `inet` 这个词区分，不靠调用方告诉我们跑的是哪条——
     * 命令是 `a || b`，谁真正跑了这边并不知道。
     */
    private fun parseAddresses(lines: List<String>?): Addresses {
        val byInterface = LinkedHashMap<String, String>()
        val bare = mutableListOf<String>()

        lines.orEmpty().forEach { line ->
            val parts = line.trim().split(WHITESPACE).filter { it.isNotEmpty() }
            if (parts.isEmpty()) return@forEach
            val inet = parts.indexOf("inet")
            if (inet >= 0) {
                // `2: eth0    inet 192.168.1.5/24 brd ...`——地址带前缀长度，切掉
                val address = parts.getOrNull(inet + 1)?.substringBefore('/')?.takeIf(::isIpv4) ?: return@forEach
                // 网卡名在索引 1（索引 0 是 `<序号>:`）。同一张卡有多个地址时只留第一个
                val name = parts.getOrNull(1)?.trimEnd(':') ?: return@forEach
                byInterface.putIfAbsent(name, address)
            } else {
                // hostname -I：一行里全是地址，没有网卡名
                bare += parts.filter(::isIpv4)
            }
        }

        val local = if (byInterface.isNotEmpty()) {
            byInterface.filterKeys { !isVirtualInterface(it) }.values.toList()
        } else {
            // 退化路径下分不出虚拟网卡，宁可多显示一个也不猜着删——猜错就是把真的内网地址藏了
            bare
        }
        return Addresses(byInterface, local.distinct().filterNot { it.startsWith("127.") })
    }

    /**
     * [PUBLIC_IP_COMMAND] 的输出 → 公网 IP。
     *
     * 只认长得像 IP 的那一行：运营商劫持页、云厂商的 403 JSON 都会让 curl 正常退出，
     * 把那一坨 HTML 当成 IP 显示出来比显示「不可用」糟得多。
     */
    fun parsePublicAddress(stdout: String): String? = stdout.lines()
        .map { it.trim() }
        .firstOrNull { isIpv4(it) || isIpv6(it) }

    private fun isIpv4(value: String): Boolean {
        val octets = value.split('.')
        return octets.size == 4 && octets.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) && part.toInt() <= 255
        }
    }

    /** 松校验：有 `:`、只含十六进制与冒号、长度对得上。真要严校验得处理 `::` 压缩与 v4 映射，不值当。 */
    private fun isIpv6(value: String): Boolean =
        value.count { it == ':' } >= 2 && value.length in 3..45 &&
            value.all { it == ':' || it.isDigit() || it.lowercaseChar() in 'a'..'f' }

    /** 32 位计数器回绕、或网卡被 down 掉重置时后一次会更小，此时按 0 处理——负速率是纯粹的噪音。 */
    private fun rate(before: Long, after: Long, intervalSeconds: Double): Double =
        (after - before).coerceAtLeast(0L) / intervalSeconds

    /** @return 网卡名 to (接收字节, 发送字节) */
    private fun netCounters(lines: List<String>?): Map<String, Pair<Long, Long>> {
        val result = LinkedHashMap<String, Pair<Long, Long>>()
        lines?.forEach { line ->
            // 表头那两行没有冒号，天然被跳过
            val colon = line.indexOf(':')
            if (colon <= 0) return@forEach
            val name = line.substring(0, colon).trim()
            if (name.isEmpty() || name == "lo") return@forEach
            val fields = line.substring(colon + 1).trim().split(WHITESPACE)
            // 接收 8 列、发送 8 列，收字节在第 0 位、发字节在第 8 位；列数不够说明格式变了
            if (fields.size < 9) return@forEach
            val rx = fields[0].toLongOrNull() ?: return@forEach
            val tx = fields[8].toLongOrNull() ?: return@forEach
            result[name] = rx to tx
        }
        return result
    }

    // ---- ps ------------------------------------------------------------------

    /**
     * 表头行的 pid 列是 `PID`，转不成数字，自然被丢掉。
     *
     * [PROCESS_COMMAND] 是两路 ps，同一个进程会在两份榜单里各出现一次，**按 pid 去重**——
     * 一条进程列两遍看着就像坏了。两路的读数取自同一台机器的同一时刻，留先来的那条即可。
     */
    private fun parseProcesses(lines: List<String>?): List<ProcessInfo> {
        val byPid = LinkedHashMap<Int, ProcessInfo>()
        lines.orEmpty().forEach { line ->
            val parts = line.trim().split(WHITESPACE)
            if (parts.size < 4) return@forEach
            val pid = parts[0].toIntOrNull() ?: return@forEach
            val cpu = parts[1].toDoubleOrNull() ?: return@forEach
            val mem = parts[2].toDoubleOrNull() ?: return@forEach
            byPid.getOrPut(pid) {
                ProcessInfo(
                    pid = pid,
                    cpuPercent = cpu,
                    memPercent = mem,
                    command = parts.subList(3, parts.size).joinToString(" "),
                )
            }
        }
        return byPid.values.toList()
    }

    // ---- 容器 ----------------------------------------------------------------

    /**
     * `名字|镜像|状态`（见 [CONTAINER_COMMAND]）。
     *
     * 三个字段都不含 `|`，所以 `limit = 3` 之后剩下的整段就是状态串——
     * 它带空格和括号（`Up 2 hours (healthy)`），按空白切会散架。
     *
     * 排序放在这里而不是交给 `docker ps`：`--filter` 一次只能过一种状态，
     * 要「运行中在前」得发两条命令，而在内存里排一次是零成本的。
     */
    private fun parseContainers(lines: List<String>?, statsLines: List<String>?): List<ContainerInfo> {
        val stats = parseContainerStats(statsLines)
        return lines.orEmpty()
            .mapNotNull { line ->
                val parts = line.split('|', limit = 3)
                if (parts.size < 3) return@mapNotNull null
                val name = parts[0].trim()
                if (name.isEmpty()) return@mapNotNull null
                val status = parts[2].trim()
                val stat = stats[name]
                ContainerInfo(
                    name = name,
                    image = parts[1].trim(),
                    status = status,
                    // docker 的状态首词是固定的英文枚举（Up / Exited / Created / Restarting / Paused / Dead），
                    // 不随 locale 变，拿它判运行与否是稳的
                    running = status.startsWith("Up"),
                    cpuPercent = stat?.cpuPercent,
                    memoryUsed = stat?.memoryUsed,
                    memoryPercent = stat?.memoryPercent,
                )
            }
            .sortedWith(compareByDescending<ContainerInfo> { it.running }.thenBy { it.name })
    }

    private data class ContainerStats(
        val cpuPercent: Double?,
        val memoryUsed: String?,
        val memoryPercent: Double?,
    )

    /**
     * `名字|CPU%|已用 / 总量|内存%`。
     *
     * 内存那列只取斜杠前的已用量：斜杠后是宿主机总内存（或容器的 limit），
     * 每一行都一样，在手机的窄屏上纯属占地方。
     *
     * 百分号必须剥掉再转数字，且 `--`（docker 对刚起来还没采到样的容器就这么输出）要落成 null——
     * `"--".toDoubleOrNull()` 本来就是 null，这里不需要特判，写下来是怕以后有人「顺手」补个 `?: 0.0`。
     */
    private fun parseContainerStats(lines: List<String>?): Map<String, ContainerStats> {
        val result = mutableMapOf<String, ContainerStats>()
        lines.orEmpty().forEach { line ->
            val parts = line.split('|')
            if (parts.size < 4) return@forEach
            val name = parts[0].trim()
            if (name.isEmpty()) return@forEach
            result[name] = ContainerStats(
                cpuPercent = percentOrNull(parts[1]),
                memoryUsed = parts[2].substringBefore('/').trim().ifBlank { null },
                memoryPercent = percentOrNull(parts[3]),
            )
        }
        return result
    }

    private fun percentOrNull(value: String): Double? =
        value.trim().removeSuffix("%").trim().toDoubleOrNull()

    // ---- 系统信息 -------------------------------------------------------------

    private fun parseSystem(lines: List<String>?): SystemInfo {
        val rows = lines.orEmpty()
        val kernel = rows.firstOrNull { it.startsWith(PREFIX_KERNEL) }
            ?.removePrefix(PREFIX_KERNEL)?.trim()?.ifBlank { null }
        val hostname = rows.firstOrNull { it.startsWith(PREFIX_HOSTNAME) }
            ?.removePrefix(PREFIX_HOSTNAME)?.trim()?.ifBlank { null }
        val distro = rows.firstOrNull { it.startsWith(PRETTY_NAME_PREFIX) }
            ?.removePrefix(PRETTY_NAME_PREFIX)?.trim()?.trim('"')?.ifBlank { null }
        // 没有 os-release 时命令退化成 /etc/issue，剩下的那行就是它
            ?: rows.firstOrNull {
                it.isNotBlank() && !it.startsWith(PREFIX_KERNEL) && !it.startsWith(PREFIX_HOSTNAME)
            }?.let(::cleanIssue)
        return SystemInfo(distro = distro, kernel = kernel, hostname = hostname)
    }

    /** `/etc/issue` 里混着 `\n \l` 这类 agetty 转义，直接显示会像乱码。 */
    private fun cleanIssue(line: String): String? = line.trim().split(WHITESPACE)
        .filterNot { it.startsWith("\\") }
        .joinToString(" ")
        .ifBlank { null }

    // ---- 小工具 --------------------------------------------------------------

    private fun parseLoad(lines: List<String>?): LoadAverage? {
        val parts = lines?.firstOrNull { it.isNotBlank() }?.trim()?.split(WHITESPACE) ?: return null
        if (parts.size < 3) return null
        val one = parts[0].toDoubleOrNull() ?: return null
        val five = parts[1].toDoubleOrNull() ?: return null
        val fifteen = parts[2].toDoubleOrNull() ?: return null
        return LoadAverage(one, five, fifteen)
    }

    private fun firstDouble(lines: List<String>?): Double? =
        lines?.firstOrNull { it.isNotBlank() }?.trim()?.split(WHITESPACE)?.firstOrNull()?.toDoubleOrNull()

    private val WHITESPACE = Regex("\\s+")

    private const val PRETTY_NAME_PREFIX = "PRETTY_NAME="

    /** 命令里写死的 `sleep 1`；两次 uptime 读不出来时拿它当分母。 */
    private const val NOMINAL_INTERVAL_SECONDS = 1.0

    /** 间隔小于这个数就当读数不可信（uptime 只有两位小数，间隔太小时误差会被放大成假的高速率）。 */
    private const val MIN_INTERVAL_SECONDS = 0.05

    private val PSEUDO_DEVICES = setOf(
        "tmpfs", "devtmpfs", "devfs", "overlay", "overlayfs", "squashfs", "udev", "none",
        "shm", "ramfs", "rootfs", "cgroup", "cgroup2", "proc", "sysfs", "devpts", "efivarfs",
        "tracefs", "debugfs", "mqueue", "hugetlbfs", "fusectl", "configfs", "pstore",
        "securityfs", "binfmt_misc", "systemd-1", "snapfuse", "nsfs",
    )

    private val PSEUDO_MOUNT_PREFIXES = listOf("/proc", "/sys", "/dev", "/run", "/snap")

    /**
     * 容器 / 虚拟化 / VPN 建出来的网卡名前缀。见 [isVirtualInterface]。
     *
     * 物理网卡的命名（`eth*`、`enp*`、`ens*`、`eno*`、`wlan*`、`wlp*`、`bond*`）和这些前缀不重叠，
     * 所以「不在表里就是物理卡」这个默认是安全的：新出现的虚拟网卡类型最多是漏收起一张，
     * 反过来把物理卡误判成虚拟，用户会以为主网卡不见了。
     */
    private val VIRTUAL_PREFIXES = listOf(
        "veth", "docker", "br-", "virbr", "vnet", "vmnet", "tun", "tap", "wg",
        "cni", "flannel", "cali", "cilium", "kube", "vxlan", "dummy", "ifb",
        "gre", "sit", "lxc", "lxdbr", "ovs", "zt", "tailscale", "utun", "nebula",
    )
}
