package net.lighttools.lightmux.tmux

import net.lighttools.lightmux.data.Host
import net.lighttools.lightmux.ssh.ExecPool

/**
 * tmux 侧通道的调度：发 exec、把字符串交给 [Tmux]、写缓存。
 *
 * **不做解析**——解析全在 [Tmux] 里，那边零 Android 依赖、可单测。这里只剩 IO。
 *
 * 连接的复用与串行归 [ExecPool] 管（监控、SFTP 共用同一个池）。
 * 活在 Application 作用域（挂在 `LightmuxApp` 上）：缓存不该跟着主页 ViewModel 走。
 */
class TmuxRepository(
    private val cache: TmuxCache,
    private val pool: ExecPool,
) {

    /** 冷启动用：上次探测的快照，不碰网络。 */
    suspend fun cachedStates(): Map<String, HostTmuxState> = cache.load().mapNotNull { (hostId, entry) ->
        val probe = Tmux.parseProbe(entry.raw)
        // 缓存里的东西解析不出来就当没有：显示一条「读取失败」却没人点得动它，比空着更差。
        if (probe is ProbeResult.Malformed) null
        else hostId to HostTmuxState(probe = probe, probedAt = entry.probedAt)
    }.toMap()

    /**
     * 探测一台主机：一次 exec 拿到会话与窗口。
     *
     * @throws java.io.IOException 连不上 / 超时。解析层面的失败走 [ProbeResult.Malformed]，不抛
     */
    suspend fun probe(host: Host): ProbeResult = pool.withConnection(host) { connection ->
        val stdout = connection.exec(Tmux.PROBE_COMMAND, PROBE_TIMEOUT_MS).stdout
        val result = Tmux.parseProbe(stdout)
        // 只缓存能解析的输出：把畸形输出存进去，等于让下次冷启动继续显示同一个错误。
        if (result !is ProbeResult.Malformed) cache.put(host.id, stdout)
        result
    }

    suspend fun rename(host: Host, from: String, to: String): ActionResult =
        run(host, Tmux.renameSessionCommand(from, to))

    suspend fun kill(host: Host, name: String): ActionResult =
        run(host, Tmux.killSessionCommand(name))

    suspend fun detachOthers(host: Host, name: String): ActionResult =
        run(host, Tmux.detachOthersCommand(name))

    /**
     * 已经 attach 着的会话切窗口：走侧通道，**绝不往前台终端注入按键**。
     *
     * @param serverId 见 [ProbeResult.Sessions.serverId]；null 或对不上都直接返回
     *   [ActionResult.STALE]，一个字节都不发
     */
    suspend fun selectWindow(host: Host, session: String, windowId: String, serverId: String?): ActionResult =
        runWindowAction(host, Tmux.selectWindowCommand(session, windowId, serverId))

    /** 同上：已经 attach 着的会话开新窗口也走侧通道，前台可能正跑着全屏 TUI。 */
    suspend fun newWindow(host: Host, session: String): ActionResult =
        run(host, Tmux.newWindowCommand(session))

    /** @param serverId 见 [selectWindow]。关窗口不可逆，校验没过时**绝不发命令** */
    suspend fun killWindow(host: Host, windowId: String, serverId: String?): ActionResult =
        runWindowAction(host, Tmux.killWindowCommand(windowId, serverId))

    /**
     * 命令为 null = 手里的 `@id` 来自没有 server 指纹的旧缓存，校验不了。
     * 伪造一个 [ActionResult.STALE] 交给上层，让「先刷新再重试」只有一条代码路径。
     */
    private suspend fun runWindowAction(host: Host, command: String?): ActionResult =
        if (command == null) ActionResult(ActionResult.STALE, "") else run(host, command)

    private suspend fun run(host: Host, command: String): ActionResult = pool.withConnection(host) { connection ->
        Tmux.parseAction(connection.exec(Tmux.action(command), ACTION_TIMEOUT_MS).stdout)
    }

    /** 主机折叠时放掉为它拨的连接。终端会话自己那条不在池子里，不受影响。 */
    suspend fun release(hostId: String) = pool.release(hostId)

    /** 见 [ExecPool.hasLiveConnection]：自动发起的探测拿它当「不许拨号」的闸门。 */
    fun hasLiveConnection(hostId: String): Boolean = pool.hasLiveConnection(hostId)

    suspend fun forget(hostId: String) {
        release(hostId)
        cache.remove(hostId)
    }

    private companion object {
        /** 探测比普通命令更该早点放弃：用户正盯着一行 loading。 */
        const val PROBE_TIMEOUT_MS = 8_000L
        const val ACTION_TIMEOUT_MS = 8_000L
    }
}
