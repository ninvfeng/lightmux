package net.lighttools.lightmux.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.lighttools.lightmux.R
import net.lighttools.lightmux.data.Host
import net.lighttools.lightmux.session.SessionState
import net.lighttools.lightmux.session.TermSessionHandle
import net.lighttools.lightmux.tmux.HostTmuxState
import net.lighttools.lightmux.tmux.ProbeResult
import net.lighttools.lightmux.tmux.RelativeTime
import net.lighttools.lightmux.tmux.TmuxSession
import net.lighttools.lightmux.tmux.TmuxWindow
import net.lighttools.lightmux.ui.common.AddFab
import net.lighttools.lightmux.ui.common.ConfirmDialog
import net.lighttools.lightmux.ui.common.ErrorBanner
import net.lighttools.lightmux.ui.common.InputDialog

/**
 * 主页：**主机 → tmux 会话 → 窗口** 三级树，也是整个 app 唯一的常驻页面（PRD §4.1）。
 *
 * 交互约定：点会话 = attach 并进终端，点窗口 = 切过去再进终端，
 * 展开/折叠走行尾的箭头按钮——「点一下就进去」是这个产品的存在理由，
 * 不能被「点一下先展开」抢走。
 *
 * 一切跨导航要活下来的状态（展开集合、滚动位置、探测缓存）都在 [HomeViewModel] 里，
 * 这里的 `remember` 只用来放对话框这种一次性的东西。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onOpenTerminal: (sessionId: String) -> Unit,
    onAddHost: () -> Unit,
    onEditHost: (hostId: String) -> Unit,
    onOpenMonitor: (hostId: String) -> Unit,
    onOpenFiles: (hostId: String) -> Unit,
    onOpenForward: (hostId: String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hosts by vm.hosts.collectAsState()
    val sessions by vm.liveSessions.collectAsState()
    // 裸会话在 VM 里就按主机分好组，行里只查表——每行各 filter 一遍是 O(主机数 × 会话数)
    val bareSessions by vm.bareSessionsByHost.collectAsState()
    // 角标数走这条聚合流，不在行里读 handle.state.value——那是个裸值，断线重连不会触发重组
    val activeCounts by vm.activeSessionCounts.collectAsState()
    val liveForwards by vm.liveForwardCount.collectAsState()
    var pendingDelete by remember { mutableStateOf<Host?>(null) }
    var pendingKill by remember { mutableStateOf<Pair<Host, TmuxSession>?>(null) }
    var pendingRename by remember { mutableStateOf<Pair<Host, TmuxSession>?>(null) }
    var pendingKillWindow by remember { mutableStateOf<Pair<Host, TmuxWindow>?>(null) }
    var pendingNewSession by remember { mutableStateOf<Host?>(null) }
    // 命令随主机一起记下来：对话框上显示的和确认后执行的必须是同一条串
    var pendingInstall by remember { mutableStateOf<Pair<Host, String>?>(null) }
    var pendingDisconnectAll by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    // 没有会话、也没有转发时不占位：图标插在刷新左边，出现与消失都不会挪动刷新和设置。
                    // 转发也算一票：它常常是唯一还连着的东西（用户关完终端只留一条隧道），
                    // 只看会话数的话这时按钮就没了，反而够不着唯一需要断的那个。
                    if (sessions.isNotEmpty() || liveForwards > 0) {
                        IconButton(onClick = { pendingDisconnectAll = true }) {
                            Icon(Icons.Default.LinkOff, stringResource(R.string.disconnect_all))
                        }
                    }
                    // 只刷已展开的主机：为渲染主页去拨所有连接是耗电、慢、还容易触发 fail2ban 的灾难。
                    IconButton(onClick = vm::refreshExpanded) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.refresh))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, stringResource(R.string.settings))
                    }
                },
            )
        },
        floatingActionButton = {
            AddFab(onClick = onAddHost, contentDescription = stringResource(R.string.add_host))
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 「列表过期」优先于技术性错误文本：它才是这一刻用户需要知道的事（动作没执行、已刷新）。
            val banner = if (vm.staleNotice) stringResource(R.string.tmux_stale_refreshed) else vm.actionError
            banner?.let { message ->
                ErrorBanner(
                    message = message,
                    actionLabel = stringResource(R.string.close),
                    onAction = vm::clearActionError,
                )
            }

            if (hosts.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.hosts_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            PullToRefreshBox(
                // vm.refreshing 是 derivedStateOf：这里在 LazyColumn 外层，直接读探测状态的话
                // 任何一台主机探测一次都要把整张列表重组一遍
                isRefreshing = vm.refreshing,
                onRefresh = vm::refreshExpanded,
            ) {
                LazyColumn(state = vm.listState, modifier = Modifier.fillMaxSize()) {
                    items(hosts, key = { it.id }) { host ->
                        HostNode(
                            host = host,
                            vm = vm,
                            bareSessions = bareSessions[host.id].orEmpty(),
                            activeSessions = activeCounts[host.id] ?: 0,
                            onOpenTerminal = onOpenTerminal,
                            onOpenMonitor = { onOpenMonitor(host.id) },
                            onOpenFiles = { onOpenFiles(host.id) },
                            onOpenForward = { onOpenForward(host.id) },
                            onEditHost = { onEditHost(host.id) },
                            onDeleteHost = { pendingDelete = host },
                            onRenameSession = { pendingRename = host to it },
                            onKillSession = { pendingKill = host to it },
                            onKillWindow = { _, window -> pendingKillWindow = host to window },
                            onNewSession = { pendingNewSession = host },
                            onInstallTmux = {
                                vm.installCommand(host.id)?.let { pendingInstall = host to it }
                            },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { host ->
        ConfirmDialog(
            title = stringResource(R.string.delete_host_title),
            message = stringResource(R.string.delete_host_message, host.name),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = {
                vm.deleteHost(host.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    // kill 是不可逆的（会话里跑着的东西一起没），必须二次确认。
    pendingKill?.let { (host, session) ->
        ConfirmDialog(
            title = stringResource(R.string.tmux_kill_title),
            message = stringResource(R.string.tmux_kill_message, session.name),
            confirmLabel = stringResource(R.string.tmux_kill),
            onConfirm = {
                vm.killSession(host, session)
                pendingKill = null
            },
            onDismiss = { pendingKill = null },
        )
    }

    // 同样不可逆，而且关掉最后一个窗口连会话一起没，更该问一句。
    pendingKillWindow?.let { (host, window) ->
        ConfirmDialog(
            title = stringResource(R.string.tmux_kill_window_title),
            message = stringResource(R.string.tmux_kill_window_message, windowLabel(window)),
            confirmLabel = stringResource(R.string.tmux_kill_window),
            onConfirm = {
                vm.killWindow(host, window)
                pendingKillWindow = null
            },
            onDismiss = { pendingKillWindow = null },
        )
    }

    pendingRename?.let { (host, session) ->
        InputDialog(
            title = stringResource(R.string.tmux_rename_title),
            label = stringResource(R.string.tmux_session_name),
            initial = session.name,
            onConfirm = {
                vm.renameSession(host, session, it)
                pendingRename = null
            },
            onDismiss = { pendingRename = null },
        )
    }

    pendingNewSession?.let { host ->
        InputDialog(
            title = stringResource(R.string.tmux_new_session),
            label = stringResource(R.string.tmux_session_name),
            initial = vm.suggestedSessionName(host.id),
            onConfirm = { name ->
                vm.newTmuxSession(host, name)?.let(onOpenTerminal)
                pendingNewSession = null
            },
            onDismiss = { pendingNewSession = null },
        )
    }

    // 装 tmux 会改动远端系统，命令必须原样摆出来让用户看过再点（PRD §4.3）。
    pendingInstall?.let { (host, command) ->
        ConfirmDialog(
            title = stringResource(R.string.tmux_install_title),
            message = stringResource(R.string.tmux_install_message, command),
            confirmLabel = stringResource(R.string.tmux_install),
            onConfirm = {
                onOpenTerminal(vm.installTmux(host, command))
                pendingInstall = null
            },
            onDismiss = { pendingInstall = null },
        )
    }

    // 一下断掉所有会话和转发，而且就挨着刷新——误触代价太大，问一句。
    if (pendingDisconnectAll) {
        // 两句分开写、按数量取舍：会话或转发只有一边时，硬凑出「0 条端口转发」这种话
        // 会让人以为自己还开着什么。
        val sessionLine = if (sessions.isEmpty()) null
        else pluralStringResource(R.plurals.disconnect_all_message, sessions.size, sessions.size)
        val forwardLine = if (liveForwards == 0) null
        else pluralStringResource(R.plurals.disconnect_all_forwards, liveForwards, liveForwards)
        ConfirmDialog(
            title = stringResource(R.string.disconnect_all),
            message = listOfNotNull(sessionLine, forwardLine).joinToString("\n"),
            confirmLabel = stringResource(R.string.disconnect_all),
            onConfirm = {
                vm.disconnectAll()
                pendingDisconnectAll = false
            },
            onDismiss = { pendingDisconnectAll = false },
        )
    }
}

/**
 * 一台主机及其展开后的整棵子树。
 *
 * @param bareSessions 这台机器上的裸会话，已由 [HomeViewModel.bareSessionsByHost] 挑好分好组
 */
