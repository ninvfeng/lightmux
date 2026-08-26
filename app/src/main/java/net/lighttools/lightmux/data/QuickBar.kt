package net.lighttools.lightmux.data

/**
 * 快捷栏上的一格。
 *
 * [id] 是落盘用的稳定标识——改它等于把老用户排好的顺序清空，加项可以，改名不行。
 * [label] 是键帽上画的字，`null` 表示这一格由 UI 画图标：「返回」「命令」「文件」「转发」是动作不是按键，
 * 拿一个字符表示不清楚。
 *
 * 没有 [label] 的都在 UI 的 `when` 里单独派发；有 [label] 且长度为 1 的字面量键统一走
 * 「键帽画什么就发什么」，所以**新增字面量键只要在这里加一行**。
 *
 * [ctrlChar] 非空即组合键（`^C` `^D`…）：发的是这个字符加 Ctrl，与粘滞 [Ctrl] 无关。
 * 组合键值得单独占一格，是因为粘滞 Ctrl 要按两下、还得瞄准软键盘上的字母；
 * 而 `^C` `^D` 恰恰是最急的两个——程序跑飞了的时候没空瞄。
 */
enum class QuickKey(val id: String, val label: String? = null, val ctrlChar: Char? = null) {
    Back("back"),
    Commands("cmd"),
    Files("files"),
    Forward("forward"),
    // 用 `Ent` 不用 `⏎`：符号在小字号下糊成一团，三个字母反而认得快
    Enter("enter", "Ent"),
    Esc("esc", "Esc"),
    Tab("tab", "Tab"),
    Ctrl("ctrl", "Ctrl"),
    Alt("alt", "Alt"),
    Up("up", "↑"),
    Down("down", "↓"),
    Left("left", "←"),
    Right("right", "→"),
    Home("home", "Home"),
    End("end", "End"),
    PageUp("pgup", "PgUp"),
    PageDown("pgdn", "PgDn"),
    Del("del", "Del"),
    Dash("dash", "-"),
    Pipe("pipe", "|"),
    Tilde("tilde", "~"),
    Slash("slash", "/"),
    Backslash("backslash", "\\"),
    Star("star", "*"),
    Dollar("dollar", "$"),
    Colon("colon", ":"),
    Underscore("underscore", "_"),
    Quote("quote", "'"),

    // 组合键。`^X` 是终端里通行的写法，也比「Ctrl+C」短得多——键帽就那么宽
    CtrlC("ctrl_c", "^C", 'c'),
    CtrlD("ctrl_d", "^D", 'd'),
    CtrlZ("ctrl_z", "^Z", 'z'),
    CtrlL("ctrl_l", "^L", 'l'),
    CtrlR("ctrl_r", "^R", 'r'),
    CtrlA("ctrl_a", "^A", 'a'),
    CtrlE("ctrl_e", "^E", 'e'),
    CtrlU("ctrl_u", "^U", 'u'),
    CtrlK("ctrl_k", "^K", 'k'),
    CtrlW("ctrl_w", "^W", 'w'),
    CtrlX("ctrl_x", "^X", 'x'),
    // tmux 默认前缀键。这个 app 的主场就是 tmux，前缀键按不出来等于半个 tmux 都用不了
    CtrlB("ctrl_b", "^B", 'b'),
    ;

