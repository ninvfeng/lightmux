package net.lighttools.lightmux

import net.lighttools.lightmux.sftp.RemoteEntry
import net.lighttools.lightmux.sftp.RemoteFileType
import net.lighttools.lightmux.sftp.SftpPath
import net.lighttools.lightmux.sftp.Transfer
import net.lighttools.lightmux.sftp.TransferDirection
import net.lighttools.lightmux.sftp.TransferStatus
import net.lighttools.lightmux.sftp.looksBinary
import net.lighttools.lightmux.sftp.sortedForDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 远端路径运算的单测。
 *
 * 这一层没有真机也没有服务器可依赖，**它是 SFTP 功能里唯一能自证正确的部分**：
 * 路径算错的后果不是显示难看，而是删错目录、把文件写到别人的家目录里。
 *
 * 覆盖重点全在「畸形输入」上——真实路径来自服务端 `ls` 的返回和用户手输，
 * 两者都会给出重复斜杠、结尾斜杠、`..`、空格和中文。
 */
class SftpPathTest {

    // ---- normalize ------------------------------------------------------------

    @Test
    fun `normalize 保留正常绝对路径`() {
        assertEquals("/var/log/nginx", SftpPath.normalize("/var/log/nginx"))
    }

    @Test
    fun `normalize 折叠重复斜杠`() {
        assertEquals("/var/log", SftpPath.normalize("//var///log"))
    }

    @Test
    fun `normalize 去掉结尾斜杠`() {
        assertEquals("/var/log", SftpPath.normalize("/var/log/"))
    }

    @Test
    fun `normalize 空串与斜杠都是根`() {
        assertEquals("/", SftpPath.normalize(""))
        assertEquals("/", SftpPath.normalize("/"))
        assertEquals("/", SftpPath.normalize("///"))
    }

    @Test
    fun `normalize 丢掉单点`() {
        assertEquals("/var/log", SftpPath.normalize("/var/./log/."))
    }

    @Test
    fun `normalize 处理双点`() {
        assertEquals("/var", SftpPath.normalize("/var/log/.."))
        assertEquals("/etc", SftpPath.normalize("/var/log/../../etc"))
    }

    @Test
    fun `normalize 的双点不能越过根`() {
        // 越过根拼出来的 /../etc 在有些服务端会被解释到 chroot 之外
        assertEquals("/", SftpPath.normalize("/.."))
        assertEquals("/", SftpPath.normalize("/a/../.."))
        assertEquals("/etc", SftpPath.normalize("/../../etc"))
    }

    @Test
    fun `normalize 把相对路径当作从根开始`() {
        // 返回值必须恒为绝对路径，否则调用方要各自猜
        assertEquals("/var/log", SftpPath.normalize("var/log"))
    }

    @Test
    fun `normalize 不动 a 点点 b 这种合法文件名`() {
        assertEquals("/tmp/a..b", SftpPath.normalize("/tmp/a..b"))
        assertEquals("/tmp/...", SftpPath.normalize("/tmp/..."))
        assertEquals("/tmp/..hidden", SftpPath.normalize("/tmp/..hidden"))
    }

    @Test
    fun `normalize 保留空格与中文`() {
        assertEquals("/home/u/我的 文档", SftpPath.normalize("/home/u//我的 文档/"))
    }

    @Test
    fun `normalize 保留反斜杠`() {
        // 反斜杠在 Linux 上是普通字符，java_io_File 会把它当分隔符——这正是不用它的原因
        assertEquals("/tmp/a\\b", SftpPath.normalize("/tmp/a\\b"))
    }

    // ---- join -----------------------------------------------------------------

    @Test
    fun `join 普通拼接`() {
        assertEquals("/var/log", SftpPath.join("/var", "log"))
    }

    @Test
    fun `join 根目录下不出现双斜杠`() {
        assertEquals("/etc", SftpPath.join("/", "etc"))
    }

    @Test
    fun `join 容忍 base 的结尾斜杠`() {
        assertEquals("/var/log", SftpPath.join("/var/", "log"))
    }

    @Test
    fun `join 遇到绝对路径的 name 就丢掉 base`() {
        // POSIX 语义：符号链接的 target 经常是绝对路径
        assertEquals("/etc/passwd", SftpPath.join("/var/log", "/etc/passwd"))
    }

    @Test
    fun `join 的双点等于上一级`() {
        assertEquals("/a", SftpPath.join("/a/b", ".."))
    }

    @Test
    fun `join 空名字回到 base`() {
        assertEquals("/a/b", SftpPath.join("/a/b", ""))
    }

