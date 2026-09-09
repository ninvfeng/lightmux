package net.lighttools.lightmux

import net.lighttools.lightmux.monitor.FactsResult
import net.lighttools.lightmux.monitor.HostFacts
import net.lighttools.lightmux.monitor.HostSnapshot
import net.lighttools.lightmux.monitor.Uptime
import net.lighttools.lightmux.monitor.formatBytes
import net.lighttools.lightmux.monitor.formatPercent
import net.lighttools.lightmux.monitor.formatRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 监控采集解析的单测。
 *
 * 用例里的 `/proc` 片段全部**写死在测试里**，绝不读本机文件——读本机就等于让用例
 * 跟着这台构建机的内核版本和挂载情况漂移，换台机器就红。
 *
 * 边界覆盖到狠：两次采样差值、计数器回绕、MemAvailable 有无、伪文件系统、
 * busybox 没有的 `ps`、非 Linux 主机、以及各种畸形数字。
 */
class HostFactsTest {

    // ---- 构造采集输出的小工具 -------------------------------------------------

    private val stat1 = """
        cpu  100 0 100 800 0 0 0 0
        cpu0 50 0 50 400 0 0 0 0
        cpu1 50 0 50 400 0 0 0 0
        intr 12345 0 0
        ctxt 987654
    """.trimIndent()

    private val stat2 = """
        cpu  200 0 200 1600 0 0 0 0
        cpu0 100 0 100 800 0 0 0 0
        cpu1 150 0 150 700 0 0 0 0
        intr 22345 0 0
        ctxt 1987654
    """.trimIndent()

    private val meminfo = """
        MemTotal:       16316936 kB
        MemFree:          200000 kB
        MemAvailable:   10000000 kB
        Buffers:          300000 kB
        Cached:          5000000 kB
        SwapCached:            0 kB
        SwapTotal:       2097148 kB
        SwapFree:        1097148 kB
    """.trimIndent()

    private val netHeader = """
        Inter-|   Receive                                                |  Transmit
         face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
    """.trimIndent()

    private val net1 = """
        $netHeader
            lo: 1000 10 0 0 0 0 0 0 1000 10 0 0 0 0 0 0
          eth0: 2000 20 0 0 0 0 0 0 3000 30 0 0 0 0 0 0
    """.trimIndent()

    private val net2 = """
        $netHeader
            lo: 9000 90 0 0 0 0 0 0 9000 90 0 0 0 0 0 0
          eth0: 6000 60 0 0 0 0 0 0 3000 30 0 0 0 0 0 0
    """.trimIndent()

    private val system = """
        ${HostFacts.PREFIX_KERNEL}Linux 6.8.0-45-generic
        ${HostFacts.PREFIX_HOSTNAME}gz3
        PRETTY_NAME="Ubuntu 24.04.1 LTS"
    """.trimIndent()

    private fun output(
        procfs: String? = "1",
        uptime1: String? = "1000.00 4000.00",
        uptime2: String? = "1002.00 4004.00",
        cpu1: String? = stat1,
        cpu2: String? = stat2,
        net1: String? = this.net1,
        net2: String? = this.net2,
        load: String? = "0.52 0.31 0.20 1/234 5678",
        mem: String? = meminfo,
        disk: String? = null,
        addr: String? = null,
        system: String? = this.system,
        ps: String? = null,
        gpu: String? = null,
        docker: String? = null,
        stats: String? = null,
        end: Boolean = true,
        prefix: String = "",
    ): String = buildString {
        append(prefix)
        procfs?.let { append("${HostFacts.MARKER_PROCFS}$it\n") }
        section(HostFacts.MARKER_UPTIME1, uptime1)
        section(HostFacts.MARKER_CPU1, cpu1)
        section(HostFacts.MARKER_NET1, net1)
        section(HostFacts.MARKER_UPTIME2, uptime2)
        section(HostFacts.MARKER_CPU2, cpu2)
        section(HostFacts.MARKER_NET2, net2)
        section(HostFacts.MARKER_LOAD, load)
        section(HostFacts.MARKER_MEM, mem)
        section(HostFacts.MARKER_DISK, disk)
        section(HostFacts.MARKER_ADDR, addr)
        section(HostFacts.MARKER_SYSTEM, system)
        section(HostFacts.MARKER_PS, ps)
        section(HostFacts.MARKER_GPU, gpu)
        section(HostFacts.MARKER_DOCKER, docker)
        // docker 不存在时这个二级哨兵压根不出现，所以它跟着 stats 走而不是跟着 docker 走
        section(HostFacts.MARKER_CSTATS, stats)
        if (end) append("${HostFacts.MARKER_END}\n")
    }

    /** null = 这一段的哨兵根本不出现（远端连命令都没跑成）。 */
    private fun StringBuilder.section(marker: String, body: String?) {
        if (body == null) return
        append(marker).append('\n')
        if (body.isNotEmpty()) append(body).append('\n')
    }

