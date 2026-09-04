package net.lighttools.lightmux

import net.lighttools.lightmux.data.QuickBar
import net.lighttools.lightmux.data.QuickCommand
import net.lighttools.lightmux.data.QuickCommands
import net.lighttools.lightmux.data.QuickCustomKey
import net.lighttools.lightmux.data.QuickCustomKeys
import net.lighttools.lightmux.data.QuickKey
import net.lighttools.lightmux.data.QuickKeySize
import net.lighttools.lightmux.data.QuickSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickBarTest {

    @Test
    fun `没存过就回落默认顺序`() {
        assertEquals(QuickBar.defaultSlots(), QuickBar.decodeSlots(null))
        assertEquals(QuickCommands.DEFAULTS, QuickCommands.decode(null))
    }

    /** 空串是「用户把键全删了」，不是「没存过」——回落默认会让删掉的键自己长回来。 */
    @Test
    fun `空串还原成空列表而不是默认值`() {
        assertTrue(QuickBar.decodeSlots("").isEmpty())
        assertTrue(QuickCommands.decode("").isEmpty())
    }

    @Test
    fun `键位顺序原样往返`() {
        val slots = slotsOf(QuickKey.Back, QuickKey.Ctrl, QuickKey.Enter, QuickKey.Slash)
        assertEquals(slots, QuickBar.decodeSlots(QuickBar.encodeSlots(slots)))
    }

    /** 降级安装时新版加的键在老版里不存在，丢掉它就行，不能让整条栏回落默认。 */
    @Test
    fun `认不出的 id 只丢那一格`() {
        assertEquals(
            slotsOf(QuickKey.Back, QuickKey.Enter),
            QuickBar.decodeSlots("back,no-such-key,enter"),
        )
    }

    @Test
    fun `重复的键只留一格`() {
        assertEquals(slotsOf(QuickKey.Ctrl), QuickBar.decodeSlots("ctrl,ctrl"))
        assertEquals("ctrl", QuickBar.encodeSlots(slotsOf(QuickKey.Ctrl, QuickKey.Ctrl)))
    }

    @Test
    fun `自定义键原样往返`() {
        val keys = listOf(
            QuickCustomKey("u1", "c1", "claude", enter = true),
            QuickCustomKey("u2", "gs", "git status"),
        )
        assertEquals(keys, QuickCustomKeys.decode(QuickCustomKeys.encode(keys)))
    }

    /** 一行一条、字段用 TAB 分隔：留着换行会把一条读成两条，留着 TAB 会把内容串到别的字段去。 */
    @Test
    fun `自定义键里的换行和 TAB 压成空格`() {
        val key = QuickCustomKey("u1", "c\t1", "claude\n--resume")
        assertEquals(
            listOf(QuickCustomKey("u1", "c 1", "claude --resume")),
            QuickCustomKeys.decode(QuickCustomKeys.encode(listOf(key))),
        )
    }

    /** 键帽或内容空着的那条是废键：栏上画不出东西，按下去也不发东西。 */
    @Test
    fun `键帽或内容为空的自定义键不落盘`() {
        val keys = listOf(
            QuickCustomKey("u1", "", "claude"),
            QuickCustomKey("u2", "gs", " "),
            QuickCustomKey("u3", "ok", "ls"),
        )
        assertEquals(listOf("u3"), QuickCustomKeys.decode(QuickCustomKeys.encode(keys)).map { it.id })
    }

    /** 删一格再加一格不该复活刚删掉的 id——老顺序串里若还留着它，新键会莫名跑到旧位置。 */
    @Test
    fun `自定义键的 id 只增不回收`() {
        val keys = listOf(QuickCustomKey("u1", "a", "a"), QuickCustomKey("u3", "b", "b"))
        assertEquals("u4", QuickCustomKeys.nextId(keys))
        assertEquals("u1", QuickCustomKeys.nextId(emptyList()))
        assertEquals("u4", QuickCustomKeys.nextId(keys - keys[0]))
    }

    @Test
    fun `自定义键排在顺序串给的位置上`() {
        val custom = QuickCustomKey("u1", "c1", "claude")
        assertEquals(
            listOf(QuickSlot.Preset(QuickKey.Back), QuickSlot.Custom(custom), QuickSlot.Preset(QuickKey.Enter)),
            QuickBar.decodeSlots("back,u1,enter", listOf(custom)),
        )
    }

    /**
     * 库里有而顺序串没提到的自定义键补在栏尾。
     *
     * 老版本写盘时不认识这些 id 会把它们过滤掉，不补回来的话键还躺在库里、栏上却看不见——
     * 用户既按不到也删不掉。新增一个自定义键因此也只需写库这一处。
     */
    @Test
    fun `顺序串漏掉的自定义键补在末尾`() {
        val custom = QuickCustomKey("u1", "c1", "claude")
        assertEquals(
            listOf(QuickSlot.Preset(QuickKey.Back), QuickSlot.Custom(custom)),
            QuickBar.decodeSlots("back", listOf(custom)),
        )
    }

    /** 删掉的自定义键只从库里移除，顺序串里那个死 id 得跟着失效，否则栏上会留一格按不动的键。 */
    @Test
    fun `库里没有的自定义 id 丢掉那一格`() {
        assertEquals(slotsOf(QuickKey.Back), QuickBar.decodeSlots("back,u1", emptyList()))
    }

    /** 一行一条，所以换行必须在存的时候就压掉，否则一条会被读成两条。 */
    @Test
    fun `命令里的换行压成空格`() {
        assertEquals("0\ta b", QuickCommands.encode(listOf(QuickCommand("a\nb"))))
        assertEquals(
            listOf(QuickCommand("a b")),
            QuickCommands.decode(QuickCommands.encode(listOf(QuickCommand("a\r\nb")))),
        )
    }

    @Test
    fun `空白命令不落盘`() {
        val commands = listOf(QuickCommand("  "), QuickCommand("ls"), QuickCommand(""))
        assertEquals(
            listOf(QuickCommand("ls")),
            QuickCommands.decode(QuickCommands.encode(commands)),
        )
    }

    @Test
    fun `命令的回车开关原样往返`() {
        val commands = listOf(QuickCommand("docker ps", enter = true), QuickCommand("cd .."))
        assertEquals(commands, QuickCommands.decode(QuickCommands.encode(commands)))
    }

    /**
     * 0.1.40 之前存的是纯命令行，没有前面那个回车标志位。
     *
     * 读不出标志位就整行当命令（不自动回车）——老用户攒下的那几条不该因为多了个开关而消失，
     * 更不该忽然变成「点一下就跑」。
     */
    @Test
    fun `旧格式的纯命令行照样读得出来`() {
        assertEquals(
            listOf(QuickCommand("ls -la"), QuickCommand("cd ..")),
            QuickCommands.decode("ls -la\ncd .."),
        )
    }

    /** 默认排在最前的几格是产品定的：四格动作键（返回、命令、文件、转发）挨在一起，然后才是真按键。 */
    @Test
    fun `默认顺序以四格动作键打头`() {
        assertEquals(
            listOf(QuickKey.Back, QuickKey.Commands, QuickKey.Files, QuickKey.Forward, QuickKey.Enter),
            QuickBar.DEFAULT_KEYS.take(5),
        )
    }

    /**
     * 动作键（返回 / 命令 / 文件 / 转发）不能有键帽文字。
     *
     * 有 [QuickKey.label] 的键在 UI 里走的是「键帽画什么就发什么」那条兜底分支，
     * 给动作键配上文字 = 按下去往终端里发一个字符，而不是打开文件页。
     */
    @Test
    fun `动作键不带键帽文字`() {
        listOf(QuickKey.Back, QuickKey.Commands, QuickKey.Files, QuickKey.Forward).forEach {
            assertEquals(null, it.label)
        }
    }

    /** id 是落盘用的：撞了就是两格键互相顶掉对方，用户排好的顺序会莫名少一格。 */
    @Test
    fun `键的 id 不重复`() {
        val ids = QuickKey.entries.map { it.id }
        assertEquals(ids.distinct(), ids)
    }

    /**
     * 组合键的键帽必须和它真正发出去的字符对得上。
     *
     * 派发按 [QuickKey.ctrlChar] 走，键帽画的是 [QuickKey.label]，两者是分开写的两个字段——
     * 抄漏一个就会出现「按 `^C` 发的是 `^D`」，而这种错在终端里代价很高。
     */
    @Test
    fun `组合键的键帽与发出的字符一致`() {
        val combos = QuickKey.entries.filter { it.ctrlChar != null }
        assertTrue(combos.isNotEmpty())
        combos.forEach {
            assertEquals("^${it.ctrlChar!!.uppercaseChar()}", it.label)
        }
    }

    /** 0.1.11–0.1.13 存的是三档 id，升级不该把调过宽松的人打回紧凑。 */
    @Test
    fun `旧的三档换算成键宽`() {
        assertEquals(36, QuickKeySize.ofLegacyId("normal"))
        assertEquals(44, QuickKeySize.ofLegacyId("loose"))
        assertEquals(QuickKeySize.DEFAULT_WIDTH_DP, QuickKeySize.ofLegacyId(null))
        assertEquals(QuickKeySize.DEFAULT_WIDTH_DP, QuickKeySize.ofLegacyId("huge"))
    }

    /** 滑块给的数没边界，越界值直接当尺寸用会画出一排比屏幕还宽的键。 */
    @Test
    fun `键宽越界夹回区间`() {
        assertEquals(QuickKeySize.MIN_WIDTH_DP, QuickKeySize.clamp(0))
        assertEquals(QuickKeySize.MAX_WIDTH_DP, QuickKeySize.clamp(999))
    }

    /**
     * 键距必须跟着键宽一起长。
     *
     * 只把键帽撑大而键仍然挨着，误触邻键的概率一点没降，那这个滑块就白拖了。
     */
    @Test
    fun `键宽越大间距和键高越大`() {
        val widths = (QuickKeySize.MIN_WIDTH_DP..QuickKeySize.MAX_WIDTH_DP).toList()
        assertEquals(widths.map { QuickKeySize.gapDp(it) }.sorted(), widths.map { QuickKeySize.gapDp(it) })
        assertEquals(
            widths.map { QuickKeySize.capHeightDp(it) }.sorted(),
            widths.map { QuickKeySize.capHeightDp(it) },
        )
        assertTrue(QuickKeySize.gapDp(QuickKeySize.MAX_WIDTH_DP) - QuickKeySize.gapDp(QuickKeySize.MIN_WIDTH_DP) >= 3)
    }

    @Test
    fun `按键序列开关原样往返`() {
        val key = QuickCustomKey("u1", "dt", "C-b d", keys = true)
        val decoded = QuickCustomKeys.decode(QuickCustomKeys.encode(listOf(key))).single()
        assertTrue(decoded.keys)
        assertFalse(decoded.enter)
        assertEquals("C-b d", decoded.text)
    }

    /**
     * 模式挤在原来的回车标志位里：`k` 序列、`1` 回车、`0` 只填。
     *
     * 降级到 0.1.57 及之前的版本时那一格读不出 `k` 就当「不回车」，序列键最多退化成把记法填进去，
     * 不会变成「点一下就跑」。
     */
    @Test
    fun `模式字段按序列回车只填三档落盘`() {
        assertEquals("u1\tk\tdt\tC-b d", QuickCustomKeys.encode(listOf(QuickCustomKey("u1", "dt", "C-b d", keys = true))))
        assertEquals("u2\t1\tc1\tclaude", QuickCustomKeys.encode(listOf(QuickCustomKey("u2", "c1", "claude", enter = true))))
        assertEquals("u3\t0\tgs\tgit status", QuickCustomKeys.encode(listOf(QuickCustomKey("u3", "gs", "git status"))))
    }

    @Test
    fun `模式为 k 的行读成按键序列`() {
        val key = QuickCustomKeys.decode("u1\tk\tdt\tC-b d").single()
        assertTrue(key.keys)
        assertFalse(key.enter)
        assertEquals("C-b d", key.text)
    }

    private fun slotsOf(vararg keys: QuickKey): List<QuickSlot> = keys.map(QuickSlot::Preset)
}
