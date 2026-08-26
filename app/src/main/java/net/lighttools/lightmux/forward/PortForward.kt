package net.lighttools.lightmux.forward

import java.util.UUID

/**
 * 一条本地端口转发（等价于 `ssh -L`）。
 *
 * 手机上监听 [localPort]，进来的连接经 SSH 隧道送到**服务器视角**的 [remoteHost]:[remotePort]。
 * 于是「服务端起了个 3000 端口」在手机浏览器里就是 `http://127.0.0.1:3000`。
 */
data class ForwardSpec(
    val id: String = UUID.randomUUID().toString(),
    val hostId: String,
    val remotePort: Int,
    val localPort: Int,
    /**
     * 目标地址，**从服务器上看出去**。
     *
     * 默认 `localhost` 覆盖绝大多数场景；填成内网别的机器（如 `192.168.1.5`）时，
     * 这台主机就成了跳板——`ssh -L` 本来就有这个能力，不额外做什么就能支持。
     */
    val remoteHost: String = DEFAULT_REMOTE_HOST,
    /** 给用户看的名字，探测来的转发默认填进程名。 */
    val label: String? = null,
) {

    /** 手机浏览器里该敲的地址。转发只绑环回口，所以固定是 [LOOPBACK]。 */
    val localUrl: String get() = "http://$LOOPBACK:$localPort"

    companion object {
        const val DEFAULT_REMOTE_HOST = "localhost"

        /**
         * 本地监听地址，**IPv4 环回口**。
         *
         * 绑端口用的地址和这里拼出去的 URL 必须是同一个，所以做成一个常量给两边共用。
         * 0.1.31 之前不是这样：`bind()` 用 `InetAddress.getLoopbackAddress()`，
         * 而它在 Android 上返回的是 IPv6 的 `::1`（libcore 里写死 `Inet6Address.LOOPBACK`，
         * 与桌面 JVM 不同），于是端口绑在 `[::1]` 上、URL 指向 `127.0.0.1`——
         * 两边错开一个协议栈。这个 bug 尤其难查：绑得上，所以不抛 `BindException`、
         * 状态照样翻成「转发中」，用户看到一切正常，浏览器却始终「拒绝连接」。
         *
         * 选 IPv4 而不是 IPv6：手机浏览器敲 `127.0.0.1` 走的就是它，
         * 而 IPv6 字面量放进 URL 还得套方括号。
         */
        const val LOOPBACK = "127.0.0.1"
    }
}

/** 端口填得不对的原因。UI 拿它去查文案，逻辑层不碰字符串资源。 */
enum class PortError {
    /** 不在 1..65535 里 */
    OutOfRange,

    /** 低于 1024，Android 上非 root 应用绑不了 */
    Privileged,
}

/**
 * 本地端口该怎么挑。
 *
 * 单独抽出来是因为这里全是「凭什么是这个数」的判断，而它恰好是纯逻辑、能自测。
 */
object ForwardRules {

    const val MAX_PORT = 65535

    /**
     * Android 上非 root 应用绑不了特权端口，远端的 80 / 443 只能落到高位口。
     *
     * 这条限制没法绕开，只能在 UI 上提前把用户引开——等 `bind()` 抛 `Permission denied`
     * 再报错，用户只会以为是 app 坏了。
     */
    const val MIN_LOCAL_PORT = 1024

    /** 挑不出同号口时的兜底起点。 */
    private const val FALLBACK_BASE = 10000

    /**
     * 特权端口的习惯映射：80 → 8080、443 → 8443、22 → 8022。
     *
     * 用固定偏移而不是顺序分配，是为了让本地端口**看得出出处**：
     * 8080 一眼就知道对着远端的 80，随机给个 10001 就得回头查列表。
     */
    private const val PRIVILEGED_OFFSET = 8000

    /**
     * 给远端端口配一个本地端口。
     *
     * 首选同号——「远端 3000 就在本地 3000」是最省心智的映射，用户不用记两个数。
     *
     * @param taken 已经被别的转发占掉的本地端口
     */
    fun suggestLocalPort(remotePort: Int, taken: Set<Int> = emptySet()): Int {
        val preferred =
            if (remotePort >= MIN_LOCAL_PORT) remotePort else remotePort + PRIVILEGED_OFFSET
        if (preferred in MIN_LOCAL_PORT..MAX_PORT && preferred !in taken) return preferred
        // 全占满是不可能的（taken 是用户自己配的那几条），但不留兜底就得让调用方处理异常。
        return (FALLBACK_BASE..MAX_PORT).firstOrNull { it !in taken } ?: preferred
    }

    /** 返回 null 表示这个本地端口没问题。 */
    fun validateLocalPort(port: Int): PortError? = when {
        port !in 1..MAX_PORT -> PortError.OutOfRange
        port < MIN_LOCAL_PORT -> PortError.Privileged
        else -> null
    }

    /** 远端端口没有特权口限制——转发到远端的 80 完全正常，受限的只有手机这一侧。 */
    fun validateRemotePort(port: Int): PortError? =
        if (port in 1..MAX_PORT) null else PortError.OutOfRange
}