    private fun snapshotOf(stdout: String): HostSnapshot {
        val result = HostFacts.parse(stdout)
        assertTrue("expected Ok but was $result", result is FactsResult.Ok)
        return (result as FactsResult.Ok).snapshot
    }

    private fun reasonOf(stdout: String): String {
        val result = HostFacts.parse(stdout)
        assertTrue("expected Malformed but was $result", result is FactsResult.Malformed)
        return (result as FactsResult.Malformed).reason
    }

    // ---- 采集命令 -------------------------------------------------------------

    @Test
    fun `采集命令一次拿完，且两组采样之间有 sleep`() {
        val cmd = HostFacts.PROBE_COMMAND
        assertEquals(2, Regex("cat /proc/stat").findAll(cmd).count())
        assertEquals(2, Regex("cat /proc/net/dev").findAll(cmd).count())
        assertEquals(2, Regex("cat /proc/uptime").findAll(cmd).count())
        assertTrue(cmd.contains("sleep 1"))
        // sleep 必须夹在两组采样中间，否则差值是 0
        assertTrue(cmd.indexOf(HostFacts.MARKER_CPU1) < cmd.indexOf("sleep 1"))
        assertTrue(cmd.indexOf("sleep 1") < cmd.indexOf(HostFacts.MARKER_CPU2))
    }

    @Test
    fun `采集命令不依赖 top free vmstat，磁盘用 df -P`() {
        val cmd = HostFacts.PROBE_COMMAND
        assertTrue(cmd.contains("df -P -k"))
        listOf("top ", "free ", "vmstat").forEach {
            assertTrue("命令里不该出现 $it", !cmd.contains(it))
        }
    }

    @Test
    fun `ps 与 os-release 允许失败，不会带垮整条命令`() {
        val cmd = HostFacts.PROBE_COMMAND
        assertTrue(cmd.contains("ps -eo pid,pcpu,pmem,comm --sort=-pcpu 2>/dev/null"))
        assertTrue(cmd.contains("/etc/os-release 2>/dev/null || head -n 1 /etc/issue"))
    }

    // ---- CPU -----------------------------------------------------------------

    @Test
    fun `CPU 使用率来自两次采样的差值`() {
        // 总时间 +1000，其中 idle +800 → 20%
        assertEquals(0.2, snapshotOf(output()).cpu.total, 1e-9)
    }

    @Test
    fun `两次采样完全相同时使用率是 0 而不是 NaN`() {
        val snapshot = snapshotOf(output(cpu1 = stat1, cpu2 = stat1))
        assertEquals(0.0, snapshot.cpu.total, 1e-9)
        assertTrue(snapshot.cpu.cores.all { it == 0.0 })
    }

    @Test
    fun `每核使用率各算各的`() {
        val cores = snapshotOf(output()).cpu.cores
        assertEquals(2, cores.size)
        assertEquals(0.2, cores[0], 1e-9)
        assertEquals(0.4, cores[1], 1e-9)
    }

    @Test
    fun `核数就是 cpuN 行数——单核机器`() {
        val single = "cpu  100 0 100 800 0 0 0 0\ncpu0 100 0 100 800 0 0 0 0"
        val singleAfter = "cpu  200 0 200 1600 0 0 0 0\ncpu0 200 0 200 1600 0 0 0 0"
        val snapshot = snapshotOf(output(cpu1 = single, cpu2 = singleAfter))
        assertEquals(1, snapshot.cpu.coreCount)
    }

    @Test
    fun `核数就是 cpuN 行数——八核机器`() {
        fun stat(scale: Int) = buildString {
            append("cpu  ${100 * scale} 0 ${100 * scale} ${800 * scale} 0 0 0 0\n")
            repeat(8) { append("cpu$it ${10 * scale} 0 ${10 * scale} ${100 * scale} 0 0 0 0\n") }
        }
        val snapshot = snapshotOf(output(cpu1 = stat(1), cpu2 = stat(2)))
        assertEquals(8, snapshot.cpu.coreCount)
    }

    @Test
    fun `guest 字段不被重复计入`() {
        // guest / guest_nice 已经含在 user / nice 里，全加一遍会把使用率算低
        val before = "cpu  100 0 100 800 0 0 0 0 0 0"
        val after = "cpu  200 0 200 1600 0 0 0 0 500 500"
        assertEquals(0.2, snapshotOf(output(cpu1 = before, cpu2 = after)).cpu.total, 1e-9)
    }

    @Test
    fun `iowait 算作空闲`() {
        val before = "cpu  100 0 100 400 400 0 0 0"
        val after = "cpu  200 0 200 800 800 0 0 0"
        assertEquals(0.2, snapshotOf(output(cpu1 = before, cpu2 = after)).cpu.total, 1e-9)
    }

    @Test
    fun `第二次采样比第一次小时使用率取 0 而不是负数`() {
        // 容器重建、计数器重置都会出现这种输出
        val snapshot = snapshotOf(output(cpu1 = stat2, cpu2 = stat1))
        assertEquals(0.0, snapshot.cpu.total, 1e-9)
    }

    // ---- 内存 ----------------------------------------------------------------

