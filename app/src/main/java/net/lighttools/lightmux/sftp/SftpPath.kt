package net.lighttools.lightmux.sftp

import net.lighttools.lightmux.monitor.formatBytes

/**
 * 远端路径的拼接与规范化。**纯 Kotlin，零 Android 依赖**——和 `Tmux`、`HostFacts` 一样，
 * 本机没有真机，这一层的单测是 M3b 唯一能自证正确的手段（PRD §6.2）。
 *
 * **绝不用 `java.io.File` 处理远端路径**：它带的是**本地**分隔符语义，
 * 在 Windows 上 `File("/a\\b").name` 会把反斜杠当分隔符，而远端 Linux 上 `a\b` 是个合法文件名；
 * 它还会把 `//a` 折成 `/a`（正好对）却把空路径解析成当前工作目录（完全不对）。
 * 远端路径只有一种语义——POSIX，所以自己写。
 */
object SftpPath {

    const val ROOT = "/"

    private const val SEPARATOR = '/'

    /** 文件名里出现 NUL 时 SFTP 协议层就会截断，必须在拼路径之前拦下来。 */
    private const val NUL = '\u0000'

    /** 面包屑的一级：显示名 + 跳过去用的绝对路径。 */
    data class Crumb(val name: String, val path: String)

    /**
     * 规范化成绝对路径。处理 `.`、`..`、重复斜杠、结尾斜杠。
     *
     * 结果**一律以 `/` 开头**：SFTP 浏览器里的路径全是绝对的（起点由 `canonicalize(".")` 给出），
     * 留一个「有时相对有时绝对」的返回值只会让调用方各自猜。
     *
     * `..` **不能越过根**：`/..` 与 `/a/../..` 都停在 `/`。这不是洁癖——
     * 越过根拼出来的 `/../etc` 在有些服务端会被解释成 chroot 之外的路径。
     */
    fun normalize(path: String): String {
        val stack = ArrayList<String>()
        for (segment in path.split(SEPARATOR)) {
            when (segment) {
                // 重复斜杠会切出空串，和 "." 一样直接丢
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                else -> stack.add(segment)
            }
        }
        return if (stack.isEmpty()) ROOT else stack.joinToString(separator = "/", prefix = ROOT)
    }

    /**
     * 在 [base] 下拼一个名字。[name] 以 `/` 开头时按绝对路径处理（POSIX 语义）。
     *
     * 结果经过 [normalize]，所以 `join("/a/b", "..")` 得到 `/a`。
     */
    fun join(base: String, name: String): String =
        if (name.startsWith(SEPARATOR)) normalize(name) else normalize("$base$SEPARATOR$name")

    /** 上一级。到根就停在根，不会往上跑出 `/`。 */
    fun parent(path: String): String {
        val normalized = normalize(path)
        if (normalized == ROOT) return ROOT
        val cut = normalized.lastIndexOf(SEPARATOR)
        return if (cut <= 0) ROOT else normalized.substring(0, cut)
    }

    /** 末段名字。根没有名字，返回空串。 */
    fun name(path: String): String {
        val normalized = normalize(path)
        if (normalized == ROOT) return ""
        return normalized.substring(normalized.lastIndexOf(SEPARATOR) + 1)
    }

    /** 从根到当前的每一级，用于面包屑。根那一级恒在第一个。 */
    fun crumbs(path: String): List<Crumb> {
        val normalized = normalize(path)
        val result = ArrayList<Crumb>()
        result.add(Crumb(ROOT, ROOT))
        if (normalized == ROOT) return result
        var current = ""
        for (segment in normalized.removePrefix(ROOT).split(SEPARATOR)) {
            current = "$current$SEPARATOR$segment"
            result.add(Crumb(segment, current))
        }
        return result
    }

    /** Unix 的隐藏文件约定：点开头。`.` 与 `..` 自然也算，列表里本来就不该出现它们。 */
    fun isHidden(name: String): Boolean = name.startsWith(".")

    /**
     * 能不能用作新建 / 重命名的名字。
     *
     * 拒绝空白、`.`、`..`、含 `/` 与 NUL。放行的部分同样重要：**空格与中文都是合法文件名**，
     * 拿「只允许 ASCII 字母数字」去卡用户，在中文环境下等于把重命名功能废掉。
     */
    fun isValidName(name: String): Boolean = when {
        name.isBlank() -> false
        name == "." || name == ".." -> false
        name.contains(SEPARATOR) -> false
        name.contains(NUL) -> false
        else -> true
    }

    /**
     * 把路径包成能直接填进 shell 的样子。
     *
     * 远端文件名里的空格、`$`、`(`、`&` 在 shell 眼里全是语法：原样填进终端，
     * `/tmp/my file.txt` 会被拆成两个参数，`/tmp/$(x)` 更是会**被执行**。
     * 所以反过来做——只有确定安全的那几类字符原样放行，其余一律单引号包起来。
     * 中文名走的也是加引号这条路：安全，且引号在 shell 里不影响结果。
     *
     * 单引号自己没法在单引号里转义，只能断开再拼（`'\''`）——这是 POSIX shell 里唯一的写法。
     */
    fun shellQuote(path: String): String =
        if (SHELL_SAFE.matches(path)) path else "'" + path.replace("'", "'\\''") + "'"

    /** 未加引号时含义等于自身的字符。少一个都只是多套一层引号，多一个可能就是一次命令注入。 */
    private val SHELL_SAFE = Regex("[A-Za-z0-9@%+=:,./_-]+")

    /**
     * 人类可读的字节数。
     *
     * 直接复用监控页那份：两个页面对「1.5 MiB」的写法必须一致，抄一份过来迟早两边分叉。
     */
    fun humanSize(bytes: Long): String = formatBytes(bytes)
}