    @Test
    fun `join 带空格与中文的名字`() {
        assertEquals("/home/u/新建 文件.txt", SftpPath.join("/home/u", "新建 文件.txt"))
    }

    @Test
    fun `join 支持多级相对路径`() {
        // SafTree 遍历产出的文件路径带子目录（如 css/app.css），join 要一次性吃下整段
        assertEquals("/srv/www/css/app.css", SftpPath.join("/srv/www", "css/app.css"))
    }

    @Test
    fun `join 不拦路径逃逸——isValidName 是唯一防线`() {
        // 记录现状：join 本身不做逃逸检查，靠调用方对遍历出的每一段名字先过 isValidName
        // （SafTree 正是这么做的）。这条丢了就是路径穿越——以后有人想删 isValidName 会红。
        assertEquals("/etc/passwd", SftpPath.join("/srv/www", "../../etc/passwd"))
    }

    // ---- parent ---------------------------------------------------------------

    @Test
    fun `parent 取上一级`() {
        assertEquals("/var", SftpPath.parent("/var/log"))
    }

    @Test
    fun `parent 到根就停在根`() {
        assertEquals("/", SftpPath.parent("/var"))
        assertEquals("/", SftpPath.parent("/"))
        assertEquals("/", SftpPath.parent(""))
    }

    @Test
    fun `parent 先规范化再取`() {
        assertEquals("/var", SftpPath.parent("/var//log/"))
    }

    // ---- name -----------------------------------------------------------------

    @Test
    fun `name 取末段`() {
        assertEquals("nginx.conf", SftpPath.name("/etc/nginx/nginx.conf"))
        assertEquals("log", SftpPath.name("/var/log/"))
    }

    @Test
    fun `name 根没有名字`() {
        assertEquals("", SftpPath.name("/"))
        assertEquals("", SftpPath.name(""))
    }

    // ---- crumbs ---------------------------------------------------------------

    @Test
    fun `crumbs 根只有一级`() {
        val crumbs = SftpPath.crumbs("/")
        assertEquals(1, crumbs.size)
        assertEquals("/", crumbs[0].path)
    }

    @Test
    fun `crumbs 逐级给出可跳转的绝对路径`() {
        val crumbs = SftpPath.crumbs("/var/log/nginx/")
        assertEquals(listOf("/", "var", "log", "nginx"), crumbs.map { it.name })
        assertEquals(listOf("/", "/var", "/var/log", "/var/log/nginx"), crumbs.map { it.path })
    }

    // ---- isHidden / isValidName ------------------------------------------------

    @Test
    fun `isHidden 认点开头`() {
        assertTrue(SftpPath.isHidden(".bashrc"))
        assertFalse(SftpPath.isHidden("bashrc"))
        assertFalse(SftpPath.isHidden("a.txt"))
    }

    @Test
    fun `isValidName 拒绝空与空白`() {
        assertFalse(SftpPath.isValidName(""))
        assertFalse(SftpPath.isValidName("   "))
        assertFalse(SftpPath.isValidName("\t"))
    }

    @Test
    fun `isValidName 拒绝点与双点`() {
        assertFalse(SftpPath.isValidName("."))
        assertFalse(SftpPath.isValidName(".."))
    }

    @Test
    fun `isValidName 拒绝带路径分隔符的名字`() {
        // 放行的话「新建目录」就能在任意位置造目录
        assertFalse(SftpPath.isValidName("a/b"))
        assertFalse(SftpPath.isValidName("/etc"))
        assertFalse(SftpPath.isValidName("../escape"))
    }

    @Test
    fun `isValidName 拒绝 NUL`() {
        // NUL 在 SFTP 协议层会把名字截断，服务端看到的和用户输入的不是一个名字
        assertFalse(SftpPath.isValidName("a\u0000b"))
    }

    @Test
    fun `isValidName 放行空格中文与点开头`() {
        // 拿「只允许 ASCII」去卡用户，等于在中文环境下废掉重命名
        assertTrue(SftpPath.isValidName("我的 文档.txt"))
        assertTrue(SftpPath.isValidName(".bashrc"))
        assertTrue(SftpPath.isValidName("a..b"))
        assertTrue(SftpPath.isValidName("..."))
        assertTrue(SftpPath.isValidName("a\\b"))
    }

    // ---- humanSize -------------------------------------------------------------

    @Test
    fun `humanSize 字节级不带小数`() {
        assertEquals("0 B", SftpPath.humanSize(0))
        assertEquals("1023 B", SftpPath.humanSize(1023))
    }