@Composable
private fun HostNode(
    host: Host,
    vm: HomeViewModel,
    bareSessions: List<TermSessionHandle>,
    activeSessions: Int,
    onOpenTerminal: (String) -> Unit,
    onOpenMonitor: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenForward: () -> Unit,
    onEditHost: () -> Unit,
    onDeleteHost: () -> Unit,
    onRenameSession: (TmuxSession) -> Unit,
    onKillSession: (TmuxSession) -> Unit,
    onKillWindow: (TmuxSession, TmuxWindow) -> Unit,
    onNewSession: () -> Unit,
    onInstallTmux: () -> Unit,
) {
    /*
     * 两个都必须过 derivedStateOf，只认「这一台的那一份」。
     *
     * [HomeViewModel.expandedHosts] 是一个整体替换的 Set、[HomeViewModel.tmuxStates] 是一整张 map，
     * 直接读等于每个节点都订阅了全局：展开任意一台、或任意一台探测有进展，屏幕上所有主机节点
     * 一起重组（行尾那些 lambda 捕获了 vm，重组还会一路穿透到 TreeRow）。
     * 派生之后只有自己这一台的布尔值/状态对象变了才醒——别家的条目在新 map 里是同一个引用。
     */
    val expanded by remember(vm, host.id) { derivedStateOf { host.id in vm.expandedHosts } }
    val state by remember(vm, host.id) { derivedStateOf { vm.stateOf(host.id) } }

    Column {
        HostRow(
            host = host,
            expanded = expanded,
            // 缓存是快照不是实时，时间戳要一直明示，否则用户会把三天前的列表当现在
            snapshot = if (expanded) snapshotLabel(state?.probedAt ?: 0L) else null,
            activeSessions = activeSessions,
            onToggle = { vm.toggleExpanded(host.id) },
            onOpenTerminal = { onOpenTerminal(vm.openTerminal(host)) },
            onOpenMonitor = onOpenMonitor,
            onOpenFiles = onOpenFiles,
            onOpenForward = onOpenForward,
            onEdit = onEditHost,
            onDelete = onDeleteHost,
        )

        if (!expanded) return@Column

        val probe = state?.probe
        if (probe is ProbeResult.Sessions) {
            probe.sessions.forEach { session ->
                TmuxSessionNode(
                    host = host,
                    session = session,
                    vm = vm,
                    onOpenTerminal = onOpenTerminal,
                    onRename = { onRenameSession(session) },
                    onKill = { onKillSession(session) },
                    onKillWindow = { onKillWindow(session, it) },
                )
            }
        }
        TmuxStatusRow(
            state = state,
            probe = probe,
            onInstall = onInstallTmux,
            onRetry = { vm.probe(host) },
        )

        // 裸会话（本 app 开的非 tmux 会话）与 tmux 会话平级：会话天然属于某台主机，
        // 拆到别的页面等于把树拍平再让用户自己拼回去（PRD §4.2）。
        bareSessions.forEach { handle ->
            BareSessionRow(
                handle = handle,
                onClick = { onOpenTerminal(handle.id) },
                onClose = { vm.closeSession(handle.id) },
            )
        }

        // 「新建会话」默认指 tmux 会话——这个产品的整个价值就在于会话活得比连接久，
        // 默认开一条断了就没的裸连接等于把卖点藏进第二行。没装 tmux 的机器上这一行不出现，
        // 那时上面的状态行摆着的是「点击安装」。
        if (probe is ProbeResult.Sessions || probe is ProbeResult.NoSessions) {
            TreeRow(
                level = 1,
                leading = Icons.Default.Add,
                title = stringResource(R.string.tmux_new_session),
                onClick = onNewSession,
            )
        }
        TreeRow(
            level = 1,
            leading = Icons.Default.Add,
            title = stringResource(R.string.new_terminal),
            onClick = { onOpenTerminal(vm.newTerminal(host)) },
        )
    }
}

