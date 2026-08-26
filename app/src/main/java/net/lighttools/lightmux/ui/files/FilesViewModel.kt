package net.lighttools.lightmux.ui.files

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import net.lighttools.lightmux.data.Host
import net.lighttools.lightmux.data.HostStore
import net.lighttools.lightmux.sftp.RemoteEntry
import net.lighttools.lightmux.sftp.RemoteFileType
import net.lighttools.lightmux.sftp.SftpPath
import net.lighttools.lightmux.sftp.SftpRepository

/**
 * 文件页状态。
 *
 * [entries] 与 [error] **并存**：`ls` 失败时保留上一屏内容，只在顶部挂一条错误。
 * 把列表清空是最糟的失败方式——用户会以为目录空了，而实际只是网络抖了一下。
 */
data class FilesUiState(
    val path: String = SftpPath.ROOT,
    val entries: List<RemoteEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** 有没有成功列过一次。用来区分「还在转圈」和「这个目录真的是空的」 */
    val loaded: Boolean = false,
    /**
     * 主机在跳进来的路上被删了（抽屉、终端页的文件面板都能跳到这里）。
     *
     * 只置位不带文案：这是给人看的一句话，得走 `strings.xml`，而 ViewModel 拿不到 Context
     * （同 `HomeViewModel.staleNotice`）。和 [error] 分开还因为这时连 `ls` 都没发出去过，
     * 套上「列目录失败：」的前缀等于把用户往错的方向指。
     */
    val hostMissing: Boolean = false,
)