    @Test
    fun `humanSize 进位边界`() {
        assertEquals("1.0 KiB", SftpPath.humanSize(1024))
        assertEquals("1.5 KiB", SftpPath.humanSize(1536))
        assertEquals("1.0 MiB", SftpPath.humanSize(1024L * 1024))
    }

    @Test
    fun `humanSize 极端值不溢出也不崩`() {
        assertEquals("8192.0 PiB", SftpPath.humanSize(Long.MAX_VALUE))
        // stat 理论上不会给负数，但畸形服务端真给了也得有个显示
        assertEquals("0 B", SftpPath.humanSize(-1))
    }

    // ---- 列表与传输模型 ---------------------------------------------------------

    @Test
    fun `sortedForDisplay 目录在前且忽略大小写`() {
        val entries = listOf(
            entry("apple", RemoteFileType.FILE),
            entry("Zebra", RemoteFileType.FILE),
            entry("bin", RemoteFileType.DIRECTORY),
            entry("Apps", RemoteFileType.DIRECTORY),
        )
        assertEquals(
            listOf("Apps", "bin", "apple", "Zebra"),
            entries.sortedForDisplay().map { it.name },
        )
    }

    @Test
    fun `looksBinary 只看 NUL`() {
        assertTrue(looksBinary(byteArrayOf(0x41, 0x00, 0x42)))
        assertFalse(looksBinary("中文 text\n".toByteArray()))
        assertFalse(looksBinary(ByteArray(0)))
    }

    @Test
    fun `传输进度总长未知时没有百分比`() {
        val unknown = transfer(transferred = 100, total = 0)
        assertNull(unknown.ratio)
        assertEquals(0.5f, transfer(transferred = 512, total = 1024).ratio!!, 0.001f)
        // 服务端报的长度偶尔小于实际传输量，进度条不能画出格
        assertEquals(1f, transfer(transferred = 2048, total = 1024).ratio!!, 0.001f)
    }

    @Test
    fun `传输终态才算结束`() {
        assertTrue(transfer(status = TransferStatus.QUEUED).active)
        assertTrue(transfer(status = TransferStatus.RUNNING).active)
        assertFalse(transfer(status = TransferStatus.DONE).active)
        assertFalse(transfer(status = TransferStatus.FAILED).active)
        assertFalse(transfer(status = TransferStatus.CANCELLED).active)
    }

    /**
     * 「输入到终端」填进去的是这个函数的输出，它写错了不是排版问题——
     * 一个叫 `$(reboot)` 的文件原样填进 shell 是会被执行的。
     */
    @Test
    fun `干净路径不加引号`() {
        assertEquals("/var/log/syslog", SftpPath.shellQuote("/var/log/syslog"))
        assertEquals("/opt/a-b_c.2/x@y+z%1=2,3:4", SftpPath.shellQuote("/opt/a-b_c.2/x@y+z%1=2,3:4"))
    }

    @Test
    fun `shell 语法字符一律包进单引号`() {
        assertEquals("'/tmp/my file.txt'", SftpPath.shellQuote("/tmp/my file.txt"))
        assertEquals("'/tmp/\$(reboot)'", SftpPath.shellQuote("/tmp/\$(reboot)"))
        assertEquals("'/tmp/a;rm -rf b'", SftpPath.shellQuote("/tmp/a;rm -rf b"))
        assertEquals("'/tmp/a*'", SftpPath.shellQuote("/tmp/a*"))
        assertEquals("'/tmp/`x`'", SftpPath.shellQuote("/tmp/`x`"))
        // 中文名走的也是加引号这条路：安全，且引号不影响 shell 拿到的结果
        assertEquals("'/tmp/日志.txt'", SftpPath.shellQuote("/tmp/日志.txt"))
    }

    /** 单引号在单引号里没法转义，只能断开再拼——写错这一条就是把引号闭早了，后半截当命令跑。 */
    @Test
    fun `名字里的单引号断开再拼`() {
        assertEquals("'/tmp/it'\\''s'", SftpPath.shellQuote("/tmp/it's"))
        assertEquals("''", SftpPath.shellQuote(""))
    }

    private fun entry(name: String, type: RemoteFileType) = RemoteEntry(
        name = name,
        path = SftpPath.join("/tmp", name),
        type = type,
        sizeBytes = 0,
        modifiedEpochSeconds = 0,
        permissions = 0,
    )

    private fun transfer(
        transferred: Long = 0,
        total: Long = 0,
        status: TransferStatus = TransferStatus.RUNNING,
    ) = Transfer(
        id = "t",
        hostId = "h",
        hostName = "host",
        direction = TransferDirection.UPLOAD,
        name = "f.bin",
        transferredBytes = transferred,
        totalBytes = total,
        status = status,
    )
}
