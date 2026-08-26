package net.lighttools.lightmux

import net.lighttools.lightmux.update.ApkGuard
import net.lighttools.lightmux.update.ApkVerdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 安装前三重校验的决策。
 *
 * 这是整个应用里后果最重的一段纯逻辑：判错一次，用户就会亲手装上一个包名相同、
 * 签名却是别人的 APK——凭据、私钥、known_hosts 全在那一刻交出去。
 * 所以每一条拒绝路径都单独测一遍，而不是只测「正常能装」。
 */
class ApkGuardTest {

    private fun verify(
        apkPackageName: String? = PACKAGE,
        apkVersionCode: Long? = 2L,
        apkSignatures: Set<String> = setOf(SIGNATURE),
        currentPackageName: String = PACKAGE,
        currentVersionCode: Long = 1L,
        currentSignatures: Set<String> = setOf(SIGNATURE),
    ) = ApkGuard.verify(
        apkPackageName = apkPackageName,
        apkVersionCode = apkVersionCode,
        apkSignatures = apkSignatures,
        currentPackageName = currentPackageName,
        currentVersionCode = currentVersionCode,
        currentSignatures = currentSignatures,
    )

    @Test
    fun `三项都过才放行`() {
        assertEquals(ApkVerdict.Ok, verify())
    }

    @Test
    fun `包名不一致直接拒绝`() {
        assertEquals(ApkVerdict.PackageMismatch, verify(apkPackageName = "com.evil.app"))
    }

    @Test
    fun `versionCode 必须严格大于当前`() {
        assertEquals(ApkVerdict.NotNewer, verify(apkVersionCode = 1L, currentVersionCode = 1L))
        assertEquals(ApkVerdict.NotNewer, verify(apkVersionCode = 1L, currentVersionCode = 5L))
    }

    @Test
    fun `签名不一致直接拒绝`() {
        assertEquals(ApkVerdict.SignatureMismatch, verify(apkSignatures = setOf("beefbeef")))
    }

    @Test
    fun `多签名时集合必须完全相同`() {
        // 少一个签名者也算不一致：安装器认的是完整的签名集合
        assertEquals(
            ApkVerdict.SignatureMismatch,
            verify(apkSignatures = setOf(SIGNATURE), currentSignatures = setOf(SIGNATURE, "cafe")),
        )
        assertEquals(
            ApkVerdict.Ok,
            verify(apkSignatures = setOf("cafe", SIGNATURE), currentSignatures = setOf(SIGNATURE, "cafe")),
        )
    }

    @Test
    fun `APK 元信息读不出来时拒绝`() {
        assertEquals(ApkVerdict.Unreadable, verify(apkPackageName = null))
        assertEquals(ApkVerdict.Unreadable, verify(apkPackageName = ""))
        assertEquals(ApkVerdict.Unreadable, verify(apkVersionCode = null))
        assertEquals(ApkVerdict.Unreadable, verify(apkSignatures = emptySet()))
    }

    @Test
    fun `拿不到自身签名时拒绝而不是放行`() {
        // 没有比对基准的「通过」等于没校验，这里必须失败关闭
        assertEquals(ApkVerdict.Unreadable, verify(currentSignatures = emptySet()))
    }

    @Test
    fun `包名错且版本旧时先报包名`() {
        // 顺序有意为之：先报错得最离谱的那一项，用户看到的提示才有用
        assertEquals(
            ApkVerdict.PackageMismatch,
            verify(apkPackageName = "com.evil.app", apkVersionCode = 1L, apkSignatures = setOf("beef")),
        )
    }

    private companion object {
        const val PACKAGE = "net.lighttools.lightmux"
        const val SIGNATURE = "0badc0de"
    }
}
