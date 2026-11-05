package net.lighttools.lightmux.sftp

/** 一轮传输包含排空前追加的所有批次；保留已完成文件，旧记录不稀释新一轮任务的进度。 */
data class TransferSummary(
    val completed: Int,
    val count: Int,
    val transferredBytes: Long,
    val totalBytes: Long,
    val ratio: Float?,
) {
    companion object {
        fun from(transfers: List<Transfer>): TransferSummary {
            val groupIds = transfers.filter { it.active }.mapTo(mutableSetOf()) { it.groupId }
                .ifEmpty { transfers.lastOrNull()?.let { mutableSetOf(it.groupId) } ?: mutableSetOf() }
            val current = transfers.filter { it.groupId in groupIds }
            val groups = current.groupBy { it.batchId }.values
            val count = groups.sumOf { group -> maxOf(group.size, group.maxOf { it.batchSize }) }
            val transferred = current.sumOf { it.transferredBytes.coerceAtLeast(0) }
            val total = current.sumOf { maxOf(it.totalBytes, it.transferredBytes, 0) }
            // SAF 可能不给大小，多文件也可能还在陆续入队；此时给不定长条，别冒充准确百分比。
            val known = count == current.size && current.none {
                it.totalBytes <= 0 && it.status != TransferStatus.DONE
            }
            val ratio = if (known && total > 0) (transferred.toDouble() / total).toFloat().coerceIn(0f, 1f)
                else if (current.isNotEmpty() && current.all { it.status == TransferStatus.DONE } && known) 1f
                else null
            return TransferSummary(current.count { it.status == TransferStatus.DONE }, count, transferred, total, ratio)
        }
    }
}
