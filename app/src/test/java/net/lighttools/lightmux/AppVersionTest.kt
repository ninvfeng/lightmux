package net.lighttools.lightmux

import net.lighttools.lightmux.update.AppVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本号比较的单测。
 *
 * 这段逻辑写错不会崩、不会报错，只会让「有新版」在跨十位数时静默失效（`0.10.0` 被判成小于 `0.2.0`），
 * 或者更糟——把用户引向一个更旧的包。没有真机可测的情况下，它是更新功能里唯一能自证正确的部分。
 */
class AppVersionTest {

    // ---- parse ----------------------------------------------------------------

    @Test
    fun `parse 剥掉 v 前缀`() {
        assertEquals(listOf(0, 2, 0), AppVersion.parse("v0.2.0"))
        assertEquals(listOf(0, 2, 0), AppVersion.parse("V0.2.0"))
        assertEquals(listOf(0, 2, 0), AppVersion.parse("  0.2.0 "))
    }

    @Test
    fun `parse 忽略预发布与构建元数据`() {
        assertEquals(listOf(1, 2, 0), AppVersion.parse("v1.2.0-rc1"))
        assertEquals(listOf(1, 2, 0), AppVersion.parse("1.2.0+build3"))
    }

    @Test
    fun `parse 拒绝非法版本串`() {
        assertNull(AppVersion.parse(null))
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("latest"))
        assertNull(AppVersion.parse("1.x.0"))
        assertNull(AppVersion.parse("v"))
        assertNull(AppVersion.parse("1..0"))
        // 溢出 Int 的段落也算非法，不能悄悄回绕成负数
        assertNull(AppVersion.parse("1.99999999999.0"))
    }

    // ---- compare --------------------------------------------------------------

    @Test
    fun `compare 按段比数字而不是比字符串`() {
        assertTrue(AppVersion.compare("0.2.0", "0.1.9") > 0)
        assertTrue(AppVersion.compare("0.10.0", "0.2.0") > 0)
        assertTrue(AppVersion.compare("0.1.0", "0.1.1") < 0)
        assertTrue(AppVersion.compare("1.0.0", "0.10.0") > 0)
    }

    @Test
    fun `compare 位数不等按补零处理`() {
        assertEquals(0, AppVersion.compare("1.0", "1.0.0"))
        assertEquals(0, AppVersion.compare("1", "1.0.0"))
        assertTrue(AppVersion.compare("1.0.1", "1.0") > 0)
    }

    @Test
    fun `compare 遇到非法串不抛异常`() {
        assertEquals(0, AppVersion.compare("garbage", "also-garbage"))
        assertTrue(AppVersion.compare("1.0.0", "garbage") > 0)
        assertTrue(AppVersion.compare(null, "0.0.1") < 0)
    }

    // ---- isNewer --------------------------------------------------------------

    @Test
    fun `isNewer 只在严格更新时为真`() {
        assertTrue(AppVersion.isNewer("v0.2.0", "0.1.9"))
        assertTrue(AppVersion.isNewer("v0.10.0", "0.2.0"))
        assertFalse(AppVersion.isNewer("0.1.0", "0.1.0"))
        assertFalse(AppVersion.isNewer("0.1.0", "0.2.0"))
    }

    @Test
    fun `isNewer 对认不出的版本一律为假`() {
        assertFalse(AppVersion.isNewer("nightly", "0.1.0"))
        assertFalse(AppVersion.isNewer("0.2.0", "not-a-version"))
        assertFalse(AppVersion.isNewer(null, "0.1.0"))
    }
}
