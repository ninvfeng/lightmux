package net.lighttools.lightmux.ssh

import android.util.Log
import com.termux.terminal.TerminalTransport
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [TerminalTransport] 的 SSH 实现：把一条 shell channel 的字节流接到终端内核上。
 *
 * 生命周期与 [connection] 绑定但**不共享**：一个 SshTransport 只服务一次连接，
 * 断线重连由上层造一条新的 [SshConnection] + 新的 SshTransport，再走
 * `TerminalSession.reconnect()` 换上去（这样滚屏历史才能保住）。
 *
 * @param connection 尚未连接的连接对象。connect 放在 [start] 里做，因为 start 本来就允许阻塞
 * @param loginCommand 登录后自动执行的命令。M2 的 tmux attach 也从这里进
 * @param onConnected shell 就绪时回调一次。终端字节流本身不区分「还没连上」和「连上了但没输出」，
 *                    UI 想把「连接中…」换掉只能靠这个信号
 */
class SshTransport(
    val connection: SshConnection,
    private val loginCommand: String? = null,
    private val onConnected: (() -> Unit)? = null,
) : TerminalTransport {

    private val closed = AtomicBoolean(false)
    private val notified = AtomicBoolean(false)

    @Volatile
    private var shell: ShellChannel? = null

    /** 尺寸可能在 shell 建起来之前就到（TerminalView 先测量完），先记下来，建 PTY 时直接用对的值。 */
    @Volatile
    private var columns = 80

    @Volatile
    private var rows = 24

    private val writeLock = Any()

    /**
     * 最后一次失败原因。
     *
     * [TerminalTransport.Listener.onClosed] 只能带一个字符串，而 UI 要按异常**类型**分流
     * （[HostKeyChangedException] 要弹告警对话框，认证失败要跳去改凭据），字符串没法承载这个信息。
     */
    @Volatile
    var failure: Throwable? = null
        private set

    override fun start(columns: Int, rows: Int, listener: TerminalTransport.Listener) {
        this.columns = columns
        this.rows = rows
        var exitCode = 0
        var message: String? = null
        var stderrPump: Thread? = null
        try {
            connection.connectBlocking()
            if (closed.get()) throw IOException("closed while connecting")

            val channel = connection.openShell(this.columns, this.rows)
            shell = channel
            // 建 channel 期间可能来过 updateSize，补一次，否则远端会一直用建连时的旧尺寸。
            channel.resize(this.columns, this.rows)

            onConnected?.invoke()
            loginCommand?.takeIf { it.isNotBlank() }?.let { write("$it\n".toByteArray()) }

            // stderr 单开一条线程：和 stdout 交替阻塞读会漏数据，而 SSH 的 stderr
            // 是独立数据流（extended data），远端写它时不会等 stdout 被读走。
            stderrPump = pump(channel.errorInput, listener, "SshTransportStderr")

            copy(channel.input, listener)
            channel.awaitClose()
            exitCode = channel.exitStatus ?: 0
        } catch (e: Throwable) {
            // 用户主动关掉时的异常是预期内的，不必让它冒到终端上吓人。
            if (!closed.get()) {
                Log.w(TAG, "ssh transport failed", e)
                failure = e
                message = e.message ?: e.javaClass.simpleName
                exitCode = 1
            }
        } finally {
            stderrPump?.interrupt()
            cleanup()
            // 契约要求：无论正常退出、异常还是被 close，onClosed 必须且只能回调一次。
            // 少一次，UI 会永远停在「连接中」；多一次，重连状态机会被推两格。
            if (notified.compareAndSet(false, true)) {
                listener.onClosed(exitCode, message)
            }
        }
    }

    override fun write(data: ByteArray, offset: Int, count: Int) {
        val out = shell?.output ?: return
        try {
            // OutputStream 不保证线程安全，而按键来自主线程、自动命令来自 start 线程。
            synchronized(writeLock) {
                out.write(data, offset, count)
                out.flush()
            }
        } catch (e: IOException) {
            if (!closed.get()) Log.w(TAG, "write failed", e)
        }
    }

    private fun write(data: ByteArray) = write(data, 0, data.size)

    override fun updateSize(columns: Int, rows: Int) {
        if (columns <= 0 || rows <= 0) return
        this.columns = columns
        this.rows = rows
        // 调用方是主线程（`TerminalView.onSizeChanged`，键盘弹出/收起就会来一次），
        // 而同步窗口尺寸要发一个 SSH 包。**主线程上发这个包会把整条连接搞坏**，详见 [SshIo]。
        // 甩到后台后重读字段而不是用参数：两次 resize 可能并发落地，读最新值才能保证最终尺寸是对的。
        SshIo.quietly { shell?.resize(this.columns, this.rows) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val channel = shell
        // 关 channel 让阻塞在 read 上的 start 线程立刻返回，它会自己走完 finally。
        // 同样甩开主线程：本方法会被终端内核在主线程 Handler 里调到（收尾与重连各一次）。
        SshIo.quietly { channel?.close() }
        connection.close()
    }

    private fun cleanup() {
        closed.set(true)
        shell?.close()
        shell = null
        connection.close()
    }

    private fun pump(source: InputStream, listener: TerminalTransport.Listener, name: String): Thread =
        Thread({ copy(source, listener) }, name).apply { isDaemon = true; start() }

    /** 读到 EOF 或出错为止。Listener 承诺同步消费，所以读缓冲可以原样传下去、循环复用。 */
    private fun copy(source: InputStream, listener: TerminalTransport.Listener) {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            while (true) {
                val read = source.read(buffer)
                if (read < 0) return
                if (read > 0) listener.onOutput(buffer, 0, read)
            }
        } catch (e: Exception) {
            // channel 关闭时读端一定会抛，这里不区分正常与异常，由 start 的 finally 统一收尾。
        }
    }

    private companion object {
        const val TAG = "SshTransport"

        /** 和 TerminalSession 的 ByteQueue 容量一致，写不进去时正好一次读满一整块。 */
        const val BUFFER_SIZE = 4096
    }
}
