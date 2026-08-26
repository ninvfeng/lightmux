package net.lighttools.lightmux

import net.lighttools.lightmux.update.RawRelease
import net.lighttools.lightmux.update.ReleaseParser
import net.lighttools.lightmux.update.UpdateChecker
import net.lighttools.lightmux.update.UpdateResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发布列表解析与「要不要提示更新」的决策。
 *
 * 测的是 [ReleaseParser.from] 而不是 [ReleaseParser.parse]：`org.json` 在 JVM 单测里是个
 * 只会抛 `Stub!` 的桩，`parse` 里那十几行 `optString` 没法在本机跑。判断逻辑全在 `from` 里。
 */
class ReleaseParserTest {

    private fun raw(
        tag: String? = "v0.2.0",
        prerelease: Boolean = false,
        draft: Boolean = false,
        notes: String? = "修了一堆 bug",
        assets: List<Pair<String, String>> = listOf("lightmux-0.2.0.apk" to APK_URL),
    ) = RawRelease(tag, prerelease, draft, notes, assets)

    private fun info(tag: String, prerelease: Boolean = false) =
        ReleaseParser.from(raw(tag = tag, prerelease = prerelease))!!

    // ---- from -----------------------------------------------------------------

    @Test
    fun `正常发布解析出版本号与 APK 直链`() {
        val info = ReleaseParser.from(raw())
        assertEquals("v0.2.0", info?.tag)
        assertEquals("0.2.0", info?.version)
        assertEquals(APK_URL, info?.apkUrl)
        assertEquals("修了一堆 bug", info?.notes)
    }

    @Test
    fun `assets 里没有 APK 时判为不可用`() {
        // 只挂了源码包的发布很常见（GitHub 自动生成），不能把 tar 当安装包递给用户
        assertNull(
            ReleaseParser.from(
                raw(assets = listOf("lightmux-0.2.0-sources.tar.gz" to "https://example.invalid/src.tar.gz"))
            )
        )
        assertNull(ReleaseParser.from(raw(assets = emptyList())))
    }

    @Test
    fun `APK 直链为空时判为不可用`() {
        assertNull(ReleaseParser.from(raw(assets = listOf("lightmux-0.2.0.apk" to ""))))
    }

    @Test
    fun `字段缺失时判为不可用`() {
        assertNull(ReleaseParser.from(null))
        assertNull(ReleaseParser.from(raw(tag = null)))
        assertNull(ReleaseParser.from(raw(tag = "   ")))
        assertNull(ReleaseParser.from(raw(tag = "latest")))
    }

    @Test
    fun `notes 缺失退化成空串而不是 null`() {
        assertEquals("", ReleaseParser.from(raw(notes = null))?.notes)
    }

    @Test
    fun `prerelease 标记被带出来`() {
        assertEquals(true, ReleaseParser.from(raw(prerelease = true))?.prerelease)
        assertEquals(false, ReleaseParser.from(raw())?.prerelease)
    }

    @Test
    fun `多个资产时取第一个 APK`() {
        val info = ReleaseParser.from(
            raw(
                assets = listOf(
                    "checksums.txt" to "https://example.invalid/sums",
                    "lightmux-0.2.0.apk" to APK_URL,
                    "lightmux-0.2.0-debug.apk" to "https://example.invalid/debug.apk",
                )
            )
        )
        assertEquals(APK_URL, info?.apkUrl)
    }

    @Test
    fun `草稿判为不可用`() {
        // 草稿的附件随时会被换掉，而且只有仓库成员看得见
        assertNull(ReleaseParser.from(raw(draft = true)))
    }

    // ---- evaluate -------------------------------------------------------------

    @Test
    fun `版本更高时提示更新`() {
        val release = info("v0.2.0")
        val result = UpdateChecker.evaluate(listOf(release), currentVersion = "0.1.9")
        assertTrue(result is UpdateResult.Available)
        assertEquals(release, (result as UpdateResult.Available).release)
    }

    @Test
    fun `版本相同或更低时不提示`() {
        val releases = listOf(info("v0.2.0"))
        assertEquals(UpdateResult.UpToDate, UpdateChecker.evaluate(releases, currentVersion = "0.2.0"))
        assertEquals(UpdateResult.UpToDate, UpdateChecker.evaluate(releases, currentVersion = "0.3.0"))
    }

    @Test
    fun `预发布版不推给普通用户`() {
        val releases = listOf(info("v0.3.0", prerelease = true))
        assertEquals(UpdateResult.UpToDate, UpdateChecker.evaluate(releases, currentVersion = "0.2.0"))
    }

    @Test
    fun `没有可用发布时当作已是最新`() {
        assertEquals(UpdateResult.UpToDate, UpdateChecker.evaluate(emptyList(), currentVersion = "0.1.0"))
    }

    @Test
    fun `按版本号挑最新而不是按列表顺序`() {
        // CNB 只给整个列表，没有 latest 端点，顺序也不该当成契约
        val releases = listOf(info("v0.2.0"), info("v0.10.0"), info("v0.9.0"))
        val result = UpdateChecker.evaluate(releases, currentVersion = "0.2.0")
        assertEquals("0.10.0", (result as UpdateResult.Available).release.version)
    }

    @Test
    fun `最新的是预发布版时退回上一个正式版`() {
        // 不能因为顶上挂着个 rc 就把正式版一起漏掉
        val releases = listOf(info("v0.4.0", prerelease = true), info("v0.3.0"))
        val result = UpdateChecker.evaluate(releases, currentVersion = "0.2.0")
        assertEquals("0.3.0", (result as UpdateResult.Available).release.version)
    }

    private companion object {
        const val APK_URL = "https://example.invalid/lightmux-0.2.0.apk"
    }
}
