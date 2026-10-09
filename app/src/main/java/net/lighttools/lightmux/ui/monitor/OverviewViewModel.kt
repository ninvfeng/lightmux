package net.lighttools.lightmux.ui.monitor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.lighttools.lightmux.data.Host
import net.lighttools.lightmux.data.HostStore
import net.lighttools.lightmux.monitor.HostPulse
import net.lighttools.lightmux.monitor.MonitorRepository

/**
 * 监控概览：所有主机的 CPU / 内存 / 网速一页看完。
 *
 * 进这一页就是用户点头「连所有主机」，所以这里是 PRD §4.3「绝不自动探测」之外的地方——
 * 但也只在页面可见时轮询，离开即停。
 */
class OverviewViewModel(
    private val monitor: MonitorRepository,
    hostStore: HostStore,
) : ViewModel() {

    val hosts: StateFlow<List<Host>> =
        hostStore.hosts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 主机 id → 读数。**键不存在 = 还没采到第一轮；值为 null = 采不到**
     * （连不上、没有 `/proc`、MFA 主机不许自动拨号），两者显示不同。
     */
    var readings by mutableStateOf(emptyMap<String, HostPulse.Reading?>())
        private set

    /** 页面不可见时必须为 null：后台一直发 SSH 命令既费电又容易触发 fail2ban。 */
    private var job: Job? = null

    /** `ON_START` 与进入页面时各叫一次，幂等。 */
    fun start() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            hosts.collectLatest { targets ->
                // 每台一条协程：一台连不上（拨号超时十几秒）不能拖住别的主机刷新
                coroutineScope { targets.forEach { host -> launch { loop(host) } } }
            }
        }
    }

    /** 读数一并清空：下次进来看到几分钟前的 CPU 会被当成现在的。 */
    fun stop() {
        job?.cancel()
        job = null
        readings = emptyMap()
    }

    /** 离开页面时放掉为概览拨的连接；同监控页，ViewModel 不随页面清掉，只能由页面来叫。 */
    fun release() {
        val ids = hosts.value.map { it.id }
        viewModelScope.launch { ids.forEach { runCatching { monitor.release(it) } } }
    }

    private suspend fun loop(host: Host) {
        var previous: HostPulse.Sample? = null
        while (true) {
            val sample = try {
                monitor.probePulse(host)
            } catch (e: CancellationException) {
                throw e // 离开页面时 cancel 正好卡在 exec 上，不能当成「采不到」写进去
            } catch (e: Exception) {
                null
            }
            readings = readings + (host.id to sample?.let { HostPulse.reading(previous, it) })
            delay(
                when {
                    // 连不上的机器别每 5 秒拨一次号：慢且容易触发 fail2ban
                    sample == null -> RETRY_MS
                    // 第一轮只有内存，CPU 与网速要靠差值——第二轮提前，别让这两格空等一个周期
                    previous == null -> FIRST_GAP_MS
                    else -> INTERVAL_MS
                }
            )
            previous = sample
        }
    }

    private companion object {
        /** 同监控页的 5 秒 */
        const val INTERVAL_MS = 5_000L
        const val FIRST_GAP_MS = 1_000L
        const val RETRY_MS = 30_000L
    }
}
