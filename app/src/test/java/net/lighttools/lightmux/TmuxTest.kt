package net.lighttools.lightmux

import net.lighttools.lightmux.tmux.ActionResult
import net.lighttools.lightmux.tmux.ProbeResult
import net.lighttools.lightmux.tmux.RelativeTime
import net.lighttools.lightmux.tmux.Tmux
import net.lighttools.lightmux.tmux.TmuxInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tmux 纯逻辑的单测。
 *
 * 本机没有真机，这些用例是 M2 唯一能自证正确的东西——所以边界要覆盖到狠：
 * 名字里的冒号/空格/单引号/中文、畸形行、空段、缺哨兵、退出码解析、命令转义。
 */
class TmuxTest {

    // ---- 构造探测输出的小工具 -------------------------------------------------

    private fun probeOutput(
        tmuxPresent: Boolean = true,
        sessions: List<String> = emptyList(),
        windows: List<String> = emptyList(),
        prefix: String = "",
        /** 没装 tmux 时才有的那一行，null = 旧版探测命令的输出（缓存里躺着的就是这种） */
        packageLine: String? = null,
        /** server pid 行的值；null = 整行都不输出，模拟旧版探测命令的缓存 */
        serverLine: String? = SERVER_PID,
    ): String = buildString {
        append(prefix)
        append("${Tmux.MARKER_TMUX}${if (tmuxPresent) 1 else 0}\n")
        if (packageLine != null) append("${Tmux.MARKER_PM}$packageLine\n")
        if (serverLine != null) append("${Tmux.MARKER_SERVER}$serverLine\n")
        append("${Tmux.MARKER_SESSIONS}\n")
        sessions.forEach { append(it).append('\n') }
        append("${Tmux.MARKER_WINDOWS}\n")
        windows.forEach { append(it).append('\n') }
        append("${Tmux.MARKER_END}\n")
    }

    private fun sessionsOf(result: ProbeResult) = (result as ProbeResult.Sessions).sessions

    private fun serverIdOf(result: ProbeResult) = (result as ProbeResult.Sessions).serverId

    // ---- 命令生成 ------------------------------------------------------------

    @Test
    fun `探测命令是一条命令，包含两个 -F 且分隔符是冒号`() {
        val cmd = Tmux.PROBE_COMMAND
        assertEquals(1, Regex("list-sessions").findAll(cmd).count())
        assertEquals(1, Regex("list-windows -a").findAll(cmd).count())
        assertTrue(cmd.contains("#{session_attached}:#{session_windows}:#{session_id}:#{session_name}"))
        assertTrue(cmd.contains("#{window_id}:#{session_id}:#{window_name}"))
        // TAB 会被 tmux 替换成别的字符，用它当分隔符必然出错
        assertFalse(cmd.contains("\t"))
    }

    @Test
    fun `探测命令把名字放在格式串行尾`() {
        assertTrue(Tmux.SESSION_FORMAT.endsWith("#{session_name}"))
        assertTrue(Tmux.WINDOW_FORMAT.endsWith("#{window_name}"))
    }

    @Test
    fun `attach 用 new-session -A，接管才加 -D`() {
        assertEquals("tmux new-session -A -s 'main'", Tmux.attachCommand("main"))
    }

    @Test
    fun `名字里的空格与单引号被正确转义`() {
        assertEquals("'my session'", Tmux.quote("my session"))
        assertEquals("""'it'\''s mine'""", Tmux.quote("it's mine"))
        assertEquals("tmux new-session -A -s 'it'\\''s mine'", Tmux.attachCommand("it's mine"))
    }

    @Test
    fun `名字里的分号与反引号不会逃出引号`() {
        val cmd = Tmux.killSessionCommand("a; rm -rf /")
        assertEquals("tmux kill-session -t '=a; rm -rf /'", cmd)
        assertEquals("'\$(whoami)`id`'", Tmux.quote("\$(whoami)`id`"))
    }

