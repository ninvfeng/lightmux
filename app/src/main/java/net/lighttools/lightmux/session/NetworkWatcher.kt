package net.lighttools.lightmux.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log

/**
 * 默认网络恢复的监听器。
 *
 * 光靠退避重试也能连上，但用户的体感差很多：从电梯里出来、飞行模式关掉的那一刻，
 * 系统已经明确告诉我们「现在能连了」，而退避可能刚好停在 15 秒那一档。
 *
 * 注册与注销**必须成对**（[start] / [stop] 都是幂等的）：`NetworkCallback` 由系统持有强引用，
 * 漏注销就是一条跟着进程走的泄漏。所以它只在有活会话时挂着，由调用方按会话数开关。
 */
class NetworkWatcher(context: Context, private val onAvailable: () -> Unit) {

    private val manager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    fun start() {
        if (callback != null) return
        val cm = manager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onAvailable()
            }
        }
        // 只关心「默认网络」：Wi-Fi 与蜂窝切换时它会各来一次 onAvailable，正是我们要抢跑的时机。
        val registered = runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onFailure { Log.w(TAG, "failed to register network callback", it) }
            .isSuccess
        if (registered) callback = cb
    }

    @Synchronized
    fun stop() {
        val cb = callback ?: return
        callback = null
        // 系统偶尔会对「没注册过」的 callback 抛 IllegalArgumentException，注销失败不该带崩 app。
        runCatching { manager?.unregisterNetworkCallback(cb) }
            .onFailure { Log.w(TAG, "failed to unregister network callback", it) }
    }

    private companion object {
        const val TAG = "NetworkWatcher"
    }
}
