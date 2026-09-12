package net.lighttools.lightmux.ui.monitor

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.lighttools.lightmux.R
import net.lighttools.lightmux.monitor.ContainerInfo
import net.lighttools.lightmux.monitor.CpuUsage
import net.lighttools.lightmux.monitor.DiskUsage
import net.lighttools.lightmux.monitor.GpuInfo
import net.lighttools.lightmux.monitor.HostSnapshot
import net.lighttools.lightmux.monitor.LoadAverage
import net.lighttools.lightmux.monitor.MemoryUsage
import net.lighttools.lightmux.monitor.NetInterface
import net.lighttools.lightmux.monitor.ProcessInfo
import net.lighttools.lightmux.monitor.SystemInfo
import net.lighttools.lightmux.monitor.Uptime
import net.lighttools.lightmux.monitor.UsageOrder
import net.lighttools.lightmux.monitor.UsageSort
import net.lighttools.lightmux.monitor.formatBytes
import net.lighttools.lightmux.monitor.formatLoad
import net.lighttools.lightmux.monitor.formatPercent
import net.lighttools.lightmux.monitor.formatRate
import net.lighttools.lightmux.ui.common.BackButton
import net.lighttools.lightmux.ui.common.ErrorBanner
import net.lighttools.lightmux.ui.common.copyToClipboard

/**
 * 监控页：一台主机的实时指标（PRD §4.4）。
 *
 * 页面可见时每 [MonitorViewModel] 里那个间隔采一次，**离开或进后台立刻停**——
 * 手机上后台狂发 SSH 命令既费电又容易触发对端 fail2ban。
 *
 * 返回走中央栈（`nav.pop()`），页面内部不写 BackHandler（CLAUDE.md 架构要点 ④）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen(
    vm: MonitorViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val host = vm.host
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> vm.start()
                Lifecycle.Event.ON_STOP -> vm.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // 进入页面时生命周期多半已经是 STARTED，等不到 ON_START 事件了
        vm.start()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.stop()
            // 复用终端会话的连接不在池子里，这里关不到，前台终端不受影响
            vm.release()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = host?.name ?: stringResource(R.string.monitor),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        host?.let {
                            Text(
                                text = it.endpoint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = vm::refreshNow) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.refresh))
                    }
                },
            )
        },
    ) { padding ->
        MonitorBody(vm = vm, modifier = Modifier.fillMaxSize().padding(padding))
    }
}

/**
 * 正文单独一层，**读数只在这里读**。
 *
 * 这一页的数据是 5 秒一刷的，在 [MonitorScreen] 顶层读 `vm.state` 等于每 5 秒把整个页面
 * （标题栏、生命周期观察者、Scaffold 那一整套）重组一遍，而真正变的只有下面这些行。
 * 展开状态、公网 IP 同理：点一下展开核心列表不该惊动标题栏。
 */
