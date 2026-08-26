package net.lighttools.lightmux.forward

/**
 * 终端输出里嗅到的一个「可以直接转发」的地址。
 *
 * @param target 转发目标，**服务器视角**。环回与通配地址一律归一成 `localhost`——
 *               `0.0.0.0` 是「监听在所有网卡上」，拿它当 `ssh -L` 的目的地址是没意义的
 * @param path   URL 的路径与查询串。转发起来之后要拼进本地地址一起打开：
 *               Jupyter 那种带 `?token=` 的地址少了它，开出来就是一个登录页
 */
data class PortHint(
    val port: Int,
    val target: String = ForwardSpec.DEFAULT_REMOTE_HOST,
    val path: String = "",
) {

    /** 提示条上显示的地址。 */
    val display: String get() = "$target:$port"
}

/**
 * 从终端输出里认出「地址 + 端口」，用来提示用户「这个要不要转发」。
 *
 * 只认三类主机：`localhost`、IPv4 字面量、方括号里的 IPv6。**不认域名**——
 * `github.com:443` 这种匹配上了也没有转发的意义，反倒会把提示条变成噪音。
 *
 * 纯逻辑、无 Android 依赖，理由同 [ListeningPorts]：本机跑不了真机，能自测的只有这部分。
 */
object PortSniffer {

    /**
     * 一条地址的形状：`[协议://]主机:端口[/路径]`。
     *
     * - 协议可有可无，且不限于 http：`redis://127.0.0.1:6379` 与裸的 `127.0.0.1:6379` 都要认。
     * - 前置的否定环视挡住「更长的词的一部分」：`1.2.3.4.5:80`、`user@127.0.0.1:22`
     *   这类命中的是半截，转出来毫无意义。
     * - 端口后面跟着数字就不算命中（`:123456`），否则会截出一个碰巧合法的 `12345`。
     */
    private val ADDRESS = Regex(
        """(?<![\w.:/@-])(?:[a-zA-Z][a-zA-Z0-9+.-]*://)?""" +
            """(localhost|\d{1,3}(?:\.\d{1,3}){3}|\[[0-9A-Fa-f:]{2,45}])""" +
            """:(\d{1,5})(?!\d)(/[^\s"'<>()\[\]]*)?"""
    )

    /** URL 落在句末时会粘上标点，`http://localhost:3000/api.` 的那个点不属于路径。 */
    private val TRAILING = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '"', '\'')

    /**
     * 一屏最多提几条。
     *
     * 有上限是因为 `cat /etc/hosts`、`docker ps` 这类输出能一口气刷出十几个地址，
     * 全都排队等着用户处理的话，提示条就从帮忙变成了骚扰。
     */
    private const val MAX_HINTS = 6

    /**
     * 扫一段终端文本。
     *
     * 同一个端口只留一条：vite 一次会打印 `Local: http://localhost:5173` 和
     * `Network: http://192.168.1.5:5173` 两行，对用户是同一件事。留环回的那条——
     * 它一定是服务器自己够得着的地址，而内网 IP 换个网段就不一定了。
     */
    fun scan(text: String): List<PortHint> {
        val found = LinkedHashMap<Int, PortHint>()
        ADDRESS.findAll(text).forEach { match ->
            val port = match.groupValues[2].toIntOrNull() ?: return@forEach
            if (port !in 1..ForwardRules.MAX_PORT) return@forEach
            val host = match.groupValues[1].removeSurrounding("[", "]")
            if (!isAddress(host)) return@forEach
            val local = isLocal(host)
            val hint = PortHint(
                port = port,
                target = if (local) ForwardSpec.DEFAULT_REMOTE_HOST else host,
                path = match.groupValues[3].trimEnd(*TRAILING),
            )
            val prev = found[port]
            if (prev == null || (local && prev.target != ForwardSpec.DEFAULT_REMOTE_HOST)) {
                found[port] = hint
            }
        }
        return found.values.take(MAX_HINTS)
    }

    /** 正则只管形状，这里管取值：`999.1.1.1` 形状对但不是地址。 */
    private fun isAddress(host: String): Boolean = when {
        host == "localhost" -> true
        // 方括号里的 IPv6 不细究：能写进方括号本身就已经很难是巧合了
        host.contains(':') -> true
        else -> host.split('.').all { (it.toIntOrNull() ?: 256) <= 255 }
    }

    private fun isLocal(host: String): Boolean =
        host == "localhost" || host == "0.0.0.0" || host == "::" || host == "::1" ||
            host.startsWith("127.")
}
