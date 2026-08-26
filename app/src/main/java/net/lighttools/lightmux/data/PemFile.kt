package net.lighttools.lightmux.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 从本地文件读进来的私钥。[name] 用作密钥的默认名字，拿不到就为 null。 */
data class PickedKeyFile(val name: String?, val pem: String)

/**
 * 用系统文件选择器挑一个私钥文件读进来。
 *
 * 手机上私钥的来路基本只有两条：从别处粘贴，或者文件已经躺在下载目录 / 网盘 / U 盘里。
 * 后者用粘贴是很痛苦的——几十行 base64 在手机上选中复制经常掉字符。
 */
object PemFile {

    private const val TAG = "PemFile"

    /**
     * 私钥再大也就几 KB（RSA-4096 约 3.2KB）。设上限不是怕内存，是怕用户误选了一个视频，
     * 那会把几百 MB 读成字符串塞进输入框。
     */
    private const val MAX_BYTES = 128 * 1024

    /** @return null = 读不了 / 超过 [MAX_BYTES] / 不是文本。格式是否被支持由 [KeyFormat] 另判 */
    suspend fun read(resolver: ContentResolver, uri: Uri): PickedKeyFile? = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                // 先读 MAX_BYTES+1 字节：超了就直接丢，不给「先读完再检查」把大文件拉进内存的机会。
                val buffer = ByteArray(MAX_BYTES + 1)
                var read = 0
                while (read < buffer.size) {
                    val n = input.read(buffer, read, buffer.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (read > MAX_BYTES) null else buffer.copyOf(read)
            }
        }.onFailure { Log.w(TAG, "cannot read key file", it) }.getOrNull() ?: return@withContext null

        // 私钥是 ASCII 文本，出现 NUL 说明选到的是二进制文件，别把乱码塞进输入框
        if (bytes.isEmpty() || bytes.any { it == 0.toByte() }) return@withContext null
        PickedKeyFile(name = displayName(resolver, uri), pem = String(bytes, Charsets.UTF_8))
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
