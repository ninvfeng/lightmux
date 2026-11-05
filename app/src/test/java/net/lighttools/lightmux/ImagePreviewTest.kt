package net.lighttools.lightmux

import net.lighttools.lightmux.sftp.ImagePreview
import net.lighttools.lightmux.sftp.RemoteEntry
import net.lighttools.lightmux.sftp.RemoteFileType
import org.junit.Assert.*
import org.junit.Test

class ImagePreviewTest {
    private fun entry(path: String, type: RemoteFileType = RemoteFileType.FILE) = RemoteEntry(
        name = path.substringAfterLast('/'), path = path, type = type,
        sizeBytes = 10, modifiedEpochSeconds = 0, permissions = 0,
    )

    @Test fun `gallery preserves the visible directory order and ignores non images`() {
        val entries = listOf(entry("/photos/b.png"), entry("/photos/readme.txt"), entry("/photos/a.JPG"))
        assertEquals(listOf("/photos/b.png", "/photos/a.JPG"), ImagePreview.gallery("/photos/a.JPG", entries))
    }

    @Test fun `gallery excludes directories special files and images from other directories`() {
        val entries = listOf(
            entry("/photos/folder.jpg", RemoteFileType.DIRECTORY),
            entry("/photos/socket.jpg", RemoteFileType.OTHER),
            entry("/elsewhere/other.jpg"),
            entry("/photos/main.jpg"),
            entry("/photos/link.png", RemoteFileType.SYMLINK),
            entry("/photos/main.jpg"),
        )
        assertEquals(listOf("/photos/main.jpg", "/photos/link.png"), ImagePreview.gallery("/photos/main.jpg", entries))
    }

    @Test fun `missing clicked image falls back to itself instead of opening a different file`() {
        assertEquals(listOf("/photos/missing.jpg"),
            ImagePreview.gallery("/photos/missing.jpg", listOf(entry("/photos/other.jpg"))))
        assertEquals(listOf("/photos/only.jpg"), ImagePreview.gallery("/photos/only.jpg", emptyList()))
    }

    @Test fun `gallery uses the caller visibility selection without adding hidden images`() {
        val visible = listOf(entry("/photos/main.jpg"))
        val withHidden = visible + entry("/photos/.hidden.png")
        assertEquals(listOf("/photos/main.jpg"), ImagePreview.gallery("/photos/main.jpg", visible))
        assertEquals(listOf("/photos/main.jpg", "/photos/.hidden.png"), ImagePreview.gallery("/photos/main.jpg", withHidden))
    }

    @Test fun `recognizes supported image extensions without confusing directory names`() {
        for (name in listOf("photo.JPG", "photo.jpeg", "图.png", "a.webp", "a.GIF", "a.bmp")) {
            assertTrue(name, ImagePreview.supports("/images/$name"))
        }
        for (path in listOf("/photo.jpg/readme", "/a.svg", "/photo.png.txt", "/no-extension")) {
            assertFalse(path, ImagePreview.supports(path))
        }
    }

    @Test fun `limits both long edges and total decoded pixels`() {
        assertEquals(1, ImagePreview.sampleSize(1024, 1024))
        assertEquals(2, ImagePreview.sampleSize(4096, 4096))
        assertEquals(4, ImagePreview.sampleSize(12000, 100))
        assertEquals(4, ImagePreview.sampleSize(100, 12000))
        val sample = ImagePreview.sampleSize(Int.MAX_VALUE, Int.MAX_VALUE)
        val side = (Int.MAX_VALUE.toLong() + sample - 1) / sample
        assertTrue(side * side <= 4L * 1024 * 1024)
    }

    @Test fun `reads until EOF even when size hint is zero`() {
        val bytes = "image data".toByteArray()
        val result = ImagePreview.readBounded(0) { offset, buffer, length ->
            if (offset >= bytes.size) -1
            else minOf(3, length, bytes.size - offset.toInt()).also {
                bytes.copyInto(buffer, 0, offset.toInt(), offset.toInt() + it)
            }
        }
        assertArrayEquals(bytes, result)
    }

    @Test fun `rejects known oversize images before reading`() {
        assertNull(ImagePreview.readBounded(ImagePreview.MAX_BYTES + 1L) { _, _, _ ->
            error("must not read")
        })
    }

    @Test fun `accepts exact limit but stops growing files one byte beyond limit`() {
        for (size in listOf(ImagePreview.MAX_BYTES, ImagePreview.MAX_BYTES + 100)) {
            var readBytes = 0L
            val result = ImagePreview.readBounded(12) { offset, _, length ->
                if (offset >= size) -1
                else minOf(length.toLong(), size - offset).toInt().also { readBytes += it }
            }
            if (size == ImagePreview.MAX_BYTES) assertEquals(size, result!!.size)
            else assertNull(result)
            assertTrue(readBytes <= ImagePreview.MAX_BYTES + 1L)
        }
    }
}
