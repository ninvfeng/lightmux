package net.lighttools.lightmux.forward

/**
 * 转发页那个搜索框的匹配规则。
 *
 * 一台开发机上监听着二三十个端口是常事（数据库、缓存、各种守护进程），列表一进去就是满屏，
 * 滚到底去找 `3000` 不如打两个数字。所以搜索框在这里不是「去搜什么」，而是**从已经列出来的
 * 里面挑**——数据早就在手上了，一次探测全拿回来的。
 *
 * 用「包含」而不是「前缀」：用户记得住的常是端口的后半截，或者进程名的一部分（`redis`）。
 *
 * 纯逻辑、无 Android 依赖，理由同 [ListeningPorts]（PRD §6.3）。
 */
object PortFilter {

    /** 远端探测出来的端口：端口号、进程名、监听地址都算数。 */
    fun matches(query: String, port: ListeningPort): Boolean =
        matches(query, port.port.toString(), port.process, port.address)

    /** 已经配好的转发：两个端口号、备注、远端主机都算数。 */
    fun matches(query: String, spec: ForwardSpec): Boolean = matches(
        query,
        spec.remotePort.toString(),
        spec.localPort.toString(),
        spec.label,
        spec.remoteHost,
    )

    private fun matches(query: String, vararg fields: String?): Boolean {
        val q = query.trim()
        // 空查询放行全部：搜索框刚展开、还没打字的那一下，列表不该先空掉
        if (q.isEmpty()) return true
        return fields.any { it != null && it.contains(q, ignoreCase = true) }
    }
}
