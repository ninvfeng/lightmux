package net.lighttools.lightmux

import net.lighttools.lightmux.sftp.TreeProgress
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TreeProgress] 是目录树上传里唯一真正有 bug 风险又看不见的一块：
 * 「每个文件的进度从 0 重新汇报」这种错只在传第二个文件时现形，
 * 真机上要等几分钟才看得到进度条掉回去。
 */
class TreeProgressTest {

    @Test
    fun `单文件按累计字节报告`() {
        val progress = TreeProgress()
        assertEquals(100L, progress.advance(100))
        assertEquals(300L, progress.advance(300))
    }

    @Test
    fun `第二个文件从 0 重报不会把总数打回去`() {
        val progress = TreeProgress()
        progress.advance(300)
        progress.nextFile()
        assertEquals(350L, progress.advance(50))
    }

    @Test
    fun `空文件零回调也要被结账`() {
        val progress = TreeProgress()
        progress.advance(100)
        progress.nextFile()
        // 空文件一次 advance 都不会有，直接进下一次 nextFile()
        progress.nextFile()
        assertEquals(100L, progress.transferred)
        assertEquals(150L, progress.advance(50))
    }

    @Test
    fun `文件之间的空档能读到累计值`() {
        val progress = TreeProgress()
        progress.advance(200)
        progress.nextFile()
        // 还没轮到下一个文件的回调，这段空档也要能读出已经传完的部分
        assertEquals(200L, progress.transferred)
    }
}
