package net.lighttools.lightmux.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 一次安装会话的结局。
 *
 * **没有「成功」这个分支**：装成功的那一刻系统就把本进程杀了重来，没人还能读到它。
 * 这个类型存在的全部意义是失败时有话可说——旧的 `ACTION_VIEW` 那条路是发完 Intent 就撒手，
 * 谁接的、装没装成、为什么没装成，应用一概不知，用户看到的只是「装完还是旧版本」。
 */
sealed interface InstallOutcome {

    data object Idle : InstallOutcome

    /** 系统确认框已经拉起来了，等用户点。 */
    data object Confirming : InstallOutcome

    data class Failed(val message: String) : InstallOutcome
}

/**
 * 进程级的安装结果信箱。
 *
 * 必须活在 ViewModel 之外：结果走的是 manifest 声明的广播接收器，它到达时设置页可能早就销毁了。
 */
object UpdateInstallState {

    private val _outcome = MutableStateFlow<InstallOutcome>(InstallOutcome.Idle)

    val outcome: StateFlow<InstallOutcome> = _outcome.asStateFlow()

    fun report(value: InstallOutcome) {
        _outcome.value = value
    }
}

/**
 * 接收 [PackageInstaller] 会话的状态回调。
 *
 * 必须写在 manifest 里而不是运行时注册：安装真正落地时系统会杀掉本进程，
 * 运行时注册的接收器活不到那一刻。
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            // 会话提交后系统先要用户点头。确认框是系统给的，我们只负责把它拉起来
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) {
                    UpdateInstallState.report(InstallOutcome.Failed(NO_CONFIRM_INTENT))
                } else {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    UpdateInstallState.report(InstallOutcome.Confirming)
                }
            }

            PackageInstaller.STATUS_SUCCESS -> UpdateInstallState.report(InstallOutcome.Idle)

            // 用户自己点了「取消」，不是故障，报错反而莫名其妙
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateInstallState.report(InstallOutcome.Idle)

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?.takeIf { it.isNotBlank() } ?: UNKNOWN_FAILURE
                UpdateInstallState.report(InstallOutcome.Failed(message))
            }
        }
    }

    private companion object {
        // 系统给的原文，不进 strings.xml：它是拿去排查问题的，翻译反而对不上搜索结果
        const val UNKNOWN_FAILURE = "unknown"
        const val NO_CONFIRM_INTENT = "missing confirmation intent"
    }
}
