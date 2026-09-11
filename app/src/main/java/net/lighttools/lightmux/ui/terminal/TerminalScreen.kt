package net.lighttools.lightmux.ui.terminal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.lighttools.lightmux.LightmuxApp
import net.lighttools.lightmux.R
import net.lighttools.lightmux.session.SessionState
import net.lighttools.lightmux.session.TermSessionHandle
import net.lighttools.lightmux.ssh.KnownHosts
import net.lighttools.lightmux.ui.common.ErrorBanner
import androidx.compose.runtime.collectAsState

/**
 * 终端页。
 *
 * **没有顶栏**：一屏就这么大，标题栏占掉的那 56dp 换成两行终端更值；返回挪到了快捷栏第一格，
 * 会话标题在快速切换抽屉里看得到。
 *
 * 调用方必须用 `key(sessionId)` 包起来（见 [net.lighttools.lightmux.ui.LightmuxRoot]），
 * 否则切会话时 AndroidView 会残留上一个 TerminalView。
 */
@Composable
fun TerminalScreen(
    handle: TermSessionHandle,
    onBack: () -> Unit,
    onEditHost: (hostId: String) -> Unit,
    onOpenFiles: () -> Unit,
    onOpenForward: () -> Unit,
    onOpenWeb: (String) -> Unit,
    modifier: Modifier = Modifier,
    drawerOpening: Boolean = false,
    sheetOpen: Boolean = false,
) {
    val context = LocalContext.current
    val app = context.applicationContext as LightmuxApp
    val state = rememberTerminalHostState(handle)
    val sessionState by handle.state.collectAsState()
    // 初值取同步快照：拿默认值垫一帧会让快捷栏先闪一下默认键位再跳到用户排好的那套
    val settings by app.settingsStore.settings
        .collectAsState(initial = remember { app.settingsStore.blockingSnapshot() })

    // failure 是普通字段，但它一定在状态翻成 Disconnected 之前写好（SshTransport 先赋值再 onClosed），
    // 所以跟着 sessionState 一起读是安全的。
    val failure = if (sessionState == SessionState.Disconnected) ConnectionFailure.of(handle.failure) else null

    /*
     * 页内唯一的 BackHandler，且只在文本选择模式下启用。
     *
     * 例外理由：手势返回不走 View 的 onKeyPreIme，TerminalView 自带的「返回退出选择模式」
     * 在手势导航机型上收不到事件，用户会直接被弹回主页、选区丢失。
     * 非选择状态下它是 disabled 的，返回键照样交给 LightmuxRoot 的中央栈。
     */
    BackHandler(enabled = state.selecting) { state.stopSelection() }

    // 进终端就是为了敲字，键盘直接给出来，省一次点击。
    LaunchedEffect(handle.id) { state.showKeyboard() }

    /*
     * 叠在终端上的面板（文件、转发）都是独立窗口，软键盘会盖住它们下半截——
     * 和快捷栏那两张表一个道理（见 [ExtraKeysBar]）。
     * 收起来之后把键盘要回来：面板多半是刚往命令行里填了个路径，下一步就是接着敲。
     */
    LaunchedEffect(sheetOpen) {
        if (sheetOpen) state.hideKeyboard() else state.showKeyboard()
    }

    LaunchedEffect(drawerOpening) {
        if (!drawerOpening) return@LaunchedEffect
        // 上面那个 BackHandler 是内层的，优先级高于根：抽屉开着按返回会先退出选择模式，
        // 用户得按两次才关得掉抽屉。
        state.stopSelection()
        // 键盘不收会盖住抽屉下半截——抽屉挂在根上，吃不到这里的 imePadding。
        state.hideKeyboard()
    }

    Scaffold(
        modifier = modifier.fillMaxSize().imePadding(),
        bottomBar = {
            Column {
                // 嗅到的端口贴在快捷栏上方，而不是弹个对话框：终端里刚起来的服务
                // 多半还要接着看日志，把屏幕挡掉是本末倒置。
                PortHintBar(state = state, hostId = handle.host.id, onOpenWeb = onOpenWeb)
                ExtraKeysBar(
                    state = state,
                    slots = settings.quickSlots,
                    keyWidthDp = settings.quickKeyWidthDp,
                    commands = settings.quickCommands,
                    onBack = onBack,
                    onFiles = onOpenFiles,
                    onForward = onOpenForward,
                    // 用 Application 的作用域：改完顺手退出终端页时，写盘不该跟着页面一起被取消
                    onSlotsChange = { app.scope.launch { app.settingsStore.setQuickSlots(it) } },
                    onCustomKeysChange = { app.scope.launch { app.settingsStore.setQuickCustomKeys(it) } },
                    onCommandsChange = { app.scope.launch { app.settingsStore.setQuickCommands(it) } },
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val current = sessionState) {
                SessionState.Connecting -> ConnectingBar()

                is SessionState.Reconnecting -> ReconnectingBar(
                    state = current,
                    onRetryNow = handle::reconnect,
                    onGiveUp = handle::abandon,
                )

                // 断开原因认不出来（多半是远端自己 exit 了）时也要给个入口，
                // 否则用户面对一屏静止的终端只能返回主页重开。
                SessionState.Disconnected -> failure?.let { ConnectionFailureBanner(it, handle, onEditHost) }
                    ?: ErrorBanner(
                        message = stringResource(R.string.session_disconnected),
                        actionLabel = stringResource(R.string.retry),
                        onAction = handle::reconnect,
                    )

                SessionState.Connected -> Unit
            }
            Box(modifier = Modifier.fillMaxSize()) {
                TerminalViewHost(state, modifier = Modifier.fillMaxSize())
            }
        }
    }

    // 主机密钥变更是阻断式的：可能是中间人，不点明白不让继续。
    (failure as? ConnectionFailure.HostKeyChanged)?.let { changed ->
        AlertDialog(
            onDismissRequest = onBack,
            title = { Text(stringResource(R.string.error_host_key_trust_new)) },
            text = {
                Text(
                    stringResource(
                        R.string.error_host_key_changed,
                        changed.endpoint,
                        changed.expected,
                        changed.actual,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    KnownHosts(context).trust(changed.endpoint, changed.actual)
                    handle.reconnect()
                }) { Text(stringResource(R.string.error_host_key_trust_new)) }
            },
            dismissButton = { TextButton(onClick = onBack) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ConnectingBar() {
    Column(modifier = Modifier.fillMaxWidth()) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            text = stringResource(R.string.connecting),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 重连中的状态条。
 *
 * 倒计时和两个按钮缺一不可：没有倒计时，「重连中」和「卡死了」在用户眼里没有区别；
 * 没有「立即重试」，刚出电梯的人要干等十几秒；没有「放弃」，一台真的下线了的机器
 * 会在通知栏和这里一直转下去。
 */
@Composable
private fun ReconnectingBar(
    state: SessionState.Reconnecting,
    onRetryNow: () -> Unit,
    onGiveUp: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.session_reconnecting, state.attempt),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.session_retry_countdown, state.secondsLeft),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onRetryNow) { Text(stringResource(R.string.session_retry_now)) }
            TextButton(onClick = onGiveUp) { Text(stringResource(R.string.session_give_up)) }
        }
    }
}

/** 按失败类型分流：认证问题送去改凭据，其余给重试。密钥变更走对话框，这里不重复渲染。 */
@Composable
private fun ConnectionFailureBanner(
    failure: ConnectionFailure,
    handle: TermSessionHandle,
    onEditHost: (hostId: String) -> Unit,
) {
    val message = connectionFailureText(failure, handle.host.endpoint)
    when (failure) {
        is ConnectionFailure.HostKeyChanged -> Unit

        ConnectionFailure.AuthFailed,
        ConnectionFailure.CredentialLost,
        ConnectionFailure.AgentUnsupported,
        -> ErrorBanner(
            message = message,
            actionLabel = stringResource(R.string.edit_credentials),
            onAction = { onEditHost(handle.host.id) },
        )

        // 跳板机的凭据不对，要改的是**那台**的凭据，所以按钮直达它的编辑页；
        // 跳板机只是连不上（或链配坏了）就退回重试 / 改当前主机。
        is ConnectionFailure.ProxyJumpFailed -> {
            val credentialIssue = failure.reason == ConnectionFailure.AuthFailed ||
                failure.reason == ConnectionFailure.CredentialLost
            ErrorBanner(
                message = message,
                actionLabel = stringResource(
                    if (credentialIssue) R.string.edit_credentials else R.string.retry
                ),
                onAction = {
                    if (credentialIssue) onEditHost(failure.jumpHostId ?: handle.host.id)
                    else handle.reconnect()
                },
            )
        }

        // 取消/超时不是「凭据错了」，重连就是再问一遍——文案已经说明白了（见 error_challenge_cancelled）
        ConnectionFailure.ChallengeCancelled,
        ConnectionFailure.ExecTimeout,
        is ConnectionFailure.Other,
        -> ErrorBanner(
            message = message,
            actionLabel = stringResource(R.string.retry),
            onAction = handle::reconnect,
        )
    }
}
