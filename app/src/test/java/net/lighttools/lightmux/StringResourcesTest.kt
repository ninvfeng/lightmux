package net.lighttools.lightmux

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 双语文案的护栏。
 *
 * CLAUDE.md 立的规矩是「`values/` 与 `values-zh/` 条目数必须相等」，但一直靠人肉数——
 * 漏翻一条，中文用户就在满屏中文里看到一句英文。
 *
 * 占位符对不上更糟：`getString(id, arg)` 会在运行期抛 `IllegalFormatException`，
 * 而这类崩溃只在那条分支被走到时才现形，多半是用户先撞上。本机跑不了真机，
 * 这种「靠约定维持、错了要很久才被发现」的东西正是纯 JVM 测试该兜住的。
 */
class StringResourcesTest {

    @Test
    fun `两种语言的条目一一对应`() {
        val english = entries(BASE).keys
        val chinese = entries(CHINESE).keys
        assertEquals("以下条目中文漏翻了", emptySet<String>(), english - chinese)
        assertEquals("以下条目只有中文，英文缺失", emptySet<String>(), chinese - english)
    }

    @Test
    fun `同一份文件里没有重名条目`() {
        // 重名不会报错，后一条会静默盖掉前一条——改了文案却不生效，最难查的一类问题
        listOf(BASE, CHINESE).forEach { file ->
            val names = rawNames(file)
            val duplicated = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            assertEquals("${file.parentFile?.name} 里有重名条目", emptySet<String>(), duplicated)
        }
    }

    @Test
    fun `占位符两边一致`() {
        val english = entries(BASE)
        val chinese = entries(CHINESE)
        // 排序后比较：中英语序不同，`%1$s` 和 `%2$s` 在译文里前后调换是正常的，
        // 要管的是「有没有少一个」和「%1$d 被译成了 %1$s」。
        english.forEach { (name, text) ->
            val translated = chinese[name] ?: return@forEach
            assertEquals(
                "$name 的占位符和英文对不上",
                placeholders(text),
                placeholders(translated),
            )
        }
    }

    // ---- 读取 ------------------------------------------------------------------

    private fun entries(file: File): Map<String, String> =
        elements(file).associate { it.getAttribute("name") to it.textContent }

    private fun rawNames(file: File): List<String> =
        elements(file).map { it.getAttribute("name") }

    private fun elements(file: File): List<Element> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    /** `%%` 是转义过的百分号（监控页那些 `CPU %1$s%%`），先摘掉再找真正的占位符。 */
    private fun placeholders(text: String): List<String> =
        PLACEHOLDER.findAll(text.replace("%%", "")).map { it.value }.sorted().toList()

    private companion object {

        val PLACEHOLDER = Regex("""%\d+\$[a-zA-Z]""")

        /**
         * 单测的工作目录是模块目录（`app/`），但直接从仓库根跑也该能过，
         * 所以两种起点都试一遍，免得换个运行方式就红。
         */
        val RES: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .take(4)
            .flatMap { sequenceOf(File(it, "src/main/res"), File(it, "app/src/main/res")) }
            .firstOrNull { it.isDirectory }
            ?: error("找不到 res 目录，工作目录是 ${File("").absolutePath}")

        val BASE = File(RES, "values/strings.xml")
        val CHINESE = File(RES, "values-zh/strings.xml")
    }
}
