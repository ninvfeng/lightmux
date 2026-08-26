package net.lighttools.lightmux.ssh

import android.content.Context
import android.util.Base64
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.IOException
import java.security.MessageDigest
import java.security.PublicKey

/**
 * 主机公钥变了。**不要静默接受**——这可能是中间人，也可能只是重装了系统，
 * 但只有用户能判断，所以必须抛到 UI 让人来点。
 */
class HostKeyChangedException(
    val endpoint: String,
    val expected: String,
    val actual: String,
) : IOException("Host key for $endpoint changed: expected $expected, got $actual")

/**
 * TOFU（Trust On First Use）主机密钥校验。
 *
 * 用 SharedPreferences 而不是 DataStore：[HostKeyVerifier.verify] 是 sshj 在 KEX 线程上的**同步**回调，
 * 在里面 runBlocking 一个 DataStore 读取既丑又有死锁风险。指纹是几十字节的小数据，SharedPreferences 够用。
 *
 * 指纹格式与 OpenSSH 一致：`SHA256:<base64 无填充>`，用户可以直接和 `ssh-keyscan` 的输出对照。
 */
class KnownHosts(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("known_hosts", Context.MODE_PRIVATE)

    fun fingerprintOf(endpoint: String): String? = prefs.getString(endpoint, null)

    fun trust(endpoint: String, fingerprint: String) {
        prefs.edit().putString(endpoint, fingerprint).apply()
    }

    fun forget(endpoint: String) {
        prefs.edit().remove(endpoint).apply()
    }

    /**
     * 造一个绑定到 [endpoint] 的校验器。
     *
     * 身份用主机配置里的 `user@host:port` 而不是 sshj 回调给的 hostname——走跳板时
     * sshj 看到的是隧道另一端的地址，两次连接未必一致，用配置里的字符串才稳定。
     */
    fun verifierFor(endpoint: String): Verifier = Verifier(endpoint)

    inner class Verifier internal constructor(private val endpoint: String) : HostKeyVerifier {

        /**
         * 指纹不符时记在这里。
         *
         * 不在 verify() 里直接抛：sshj 会把 KEX 线程上的异常包成 TransportException 再重抛，
         * 类型信息就丢了。返回 false 让它正常失败，由 [SshConnection] 在 catch 里读这个字段还原成
         * [HostKeyChangedException]，UI 才拿得到 expected/actual 两个指纹去弹告警。
         */
        @Volatile
        var mismatch: HostKeyChangedException? = null
            private set

        /** 首次连接时记录下来的指纹，供 UI 展示「已信任」。 */
        @Volatile
        var acceptedFingerprint: String? = null
            private set

        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val actual = fingerprint(key)
            val known = fingerprintOf(endpoint)
            acceptedFingerprint = actual
            return when (known) {
                null -> {
                    trust(endpoint, actual)
                    true
                }

                actual -> true
                else -> {
                    mismatch = HostKeyChangedException(endpoint, known, actual)
                    false
                }
            }
        }

        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
    }

    companion object {

        /** OpenSSH 风格的 `SHA256:xxx` 指纹，算的是公钥的 SSH wire 编码。 */
        fun fingerprint(key: PublicKey): String {
            val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
            val digest = MessageDigest.getInstance("SHA-256").digest(blob)
            return "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
        }
    }
}