    @Test
    fun `中文会话名照常转义`() {
        assertEquals("tmux new-session -A -s '部署 机'", Tmux.attachCommand("部署 机"))
    }

    @Test
    fun `kill 与 rename 用等号前缀强制精确匹配`() {
        // 不加 = 的话 tmux 允许前缀匹配，kill dev 有机会打到 dev2 上
        assertEquals("tmux kill-session -t '=dev'", Tmux.killSessionCommand("dev"))
        assertEquals("tmux rename-session -t '=dev' 'prod'", Tmux.renameSessionCommand("dev", "prod"))
        assertEquals("tmux detach-client -s '=dev'", Tmux.detachOthersCommand("dev"))
    }

    @Test
    fun `窗口切换用 @id 而不是会话名冒号索引`() {
        val select = Tmux.selectWindowCommand("@7", SERVER_PID)!!
        assertTrue(select.endsWith("tmux select-window -t '@7'"))
        val cmd = Tmux.attachWindowCommand("main", "@7", SERVER_PID)
        assertTrue(cmd.contains("tmux select-window -t '@7'"))
        assertTrue(cmd.endsWith("tmux new-session -A -s 'main'"))
        // 缓存里的 @id 可能已经没了，切失败也要落到会话里，所以吞掉它的错误
        assertTrue(cmd.contains("2>/dev/null"))
    }

    @Test
    fun `动作命令包上退出码首行且合并 stderr`() {
        val wrapped = Tmux.action("tmux kill-session -t '=x'")
        assertTrue(wrapped.contains("2>&1"))
        assertTrue(wrapped.contains("${Tmux.MARKER_RC}\$rc"))
        assertTrue(wrapped.contains("tmux kill-session -t '=x'"))
    }

    // ---- 正常解析 ------------------------------------------------------------

    @Test
    fun `正常输出解析出会话与窗口`() {
        val result = Tmux.parseProbe(
            probeOutput(
                sessions = listOf("1:2:\$0:main", "0:1:\$1:deploy"),
                windows = listOf(
                    "0:1:0:@0:\$0:server",
                    "1:2:1:@1:\$0:logs",
                    "1:1:0:@2:\$1:build",
                ),
            )
        )
        val sessions = sessionsOf(result)
        assertEquals(listOf("main", "deploy"), sessions.map { it.name })

        val main = sessions[0]
        assertEquals(2, main.windowCount)
        assertEquals(1, main.attachedClients)
        assertTrue(main.attached)
        assertEquals(listOf("server", "logs"), main.windows.map { it.name })
        assertEquals(listOf("@0", "@1"), main.windows.map { it.id })
        assertEquals(listOf(false, true), main.windows.map { it.active })
        assertEquals(listOf(1, 2), main.windows.map { it.panes })

        val deploy = sessions[1]
        assertFalse(deploy.attached)
        assertEquals(listOf("build"), deploy.windows.map { it.name })
    }

    @Test
    fun `窗口按 index 排序，不管 tmux 吐出来的顺序`() {
        val result = Tmux.parseProbe(
            probeOutput(
                sessions = listOf("0:3:\$0:main"),
                windows = listOf("0:1:9:@9:\$0:last", "0:1:2:@2:\$0:mid", "1:1:0:@0:\$0:first"),
            )
        )
        assertEquals(listOf(0, 2, 9), sessionsOf(result)[0].windows.map { it.index })
    }

    @Test
    fun `会话名含冒号时名字取到行尾`() {
        val result = Tmux.parseProbe(
            probeOutput(
                sessions = listOf("0:1:\$0:a:b:c"),
                windows = listOf("1:1:0:@0:\$0:w"),
            )
        )
        val session = sessionsOf(result).single()
        assertEquals("a:b:c", session.name)
        assertEquals(1, session.windowCount)
        assertEquals("w", session.windows.single().name)
    }

