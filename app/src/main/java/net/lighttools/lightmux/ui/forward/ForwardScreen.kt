package net.lighttools.lightmux.ui.forward

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import net.lighttools.lightmux.R
import net.lighttools.lightmux.forward.ForwardFailure
import net.lighttools.lightmux.forward.ForwardSpec
import net.lighttools.lightmux.forward.ForwardStatus
import net.lighttools.lightmux.forward.ListeningPort
import net.lighttools.lightmux.forward.PortError
import net.lighttools.lightmux.forward.PortFilter
import net.lighttools.lightmux.ui.common.AddFab
import net.lighttools.lightmux.ui.common.BackButton
import net.lighttools.lightmux.ui.common.BackgroundLimitBanner
import net.lighttools.lightmux.ui.common.ConfirmDialog
import net.lighttools.lightmux.ui.common.ErrorBanner
import net.lighttools.lightmux.ui.common.Spinner

/**
 * 端口转发。服务端起在 `127.0.0.1` 上的服务，经这里映射到手机本地端口。
 *
 * 页面上只有两条路：**从远端在监听的那堆端口里挑一个**（一进来就扫好了，见 [ForwardViewModel]），
 * 或者手填一条。返回走中央栈，页面内部不写 BackHandler（CLAUDE.md 架构要点 ④）。
 *
 * 这是主页那条路进来时的整页壳；从终端进来的是 [ForwardSheet]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardScreen(
    vm: ForwardViewModel,
    onBack: () -> Unit,
    onOpenWeb: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    val query = vm.query
                    if (query == null) {
                        Column {
                            Text(stringResource(R.string.forward_title))
                            vm.host?.let {
                                // 主机名是用户自己起的，长名字不截会把标题挤成两三行、
                                // 顶得下面的返回箭头和两个动作图标错位（面板那份早就截了）
                                Text(
                                    it.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    } else {
                        FilterField(vm = vm, query = query)
                    }
                },
                navigationIcon = {
                    // 搜索框开着时这个箭头先收搜索框，和系统返回键一个行为（vm::onBack）——
                    // 一个退页面一个收框，用户没法预判点下去会发生什么
                    BackButton(onClick = if (vm.query == null) onBack else vm::closeFilter)
                },
                actions = {
                    FilterAction(vm)
                    ProbeAction(vm)
                },
            )
        },
        floatingActionButton = {
            AddFab(onClick = vm::newForward, contentDescription = stringResource(R.string.forward_add))
        },
    ) { padding ->
        ForwardContent(
            vm = vm,
            onOpenWeb = onOpenWeb,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

/**
 * 同一份转发列表的另一个壳：**终端页上叠的半屏面板**。
 *
 * 这条路原先是整页跳转，当时的理由是「配完就去浏览器了，不会再回到终端上来」——
 * 用反了才发现顺序恰恰相反：要配转发，多半是因为服务刚在这个终端里起来、日志正在滚，
 * 而「转发通没通、端口对不对」的答案就在那几行日志里。整页跳走等于把答案盖住。
 * 半屏停在终端上方，下面在干什么看得见；真要翻长列表再往上一拖变全屏。
 * 手感与文件面板一致（见 [net.lighttools.lightmux.ui.files.FilesSheet]）。
 *
 * **返回键 = 关面板**：面板是独立窗口（`ComponentDialog`），返回先被它自己的 dispatcher
 * 接走，根上那个中央 `BackHandler` 收不到，这里也不去抢——抢了还会连带 Android 14 的
 * 返回预览动画一起失效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardSheet(vm: ForwardViewModel, onDismiss: () -> Unit, onOpenWeb: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        // 撑满可用高度，「半屏档」才存在：内容不够高时 M3 直接跳过 PartiallyExpanded，
        // 一弹就是贴着内容的一小条，拖不出全屏
        Column(modifier = Modifier.fillMaxHeight()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val query = vm.query
                if (query == null) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.forward_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        vm.host?.let {
                            Text(
                                text = it.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else {
                    FilterField(vm = vm, query = query, modifier = Modifier.weight(1f))
                }
                FilterAction(vm)
                ProbeAction(vm)
                // 面板里没有 FAB 的位置，「加一条」挪到标题行。它本来也是次要入口——
                // 主路径是「扫一下，点添加」。
                IconButton(onClick = vm::newForward) {
                    Icon(Icons.Default.Add, stringResource(R.string.forward_add))
                }
            }
            ForwardContent(vm = vm, onOpenWeb = onOpenWeb, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * 重新扫描。列表是进页面时自动扫出来的，这个按钮管的是「服务刚在终端里起来，再扫一次」。
 *
 * 两个壳各摆各的位置，但转圈那一下是同一份状态，不能各写一份。
 */
@Composable
private fun ProbeAction(vm: ForwardViewModel) {
    IconButton(onClick = vm::probe, enabled = !vm.probing) {
        if (vm.probing) {
            Spinner(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Default.Refresh, stringResource(R.string.forward_probe))
        }
    }
}

