package net.lighttools.lightmux.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.util.Locale

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 界面语言。
 *
 * [Follow] 之外的两项靠 `createConfigurationContext` 包一层 base context 实现（见 MainActivity），
 * 不引 appcompat 的 `AppCompatDelegate.setApplicationLocales`——那要为一个开关拖进整个 appcompat。
 */
enum class LanguageOption(val id: String, val locale: Locale?) {
    Follow("system", null),
    Chinese("zh", Locale.SIMPLIFIED_CHINESE),
    English("en", Locale.ENGLISH),
    ;

    companion object {
        fun of(id: String?): LanguageOption = entries.firstOrNull { it.id == id } ?: Follow
    }
}

enum class ThemeOption(val id: String) {
    Follow("system"),
    Light("light"),
    Dark("dark"),
    ;

    companion object {

        /**
         * 默认深色，**不跟随系统**。
         *
         * 终端本身是白字黑底（vendored 内核的默认配色，见 `TerminalColorScheme`），
         * 外壳一跟随系统，白天打开就是「亮色标题栏 + 纯黑终端」，接缝刺眼；
         * 何况 SSH 客户端的使用场景本来就偏暗环境。想要亮色的人去设置里改一次即可。
         */
        val Default = Dark

        fun of(id: String?): ThemeOption = entries.firstOrNull { it.id == id } ?: Default
    }
}

/**
 * 终端配色。
 *
 * [colors] 是覆盖项，键名沿用 vendored `TerminalColorScheme.updateWith` 认的那一套
 * （`color0`…`color15` / `foreground` / `background` / `cursor`），没列出的索引回落到内置 xterm 默认。
 *
 * 色值按各配色方案**公开发布的十六进制值**填写，属于事实数据；没有从任何配色文件里拷代码。
 * - Solarized Dark：Ethan Schoonover 的配色规格（base03/base0 等色标）
 * - Gruvbox Dark：Pavel Pertsev 的配色规格（bg0/fg1 等色标）
 */
enum class TerminalPalette(val id: String, val colors: Map<String, String>) {

    /** vendored 内核自带的 xterm 默认表，不做任何覆盖。 */
    Default("default", emptyMap()),

    SolarizedDark(
        "solarized-dark",
        mapOf(
            "color0" to "#073642", "color1" to "#dc322f", "color2" to "#859900", "color3" to "#b58900",
            "color4" to "#268bd2", "color5" to "#d33682", "color6" to "#2aa198", "color7" to "#eee8d5",
            "color8" to "#002b36", "color9" to "#cb4b16", "color10" to "#586e75", "color11" to "#657b83",
            "color12" to "#839496", "color13" to "#6c71c4", "color14" to "#93a1a1", "color15" to "#fdf6e3",
            "foreground" to "#839496", "background" to "#002b36", "cursor" to "#93a1a1",
        ),
    ),

    GruvboxDark(
        "gruvbox-dark",
        mapOf(
            "color0" to "#282828", "color1" to "#cc241d", "color2" to "#98971a", "color3" to "#d79921",
            "color4" to "#458588", "color5" to "#b16286", "color6" to "#689d6a", "color7" to "#a89984",
            "color8" to "#928374", "color9" to "#fb4934", "color10" to "#b8bb26", "color11" to "#fabd2f",
            "color12" to "#83a598", "color13" to "#d3869b", "color14" to "#8ec07c", "color15" to "#ebdbb2",
            "foreground" to "#ebdbb2", "background" to "#282828", "cursor" to "#ebdbb2",
        ),
    ),
    ;

    companion object {
        fun of(id: String?): TerminalPalette = entries.firstOrNull { it.id == id } ?: Default
    }
}

/**
 * 全部应用设置。
 *
 * 行距不在里面：vendored 的 `TerminalRenderer` 直接用字体度量算行高（`mFontLineSpacing`），
 * 没有对外的调节口，而 `terminal-view/` 是不许改的 Apache-2.0 代码（CLAUDE.md 法律红线），
 * 所以 PRD 里的「行距可调」这一项**砍掉**，只保留字号。
 */
data class AppSettings(
    val language: LanguageOption = LanguageOption.Follow,
    val theme: ThemeOption = ThemeOption.Default,
    val palette: TerminalPalette = TerminalPalette.Default,
    val terminalTextSizeSp: Int = DEFAULT_TERMINAL_TEXT_SIZE_SP,
    val quickSlots: List<QuickSlot> = QuickBar.defaultSlots(),
    val quickKeyWidthDp: Int = QuickKeySize.DEFAULT_WIDTH_DP,
    val quickCommands: List<QuickCommand> = QuickCommands.DEFAULTS,
) {
    companion object {
        const val DEFAULT_TERMINAL_TEXT_SIZE_SP = 13
        const val MIN_TERMINAL_TEXT_SIZE_SP = 8
        const val MAX_TERMINAL_TEXT_SIZE_SP = 28
    }
}

/**
 * 设置的持久化。存储方式与 [HostStore] 一致（Preferences DataStore），
 * 但这里是几个互不相干的标量，就按扁平 key 存，不必像主机列表那样整存整取。
 */
class SettingsStore(context: Context) {

    private val store = context.applicationContext.settingsDataStore