    @Test
    fun `MemAvailable 优先`() {
        val memory = snapshotOf(output()).memory
        assertEquals(16_316_936L * 1024, memory.totalBytes)
        assertEquals(6_316_936L * 1024, memory.usedBytes)
    }

    @Test
    fun `没有 MemAvailable 时退化成 MemFree 加 Buffers 加 Cached`() {
        val legacy = meminfo.lines().filterNot { it.startsWith("MemAvailable") }.joinToString("\n")
        val memory = snapshotOf(output(mem = legacy)).memory
        // 16316936 - (200000 + 300000 + 5000000)
        assertEquals(10_816_936L * 1024, memory.usedBytes)
    }

    @Test
    fun `既没有 MemAvailable 也没有 MemFree 时判定畸形`() {
        val broken = meminfo.lines()
            .filterNot { it.startsWith("MemAvailable") || it.startsWith("MemFree") }
            .joinToString("\n")
        assertTrue(reasonOf(output(mem = broken)).contains("meminfo"))
    }

    @Test
    fun `swap 用量正常解析`() {
        val swap = snapshotOf(output()).swap
        assertNotNull(swap)
        assertEquals(2_097_148L * 1024, swap!!.totalBytes)
        assertEquals(1_000_000L * 1024, swap.usedBytes)
    }

    @Test
    fun `没有 swap 的机器 swap 为 null 而不是零占用`() {
        val noSwap = meminfo.replace("SwapTotal:       2097148 kB", "SwapTotal:             0 kB")
        assertNull(snapshotOf(output(mem = noSwap)).swap)
    }

    @Test
    fun `meminfo 里的畸形数字不抛异常也不污染其他字段`() {
        val dirty = meminfo.replace("Buffers:          300000 kB", "Buffers:          ??? kB")
        val memory = snapshotOf(output(mem = dirty)).memory
        assertEquals(16_316_936L * 1024, memory.totalBytes)
    }

    // ---- 磁盘 ----------------------------------------------------------------

    private val df = """
        Filesystem     1024-blocks      Used Available Capacity Mounted on
        /dev/sda1         41153856  20000000  20000000      50% /
        tmpfs              4028004         0   4028004       0% /dev/shm
        devtmpfs           4028004         0   4028004       0% /dev
        /dev/loop0             128       128         0     100% /snap/core/1
        overlay           41153856  30000000  10000000      75% /var/lib/docker/overlay2/x/merged
        /dev/sdb1          1048576    524288    524288      50% /mnt/my data
    """.trimIndent()

    @Test
    fun `df -P 的单行输出被正确解析`() {
        val disks = snapshotOf(output(disk = df)).disks
        val root = disks.first { it.mountPoint == "/" }
        assertEquals("/dev/sda1", root.device)
        assertEquals(41_153_856L * 1024, root.totalBytes)
        assertEquals(20_000_000L * 1024, root.usedBytes)
        assertEquals(20_000_000L * 1024, root.availableBytes)
    }

    @Test
    fun `伪文件系统被过滤掉`() {
        val mounts = snapshotOf(output(disk = df)).disks.map { it.mountPoint }
        assertEquals(listOf("/", "/mnt/my data"), mounts)
    }

    @Test
    fun `带空格的挂载点不会被截断`() {
        val disk = snapshotOf(output(disk = df)).disks.first { it.device == "/dev/sdb1" }
        assertEquals("/mnt/my data", disk.mountPoint)
    }

    @Test
    fun `磁盘占用率用 已用除以已用加可用，和 df 的 Capacity 口径一致`() {
        val disk = snapshotOf(output(disk = df)).disks.first { it.mountPoint == "/" }
        assertEquals(0.5f, disk.ratio, 1e-6f)
    }

    @Test
    fun `df 段缺失只是没有磁盘，不影响整次采集`() {
        assertTrue(snapshotOf(output(disk = null)).disks.isEmpty())
    }

    // ---- 网络 ----------------------------------------------------------------

    @Test
    fun `网卡速率按两次 uptime 的真实间隔算`() {
        // 间隔 2 秒，收字节 2000 → 6000
        val eth0 = snapshotOf(output()).interfaces.single()
        assertEquals("eth0", eth0.name)
        assertEquals(2000.0, eth0.rxBytesPerSecond, 1e-9)
        assertEquals(0.0, eth0.txBytesPerSecond, 1e-9)
    }

    @Test
    fun `lo 被过滤`() {
        assertTrue(snapshotOf(output()).interfaces.none { it.name == "lo" })
    }

    @Test
    fun `计数器回绕时速率按 0 处理而不是负数`() {
        val wrapped = net2.replace(
            "  eth0: 6000 60 0 0 0 0 0 0 3000 30 0 0 0 0 0 0",
            "  eth0: 100 1 0 0 0 0 0 0 200 2 0 0 0 0 0 0",
        )
        val eth0 = snapshotOf(output(net2 = wrapped)).interfaces.single()
        assertEquals(0.0, eth0.rxBytesPerSecond, 1e-9)
        assertEquals(0.0, eth0.txBytesPerSecond, 1e-9)
    }