/** 放大镜。它现在过滤的是**已经在手上的**那份列表，不再发起任何请求。 */
@Composable
private fun FilterAction(vm: ForwardViewModel) {
    if (vm.query == null) {
        IconButton(onClick = vm::startFilter) {
            Icon(Icons.Default.Search, stringResource(R.string.forward_filter))
        }
    } else {
        IconButton(onClick = vm::closeFilter) {
            Icon(Icons.Default.Close, stringResource(R.string.close))
        }
    }
}

/**
 * 搜索框。占的是标题的位置——两个壳的标题行都只有这么点宽，再挤一个输入框进去就没法看了。
 *
 * 用 [BasicTextField] 而不是 M3 的 `TextField`：后者 56dp 的容器塞进标题行会把整行顶变形
 * （同 [net.lighttools.lightmux.ui.web.WebScreen] 的地址栏）。占位文字自己画一层。
 */
@Composable
private fun FilterField(vm: ForwardViewModel, query: String, modifier: Modifier = Modifier) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // 展开就把光标送进去：点放大镜的下一个动作必然是打字
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(modifier = modifier) {
        if (query.isEmpty()) {
            Text(
                text = stringResource(R.string.forward_filter),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BasicTextField(
            value = query,
            onValueChange = vm::editQuery,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = LocalContentColor.current),
            singleLine = true,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            // 过滤是边打边生效的，回车只剩「把键盘收了，让列表露出来」这一件事可做
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        )
    }
}

/**
 * 两个壳共用的正文：提示条 + 转发列表 + 探测出来的远端端口，外加编辑与删除那两个对话框。
 *
 * 对话框也留在这里而不是交给壳：它们跟着列表行走（点一行编辑、长按一行删除），
 * 壳只负责标题栏和「加一条」的摆放。
 */
