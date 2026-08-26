package net.lighttools.lightmux.ui.files

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.lighttools.lightmux.R
import net.lighttools.lightmux.sftp.RemoteEntry
import net.lighttools.lightmux.sftp.RemoteFileType
import net.lighttools.lightmux.sftp.SftpPath
import net.lighttools.lightmux.sftp.Transfer
import net.lighttools.lightmux.sftp.TransferDirection
import net.lighttools.lightmux.sftp.TransferQueue
import net.lighttools.lightmux.sftp.TransferStatus
import net.lighttools.lightmux.ui.common.BackButton
import net.lighttools.lightmux.ui.common.ConfirmDialog
import net.lighttools.lightmux.ui.common.ErrorBanner
import net.lighttools.lightmux.ui.common.copyToClipboard
import net.lighttools.lightmux.ui.common.InputDialog
import net.lighttools.lightmux.ui.home.TreeRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件页：一台主机的 SFTP 浏览器（PRD §4.4），从主页那条路进来时的壳。
 *
 * **整个浏览过程只有这一个页面**，进目录不叠导航栈——目录层级在 [FilesViewModel] 里。
 * 返回键的语义（子目录回上级、起点才退页面）由 `LightmuxRoot` 那个唯一的 `BackHandler`
 * 调 [FilesViewModel.goUp] 实现，这里**不写第二个 BackHandler**（CLAUDE.md 架构要点 ④）。
 *
 * 本地文件全部走 SAF 选择器，app 不申请任何存储权限。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    vm: FilesViewModel,
    queue: TransferQueue,
    onBack: () -> Unit,
    onEditFile: (path: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FilesContent(
        vm = vm,
        queue = queue,
        onEditFile = onEditFile,
        // 主页进来的这条路没有终端可送，那一项菜单不出现，别给一个点了没反应的入口
        onSendToTerminal = null,
        modifier = modifier.fillMaxSize(),
    ) { actions ->
        TopAppBar(
            // 路径不在这儿了：它下面那条路径栏已经完整显示一份，顶栏再来一份就是同一串字挤两行
            title = {
                Text(
                    text = vm.host?.name ?: stringResource(R.string.files),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            navigationIcon = { BackButton(onBack) },
            actions = actions,
        )
    }
}

/**
 * 同一个浏览器的另一个壳：**终端页上叠的半屏面板**。
 *
 * 从终端点开文件，十有八九是为了「挑个路径填进正在敲的那行命令」——整页跳走会把命令上下文
 * （敲了一半的那半行、上一条的输出）藏起来，回来还得重新对照。半屏就停在终端上方，
 * 下面在干什么看得见；真要翻目录再往上一拖变全屏。手感与快捷命令表一致（见 [QuickCommandsSheet]）。
 *
 * **返回键 = 关面板**，不是「回上一级」：面板是独立窗口（`ComponentDialog`），返回先被它自己的
 * dispatcher 接走，根上那个中央 `BackHandler` 收不到，这里也不去抢——抢了还会连带
 * Android 14 的返回预览动画一起失效。上一级走路径栏左边那个 `↑`，目录状态留在 VM 里，
 * 关掉再点开还是原地。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesSheet(
    vm: FilesViewModel,
    queue: TransferQueue,
    onEditFile: (path: String) -> Unit,
    onSendToTerminal: (path: String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        FilesContent(
            vm = vm,
            queue = queue,
            onEditFile = onEditFile,
            onSendToTerminal = onSendToTerminal,
            // 撑满可用高度，「半屏档」才存在：内容不够高时 M3 直接跳过 PartiallyExpanded，
            // 一弹就是贴着内容的一小条，拖不出全屏
            modifier = Modifier.fillMaxHeight(),
        ) { actions ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = vm.host?.name ?: stringResource(R.string.files),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
                actions()
            }
        }
    }
}

/**
 * 两个壳共用的正文：错误条 + 路径栏 + 目录列表 + 传输条，外加上传/新建/删改那一堆状态。
 *
 * @param header 顶部那一行由壳决定（整页是 `TopAppBar`，面板是一行标题），
 *   但**动作图标是同一份**，所以由正文塞进去——两处各写一份迟早漂移
 */
@Composable
private fun FilesContent(
    vm: FilesViewModel,
    queue: TransferQueue,
    onEditFile: (path: String) -> Unit,
    onSendToTerminal: ((path: String) -> Unit)?,
    modifier: Modifier = Modifier,
    header: @Composable (actions: @Composable RowScope.() -> Unit) -> Unit,
) {
    val state = vm.state
    val host = vm.host
    /*
     * 拿 State 本身，**不在这一层读它的值**。
     *
     * 传输中的每个数据块都会往 [TransferQueue.transfers] 里换一份新列表（`transferredBytes` 在变），
     * 在正文这一层 `by` 出来等于把「进度条走了一格」变成「整页重组」——路径栏、目录列表、
     * 每一行的长按菜单全跟着重算，传一个大文件时一秒几十遍。
     * 真正要看进度的只有底下那条传输条，读取就下沉到它里面。
     */
    val transfers = queue.transfers.collectAsState()
    val context = LocalContext.current
    val copied = stringResource(R.string.files_path_copied)

    // 这三个都是一次性的对话框开关，页面没了就该没了，留在 remember 里正合适；
    // 「等着挑落点的下载」不一样，它要熬过 Activity 重建，所以在 vm 里（见 FilesViewModel.pendingDownload）
    var pendingRename by remember { mutableStateOf<RemoteEntry?>(null) }
    var pendingDelete by remember { mutableStateOf<RemoteEntry?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }

    // 浏览通道的关闭时机由 LightmuxRoot 统一判（进编辑器再回来时不该断了重连），这里只管拉数据
    LaunchedEffect(vm) { vm.start() }

    // 多选：一次挑三个文件传上去是常态，让用户点三遍「上传」没道理
    val uploadPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val target = vm.host ?: return@rememberLauncherForActivityResult
        uris.forEach { queue.upload(target, it, vm.state.path) }
    }
    val downloadPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(DOWNLOAD_MIME)
    ) { uri ->
        val entry = vm.consumePendingDownload()
        val target = vm.host
        if (uri != null && entry != null && target != null) queue.download(target, entry, uri)
    }

    // 上传完成后目录里多了东西，不自动重列的话用户会以为没传上去。
    // 派生成一个计数：正文只在「还有几条在传」变了的时候才醒，字节数往前挪不算。
    val uploading by remember(transfers) {
        derivedStateOf {
            transfers.value.count { it.direction == TransferDirection.UPLOAD && it.active }
        }
    }
    var lastUploading by remember { mutableIntStateOf(0) }
    LaunchedEffect(uploading) {
        if (lastUploading > 0 && uploading == 0) vm.refresh()
        lastUploading = uploading
    }

    // 导航栏留白由这里统一给：整页原先靠 Scaffold 的 contentPadding，面板则和快捷命令表一样自己加
    Column(modifier.navigationBarsPadding()) {
        header {
            IconButton(onClick = vm::toggleHidden) {
                Icon(
                    imageVector = if (vm.showHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = stringResource(R.string.files_show_hidden),
                )
            }
            IconButton(onClick = { uploadPicker.launch(arrayOf(UPLOAD_MIME)) }, enabled = host != null) {
                Icon(Icons.Default.Upload, stringResource(R.string.files_upload))
            }
            IconButton(onClick = { creatingFolder = true }, enabled = host != null) {
                Icon(Icons.Default.CreateNewFolder, stringResource(R.string.files_new_folder))
            }
            IconButton(onClick = vm::refresh) {
                Icon(Icons.Default.Refresh, stringResource(R.string.refresh))
            }
        }

        // 主机没了就只说这一句，不给重试：这台机器不会再回来，按多少次都一样
        if (state.hostMissing) ErrorBanner(message = stringResource(R.string.error_host_missing))
        state.error?.let { error ->
            ErrorBanner(
                message = stringResource(R.string.files_failed, error),
                actionLabel = stringResource(R.string.retry),
                onAction = vm::refresh,
            )
        }
        vm.actionError?.let { error ->
            ErrorBanner(
                message = error,
                actionLabel = stringResource(R.string.close),
                onAction = vm::clearActionError,
            )
        }

        PathBar(
            path = state.path,
            crumbs = vm.crumbs,
            onGo = vm::goTo,
            onCopy = { copyToClipboard(context, state.path, copied) },
            onSendToTerminal = onSendToTerminal?.let { send -> { send(state.path) } },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val entries = vm.visibleEntries
            when {
                // 失败时保留上一屏内容，所以这里只处理「从没列成功过」的情况
                !state.loaded && state.loading -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                entries.isEmpty() && state.loaded -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.files_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(entries, key = { it.path }) { entry ->
                        EntryRow(
                            entry = entry,
                            onClick = { vm.open(entry) { onEditFile(it.path) } },
                            onCopyPath = { copyToClipboard(context, entry.path, copied) },
                            onSendToTerminal = onSendToTerminal?.let { send -> { send(entry.path) } },
                            onDownload = {
                                vm.requestDownload(entry)
                                downloadPicker.launch(entry.name)
                            },
                            onEdit = { onEditFile(entry.path) },
                            onRename = { pendingRename = entry },
                            onDelete = { pendingDelete = entry },
                        )
                    }
                }
            }
            // 刷新中的细进度条：目录已经有内容时不该整屏转圈，那会让人以为内容没了
            if (state.loading && state.loaded) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            }
        }

        TransferBar(
            transfers = transfers,
            onCancel = queue::cancel,
            onClear = queue::clearFinished,
        )
    }

    if (creatingFolder) {
        NameDialog(
            title = stringResource(R.string.files_new_folder_title),
            initial = "",
            onConfirm = {
                vm.mkdir(it)
                creatingFolder = false
            },
            onDismiss = { creatingFolder = false },
        )
    }

    pendingRename?.let { entry ->
        NameDialog(
            title = stringResource(R.string.files_rename_title),
            initial = entry.name,
            onConfirm = {
                vm.rename(entry, it)
                pendingRename = null
            },
            onDismiss = { pendingRename = null },
        )
    }

    // 删目录是递归的，删掉就找不回来了，必须二次确认
    pendingDelete?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.files_delete_title),
            message = if (entry.isDirectory) {
                stringResource(R.string.files_delete_dir_message, entry.name)
            } else {
                stringResource(R.string.files_delete_message, entry.name)
            },
            confirmLabel = stringResource(R.string.delete),
            onConfirm = {
                vm.delete(entry)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/**
 * 路径栏：上一级 + 一整串当前路径 + 复制 / 输入到终端。
 *
 * 原来这里是一排面包屑按钮，一级一个 `TextButton`：`/var/lib/docker/volumes` 这种深路径
 * 光按钮的左右留白就吃掉大半屏，还得横划才看得全。现在按路径本来的样子连着写一行，
 * 省出来的宽度给了右边两个图标——**复制路径**和**输入到终端**针对的是「当前目录」，
 * 和长按某一行拿到的是不同的东西（`cd`、`tar -C` 要的是目录，不是目录里的某个文件）。
 *
 * 多级跳转没丢：点路径本身弹出祖先列表，比一级一级点上去快。
 *
 * @param onSendToTerminal null = 不是从终端进来的，那个图标就不出现
 */
@Composable
private fun PathBar(
    path: String,
    crumbs: List<SftpPath.Crumb>,
    onGo: (String) -> Unit,
    onCopy: () -> Unit,
    onSendToTerminal: (() -> Unit)?,
) {
    // 最后一级就是当前目录，跳过去是原地不动，不列
    val ancestors = crumbs.dropLast(1)
    var menuOpen by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    /*
     * 深路径的重点在末尾（「我在哪」），横滚条却默认停在开头，一屏只看得到 `/var/lib/do…`。
     * 盯 maxValue 而不是在 path 变的那一帧滑：那时新路径还没排版，maxValue 还是上一条的。
     * 用户自己往左划不会被打断——手划不改变 maxValue。
     */
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) }
    }

    /*
     * 关掉 M3 强制的 48dp 最小触摸目标。
     *
     * 不关的话 [CompactIconButton] 的 36dp 只缩得动水波纹，**布局上仍占 48dp**，
     * 三个图标白吃 36dp 宽度，这一栏也就压不下去——和快捷栏踩的是同一个坑（见 [ExtraKeysBar]）。
     * 只关这一处；文件列表、顶栏那些按钮仍守 48dp。
     */
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 上一级走 parent 而不是 vm.goUp：goUp 退的是「来时的路」（返回键的语义），
            // 在起点会退无可退，而这个图标按下去应该永远是「到上层目录」
            CompactIconButton(
                icon = Icons.Default.ArrowUpward,
                contentDescription = stringResource(R.string.files_go_up),
                enabled = path != SftpPath.ROOT,
                onClick = { onGo(SftpPath.parent(path)) },
            )

            Box(Modifier.weight(1f)) {
                Text(
                    text = path,
                    modifier = Modifier
                        // clickable 在外、horizontalScroll 在内：可点区是看得见的那一段，
                        // 不是被划到屏幕外的那一截
                        .clickable(enabled = ancestors.isNotEmpty()) { menuOpen = true }
                        .horizontalScroll(scroll)
                        .padding(vertical = 8.dp),
                    maxLines = 1,
                    style = MaterialTheme.typography.bodyMedium,
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    ancestors.forEach { crumb ->
                        DropdownMenuItem(
                            // 显示完整路径而不是那一级的名字：一串 `usr`/`local`/`share` 认不出深浅
                            text = { Text(crumb.path, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = {
                                menuOpen = false
                                onGo(crumb.path)
                            },
                        )
                    }
                }
            }

            CompactIconButton(
                icon = Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.files_copy_path),
                onClick = onCopy,
            )
            onSendToTerminal?.let { send ->
                CompactIconButton(
                    icon = Icons.Default.Terminal,
                    contentDescription = stringResource(R.string.files_send_to_terminal),
                    onClick = send,
                )
            }
        }
    }
}

