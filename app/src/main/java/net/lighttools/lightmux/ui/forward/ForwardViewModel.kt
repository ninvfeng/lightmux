package net.lighttools.lightmux.ui.forward

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.lighttools.lightmux.data.ForwardStore
import net.lighttools.lightmux.data.Host
import net.lighttools.lightmux.data.HostStore
import net.lighttools.lightmux.forward.ForwardManager
import net.lighttools.lightmux.forward.ForwardRules
import net.lighttools.lightmux.forward.ForwardSpec
import net.lighttools.lightmux.forward.ForwardStatus
import net.lighttools.lightmux.forward.ListeningPort
import net.lighttools.lightmux.forward.PortError
import java.util.UUID

/** 列表里的一行：配置 + 它此刻的状态（null = 没开）。 */
data class ForwardRow(val spec: ForwardSpec, val status: ForwardStatus?)

/**
 * 已经配过转发的远端端口。探测出来的端口列表靠它决定「添加」还是「已添加」。
 *
 * 建成 Set 一次查一次，而不是每个端口行 `any {}` 扫一遍全部转发——那是 O(端口数 × 转发数)，
 * 端口一多（一台跑十几个服务的机器随手就是几十行）就成了平方级。
 */
internal fun takenRemotePorts(rows: List<ForwardRow>): Set<Int> =
    rows.mapTo(mutableSetOf()) { it.spec.remotePort }

/**
 * 新增 / 编辑转发的表单。[id] 非空表示在改一条已有的。
 *
 * 端口用字符串存而不是 Int：输入框清空的那一刻它既不是 0 也不是上一个值，
 * 硬塞进 Int 会让「删掉重打」变成「删不掉」。
 */
data class ForwardDraft(
    val id: String? = null,
    val remotePort: String = "",
    val localPort: String = "",
    val remoteHost: String = ForwardSpec.DEFAULT_REMOTE_HOST,
    val label: String = "",
    val remoteError: PortError? = null,
    val localError: PortError? = null,
    /**
     * 用户手动改过本地端口没有。
     *
     * 没改过时本地端口跟着远端端口走（远端 3000 → 本地 3000），一旦手改过就不再自动覆盖——
     * 否则用户特意把本地口改成 8081、回头又调了一下远端口，改的那下就白费了。
     */
    val localTouched: Boolean = false,
)

/**
 * 端口转发页的状态。
 *
 * 页面/面板一露面就探一次远端端口（[probe]，由 UI 侧发起）：点开「转发」这一下本身就是意图，
 * 进来还要再点一次放大镜才看得到列表，等于把用户已经表达过的意思再问一遍。
 * 这与 PRD §4.3 的「展开即探测」是同一条纪律——§4.3 要避的是**替所有主机批量拨号**，
 * 不是这种针对单台、由用户亲手打开的一次 `exec`。
 * 探不到也不拦人：失败只挂一条可重试的提示，手填端口那条路一直开着。
 */