class FilesViewModel(
    private val repository: SftpRepository,
    private val hostStore: HostStore,
    private val hostId: String,
    private val initialPath: String,
) : ViewModel() {

    var host by mutableStateOf<Host?>(null)
        private set

    var state by mutableStateOf(FilesUiState(path = initialPath))
        private set

    /** 隐藏文件默认不显示。服务器上点开头的东西太多（`.cache`、`.local`…），默认显示等于噪声淹没内容。 */
    var showHidden by mutableStateOf(false)
        private set

    /** 操作反馈（删除失败、改名失败…）。一次性，展示完就清。 */
    var actionError by mutableStateOf<String?>(null)
        private set

    /**
     * 正等着「存到哪」的那一条：点下载后要先去系统的文件选择器挑落点，选完才知道往哪写。
     *
     * **必须活在 ViewModel 里**（CLAUDE.md 架构要点 ④）：选择器是另一个 Activity，它开着的时候
     * 本 Activity 随时可能被重建（转屏、切换深色模式、改语言那次更是必然重建）。
     * 页面 `remember` 到那时已经清空，而选择器的回调靠 `rememberSaveable` 的 key 活了下来，
     * 结果就是「选完落点，文件却没开始下载」，还不报错。
     */
    private var pendingDownload: RemoteEntry? = null

    fun requestDownload(entry: RemoteEntry) {
        pendingDownload = entry
    }

    /** 取走并清掉：选择器回调只会来一次，留着会让下一次取消的挑选误传上一条。 */
    fun consumePendingDownload(): RemoteEntry? = pendingDownload.also { pendingDownload = null }

    /**
     * 祖先链，栈顶是当前目录的上一级。
     *
     * **只维护目录层级，不进导航栈**：整个文件浏览是一个页面。
     * 如果每进一级目录就 push 一个 `Screen.Files`，页面会被销毁重建，
     * ViewModel 一个目录攒一个，SFTP 连接也跟着来回拆建——翻三层目录就重连三次。
     */
    private var ancestors = listOf<String>()

    val visibleEntries: List<RemoteEntry>
        get() = if (showHidden) state.entries else state.entries.filterNot { it.isHidden }

    val crumbs: List<SftpPath.Crumb> get() = SftpPath.crumbs(state.path)

    /** 进页面时调一次。幂等，重进页面不会重复拉。 */
    fun start() {
        if (state.loaded || state.loading) return
        viewModelScope.launch {
            val target = hostStore.get(hostId)
            if (target == null) {
                state = state.copy(error = null, hostMissing = true)
                return@launch
            }
            host = target
            // 起点空着表示「按家目录开」，由服务端的 canonicalize 决定：
            // 硬拼 /home/<user> 在 root 和容器里都是错的。带了路径则是进程恢复回来的，照旧打开。
            val start = if (initialPath.isBlank()) {
                runCatching { repository.home(target) }.getOrDefault(SftpPath.ROOT)
            } else {
                SftpPath.normalize(initialPath)
            }
            state = state.copy(path = start)
            load()
        }
    }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    fun toggleHidden() {
        showHidden = !showHidden
    }

    fun clearActionError() {
        actionError = null
    }

    /** 点一条：目录进去，链接先问服务端指向哪，普通文件交给调用方（去编辑器）。 */
    fun open(entry: RemoteEntry, onFile: (RemoteEntry) -> Unit) {
        when (entry.type) {
            RemoteFileType.DIRECTORY -> enter(entry.path)

            RemoteFileType.SYMLINK -> viewModelScope.launch {
                // ls 给的是 lstat 的结果，链接指向目录还是文件只有 stat 才知道
                val followed = runCatching { repository.statFollowing(host ?: return@launch, entry.path) }
                    .getOrNull()
                if (followed?.isDirectory == true) enter(entry.path) else onFile(entry)
            }

            else -> onFile(entry)
        }
    }

    /** 路径栏跳转（上一级图标、点路径弹出的祖先表）。跳到祖先时把中间那几级一起弹掉，返回键才不会又走回刚跳过的目录。 */
    fun goTo(path: String) {
        if (path == state.path) return
        val index = ancestors.indexOf(path)
        ancestors = if (index >= 0) ancestors.take(index) else ancestors + state.path
        navigate(path)
    }

    /**
     * 返回上一级。
     *
     * @return false 表示已经在这次浏览的起点，返回键该交回中央栈去关页面
     */
    fun goUp(): Boolean {
        val parent = ancestors.lastOrNull() ?: return false
        ancestors = ancestors.dropLast(1)
        navigate(parent)
        return true
    }

    fun mkdir(name: String) = mutate { host -> repository.mkdir(host, SftpPath.join(state.path, name)) }

    fun rename(entry: RemoteEntry, name: String) = mutate { host ->
        repository.rename(host, entry.path, SftpPath.join(SftpPath.parent(entry.path), name))
    }

    fun delete(entry: RemoteEntry) = mutate { host -> repository.delete(host, entry) }

    private fun enter(path: String) {
        ancestors = ancestors + state.path
        navigate(path)
    }

    private fun navigate(path: String) {
        // 换目录时先清空列表：留着上一个目录的内容配新路径，用户会以为文件跑到别处去了
        state = FilesUiState(path = path)
        refresh()
    }

    /** 改动完必须重列，否则用户看到的还是改之前的目录。 */
    private fun mutate(block: suspend (Host) -> Unit) {
        val target = host ?: return
        viewModelScope.launch {
            val outcome = runCatching { block(target) }
            outcome.exceptionOrNull()?.let { actionError = it.describe() }
            load()
        }
    }

    private suspend fun load() {
        val target = host ?: return
        val at = state.path
        state = state.copy(loading = true)
        val outcome = runCatching { repository.list(target, at) }
        // 期间用户可能已经切走了，晚到的结果不能盖掉新目录
        if (state.path != at) return
        state = outcome.fold(
            onSuccess = { FilesUiState(path = at, entries = it, loaded = true) },
            onFailure = { state.copy(loading = false, error = it.describe()) },
        )
    }
}

/** 异常没有 message 时（很多 IO 异常就是这样）退回类名，总比弹一个空气泡强。 */
internal fun Throwable.describe(): String =
    message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