/**
 * 探测状态行：读取中 / 读取失败 / 没有会话 / 没装 tmux。
 *
 * 这四种必须各说各的。尤其**不能把失败显示成「没有会话」**——用户会以为自己的会话丢了。
 *
 * 做成 vm 无关的 public，是为了让主页和快速切换抽屉共用同一份判断。这四个分支是全 app
 * 最不能出错的一段 UI，留两份拷贝早晚有一份漏掉一个分支，而那个 bug 长得就像「用户的会话没了」。
 */
@Composable
fun TmuxStatusRow(
    state: HostTmuxState?,
    probe: ProbeResult?,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
) {
    when {
        state?.loading == true -> TreeRow(
            level = 1,
            leading = Icons.Default.Refresh,
            title = stringResource(R.string.tmux_loading),
            onClick = {},
            trailing = {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            },
        )

        state?.error != null -> TreeRow(
            level = 1,
            leading = Icons.Default.Warning,
            title = stringResource(R.string.tmux_failed),
            subtitle = state.error,
            onClick = onRetry,
        )

        // 认得出包管理器就把这一行变成安装入口。认不出（或缓存来自旧版探测）时退回重试，
        // 不摆一个点了什么也不会发生的按钮。
        probe is ProbeResult.NoTmux -> TreeRow(
            level = 1,
            leading = Icons.Default.Warning,
            title = stringResource(R.string.tmux_absent),
            subtitle = if (probe.installer != null) stringResource(R.string.tmux_install_hint) else null,
            onClick = if (probe.installer != null) onInstall else onRetry,
        )

        probe is ProbeResult.NoSessions -> TreeRow(
            level = 1,
            leading = Icons.Default.Dashboard,
            title = stringResource(R.string.tmux_none),
            onClick = onRetry,
        )

        else -> Unit
    }
}

