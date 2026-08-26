package net.lighttools.lightmux

import net.lighttools.lightmux.update.ReleaseNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新说明的排版单测。
 *
 * 这段逻辑坏了不会崩，只会让更新弹窗里的说明重新变回一堆断行和星号——
 * 而那个弹窗要在真机上点「检查更新」且恰好有新版才看得到，人工回归几乎覆盖不到。
 */
class ReleaseNotesTest {

    @Test
    fun `硬折行接回同一条`() {
        val raw = """
            - 快捷栏加了组合键，
              去末尾的「管理」里加进来即可。
        """.trimIndent()
        assertEquals("• 快捷栏加了组合键，去末尾的「管理」里加进来即可。", ReleaseNotes.format(raw))
    }

    @Test
    fun `英文折行之间补空格，中文不补`() {
        assertEquals("hello world", ReleaseNotes.format("hello\nworld"))
        assertEquals("你好世界", ReleaseNotes.format("你好\n世界"))
    }

    @Test
    fun `擦掉强调与反引号，保留文字`() {
        assertEquals("• 键距终于能调了：^C 生效", ReleaseNotes.format("- **键距终于能调了**：`^C` 生效"))
    }

    @Test
    fun `标题去掉井号并自成一行`() {
        assertEquals("Added\n• 组合键", ReleaseNotes.format("### Added\n- 组合键"))
    }

    @Test
    fun `空行分段但不叠加，分割线当空行`() {
        assertEquals("甲\n\n乙", ReleaseNotes.format("甲\n\n\n---\n\n乙"))
    }

    @Test
    fun `条目之间不会被粘成一条`() {
        val out = ReleaseNotes.format("- 第一条\n- 第二条")
        assertEquals("• 第一条\n• 第二条", out)
    }

    @Test
    fun `空说明仍是空——调用方据此显示占位文案`() {
        assertTrue(ReleaseNotes.format("").isBlank())
        assertTrue(ReleaseNotes.format("\n---\n  \n").isBlank())
    }

    @Test
    fun `CRLF 与真实 CHANGELOG 段落`() {
        val raw = "### Changed\r\n\r\n- **快捷栏键位大小改成滑块**，24–48dp 连续可调，\r\n  不再是三档。\r\n"
        // 标题后的空行留着：真实说明里标题挨着条目会挤成一坨
        assertEquals("Changed\n\n• 快捷栏键位大小改成滑块，24–48dp 连续可调，不再是三档。", ReleaseNotes.format(raw))
    }
}
