package net.lighttools.lightmux.data

/**
 * 按键序列里认得的具名键。
 *
 * 名字沿用 tmux `send-keys` 那套（`Enter` `Escape` `DC` `PPage`…），用 tmux 的人不用再学一份；
 * 顺带收下本 app 键帽上的写法（`Ent` `Esc` `PgUp` `Del`）与几个常见全拼，认起来不区分大小写。
 * 键码映射放 UI 层：这一层不引 Android，好让解析器跟着单测跑。
 */
enum class NamedKey(private vararg val aliases: String) {
    Enter("enter", "ent", "return"),
    Escape("escape", "esc"),
    Tab("tab"),
    Backspace("bspace", "backspace", "bs"),
    Delete("dc", "delete", "del"),
    Insert("ic", "insert", "ins"),
    Home("home"),
    End("end"),
    PageUp("pgup", "pageup", "ppage"),
    PageDown("pgdn", "pagedown", "npage"),
    Up("up"),
    Down("down"),
    Left("left"),
    Right("right"),
    F1("f1"), F2("f2"), F3("f3"), F4("f4"), F5("f5"), F6("f6"),
    F7("f7"), F8("f8"), F9("f9"), F10("f10"), F11("f11"), F12("f12"),
    ;

    companion object {
        fun of(name: String): NamedKey? {
            val lower = name.lowercase()
            return entries.firstOrNull { lower in it.aliases }
        }
    }
}

/**
 * 一次击键：一个字符或一个具名键，外加修饰键。[named] 与 [char] 恰有一个非空，只由 [KeyNotation] 构造。
 *
 * 修饰键是**写明的**，与粘滞 `Ctrl`/`Alt` 无关——序列的意义就在于按下去发什么是确定的。
 */
data class KeyStroke(
    val named: NamedKey? = null,
    val char: Char? = null,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
)

/**
 * 自定义键的「按键序列」记法：空格分隔的若干击键，每一击是
 * `[C-][M-][S-]<键>`，`<键>` 是单个字符或 [NamedKey] 的名字；`^x` 等价于 `C-x`。
 *
 * 例：`C-b d`（tmux detach）、`M-.`（readline 上一个参数）、`C-b 1`、`F5`、`Up Up Enter`。
 * `Space` 发空格、`BTab` 发反向 Tab（`S-Tab`），都是 tmux 的写法。
 *
 * 认不出的 token 让整条序列返回 `null`，而不是跳过它：一条 `C-b d` 少发了半截，
 * 后果是 `d` 单独打进了 shell——沉默地做一半比什么都不做更糟。
 */
object KeyNotation {

    fun parse(text: String): List<KeyStroke>? {
        val tokens = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        return tokens.map { parseToken(it) ?: return null }
    }

    private fun parseToken(token: String): KeyStroke? {
        var rest = token
        var ctrl = false
        var alt = false
        var shift = false
        // `C-` `M-` `S-` 可以叠：`C-M-x`。要求前缀后面还有东西，`C--` 才能解成 Ctrl 加 `-`
        while (rest.length > 2 && rest[1] == '-') {
            when (rest[0].lowercaseChar()) {
                'c' -> ctrl = true
                'm' -> alt = true
                's' -> shift = true
                else -> break
            }
            rest = rest.substring(2)
        }
        // 单独一个 `^` 是字符；`^x` 才是 Ctrl
        if (rest.length > 1 && rest[0] == '^') {
            ctrl = true
            rest = rest.substring(1)
        }
        return when {
            rest.length == 1 -> KeyStroke(char = rest[0], ctrl = ctrl, alt = alt, shift = shift)
            rest.equals("space", ignoreCase = true) -> KeyStroke(char = ' ', ctrl = ctrl, alt = alt, shift = shift)
            rest.equals("btab", ignoreCase = true) -> KeyStroke(named = NamedKey.Tab, ctrl = ctrl, alt = alt, shift = true)
            else -> NamedKey.of(rest)?.let { KeyStroke(named = it, ctrl = ctrl, alt = alt, shift = shift) }
        }
    }

    private val WHITESPACE = Regex("\\s+")
}
