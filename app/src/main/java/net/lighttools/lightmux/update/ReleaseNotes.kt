package net.lighttools.lightmux.update

/**
 * Release 说明的显示前处理。
 *
 * 正文来自 CHANGELOG（见 `scripts/release_notes.py`），那是写给 80 列宽的屏幕看的 Markdown：
 * 一条说明手工折成三四行，带 `**` 和反引号。原样丢进手机上的对话框，每条都断成参差不齐的短行，
 * 中间还夹着一堆星号。这里只做两件事：**把硬折行接回去**、**擦掉标记符号**。
 *
 * 不引 Markdown 渲染库：更新弹窗一年也看不了几次，为它加一个依赖（还得查许可）不值。
 */
object ReleaseNotes {

    private val BULLET = Regex("""^[-*+]\s+""")

    private val RULE = Regex("""^(-{3,}|\*{3,}|_{3,})$""")

    fun format(raw: String): String {
        val lines = mutableListOf<String>()
        // 上一行是标题 / 条目 / 段首，后面跟的普通行就是它被折断的下半截
        var canJoin = false
        for (original in raw.replace("\r\n", "\n").split('\n')) {
            val line = original.trim()
            val bullet = BULLET.find(line)
            when {
                // 分割线在窄屏上只是一行没有意义的横杠，当空行用
                line.isEmpty() || RULE.matches(line) -> {
                    if (lines.lastOrNull()?.isNotEmpty() == true) lines += ""
                    canJoin = false
                }

                line.startsWith("#") -> {
                    lines += strip(line.trimStart('#').trim())
                    canJoin = true
                }

                bullet != null -> {
                    lines += "• " + strip(line.substring(bullet.value.length))
                    canJoin = true
                }

                canJoin && lines.isNotEmpty() -> lines[lines.lastIndex] = join(lines.last(), strip(line))

                else -> {
                    lines += strip(line)
                    canJoin = true
                }
            }
        }
        return lines.joinToString("\n").trim()
    }

    private fun strip(text: String): String = text.replace("**", "").replace("`", "")

    /** 接回硬折行：两端都是 ASCII 才补空格，中文之间补了反而多一个空档。 */
    private fun join(left: String, right: String): String {
        if (left.isEmpty()) return right
        if (right.isEmpty()) return left
        val gap = left.last().code < 128 && right.first().code < 128
        return if (gap) "$left $right" else left + right
    }
}
