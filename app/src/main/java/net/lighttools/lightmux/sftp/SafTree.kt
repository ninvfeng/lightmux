package net.lighttools.lightmux.sftp

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream

/**
 * 把 SAF 选中的一棵目录树遍历成 [LocalTree]。
 *
 * `androidx.documentfile` 只在 runtimeClasspath（经 transition 传入），compileClasspath
 * 没有——不为一次遍历专门加这个依赖，手写 `DocumentsContract` 调用（`DocumentFile.listFiles()`
 * 是一个条目一次跨进程 query，比这里每层一次 query 差一个数量级）。
 */
object SafTree {

    /**
     * `OPEN_DOCUMENT_TREE` 允许用户一键选中「内部存储」整个根，不拦就是遍历几分钟、
     * 再开始一场几小时的上传。
     */
    private const val MAX_ENTRIES = 10_000

    private val COLUMNS = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
    )

    /** 根目录的名字，服务端要建的同名目录用它。畸形 provider 给不出合法名字时返回 null。 */
    fun rootName(resolver: ContentResolver, treeUri: Uri): String? {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val name = resolver.query(rootUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        return name?.takeIf(SftpPath::isValidName)
    }

    /**
     * 遍历整棵树。**BFS + 显式队列，不用函数递归**——`node_modules` 那种嵌套几十上百层，
     * 一路递归下去就是 `StackOverflowError`，它是 `Error` 不是 `Exception`，协程里没有
     * catch 接得住；BFS 还顺带保证父目录排在子目录前，正是远端 `mkdirs` 要的顺序。
     *
     * 每展开一层前 `ensureActive()`：云 provider 上一层就是一次网络请求，
     * 几百层能到分钟级，必须能被取消打断。
     */
    suspend fun walk(
        resolver: ContentResolver,
        treeUri: Uri,
        onStreamOpened: (InputStream) -> Unit,
    ): LocalTree = withContext(Dispatchers.IO) {
        val directories = mutableListOf<String>()
        val files = mutableListOf<LocalTreeFile>()
        var totalBytes = 0L
        var entryCount = 0

        // (文档 id, 相对树根的路径；根自身是空串)
        val pending = ArrayDeque<Pair<String, String>>()
        pending.addLast(DocumentsContract.getTreeDocumentId(treeUri) to "")

        while (pending.isNotEmpty()) {
            ensureActive()
            val (docId, relative) = pending.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val cursor = resolver.query(childrenUri, COLUMNS, null, null, null)
                ?: throw IOException("cannot list ${relative.ifEmpty { "/" }}")
            cursor.use {
                val idIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                // 可选列：projection 里写了不代表 cursor 里有，按列名找不到就当 0 处理
                val sizeIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                while (it.moveToNext()) {
                    entryCount++
                    if (entryCount > MAX_ENTRIES) throw IOException("too many entries (> $MAX_ENTRIES)")

                    val childId = it.getString(idIndex)
                    val name = it.getString(nameIndex)
                    // provider 给出 ".." 时 SftpPath.join 会把文件写到目标目录外面——
                    // 这是整条链路上唯一挡得住路径逃逸的地方，遇非法名整棵树失败，不静默跳过
                    if (!SftpPath.isValidName(name)) throw IOException("unsafe entry name: $name")

                    val childRelative = if (relative.isEmpty()) name else "$relative/$name"
                    if (it.getString(mimeIndex) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        directories += childRelative
                        pending.addLast(childId to childRelative)
                    } else {
                        val size = if (sizeIndex >= 0 && !it.isNull(sizeIndex)) it.getLong(sizeIndex) else 0L
                        totalBytes += size
                        // 子文档 URI 必须用 buildDocumentUriUsingTree：buildDocumentUri 建出来的
                        // 是非 tree URI，我们对它没有授权，openInputStream 直接 SecurityException
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                        files += LocalTreeFile(childRelative, SafSource(resolver, childUri, name, size, onStreamOpened))
                    }
                }
            }
        }

        LocalTree(directories, files, totalBytes)
    }
}

/**
 * [LocalSource] 的 SAF 实现。**流在 [open] 里才打开**——一棵树几千个文件，
 * 不能一上来攥着几千个 fd。打开时回调 [onStreamOpened]，让 `TransferQueue`
 * 把它记进 `Handle.stream`，取消时才关得掉正在读的那一个。
 */
private class SafSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val name: String,
    override val length: Long,
    private val onStreamOpened: (InputStream) -> Unit,
) : LocalSource {

    override fun open(): InputStream =
        (resolver.openInputStream(uri) ?: throw IOException("cannot open $uri")).also(onStreamOpened)
}
