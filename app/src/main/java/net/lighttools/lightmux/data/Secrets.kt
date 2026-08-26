package net.lighttools.lightmux.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 凭据的对称加解密。密钥存在 Android Keystore 里，**永远不出安全硬件**，
 * 所以就算 APK 被解包、DataStore 文件被 adb 拉走，拿到的也只是密文。
 *
 * 密文格式：`v1:<base64 IV>:<base64 密文+tag>`。带版本号是为了以后换算法时能识别旧数据。
 */
object Secrets {

    private const val TAG = "Secrets"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "lightmux_secrets"
    private const val PREFIX = "v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val B64 = Base64.NO_WRAP

    /** Keystore 操作有跨进程开销，密钥句柄可安全复用，缓存一份。 */
    @Volatile
    private var cached: SecretKey? = null

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "$PREFIX:${Base64.encodeToString(cipher.iv, B64)}:${Base64.encodeToString(body, B64)}"
    }

    /**
     * 解密失败返回 null 而不是抛异常。
     *
     * 用户清除应用数据、恢复出厂、或换机后恢复备份时，Keystore 密钥会消失而 DataStore 文件还在，
     * 此时密文已经**永久不可解**。这不是 bug，是预期路径：调用方应把它当作「凭据丢了，请重新输入」，
     * 而不是让整个主机列表加载崩掉。
     */
    fun decryptOrNull(blob: String): String? {
        val parts = blob.split(':')
        if (parts.size != 3 || parts[0] != PREFIX) return null
        return try {
            val iv = Base64.decode(parts[1], B64)
            val body = Base64.decode(parts[2], B64)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "credential decrypt failed, keystore key probably gone", e)
            null
        }
    }

    private fun key(): SecretKey {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: load().also { cached = it }
        }
    }

    private fun load(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // 每次自动重连都要解密凭据，要求生物识别会让后台重连直接卡死。
                // 应用锁（V2）是在 app 入口做，不该下沉到这一层。
                .setUserAuthenticationRequired(false)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