@Composable
private fun MonitorBody(vm: MonitorViewModel, modifier: Modifier = Modifier) {
    val state = vm.state

    Column(modifier) {
        // 主机没了就只说这一句，不给重试：这台机器不会再回来，按多少次都一样
        if (state.hostMissing) ErrorBanner(message = stringResource(R.string.error_host_missing))
        state.error?.let { error ->
            ErrorBanner(
                message = stringResource(R.string.monitor_failed, error),
                actionLabel = stringResource(R.string.retry),
                onAction = vm::refreshNow,
            )
        }

        when {
            state.unsupported -> Centered {
                Text(
                    text = stringResource(R.string.monitor_unsupported),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 首次进入摆骨架屏，等快采/全量陆续填上；已经报错过（重试也未必成功）
            // 就只留上面那条横幅，永远填不上的灰条比一句错误更像坏了
            state.snapshot == null -> if (state.error == null && !state.hostMissing) MetricsSkeleton()

            else -> Metrics(
                snapshot = state.snapshot,
                // CPU 段等的是差值，必须靠全量命令；还没报错就说明它正在路上
                cpuPending = state.error == null,
                publicIp = vm.publicIp,
                coresExpanded = vm.coresExpanded,
                onToggleCores = vm::toggleCores,
                virtualNetExpanded = vm.virtualNetExpanded,
                onToggleVirtualNet = vm::toggleVirtualNet,
                containerSort = vm.containerSort,
                onToggleContainerSort = vm::toggleContainerSort,
                processSort = vm.processSort,
                onToggleProcessSort = vm::toggleProcessSort,
            )
        }
    }
}

/**
 * 首屏骨架：只摆「几乎所有机器都有」的三段（系统 / CPU / 内存）。磁盘、网络、容器、GPU
 * 能不能采到因机器而异，猜画出来再消失反而更像坏了——等快采或全量数据回来（或者确认
 * 采不到）再由 [Metrics] 决定要不要渲染那些段。
 */
@Composable
private fun MetricsSkeleton() {
    val alpha = rememberSkeletonAlpha()
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Section(stringResource(R.string.monitor_system)) {
            repeat(SKELETON_SYSTEM_ROWS) { SkeletonInfoRow(alpha) }
        }
        Section(stringResource(R.string.monitor_cpu)) { SkeletonMeter(alpha) }
        Section(stringResource(R.string.monitor_memory)) { SkeletonMeter(alpha) }
    }
}

/**
 * 骨架屏的脉动灰条驱动值。
 *
 * `alpha()` 只在 [SkeletonBar] 的 [Canvas] draw lambda 里读——理由同 `Spinner.kt` 顶部那条注释：
 * 提到这行以上（比如在 Composable 主体里 `val a = alpha()`）就是每帧重组一次，而不是每帧重绘。
 */
@Composable
private fun rememberSkeletonAlpha(): () -> Float {
    val transition = rememberInfiniteTransition()
    val alpha = transition.animateFloat(
        initialValue = SKELETON_ALPHA_MIN,
        targetValue = SKELETON_ALPHA_MAX,
        animationSpec = infiniteRepeatable(
            animation = tween(SKELETON_PULSE_MILLIS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    )
    return { alpha.value }
}

/** 一条圆角灰条，宽度按 [widthFraction] 收窄——模拟不同长度的文字或进度条占位。 */
@Composable
private fun SkeletonBar(widthFraction: Float = 1f, height: Dp = 14.dp, alpha: () -> Float) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = Modifier.fillMaxWidth(widthFraction).height(height)) {
        drawRoundRect(color = color.copy(alpha = alpha()), cornerRadius = CornerRadius(size.height / 2f))
    }
}

/** 骨架屏版 [InfoRow]：标签、值各一条占位。 */
@Composable
private fun SkeletonInfoRow(alpha: () -> Float) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkeletonBar(widthFraction = 0.28f, alpha = alpha)
        SkeletonBar(widthFraction = 0.4f, alpha = alpha)
    }
}

/** 骨架屏版 [Meter]：标题 + 读数两条占位，下面接一条更粗的进度条占位。 */
@Composable
private fun SkeletonMeter(alpha: () -> Float) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SkeletonBar(widthFraction = 0.32f, alpha = alpha)
            SkeletonBar(widthFraction = 0.16f, height = 12.dp, alpha = alpha)
        }
        SkeletonBar(height = 6.dp, alpha = alpha)
    }
}

/**
 * 全部指标。
 *
 * 用 `Column + verticalScroll` 而不是 `LazyColumn`：卡片一共就固定八段（系统 / CPU / 内存 /
 * swap / 磁盘 / 网络 / 容器 / 进程），懒加载省下的组合量还不够抵掉把 [Section] 拆成 item 的代价
 * ——段内的 `spacedBy` 与段尾分隔线都挂在 Section 这层容器上。段内行数则由采集侧封了顶
 * （进程 7 行、容器 50 行），唯一会随机器规模爆的虚拟网卡默认收起（见 [NetworkRows]）。
 */