    @Test
    fun `零流量网卡被过滤`() {
        val idle = "\n  veth9: 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0"
        val interfaces = snapshotOf(output(net1 = net1 + idle, net2 = net2 + idle)).interfaces
        assertEquals(listOf("eth0"), interfaces.map { it.name })
    }

    @Test
    fun `采样中途才出现的网卡不报速率`() {
        val appeared = net2 + "\n  eth1: 5000 50 0 0 0 0 0 0 5000 50 0 0 0 0 0 0"
        assertEquals(listOf("eth0"), snapshotOf(output(net2 = appeared)).interfaces.map { it.name })
    }

    @Test
    fun `两次 uptime 读不出来时退回名义间隔 1 秒`() {
        val eth0 = snapshotOf(output(uptime1 = "bogus", uptime2 = "1002.00 4004.00")).interfaces.single()
        assertEquals(4000.0, eth0.rxBytesPerSecond, 1e-9)
    }

    @Test
    fun `网卡段缺失只是没有网络数据`() {
        assertTrue(snapshotOf(output(net1 = null, net2 = null)).interfaces.isEmpty())
    }

    // ---- 虚拟网卡 -------------------------------------------------------------

    @Test
    fun `veth docker tun 这类被标成虚拟网卡`() {
        listOf("veth1a2b3c", "docker0", "br-9f8e7d", "virbr0", "tun0", "tailscale0", "wg0", "cni0")
            .forEach { assertTrue(it, HostFacts.isVirtualInterface(it)) }
    }

    @Test
    fun `物理网卡不会被误判成虚拟——包括裸网桥 br0`() {
        // br0 是 KVM 宿主机上唯一的对外通路，收起来等于把真实流量藏了
        listOf("eth0", "enp3s0", "ens18", "eno1", "wlan0", "wlp2s0", "bond0", "br0")
            .forEach { assertTrue(it, !HostFacts.isVirtualInterface(it)) }
    }

    @Test
    fun `虚拟标记落在 NetInterface 上，物理卡不受影响`() {
        val extra = "\n  docker0: 100 1 0 0 0 0 0 0 200 2 0 0 0 0 0 0"
        val later = "\n  docker0: 900 9 0 0 0 0 0 0 800 8 0 0 0 0 0 0"
        val interfaces = snapshotOf(output(net1 = net1 + extra, net2 = net2 + later)).interfaces
        assertEquals(2, interfaces.size)
        assertTrue(!interfaces.first { it.name == "eth0" }.virtual)
        assertTrue(interfaces.first { it.name == "docker0" }.virtual)
    }

    // ---- 本机 IP -------------------------------------------------------------

    @Test
    fun `ip -o -4 addr 的输出被解析成网卡地址`() {
        val addr = """
            2: eth0    inet 192.168.3.101/24 brd 192.168.3.255 scope global eth0\       valid_lft forever
            3: docker0    inet 172.17.0.1/16 brd 172.17.255.255 scope global docker0\       valid_lft forever
        """.trimIndent()
        val extra = "\n  docker0: 100 1 0 0 0 0 0 0 200 2 0 0 0 0 0 0"
        val later = "\n  docker0: 900 9 0 0 0 0 0 0 800 8 0 0 0 0 0 0"
        val snapshot = snapshotOf(output(addr = addr, net1 = net1 + extra, net2 = net2 + later))

        assertEquals("192.168.3.101", snapshot.interfaces.first { it.name == "eth0" }.address)
        assertEquals("172.17.0.1", snapshot.interfaces.first { it.name == "docker0" }.address)
        // 概览那一行只留物理网卡的地址：docker0 的网关地址不是「这台机器的 IP」
        assertEquals(listOf("192.168.3.101"), snapshot.addresses)
    }

    @Test
    fun `没有 iproute2 时退化成 hostname -I，全部地址都留着`() {
        // 这条路径拿不到网卡名，也就分不出虚拟网卡——宁可多显示一个也不猜着删
        val snapshot = snapshotOf(output(addr = "192.168.3.101 172.17.0.1 "))
        assertEquals(listOf("192.168.3.101", "172.17.0.1"), snapshot.addresses)
        assertNull(snapshot.interfaces.single().address)
    }

    @Test
    fun `地址段缺失只是没有 IP，不影响整次采集`() {
        val snapshot = snapshotOf(output(addr = null))
        assertTrue(snapshot.addresses.isEmpty())
        assertEquals(0.2, snapshot.cpu.total, 1e-9)
    }

    @Test
    fun `同一张网卡的第二个地址不覆盖第一个`() {
        val addr = """
            2: eth0    inet 10.0.0.5/24 brd 10.0.0.255 scope global eth0\       valid_lft forever
            2: eth0    inet 10.0.0.9/24 brd 10.0.0.255 scope global secondary eth0\       valid_lft forever
        """.trimIndent()
        assertEquals("10.0.0.5", snapshotOf(output(addr = addr)).interfaces.single().address)
    }

    // ---- 公网 IP -------------------------------------------------------------

