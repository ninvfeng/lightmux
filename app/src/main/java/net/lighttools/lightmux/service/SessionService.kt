package net.lighttools.lightmux.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import net.lighttools.lightmux.LightmuxApp
import net.lighttools.lightmux.MainActivity
import net.lighttools.lightmux.R

/**
 * 会话保活的前台服务。
 *
 * 真正的会话状态在 [net.lighttools.lightmux.session.SessionManager]（Application 作用域），
 * 本服务只做一件事：**让进程别被回收**。手机切后台几分钟后进程被杀，SSH 连接与滚屏历史一起没，
 * 「多会话常驻」这个卖点也就没了。
 *
 * 启停由 [MainActivity] 在前台时驱动（Android 12+ 禁止后台启动前台服务），
 * 停止则由本服务自己观察——最后一个会话可能是在通知栏里被关掉的，那时 Activity 未必还在。
 */
class SessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as LightmuxApp
        createChannel()
        // 必须在 onCreate 里立刻进前台：startForegroundService 之后超过 5 秒不 startForeground
        // 会被系统直接判 ANR 杀掉，等第一个 Flow 值到达再调是来不及的。
        enterForeground(app.sessionManager.sessions.value.size, 0, 0)

        combine(
            app.sessionManager.sessions,
            app.transferQueue.transfers,
            app.forwardManager.states,
        ) { sessions, transfers, forwards ->
            Triple(sessions.size, transfers.count { it.active }, forwards.count { it.value.isLive })
        }
            // 少了这行，传一个大文件就是每秒 5 次 startForeground：TransferQueue 每 200ms 推一次
            // 进度，combine 就跟着发一个新值，而这里只关心三个计数——进度动了它们多半没变。
            // 重建通知是一次 NotificationManager 的 binder 调用，5 次/秒正好卡在系统的通知限流
            // 阈值上，被限流之后连真正该更新的那次也一起丢了。
            .distinctUntilChanged()
            .onEach { (sessions, transfers, forwards) ->
                // 端口转发也要算进来：转发靠的是自己那条 SSH 连接，进程被回收它一样断，
                // 而用户此时正盯着浏览器里那个 127.0.0.1 页面，断了只会看到「无法连接」。
                if (sessions == 0 && transfers == 0 && forwards == 0) stopSelf()
                else enterForeground(sessions, transfers, forwards)
            }
            .launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT_ALL) {
            // 会话和转发一起断（同 HomeViewModel.disconnectAll）：这条通知上写着的就是
            // 「N 个会话 · M 条转发」，只断前一半会留下一条自称已断开、却还挂着通知的服务。
            // 传输队列不动——那是一件有终点的事，正传到一半的大文件不该被顺手掐了。
            val app = application as LightmuxApp
            app.sessionManager.closeAll()
            app.forwardManager.stopAll()
        }
        // 不用 START_STICKY：进程真被杀掉后重建的是一个没有任何连接的空服务，
        // 只会留下一条骗人的通知——会话本来就恢复不了。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun enterForeground(sessions: Int, transfers: Int, forwards: Int) {
        val notification = buildNotification(sessions, transfers, forwards)
        // Android 14 起前台服务必须声明类型。用 specialUse 而不是 dataSync：
        // 后者有 6 小时上限，语义也是「同步一批数据然后结束」，而我们要维持的是一条交互式远程 shell。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(sessions: Int, transfers: Int, forwards: Int): Notification {
        val content = SessionNotificationText.content(
            sessions = sessions,
            transfers = transfers,
            forwards = forwards,
            sessionText = { resources.getQuantityString(R.plurals.notification_sessions, it, it) },
            transferText = { resources.getQuantityString(R.plurals.notification_transfers, it, it) },
            forwardText = { resources.getQuantityString(R.plurals.notification_forwards, it, it) },
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setContentIntent(openApp())
            .addAction(0, getString(R.string.notification_disconnect_all), disconnectAll())
            .setOngoing(true)
            // 会话是从几小时前开始的，通知上显示一个「3 小时前」只会让人以为它卡住了。
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /** MainActivity 是 singleTask，直接 launch 就会回到原来那个实例，不会叠一层新的。 */
    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun disconnectAll(): PendingIntent = PendingIntent.getService(
        this,
        1,
        Intent(this, SessionService::class.java).setAction(ACTION_DISCONNECT_ALL),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * 低优先级渠道：这条通知一挂就是几小时，出声或震动是纯粹的骚扰。
     * IMPORTANCE_LOW 同时也让它不占用状态栏的抬头提示。
     */
    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_sessions),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_sessions_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {

        private const val CHANNEL_ID = "sessions"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISCONNECT_ALL = "net.lighttools.lightmux.DISCONNECT_ALL"

        /**
         * **只能在 app 处于前台时调用**：Android 12+ 禁止从后台启动前台服务，
         * 后台调用会抛 ForegroundServiceStartNotAllowedException。
         * 调用点固定在 [MainActivity] 的 STARTED 生命周期里。
         */
        fun start(context: Context) {
            val intent = Intent(context, SessionService::class.java)
            // 起不来不是致命错误：会话照跑，只是进程在后台更容易被回收，不值得为它崩掉 app。
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "failed to start session service", it) }
        }

        private const val TAG = "SessionService"
    }
}