@Composable
private fun Metrics(
    snapshot: HostSnapshot,
    cpuPending: Boolean,
    publicIp: String?,
    coresExpanded: Boolean,
    onToggleCores: () -> Unit,
    virtualNetExpanded: Boolean,
    onToggleVirtualNet: () -> Unit,
    containerSort: UsageSort,
    onToggleContainerSort: () -> Unit,
    processSort: UsageSort,
    onToggleProcessSort: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Section(stringResource(R.string.monitor_system)) {
            SystemRows(snapshot.system, snapshot.uptimeSeconds)
        }
        Section(stringResource(R.string.monitor_cpu)) {
            CpuRows(snapshot.cpu, cpuPending, snapshot.load.formatted(), coresExpanded, onToggleCores)
        }
        // 没有独立显卡、或驱动工具不在 PATH 里，整段不显示——同容器、进程
        if (snapshot.gpus.isNotEmpty()) {
            Section(stringResource(R.string.monitor_gpu)) {
                snapshot.gpus.forEachIndexed { index, gpu ->
                    // 单卡机器上标个 #0 只是噪音，多卡时不标又分不出哪条是哪张
                    GpuRows(gpu = gpu, index = index, numbered = snapshot.gpus.size > 1)
                }
            }
        }
        Section(stringResource(R.string.monitor_memory)) { MemoryRow(snapshot.memory) }
        snapshot.swap?.let { swap ->
            Section(stringResource(R.string.monitor_swap)) { MemoryRow(swap) }
        }
        if (snapshot.disks.isNotEmpty()) {
            Section(stringResource(R.string.monitor_disk)) { snapshot.disks.forEach { DiskRow(it) } }
        }
        // 网卡一张都没有时 IP 还是该显示——纯内网机器的网卡可能整个采样窗口零流量被滤掉了
        if (snapshot.interfaces.isNotEmpty() || snapshot.addresses.isNotEmpty() || publicIp != null) {
            Section(stringResource(R.string.monitor_network)) {
                NetworkRows(snapshot, publicIp, virtualNetExpanded, onToggleVirtualNet)
            }
        }
        // 没装 docker、或当前用户没权限访问 socket，都是这里为空——同 ps，整段不显示
        if (snapshot.containers.isNotEmpty()) {
            // 排序结果 remember 住：这一页 5 秒刷一次，读数没变时不该跟着重排一遍
            val containers = remember(snapshot.containers, containerSort) {
                UsageOrder.containers(snapshot.containers, containerSort)
            }
            Section(
                title = stringResource(R.string.monitor_containers),
                trailing = { SortToggle(containerSort, onToggleContainerSort) },
            ) { containers.forEach { ContainerRow(it) } }
        }
        // busybox 的 ps 采不到，那就整段不显示——空标题下面一片空白更像是坏了
        if (snapshot.processes.isNotEmpty()) {
            val processes = remember(snapshot.processes, processSort) {
                UsageOrder.processes(snapshot.processes, processSort)
            }
            Section(
                title = stringResource(R.string.monitor_processes),
                trailing = { SortToggle(processSort, onToggleProcessSort) },
            ) { processes.forEach { ProcessRow(it) } }
        }
    }
}

@Composable
private fun SystemRows(system: SystemInfo, uptimeSeconds: Long) {
    val unavailable = stringResource(R.string.monitor_unavailable)
    // 采不到的项如实标「不可用」，不拿空字符串糊过去（PRD §4.4）
    InfoRow(stringResource(R.string.monitor_distro), system.distro ?: unavailable)
    InfoRow(stringResource(R.string.monitor_kernel), system.kernel ?: unavailable)
    system.hostname?.let { InfoRow(stringResource(R.string.monitor_hostname), it) }
    InfoRow(stringResource(R.string.monitor_uptime), uptimeText(uptimeSeconds))
}

