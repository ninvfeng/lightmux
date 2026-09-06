package net.lighttools.lightmux.forward

/**
 * 远端一个正在监听的 TCP 端口。
 *
 * @param address 监听地址原文（`127.0.0.1` / `0.0.0.0` / `*` / `::1`）
 * @param process 进程名；探不到就是 null——非 root 用户看不见别人的进程，这很正常
 */
data class ListeningPort(
    val port: Int,
    val address: String,
    val process: String? = null,
) {

    /**
     * 只绑在环回地址上——**这类端口才是端口转发的正主**。
     *
     * 绑 `0.0.0.0` 的服务，手机浏览器直接敲服务器 IP 就能到，用不着转发；
     * 只绑 `127.0.0.1` 的（开发服务器、Redis、各种管理面板）除了转发没有第二条路。
     * 列表把它们排在前面，用户一眼看到的就是真正需要转发的那几个。
     */
    val loopbackOnly: Boolean
        get() = address == "::1" || address.startsWith("127.")
}

/**
 * 解析远端 `ss` / `netstat` 的监听端口列表。
 *
 * 纯逻辑、无 Android 依赖，理由和 [net.lighttools.lightmux.tmux.Tmux] 一样：
 * 本机跑不了真机，能自测的只有这部分（见 PRD §6.3）。
 */
object ListeningPorts {

    /**
     * 一次探完。
     *
     * `ss` 是现在的标配，但精简容器里常常只有 `netstat`，两个都没有的机器就返回空——
     * 探测失败不该拦住用户，手填端口那条路一直开着。
     *
     * `-n` 不做端口名反查（省掉一次 `/etc/services` 查表，也避免把 `6379` 显示成 `redis`
     * 之后我们还要反解回数字）；`-p` 拿进程名，非 root 时它只会少给几行，不会报错。
     *
     * `PATH` 要补 `/usr/sbin`、`/sbin`：exec channel 是非交互 shell，不读 `.bashrc`，
     * 而这两个网络工具正装在那儿——非 root 用户的默认 PATH 里往往没有它们，
     * 结果不是报错而是「一个监听端口都没有」，比报错更难查。
     */
    const val PROBE_COMMAND =
        "export PATH=\"\$PATH:/usr/sbin:/sbin\"; ss -tlnp 2>/dev/null || netstat -tlnp 2>/dev/null"

    private val WHITESPACE = Regex("\\s+")

    /** ss 的进程列：`users:(("redis-server",pid=123,fd=6))` */
    private val SS_PROCESS = Regex("""users:\(\("([^"]+)"""")

    /** netstat 的进程列：`123/redis-server`；拿不到权限时那里是个 `-`，正则自然不匹配。 */
    private val NETSTAT_PROCESS = Regex("""(?:^|\s)\d+/(\S+)""")

    /**
     * 解析出所有监听中的端口，按「该不该转发」排序。
     *
     * 解析不出任何一行时返回空列表——这里和 tmux 那边的 `Malformed` 纪律不同是有意的：
     * 空列表在这里不会骗人，UI 只是退回手填端口，而不是宣称「你没有会话」。
     */
    fun parse(raw: String): List<ListeningPort> {
        val found = LinkedHashMap<Int, ListeningPort>()
        raw.lineSequence().forEach { line ->
            // 两种工具的表头、列序都不同，但监听行一定带 LISTEN，用它当唯一的入口条件。
            if (!line.contains("LISTEN")) return@forEach
            val entry = parseLine(line) ?: return@forEach
            // 同一个端口 IPv4 / IPv6 各监听一次是常态（`tcp` 与 `tcp6` 两行），
            // 对转发来说是同一个目标，只留一条；通配地址要盖掉环回地址——
            // 决定「用不用得着转发」的是最宽的那个绑定。
            val prev = found[entry.port]
            if (prev == null || (prev.loopbackOnly && !entry.loopbackOnly)) found[entry.port] = entry
        }
        return found.values.sortedWith(
            compareByDescending<ListeningPort> { it.loopbackOnly }.thenBy { it.port }
        )
    }

    private fun parseLine(line: String): ListeningPort? {
        val fields = line.trim().split(WHITESPACE)
        // 本地地址是行里第一个「地址:端口」形态的字段。ss 与 netstat 的列序不一样，
        // 但两者的**对端**地址一律是 `*:*` / `0.0.0.0:*`，端口位不是数字，不会被认成本地地址；
        // 前面的 Recv-Q / Send-Q 是纯数字，不含冒号，同样不会误配。
        val (address, port) = fields.firstNotNullOfOrNull(::splitHostPort) ?: return null
        return ListeningPort(port = port, address = address, process = processName(line))
    }

    /** `127.0.0.1:6379` / `*:80` / `[::1]:11434` / `0.0.0.0:22` → 地址 + 端口 */
    private fun splitHostPort(token: String): Pair<String, Int>? {
        // IPv6 地址自己带一堆冒号，端口只能从最后一个冒号切。
        val sep = token.lastIndexOf(':')
        if (sep <= 0 || sep == token.length - 1) return null
        val port = token.substring(sep + 1).toIntOrNull() ?: return null
        if (port !in 1..ForwardRules.MAX_PORT) return null
        return token.substring(0, sep).removeSurrounding("[", "]") to port
    }

    private fun processName(line: String): String? =
        SS_PROCESS.find(line)?.groupValues?.getOrNull(1)
            ?: NETSTAT_PROCESS.find(line)?.groupValues?.getOrNull(1)
}
