package net.lighttools.lightmux.ui.monitor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(hosts, key = { it.id }) { host ->
                OverviewCard(
                    host = host,
                    loaded = host.id in vm.readings,
                    reading = vm.readings[host.id],
                    onClick = { onOpenMonitor(host.id) },
                )
            }
        }
    }
}

/**
 * 一台主机一张卡：左边名字 + 网速，右边 CPU / 内存 / 磁盘三个环。
 *
 * 卡片底色用 `surfaceContainer`、不加阴影不描边，同主机编辑页——层次靠底色深浅，不靠线。
 */
@Composable
private fun OverviewCard(host: Host, loaded: Boolean, reading: HostPulse.Reading?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(
                        when {
                            reading != null -> MaterialTheme.colorScheme.primary
                            loaded -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.outlineVariant
                        }
                    )
                    Text(
                        text = host.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (reading != null) {
                    Rate("↓", reading.rxBytesPerSecond)
                    Rate("↑", reading.txBytesPerSecond)
                } else {
                    // 采集中与采不到分开说——一行「—」会被当成读数是零
                    Text(
                        text = stringResource(if (loaded) R.string.overview_unavailable else R.string.connecting),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (loaded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Gauge(stringResource(R.string.monitor_cpu), reading?.cpu)
            Gauge(stringResource(R.string.monitor_memory), reading?.memory?.ratio?.toDouble())
            Gauge(stringResource(R.string.monitor_disk), reading?.disk?.ratio?.toDouble())
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(Modifier.size(8.dp).background(color, CircleShape))
}

@Composable
private fun Rate(arrow: String, bytesPerSecond: Double?) {
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)) { append(arrow) }
            append(" ")
            append(bytesPerSecond?.let(::formatRate) ?: PLACEHOLDER)
        },
        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = TABULAR),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

/**
 * 270° 的开口环，读数在环心。
 *
 * 进度只在 Canvas 的 draw lambda 里读：动画每帧只重绘，不重组（同 `Spinner.kt`）。
 *
 * @param ratio null = 读数暂缺（还在连、CPU 第一轮算不出差值、或这台没有真实磁盘），只画空轨道
 */
@Composable
private fun Gauge(label: String, ratio: Double?) {
    val high = (ratio ?: 0.0) >= HIGH_USAGE
    // 只在吃满时变色：常态一片彩色反而让人看不出哪台机器出了事
    val color = if (high) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val progress = animateFloatAsState((ratio ?: 0.0).toFloat().coerceIn(0f, 1f), tween(600), label = "gauge")
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(Modifier.size(GAUGE_SIZE), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                val topLeft = Offset(stroke.width / 2, stroke.width / 2)
                val arc = Size(size.width - stroke.width, size.height - stroke.width)
                drawArc(track, GAUGE_START, GAUGE_SWEEP, false, topLeft, arc, style = stroke)
                val sweep = GAUGE_SWEEP * progress.value
                if (sweep > 0f) drawArc(color, GAUGE_START, sweep, false, topLeft, arc, style = stroke)
            }
            Text(
                text = if (ratio == null) AnnotatedString(PLACEHOLDER) else buildAnnotatedString {
                    append("${(ratio * 100).toInt()}")
                    withStyle(SpanStyle(fontSize = 8.sp)) { append("%") }
                },
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = TABULAR),
                fontWeight = FontWeight.SemiBold,
                color = if (high) color else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

private val GAUGE_SIZE = 42.dp
/** 从左下 135° 起顺时针扫 270°，开口朝下 */
private const val GAUGE_START = 135f
private const val GAUGE_SWEEP = 270f
/** 等宽数字：每 5 秒刷一次，比例字体下「1」和「8」宽度不同，数字会左右晃 */
private const val TABULAR = "tnum"
private const val PLACEHOLDER = "—"
private const val HIGH_USAGE = 0.9
