package net.lighttools.lightmux.ui.files

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import net.lighttools.lightmux.data.Host
import net.lighttools.lightmux.data.HostStore
import net.lighttools.lightmux.sftp.SftpPath
import net.lighttools.lightmux.sftp.SftpRepository
import net.lighttools.lightmux.sftp.TextLoad

/**
 * 应用内文本编辑器的状态。
 *
 * 「太大」和「是二进制」不是错误，是**两种正常结果**：前者该去终端里用 vim，
 * 后者根本不该用文本编辑器打开。混进 [error] 里用户只会看到一句莫名其妙的失败。
 */
class FileEditViewModel(
    private val repository: SftpRepository,
    private val hostStore: HostStore,
    private val hostId: String,
    val path: String,
) : ViewModel() {

    var host by mutableStateOf<Host?>(null)
        private set

    var text by mutableStateOf("")
        private set

    var loading by mutableStateOf(true)
        private set

    var saving by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /**
     * 主机在跳进来的路上被删了。
     *
     * 只置位不带文案：这是给人看的一句话，得走 `strings.xml`，而 ViewModel 拿不到 Context
     * （同 `HomeViewModel.staleNotice`）。
     */
    var hostMissing by mutableStateOf(false)
        private set

    /** 非空表示文件超限，值是它的大小。 */
    var tooLarge by mutableStateOf<Long?>(null)
        private set

    var binary by mutableStateOf(false)
        private set

    /** 用户按了返回但有未保存改动，页面据此弹确认框。 */
    var exitRequested by mutableStateOf(false)
        private set

    val name: String get() = SftpPath.name(path)

    /** 上一次落盘的内容。用它比对而不是维护一个 dirty 标记：撤销回原样后就不该再拦返回。 */
    private var saved: String? = null

    val editable: Boolean get() = saved != null

    val dirty: Boolean get() = editable && text != saved

    fun start() {
        if (!loading) return
        viewModelScope.launch {
            val target = hostStore.get(hostId)
            if (target == null) {
                loading = false
                hostMissing = true
                return@launch
            }
            host = target
            val outcome = runCatching { repository.readText(target, path) }
            loading = false
            outcome.fold(
                onSuccess = { load ->
                    when (load) {
                        is TextLoad.Ok -> {
                            text = load.text
                            saved = load.text
                        }

                        is TextLoad.TooLarge -> tooLarge = load.sizeBytes
                        TextLoad.Binary -> binary = true
                    }
                },
                onFailure = { error = it.describe() },
            )
        }
    }

    fun onTextChanged(value: String) {
        text = value
    }

    fun clearError() {
        error = null
    }

    fun save() {
        val target = host ?: return
        if (saving) return
        saving = true
        val snapshot = text
        viewModelScope.launch {
            val outcome = runCatching { repository.writeText(target, path, snapshot) }
            saving = false
            outcome.fold(
                // 存的是发起保存那一刻的快照，期间用户可能又敲了几个字，不能拿 text 当已保存内容
                onSuccess = { saved = snapshot },
                onFailure = { error = it.describe() },
            )
        }
    }

    /**
     * 返回键。
     *
     * @return true 表示这一次返回被拦下了（弹了确认框），中央栈不要 pop
     */
    fun requestExit(): Boolean {
        if (!dirty) return false
        exitRequested = true
        return true
    }

    fun dismissExit() {
        exitRequested = false
    }
}
