package net.lighttools.lightmux

import com.termux.terminal.KeyHandler
import net.lighttools.lightmux.ui.terminal.StickyModifiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StickyModifiersTest {

    @Test
    fun `初始没有任何修饰键`() {
        assertFalse(StickyModifiers.NONE.any)
        assertEquals(0, StickyModifiers.NONE.keyMod())
    }

    @Test
    fun `按一下 Ctrl 亮起，下一个键消费后自动松开`() {
        val pressed = StickyModifiers.NONE.toggleCtrl()
        assertTrue(pressed.ctrl)
        assertTrue(pressed.any)

        val afterKey = pressed.consumed()
        assertFalse(afterKey.ctrl)
        assertFalse(afterKey.any)
    }

    @Test
    fun `再按一下取消，不必等按键消费`() {
        assertFalse(StickyModifiers.NONE.toggleCtrl().toggleCtrl().ctrl)
        assertFalse(StickyModifiers.NONE.toggleAlt().toggleAlt().alt)
    }

    @Test
    fun `Ctrl 与 Alt 互不影响，可以同时粘滞`() {
        val both = StickyModifiers.NONE.toggleCtrl().toggleAlt()
        assertTrue(both.ctrl)
        assertTrue(both.alt)
        assertEquals(KeyHandler.KEYMOD_CTRL or KeyHandler.KEYMOD_ALT, both.keyMod())
        // 一次按键把两个都带走
        assertEquals(StickyModifiers.NONE, both.consumed())
    }

    @Test
    fun `没有修饰键时消费返回同一个对象，避免无谓重组`() {
        val none = StickyModifiers.NONE
        assertSame(none, none.consumed())
    }

    @Test
    fun `keyMod 只带按下的那一个`() {
        assertEquals(KeyHandler.KEYMOD_CTRL, StickyModifiers.NONE.toggleCtrl().keyMod())
        assertEquals(KeyHandler.KEYMOD_ALT, StickyModifiers.NONE.toggleAlt().keyMod())
    }
}
