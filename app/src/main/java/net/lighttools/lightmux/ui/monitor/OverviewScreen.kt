package net.lighttools.lightmux.ui.monitor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.lighttools.lightmux.R
import net.lighttools.lightmux.data.Host
import net.lighttools.lightmux.monitor.HostPulse
import net.lighttools.lightmux.monitor.formatRate
import net.lighttools.lightmux.ui.common.BackButton

/**
 * 监控概览：每台主机一行 CPU / 内存 / 网速，点一行进那台的监控页。
 * 生命周期处理同 [MonitorScreen]：可见才轮询，离开放掉连接。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(
    vm: OverviewViewModel,
    onBack: () -> Unit,
    onOpenMonitor: (hostId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
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
            vm.release()
        }
    }

    val hosts by vm.hosts.collectAsState()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.overview)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        if (hosts.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.hosts_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item(key = "header") {
                OverviewHeader()
                HorizontalDivider()
            }
            items(hosts, key = { it.id }) { host ->
                OverviewRow(
                    host = host,
                    loaded = host.id in vm.readings,
                    reading = vm.readings[host.id],
                    onClick = { onOpenMonitor(host.id) },
                )
                HorizontalDivider()
            }
        }
    }
}

/**
 * 表头。各列只在这里写一次名字，行里只剩数字——一台一行要塞下四项读数，每格再带标签就放不下了。
 */
@Composable
private fun OverviewHeader() {
    OverviewLine(
        name = { },
        cpu = { HeaderCell(stringResource(R.string.monitor_cpu)) },
        memory = { HeaderCell(stringResource(R.string.monitor_memory)) },
        disk = { HeaderCell(stringResource(R.string.monitor_disk)) },
        net = { HeaderCell(stringResource(R.string.monitor_network)) },
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

@Composable
private fun HeaderCell(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

@Composable
private fun OverviewRow(host: Host, loaded: Boolean, reading: HostPulse.Reading?, onClick: () -> Unit) {
    val name: @Composable () -> Unit = {
        Text(
            text = host.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    val modifier = Modifier.clickable(onClick = onClick).padding(vertical = 10.dp)
    // 采集中与采不到分开说——一行「—」会被当成读数是零
    if (reading == null) {
        Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { name() }
            Text(
                text = stringResource(if (loaded) R.string.overview_unavailable else R.string.connecting),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    OverviewLine(
        name = name,
        cpu = { UsageCell(reading.cpu) },
        memory = { UsageCell(reading.memory.ratio.toDouble()) },
        disk = { UsageCell(reading.disk?.ratio?.toDouble()) },
        net = {
            // 上下叠放：一个速率最长能到「1023.9 KiB/s」，并排就把主机名挤没了
            Column {
                RateText("↓${reading.rxBytesPerSecond?.let(::formatRate) ?: PLACEHOLDER}")
                RateText("↑${reading.txBytesPerSecond?.let(::formatRate) ?: PLACEHOLDER}")
            }
        },
        modifier = modifier,
    )
}

/** 表头与数据行共用的列宽，保证上下对齐。列宽固定不随数字伸缩：每 5 秒刷一次，跟着内容挪位置整页都在抖。 */
@Composable
private fun OverviewLine(
    name: @Composable () -> Unit,
    cpu: @Composable () -> Unit,
    memory: @Composable () -> Unit,
    disk: @Composable () -> Unit,
    net: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) { name() }
        Box(Modifier.width(USAGE_WIDTH)) { cpu() }
        Box(Modifier.width(USAGE_WIDTH)) { memory() }
        Box(Modifier.width(USAGE_WIDTH)) { disk() }
        Box(Modifier.width(NET_WIDTH)) { net() }
    }
}

/** @param ratio null = 读数暂缺（CPU 第一轮算不出差值，或这台没有真实磁盘） */
@Composable
private fun UsageCell(ratio: Double?) {
    // 只在吃满时变色：常态一片彩色反而让人看不出哪台机器出了事
    val high = (ratio ?: 0.0) >= HIGH_USAGE
    val color = if (high) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = ratio?.let { "${(it * 100).toInt()}%" } ?: PLACEHOLDER,
            style = MaterialTheme.typography.labelMedium,
            color = if (high) color else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        LinearProgressIndicator(
            progress = { (ratio ?: 0.0).toFloat() },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = color,
        )
    }
}

@Composable
private fun RateText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

private val USAGE_WIDTH = 40.dp
private val NET_WIDTH = 76.dp
private const val PLACEHOLDER = "—"
private const val HIGH_USAGE = 0.9
