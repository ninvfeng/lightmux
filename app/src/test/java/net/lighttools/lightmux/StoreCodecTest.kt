package net.lighttools.lightmux

import net.lighttools.lightmux.data.AuthMethod
import net.lighttools.lightmux.data.RawAuth
import net.lighttools.lightmux.data.RawKey
import net.lighttools.lightmux.data.SshKey
import net.lighttools.lightmux.data.StoreCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 凭据编解码的往返测试。
 *
 * 假的加解密：`ok:` 开头的解得开，其余一律解不开——正好对应 Keystore 密钥没了的现实
 * （改锁屏方式、恢复出厂、跨设备恢复备份），真 [net.lighttools.lightmux.data.Secrets] 在 JVM 上跑不起来。
 */
class StoreCodecTest {

    private val dead = "v1:AAAA:BBBB"

    private fun encrypt(plain: String) = "ok:$plain"

    private fun decrypt(blob: String): String? = if (blob.startsWith("ok:")) blob.removePrefix("ok:") else null

    private fun roundTrip(auth: AuthMethod): AuthMethod =
        StoreCodec.decodeAuth(StoreCodec.encodeAuth(auth, ::encrypt), emptyList(), ::decrypt)

    // ---- 密码 ----

    @Test
    fun `正常密码往返无损`() {
        val raw = StoreCodec.encodeAuth(AuthMethod.Password("hunter2"), ::encrypt)
        assertEquals("ok:hunter2", raw.password)

        val decoded = StoreCodec.decodeAuth(raw, emptyList(), ::decrypt) as AuthMethod.Password
        assertEquals("hunter2", decoded.password)
        assertFalse(decoded.credentialLost)
        assertNull(decoded.cipher)
    }

    @Test
    fun `密码解不开时退化成空串并打上丢失标记`() {
        val decoded = StoreCodec.decodeAuth(
            RawAuth(type = "password", password = dead), emptyList(), ::decrypt
        ) as AuthMethod.Password

        assertEquals("", decoded.password)
        assertTrue(decoded.credentialLost)
        assertEquals(dead, decoded.cipher)
    }

    @Test
    fun `解不开的密码重新写盘时原样写回密文，而不是 encrypt 空串`() {
        val lost = StoreCodec.decodeAuth(RawAuth(type = "password", password = dead), emptyList(), ::decrypt)
        assertEquals(dead, StoreCodec.encodeAuth(lost, ::encrypt).password)
    }

    @Test
    fun `改另一台主机导致的整表重写，不会把丢失标记抹成空密码`() {
        // 这就是那条 bug 链：重写 → 再读 → credentialLost 变回 false → 拿空密码去撞服务端。
        var raw = RawAuth(type = "password", password = dead)
        repeat(3) {
            raw = StoreCodec.encodeAuth(StoreCodec.decodeAuth(raw, emptyList(), ::decrypt), ::encrypt)
        }
        val decoded = StoreCodec.decodeAuth(raw, emptyList(), ::decrypt) as AuthMethod.Password

        assertEquals(dead, raw.password)
        assertTrue(decoded.credentialLost)
        assertEquals("", decoded.password)
    }

    @Test
    fun `没存过密码不算凭据丢失`() {
        val decoded = StoreCodec.decodeAuth(RawAuth(type = "password"), emptyList(), ::decrypt)
        assertEquals(AuthMethod.Password(""), decoded)
        // 整个 auth 缺失的老记录也走密码这条路，行为不变
        assertEquals(AuthMethod.Password(""), StoreCodec.decodeAuth(null, emptyList(), ::decrypt))
    }

    // ---- 手工粘贴的私钥 ----

    @Test
    fun `正常私钥往返无损`() {
        val raw = StoreCodec.encodeAuth(AuthMethod.PrivateKey("PEM", "pass"), ::encrypt)
        assertEquals("ok:PEM", raw.pem)
        assertEquals("ok:pass", raw.passphrase)
        assertEquals(AuthMethod.PrivateKey("PEM", "pass"), StoreCodec.decodeAuth(raw, emptyList(), ::decrypt))
    }

    @Test
    fun `PEM 解不开时保住密文与丢失标记`() {
        val decoded = StoreCodec.decodeAuth(
            RawAuth(type = "key", pem = dead), emptyList(), ::decrypt
        ) as AuthMethod.PrivateKey

        assertEquals("", decoded.pem)
        assertTrue(decoded.credentialLost)
        assertEquals(dead, decoded.pemCipher)
        // 往返后仍是同一份密文，而不是 encrypt("")
        assertEquals(dead, StoreCodec.encodeAuth(decoded, ::encrypt).pem)
        assertTrue((roundTrip(decoded) as AuthMethod.PrivateKey).credentialLost)
    }

