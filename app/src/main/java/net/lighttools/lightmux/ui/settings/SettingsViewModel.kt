package net.lighttools.lightmux.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.lighttools.lightmux.data.AppSettings
import net.lighttools.lightmux.data.LanguageOption
import net.lighttools.lightmux.data.SettingsStore
import net.lighttools.lightmux.data.TerminalPalette
import net.lighttools.lightmux.data.ThemeOption
import net.lighttools.lightmux.update.ApkTooLargeException
import net.lighttools.lightmux.update.ApkVerdict
import net.lighttools.lightmux.update.InstallOutcome
import net.lighttools.lightmux.update.ReleaseInfo
import net.lighttools.lightmux.update.UpdateInstallState
import net.lighttools.lightmux.update.UpdateChecker
import net.lighttools.lightmux.update.UpdateInstaller
import net.lighttools.lightmux.update.UpdateResult

/**
 * 更新流程的可见状态。三种结局（查不到 / 已最新 / 有新版）都必须落到某个具体状态上——
 * 点了「检查更新」什么都不动，用户只会以为按钮坏了。
 */
sealed interface UpdateUiState {

    data object Idle : UpdateUiState

    data object Checking : UpdateUiState

    data object UpToDate : UpdateUiState

    data class Available(val release: ReleaseInfo) : UpdateUiState

    data class Downloading(val percent: Int) : UpdateUiState

    /** 缺「安装未知应用」授权。必须显式提示并给入口，否则拉安装器会被系统静默丢掉。 */
    data class NeedsPermission(val release: ReleaseInfo) : UpdateUiState

    /** 网络失败或校验不通过。[verdict] 非空表示是校验拦下来的。 */
    data class Failed(val reason: String?, val verdict: ApkVerdict? = null) : UpdateUiState

    /** 系统把安装拒了。[message] 是系统的原话，用来排查「装完还是旧版本」这类问题。 */
    data class InstallFailed(val message: String) : UpdateUiState
}

class SettingsViewModel(
    private val store: SettingsStore,
    private val checker: UpdateChecker,
    private val installer: UpdateInstaller,
    val currentVersion: String,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = store.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = store.blockingSnapshot(),
    )

    var update by mutableStateOf<UpdateUiState>(UpdateUiState.Idle)
        private set

    init {
        // 安装结果是异步回来的（系统确认框之后才有结论），而且走的是进程级信箱不是这里的返回值。
        // 不收这一路的话，安装被系统拒了在界面上就是「什么也没发生」——正是这次要修的那个症状。
        viewModelScope.launch {
            UpdateInstallState.outcome.collect { outcome ->
                if (outcome is InstallOutcome.Failed) {
                    update = UpdateUiState.InstallFailed(outcome.message)
                }
            }
        }
    }

    fun setLanguage(value: LanguageOption) = edit { store.setLanguage(value) }

    fun setTheme(value: ThemeOption) = edit { store.setTheme(value) }

    fun setPalette(value: TerminalPalette) = edit { store.setPalette(value) }

    fun setTerminalTextSize(sp: Int) = edit { store.setTerminalTextSize(sp) }

    fun setQuickKeyWidth(dp: Int) = edit { store.setQuickKeyWidth(dp) }

    /** 只在用户点按时查，不做后台自动检查（PRD：省电，也免得偷偷联网）。 */
    fun checkForUpdate() {
        if (update is UpdateUiState.Checking || update is UpdateUiState.Downloading) return
        update = UpdateUiState.Checking
        viewModelScope.launch {
            update = when (val result = checker.check(currentVersion)) {
                is UpdateResult.Available -> UpdateUiState.Available(result.release)
                UpdateResult.UpToDate -> UpdateUiState.UpToDate
                is UpdateResult.Failed -> UpdateUiState.Failed(result.reason)
            }
        }
    }

    /**
     * 下载 → 三重校验 → 拉起系统安装器。
     *
     * 校验不过时 [UpdateInstaller.verify] 已经把文件删了，这里只负责把结论摆到 UI 上。
     */
    fun downloadAndInstall(release: ReleaseInfo) {
        if (update is UpdateUiState.Downloading) return
        if (!installer.canInstall()) {
            update = UpdateUiState.NeedsPermission(release)
            return
        }
        update = UpdateUiState.Downloading(0)
        viewModelScope.launch {
            val apk = runCatching {
                installer.download(release) { percent ->
                    // 回调来自 IO 线程，Compose 状态只能在主线程改
                    withContext(Dispatchers.Main) { update = UpdateUiState.Downloading(percent) }
                }
            }.getOrElse { error ->
                // 超限不是网络故障，别把它塞进「查不到更新：<异常消息>」那句里当技术细节展示
                update = if (error is ApkTooLargeException) {
                    UpdateUiState.Failed(reason = null, verdict = ApkVerdict.TooLarge)
                } else {
                    UpdateUiState.Failed(error.message ?: error.javaClass.simpleName)
                }
                return@launch
            }
            when (val verdict = withContext(Dispatchers.IO) { installer.verify(apk) }) {
                ApkVerdict.Ok -> {
                    // 先清掉上一轮的结论，否则信箱里那条旧失败会被 collect 立刻盖回界面上
                    UpdateInstallState.report(InstallOutcome.Idle)
                    runCatching { installer.install(apk) }
                        .onSuccess { update = UpdateUiState.Idle }
                        .onFailure {
                            update = UpdateUiState.InstallFailed(
                                it.message ?: it.javaClass.simpleName
                            )
                        }
                }

                else -> update = UpdateUiState.Failed(reason = null, verdict = verdict)
            }
        }
    }

    fun grantInstallPermission() {
        installer.openUnknownSourcesSettings()
        update = UpdateUiState.Idle
    }

    fun dismissUpdate() {
        update = UpdateUiState.Idle
    }

    private fun edit(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