/**
 * 每核占用默认收起：几十核的机器一进页面就是一屏进度条，把内存、磁盘全推到屏幕外。
 * 展开的开关挂在总览那条 Meter 上，不另占一行。
 *
 * @param cpu null 有两种含义，靠 [cpuPending] 分辨：全量命令还没回来（画骨架条），
 *   或者已经报错（退化成「不可用」，同 [GpuRows] 对读不出来的字段的处理）。
 *   负载那一行来自首屏快采，不受这两种状态影响，全程照常显示。
 */
@Composable
private fun CpuRows(cpu: CpuUsage?, cpuPending: Boolean, load: String, coresExpanded: Boolean, onToggleCores: () -> Unit) {
    when {
        cpu != null -> {
            Meter(
                label = if (cpu.coreCount > 0) {
                    pluralStringResource(R.plurals.monitor_cores, cpu.coreCount, cpu.coreCount)
                } else {
                    stringResource(R.string.monitor_cpu)
                },
                value = stringResource(R.string.monitor_percent, formatPercent(cpu.total)),
                ratio = cpu.total.toFloat(),
                // 单核机器展开了也只有一条和总览一模一样的进度条，没什么可看的
                expanded = if (cpu.coreCount > 1) coresExpanded else null,
                onToggle = onToggleCores,
            )
            if (coresExpanded) {
                cpu.cores.forEachIndexed { index, usage ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.monitor_core, index),
                            modifier = Modifier.width(56.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LinearProgressIndicator(
                            progress = { usage.toFloat() },
                            modifier = Modifier.weight(1f).height(4.dp),
                        )
                        Text(
                            text = stringResource(R.string.monitor_percent, formatPercent(usage)),
                            modifier = Modifier.width(52.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        cpuPending -> SkeletonMeter(rememberSkeletonAlpha())

        else -> InfoRow(stringResource(R.string.monitor_cpu), stringResource(R.string.monitor_unavailable))
    }
    Text(
        text = load,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 一张卡两条进度条：算力占用在上、显存在下，温度跟在后面一行小字（同 CPU 段的负载行）。
 *
 * **采不到的那条整条不画**：直通给虚拟机的卡报不出 utilization，画一条 0% 的进度条
 * 会被当成「这张卡闲着」，而它可能正被另一台虚拟机跑满（PRD §4.4）。
 */
@Composable
private fun GpuRows(gpu: GpuInfo, index: Int, numbered: Boolean) {
    val label = if (numbered) "#$index ${gpu.name}" else gpu.name
    val utilization = gpu.utilization
    if (utilization != null) {
        Meter(
            label = label,
            value = stringResource(R.string.monitor_percent, formatPercent(utilization)),
            ratio = utilization.toFloat(),
        )
    } else {
        // 名字这一行不能跟着消失：整张卡不见了，用户会以为机器上没这块卡
        InfoRow(label, stringResource(R.string.monitor_unavailable))
    }
    val used = gpu.memoryUsedBytes
    val total = gpu.memoryTotalBytes
    val ratio = gpu.memoryRatio
    if (used != null && total != null && ratio != null) {
        Meter(
            label = stringResource(R.string.monitor_gpu_vram, formatBytes(used), formatBytes(total)),
            value = stringResource(R.string.monitor_percent, formatPercent(ratio.toDouble())),
            ratio = ratio,
        )
    }
    gpu.temperatureCelsius?.let {
        Text(
            text = stringResource(R.string.monitor_gpu_temp, it),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MemoryRow(memory: MemoryUsage) {
    Meter(
        label = stringResource(
            R.string.monitor_usage,
            formatBytes(memory.usedBytes),
            formatBytes(memory.totalBytes),
        ),
        value = stringResource(R.string.monitor_percent, formatPercent(memory.ratio.toDouble())),
        ratio = memory.ratio,
    )
}

@Composable
private fun DiskRow(disk: DiskUsage) {
    Meter(
        label = disk.mountPoint,
        value = stringResource(
            R.string.monitor_usage,
            formatBytes(disk.usedBytes),
            formatBytes(disk.totalBytes),
        ),
        ratio = disk.ratio,
        // 快满的盘要一眼看见：磁盘写满是这几项指标里唯一会立刻让服务挂掉的
        alert = disk.ratio >= DISK_ALERT_RATIO,
    )
}

/**
 * 网络段：IP 在前，物理网卡速率在中，虚拟网卡收在最后。
 *
 * 虚拟网卡默认收起的理由见 [NetInterface.virtual]——一台跑容器的机器上光 veth 就能刷满一屏，
 * 而用户来这一段大多是想知道「这机器的地址是多少、对外在传多少」。
 */
@Composable
private fun NetworkRows(
    snapshot: HostSnapshot,
    publicIp: String?,
    virtualExpanded: Boolean,
    onToggleVirtual: () -> Unit,
) {
    val unavailable = stringResource(R.string.monitor_unavailable)
    // 一个地址一行而不是拼成一串：IP 是拿来往别处粘的，复制按钮得对应到具体某一个地址，
    // 「10.0.0.2  172.17.0.1」整串粘出去没有任何用处
    snapshot.addresses.forEach { CopyRow(stringResource(R.string.monitor_local_ip), it) }
    // 公网 IP 要单发一条 exec，首屏那几秒还没回来；采不到（机器出不去网）也照样占这一行，
    // 整行消失会让用户以为这功能没做
    if (publicIp != null) CopyRow(stringResource(R.string.monitor_public_ip), publicIp)
    else InfoRow(stringResource(R.string.monitor_public_ip), unavailable)

    val (virtual, physical) = snapshot.interfaces.partition { it.virtual }
    physical.forEach { NetRow(it) }
    if (virtual.isNotEmpty()) {
        ExpandRow(
            label = pluralStringResource(R.plurals.monitor_virtual_nics, virtual.size, virtual.size),
            expanded = virtualExpanded,
            onToggle = onToggleVirtual,
        )
        if (virtualExpanded) virtual.forEach { NetRow(it) }
    }
}

/**
 * 不复用 [InfoRow]：那边 label 不带 weight，网卡名后面挂上地址会把右边的速率挤出屏幕。
 * 地址单独占第二行，也只有真采到时才占。
 */
@Composable
private fun NetRow(iface: NetInterface) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = iface.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            // 多网卡的机器上，「哪张卡是哪个地址」只有挨着放才看得出来
            iface.address?.let {
                Text(
                    text = it,
                    maxLines = 1,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = stringResource(
                R.string.monitor_net_rates,
                formatRate(iface.rxBytesPerSecond),
                formatRate(iface.txBytesPerSecond),
            ),
            maxLines = 1,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ProcessRow(process: ProcessInfo) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = process.command,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(
                R.string.monitor_process_detail,
                process.pid,
                formatLoad(process.cpuPercent),
                formatLoad(process.memPercent),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ContainerRow(container: ContainerInfo) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = container.name,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = container.status,
                maxLines = 1,
                style = MaterialTheme.typography.labelSmall,
                // 停掉的容器不报红：一台机器上躺着几个跑完的一次性任务是常态，
                // 见红就报会让「真的挂了」那一条失去分量
                color = if (container.running) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = container.image,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 停掉的容器不在 docker stats 输出里，这里整块不画——留一行「CPU 0%」会让人以为它还在跑
            if (container.hasStats) {
                Text(
                    text = stringResource(
                        R.string.monitor_container_stats,
                        container.cpuPercent?.let(::formatLoad) ?: "-",
                        container.memoryText(),
                    ),
                    maxLines = 1,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** `25.1MiB (0.2%)`；占用率读不到就只剩用量，不补个假的百分比。 */
@Composable
private fun ContainerInfo.memoryText(): String {
    val used = memoryUsed ?: return "-"
    val percent = memoryPercent ?: return used
    return stringResource(R.string.monitor_container_mem, used, formatLoad(percent))
}

/** 「N 个虚拟网卡 ›」这类整行开关。点整行，不是只点箭头——一行 20dp 的箭头在手机上太难戳。 */
@Composable
private fun ExpandRow(label: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ExpandIcon(expanded)
    }
}

@Composable
private fun ExpandIcon(expanded: Boolean) {
    Icon(
        imageVector = if (expanded) Icons.Default.KeyboardArrowDown
        else Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = stringResource(
            if (expanded) R.string.monitor_collapse else R.string.monitor_expand
        ),
        modifier = Modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 一条带进度条的指标。标题在左、读数在右，条在下面占满宽度。
 *
 * @param expanded 非 null 时整条可点，行尾多一个展开箭头；null = 这条没有可展开的东西
 */
@Composable
private fun Meter(
    label: String,
    value: String,
    ratio: Float,
    alert: Boolean = false,
    expanded: Boolean? = null,
    onToggle: () -> Unit = {},
) {
    val color = if (alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier.fillMaxWidth()
            .then(if (expanded != null) Modifier.clickable(onClick = onToggle) else Modifier),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = if (alert) color else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            expanded?.let { ExpandIcon(it) }
        }
        LinearProgressIndicator(
            progress = { ratio },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = color,
        )
    }
}

/**
 * 带复制按钮的 [InfoRow]，给 IP 这类要往外粘的值用。
 *
 * 不做成「整行可点即复制」：这一段里大多数 InfoRow 复制了也没意义，
 * 整行热区反而让「哪几行能点」变得没法预期，不如一个明摆着的按钮。
 */
@Composable
private fun CopyRow(label: String, value: String) {
    val context = LocalContext.current
    val copied = stringResource(R.string.monitor_ip_copied)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
        IconButton(
            onClick = { copyToClipboard(context, value, copied) },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.monitor_copy_ip),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** @param trailing 标题行右端的东西（排序开关）。没有就只画标题，行高不变。 */
@Composable
private fun Section(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            trailing?.invoke()
        }
        content()
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

/**
 * 排序开关：只有两个键，做成**点一下就换**的一个标签，比塞两个分段按钮省地方也少一次决策。
 * 标签上写的是当前生效的键（`CPU ↓`），不是「点了会变成什么」——后者每次都得在脑子里翻译一遍。
 */
@Composable
private fun SortToggle(sort: UsageSort, onToggle: () -> Unit) {
    Text(
        text = stringResource(
            if (sort == UsageSort.CPU) R.string.monitor_sort_cpu else R.string.monitor_sort_memory
        ),
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = stringResource(R.string.monitor_sort_toggle), onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        maxLines = 1,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun LoadAverage.formatted(): String = stringResource(
    R.string.monitor_load,
    formatLoad(one),
    formatLoad(five),
    formatLoad(fifteen),
)

/** 秒 → 「3 天 4 小时」。量级由纯逻辑给，词由 strings.xml 给。 */
@Composable
private fun uptimeText(seconds: Long): String {
    val parts = Uptime.of(seconds)
    return when {
        parts.days > 0 -> stringResource(R.string.monitor_uptime_days, parts.days, parts.hours)
        parts.hours > 0 -> stringResource(R.string.monitor_uptime_hours, parts.hours, parts.minutes)
        else -> stringResource(R.string.monitor_uptime_minutes, parts.minutes)
    }
}

/** 90% 起报警。到这个水位时留给用户处理的时间已经不多了。 */
private const val DISK_ALERT_RATIO = 0.9f

/** 骨架屏系统段的占位行数：发行版 / 内核 / 主机名 / 运行时长，与 [SystemRows] 的常见行数对齐。 */
private const val SKELETON_SYSTEM_ROWS = 4

/** 骨架条脉动的最暗/最亮 alpha，比转圈指示器的 1100ms 一圈略快，读起来更像「马上就好」。 */
private const val SKELETON_ALPHA_MIN = 0.35f
private const val SKELETON_ALPHA_MAX = 0.75f
private const val SKELETON_PULSE_MILLIS = 900