    @Test
    fun `口令解不开时私钥照常重新加密，只冻住那段口令密文`() {
        val decoded = StoreCodec.decodeAuth(
            RawAuth(type = "key", pem = "ok:PEM", passphrase = dead), emptyList(), ::decrypt
        ) as AuthMethod.PrivateKey

        assertEquals("PEM", decoded.pem)
        assertNull(decoded.passphrase)
        assertTrue(decoded.credentialLost)

        val rewritten = StoreCodec.encodeAuth(decoded, ::encrypt)
        assertEquals("ok:PEM", rewritten.pem)
        assertEquals(dead, rewritten.passphrase)
        assertTrue((StoreCodec.decodeAuth(rewritten, emptyList(), ::decrypt)).credentialLost)
    }

    // ---- 引用密钥库的私钥 ----

    @Test
    fun `引用密钥库时只落 keyId，PEM 读取时装配`() {
        val keys = listOf(SshKey(id = "k1", name = "laptop", pem = "PEM", passphrase = "pass"))
        val raw = StoreCodec.encodeAuth(AuthMethod.PrivateKey("PEM", "pass", keyId = "k1"), ::encrypt)

        assertEquals("k1", raw.keyId)
        assertNull(raw.pem)
        assertNull(raw.passphrase)

        val decoded = StoreCodec.decodeAuth(raw, keys, ::decrypt) as AuthMethod.PrivateKey
        assertEquals(AuthMethod.PrivateKey("PEM", "pass", keyId = "k1"), decoded)
    }

    @Test
    fun `keyId 指向的钥匙没了，算凭据丢失且往返不变`() {
        val raw = RawAuth(type = "key", keyId = "k1")
        val decoded = StoreCodec.decodeAuth(raw, emptyList(), ::decrypt) as AuthMethod.PrivateKey

        assertEquals("", decoded.pem)
        assertTrue(decoded.credentialLost)
        assertEquals(raw, StoreCodec.encodeAuth(decoded, ::encrypt))
    }

    @Test
    fun `keyId 指向的钥匙自己解不开，同样算凭据丢失`() {
        val keys = listOf(SshKey(id = "k1", name = "laptop", pem = "", pemCipher = dead))
        val decoded = StoreCodec.decodeAuth(RawAuth(type = "key", keyId = "k1"), keys, ::decrypt)
        assertTrue(decoded.credentialLost)
    }

    @Test
    fun `agent 往返不变`() {
        assertEquals(AuthMethod.Agent, roundTrip(AuthMethod.Agent))
    }

    @Test
    fun `无需认证往返不变`() {
        assertEquals(AuthMethod.None, roundTrip(AuthMethod.None))
    }

    // ---- 密钥库 ----

    @Test
    fun `钥匙正常往返无损`() {
        val raw = StoreCodec.encodeKey(SshKey(id = "k1", name = "laptop", pem = "PEM", passphrase = "pass"), ::encrypt)
        assertEquals("ok:PEM", raw.pem)
        assertEquals("ok:pass", raw.passphrase)

        val decoded = StoreCodec.decodeKey(raw, ::decrypt)
        assertEquals(SshKey(id = "k1", name = "laptop", pem = "PEM", passphrase = "pass"), decoded)
        assertFalse(decoded.broken)
    }

    @Test
    fun `钥匙的 PEM 解不开时留住名字与密文`() {
        val decoded = StoreCodec.decodeKey(RawKey(id = "k1", name = "laptop", pem = dead), ::decrypt)

        assertEquals("laptop", decoded.name)
        assertTrue(decoded.broken)
        assertEquals(dead, decoded.pemCipher)
        assertEquals(dead, StoreCodec.encodeKey(decoded, ::encrypt).pem)
    }

    @Test
    fun `钥匙的口令解不开时字段不会被整个丢掉`() {
        val raw = RawKey(id = "k1", name = "laptop", pem = "ok:PEM", passphrase = dead)
        val rewritten = StoreCodec.encodeKey(StoreCodec.decodeKey(raw, ::decrypt), ::encrypt)

        assertEquals("ok:PEM", rewritten.pem)
        assertEquals(dead, rewritten.passphrase)
    }

    // ---- 读-改-写 ----

    @Test
    fun `没有原始数据时以空列表为基线写入`() {
        val next = StoreCodec.rewrite<String>(
            raw = null,
            parse = { error("空数据不该走解析") },
            transform = { it + "a" },
            encode = { it.joinToString(",") },
        )
        assertEquals("a", next)
    }

    @Test
    fun `存储损坏时放弃写入，绝不覆盖原始数据`() {
        val next = StoreCodec.rewrite<String>(
            raw = "{坏掉的 JSON",
            parse = { null },
            transform = { it + "a" },
            encode = { error("解析失败时不该编码") },
        )
        assertNull(next)
    }

    @Test
    fun `解析得出来就照常改写`() {
        val next = StoreCodec.rewrite(
            raw = "a,b",
            parse = { it.split(",") },
            transform = { it + "c" },
            encode = { it.joinToString(",") },
        )
        assertEquals("a,b,c", next)
    }
}