/**
 * 比 [IconButton] 的 48dp 窄一圈：这一排图标是配角，占满标准触摸目标就把路径挤没了。
 *
 * **必须配合调用处那个 [LocalMinimumInteractiveComponentSize] 覆盖**，否则只是水波纹变小。
 */
@Composable
private fun CompactIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp)) {
        Icon(icon, contentDescription, modifier = Modifier.size(20.dp))
    }
}

/**
 * 一行 + 挂在这一行上的长按菜单。菜单锚在行内，弹出位置才跟着手指走。
 *
 * 路径那两项排在最前：一屏文件里挑一个填进命令行，是这个页面被打开的主要理由——
 * 在手机上照着屏幕手打 `/var/lib/docker/volumes/...` 才是真正劝退的那件事。
 * 删除排最后，离手指最远。
 *
 * @param onSendToTerminal null = 不是从终端进来的（主页那条路），这一项不出现
 */
@Composable
private fun EntryRow(
    entry: RemoteEntry,
    onClick: () -> Unit,
    onCopyPath: () -> Unit,
    onSendToTerminal: (() -> Unit)?,
    onDownload: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        TreeRow(
            level = 0,
            leading = entry.icon(),
            title = entry.name,
            subtitle = entry.detail(),
            onClick = onClick,
            onLongClick = { menuOpen = true },
        )

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            // 目录也给：`cd` 和 `tar -C` 一样要目录路径
            DropdownMenuItem(
                text = { Text(stringResource(R.string.files_copy_path)) },
                onClick = {
                    menuOpen = false
                    onCopyPath()
                },
            )
            onSendToTerminal?.let { send ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.files_send_to_terminal)) },
                    onClick = {
                        menuOpen = false
                        send()
                    },
                )
            }
            HorizontalDivider()
            // 目录不能下载也不能编辑：打包下载得先在远端 tar，那是终端该干的事
            if (!entry.isDirectory) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.files_download)) },
                    onClick = {
                        menuOpen = false
                        onDownload()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.files_edit)) },
                    onClick = {
                        menuOpen = false
                        onEdit()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.tmux_rename)) },
                onClick = {
                    menuOpen = false
                    onRename()
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

/** 新建 / 重命名共用。名字非法时直接不让点确认，别等一个往返回来再报错。 */
@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    InputDialog(
        title = title,
        label = stringResource(R.string.files_name),
        initial = initial,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        validate = SftpPath::isValidName,
    )
}

