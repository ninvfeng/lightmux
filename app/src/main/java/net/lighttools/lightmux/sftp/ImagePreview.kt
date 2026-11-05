package net.lighttools.lightmux.sftp

import java.io.ByteArrayOutputStream
import java.util.Locale

/** 原生解码器能覆盖的常用格式；GIF 只预览首帧，不额外引入动画或 SVG 库。 */
object ImagePreview {
    const val MAX_BYTES = 20 * 1024 * 1024
    private const val MAX_DIMENSION = 4096
    private const val MAX_PIXELS = 4L * 1024 * 1024
    private val extensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    fun supports(path: String): Boolean =
        SftpPath.name(path).substringAfterLast('.', "").lowercase(Locale.ROOT) in extensions

    /** 压缩文件很小也可能解出巨幅位图，所以同时限制最长边和总像素数。 */
    fun sampleSize(width: Int, height: Int): Int {
        require(width > 0 && height > 0)
        var sample = 1
        while (true) {
            val w = (width.toLong() + sample - 1) / sample
            val h = (height.toLong() + sample - 1) / sample
            if (w <= MAX_DIMENSION && h <= MAX_DIMENSION && w * h <= MAX_PIXELS) return sample
            sample *= 2
        }
    }

    /** stat 后远端仍可能变大，读取时也必须守上限；额外读一个字节区分刚好到顶与超限。 */
    fun readBounded(sizeHint: Long, read: (offset: Long, buffer: ByteArray, length: Int) -> Int): ByteArray? {
        if (sizeHint > MAX_BYTES) return null
        val out = ByteArrayOutputStream(sizeHint.coerceIn(0, 32 * 1024).toInt())
        val buffer = ByteArray(32 * 1024)
        var offset = 0L
        while (true) {
            val length = minOf(buffer.size.toLong(), MAX_BYTES + 1L - offset).toInt()
            val count = read(offset, buffer, length)
            if (count < 0) return out.toByteArray()
            check(count > 0 && count <= length) { "Invalid SFTP read length: $count" }
            offset += count
            if (offset > MAX_BYTES) return null
            out.write(buffer, 0, count)
        }
    }
}
