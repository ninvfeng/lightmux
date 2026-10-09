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

@Composable
private fun OverviewRow(host: Host, loaded: Boolean, reading: HostPulse.Reading?, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = host.name,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            if (reading != null) {
                Text(
                    text = "↓${reading.rxBytesPerSecond?.let(::formatRate) ?: PLACEHOLDER}  " +
                        "↑${reading.txBytesPerSecond?.let(::formatRate) ?: PLACEHOLDER}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // 采集中与采不到分开说——一行「—」会被当成读数是零
        if (reading == null) {
            Text(
                text = stringResource(if (loaded) R.string.overview_unavailable else R.string.connecting),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            UsageBar(stringResource(R.string.monitor_cpu), reading.cpu, Modifier.weight(1f))
            UsageBar(stringResource(R.string.monitor_memory), reading.memory.ratio.toDouble(), Modifier.weight(1f))
        }
    }
}

/** @param ratio null = 第一轮还算不出差值 */
@Composable
private fun UsageBar(label: String, ratio: Double?, modifier: Modifier) {
    // 只在吃满时变色：常态一片彩色反而让人看不出哪台机器出了事
    val high = (ratio ?: 0.0) >= HIGH_USAGE
    val color = if (high) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = "$label ${ratio?.let { "${(it * 100).toInt()}%" } ?: PLACEHOLDER}",
            style = MaterialTheme.typography.labelSmall,
            color = if (high) color else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { (ratio ?: 0.0).toFloat() },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = color,
        )
    }
}

private const val PLACEHOLDER = "—"
private const val HIGH_USAGE = 0.9