@Composable
private fun ForwardContent(
    vm: ForwardViewModel,
    onOpenWeb: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val allRows by vm.rows.collectAsState()
    var deleting by remember { mutableStateOf<ForwardSpec?>(null) }

    /*
     * 一露面就扫一次，不等用户点。
     *
     * 点开「转发」的意思就是「让我看看这台机器上有什么端口」——扫描本身是一次 `exec`，
     * 藏在一个放大镜后面只是让人多点一下。两个壳都从这儿走，各写一份迟早分叉。
     */
    LaunchedEffect(Unit) {
        // 两个壳共用一个 VM，上一次留下的过滤词不该跟到这一次：面板被返回键关掉时
        // 收不到任何回调，不在这儿清就会「再打开还筛着，端口像是丢了」
        vm.closeFilter()
        vm.probe()
    }

    // 搜索框收着时 query 是 null，PortFilter 对空串全放行，两种情况在这里合成一件事
    val query = vm.query.orEmpty()
    /*
     * 两次过滤都 remember 住。这里是列表外层，任何一条转发的状态跳一下（Starting → Active、
     * 重试计数往前走）都会把这段重跑一遍——而那时 query 和列表本身多半根本没变。
     * 一台机器上扫出几百个监听端口是常事，边打字边全量过滤已经够费了，不该再白跑。
     */
    val probed = vm.probed
    val rows = remember(allRows, query) { allRows.filter { PortFilter.matches(query, it.spec) } }
    val ports = remember(probed, query) { probed?.filter { PortFilter.matches(query, it) } }
    val filtering = query.isNotBlank()
    // 「已添加」的判断在列表外建一次索引。这里必须看**全量**转发：被筛掉的那条照样占着端口。
    val taken = remember(allRows) { takenRemotePorts(allRows) }

    Column(modifier = modifier) {
        vm.error?.let {
            ErrorBanner(
                message = it,
                actionLabel = stringResource(R.string.retry),
                onAction = { vm.dismissError(); vm.probe() },
            )
        }
        // 转发这里最该挂这条提示：用法本身就是「转完切去浏览器」，
        // 而切走的那一下正是省电策略掐网的时机——隧道会当着用户的面断掉。
        BackgroundLimitBanner()
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                // 转发只绑环回口，这件事必须写在明面上：用户多半以为「转发出来的端口」
                // 同网段的电脑也能连，照着去试会白折腾半天。
                Text(
                    text = stringResource(R.string.forward_loopback_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            if (rows.isEmpty()) {
                // 过滤到空不提示「还没有转发」——用户配的那几条还在，只是这会儿被筛掉了
                if (!filtering) {
                    item {
                        Text(
                            text = stringResource(R.string.forward_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            } else {
                items(rows, key = { it.spec.id }) { row ->
                    ForwardItem(
                        row = row,
                        onToggle = { vm.toggle(row) },
                        onEdit = { vm.edit(row.spec) },
                        onOpen = { onOpenWeb(row.spec.localUrl) },
                        onLongPress = { deleting = row.spec },
                    )
                }
            }

            ports?.let { visible ->
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        text = stringResource(R.string.forward_remote_ports),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (visible.isEmpty()) {
                    item {
                        Text(
                            // 「这台机器上没有监听中的端口」和「打的这几个字没匹配上」是两回事，
                            // 说错了会让用户以为服务没起来
                            text = stringResource(
                                if (filtering) R.string.forward_filter_none
                                else R.string.forward_remote_ports_empty
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                items(visible, key = { it.port }) { port ->
                    RemotePortItem(
                        port = port,
                        // 已经配过的不再让点第二次——重复一条只会撞本地端口
                        added = port.port in taken,
                        onAdd = { vm.addFromProbe(port) },
                    )
                }
            }
        }
    }

    vm.draft?.let { draft ->
        ForwardDialog(
            draft = draft,
            vm = vm,
            onDelete = { allRows.firstOrNull { it.spec.id == draft.id }?.let { deleting = it.spec } },
        )
    }

    deleting?.let { spec ->
        ConfirmDialog(
            title = stringResource(R.string.forward_delete_title),
            message = stringResource(
                R.string.forward_mapping, spec.remoteHost, spec.remotePort, spec.localPort
            ),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = {
                vm.delete(spec)
                vm.dismissDraft()
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ForwardItem(
    row: ForwardRow,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 删除放长按里：这一行最常按的是开关，把删除摆在旁边迟早误触
            .combinedClickable(onClick = onEdit, onLongClick = onLongPress)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 备注和 remoteHost 都是用户手填的，长了不截会把这一行撑成好几行，
        // 右边的开关跟着往下掉，一列开关就对不齐了
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.spec.label ?: stringResource(R.string.forward_port_n, row.spec.remotePort),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    R.string.forward_mapping,
                    row.spec.remoteHost,
                    row.spec.remotePort,
                    row.spec.localPort,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StatusLine(row)
        }
        // 通了才给「打开」：转发没起来时打开只会看到一个连接失败页，
        // 那会让用户以为是服务端的问题。
        if (row.status is ForwardStatus.Active) {
            IconButton(onClick = onOpen) {
                Icon(Icons.Default.OpenInBrowser, stringResource(R.string.forward_open))
            }
        }
        Switch(checked = row.status?.isLive == true, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun StatusLine(row: ForwardRow) {
    val status = row.status ?: return
    val text = when (status) {
        ForwardStatus.Starting -> stringResource(R.string.forward_status_starting)
        ForwardStatus.Active -> stringResource(R.string.forward_status_active)
        is ForwardStatus.Retrying -> stringResource(R.string.forward_status_retrying, status.attempt)
        is ForwardStatus.Failed -> when (status.reason) {
            ForwardFailure.PortInUse ->
                stringResource(R.string.forward_status_port_in_use, row.spec.localPort)

            ForwardFailure.Rejected ->
                status.detail ?: stringResource(R.string.forward_status_rejected)

            ForwardFailure.HostGone -> stringResource(R.string.forward_status_host_gone)
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        // Rejected 那一支摆的是 sshd 原样吐回来的一行话，长度完全不受控；
        // 留两行够看清是哪类失败，再长就该去看日志了
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        color = when (status) {
            is ForwardStatus.Failed -> MaterialTheme.colorScheme.error
            ForwardStatus.Active -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
private fun RemotePortItem(port: ListeningPort, added: Boolean, onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 进程名（`/usr/lib/jvm/.../java` 这种全路径很常见）和监听地址都可能很长，
        // 不截会把「添加」按钮挤出屏幕
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = port.process?.let { "${port.port}  $it" } ?: port.port.toString(),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (port.loopbackOnly) {
                    stringResource(R.string.forward_port_loopback, port.address)
                } else {
                    port.address
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onAdd, enabled = !added) {
            Text(
                stringResource(
                    if (added) R.string.forward_port_added else R.string.forward_port_add
                )
            )
        }
    }
}

@Composable
private fun ForwardDialog(draft: ForwardDraft, vm: ForwardViewModel, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = vm::dismissDraft,
        title = {
            Text(
                stringResource(
                    if (draft.id == null) R.string.forward_dialog_new else R.string.forward_dialog_edit
                )
            )
        },
        text = {
            // 四个输入框 + 键盘弹起来，小屏上装不下，不给滚动条最后一格就点不到了
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = draft.remotePort,
                    onValueChange = vm::editRemotePort,
                    label = { Text(stringResource(R.string.forward_remote_port)) },
                    isError = draft.remoteError != null,
                    supportingText = draft.remoteError?.let { { Text(portErrorText(it)) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.localPort,
                    onValueChange = vm::editLocalPort,
                    label = { Text(stringResource(R.string.forward_local_port)) },
                    isError = draft.localError != null,
                    supportingText = draft.localError?.let { { Text(portErrorText(it)) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.remoteHost,
                    onValueChange = vm::editRemoteHost,
                    label = { Text(stringResource(R.string.forward_remote_host)) },
                    supportingText = { Text(stringResource(R.string.forward_remote_host_hint)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.label,
                    onValueChange = vm::editLabel,
                    label = { Text(stringResource(R.string.forward_label)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = { vm.saveDraft() }) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            Row {
                if (draft.id != null) {
                    TextButton(onClick = onDelete) {
                        Text(
                            stringResource(R.string.delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = vm::dismissDraft) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun portErrorText(error: PortError): String = stringResource(
    when (error) {
        PortError.OutOfRange -> R.string.forward_port_out_of_range
        PortError.Privileged -> R.string.forward_port_privileged
    }
)
