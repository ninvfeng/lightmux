package net.lighttools.lightmux.ui.terminal

import com.termux.terminal.KeyHandler

/**
 * 附加键栏的**粘滞**修饰键状态：按一下亮起，作用于下一个键，然后自动松开。
 *
 * 之所以要有这个东西：Android 软键盘没有 Ctrl 键，打不出 `Ctrl-B` 就等于 tmux 前缀键失效，
 * 整个产品的主打功能直接不可用（PRD §5）。「按住不放」在触屏上做不到，所以只能粘滞。
 *
 * 做成不可变值类型：Compose 直接把它塞进 State，同时这套状态机不带任何 Android 依赖，
 * 能在本机单测里跑——本项目没有真机，可测性得靠这种拆分换。
 */
data class StickyModifiers(val ctrl: Boolean = false, val alt: Boolean = false) {

    val any: Boolean get() = ctrl || alt

    fun toggleCtrl(): StickyModifiers = copy(ctrl = !ctrl)

    fun toggleAlt(): StickyModifiers = copy(alt = !alt)

    /**
     * 一个按键已经带上修饰符了，松开。
     *
     * 返回自身而不是新对象（当前没有任何修饰键时），是为了让 Compose 的相等判断短路掉无谓重组——
     * 这个方法会被每一次按键、每一次 onKeyUp 调到。
     */
    fun consumed(): StickyModifiers = if (any) NONE else this

    /** 转成 [KeyHandler] 认识的 keyMod 位，给方向键 / Esc / Tab 这类走 keyCode 的键用。 */
    fun keyMod(): Int =
        (if (ctrl) KeyHandler.KEYMOD_CTRL else 0) or (if (alt) KeyHandler.KEYMOD_ALT else 0)

    companion object {
        val NONE = StickyModifiers()
    }
}