    @Test
    fun `窗口名含冒号时名字取到行尾`() {
        val result = Tmux.parseProbe(
            probeOutput(
                sessions = listOf("0:1:\$0:main"),
                windows = listOf("1:1:0:@0:\$0:vim: src/main.kt"),
            )
        )
        assertEquals("vim: src/main.kt", sessionsOf(result).single().windows.single().name)
    }

    @Test
    fun `会话名含空格、单引号、中文都能原样带回`() {
        val names = listOf("my session", "it's mine", "部署机", "a b:c'd")
        val result = Tmux.parseProbe(
            probeOutput(sessions = names.mapIndexed { i, n -> "0:1:\$$i:$n" })
        )
        assertEquals(names, sessionsOf(result).map { it.name })
    }

    @Test
    fun `窗口名可以是空串`() {
        val result = Tmux.parseProbe(
            probeOutput(sessions = listOf("0:1:\$0:main"), windows = listOf("1:1:0:@0:\$0:"))
        )
        assertEquals("", sessionsOf(result).single().windows.single().name)
    }

    @Test
    fun `没有窗口行的会话仍然出现在列表里`() {
        val result = Tmux.parseProbe(probeOutput(sessions = listOf("0:1:\$0:main")))
        val session = sessionsOf(result).single()
        assertEquals(1, session.windowCount)
        assertTrue(session.windows.isEmpty())
    }

    @Test
    fun `归属不明的窗口被丢掉而不是判定畸形`() {
        // 列完会话后那个会话被 kill 了，是竞态不是格式错误，报读取失败属于误伤
        val result = Tmux.parseProbe(
            probeOutput(
                sessions = listOf("0:1:\$0:main"),
                windows = listOf("1:1:0:@0:\$0:ok", "1:1:0:@9:\$7:orphan"),
            )
        )
        assertEquals(listOf("ok"), sessionsOf(result).single().windows.map { it.name })
    }

    @Test
    fun `多余空行与 motd 前缀不影响解析`() {
        val result = Tmux.parseProbe(
            probeOutput(
                sessions = listOf("", "0:1:\$0:main", ""),
                windows = listOf("", "1:1:0:@0:\$0:w"),
                prefix = "Welcome to Ubuntu 24.04\nLast login: Tue Aug 12\n\n",
            )
        )
        assertEquals("main", sessionsOf(result).single().name)
    }

    @Test
    fun `CRLF 行尾不影响解析`() {
        val raw = probeOutput(sessions = listOf("0:1:\$0:main"), windows = listOf("1:1:0:@0:\$0:w"))
            .replace("\n", "\r\n")
        assertEquals("main", sessionsOf(Tmux.parseProbe(raw)).single().name)
    }

    // ---- 三种状态的区分 ------------------------------------------------------

    @Test
    fun `远端没装 tmux 返回 NoTmux`() {
        assertEquals(ProbeResult.NoTmux(), Tmux.parseProbe(probeOutput(tmuxPresent = false)))
    }

    @Test
    fun `没装 tmux 时带回包管理器与是否 root`() {
        assertEquals(
            ProbeResult.NoTmux(TmuxInstaller("apt-get", root = true)),
            Tmux.parseProbe(probeOutput(tmuxPresent = false, packageLine = "apt-get:0")),
        )
        assertEquals(
            ProbeResult.NoTmux(TmuxInstaller("apk", root = false)),
            Tmux.parseProbe(probeOutput(tmuxPresent = false, packageLine = "apk:1000")),
        )
    }

    @Test
    fun `认不出包管理器时 installer 为空而不是崩掉`() {
        // 一台机器上一个包管理器都没有（`pm=''`），以及缓存里那份旧版输出（压根没有这一行）
        listOf(":0", "brew-clone:0", "apt-get", "") .forEach { line ->
            assertEquals(
                ProbeResult.NoTmux(),
                Tmux.parseProbe(probeOutput(tmuxPresent = false, packageLine = line)),
            )
        }
    }

