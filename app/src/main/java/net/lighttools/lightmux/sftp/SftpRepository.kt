package net.lighttools.lightmux.sftp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.lighttools.lightmux.data.Host
import net.lighttools.lightmux.session.SessionManager
import net.lighttools.lightmux.ssh.KeyedMutex
import net.lighttools.lightmux.ssh.SshConnection
import net.lighttools.lightmux.ssh.SshIo
import net.schmizz.sshj.common.StreamCopier
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.xfer.LocalDestFile
import net.schmizz.sshj.xfer.LocalFileFilter
import net.schmizz.sshj.xfer.LocalSourceFile
import net.schmizz.sshj.xfer.TransferListener
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap

/** 上传源。抽成接口是为了让 SAF（`Uri` + `ContentResolver`）不必渗进这一层。 */
interface LocalSource {
    val name: String

    /** SAF 拿不到长度时为 0，此时进度条只能显示已传字节数。 */
    val length: Long

    fun open(): InputStream
}

/** 下载目标。同样只要一个流，具体是 SAF 还是别的由调用方决定。 */
fun interface LocalSink {
    fun open(): OutputStream
}

/**
 * 一整棵本地目录树，[SafTree.walk] 的产出。
 *
 * 目录与文件分两张表——SFTP 没有「写文件时自动建父目录」这回事，
 * 目录必须按 [directories] 的顺序（父在前）先逐个 `mkdirs`。
 */
class LocalTree(
    val directories: List<String>,
    val files: List<LocalTreeFile>,
    val totalBytes: Long,
)

/** 树里的一个文件。[path] 是相对树根的路径，可能带子目录（如 `css/app.css`）。 */
class LocalTreeFile(val path: String, val source: LocalSource)

/** 远端一个目录里有什么。[names] 是全部条目名（判重名用），[dirs] 是其中的真目录（决定还要不要往下探）。 */
class DirNames(val names: Set<String>, val dirs: Set<String>)

/** 用户取消了传输。从进度回调里抛出来打断 sshj 的复制循环，见 [SftpRepository.upload]。 */
class TransferCancelledException : IOException("transfer cancelled")

/**
 * SFTP 的调度层：连接、通道、串行化。
 *
 * **不走 `ExecPool`**：`ExecPool.withConnection` 在 block 执行期间持有该主机的 Mutex，
 * 而上传下载动辄几分钟，挂在那把锁上会把 tmux 探测和监控采集全堵死。
 * 所以这里自己维护 `hostId -> SshConnection`，页面进入时建、离开时关。
 *
 * 同一台主机上开**两条 SFTP channel**（浏览 / 传输），各自一把锁：
 * `SFTPClient` 不是线程安全的（请求 id 分配 + 包读写），所有请求必须串行；
 * 而只用一条通道的话，传一个大文件期间用户连目录都翻不动。
 * 一条 SSH 连接上多开一个 channel 几乎免费，这是 SSH 多路复用本来就该干的事。
 */
class SftpRepository(private val sessions: SessionManager) {

    /** 通道用途。浏览由文件页持有，传输由 [TransferQueue] 持有，两者生命周期互不相干。 */
    enum class Lane { BROWSE, TRANSFER }

    private class Channel(val client: SFTPClient) {
        /** SFTPClient 不是线程安全的，一条通道上的请求必须排队。 */
        val mutex = Mutex()

        val healthy: Boolean get() = runCatching { client.sftpEngine.subsystem.isOpen }.getOrDefault(false)
    }

    private class Link(val connection: SshConnection) {
        val channels = ConcurrentHashMap<Lane, Channel>()
    }

    private val links = ConcurrentHashMap<String, Link>()

    /**
     * 只保护「建 / 拆」连接与通道，**不覆盖请求本身**——否则又变成一把被大文件占住的全局锁。
     *
     * 一台主机一把，不是全局一把：建连要握手 + 认证，连不上时要等满 20 秒超时，
     * 用全局锁的话这段时间里所有主机的浏览和传输全排在后面。同一台主机内部仍然串行，
     * 那是想要的——两条 lane 首次同时进来只该拨一条连接。
     */
    private val linkLocks = KeyedMutex()