/** 一个 tmux 会话 + 展开后的窗口。 */
@Composable
private fun TmuxSessionNode(
    host: Host,
    session: TmuxSession,
    vm: HomeViewModel,
    onOpenTerminal: (String) -> Unit,
    onRename: () -> Unit,
    onKill: () -> Unit,
    onKillWindow: (TmuxWindow) -> Unit,
) {
    // 同 [HostNode]：expandedSessions 是整体替换的 Set，直接读会让展开任意一个会话
    // 把屏幕上所有会话节点一起拖着重组
    val expanded by remember(vm, host.id, session.name) {
        derivedStateOf { vm.isSessionExpanded(host.id, session.name) }
    }
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        TreeRow(
            level = 1,
            leading = Icons.Default.Dashboard,
            title = session.name,
            subtitle = buildString {
                append(pluralStringResource(R.plurals.tmux_windows, session.windowCount, session.windowCount))
                if (session.attached) append(" · ").append(stringResource(R.string.tmux_attached))
            },
            // 行体点击 = attach 并进终端。展开窗口交给行尾箭头，别抢主路径。
            onClick = { onOpenTerminal(vm.attach(host, session.name)) },
            onLongClick = { menuOpen = true },
            trailing = {
                IconButton(onClick = { vm.toggleSessionExpanded(host.id, session.name) }) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowDown
                        else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.tmux_toggle_windows),
                    )
                }
                // 结束会话摆到行尾最右，和裸会话、窗口的关闭图标对齐成一列：藏在长按里没人找得到。
                IconButton(onClick = onKill) {
                    Icon(Icons.Default.Close, stringResource(R.string.tmux_kill))
                }
            },
        )

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.tmux_rename)) },
                onClick = {
                    menuOpen = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.tmux_detach_others)) },
                enabled = session.attached,
                onClick = {
                    menuOpen = false
                    vm.detachOthers(host, session)
                },
            )
        }
    }

    if (expanded) {
        session.windows.forEach { window ->
            WindowRow(
                window = window,
                onClick = { onOpenTerminal(vm.openWindow(host, session.name, window)) },
                onKill = { onKillWindow(window) },
            )
        }
        // 建完直接落到新窗口里，和点已有窗口一个语义——建了却停在主页，用户还得再点一下。
        TreeRow(
            level = 2,
            leading = Icons.Default.Add,
            title = stringResource(R.string.tmux_new_window),
            onClick = { onOpenTerminal(vm.newWindow(host, session)) },
        )
    }
}

/** 行体点击 = 切过去，行尾图标 = 关掉它。两个动作都得一眼看见，误触由确认框兜。 */
@Composable
private fun WindowRow(window: TmuxWindow, onClick: () -> Unit, onKill: () -> Unit) {
    TreeRow(
        level = 2,
        leading = Icons.Default.Terminal,
        title = windowLabel(window),
        badge = if (window.active) "● ${stringResource(R.string.tmux_active)}" else null,
        onClick = onClick,
    ) {
        IconButton(onClick = onKill) {
            Icon(Icons.Default.Close, stringResource(R.string.tmux_kill_window))
        }
    }
}