    @Test
    fun `装了 tmux 就不去探包管理器`() {
        // 绝大多数主机走这条路径，多一遍 command -v 循环是白花的往返时间
        val branch = Tmux.PROBE_COMMAND.substringBefore("else")
        assertFalse(branch.contains(Tmux.MARKER_PM))
        assertTrue(Tmux.PROBE_COMMAND.contains(Tmux.MARKER_PM))
    }

    // ---- 安装命令 ------------------------------------------------------------

    @Test
    fun `非 root 时每一条子命令都带 sudo`() {
        // `sudo a && b` 里的 b 是不带 sudo 跑的，装一半比直接失败更难查
        assertEquals(
            "sudo apt-get update && sudo apt-get install -y tmux",
            Tmux.installCommand(TmuxInstaller("apt-get", root = false)),
        )
    }

    @Test
    fun `root 不加 sudo，brew 任何时候都不加`() {
        assertEquals(
            "apt-get update && apt-get install -y tmux",
            Tmux.installCommand(TmuxInstaller("apt-get", root = true)),
        )
        // brew 拒绝以 root 运行，给它加 sudo 是帮倒忙
        assertEquals("brew install tmux", Tmux.installCommand(TmuxInstaller("brew", root = false)))
        assertEquals("brew install tmux", Tmux.installCommand(TmuxInstaller("brew", root = true)))
    }

    @Test
    fun `认得的包管理器都给得出命令，不认得的返回 null`() {
        Tmux.PACKAGE_MANAGERS.forEach { manager ->
            assertTrue(manager, Tmux.installCommand(TmuxInstaller(manager, root = true)) != null)
        }
        assertNull(Tmux.installCommand(TmuxInstaller("emerge", root = true)))
    }

    // ---- 新建会话 / 新建窗口 --------------------------------------------------

    @Test
    fun `新建窗口用等号前缀且不自己 select`() {
        // tmux 建完就把新窗口选为当前窗口，再 select 一次是多余往返
        assertEquals("tmux new-window -t '=dev'", Tmux.newWindowCommand("dev"))
        assertFalse(Tmux.newWindowCommand("dev").contains("select-window"))
    }

    @Test
    fun `没 attach 时建窗口与 attach 合成一条命令`() {
        val cmd = Tmux.newWindowAndAttachCommand("it's mine")
        assertTrue(cmd.startsWith("tmux new-window -t '=it'\\''s mine'"))
        assertTrue(cmd.endsWith("tmux new-session -A -s 'it'\\''s mine'"))
        // 会话刚被 kill 时建窗口会失败，那时 -A 会把它重新建出来，用户仍然落在终端里
        assertTrue(cmd.contains("2>/dev/null"))
    }

    @Test
    fun `关窗口用 @id 而不是会话名冒号索引`() {
        // index 会随着窗口增删往前挪，拿它 kill 就是在关隔壁那个，而这个动作不可逆
        val cmd = Tmux.killWindowCommand("@7", SERVER_PID)!!
        assertTrue(cmd.endsWith("tmux kill-window -t '@7'"))
        assertFalse(cmd.substringAfter("kill-window").contains(":"))
    }

    // ---- 窗口 @id 的服务端有效期校验 ------------------------------------------

    @Test
    fun `探测在同一条 exec 里带回 server pid`() {
        val cmd = Tmux.PROBE_COMMAND
        assertTrue(cmd.contains("${Tmux.MARKER_SERVER}\$(${Tmux.SERVER_PID_EXPR})"))
        // 纪律 1：还是一条 exec，display-message 只是多一行 echo，不是多一次往返
        assertEquals(1, Regex("display-message").findAll(cmd).count())
        // 没有 server 在跑时 tmux 会往 stderr 吼，那不是错误
        assertTrue(Tmux.SERVER_PID_EXPR.contains("2>/dev/null"))
    }

    @Test
    fun `解析出 server pid`() {
        val result = Tmux.parseProbe(probeOutput(sessions = listOf("0:1:\$0:main")))
        assertEquals(SERVER_PID, serverIdOf(result))
    }