    // ---- 浏览 ----------------------------------------------------------------

    /** 登录后的起点。用 `canonicalize(".")` 拿真实家目录，别硬拼 `/home/<user>`（root 和容器里都不对）。 */
    suspend fun home(host: Host): String = onLane(host, Lane.BROWSE) { client ->
        SftpPath.normalize(client.canonicalize("."))
    }

    suspend fun list(host: Host, path: String): List<RemoteEntry> = onLane(host, Lane.BROWSE) { client ->
        client.ls(path).map { it.attributes.toEntry(SftpPath.normalize(it.path), it.name) }.sortedForDisplay()
    }

    /**
     * 只取名字的列目录，上传前的同名预检用。
     *
     * 不复用 [list]：那边要排序、要建 [RemoteEntry]，而预检只关心「有没有这个名字」。
     */
    suspend fun listNames(host: Host, path: String): DirNames? = onLane(host, Lane.BROWSE) { client ->
        val entries = try {
            client.ls(path)
        } catch (e: SFTPException) {
            // 目录不存在是预检里最常见的正常结果，返回 null；其余失败（权限不足、连接断）必须原样抛。
            // 把它们也当成「里面什么都没有」= 判定无冲突然后静默覆盖，那正是这次要消灭的东西。
            if (e.statusCode == Response.StatusCode.NO_SUCH_FILE ||
                e.statusCode == Response.StatusCode.NO_SUCH_PATH
            ) {
                return@onLane null
            }
            throw e
        }
        DirNames(
            names = entries.mapTo(mutableSetOf()) { it.name },
            dirs = entries.filter { it.isDirectory }.mapTo(mutableSetOf()) { it.name },
        )
    }

    /**
     * 跟随符号链接看它到底指向什么。
     *
     * 只在用户点了某条链接时才调用：目录里几十条链接逐个 stat 要多几十个往返，
     * 而绝大多数链接用户根本不会去点。
     */
    suspend fun statFollowing(host: Host, path: String): RemoteEntry? = onLane(host, Lane.BROWSE) { client ->
        runCatching { client.stat(path) }.getOrNull()?.toEntry(path, SftpPath.name(path))
    }

    suspend fun mkdir(host: Host, path: String) = onLane(host, Lane.BROWSE) { client ->
        client.mkdir(path)
    }

