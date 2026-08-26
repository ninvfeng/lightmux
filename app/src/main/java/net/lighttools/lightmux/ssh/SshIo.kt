package net.lighttools.lightmux.ssh

import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

/**
 * 收尾类 socket 操作的执行处：**保证它们永远不在主线程上跑**。
 *
 * 「关连接」「改远端窗口尺寸」这两件事的调用方天生在主线程上——主页收起主机卡片、退出文件页、
 * `TerminalView.onSizeChanged`、终端内核在主线程 Handler 里做收尾。而它们都是真实的 socket 写
 * （SSH_MSG_DISCONNECT / SSH_MSG_CHANNEL_CLOSE / window-change），在主线程上做会被 StrictMode
 * 抛 `NetworkOnMainThreadException`，后果有两种，都很难查：
 *
 * 1. 它是 `RuntimeException`，而 sshj 的 `IOUtils.closeQuietly` 只接 `IOException`，接不住，
 *    异常一路冒到协程/Handler 上，**整个 app 直接退出**。
 * 2. sshj 是「先 `encoder.encode()` 拿包序号，再写 socket」，写失败时序号已经加过了。
 *    序号一错位，下一个真包（比如用户敲的第一个键）就过不了服务端的 MAC 校验，
 *    对端直接断开——现象是「连上之后一输入就提示连接已断开」，看着完全不像尺寸同步的锅。
 *
 * 用 cached 池而不是单线程：移动网络下关一条半死的连接可能卡在 TCP 超时上，
 * 单线程会被它一个人堵住，后面的 resize 全部迟到。
 */
internal object SshIo {

    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "SshIo").apply { isDaemon = true }
    }

    /**
     * 跑一段收尾 IO：已经在后台线程上就地做完，在主线程上则甩给后台。
     *
     * 异常一律只记日志——调用点本来就是 `closeQuietly` 语义，而在线程池里漏出去的异常
     * 会被 Android 的默认 handler 当成崩溃处理。
     */
    fun quietly(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) executor.execute { swallow(block) } else swallow(block)
    }

    private fun swallow(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            Log.w(TAG, "background ssh io failed", e)
        }
    }

    private const val TAG = "SshIo"
}