    @Test
    fun `旧版缓存没有 server 行时照常解析，serverId 为空`() {
        // 这条是向后兼容的底线：DataStore 里躺着的旧输出必须还能画出会话树，
        // 少一个 pid 只该让破坏性动作先刷新，不该让整份列表变成「读取失败」
        val result = Tmux.parseProbe(
            probeOutput(
                sessions = listOf("0:1:\$0:main"),
                windows = listOf("1:1:0:@0:\$0:w"),
                serverLine = null,
            )
        )
        assertTrue(result is ProbeResult.Sessions)
        assertNull(serverIdOf(result))
        assertEquals("main", sessionsOf(result).single().name)
        assertEquals("@0", sessionsOf(result).single().windows.single().id)
    }

    @Test
    fun `server 行为空值时 serverId 为空而不是空串`() {
        // 探测那一刻压根没有 server 在跑，`$(...)` 就是空的
        val result = Tmux.parseProbe(probeOutput(sessions = listOf("0:1:\$0:main"), serverLine = ""))
        assertNull(serverIdOf(result))
    }

    @Test
    fun `关窗口先在远端校验 server pid，对不上就以 STALE 退出`() {
        val cmd = Tmux.killWindowCommand("@3", SERVER_PID)!!
        assertTrue(cmd.startsWith("[ \"\$(${Tmux.SERVER_PID_EXPR})\" = '$SERVER_PID' ]"))
        assertTrue(cmd.contains("|| exit ${ActionResult.STALE};"))
        // 校验必须排在 kill 前面，否则先关了再说的顺序等于没校验
        assertTrue(cmd.indexOf("exit ${ActionResult.STALE}") < cmd.indexOf("kill-window"))
    }

    @Test
    fun `切窗口也带同一套校验`() {
        val cmd = Tmux.selectWindowCommand("@3", SERVER_PID)!!
        assertTrue(cmd.contains("|| exit ${ActionResult.STALE};"))
        assertTrue(cmd.indexOf("exit ${ActionResult.STALE}") < cmd.indexOf("select-window"))
    }

    @Test
    fun `没有 serverId 就不生成窗口命令`() {
        // 旧缓存里的 @id 校验不了，宁可让上层先刷新，也不拿不可逆的 kill 去赌
        assertNull(Tmux.killWindowCommand("@3", null))
        assertNull(Tmux.selectWindowCommand("@3", null))
    }

    @Test
    fun `attach 时校验没过就只 attach 不切窗口`() {
        // 登录命令不能 exit，切错窗口虽可逆但仍是错的，所以用 && 短路而不是 || exit
        val cmd = Tmux.attachWindowCommand("main", "@3", SERVER_PID)
        assertFalse(cmd.contains("exit ${ActionResult.STALE}"))
        assertTrue(cmd.contains("] && tmux select-window"))
        assertTrue(cmd.endsWith("tmux new-session -A -s 'main'"))
        // serverId 为空（旧缓存）就退化成纯 attach，落在会话的当前窗口上
        assertEquals("tmux new-session -A -s 'main'", Tmux.attachWindowCommand("main", "@3", null))
    }

    @Test
    fun `serverId 也走引号转义`() {
        // pid 正常只有数字，但它来自远端输出，当成不可信输入处理才不会有下一个注入点
        val cmd = Tmux.killWindowCommand("@3", "1;rm -rf /")!!
        assertTrue(cmd.contains("= '1;rm -rf /' ]"))
        assertTrue(Tmux.attachWindowCommand("m", "@3", "it's").contains("""= 'it'\''s' ]"""))
    }

    @Test
    fun `STALE 退出码不和普通失败撞车`() {
        assertTrue(Tmux.parseAction("${Tmux.MARKER_RC}${ActionResult.STALE}\n").stale)
        assertFalse(Tmux.parseAction("${Tmux.MARKER_RC}${ActionResult.STALE}\n").ok)
        assertFalse(Tmux.parseAction("${Tmux.MARKER_RC}1\ncan't find window\n").stale)
        assertFalse(Tmux.parseAction("${Tmux.MARKER_RC}0\n").stale)
    }