    /**
     * 新建空文件。
     *
     * **必须带 `EXCL`**：不带的话同名文件会被 `CREAT` 悄悄打开——若再配上 `TRUNC` 就是直接清空，
     * 用户以为自己新建了一个文件，实际上把原来那个几百行的配置删干净了。
     * 有 `EXCL` 时服务端直接拒绝，失败总比静默毁数据强。
     *
     * 权限位不设：交给服务端的 umask 决定，和上传新文件的处理一致。
     */
    suspend fun createFile(host: Host, path: String) = onLane(host, Lane.BROWSE) { client ->
        client.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)).close()
    }

    suspend fun rename(host: Host, from: String, to: String) = onLane(host, Lane.BROWSE) { client ->
        client.rename(from, to)
    }

    /** 删除。目录递归删——SFTP 的 `rmdir` 只删空目录，不自己走一遍的话用户点了没反应。 */
    suspend fun delete(host: Host, entry: RemoteEntry) = onLane(host, Lane.BROWSE) { client ->
        client.deleteRecursively(entry.path, entry.type)
    }

    /** 读小文本文件给应用内编辑器。超限与二进制**不抛异常**，作为结果返回，UI 才好分别提示。 */
    suspend fun readText(host: Host, path: String): TextLoad = onLane(host, Lane.BROWSE) { client ->
        val size = client.stat(path).size
        if (size > MAX_EDITABLE_BYTES) {
            TextLoad.TooLarge(size)
        } else {
            val bytes = client.open(path, EnumSet.of(OpenMode.READ)).use { it.readFully(size.toInt()) }
            if (looksBinary(bytes)) TextLoad.Binary else TextLoad.Ok(String(bytes, Charsets.UTF_8))
        }
    }

    /**
     * 保存编辑过的文件。
     *
     * 写之前先 `stat` 记下权限，写完 `setattr` 恢复：`open(CREAT|TRUNC)` 在部分服务端会按 umask
     * 重设权限位，用户编辑一次 `.sh` 脚本执行位就没了——而这种事要等到下次运行脚本才被发现。
     */
    suspend fun writeText(host: Host, path: String, text: String) = onLane(host, Lane.BROWSE) { client ->
        val permissions = runCatching { client.stat(path).permissions }.getOrNull()
        val bytes = text.toByteArray(Charsets.UTF_8)
        client.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { file ->
            var offset = 0
            while (offset < bytes.size) {
                val length = minOf(CHUNK_BYTES, bytes.size - offset)
                file.write(offset.toLong(), bytes, offset, length)
                offset += length
            }
        }
        if (permissions != null) {
            client.setattr(path, FileAttributes.Builder().withPermissions(permissions).build())
        }
    }

    // ---- 传输 ----------------------------------------------------------------

    /**
     * 上传。跑在传输通道上，和浏览互不阻塞。
     *
     * @param onProgress 已传字节数。**取消就靠它抛 [TransferCancelledException]**——
     *   sshj 的复制循环每写完一块都会回调一次，这是唯一能从外部叫停一次传输的干净时机
     */
    suspend fun upload(
        host: Host,
        source: LocalSource,
        remotePath: String,
        onProgress: (Long) -> Unit,
    ) = onLane(host, Lane.TRANSFER) { client ->
        val transfer = client.fileTransfer
        // SAF 给不出 POSIX 权限，preserveAttributes 会拿一个编出来的 0644 去 chmod 远端，
        // 覆盖掉服务端 umask 的决定。关掉它，新文件按服务端默认权限落地。
        transfer.preserveAttributes = false
        transfer.transferListener = ProgressListener(onProgress)
        transfer.upload(SourceAdapter(source), remotePath)
    }

    suspend fun download(
        host: Host,
        remotePath: String,
        sink: LocalSink,
        onProgress: (Long) -> Unit,
    ) = onLane(host, Lane.TRANSFER) { client ->
        val transfer = client.fileTransfer
        // 下载目标是 SAF 的 Uri，没有权限位和时间戳可设，保留属性只会白白报错
        transfer.preserveAttributes = false
        transfer.transferListener = ProgressListener(onProgress)
        transfer.download(remotePath, DestAdapter(sink))
    }

    /**
     * 上传整棵目录树到 [remoteRoot]（这就是要建的那个同名目录，不是它的父目录）。
     *
     * **整棵树在一次 [onLane] 里跑完**，不逐文件抢锁——否则队列里排着的其他传输会插进
     * 文件之间，用户看到走走停停的进度条，服务端目录也会长时间停在半截。
     *
     * 目录一律复用（`mkdirs` 天然幂等）。**同名文件覆盖还是跳过，这一层不决定**——
     * 调用方（[TransferQueue.submit]）在预检问过用户之后，把要跳过的那些从 [tree] 里剔掉再交进来，
     * 落到这里的每个文件都是「确定要写」的，照 `WRITE|CREAT|TRUNC` 直接盖。
     */
    suspend fun uploadTree(
        host: Host,
        tree: LocalTree,
        remoteRoot: String,
        onProgress: (Long) -> Unit,
    ) = onLane(host, Lane.TRANSFER) { client ->
        at(remoteRoot) { client.mkdirs(remoteRoot) }
        tree.directories.forEach { relative ->
            val path = SftpPath.join(remoteRoot, relative)
            at(path) { client.mkdirs(path) }
        }

        val progress = TreeProgress()
        val transfer = client.fileTransfer
        transfer.preserveAttributes = false
        transfer.transferListener = TreeProgressListener(progress, onProgress)
        tree.files.forEach { file ->
            val remotePath = SftpPath.join(remoteRoot, file.path)
            // 先报一次累计值再开传：sshj 只在「正在写」时回调，文件之间的空档没人认领取消，
            // 全是小文件的树几乎全是这种空档；progress() 的取消检查在节流之前，不会被吃掉。
            onProgress(progress.transferred)
            at(remotePath) { transfer.upload(SourceAdapter(file.source), remotePath) }
            progress.nextFile()
        }
        onProgress(progress.transferred)
    }

    /**
     * 给出错的相对路径。一棵树几百项，光一句 `Permission denied` 不知道是哪个文件。
     *
     * **必须先认领 [TransferCancelledException]**：它是 `IOException` 子类，
     * 不先放行就会被包成一条 `a/b.txt: transfer cancelled` 的 FAILED，
     * 而那本来是用户自己按的取消。
     */
    private inline fun at(path: String, block: () -> Unit) {
        try {
            block()
        } catch (e: TransferCancelledException) {
            throw e
        } catch (e: IOException) {
            throw IOException("$path: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    // ---- 连接与通道 ------------------------------------------------------------

    /**
     * 关掉这台主机的浏览通道。传输通道不受影响——退出文件页时后台传输必须继续。
     *
     * 故意**不等在途请求做完**：用户已经离开页面，那次 `ls` 的结果没人要了，
     * 直接把 channel 关掉让它抛异常返回，比让人多等几秒好。
     */
    suspend fun closeBrowsing(hostId: String) = closeLane(hostId, Lane.BROWSE)

    /** 队列排空时由 [TransferQueue] 调用。 */
    suspend fun closeTransfers(hostId: String) = closeLane(hostId, Lane.TRANSFER)

    /** 页面栈里已经没有文件页的主机，浏览通道就该关。 */
    suspend fun closeBrowsingExcept(hostIds: Set<String>) {
        links.keys.filterNot { it in hostIds }.forEach { closeBrowsing(it) }
    }

    private suspend fun <T> onLane(host: Host, lane: Lane, block: (SFTPClient) -> T): T =
        withContext(Dispatchers.IO) {
            val channel = channelFor(host, lane)
            channel.mutex.withLock { block(channel.client) }
        }

    private suspend fun channelFor(host: Host, lane: Lane): Channel = linkLocks[host.id].withLock {
        val existing = links[host.id]
        // 连接断了以后 SFTPClient 不能复用——channel 跟着连接一起没了，只能整个丢掉重建。
        if (existing != null && !existing.connection.isConnected) {
            links.remove(host.id)
            existing.closeQuietly()
        }
        val link = links.getOrPut(host.id) { Link(connect(host)) }
        link.channels[lane]?.takeIf { it.healthy }?.let { return@withLock it }
        link.channels.remove(lane)?.closeQuietly()
        Channel(link.connection.openSftp()).also { link.channels[lane] = it }
    }

    private fun connect(host: Host): SshConnection =
        SshConnection(host).apply { connectBlocking() }

    /**
     * 文件页为这台主机建的那条连接，没有则 null。
     *
     * 给 [net.lighttools.lightmux.ssh.ExecPool] 蹭：用户在文件页拉开快速切换抽屉时，
     * 这条连接就在手边，而侧通道以前看不见它，只能显示一份带时间戳的旧缓存
     * （「自动的动作不许拨号」是 PRD §4.3 定下的，不能为渲染抽屉去握手）。
     * 只读不拿所有权——关它的仍然是 [closeLane]，蹭的一方拿到的可能随时失效，
     * 和复用前台终端那条连接是同一种风险，exec 抛 IOException 上层照常提示。
     */
    fun liveConnection(hostId: String): SshConnection? =
        links[hostId]?.connection?.takeIf { it.isConnected }

    private suspend fun closeLane(hostId: String, lane: Lane) = linkLocks[hostId].withLock {
        val link = links[hostId] ?: return@withLock
        link.channels.remove(lane)?.closeQuietly()
        // 两条通道都空了才关连接：留着一条闲连接比每次进页面重新握手认证划算得多
        if (link.channels.isEmpty()) {
            links.remove(hostId)
            link.connection.close()
        }
    }

    /** 关 channel 要发包，而退出文件页这条路径是从主线程进来的，必须甩开——见 [SshIo]。 */
    private fun Channel.closeQuietly() = SshIo.quietly { client.close() }

    private fun Link.closeQuietly() {
        channels.values.forEach { it.closeQuietly() }
        channels.clear()
        connection.close()
    }

    /**
     * 目录递归删。**符号链接一律删链接本身**，跟进去删等于把链接指向的真实目录清空。
     *
     * 用显式栈而不是函数递归：深度由远端目录决定，`node_modules` 这种嵌套几十上百层的
     * 一路递归下去就是 StackOverflowError——它是 `Error` 不是 `Exception`，
     * 发生在 IO 协程里没有任何 catch 接得住，删一个目录能把整个 app 带走。
     *
     * 顺序仍是后序（子项删完才 rmdir 父目录），出错照样立刻抛出去中断整趟删除，
     * 只有同级目录之间的先后变了——那对结果没有影响。
     */
    private fun SFTPClient.deleteRecursively(path: String, type: RemoteFileType) {
        if (type != RemoteFileType.DIRECTORY) {
            rm(path)
            return
        }
        // 栈里只放目录：非目录当场 rm 掉，不占空间。展开过的目录留在栈底等子项删完再 rmdir。
        val pending = ArrayDeque<PendingDir>()
        pending.addLast(PendingDir(path))
        while (pending.isNotEmpty()) {
            val dir = pending.last()
            if (dir.listed) {
                pending.removeLast()
                rmdir(dir.path)
                continue
            }
            dir.listed = true
            ls(dir.path).forEach { child ->
                val childPath = SftpPath.normalize(child.path)
                if (child.attributes.type.toRemoteType() == RemoteFileType.DIRECTORY) {
                    pending.addLast(PendingDir(childPath))
                } else {
                    rm(childPath)
                }
            }
        }
    }

    /** [deleteRecursively] 的栈帧。[listed] 区分「还没展开」和「子项已删完，该 rmdir 了」。 */
    private class PendingDir(val path: String, var listed: Boolean = false)

    private fun RemoteFile.readFully(sizeHint: Int): ByteArray {
        val buffer = ByteArray(CHUNK_BYTES)
        val out = java.io.ByteArrayOutputStream(sizeHint.coerceIn(0, MAX_EDITABLE_BYTES.toInt()))
        var offset = 0L
        while (true) {
            val read = read(offset, buffer, 0, buffer.size)
            if (read < 0) break
            out.write(buffer, 0, read)
            offset += read
        }
        return out.toByteArray()
    }

    private fun FileAttributes.toEntry(path: String, name: String) = RemoteEntry(
        name = name,
        path = path,
        type = type.toRemoteType(),
        sizeBytes = size,
        modifiedEpochSeconds = mtime,
        permissions = mode.permissionsMask,
    )

    companion object {

        /**
         * 应用内编辑器的上限。
         *
         * 512 KB 不是随手取的：再大一点，Compose 的 `TextField` 在手机上每敲一个字都要重排整篇，
         * 卡到没法用。超限的文件让用户去终端里开 vim，那才是对的工具。
         */
        const val MAX_EDITABLE_BYTES = 512L * 1024L

        private const val CHUNK_BYTES = 32 * 1024
    }
}

private fun FileMode.Type.toRemoteType(): RemoteFileType = when (this) {
    FileMode.Type.DIRECTORY -> RemoteFileType.DIRECTORY
    FileMode.Type.REGULAR -> RemoteFileType.FILE
    FileMode.Type.SYMLINK -> RemoteFileType.SYMLINK
    else -> RemoteFileType.OTHER
}

/** 把 sshj 的传输回调收窄成「已传了多少字节」。目录那一支用不上——我们只传单个文件。 */
private class ProgressListener(private val onProgress: (Long) -> Unit) : TransferListener {

    override fun directory(name: String): TransferListener = this

    override fun file(name: String, size: Long): StreamCopier.Listener =
        StreamCopier.Listener { transferred -> onProgress(transferred) }
}

/**
 * 整棵树上传时的累计进度。**internal** 而非 private——单测要验证它（见 `TreeProgressTest`），
 * 参照 `KeyedMutexTest` 测 `internal class KeyedMutex` 的先例。
 *
 * sshj 的 [StreamCopier.Listener] 回调给的是**当前文件**已传字节，每个文件从 0 重算，
 * 所以要靠这里的 [settled] 把「已经翻篇的文件」攒住，[current] 只装正在传的那个。
 * **按实际传过的字节数结账**，不是文件声称的长度——SAF 的 SIZE 可能是 0 或过期值。
 */
internal class TreeProgress {
    private var settled = 0L
    private var current = 0L

    /** 累计已传字节，文件之间的空档也能读到——没有回调不代表进度条该停摆。 */
    val transferred: Long get() = settled + current

    /** 收到当前文件的一次回调，[bytes] 是该文件的累计值（不是增量）。 */
    fun advance(bytes: Long): Long {
        current = bytes
        return transferred
    }

    /**
     * 结掉当前文件，并进 [settled]。
     *
     * 空文件在 sshj 里一次回调都不会有（`StreamCopier` 读到 -1 直接跳出），
     * 靠每上传完一个文件（不论空不空）都调一次这个来结账，两次连调也无害。
     */
    fun nextFile() {
        settled += current
        current = 0L
    }
}

/** [TreeProgress] 与 sshj 回调之间的胶水，十行搞定。 */
private class TreeProgressListener(
    private val progress: TreeProgress,
    private val onProgress: (Long) -> Unit,
) : TransferListener {

    override fun directory(name: String): TransferListener = this

    override fun file(name: String, size: Long): StreamCopier.Listener =
        StreamCopier.Listener { transferred -> onProgress(progress.advance(transferred)) }
}

/** [LocalSource] → sshj。只管单个文件；目录树上传见 [SftpRepository.uploadTree]，逐文件复用这个适配器。 */
private class SourceAdapter(private val source: LocalSource) : LocalSourceFile {

    override fun getName(): String = source.name

    override fun getLength(): Long = source.length

    override fun getInputStream(): InputStream = source.open()

    /** 只在 `preserveAttributes` 打开时才会被读到，而上传时我们把它关了。 */
    override fun getPermissions(): Int = DEFAULT_PERMISSIONS

    override fun isFile(): Boolean = true

    override fun isDirectory(): Boolean = false

    override fun getChildren(filter: LocalFileFilter?): Iterable<LocalSourceFile> = emptyList()

    override fun providesAtimeMtime(): Boolean = false

    override fun getLastAccessTime(): Long = 0L

    override fun getLastModifiedTime(): Long = 0L

    private companion object {
        /** 0644。用不上，但接口要求给一个数。 */
        const val DEFAULT_PERMISSIONS = 420
    }
}

/** [LocalSink] → sshj。 */
private class DestAdapter(private val sink: LocalSink) : LocalDestFile {

    /** 恒为 0：SAF 的目标文件是新建的，续传没有意义（也拿不到已写长度）。 */
    override fun getLength(): Long = 0L

    override fun getOutputStream(): OutputStream = sink.open()

    override fun getOutputStream(append: Boolean): OutputStream = sink.open()

    override fun getChild(name: String): LocalDestFile = this

    /** 用户在系统选择器里已经指定了保存位置和文件名，远端叫什么不影响落地到哪。 */
    override fun getTargetFile(filename: String): LocalDestFile = this

    override fun getTargetDirectory(dirname: String): LocalDestFile =
        throw IOException("downloading a directory is not supported")

    override fun setPermissions(perms: Int) = Unit

    override fun setLastAccessedTime(t: Long) = Unit

    override fun setLastModifiedTime(t: Long) = Unit
}