    @Test
    fun `公网 IP 单独一条命令，不在主采集里`() {
        assertTrue(HostFacts.PUBLIC_IP_COMMAND.contains("curl"))
        assertTrue(HostFacts.PUBLIC_IP_COMMAND.contains("--max-time"))
        // 塞进主采集等于每 5 秒拖一次外网请求
        assertTrue(!HostFacts.PROBE_COMMAND.contains("ifconfig.me"))
    }

    @Test
    fun `公网 IP 认 v4 也认 v6`() {
        assertEquals("203.0.113.7", HostFacts.parsePublicAddress("203.0.113.7\n"))
        assertEquals("2001:db8::1", HostFacts.parsePublicAddress(" 2001:db8::1 \n"))
    }

    @Test
    fun `劫持页和错误响应不会被当成公网 IP`() {
        // curl 对这些照样 0 退出，把 HTML 当 IP 显示比显示「不可用」糟得多
        assertNull(HostFacts.parsePublicAddress("<html><body>404</body></html>"))
        assertNull(HostFacts.parsePublicAddress("{\"error\":\"rate limited\"}"))
        assertNull(HostFacts.parsePublicAddress(""))
        assertNull(HostFacts.parsePublicAddress("999.1.1.1"))
        assertNull(HostFacts.parsePublicAddress("curl: (6) Could not resolve host"))
    }

    // ---- 进程 ----------------------------------------------------------------

    @Test
    fun `进程 Top 正常解析，表头被丢弃`() {
        val ps = """
              PID %CPU %MEM COMMAND
                1  0.5  0.1 systemd
             1234 12.3  4.5 java
        """.trimIndent()
        val processes = snapshotOf(output(ps = ps)).processes
        assertEquals(2, processes.size)
        assertEquals(1234, processes[1].pid)
        assertEquals(12.3, processes[1].cpuPercent, 1e-9)
        assertEquals(4.5, processes[1].memPercent, 1e-9)
        assertEquals("java", processes[1].command)
    }

    @Test
    fun `busybox 的 ps 采不到时整次采集仍然成功`() {
        // busybox 不认 -eo，那一段就是空的——不能因此判定整次采集失败
        val snapshot = snapshotOf(output(ps = ""))
        assertTrue(snapshot.processes.isEmpty())
        assertEquals(0.2, snapshot.cpu.total, 1e-9)
    }

    @Test
    fun `ps 报错信息不会被当成进程`() {
        val ps = "ps: unrecognized option: e\nBusyBox v1.36.1 multi-call binary."
        assertTrue(snapshotOf(output(ps = ps)).processes.isEmpty())
    }

    @Test
    fun `两路 ps 的重叠进程按 pid 去重`() {
        // CPU 榜和内存榜都会列出 java，合起来必须只剩一条
        val ps = """
              PID %CPU %MEM COMMAND
             1234 12.3  4.5 java
                1  0.5  0.1 systemd
              PID %CPU %MEM COMMAND
             1234 12.3  4.5 java
             2222  0.0 30.0 postgres
        """.trimIndent()
        val processes = snapshotOf(output(ps = ps)).processes
        assertEquals(3, processes.size)
        assertEquals(listOf(1234, 1, 2222), processes.map { it.pid })
    }

    @Test
    fun `采集命令按 CPU 与内存各发一路 ps`() {
        // 少了内存那一路，UI 上「按内存排序」就只能在 CPU 前几名里排，排出来是错的
        assertTrue(HostFacts.PROBE_COMMAND.contains("--sort=-pcpu"))
        assertTrue(HostFacts.PROBE_COMMAND.contains("--sort=-pmem"))
    }

    // ---- GPU -----------------------------------------------------------------

    @Test
    fun `nvidia-smi 的一行拆成占用、显存与温度`() {
        val gpu = "37, 2048, 24564, 61, NVIDIA GeForce RTX 4090"
        val card = snapshotOf(output(gpu = gpu)).gpus.single()
        assertEquals("NVIDIA GeForce RTX 4090", card.name)
        assertEquals(0.37, card.utilization!!, 1e-9)
        // 命令给的是 MiB，模型里统一存 byte
        assertEquals(2048L * 1024 * 1024, card.memoryUsedBytes)
        assertEquals(24564L * 1024 * 1024, card.memoryTotalBytes)
        assertEquals(61, card.temperatureCelsius)
        assertEquals(2048.0 / 24564, card.memoryRatio!!.toDouble(), 1e-6)
    }

    @Test
    fun `显卡型号里的逗号不会把行切散`() {
        // 名字放行尾就是为了这个：OEM 命名里带逗号的卡不少
        val card = snapshotOf(output(gpu = "0, 1, 2, 3, NVIDIA RTX A4000, Ada Generation")).gpus.single()
        assertEquals("NVIDIA RTX A4000, Ada Generation", card.name)
    }

