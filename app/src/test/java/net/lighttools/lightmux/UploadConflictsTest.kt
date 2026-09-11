package net.lighttools.lightmux

import kotlinx.coroutines.runBlocking
import net.lighttools.lightmux.sftp.DirNames
import net.lighttools.lightmux.sftp.LocalSource
import net.lighttools.lightmux.sftp.LocalTree
import net.lighttools.lightmux.sftp.LocalTreeFile
import net.lighttools.lightmux.sftp.UploadConflicts
import net.lighttools.lightmux.sftp.without
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

class UploadConflictsTest {

    @Test
    fun `同一个目录只列一次`() {
        var calls = 0
        val files = (1..20).map { "file$it.txt" }
        runBlocking {
            UploadConflicts.find(dirs = listOf(""), files = files) {
                calls++
                DirNames(names = emptySet(), dirs = emptySet())
            }
        }
        assertEquals(1, calls)
    }

    @Test
    fun `父目录不存在时整枝不再探测`() {
        val visited = mutableListOf<String>()
        runBlocking {
            UploadConflicts.find(
                dirs = listOf("", "a", "a/b"),
                files = listOf("a/b/c.txt"),
            ) { dir ->
                visited += dir
                if (dir == "") DirNames(names = setOf("other"), dirs = emptySet()) else null
            }
        }
        assertEquals(listOf(""), visited)
    }

    @Test
    fun `目录查询返回 null 时没有冲突`() {
        val hit = runBlocking {
            UploadConflicts.find(dirs = listOf(""), files = listOf("a.txt")) { null }
        }
        assertTrue(hit.isEmpty())
    }

    @Test
    fun `只挑出真正同名的文件`() {
        val hit = runBlocking {
            UploadConflicts.find(dirs = listOf(""), files = listOf("a.txt", "b.txt")) {
                DirNames(names = setOf("a.txt"), dirs = emptySet())
            }
        }
        assertEquals(setOf("a.txt"), hit)
    }

    @Test
    fun `远端同名的是目录也算冲突`() {
        val hit = runBlocking {
            UploadConflicts.find(dirs = listOf(""), files = listOf("css")) {
                DirNames(names = setOf("css"), dirs = setOf("css"))
            }
        }
        assertEquals(setOf("css"), hit)
    }
}

class LocalTreeFilterTest {

    private fun fakeTree(paths: List<String>, size: Long = 10L) = LocalTree(
        directories = emptyList(),
        files = paths.map { p -> LocalTreeFile(p, fakeSource(p, size)) },
        totalBytes = paths.size * size,
    )

    private fun fakeSource(n: String, len: Long) = object : LocalSource {
        override val name: String = n
        override val length: Long = len
        override fun open(): InputStream = throw UnsupportedOperationException()
    }

    @Test
    fun `跳过的文件消失且总字节数重算`() {
        val tree = fakeTree(listOf("a.txt", "b.txt", "c.txt"))
        val result = tree.without(setOf("b.txt"))
        assertEquals(listOf("a.txt", "c.txt"), result.files.map { it.path })
        assertEquals(20L, result.totalBytes)
    }

    @Test
    fun `空 skip 原样返回`() {
        val tree = fakeTree(listOf("a.txt"))
        assertTrue(tree.without(emptySet()) === tree)
    }

    @Test
    fun `目录列表不受影响`() {
        val tree = LocalTree(
            directories = listOf("css"),
            files = listOf(LocalTreeFile("css/app.css", fakeSource("app.css", 5L))),
            totalBytes = 5L,
        )
        val result = tree.without(setOf("css/app.css"))
        assertEquals(listOf("css"), result.directories)
        assertTrue(result.files.isEmpty())
    }
}