    @Test
    fun `默认会话名避开已有的名字`() {
        assertEquals("main", Tmux.defaultSessionName(emptyList()))
        assertEquals("main", Tmux.defaultSessionName(listOf("dev", "deploy")))
        assertEquals("main2", Tmux.defaultSessionName(listOf("main")))
        assertEquals("main4", Tmux.defaultSessionName(listOf("main", "main2", "main3")))
        // 中间有空档就填空档，不是一路往后数
        assertEquals("main3", Tmux.defaultSessionName(listOf("main", "main2", "main4")))
    }

    @Test
    fun `装了但没有会话返回 NoSessions`() {
        assertEquals(ProbeResult.NoSessions, Tmux.parseProbe(probeOutput()))
    }

    @Test
    fun `Sessions 分支永远非空`() {
        val result = Tmux.parseProbe(probeOutput(sessions = listOf("0:1:\$0:main")))
        assertTrue(sessionsOf(result).isNotEmpty())
    }

    @Test
    fun `空输出是 Malformed 而不是空列表`() {
        // 这条是底线：空列表会让 UI 说「这台机器没有会话」，用户会以为自己的会话丢了
        assertTrue(Tmux.parseProbe("") is ProbeResult.Malformed)
        assertTrue(Tmux.parseProbe("\n\n\n") is ProbeResult.Malformed)
    }

    @Test
    fun `连接被打断导致哨兵缺失时返回 Malformed`() {
        val full = probeOutput(sessions = listOf("0:1:\$0:main"), windows = listOf("1:1:0:@0:\$0:w"))
        // 逐个删掉哨兵，每一种都必须是 Malformed
        listOf(Tmux.MARKER_SESSIONS, Tmux.MARKER_WINDOWS, Tmux.MARKER_END).forEach { marker ->
            val broken = full.lines().filterNot { it == marker }.joinToString("\n")
            assertTrue("missing $marker", Tmux.parseProbe(broken) is ProbeResult.Malformed)
        }
        // 探测标记本身缺失
        val noMarker = full.lines().filterNot { it.startsWith(Tmux.MARKER_TMUX) }.joinToString("\n")
        assertTrue(Tmux.parseProbe(noMarker) is ProbeResult.Malformed)
    }

    @Test
    fun `哨兵顺序颠倒返回 Malformed`() {
        val raw = "${Tmux.MARKER_TMUX}1\n${Tmux.MARKER_WINDOWS}\n${Tmux.MARKER_SESSIONS}\n${Tmux.MARKER_END}\n"
        assertTrue(Tmux.parseProbe(raw) is ProbeResult.Malformed)
    }

    @Test
    fun `畸形会话行返回 Malformed`() {
        val bad = listOf(
            "0:1:\$0",          // 字段不够
            "x:1:\$0:main",     // 附加数不是数字
            "0:y:\$0:main",     // 窗口数不是数字
            "0:1:0:main",       // 会话 id 没有 $ 前缀
            "0:1:\$0:",         // 空会话名（tmux 不允许，出现即异常）
            "-1:1:\$0:main",    // 负数
            "no server running on /tmp/tmux-0/default",
        )
        bad.forEach { line ->
            val result = Tmux.parseProbe(probeOutput(sessions = listOf(line)))
            assertTrue("should be malformed: $line", result is ProbeResult.Malformed)
        }
    }

    @Test
    fun `畸形窗口行返回 Malformed`() {
        val bad = listOf(
            "1:1:0:@0:\$0",     // 字段不够
            "1:1:0:0:\$0:w",    // 窗口 id 没有 @ 前缀
            "1:1:x:@0:\$0:w",   // 索引不是数字
            "1:1:0:@0:0:w",     // 会话 id 没有 $ 前缀
        )
        bad.forEach { line ->
            val result = Tmux.parseProbe(
                probeOutput(sessions = listOf("0:1:\$0:main"), windows = listOf(line))
            )
            assertTrue("should be malformed: $line", result is ProbeResult.Malformed)
        }
    }