/**
 * 底部传输条。默认折叠成一行摘要，点开才看细节——传输是后台的事，
 * 不该在浏览时占掉半屏。
 *
 * 收 `State` 而不是收现成的列表：进度每秒变几十次，让调用方把值读出来再传进来，
 * 等于把整个文件页拖进这个刷新频率（见 [FilesContent]）。这里是唯一真的要看进度的地方，
 * 订阅就落在这一层。
 */
@Composable
private fun TransferBar(
    transfers: State<List<Transfer>>,
    onCancel: (String) -> Unit,
    onClear: () -> Unit,
) {
    val list = transfers.value
    if (list.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val active = list.count { it.active }

    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (active > 0) stringResource(R.string.files_transfers_active, active)
                    else stringResource(R.string.files_transfers_idle),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                )
                if (active == 0) {
                    TextButton(onClick = onClear) { Text(stringResource(R.string.files_transfers_clear)) }
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                        contentDescription = stringResource(R.string.files_transfers),
                    )
                }
            }
            if (expanded) {
                list.forEach { transfer ->
                    TransferRow(transfer = transfer, onCancel = { onCancel(transfer.id) })
                }
            }
        }
    }
}

@Composable
private fun TransferRow(transfer: Transfer, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (transfer.direction == TransferDirection.UPLOAD) Icons.Default.Upload
                else Icons.Default.Download,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(
                    text = transfer.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = transfer.statusText(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (transfer.status == TransferStatus.FAILED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (transfer.active) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, stringResource(R.string.files_transfer_cancel))
                }
            }
        }
        if (transfer.active) {
            val ratio = transfer.ratio
            // 长度未知时给个不定长动画，别画一条永远停在 0% 的进度条
            if (ratio == null) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
            else LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth().height(3.dp))
        }
    }
}

