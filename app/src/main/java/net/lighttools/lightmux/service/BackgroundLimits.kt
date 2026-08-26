package net.lighttools.lightmux.service

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * 后台联网的系统豁免（电池优化白名单）。
 *
 * **前台服务只保证进程不被杀，管不住网络。** 省电策略会在应用切后台几秒内掐掉它的网络，
 * 而端口转发唯一的使用姿势恰恰就是「点开浏览器」——那一下 app 就进了后台，隧道随即断开，
 * 用户看到的是一个刚打开就失效的页面。终端会话在后台同样会断，只是没人盯着所以不易察觉。
 *
 * 这件事没有代码层的解法：能不能在后台联网由系统策略说了算，[SessionService] 也好、
 * 重连退避也好都绕不过去。app 唯一能做的是把授权入口递到用户面前——各家 ROM 把它藏在
 * 完全不同的菜单里，「完全后台行为」「省电策略」「应用启动管理」说的都是同一件事。
 */
object BackgroundLimits {

    /**
     * 系统有没有放行本应用的后台行为。
     *
     * 取不到 `PowerManager` 时按**放行**处理：宁可不提示，也不要在一台其实没有这个限制的
     * 机器上凭空挂一条吓唬人的横幅。
     */
    fun isExempt(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return true
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 拉起系统的授权入口。
     *
     * 两级兜底：标准 Intent 在部分 ROM 上被拦掉、或压根没有对应 Activity，那就退到应用详情页
     * ——省电策略的入口一定在那儿，只是要用户自己多点两下，总好过点了没反应。
     */
    @SuppressLint("BatteryLife")
    fun requestExemption(context: Context) {
        val self = Uri.fromParts("package", context.packageName, null)
        if (start(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, self))) return
        start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, self))
    }

    private fun start(context: Context, intent: Intent): Boolean = runCatching {
        // 从非 Activity 的 context 起系统页必须自带任务栈，否则直接抛 AndroidRuntimeException
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.onFailure { Log.w(TAG, "failed to open battery optimization settings", it) }.isSuccess

    private const val TAG = "BackgroundLimits"
}
