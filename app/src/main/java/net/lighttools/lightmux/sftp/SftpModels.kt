package net.lighttools.lightmux.sftp

/**
 * 远端条目的类型。
 *
 * 符号链接**单独成一类**而不是在列表阶段就跟进去看指向哪：一个目录里可能有几十条链接，
 * 每条都额外 stat 一次要多几十个往返；真正需要知道「链接指向的是不是目录」的时刻只有一个——
 * 用户点了它（见 [SftpRepository.statFollowing]）。
 */
enum class RemoteFileType { FILE, DIRECTORY, SYMLINK, OTHER }

/**
 * 目录列表里的一条。
 *
 * [permissions] 是 POSIX 权限位（0644 这种）。留着它是为了保存编辑过的文件后能把权限**恢复回去**，
 * 不是为了显示——写完不恢复的话，用户编辑一次 `.sh` 就丢了执行位，而这种事要到下次运行脚本才被发现。
 */
data class RemoteEntry(
    val name: String,
    val path: String,
    val type: RemoteFileType,
    val sizeBytes: Long,
    val modifiedEpochSeconds: Long,
    val permissions: Int,
) {
    val isDirectory: Boolean get() = type == RemoteFileType.DIRECTORY
    val isHidden: Boolean get() = SftpPath.isHidden(name)
}

/**
 * 目录在前、再按名字排。
 *
 * 用 [String.CASE_INSENSITIVE_ORDER] 而不是默认的码点序：默认序会把 `Zebra` 排在 `apple` 前面，
 * 在文件管理器里看着就像乱序。
 */
fun List<RemoteEntry>.sortedForDisplay(): List<RemoteEntry> =
    sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })

/** 应用内编辑器读文件的结果。三种失败各说各的，不能笼统报「打不开」。 */
sealed interface TextLoad {

    data class Ok(val text: String) : TextLoad

    /** 超过 [SftpRepository.MAX_EDITABLE_BYTES]。手机上编辑几 MB 的文本既卡又没意义 */
    data class TooLarge(val sizeBytes: Long) : TextLoad

    /** 含 NUL 字节。硬当文本打开只会把二进制内容改坏，而用户看不出哪里坏了 */
    data object Binary : TextLoad
}

/**
 * 是不是二进制。
 *
 * 判据只有一个：**含 NUL 字节**。这是 `grep`、`git`、`vim` 共同的判据，
 * 简单、无误报（UTF-8 文本里不会出现 NUL），也不需要猜编码。
 */
fun looksBinary(bytes: ByteArray): Boolean = bytes.any { it == 0.toByte() }

enum class TransferDirection { UPLOAD, DOWNLOAD }

enum class TransferStatus {
    QUEUED,
    RUNNING,
    DONE,
    FAILED,
    CANCELLED;

    val finished: Boolean get() = this == DONE || this == FAILED || this == CANCELLED
}

/**
 * 后台传输队列里的一项。
 *
 * 不持有 `Uri`、流、协程这些活的东西——它要被 `StateFlow` 广播给 UI，
 * 必须是一份可以随便拷贝比较的快照。
 */
data class Transfer(
    val id: String,
    val hostId: String,
    val hostName: String,
    val direction: TransferDirection,
    val name: String,
    val transferredBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val status: TransferStatus = TransferStatus.QUEUED,
    val error: String? = null,
) {
    val active: Boolean get() = !status.finished

    /** 总长度未知（SAF 给不出长度）时返回 null——一条永远停在 0% 的进度条比不显示更糟。 */
    val ratio: Float?
        get() = if (totalBytes <= 0L) null
        else (transferredBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
}