@Composable
private fun Transfer.statusText(): String = when (status) {
    TransferStatus.QUEUED -> stringResource(R.string.files_transfer_queued, hostName)
    TransferStatus.RUNNING -> stringResource(
        R.string.files_transfer_progress,
        SftpPath.humanSize(transferredBytes),
        SftpPath.humanSize(totalBytes),
    )

    TransferStatus.DONE -> stringResource(R.string.files_transfer_done)
    TransferStatus.CANCELLED -> stringResource(R.string.files_transfer_cancelled)
    TransferStatus.FAILED -> stringResource(R.string.files_transfer_failed, error.orEmpty())
}

private fun RemoteEntry.icon(): ImageVector = when (type) {
    RemoteFileType.DIRECTORY -> Icons.Default.Folder
    RemoteFileType.SYMLINK -> Icons.Default.Link
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

/** 「大小 · 修改时间」。目录不显示大小——SFTP 给的是目录项本身的字节数，不是里面内容的总和。 */
@Composable
private fun RemoteEntry.detail(): String {
    val format = rememberTimeFormat()
    val time = remember(modifiedEpochSeconds, format) {
        if (modifiedEpochSeconds <= 0L) "" else format.format(Date(modifiedEpochSeconds * 1000L))
    }
    return if (isDirectory) time else "${SftpPath.humanSize(sizeBytes)} · $time"
}

/**
 * 时间格式化器，跟着当前 locale 重建。
 *
 * 原来这里是个文件级的 `val TIME_FORMAT = SimpleDateFormat(..., Locale.getDefault())`，两个毛病：
 * ① `Locale.getDefault()` 在类初始化那一刻就被读死，此后整个进程都用那一个值——系统语言改了要等
 * 进程重启才跟上，而本 app 自己的语言开关走的是 `createConfigurationContext`
 * （见 [net.lighttools.lightmux.MainActivity]），压根**不动进程默认 locale**，选了也白选。
 * lint 的 ConstantLocale 报的就是这条。② `SimpleDateFormat` 非线程安全，一个静态实例是等着被踩的雷。
 *
 * 不换 `java.time.DateTimeFormatter`：它要 API 26，本项目 minSdk 24 且没开 core library desugaring，
 * 为一行时间戳把 desugar 的运行时打进包里不值当（APK 体积是这个项目一直在抠的东西）。
 * 每个调用点各 remember 一份实例，只在组合线程上用，非线程安全也就不成问题。
 *
 * 用配置里的 locale 而不是 [Locale.US]：日期是给人读的，跟手机走。代价是数字系统也跟着走
 * （阿拉伯语环境下是印度数字，泰语是佛历年），和旁边那个刻意用 `Locale.US` 的文件大小对不齐——
 * 真要按 `formatBytes` 那条思路统一，把这里的 locale 换成 [Locale.US] 即可，两条路各有各的道理。
 */
@Composable
private fun rememberTimeFormat(): SimpleDateFormat {
    // 读 Configuration 而不是 Locale.getDefault()：app 内的语言选择只体现在这份被包过的配置里。
    // 兜底同理不能用 Locale.getDefault()——那是**进程**默认 locale，本 app 的语言开关压根不动它，
    // 兜过去等于把用户刚选的语言丢掉，切回系统语言排版。Configuration 恒带至少一个 locale，
    // 这条 `?:` 实际走不到，取值和同文件的 formatBytes 对齐（lint 的 NonObservableLocale 报的就是它）。
    val locale = LocalConfiguration.current.locales[0] ?: Locale.US
    return remember(locale) { SimpleDateFormat("yyyy-MM-dd HH:mm", locale) }
}

/** 上传不限类型；下载让系统按扩展名去猜没有意义，统一按二进制流建文件。 */
private const val UPLOAD_MIME = "*/*"
private const val DOWNLOAD_MIME = "application/octet-stream"
