package net.lighttools.lightmux

import net.lighttools.lightmux.data.KeyNotation
import net.lighttools.lightmux.data.KeyStroke
import net.lighttools.lightmux.data.NamedKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyNotationTest {

    @Test
    fun `单字符就是那个字符`() {
        assertEquals(listOf(KeyStroke(char = 'd')), KeyNotation.parse("d"))
    }

    @Test
    fun `空格分隔成多击`() {
        assertEquals(
            listOf(KeyStroke(char = 'b', ctrl = true), KeyStroke(char = 'd')),
            KeyNotation.parse("C-b d"),
        )
    }

    /** `^x` 是 tmux 与键帽上都常见的写法，得和 `C-x` 一个意思；单独的 `^` 只能是字符本身。 */
    @Test
    fun `脱字符前缀等价于 Ctrl`() {
        assertEquals(KeyNotation.parse("C-b"), KeyNotation.parse("^b"))
        assertEquals(listOf(KeyStroke(char = '^')), KeyNotation.parse("^"))
        assertEquals(listOf(KeyStroke(char = '^', ctrl = true)), KeyNotation.parse("^^"))
    }

    @Test
    fun `Alt 前缀与修饰键叠加`() {
        assertEquals(listOf(KeyStroke(char = '.', alt = true)), KeyNotation.parse("M-."))
        assertEquals(listOf(KeyStroke(char = 'x', ctrl = true, alt = true)), KeyNotation.parse("C-M-x"))
    }

    @Test
    fun `修饰键前缀不分大小写`() {
        assertEquals(listOf(KeyStroke(char = 'x', ctrl = true)), KeyNotation.parse("c-x"))
        assertEquals(listOf(KeyStroke(char = 'x', alt = true)), KeyNotation.parse("m-x"))
    }

    @Test
    fun `Shift 前缀与 BTab 都是反向 Tab`() {
        val backTab = listOf(KeyStroke(named = NamedKey.Tab, shift = true))
        assertEquals(backTab, KeyNotation.parse("S-Tab"))
        assertEquals(backTab, KeyNotation.parse("BTab"))
    }

    /** 空格是分隔符，要发空格只能写 `Space`。 */
    @Test
    fun `Space 发空格`() {
        assertEquals(listOf(KeyStroke(char = ' ')), KeyNotation.parse("Space"))
        assertEquals(listOf(KeyStroke(char = ' ', ctrl = true)), KeyNotation.parse("C-Space"))
    }

    /** `-` 既是前缀分隔符又可能是要发的字符：前缀后面必须还有东西，`C--` 才不会被吃成空。 */
    @Test
    fun `连字符本身也能发`() {
        assertEquals(listOf(KeyStroke(char = '-', ctrl = true)), KeyNotation.parse("C--"))
        assertEquals(listOf(KeyStroke(char = '-')), KeyNotation.parse("-"))
    }

    @Test
    fun `具名键认别名且不分大小写`() {
        listOf("enter", "Enter", "Ent", "Return").forEach {
            assertEquals(it, listOf(KeyStroke(named = NamedKey.Enter)), KeyNotation.parse(it))
        }
        listOf("Esc", "Escape").forEach {
            assertEquals(it, listOf(KeyStroke(named = NamedKey.Escape)), KeyNotation.parse(it))
        }
        listOf("DC", "Del", "Delete").forEach {
            assertEquals(it, listOf(KeyStroke(named = NamedKey.Delete)), KeyNotation.parse(it))
        }
        listOf("PgUp", "PPage", "PageUp").forEach {
            assertEquals(it, listOf(KeyStroke(named = NamedKey.PageUp)), KeyNotation.parse(it))
        }
        assertEquals(listOf(KeyStroke(named = NamedKey.F5)), KeyNotation.parse("F5"))
        assertEquals(listOf(KeyStroke(named = NamedKey.Up)), KeyNotation.parse("Up"))
    }

    @Test
    fun `具名键也能带修饰键`() {
        assertEquals(listOf(KeyStroke(named = NamedKey.Up, ctrl = true)), KeyNotation.parse("C-Up"))
        assertEquals(listOf(KeyStroke(named = NamedKey.Enter, alt = true)), KeyNotation.parse("M-Enter"))
    }

    @Test
    fun `任意空白都能分隔且首尾空白忽略`() {
        val expected = listOf(KeyStroke(named = NamedKey.Up), KeyStroke(named = NamedKey.Up), KeyStroke(named = NamedKey.Enter))
        assertEquals(expected, KeyNotation.parse("Up Up Enter"))
        assertEquals(expected, KeyNotation.parse("Up\tUp   Enter"))
        assertEquals(expected, KeyNotation.parse("  Up Up Enter \t"))
    }

    /** 认不出一个 token 就整条作废——发了半截 `C-b` 再把 `d` 单独打进 shell 比什么都不发更糟。 */
    @Test
    fun `认不出的 token 让整条返回 null`() {
        assertNull(KeyNotation.parse("C-b foo"))
        assertNull(KeyNotation.parse("ab"))
        assertNull(KeyNotation.parse("C-"))
    }

    @Test
    fun `空串与纯空白返回 null`() {
        assertNull(KeyNotation.parse(""))
        assertNull(KeyNotation.parse("   "))
        assertNull(KeyNotation.parse("\t\n"))
    }

    /** 枚举名就是文档里写的主名（`PageUp` `F12`…），别名表漏了它用户照着文档写就认不出。 */
    @Test
    fun `每个具名键都能用枚举名解析回来`() {
        NamedKey.entries.forEach {
            assertEquals(it.name, it, NamedKey.of(it.name))
            assertEquals(it.name, listOf(KeyStroke(named = it)), KeyNotation.parse(it.name))
        }
    }
}
