package net.lighttools.lightmux.sftp

import android.net.Uri
import net.lighttools.lightmux.data.Host
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference

/** 一次上传在「问用户」之前就备齐的全部材料。scan* 的产出，用户拍板后原样交回 submit。 */
sealed interface UploadPlan {

    /** 与远端重名的那些，按上传顺序、给人看的名字（目录树里是 `css/app.css` 这种相对路径）。空 = 不用问 */
    val conflicts: List<String>

    class Files internal constructor(
        internal val host: Host,
        internal val remoteDir: String,
        internal val picked: List<Picked>,
        override val conflicts: List<String>,
    ) : UploadPlan

    /**
     * [tree] 在对话框开着期间会一直被持有（最多约 10000 个 `SafSource`，约 2-4 MB）。
     * 峰值和现有实现一样——传输全程本来就要持有这棵树，只是生命周期提前了，可接受。
     */
    class Folder internal constructor(
        internal val host: Host,
        internal val name: String,
        internal val remoteRoot: String,
        internal val tree: LocalTree,
        internal val streams: AtomicReference<Closeable?>,
        override val conflicts: List<String>,
    ) : UploadPlan
}

/** 选中的一个文件。[name] 为 null = provider 不给 DISPLAY_NAME，这种不参与比对、直接传。 */
internal class Picked(val uri: Uri, val name: String?)

/** 去掉 [skip] 里那些文件。**目录一个不删**：用户选的是「跳过这几个文件」，不是「别建这个目录」。 */
internal fun LocalTree.without(skip: Set<String>): LocalTree {
    if (skip.isEmpty()) return this
    val kept = files.filterNot { it.path in skip }
    // totalBytes 必须跟着重算，不然进度条永远到不了 100%
    return LocalTree(directories, kept, kept.sumOf { it.source.length })
}

/**
 * 上传前的同名预检。
 *
 * **一个目录只列一次，不逐个文件 stat**：一棵树几百个文件挤在几十个目录里，
 * 逐个 stat 就是几百次往返，而列目录一次就把整层的名字都拿回来了。
 */
internal object UploadConflicts {

    /**
     * 找出 [files] 里在远端已经存在的那些。路径一律相对上传根，根自身写作空串。
     *
     * @param dirs 要探测的目录，相对上传根，**父必须排在子前**（[SafTree.walk] 的 BFS 天然如此），首项是根（空串）
     * @param list 列一个目录，不存在时返回 null
     */
    suspend fun find(
        dirs: List<String>,
        files: List<String>,
        list: suspend (String) -> DirNames?,
    ): Set<String> {
        val listed = HashMap<String, DirNames>()
        for (dir in dirs) {
            // 父目录里没有这个名字 = 这一枝远端根本不存在，里面的东西一个都不可能撞名，整枝跳过。
            // 不剪枝的话，第一次上传一棵 200 子目录的树就是 200 次注定 NO_SUCH_FILE 的往返
            val parent = dir.substringBeforeLast('/', "")
            if (dir.isNotEmpty() &&
                listed[parent]?.dirs?.contains(dir.substringAfterLast('/')) != true
            ) {
                continue
            }
            list(dir)?.let { listed[dir] = it }
        }
        return files.filterTo(mutableSetOf()) {
            listed[it.substringBeforeLast('/', "")]?.names?.contains(it.substringAfterLast('/')) == true
        }
    }
}