    @Test
    fun `多卡按 nvidia-smi 的顺序列出，顺序就是卡的编号`() {
        val gpu = """
            10, 100, 8192, 40, Tesla T4
            90, 7000, 8192, 78, Tesla T4
        """.trimIndent()
        val gpus = snapshotOf(output(gpu = gpu)).gpus
        assertEquals(2, gpus.size)
        assertEquals(0.1, gpus[0].utilization!!, 1e-9)
        assertEquals(0.9, gpus[1].utilization!!, 1e-9)
    }

    @Test
    fun `读不出来的字段是 null 而不是 0`() {
        // 直通给虚拟机的卡报不出 utilization，老驱动报不出温度——0% 会被当成「这张卡闲着」
        val card = snapshotOf(output(gpu = "[N/A], 512, 16384, [N/A], NVIDIA A100-SXM4")).gpus.single()
        assertNull(card.utilization)
        assertNull(card.temperatureCelsius)
        assertEquals(512L * 1024 * 1024, card.memoryUsedBytes)
    }

    @Test
    fun `AMD 的 sysfs 那路输出同一种行`() {
        // 换算在 shell 里就做掉了，解析这边两条路没有分支
        val card = snapshotOf(output(gpu = "45, 512, 8176, 47, card0 amdgpu")).gpus.single()
        assertEquals("card0 amdgpu", card.name)
        assertEquals(0.45, card.utilization!!, 1e-9)
        assertEquals(47, card.temperatureCelsius)
    }

    @Test
    fun `sysfs 那路读不到显存时是 null，不是 0 字节`() {
        // shell 里留的是空字段。补 0 会画出一条「显存没被占用」的空条
        val card = snapshotOf(output(gpu = "45, , , 47, card0 amdgpu")).gpus.single()
        assertNull(card.memoryUsedBytes)
        assertNull(card.memoryRatio)
        assertEquals(0.45, card.utilization!!, 1e-9)
    }

    @Test
    fun `字段不够或名字为空的行整行丢掉`() {
        val gpu = """
            45, 512, 8176, 47
            45, 512, 8176, 47,
            NVIDIA-SMI has failed because it couldn't communicate with the driver
        """.trimIndent()
        assertTrue(snapshotOf(output(gpu = gpu)).gpus.isEmpty())
    }

    @Test
    fun `没有 GPU 的机器整次采集仍然成功`() {
        val snapshot = snapshotOf(output(gpu = ""))
        assertTrue(snapshot.gpus.isEmpty())
        assertEquals(0.2, snapshot.cpu.total, 1e-9)
    }

    @Test
    fun `GPU 命令套了 timeout，名字放在查询串行尾`() {
        val cmd = HostFacts.PROBE_COMMAND
        assertTrue(cmd.contains("--query-gpu=utilization.gpu,memory.used,memory.total,temperature.gpu,name"))
        // 驱动挂掉时 nvidia-smi 能吊在 ioctl 上几十秒，它和 CPU、内存共用同一条 exec
        assertTrue(cmd.contains("timeout 4 nvidia-smi"))
        // card* 会扫到 card0-DP-1 这类连接器目录，同一块卡列好几遍
        assertTrue(!cmd.contains("/sys/class/drm/card*"))
        assertTrue(cmd.contains("/sys/class/drm/card[0-9]"))
        // 和其余静态指标一样排在第二次采样之后，不能拉长采样间隔
        assertTrue(cmd.indexOf(HostFacts.MARKER_CPU2) < cmd.indexOf(HostFacts.MARKER_GPU))
    }

    @Test
    fun `通配符前先关掉 zsh 的 NOMATCH，否则没有独显的机器整页采集都会断`() {
        val cmd = HostFacts.PROBE_COMMAND
        // zsh 匹配不到通配符时报错并中止整条命令，__LM_END__ 吐不出来，用户看到的是「采集失败」
        assertTrue(cmd.contains("setopt no_nomatch"))
        // sh / bash 没有 setopt 这个内建，必须兜住 command not found
        assertTrue(cmd.contains("setopt no_nomatch 2>/dev/null || true"))
        // 关闭动作必须排在所有通配符之前才有意义
        assertTrue(cmd.indexOf("setopt no_nomatch") < cmd.indexOf("/sys/class/drm/card[0-9]"))
        assertTrue(cmd.indexOf("setopt no_nomatch") < cmd.indexOf("hwmon*"))
    }

    // ---- 容器 ----------------------------------------------------------------

    @Test
    fun `容器按 竖线 分三段解析，运行中的排在前面`() {
        val docker = """
            db|postgres:16-alpine|Exited (0) 2 hours ago
            web|nginx:1.25|Up 3 days (healthy)
        """.trimIndent()
        val containers = snapshotOf(output(docker = docker)).containers
        assertEquals(2, containers.size)
        assertEquals("web", containers[0].name)
        assertTrue(containers[0].running)
        // 带空格和括号的状态串按空白切会散架，必须整段留下
        assertEquals("Up 3 days (healthy)", containers[0].status)
        assertEquals("db", containers[1].name)
        assertTrue(!containers[1].running)
        assertEquals("postgres:16-alpine", containers[1].image)
    }