class ForwardViewModel(
    private val store: ForwardStore,
    private val manager: ForwardManager,
    private val hostStore: HostStore,
    private val hostId: String,
) : ViewModel() {

    var host by mutableStateOf<Host?>(null)
        private set

    val rows: StateFlow<List<ForwardRow>> =
        combine(store.forwards, manager.states) { specs, states ->
            specs.filter { it.hostId == hostId }.map { ForwardRow(it, states[it.id]) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** null = 还没探测过，空列表 = 探过了但远端没有监听端口。这两件事在 UI 上长得不一样。 */
    var probed by mutableStateOf<List<ListeningPort>?>(null)
        private set

    var probing by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** null = 表单没打开。 */
    var draft by mutableStateOf<ForwardDraft?>(null)
        private set

    /**
     * 搜索框里的过滤词；null = 搜索框收着，空串 = 展开了但还没打字。
     *
     * 两者必须分开：收着的时候标题得露出来，展开还没打字的时候列表得照旧全给
     * （匹配规则见 [net.lighttools.lightmux.forward.PortFilter]）。
     */
    var query by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch { host = hostStore.get(hostId) }
    }

    fun dismissError() {
        error = null
    }

    // ---- 搜索框 -------------------------------------------------------------

    fun startFilter() {
        if (query == null) query = ""
    }

    fun editQuery(value: String) {
        query = value
    }

    /** 关搜索框顺带清掉过滤词：留着的话列表看着还是缺的，用户会以为端口丢了。 */
    fun closeFilter() {
        query = null
    }

    /**
     * 返回键。搜索框开着就先收起来，收完了才轮到中央栈退页面——同浏览器地址栏那套
     * （[net.lighttools.lightmux.ui.web.WebViewModel.goBack]）。
     *
     * 只有整页壳走这里；半屏面板的返回被面板自己的 dispatcher 接走（关面板），
     * 那条路上搜索态由「每次展开先清一次」兜住。
     */
    fun onBack(): Boolean {
        if (query == null) return false
        query = null
        return true
    }

    /**
     * 远端在监听哪些端口。进页面自动跑一次，之后点「重新扫描」再跑——
     * 服务多半是刚在终端里起来的，列表要能当场刷新。
     */
    fun probe() {
        if (probing) return
        viewModelScope.launch {
            val target = host ?: hostStore.get(hostId)?.also { host = it } ?: return@launch
            probing = true
            runCatching { manager.probe(target) }
                .onSuccess { probed = it }
                .onFailure { error = it.message?.takeIf(String::isNotBlank) ?: it.javaClass.simpleName }
            probing = false
        }
    }

    /**
     * 从探测列表里一键转发。加完直接开——用户点这一下的意思就是「我现在要用它」，
     * 再让他去列表里找一遍、点第二下开关，是没有道理的。
     */
    fun addFromProbe(port: ListeningPort) {
        viewModelScope.launch {
            val spec = ForwardSpec(
                hostId = hostId,
                remotePort = port.port,
                localPort = ForwardRules.suggestLocalPort(port.port, takenLocalPorts()),
                label = port.process,
            )
            store.upsert(spec)
            manager.start(spec)
        }
    }

    // ---- 表单 ---------------------------------------------------------------

    fun newForward() {
        draft = ForwardDraft()
    }

    fun edit(spec: ForwardSpec) {
        draft = ForwardDraft(
            id = spec.id,
            remotePort = spec.remotePort.toString(),
            localPort = spec.localPort.toString(),
            remoteHost = spec.remoteHost,
            label = spec.label.orEmpty(),
            localTouched = true,
        )
    }

    fun dismissDraft() {
        draft = null
    }

    fun editRemotePort(value: String) {
        val current = draft ?: return
        val port = value.toIntOrNull()
        draft = current.copy(
            remotePort = value,
            // 用户没自己动过本地端口时，让它跟着远端端口走
            localPort = if (current.localTouched || port == null) current.localPort
            else ForwardRules.suggestLocalPort(port, takenLocalPorts(current.id)).toString(),
            remoteError = null,
            localError = null,
        )
    }

    fun editLocalPort(value: String) {
        draft = draft?.copy(localPort = value, localTouched = true, localError = null)
    }

    fun editRemoteHost(value: String) {
        draft = draft?.copy(remoteHost = value)
    }

    fun editLabel(value: String) {
        draft = draft?.copy(label = value)
    }

    /** 存下来。校验不过就把错误挂回表单，返回 false。 */
    fun saveDraft(): Boolean {
        val current = draft ?: return false
        val remote = current.remotePort.trim().toIntOrNull()
        val local = current.localPort.trim().toIntOrNull()
        // 空串、非数字与越界归到同一条提示：用户看到的都是「填 1-65535」
        val remoteError = if (remote == null) PortError.OutOfRange else ForwardRules.validateRemotePort(remote)
        val localError = if (local == null) PortError.OutOfRange else ForwardRules.validateLocalPort(local)
        if (remote == null || local == null || remoteError != null || localError != null) {
            draft = current.copy(remoteError = remoteError, localError = localError)
            return false
        }
        val spec = ForwardSpec(
            id = current.id ?: UUID.randomUUID().toString(),
            hostId = hostId,
            remotePort = remote,
            localPort = local,
            remoteHost = current.remoteHost.trim().ifBlank { ForwardSpec.DEFAULT_REMOTE_HOST },
            label = current.label.trim().ifBlank { null },
        )
        val isNew = current.id == null
        draft = null
        viewModelScope.launch {
            // 改的是正在跑的那条就重开一次：端口都换了，老的 ServerSocket 还听在原处没有意义。
            val wasRunning = manager.isRunning(spec.id)
            if (wasRunning) manager.stop(spec.id)
            store.upsert(spec)
            // 新加的直接开。和 addFromProbe 同一个道理：填完这张表的意思就是「我现在要用它」。
            if (wasRunning || isNew) manager.start(spec)
        }
        return true
    }

    // ---- 开关 ---------------------------------------------------------------

    fun toggle(row: ForwardRow) {
        if (row.status?.isLive == true) manager.stop(row.spec.id) else manager.start(row.spec)
    }

    fun delete(spec: ForwardSpec) {
        manager.stop(spec.id)
        viewModelScope.launch { store.delete(spec.id) }
    }

    /** 已经被别的转发占掉的本地端口。[exclude] 是正在编辑的那条自己，不算冲突。 */
    private fun takenLocalPorts(exclude: String? = null): Set<Int> =
        rows.value.filter { it.spec.id != exclude }.mapTo(mutableSetOf()) { it.spec.localPort }
}
