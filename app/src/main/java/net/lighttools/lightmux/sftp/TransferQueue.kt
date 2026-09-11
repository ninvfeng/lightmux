package net.lighttools.lightmux.sftp

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.lighttools.lightmux.data.Host
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 后台传输队列。**活在 Application 作用域**（挂在 `LightmuxApp` 上）：
 * 用户传一个大文件时理应能退出文件页去干别的，传输不该跟着页面一起没。
 *
 * **一次只跑一个**。并行传输在移动网络上只会互相抢同一份带宽，总时长不变，
 * 却让每条进度条都变慢、也更容易把服务端的并发限制撞满。
 * `kotlinx` 的 [Mutex] 是公平的，排队顺序天然就是入队顺序，不用自己维护队列结构。
 *
 * 本地文件一律走 SAF（[ContentResolver]），**不申请任何存储权限**：
 * `WRITE_EXTERNAL_STORAGE` 在 Android 10+ 已经失效，`MANAGE_EXTERNAL_STORAGE` 要单独审核，
 * 为了「传个文件」去申请它们，代价是应用上不了架。
 */
class TransferQueue(
    private val repository: SftpRepository,
    private val resolver: ContentResolver,
    private val scope: CoroutineScope,
) {

    private val _transfers = MutableStateFlow<List<Transfer>>(emptyList())
    val transfers: StateFlow<List<Transfer>> = _transfers.asStateFlow()

    private val lane = Mutex()

    /**
     * 入队闸门。
     *
     * SAF 的 `query` 是跨进程调用，遇上第三方 provider 一次要几百毫秒，而多选上传会连发
     * 十几次——全压在主线程上就是十几次串行 binder，点完「上传」界面先卡一下。
     * 所以查询挪进了 IO，但入队顺序还必须是用户在选择器里挑的那个顺序，于是拿这把公平锁
     * 把「查询 + 入队」整段串起来：从 `Main.immediate` 起步，第一次 `withLock` 不挂起，
     * 排队顺序就等于调用顺序。
     */
    private val intake = Mutex()

    private val handles = ConcurrentHashMap<String, Handle>()

    /**
     * 一次传输的「活」部分：取消标记、正在读写的本地流、协程。这些都不能进 [Transfer] 快照。
     *
     * [stream] 是构造参数而不是类内新建：目录预检要在还没有 Handle 的时候就遍历完本地树，
     * 而 `SafSource` 的「流开了」回调在 walk 的当下就被烘进每个文件里了。
     * 把同一个 ref 在入队时交给 Handle，取消才关得掉正在读的那个流（否则整棵树都取消不掉）。
     */
    private class Handle(val stream: AtomicReference<Closeable?> = AtomicReference(null)) {

        val cancelled = AtomicBoolean(false)

        @Volatile
        var job: Job? = null

        fun cancel() {
            cancelled.set(true)
            runCatching { stream.getAndSet(null)?.close() }
            job?.cancel()
        }
    }

    /** 上传一个 SAF 选中的文件到 [remoteDir]。只由 [submit] 调——外部入口一律先过预检。 */
    private fun upload(host: Host, uri: Uri, remoteDir: String) {
        scope.launch(Dispatchers.Main.immediate) {
            intake.withLock {
                val (name, size) = withContext(Dispatchers.IO) {
                    (displayName(uri) ?: fallbackName()) to sizeOf(uri)
                }
                val transfer = newTransfer(host, TransferDirection.UPLOAD, name, size)
                enqueue(transfer) { handle ->
                    val source = object : LocalSource {
                        override val name: String = name
                        override val length: Long = transfer.totalBytes
                        override fun open(): InputStream = resolver.openInputStream(uri)
                            ?.also { handle.stream.set(it) }
                            ?: throw IOException("cannot open $uri")
                    }
                    repository.upload(host, source, SftpPath.join(remoteDir, name), progress(transfer.id, handle))
                }
            }
        }
    }

    /** 多选文件的预检：查名字 + 列一次目标目录。 */
    suspend fun scanFiles(host: Host, uris: List<Uri>, remoteDir: String): UploadPlan.Files {
        val picked = withContext(Dispatchers.IO) { uris.map { Picked(it, displayName(it)) } }
        val existing = repository.listNames(host, remoteDir)?.names.orEmpty()
        return UploadPlan.Files(host, remoteDir, picked, picked.mapNotNull { it.name }.filter { it in existing })
    }

    /** 目录的预检：遍历本地树 + 逐层探测远端。整个流程里最慢的一段。 */
    suspend fun scanFolder(host: Host, treeUri: Uri, remoteDir: String): UploadPlan.Folder {
        val name = withContext(Dispatchers.IO) { SafTree.rootName(resolver, treeUri) } ?: fallbackName()
        // ref 先于 Handle 存在：预检时还没有 Handle，但 SafSource 要在 walk 的当下就把回调烘进去
        val streams = AtomicReference<Closeable?>(null)
        val tree = SafTree.walk(resolver, treeUri) { streams.set(it) }
        val root = SftpPath.join(remoteDir, name)
        val hit = UploadConflicts.find(listOf("") + tree.directories, tree.files.map { it.path }) {
            repository.listNames(host, SftpPath.join(root, it))     // join(root, "") == root
        }
        return UploadPlan.Folder(
            host, name, root, tree, streams,
            tree.files.mapNotNull { f -> f.path.takeIf(hit::contains) },   // 按遍历顺序，读起来才像目录结构
        )
    }

    /**
     * 用户拍板后把计划落成传输。
     *
     * @param overwrite true = 同名的照传（服务端 WRITE|CREAT|TRUNC 覆盖），false = 同名的跳过、其余照传
     */
    fun submit(plan: UploadPlan, overwrite: Boolean) {
        val skip = if (overwrite) emptySet() else plan.conflicts.toSet()
        when (plan) {
            // name 为 null 时 `null in Set<String>` 恒 false，正好就是「取不到名字的一律直传」
            is UploadPlan.Files ->
                plan.picked.filterNot { it.name in skip }.forEach { upload(plan.host, it.uri, plan.remoteDir) }

            is UploadPlan.Folder -> {
                val tree = plan.tree.without(skip)
                val transfer = newTransfer(plan.host, TransferDirection.UPLOAD, plan.name, tree.totalBytes)
                // 全跳过导致一个文件都不剩时照样入队：mkdirs 幂等、一秒就「完成」，
                // 比什么都不发生更像「我的选择被执行了」。多文件那边没有这个壳，对话框消失就是全部反馈
                enqueue(transfer, Handle(plan.streams)) { handle ->
                    repository.uploadTree(plan.host, tree, plan.remoteRoot, progress(transfer.id, handle))
                }
            }
        }
    }

    /** 下载到用户在系统选择器里指定的位置。 */
    fun download(host: Host, entry: RemoteEntry, target: Uri) {
        val transfer = newTransfer(host, TransferDirection.DOWNLOAD, entry.name, entry.sizeBytes)
        enqueue(transfer) { handle ->
            val sink = LocalSink {
                // "wt" = 截断已有内容。用户可能挑了个同名旧文件覆盖，不截断会留下上一份的尾巴
                resolver.openOutputStream(target, "wt")
                    ?.also { handle.stream.set(it) }
                    ?: throw IOException("cannot open $target")
            }
            repository.download(host, entry.path, sink, progress(transfer.id, handle))
        }
    }

    fun cancel(id: String) {
        val hostId = _transfers.value.firstOrNull { it.id == id }?.hostId
        handles.remove(id)?.cancel()
        // 还没轮到的传输可能连协程体都没进过，状态只能在这里落
        finish(id, TransferStatus.CANCELLED)
        if (hostId != null) scope.launch { closeIfDrained(hostId) }
    }

    /** 清掉列表里已结束的项。进行中的不动。 */
    fun clearFinished() {
        _transfers.update { list -> list.filter { it.active } }
    }

    private fun newTransfer(
        host: Host,
        direction: TransferDirection,
        name: String,
        totalBytes: Long,
    ) = Transfer(
        id = UUID.randomUUID().toString(),
        hostId = host.id,
        hostName = host.name,
        direction = direction,
        name = name,
        totalBytes = totalBytes,
    )

    private fun enqueue(transfer: Transfer, handle: Handle = Handle(), body: suspend (Handle) -> Unit) {
        handles[transfer.id] = handle
        _transfers.update { it + transfer }

        handle.job = scope.launch {
            try {
                lane.withLock {
                    if (handle.cancelled.get()) return@withLock
                    update(transfer.id) { it.copy(status = TransferStatus.RUNNING) }
                    body(handle)
                    // 最后一次进度回调被节流吃掉了，成功时把进度补满，否则会停在 97%
                    update(transfer.id) { it.copy(transferredBytes = maxOf(it.transferredBytes, it.totalBytes)) }
                    finish(transfer.id, TransferStatus.DONE)
                }
            } catch (e: CancellationException) {
                finish(transfer.id, TransferStatus.CANCELLED)
                throw e
            } catch (e: Exception) {
                // 取消是靠关流 / 从进度回调里抛异常实现的，落到这里的异常先按取消认领
                if (handle.cancelled.get()) finish(transfer.id, TransferStatus.CANCELLED)
                else finish(transfer.id, TransferStatus.FAILED, e.message ?: e.javaClass.simpleName)
            } finally {
                handles.remove(transfer.id)
                runCatching { handle.stream.getAndSet(null)?.close() }
                // 协程可能已经被取消了，收尾得在 NonCancellable 里做，否则通道关不掉
                withContext(NonCancellable) { closeIfDrained(transfer.hostId) }
            }
        }
        // 排队期间被取消的，上面的 job 赋值可能晚于 cancel()，补一次
        if (handle.cancelled.get()) handle.job?.cancel()
    }

    /**
     * 进度回调。两件事：**报进度**和**认取消**。
     *
     * 节流到 [PROGRESS_INTERVAL_MS]：sshj 每写完一块（几十 KB）就回调一次，
     * 传一个 1 GB 的文件会有几万次，全转成 `StateFlow` 更新能把 Compose 的重组打爆。
     */
    private fun progress(id: String, handle: Handle): (Long) -> Unit {
        var lastAt = 0L
        return { transferred ->
            if (handle.cancelled.get()) throw TransferCancelledException()
            val now = System.currentTimeMillis()
            if (now - lastAt >= PROGRESS_INTERVAL_MS) {
                lastAt = now
                update(id) { it.copy(transferredBytes = transferred) }
            }
        }
    }

    private fun update(id: String, transform: (Transfer) -> Transfer) {
        _transfers.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    /** 终态只落一次：取消是从 UI 和协程两条路同时来的，后到的那条不能把结果改回去。 */
    private fun finish(id: String, status: TransferStatus, error: String? = null) {
        update(id) { if (it.status.finished) it else it.copy(status = status, error = error) }
    }

    /** 这台主机没有在跑的传输了就关掉传输通道；浏览通道归文件页管，不碰。 */
    private suspend fun closeIfDrained(hostId: String) {
        if (_transfers.value.none { it.hostId == hostId && it.active }) {
            runCatching { repository.closeTransfers(hostId) }
        }
    }

    /** 部分第三方 provider 就是不给 `DISPLAY_NAME`，拿不到时得有个能落地的名字。 */
    private fun displayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()?.takeIf { SftpPath.isValidName(it) }

    /** 长度拿不到就是 0，此时进度条只显示已传字节数，不假装知道百分比。 */
    private fun sizeOf(uri: Uri): Long = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    private fun fallbackName(): String = "upload-${System.currentTimeMillis()}"

    private companion object {
        const val PROGRESS_INTERVAL_MS = 200L
    }
}
