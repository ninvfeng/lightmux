package net.lighttools.lightmux.monitor

import net.lighttools.lightmux.data.Host
import net.lighttools.lightmux.ssh.ExecPool

/**
 * 监控采集的调度：发一条 exec，把回来的字符串交给 [HostFacts]。
 *
 * **不做解析**——解析全在 [HostFacts] 里，那边零 Android 依赖、可单测。这里只剩 IO。
 * 连接复用与按主机串行由 [ExecPool] 统一负责，和 tmux 侧通道共用同一个池。
 */
class MonitorRepository(private val pool: ExecPool) {

    /**
     * 采一次。
     *
     * @throws java.io.IOException 连不上 / 超时。输出层面的失败走 [FactsResult]，不抛
     */
    suspend fun probe(host: Host): FactsResult = pool.withConnection(host) { connection ->
        HostFacts.parse(connection.exec(HostFacts.PROBE_COMMAND, PROBE_TIMEOUT_MS).stdout)
    }

    /**
     * 首屏快采：只要瞬时可得的那几项（不含 CPU / 网卡速率，见 [HostFacts.QUICK_COMMAND]），
     * 供骨架屏尽快填上真实数据。调用方只在还没有任何快照时发这一条，见
     * [net.lighttools.lightmux.ui.monitor.MonitorViewModel.collect]。
     *
     * @throws java.io.IOException 连不上 / 超时
     */
    suspend fun probeQuick(host: Host): FactsResult = pool.withConnection(host) { connection ->
        HostFacts.parseQuick(connection.exec(HostFacts.QUICK_COMMAND, QUICK_TIMEOUT_MS).stdout)
    }

    /**
     * 采一次公网 IP。
     *
     * 和 [probe] 分开发，是因为它要走外网、慢且几乎不变（见 [HostFacts.PUBLIC_IP_COMMAND]）。
     * 代价是它会在 [ExecPool] 的主机锁上排队，把下一轮采集推迟最多几秒——
     * 所以调用方只在进页面时叫一次，不能塞进轮询里。
     *
     * @return null 表示这台机器出不去网、没有 curl/wget，或者拿回来的不是个 IP
     */
    suspend fun probePublicIp(host: Host): String? = pool.withConnection(host) { connection ->
        HostFacts.parsePublicAddress(connection.exec(HostFacts.PUBLIC_IP_COMMAND, PUBLIC_IP_TIMEOUT_MS).stdout)
    }

    /** 离开监控页时放掉为它拨的连接；复用终端会话的那条不在池子里，关不到。 */
    suspend fun release(hostId: String) = pool.release(hostId)

    private companion object {
        /**
         * 命令里夹着 `sleep 1`，加上两次 `cat /proc/stat`、`df`，以及最贵的 `docker stats --no-stream`
         * （守护进程侧要采一轮 cgroup，自带 6 秒 timeout），比 tmux 探测宽松一大截。
         */
        const val PROBE_TIMEOUT_MS = 18_000L

        /** 快采只读 `/proc` 与 `df`，外加一路 `ps`——远比全量命令轻，但连接慢的主机上仍要留够余量。 */
        const val QUICK_TIMEOUT_MS = 8_000L

        /** 两个端点各 `--max-time 3`，封顶 6 秒；留两秒给 shell 启动和收尾。 */
        const val PUBLIC_IP_TIMEOUT_MS = 8_000L
    }
}
