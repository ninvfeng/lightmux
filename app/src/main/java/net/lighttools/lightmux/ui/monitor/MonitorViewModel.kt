package net.lighttools.lightmux.ui.monitor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.lighttools.lightmux.data.Host
import net.lighttools.lightmux.data.HostStore
import net.lighttools.lightmux.monitor.FactsResult
import net.lighttools.lightmux.monitor.HostSnapshot
import net.lighttools.lightmux.monitor.MonitorRepository
import net.lighttools.lightmux.monitor.UsageSort

/**
 * 监控页状态。
 *
 * [snapshot] 与 [error] 是**并存**的：采集失败时保留上一次的数据继续显示，只额外挂一条错误——
 * 把已有内容清空是对用户最不友好的失败方式（和主页 tmux 树的处理一致）。
 */
data class MonitorUiState(
    val snapshot: HostSnapshot? = null,
    /** 这台机器没有 `/proc`。和「采集失败」是两回事，重试多少次都一样，所以不给重试按钮 */
    val unsupported: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    /**
     * 主机在跳进来的路上被删了（抽屉/终端页都能跳到这里）。
     *
     * 只置位不带文案：这是给人看的一句话，得走 `strings.xml`，而 ViewModel 拿不到 Context
     * （同 `HomeViewModel.staleNotice`）。和 [error] 分开还因为它连采集都没开始过，
     * 套上「采集失败：」的前缀是误导，重试也永远不会成功。
     */
    val hostMissing: Boolean = false,
)

class MonitorViewModel(
    private val repository: MonitorRepository,
    private val hostStore: HostStore,
    private val hostId: String,
) : ViewModel() {

    var host by mutableStateOf<Host?>(null)
        private set

    var state by mutableStateOf(MonitorUiState())
        private set

    /**
     * 公网 IP。
     *
     * 独立于 [state] 存放：每轮采集成功都会整个换掉 [MonitorUiState]，
     * 放进去就会被 5 秒冲一次，而它一次采集要好几秒，冲掉就再也补不回来。
     */
    var publicIp by mutableStateOf<String?>(null)
        private set

    /**
     * 展开状态放 ViewModel 而不是页面的 `remember`（CLAUDE.md 架构要点 ④）。
     * 从终端页返回时页面会重建，展开的核心列表不该跟着塌回去。
     */
    var coresExpanded by mutableStateOf(false)
        private set

    var virtualNetExpanded by mutableStateOf(false)
        private set

    /**
     * 容器段、进程段各自的排序键，同样放 ViewModel（理由同展开状态）。
     *
     * 两段分开：来看容器的多半在找哪个服务吃内存，来看进程的多半在找哪个进程占着 CPU，
     * 用一个开关联动会让其中一段莫名其妙地跟着变。
     */
    var containerSort by mutableStateOf(UsageSort.CPU)
        private set

    var processSort by mutableStateOf(UsageSort.CPU)
        private set

    /** 轮询协程。页面不可见时必须为 null——后台狂发 SSH 命令既费电又容易触发 fail2ban。 */
    private var poller: Job? = null

    /** 公网 IP 只采一次。出不去网的机器如果每轮都试，等于每 5 秒白等 6 秒。 */
    private var publicIpJob: Job? = null
    private var publicIpTried = false

    fun toggleCores() {
        coresExpanded = !coresExpanded
    }

    fun toggleVirtualNet() {
        virtualNetExpanded = !virtualNetExpanded
    }

    fun toggleContainerSort() {
        containerSort = containerSort.toggled()
    }

    fun toggleProcessSort() {
        processSort = processSort.toggled()
    }

    /**
     * 开始轮询。幂等：`ON_START` 与 `DisposableEffect` 首次进入会各叫一次。
     */
    fun start() {
        if (poller?.isActive == true) return
        poller = viewModelScope.launch {
            while (isActive) {
                collect()
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    /** 页面 `onStop` 或离开时调用。 */
    fun stop() {
        poller?.cancel()
        poller = null
    }

    /** 失败后的「重试」：立刻采一次，不等下一个 5 秒。手点的这一下也重试公网 IP。 */
    fun refreshNow() {
        publicIpTried = false
        viewModelScope.launch { collect() }
    }

    /**
     * 放掉为监控拨的那条连接。
     *
     * 这里不能用 [onCleared]：ViewModel 挂在 Activity 的 ViewModelStore 上，
     * 离开页面并不会清掉它（这也是重进页面能立刻看到上次数据的原因），
     * 所以释放时机只能由页面的 `DisposableEffect` 给。
     */
    fun release() {
        viewModelScope.launch { runCatching { repository.release(hostId) } }
    }

    private suspend fun collect() {
        if (state.loading) return
        val target = host ?: hostStore.get(hostId)?.also { host = it }
        if (target == null) {
            state = state.copy(loading = false, error = null, hostMissing = true)
            return
        }

        state = state.copy(loading = true)
        val outcome = runCatching { repository.probe(target) }
        state = outcome.fold(
            onSuccess = { result ->
                when (result) {
                    is FactsResult.Ok -> {
                        // 排在采集之后：公网 IP 那条 exec 要在同一把主机锁上排队，
                        // 抢在采集前面会让首屏白等好几秒
                        fetchPublicIp(target)
                        MonitorUiState(snapshot = result.snapshot)
                    }

                    FactsResult.Unsupported ->
                        MonitorUiState(unsupported = true)

                    // 解析失败不覆盖上一次的好数据：宁可显示一份带「采集失败」的旧读数，
                    // 也不能把页面清空——那等于告诉用户这台机器什么都没有。
                    is FactsResult.Malformed ->
                        state.copy(loading = false, error = result.reason)
                }
            },
            onFailure = { error ->
                state.copy(loading = false, error = error.message?.takeIf { it.isNotBlank() }
                    ?: error.javaClass.simpleName)
            },
        )
    }

    /** 采不到就一直是「不可用」，直到用户手点刷新。失败不进 [state] 的 error——那条横幅是给整次采集失败留的。 */
    private fun fetchPublicIp(target: Host) {
        if (publicIpTried || publicIpJob?.isActive == true) return
        publicIpTried = true
        publicIpJob = viewModelScope.launch {
            publicIp = runCatching { repository.probePublicIp(target) }.getOrNull()
        }
    }

    private companion object {
        /**
         * 5 秒一次。再快没有意义（`/proc/stat` 的采样本身就要 1 秒），
         * 再慢则「盯着看内存有没有降下去」这个场景不成立。
         */
        const val REFRESH_INTERVAL_MS = 5_000L
    }
}