    val settings: Flow<AppSettings> = store.data.map { prefs ->
        // 栏的顺序里可能含自定义键的 id，得先把库取出来才解得开
        val customs = QuickCustomKeys.decode(prefs[KEY_QUICK_CUSTOM_KEYS])
        AppSettings(
            language = LanguageOption.of(prefs[KEY_LANGUAGE]),
            theme = ThemeOption.of(prefs[KEY_THEME]),
            palette = TerminalPalette.of(prefs[KEY_PALETTE]),
            terminalTextSizeSp = (prefs[KEY_TEXT_SIZE] ?: AppSettings.DEFAULT_TERMINAL_TEXT_SIZE_SP)
                .coerceIn(AppSettings.MIN_TERMINAL_TEXT_SIZE_SP, AppSettings.MAX_TERMINAL_TEXT_SIZE_SP),
            quickSlots = QuickBar.decodeSlots(prefs[KEY_QUICK_KEYS], customs),
            // 没存过新键就看旧的三档：0.1.11–0.1.13 调过宽松的人，不该升级一下被打回紧凑
            quickKeyWidthDp = prefs[KEY_QUICK_KEY_WIDTH]?.let { QuickKeySize.clamp(it) }
                ?: QuickKeySize.ofLegacyId(prefs[KEY_QUICK_KEY_SIZE_LEGACY]),
            quickCommands = QuickCommands.decode(prefs[KEY_QUICK_COMMANDS]),
        ).also { cached = it }
    }.catch { e ->
        // 读不出来（DataStore 文件损坏）就用默认值，和 [HostStore] / [SshKeyStore] / [ForwardStore]
        // 的损坏降级同一口径：不崩，也不覆盖原始数据。
        //
        // 这里尤其不能抛：[blockingSnapshot] 的第一个使用者是 `MainActivity.attachBaseContext`，
        // 异常会在 Activity 拿到 Resources 之前冒出去，**app 连界面都进不去**。
        // 而这个 Store 里只有语言 / 主题 / 快捷栏，读失败最多是语言不对，用户还能进设置改回来。
        Log.w(TAG, "settings unreadable, falling back to defaults", e)
        emit(AppSettings().also { cached = it })
    }

    suspend fun snapshot(): AppSettings = settings.first()

    /**
     * 同步读一份。**只给 `attachBaseContext` 用**：语言必须在 Activity 拿到 Resources 之前定下来，
     * 那一刻还没有协程可以等。首次会阻塞一次几百字节的文件读（毫秒级），之后走进程内缓存，
     * 语言切换触发的 `recreate()` 因此是零 IO 的。
     *
     * 读失败不会抛——降级由 [settings] 那条 `catch` 兜住（理由写在那里）。
     */
    fun blockingSnapshot(): AppSettings = cached ?: runBlocking { snapshot() }

    suspend fun setLanguage(value: LanguageOption) = put(KEY_LANGUAGE, value.id)

    suspend fun setTheme(value: ThemeOption) = put(KEY_THEME, value.id)

    suspend fun setPalette(value: TerminalPalette) = put(KEY_PALETTE, value.id)

    suspend fun setTerminalTextSize(sp: Int) {
        val clamped = sp.coerceIn(
            AppSettings.MIN_TERMINAL_TEXT_SIZE_SP,
            AppSettings.MAX_TERMINAL_TEXT_SIZE_SP,
        )
        store.edit { it[KEY_TEXT_SIZE] = clamped }
    }

    suspend fun setQuickSlots(slots: List<QuickSlot>) = put(KEY_QUICK_KEYS, QuickBar.encodeSlots(slots))

    /**
     * 自定义键的库。**增删改都只写这一条**——栏的顺序会自己跟上（见 [QuickBar.decodeSlots]）：
     * 新加的补在栏尾，删掉的那格顺序串里认不出来自然消失。
     */
    suspend fun setQuickCustomKeys(keys: List<QuickCustomKey>) =
        put(KEY_QUICK_CUSTOM_KEYS, QuickCustomKeys.encode(keys))

    suspend fun setQuickKeyWidth(dp: Int) {
        store.edit { it[KEY_QUICK_KEY_WIDTH] = QuickKeySize.clamp(dp) }
    }

    suspend fun setQuickCommands(commands: List<QuickCommand>) =
        put(KEY_QUICK_COMMANDS, QuickCommands.encode(commands))

    private suspend fun put(key: Preferences.Key<String>, value: String) {
        store.edit { it[key] = value }
    }

    private companion object {
        const val TAG = "SettingsStore"
        val KEY_LANGUAGE = stringPreferencesKey("language")
        val KEY_THEME = stringPreferencesKey("theme")
        val KEY_PALETTE = stringPreferencesKey("terminal_palette")
        val KEY_TEXT_SIZE = intPreferencesKey("terminal_text_size_sp")
        val KEY_QUICK_KEYS = stringPreferencesKey("quick_keys")
        val KEY_QUICK_CUSTOM_KEYS = stringPreferencesKey("quick_custom_keys")
        val KEY_QUICK_KEY_WIDTH = intPreferencesKey("quick_key_width_dp")
        val KEY_QUICK_KEY_SIZE_LEGACY = stringPreferencesKey("quick_key_size")
        val KEY_QUICK_COMMANDS = stringPreferencesKey("quick_commands")

        /** 进程内最近一次读到的值，见 [blockingSnapshot]。 */
        @Volatile
        var cached: AppSettings? = null
    }
}
