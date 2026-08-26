package net.lighttools.lightmux.ui.terminal

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.lighttools.lightmux.LightmuxApp
import net.lighttools.lightmux.R
import net.lighttools.lightmux.data.ForwardStore
import net.lighttools.lightmux.forward.ForwardFailure
import net.lighttools.lightmux.forward.ForwardManager
import net.lighttools.lightmux.forward.ForwardRules
import net.lighttools.lightmux.forward.ForwardSpec
import net.lighttools.lightmux.forward.ForwardStatus
import net.lighttools.lightmux.forward.PortHint
import net.lighttools.lightmux.forward.PortSniffer

/**
 * 终端输出里嗅到端口就提一句，一点即转发。
 *
 * 状态放 ViewModel（按主机分键）而不是页面的 `remember`：转发页、文件面板都在同一个返回栈上，
 * 去一趟再回来「忽略过哪些端口」不能忘（CLAUDE.md 架构要点 ④）。
 *
 * 忽略清单只活在内存里，不落盘：重开一次 app 等于重新审视一遍，比记一辈子好——
 * 用户当时忽略的多半是「这次不用」，不是「以后都别提」。
 */
class PortHintViewModel(
    private val store: ForwardStore,
    private val manager: ForwardManager,
    private val hostId: String,
) : ViewModel() {

    /** 刚建起来的那条转发，附带该打开的完整地址。 */
    data class Created(val spec: ForwardSpec, val url: String)

    /** 等着用户处理的提示，末尾那条正显示着。 */
    var pending by mutableStateOf<List<PortHint>>(emptyList())
        private set

    var created by mutableStateOf<Created?>(null)
        private set

    private val ignored = mutableSetOf<Int>()

    /** 这台主机已经配过的远端端口。配过就不提示了，重复一条只会撞本地端口。 */
    private var configured: Set<Int> = emptySet()

    /** 已被占用的本地端口，**不分主机**——本地端口冲突是设备级的。 */
    private var takenLocal: Set<Int> = emptySet()

    init {
        viewModelScope.launch {
            store.forwards.collect { list ->
                configured = list.filter { it.hostId == hostId }.mapTo(mutableSetOf()) { it.remotePort }
                takenLocal = list.mapTo(mutableSetOf()) { it.localPort }
                // 用户可能是在转发页手动加的，那条提示就该自己消失
                pending = pending.filterNot { it.port in configured }
            }
        }
    }

    /**
     * 喂一段终端文本进来。
     *
     * 正则甩到 [Dispatchers.Default]：调用方在主线程上，而一屏加历史能有两万来个字符，
     * 这活儿没必要跟渲染抢线程。
     */
    fun feed(text: String) {
        if (text.isEmpty()) return
        viewModelScope.launch {
            val fresh = withContext(Dispatchers.Default) { PortSniffer.scan(text) }
                .filterNot { it.port in ignored || it.port in configured }
            if (fresh.isEmpty()) return@launch
            // distinctBy 让已在队列里的那条留下：同一个端口反复被扫到时，提示条不该跟着抖
            pending = (pending + fresh).distinctBy { it.port }.takeLast(MAX_PENDING)
        }
    }

    /** 「×」：这个端口这次不提了。 */
    fun ignore(hint: PortHint) {
        ignored += hint.port
        pending = pending - hint
    }

    /**
     * 建一条转发并直接开。
     *
     * 和 [net.lighttools.lightmux.ui.forward.ForwardViewModel.addFromProbe] 同一个道理：
     * 用户点这一下的意思就是「我现在要用它」，再让他去转发页点第二下开关是没有道理的。
     */
    fun forward(hint: PortHint) {
        val spec = ForwardSpec(
            hostId = hostId,
            remotePort = hint.port,
            localPort = ForwardRules.suggestLocalPort(hint.port, takenLocal),
            remoteHost = hint.target,
        )
        pending = pending - hint
        created = Created(spec, spec.localUrl + hint.path)
        viewModelScope.launch {
            store.upsert(spec)
            manager.start(spec)
        }
    }

    fun dismissCreated() {
        created = null
    }

    private companion object {
        /** 排队上限。攒太多说明用户不想理它们，留最新的几条就够了。 */
        const val MAX_PENDING = 4
    }
}

/**
 * 提示条。贴在快捷栏正上方——手指本来就在这一带，转发点一下就成。
 *
 * 一次只显示一条：手机上这条栏每多占一行，终端就少一行。
 */
@Composable
fun PortHintBar(
    state: TerminalHostState,
    hostId: String,
    onOpenWeb: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as LightmuxApp
    val vm: PortHintViewModel = viewModel(
        // 按 hostId 分键：忽略清单与「已配过哪些端口」都是跟着主机走的
        key = "portHints:$hostId",
        factory = viewModelFactory {
            initializer { PortHintViewModel(app.forwardStore, app.forwardManager, hostId) }
        },
    )

    /*
     * 靠屏幕更新的节拍驱动，不定时轮询：终端静止时一次都不扫。
     *
     * 扫完先歇一秒再回来收——[TerminalHostState.screenTicks] 是合并的，
     * 于是刷屏的构建日志最多也就每秒扫这一遍。
     */
    LaunchedEffect(state, vm) {
        state.screenTicks.collect {
            vm.feed(state.screenText())
            delay(SCAN_INTERVAL_MS)
        }
    }

    val states by app.forwardManager.states.collectAsState()
    val created = vm.created
    val hint = vm.pending.lastOrNull()

    when {
        created != null -> {
            val status = states[created.spec.id]
            HintRow(
                text = createdText(created.spec, status),
                // 通了才给「打开」：没起来时打开只会看到一个连接失败页，
                // 那会让用户以为是服务端的问题（同转发页的规矩）
                actionLabel = stringResource(R.string.forward_hint_open)
                    .takeIf { status is ForwardStatus.Active },
                onAction = {
                    onOpenWeb(created.url)
                    vm.dismissCreated()
                },
                onDismiss = vm::dismissCreated,
                modifier = modifier,
            )
        }

        hint != null -> HintRow(
            text = stringResource(R.string.forward_hint_detected, hint.display),
            actionLabel = stringResource(R.string.forward_port_add),
            onAction = { vm.forward(hint) },
            onDismiss = { vm.ignore(hint) },
            modifier = modifier,
        )
    }
}

@Composable
private fun createdText(spec: ForwardSpec, status: ForwardStatus?): String = when (status) {
    ForwardStatus.Active -> stringResource(R.string.forward_hint_ready, spec.localPort)

    is ForwardStatus.Failed -> when (status.reason) {
        ForwardFailure.PortInUse -> stringResource(R.string.forward_status_port_in_use, spec.localPort)
        else -> status.detail ?: stringResource(R.string.forward_status_rejected)
    }

    else -> stringResource(R.string.forward_status_starting)
}

@Composable
private fun HintRow(
    text: String,
    actionLabel: String?,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.SettingsEthernet, null, modifier = Modifier.size(18.dp))
            Text(
                text = text,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            actionLabel?.let { TextButton(onClick = onAction) { Text(it) } }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, stringResource(R.string.forward_hint_ignore))
            }
        }
    }
}

/** 两次扫描之间至少隔这么久。见 [PortHintBar] 里的节流。 */
private const val SCAN_INTERVAL_MS = 1_000L
