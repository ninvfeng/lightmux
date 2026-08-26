package net.lighttools.lightmux

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.lighttools.lightmux.data.LanguageOption
import net.lighttools.lightmux.data.SettingsStore
import net.lighttools.lightmux.data.ThemeOption
import net.lighttools.lightmux.service.SessionService
import net.lighttools.lightmux.ui.LightmuxRoot
import net.lighttools.lightmux.ui.theme.LightmuxTheme

class MainActivity : ComponentActivity() {

    /** 这个 Activity 实例是按哪种语言 inflate 的。设置里改成别的就得重建。 */
    private var appliedLanguage = LanguageOption.Follow

    /**
     * 语言只能在这里定：`Resources` 在 Activity 拿到 base context 时就固定了，
     * 之后再改 Configuration 已经晚了。minSdk 24 又不引 appcompat，
     * 所以走 `createConfigurationContext` 包一层 + 切换后 `recreate()` 这条标准路径。
     */
    override fun attachBaseContext(newBase: Context) {
        appliedLanguage = SettingsStore(newBase).blockingSnapshot().language
        super.attachBaseContext(wrapLocale(newBase, appliedLanguage))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        keepSessionsAlive()
        recreateOnLanguageChange()
        val settingsStore = (application as LightmuxApp).settingsStore
        setContent {
            // 主题不需要重建 Activity：Compose 直接由状态驱动重组
            val settings by settingsStore.settings
                .collectAsState(initial = remember { settingsStore.blockingSnapshot() })
            val dark = when (settings.theme) {
                ThemeOption.Follow -> isSystemInDarkTheme()
                ThemeOption.Light -> false
                ThemeOption.Dark -> true
            }
            // enableEdgeToEdge() 是按**系统**深色模式决定状态栏图标颜色的，而主题默认强制深色：
            // 亮色系统上就成了「深色图标 + 深色背景」，状态栏与导航栏直接看不见。按 app 自己的主题再定一次。
            val view = LocalView.current
            SideEffect {
                WindowCompat.getInsetsController(window, view).run {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            LightmuxTheme(darkTheme = dark) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    LightmuxRoot()
                }
            }
        }
    }

    /**
     * 语言变了就重建。放在 STARTED 里而不是直接 collect：后台的 Activity 调 `recreate()`
     * 会在返回时闪一下，也没有必要——它回到前台时本来就要走一遍这条流。
     */
    private fun recreateOnLanguageChange() {
        val app = application as LightmuxApp
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.settingsStore.settings
                    .map { it.language }
                    .distinctUntilChanged()
                    .collect { if (it != appliedLanguage) recreate() }
            }
        }
    }

    /**
     * 有活儿就拉起前台服务。
     *
     * 必须由 Activity 在 [Lifecycle.State.STARTED] 里驱动：Android 12+ 禁止从后台启动前台服务，
     * 而「用户点开一个会话」这一刻 app 一定在前台。停止不在这里做——最后一个会话可能是在通知栏里
     * 被关掉的，那时 Activity 早就 STOPPED 了，收不到这条流，所以由服务自己观察着停。
     *
     * **不申请通知权限**：那条常驻通知对用户没有信息量，只是前台服务的强制附属品。
     * Android 13+ 未授权时系统只是不显示它，服务与保活能力一点不受影响；
     * 想看的人可以自己去系统设置里打开。
     */
    private fun keepSessionsAlive() {
        val app = application as LightmuxApp
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.hasLiveWork.distinctUntilChanged().collect { busy ->
                    if (busy) SessionService.start(this@MainActivity)
                }
            }
        }
    }
}

/** 「跟随系统」时原样返回 base context，别去覆盖系统的语言列表（那会丢掉用户的次选语言）。 */
private fun wrapLocale(base: Context, language: LanguageOption): Context {
    val locale = language.locale ?: return base
    val config = Configuration(base.resources.configuration)
    config.setLocales(LocaleList(locale))
    return base.createConfigurationContext(config)
}