    @Test
    fun `探测标记值不是 0 或 1 时返回 Malformed`() {
        val raw = "${Tmux.MARKER_TMUX}yes\n${Tmux.MARKER_SESSIONS}\n${Tmux.MARKER_WINDOWS}\n${Tmux.MARKER_END}\n"
        assertTrue(Tmux.parseProbe(raw) is ProbeResult.Malformed)
    }

    @Test
    fun `Malformed 带上原因，方便排查`() {
        val result = Tmux.parseProbe(probeOutput(sessions = listOf("garbage"))) as ProbeResult.Malformed
        assertTrue(result.reason.contains("garbage"))
    }

    @Test
    fun `缓存里的原始输出能原样解析回来`() {
        // TmuxCache 存的就是这段原始文本，解码 = 再跑一次 parseProbe
        val raw = probeOutput(
            sessions = listOf("1:2:\$0:main"),
            windows = listOf("1:1:0:@0:\$0:a", "0:1:1:@1:\$0:b"),
        )
        assertEquals(Tmux.parseProbe(raw), Tmux.parseProbe(raw))
        assertEquals(2, sessionsOf(Tmux.parseProbe(raw)).single().windows.size)
    }

    // ---- 退出码解析 ----------------------------------------------------------

    @Test
    fun `退出码 0 是成功`() {
        val result = Tmux.parseAction("${Tmux.MARKER_RC}0\n\n")
        assertTrue(result.ok)
        assertEquals("", result.output)
    }

    @Test
    fun `退出码非 0 带回 stderr 文本`() {
        val result = Tmux.parseAction("${Tmux.MARKER_RC}1\ncan't find session: nope\n")
        assertFalse(result.ok)
        assertEquals(1, result.code)
        assertEquals("can't find session: nope", result.output)
    }

    @Test
    fun `退出码行前面有 motd 也能认出来`() {
        val result = Tmux.parseAction("Welcome!\n${Tmux.MARKER_RC}0\nok\n")
        assertTrue(result.ok)
        assertEquals("ok", result.output)
    }

    @Test
    fun `拿不到退出码一律当失败`() {
        // 「不知道成没成」当成功，用户会看着没被 kill 的会话从列表里消失
        listOf("", "something went wrong", "${Tmux.MARKER_RC}abc").forEach { raw ->
            val result = Tmux.parseAction(raw)
            assertFalse(result.ok)
            assertEquals(ActionResult.NO_EXIT_CODE, result.code)
        }
    }

    @Test
    fun `多行输出全部带回`() {
        val result = Tmux.parseAction("${Tmux.MARKER_RC}2\nline1\nline2\n")
        assertEquals("line1\nline2", result.output)
    }

    // ---- 相对时间 ------------------------------------------------------------

    @Test
    fun `相对时间按量级换算`() {
        assertEquals(RelativeTime.Age.JustNow, RelativeTime.of(0))
        assertEquals(RelativeTime.Age.JustNow, RelativeTime.of(59_000))
        assertEquals(RelativeTime.Age.Minutes(3), RelativeTime.of(3 * 60_000L + 5_000))
        assertEquals(RelativeTime.Age.Minutes(59), RelativeTime.of(59 * 60_000L))
        assertEquals(RelativeTime.Age.Hours(2), RelativeTime.of(2 * 3_600_000L))
        assertEquals(RelativeTime.Age.Days(3), RelativeTime.of(3 * 86_400_000L))
    }

    @Test
    fun `设备时钟被往回调时不显示负数`() {
        assertEquals(RelativeTime.Age.JustNow, RelativeTime.of(-90_000))
    }

    private companion object {
        const val SERVER_PID = "48210"
    }
}
