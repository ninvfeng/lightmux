package net.lighttools.lightmux.ui.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import net.lighttools.lightmux.web.WebUrl

/**
 * 网页那侧的动作通道。
 *
 * `WebView` 实例只能活在 Compose 树里——它持的是 Activity context，塞进 ViewModel 就是
 * 连着整个 Activity 一起泄漏。所以 VM 不存 View，只存这一组回调，由 [WebScreen] 在挂载时
 * 注册、销毁时置空。
 */
interface WebControls {

    fun load(url: String)

    fun reload()

    /** 返回 false 表示网页自己没有可回退的历史了。 */
    fun goBack(): Boolean

    fun goForward()
}

/**
 * 内置浏览器的状态。
 *
 * 状态放 VM 而不是页面的 `remember`：地址栏的编辑草稿、当前地址在配置变更（切暗色、改字号）
 * 后必须还在，不然用户打了一半的地址会凭空消失。
 */
class WebViewModel(initialUrl: String) : ViewModel() {

    /** 当前地址。跟着网页自己的跳转走——点了站内链接、被 302 带走了，地址栏都得跟上。 */
    var url by mutableStateOf(initialUrl)
        private set

    /**
     * 0..100。满 100 就不画进度条了。
     *
     * 用 [mutableIntStateOf]：WebView 一次加载能回灌几十次进度，泛型版每次赋值都装一个 Integer。
     */
    var progress by mutableIntStateOf(100)
        private set

    var canGoForward by mutableStateOf(false)
        private set

    /** 地址栏的编辑草稿；null = 不在编辑态，显示的是当前地址。 */
    var draft by mutableStateOf<String?>(null)
        private set

    var controls: WebControls? = null

    // ---- 网页那侧回灌 ----------------------------------------------------------

    fun onNavigated(url: String, canGoForward: Boolean) {
        this.url = url
        this.canGoForward = canGoForward
    }

    fun onProgress(value: Int) {
        progress = value.coerceIn(0, 100)
    }

    // ---- 用户操作 --------------------------------------------------------------

    /** 进编辑态。草稿给完整 URL 而不是显示用的短形式——要改的多半是尾巴上的路径。 */
    fun startEditing() {
        draft = url
    }

    fun editDraft(value: String) {
        draft = value
    }

    fun cancelEditing() {
        draft = null
    }

    /** 回车。地址不合法（比如打了个 65536）就把草稿留着，让用户接着改。 */
    fun submitDraft() {
        val target = WebUrl.normalize(draft.orEmpty()) ?: return
        draft = null
        controls?.load(target)
    }

    fun reload() {
        controls?.reload()
    }

    fun goForward() {
        controls?.goForward()
    }

    /**
     * 返回键。有网页历史就退一页，没有则交还给中央栈去关页面（浏览器的通用预期，同文件页的
     * 「先回上一级」）。编辑态优先退出——地址栏开着时按返回，用户要的是取消这次输入。
     */
    fun goBack(): Boolean {
        if (draft != null) {
            draft = null
            return true
        }
        return controls?.goBack() == true
    }

    override fun onCleared() {
        controls = null
    }
}