    @Test
    fun `镜像名里的冒号和斜杠不影响分段`() {
        val docker = "gitea|registry.example.com:5000/org/gitea:1.22.1-rootless|Up 6 weeks"
        val containers = snapshotOf(output(docker = docker)).containers
        assertEquals(1, containers.size)
        assertEquals("registry.example.com:5000/org/gitea:1.22.1-rootless", containers[0].image)
        assertEquals("Up 6 weeks", containers[0].status)
    }

    @Test
    fun `同为运行中时按名字排，顺序不随 docker 的创建时间漂`() {
        val docker = """
            zoo|a:1|Up 1 hour
            app|b:1|Up 2 hours
        """.trimIndent()
        val names = snapshotOf(output(docker = docker)).containers.map { it.name }
        assertEquals(listOf("app", "zoo"), names)
    }

    @Test
    fun `没装 docker 或没权限时整次采集仍然成功`() {
        // 两种情况在这里长得一样：命令 not found 与 permission denied 的 stderr 都被丢弃了
        val snapshot = snapshotOf(output(docker = ""))
        assertTrue(snapshot.containers.isEmpty())
        assertEquals(0.2, snapshot.cpu.total, 1e-9)
    }

    // ---- 容器资源占用 ---------------------------------------------------------

    @Test
    fun `docker stats 按名字并到容器上`() {
        val docker = """
            db|postgres:16-alpine|Exited (0) 2 hours ago
            web|nginx:1.25|Up 3 days (healthy)
        """.trimIndent()
        val stats = "web|1.23%|25.14MiB / 15.6GiB|0.16%"
        val containers = snapshotOf(output(docker = docker, stats = stats)).containers

        val web = containers.first { it.name == "web" }
        assertEquals(1.23, web.cpuPercent!!, 1e-9)
        // 斜杠后面是宿主机总内存，每行都一样，窄屏上纯属占地方
        assertEquals("25.14MiB", web.memoryUsed)
        assertEquals(0.16, web.memoryPercent!!, 1e-9)
        assertTrue(web.hasStats)

        // 停掉的容器根本不在 stats 输出里——补个 0 会让人以为它还在跑
        val db = containers.first { it.name == "db" }
        assertNull(db.cpuPercent)
        assertNull(db.memoryUsed)
        assertTrue(!db.hasStats)
    }

    @Test
    fun `stats 超时或整段缺失时容器列表照常显示`() {
        // stats 排在 ps 之后就是为了这个：贵的那步挂了，至少还剩一份容器列表
        val containers = snapshotOf(output(docker = "web|nginx:1.25|Up 3 days", stats = null)).containers
        assertEquals(1, containers.size)
        assertEquals("web", containers[0].name)
        assertTrue(!containers[0].hasStats)
    }

    @Test
    fun `刚起来还没采到样的容器读数是 null 而不是 0`() {
        val stats = "web|--|-- / --|--"
        val web = snapshotOf(output(docker = "web|nginx:1.25|Up 1 second", stats = stats))
            .containers.single()
        assertNull(web.cpuPercent)
        assertNull(web.memoryPercent)
    }

    @Test
    fun `stats 的表头行不会变成一个幽灵容器`() {
        val stats = "NAME|CPU %|MEM USAGE / LIMIT|MEM %\nweb|0.50%|10MiB / 1GiB|1.00%"
        val containers = snapshotOf(output(docker = "web|nginx:1.25|Up 3 days", stats = stats)).containers
        assertEquals(1, containers.size)
        assertEquals(0.5, containers[0].cpuPercent!!, 1e-9)
    }

    @Test
    fun `采集命令里 stats 排在 ps 之后且各自带 timeout`() {
        val ps = HostFacts.PROBE_COMMAND.indexOf("ps -a --format")
        val stats = HostFacts.PROBE_COMMAND.indexOf("stats --no-stream")
        assertTrue(ps in 1..<stats)
        assertTrue(HostFacts.PROBE_COMMAND.contains("timeout 4"))
        // dockerd 卡死时没有 timeout 会把整页监控一起拖到超时
        assertTrue(HostFacts.PROBE_COMMAND.contains("timeout 6"))
    }

    @Test
    fun `docker 的报错信息不会被当成容器`() {
        val docker = "Cannot connect to the Docker daemon at unix:///var/run/docker.sock."
        assertTrue(snapshotOf(output(docker = docker)).containers.isEmpty())
    }

    @Test
    fun `容器命令套了 timeout，卡死的守护进程拖不垮整次采集`() {
        val cmd = HostFacts.PROBE_COMMAND
        assertTrue(cmd.contains("timeout 4"))
        assertTrue(cmd.contains("{{.Names}}|{{.Image}}|{{.Status}}"))
        // 状态字段用 Status 首词判，不用 20.10 才有的 State——老 docker 上模板会整条报错
        assertTrue(!cmd.contains("{{.State}}"))
        // 容器段排在第二次采样之后，不能夹在两组采样中间拉长采样间隔
        assertTrue(cmd.indexOf(HostFacts.MARKER_CPU2) < cmd.indexOf(HostFacts.MARKER_DOCKER))
    }