    companion object {
        fun of(id: String): QuickKey? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 用户自己加的一格键。
 *
 * [QuickKey] 是编译期定死的一张表，这一份是运行期的：键帽写什么、按下去往终端里送什么，
 * 全由用户填。它存在的理由是**把常用的那条命令顶成一级快捷键**——
 * `c1`（`alias claude`）这种一天按几十次的东西，藏在命令表里等于每次多两下点击。
 *
 * [id] 是落盘用的稳定标识，形如 `u1`；与 [QuickKey.id] 不会撞，预设 id 里没有「u + 纯数字」。
 * [enter] 是「按下即执行」：默认不勾（只填不回车，与快捷命令表一致，误触跑到线上机器代价太高），
 * 但一级快捷键的意义本就在于一按就跑，所以给开关而不替用户定死。
 */
data class QuickCustomKey(
    val id: String,
    val label: String,
    val text: String,
    val enter: Boolean = false,
)

/**
 * 快捷栏上的一格：预设键或自定义键。
 *
 * 两者共用 [id] 的命名空间——栏的顺序仍是一串 id，解码时先查预设表再查自定义表。
 */
sealed interface QuickSlot {

    val id: String

    data class Preset(val key: QuickKey) : QuickSlot {
        override val id: String get() = key.id
    }

    data class Custom(val key: QuickCustomKey) : QuickSlot {
        override val id: String get() = key.id
    }
}

/**
 * 自定义键的存取。
 *
 * 一行一条，字段用 TAB 分隔：`id \t enter \t label \t text`。
 * **要发送的文本放行尾**，从行尾反解——它是唯一可能含奇怪字符的字段（同 tmux 侧通道那条纪律）。
 * TAB 和换行在落盘前压成空格，否则一条会被读成两条或串行。
 */
object QuickCustomKeys {

    /** 键帽宽度是屏幕分出来的，太长的一格能把整条栏挤到划不动。 */
    const val MAX_LABEL_LENGTH = 12

    private const val ID_PREFIX = "u"

    fun decode(raw: String?): List<QuickCustomKey> = raw?.lines().orEmpty().mapNotNull { line ->
        val parts = line.split('\t', limit = 4)
        if (parts.size < 4) return@mapNotNull null
        val id = parts[0].trim()
        val label = parts[2].trim()
        val text = parts[3].trim()
        if (id.isEmpty() || label.isEmpty() || text.isEmpty()) null
        else QuickCustomKey(id = id, label = label, text = text, enter = parts[1].trim() == "1")
    }.distinctBy { it.id }

    fun encode(keys: List<QuickCustomKey>): String = keys
        .distinctBy { it.id }
        .mapNotNull { key ->
            // id 还要挡掉逗号——栏的顺序串是逗号分隔的
            val id = key.id.flatten().replace(",", "")
            val label = key.label.flatten().take(MAX_LABEL_LENGTH)
            val text = key.text.flatten()
            if (id.isEmpty() || label.isEmpty() || text.isEmpty()) null
            else "$id\t${if (key.enter) "1" else "0"}\t$label\t$text"
        }
        .joinToString("\n")

    /** 新 id 取「现有最大序号 + 1」：删掉一格再加一格不该复活刚删掉的那个 id。 */
    fun nextId(keys: List<QuickCustomKey>): String {
        val max = keys.mapNotNull { it.id.removePrefix(ID_PREFIX).toIntOrNull() }.maxOrNull() ?: 0
        return "$ID_PREFIX${max + 1}"
    }
}

/**
 * 快捷栏的键位尺寸。
 *
 * 用户只拖一个数——**键宽**，其余三个尺寸由它推出来。理由是这三者本来就不该分开调：
 * - [capHeightDp] 跟着宽度走，不然拖宽了会得到一排又扁又长的键
 * - [gapDp] 管键与键之间。键帽撑大而键仍挨着，误触邻键的概率一点没降，等于白拖
 * - [labelPadDp] 才管**多字符键**（`Ctrl` `Home` `PgDn`）——这些键宽由文字自己撑开，
 *   不缩留白，键宽下限调多小都一格省不出来；[capWidthDp] 只管得到单字符键（`↑` `-` `|`）
 *
 * 系数是从 0.1.11 那三档（26/36/44）反解出来的，端点对得上原来的紧凑与宽松。
 * 默认 [DEFAULT_WIDTH_DP] 就是原紧凑档：排在方向键之后的 `Ctrl` 落在第 10 格，
 * 只有这个宽度不用横划就够得着（354dp 宽的屏一屏 13 个键）。
 *
 * 全是 dp 数值（`Int`）不是 `Dp`：这一层不引 Compose，好让它跟着单测跑。
 */
object QuickKeySize {

    const val MIN_WIDTH_DP = 24
    const val MAX_WIDTH_DP = 48
    const val DEFAULT_WIDTH_DP = 26

    /** 管理表里的样品键帽。固定值，不跟着栏上那排缩——那是给人看清楚「这是哪个键」的。 */
    const val SHEET_WIDTH_DP = 36

    fun clamp(widthDp: Int): Int = widthDp.coerceIn(MIN_WIDTH_DP, MAX_WIDTH_DP)

    fun capHeightDp(widthDp: Int): Int = (clamp(widthDp) + 30) / 2

    fun gapDp(widthDp: Int): Int = ((clamp(widthDp) - 22) / 3).coerceAtLeast(1)

    fun labelPadDp(widthDp: Int): Int = gapDp(widthDp) + 1

    fun iconDp(widthDp: Int): Int = (capHeightDp(widthDp) - 12).coerceIn(14, 20)

    /** 0.1.11–0.1.13 存的是三档 id。只读一次，读完就按新键落盘，不留双写。 */
    fun ofLegacyId(id: String?): Int = when (id) {
        "normal" -> 36
        "loose" -> 44
        else -> DEFAULT_WIDTH_DP
    }
}

/**
 * 快捷栏的键位顺序。
 *
 * 存成逗号分隔的 id 串，认不出来的 id 直接丢掉——降级安装（新版加的键在老版里不存在）
 * 不该让整条栏回落成默认值，那样用户排好的顺序会莫名其妙没了。
 */
object QuickBar {

    /**
     * 默认顺序：返回、命令、文件、转发、`Ent`、`Esc`、方向键，然后才是 `Ctrl` 那一批。
     *
     * 返回排第一是因为终端页没有顶栏了（全屏留给终端），它是唯一的显式出口；
     * 四格动作键挨在一起，剩下的才是真按键，这样一眼能看出哪几格「不发字符」。
     * 转发紧跟文件：终端里刚起来的那个服务，转出来就在手机浏览器里开得了，
     * 这条路径的起点几乎总是「我刚在这个终端里把它跑起来」。
     * `Ctrl` 排在方向键之后是妥协——它对 tmux 前缀键最要紧，但窄屏上要划一下才够得着，
     * 想提前的人去「管理」里拖一次即可。
     *
     * 改这份默认值**只影响没动过快捷栏的用户**：动过的人存的是自己那份 id 串，
     * 新增的键要去「管理」里加（这是有意的，见 [decodeKeys]——不能让默认值把用户排好的顺序推翻）。
     */
    val DEFAULT_KEYS: List<QuickKey> = listOf(
        QuickKey.Back,
        QuickKey.Commands,
        QuickKey.Files,
        QuickKey.Forward,
        QuickKey.Enter,
        QuickKey.Esc,
        QuickKey.Up,
        QuickKey.Down,
        QuickKey.Left,
        QuickKey.Right,
        QuickKey.Ctrl,
        QuickKey.Tab,
        QuickKey.Alt,
        QuickKey.Dash,
        QuickKey.Pipe,
        QuickKey.Tilde,
        QuickKey.Slash,
    )

    /** 默认栏全是预设键；自定义键得用户自己加，默认值里不塞。 */
    fun defaultSlots(): List<QuickSlot> = DEFAULT_KEYS.map(QuickSlot::Preset)

    /**
     * 还原一条栏。`null`（没存过）才回落默认；空串是「用户把键全删了」，得如实还原成空。
     *
     * 顺序串里没提到的自定义键补在末尾：老版本写盘时不认识这些 id 会把它们过滤掉，
     * 不补回来的话键还躺在库里、栏上却看不见——用户既按不到也删不掉。
     * 顺带也让「新加一个自定义键」只需写一次盘（写库即可，栏顺序自己跟上）。
     */
    fun decodeSlots(raw: String?, customs: List<QuickCustomKey> = emptyList()): List<QuickSlot> {
        val byId = customs.associateBy { it.id }
        val ordered = raw?.split(',')
            ?.map { it.trim() }
            ?.mapNotNull { id -> QuickKey.of(id)?.let(QuickSlot::Preset) ?: byId[id]?.let(QuickSlot::Custom) }
            ?.distinctBy { it.id }
            ?: defaultSlots()
        val seen = ordered.mapTo(mutableSetOf()) { it.id }
        return ordered + customs.filterNot { it.id in seen }.map(QuickSlot::Custom)
    }

    fun encodeSlots(slots: List<QuickSlot>): String =
        slots.distinctBy { it.id }.joinToString(",") { it.id }

    /** 栏上那些自定义键就是库的全部（[decodeSlots] 保证不漏），改库时从这里取当前值。 */
    fun customsOf(slots: List<QuickSlot>): List<QuickCustomKey> =
        slots.filterIsInstance<QuickSlot.Custom>().map { it.key }
}

/**
 * 一条快捷命令。
 *
 * [enter] 同 [QuickCustomKey.enter]：默认只填进命令行不替用户回车（误触一条命令跑到线上机器
 * 代价太高），但 `docker ps`、`tmux ls` 这种看一眼就完的，每次多按一下 ⏎ 也是白按——
 * 所以按条给开关。
 */
data class QuickCommand(val text: String, val enter: Boolean = false)

/**
 * 快捷命令。
 *
 * 一行一条，所以命令里的换行会被压成空格——多行脚本请写成 `a && b`，
 * 这条栏是给「常敲的那几条」用的，不是脚本编辑器。
 */
object QuickCommands {

    val DEFAULTS: List<QuickCommand> = listOf(
        "ls -la",
        "cd ..",
        "df -h",
        "free -h",
        "docker ps",
        "tmux ls",
    ).map(::QuickCommand)

    /**
     * 一行一条，格式 `enter \t 命令`。`null` 回落默认，空串如实还原成空（同 [QuickBar.decodeSlots]）。
     *
     * 0.1.40 之前存的是纯命令行，没有前面那个标志位——首字段不是 `0`/`1` 就整行当命令，
     * 老用户攒下的那几条不该因为多了个开关而消失。
     */
    fun decode(raw: String?): List<QuickCommand> = raw?.lines()?.mapNotNull { line ->
        val parts = line.split('\t', limit = 2)
        val tagged = parts.size == 2 && (parts[0] == "0" || parts[0] == "1")
        val text = (if (tagged) parts[1] else line).trim()
        if (text.isEmpty()) null else QuickCommand(text, enter = tagged && parts[0] == "1")
    } ?: DEFAULTS

    fun encode(commands: List<QuickCommand>): String = commands
        .mapNotNull { command ->
            val text = command.text.flatten()
            if (text.isEmpty()) null else "${if (command.enter) "1" else "0"}\t$text"
        }
        .joinToString("\n")
}

/**
 * 落盘前把分隔符压掉：TAB 是字段分隔符、换行是记录分隔符，
 * 留着它们一条会被读成两条、或者串到别的字段去。
 */
private fun String.flatten(): String = replace(Regex("[\\t\\r\\n]+"), " ").trim()