/**
 * 主机行尾的一格动作。
 *
 * 40dp 而不是 M3 默认的 48dp：四格一起 160dp，360dp 屏上标题还剩 144dp，够放一个像样的主机名。
 * 再小就真按不准了——40dp 已经是 Material 触摸目标的下限。
 *
 * **这 40dp 只有配合调用处那个 [LocalMinimumInteractiveComponentSize] 覆盖才作数**：
 * 不关的话 `size(40.dp)` 只缩得动画出来的那一格，布局上每格仍占 48dp，
 * 四格实占 192dp，标题被压到 112dp——和快捷栏、文件页路径栏踩的是同一个坑。
 */
@Composable
private fun HostAction(icon: ImageVector, label: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, stringResource(label), modifier = Modifier.size(22.dp))
    }
}

/** 窗口名可以是空串（`rename-window ''`），那时只剩 index 也比显示一个「0: 」强。 */
private fun windowLabel(window: TmuxWindow): String =
    if (window.name.isEmpty()) window.index.toString() else "${window.index}: ${window.name}"

@Composable
private fun HostRow(
    host: Host,
    expanded: Boolean,
    snapshot: String?,
    activeSessions: Int,
    onToggle: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenMonitor: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenForward: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        TreeRow(
            level = 0,
            leading = if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            title = host.name,
            subtitle = if (snapshot != null) "${host.endpoint} · $snapshot" else host.endpoint,
            badge = if (activeSessions > 0) "●$activeSessions" else null,
            onClick = onToggle,
            onLongClick = { menuOpen = true },
        ) {
            /*
             * 四格图标自己成一个 Row，不摊给 TreeRow 那层。
             *
             * 摊上去的话每格之间会多吃一份 8dp 的 spacedBy，四格连带多出 24dp，
             * 标题从 144dp 掉到 120dp。这里贴着排，留给标题 144dp。
             *
             * 关掉 M3 强制的 48dp 最小触摸目标，[HostAction] 的 40dp 才落得下来——
             * 不关的话四格实占 192dp，上面那笔账根本不成立。只关这一处；
             * 树里其余的行尾按钮（展开、关闭）仍守 48dp。
             */
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    HostAction(Icons.Default.Terminal, R.string.terminal, onOpenTerminal)
                    HostAction(Icons.Default.Monitor, R.string.monitor, onOpenMonitor)
                    HostAction(Icons.Default.Folder, R.string.files, onOpenFiles)
                    HostAction(Icons.Default.SettingsEthernet, R.string.home_forward, onOpenForward)
                }
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.edit)) },
                onClick = {
                    menuOpen = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete)) },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun BareSessionRow(
    handle: TermSessionHandle,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val state by handle.state.collectAsState()
    TreeRow(
        level = 1,
        leading = Icons.Default.Terminal,
        title = handle.title,
        subtitle = when (val current = state) {
            SessionState.Connecting -> stringResource(R.string.connecting)
            SessionState.Connected -> null
            // 主页不显示倒计时秒数：这一行每秒重组一次会把整棵树带着一起刷
            is SessionState.Reconnecting -> stringResource(R.string.session_reconnecting, current.attempt)
            SessionState.Disconnected -> stringResource(R.string.session_disconnected)
        },
        onClick = onClick,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, stringResource(R.string.close_session))
        }
    }
}

/**
 * 缓存时间戳 → 「3 分钟前」。0 表示还没探测过，不显示。
 *
 * 抽屉也要用，所以是 public：同一套时间文案抄两份，早晚会各自漂移。
 */
@Composable
fun snapshotLabel(probedAt: Long): String? {
    if (probedAt <= 0L) return null
    return when (val age = RelativeTime.of(System.currentTimeMillis() - probedAt)) {
        RelativeTime.Age.JustNow -> stringResource(R.string.snapshot_just_now)
        is RelativeTime.Age.Minutes -> stringResource(R.string.snapshot_minutes, age.value)
        is RelativeTime.Age.Hours -> stringResource(R.string.snapshot_hours, age.value)
        is RelativeTime.Age.Days -> stringResource(R.string.snapshot_days, age.value)
    }
}

/**
 * 树的一行。主机（level 0）、tmux 会话与裸会话（level 1）、窗口（level 2）共用，
 * 缩进和点击语义都在这一处，别再各写一套。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TreeRow(
    level: Int,
    leading: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badge: String? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = (12 + level * 20).dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = leading,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (badge != null) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}
