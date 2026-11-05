package net.lighttools.lightmux

import net.lighttools.lightmux.sftp.Transfer
import net.lighttools.lightmux.sftp.TransferDirection
import net.lighttools.lightmux.sftp.TransferStatus
import net.lighttools.lightmux.sftp.TransferSummary
import org.junit.Assert.*
import org.junit.Test

class TransferSummaryTest {
    private fun transfer(
        id: String,
        batch: String,
        size: Long,
        sent: Long,
        status: TransferStatus,
        count: Int = 2,
    ) = Transfer(id, "host", "Host", TransferDirection.UPLOAD, id, sent, size, status,
        batchId = batch, batchSize = count)

    @Test fun `includes completed files from the current batch but not previous history`() {
        val summary = TransferSummary.from(listOf(
            transfer("old", "old", 10000, 10000, TransferStatus.DONE, 1),
            transfer("first", "new", 100, 100, TransferStatus.DONE),
            transfer("second", "new", 300, 100, TransferStatus.RUNNING),
        ))
        assertEquals(1, summary.completed)
        assertEquals(2, summary.count)
        assertEquals(200L, summary.transferredBytes)
        assertEquals(400L, summary.totalBytes)
        assertEquals(0.5f, summary.ratio!!, 0.001f)
    }

    @Test fun `weights bytes rather than averaging individual percentages`() {
        val summary = TransferSummary.from(listOf(
            transfer("small", "batch", 100, 100, TransferStatus.DONE),
            transfer("big", "batch", 900, 0, TransferStatus.QUEUED),
        ))
        assertEquals(0.1f, summary.ratio!!, 0.001f)
    }

    @Test fun `does not claim a percentage until all picked files have entered the queue`() {
        val summary = TransferSummary.from(listOf(
            transfer("first", "batch", 100, 50, TransferStatus.RUNNING, 3),
        ))
        assertEquals(3, summary.count)
        assertNull(summary.ratio)
    }

    @Test fun `unknown size remains indeterminate and still reports transferred bytes`() {
        val summary = TransferSummary.from(listOf(
            transfer("first", "batch", 100, 100, TransferStatus.DONE),
            transfer("unknown", "batch", 0, 100, TransferStatus.RUNNING),
        ))
        assertNull(summary.ratio)
        assertEquals(200L, summary.transferredBytes)
    }

    @Test fun `an unknown size failure does not imply all bytes were transferred`() {
        val summary = TransferSummary.from(listOf(
            transfer("unknown", "batch", 0, 100, TransferStatus.FAILED, 1),
        ))
        assertNull(summary.ratio)
        assertEquals(0, summary.completed)
        assertEquals(100L, summary.transferredBytes)
    }

    @Test fun `after completion shows the latest batch including failures and cancellations`() {
        val summary = TransferSummary.from(listOf(
            transfer("old", "old", 10000, 10000, TransferStatus.DONE, 1),
            transfer("failed", "new", 100, 20, TransferStatus.FAILED),
            transfer("cancelled", "new", 100, 0, TransferStatus.CANCELLED),
        ))
        assertEquals(0, summary.completed)
        assertEquals(2, summary.count)
        assertEquals(0.1f, summary.ratio!!, 0.001f)
    }

    @Test fun `keeps completed batches in the same queue run until the entire run finishes`() {
        val summary = TransferSummary.from(listOf(
            transfer("old", "old", 10000, 10000, TransferStatus.DONE, 1),
            transfer("done", "first", 100, 100, TransferStatus.DONE, 1).copy(groupId = "run"),
            transfer("next", "second", 100, 0, TransferStatus.RUNNING, 1).copy(groupId = "run"),
        ))
        assertEquals(1, summary.completed)
        assertEquals(2, summary.count)
        assertEquals(0.5f, summary.ratio!!, 0.001f)
    }

    @Test fun `combines active batches and handles zero byte success`() {
        val active = TransferSummary.from(listOf(
            transfer("one", "one", 100, 50, TransferStatus.RUNNING, 1),
            transfer("two", "two", 100, 0, TransferStatus.QUEUED, 1),
        ))
        assertEquals(2, active.count)
        assertEquals(0.25f, active.ratio!!, 0.001f)
        val done = TransferSummary.from(listOf(transfer("empty", "empty", 0, 0, TransferStatus.DONE, 1)))
        assertEquals(1f, done.ratio!!, 0.001f)
        assertEquals(0, TransferSummary.from(emptyList()).count)
    }
}