    // ---- 系统信息 -------------------------------------------------------------

    @Test
    fun `PRETTY_NAME 的引号被去掉`() {
        val info = snapshotOf(output()).system
        assertEquals("Ubuntu 24.04.1 LTS", info.distro)
        assertEquals("Linux 6.8.0-45-generic", info.kernel)
        assertEquals("gz3", info.hostname)
    }

    @Test
    fun `没有 os-release 时退化到 etc issue，并清掉 agetty 转义`() {
        val fallback = """
            ${HostFacts.PREFIX_KERNEL}Linux 5.10.0
            ${HostFacts.PREFIX_HOSTNAME}box
            Debian GNU/Linux 11 \n \l
        """.trimIndent()
        assertEquals("Debian GNU/Linux 11", snapshotOf(output(system = fallback)).system.distro)
    }

    @Test
    fun `系统信息缺失时是 null 而不是空串`() {
        val empty = "${HostFacts.PREFIX_KERNEL}\n${HostFacts.PREFIX_HOSTNAME}"
        val info = snapshotOf(output(system = empty)).system
        assertNull(info.kernel)
        assertNull(info.hostname)
        assertNull(info.distro)
    }

    // ---- 必需段与失败分支 ------------------------------------------------------

    @Test
    fun `没有 proc stat 的主机返回 Unsupported`() {
        // macOS / BSD：首版明确只支持 Linux，页面要如实说「暂不支持」而不是「读取失败」
        assertEquals(FactsResult.Unsupported, HostFacts.parse(output(procfs = "0")))
    }

    @Test
    fun `缺少 procfs 哨兵判定畸形`() {
        assertTrue(reasonOf(output(procfs = null)).contains(HostFacts.MARKER_PROCFS))
    }

    @Test
    fun `procfs 哨兵不是 0 也不是 1 时判定畸形`() {
        assertTrue(reasonOf(output(procfs = "yes")).contains("procfs"))
    }

    @Test
    fun `输出被截断（没有结束哨兵）判定畸形`() {
        assertTrue(reasonOf(output(end = false)).contains(HostFacts.MARKER_END))
    }

    @Test
    fun `缺少 meminfo 段判定畸形`() {
        assertTrue(reasonOf(output(mem = null)).contains("meminfo"))
    }

    @Test
    fun `缺少 loadavg 段判定畸形`() {
        assertTrue(reasonOf(output(load = null)).contains("loadavg"))
    }

    @Test
    fun `缺少第二次 stat 采样判定畸形`() {
        assertTrue(reasonOf(output(cpu2 = null)).contains("stat"))
    }

    @Test
    fun `缺少 uptime 判定畸形`() {
        assertTrue(reasonOf(output(uptime1 = null, uptime2 = null)).contains("uptime"))
    }

    @Test
    fun `stat 行里的畸形数字不抛异常，只是判定畸形`() {
        val dirty = "cpu  100 x 100 800 0 0 0 0\ncpu0 50 0 50 400 0 0 0 0"
        assertTrue(reasonOf(output(cpu1 = dirty)).contains("stat"))
    }

    @Test
    fun `loadavg 里的畸形数字不抛异常`() {
        assertTrue(reasonOf(output(load = "0.52 nan-ish")).contains("loadavg"))
    }

    @Test
    fun `远端 shell 的 motd 噪音不影响解析`() {
        val snapshot = snapshotOf(output(prefix = "Welcome to Ubuntu\nLast login: Mon\n"))
        assertEquals(0.2, snapshot.cpu.total, 1e-9)
    }

    @Test
    fun `行尾的回车不影响解析`() {
        val crlf = output().replace("\n", "\r\n")
        assertEquals(0.2, snapshotOf(crlf).cpu.total, 1e-9)
    }

    // ---- 其余读数 -------------------------------------------------------------

    @Test
    fun `负载与运行时长正常解析`() {
        val snapshot = snapshotOf(output())
        assertEquals(0.52, snapshot.load.one, 1e-9)
        assertEquals(0.31, snapshot.load.five, 1e-9)
        assertEquals(0.20, snapshot.load.fifteen, 1e-9)
        // uptime 取第二次采样的值
        assertEquals(1002L, snapshot.uptimeSeconds)
    }

    @Test
    fun `运行时长按天时分拆分`() {
        assertEquals(Uptime.Parts(3, 4, 5), Uptime.of(3 * 86_400L + 4 * 3_600L + 5 * 60L + 30L))
        assertEquals(Uptime.Parts(0, 0, 0), Uptime.of(-1))
    }

    @Test
    fun `字节数按 1024 进制显示`() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KiB", formatBytes(1024))
        assertEquals("1.5 GiB", formatBytes(1536L * 1024 * 1024))
        assertEquals("0 B", formatBytes(-1))
        assertEquals("1.0 MiB/s", formatRate(1024.0 * 1024))
    }

    @Test
    fun `百分比保留一位小数`() {
        assertEquals("12.3", formatPercent(0.1234))
        assertEquals("100.0", formatPercent(1.0))
    }
}
