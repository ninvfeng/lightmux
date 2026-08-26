package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A terminal session, consisting of a terminal emulator coupled to a byte transport.
 * <p>
 * [lightmux] 相对上游的改动：进程/PTY 相关部分（forkpty、waitpid、SIGKILL、/proc 读 cwd）全部移除，
 * 换成 {@link TerminalTransport}。终端仿真、队列与主线程回调的结构保持上游原样。
 * <p>
 * NOTE: The terminal session may outlive the TerminalView, so be careful with callbacks!
 */
public final class TerminalSession extends TerminalOutput {

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_TRANSPORT_CLOSED = 4;

    private static final String LOG_TAG = "TerminalSession";

    /**
     * [lightmux] 断线时写入终端缓冲的提示文案，由 app 层按当前语言注入。
     * 放静态字段而非 strings.xml，是因为 vendored 模块不该依赖 app 的资源。
     */
    public static String sDisconnectedLabel = "连接已断开";

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /**
     * A queue written to from a separate thread when the transport outputs, and read by main thread to process by
     * terminal emulator.
     *
     * <p>[lightmux] 非 final：{@link #reconnect} 会整条换新，见那里的注释。</p>
     */
    volatile ByteQueue mProcessToTerminalIOQueue = new ByteQueue(4096);
    /**
     * A queue written to from the main thread due to user interaction, and read by another thread which forwards by
     * writing to the transport.
     *
     * <p>[lightmux] 非 final，理由同上。</p>
     */
    volatile ByteQueue mTerminalToProcessIOQueue = new ByteQueue(4096);
    /** Buffer to write translate code points into utf8 before writing to mTerminalToProcessIOQueue */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Callback which gets notified when a session finishes or changes title. */
    TerminalSessionClient mClient;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler(Looper.getMainLooper());

    private final Integer mTranscriptRows;

    /** [lightmux] 当前传输通道。重连时会被换掉，故非 final。 */
    private TerminalTransport mTransport;

    /** [lightmux] 通道是否在运行。取代上游的 mShellPid > 0 判定。 */
    private boolean mRunning;

    /** [lightmux] 通道退出码，仅在 !{@link #isRunning()} 时有效。 */
    private int mExitStatus;

    /** [lightmux] 写线程的代数：重连后旧线程可能仍在收尾，用它区分谁是当前线程。 */
    private int mGeneration;

    public TerminalSession(TerminalTransport transport, Integer transcriptRows, TerminalSessionClient client) {
        this.mTransport = transport;
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /** Inform the transport of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows);
        } else {
            // [lightmux] 已有 emulator 时只 resize，绝不重建 —— 重建会丢滚屏历史。
            mTransport.updateSize(columns, rows);
            mEmulator.resize(columns, rows);
        }
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /**
     * Set the terminal emulator's window size and start terminal emulation.
     *
     * @param columns The number of columns in the terminal window.
     * @param rows    The number of rows in the terminal window.
     */
    public void initializeEmulator(int columns, int rows) {
        mEmulator = new TerminalEmulator(this, columns, rows, mTranscriptRows, mClient);
        startTransport(columns, rows);
    }

    /**
     * [lightmux] 换一条传输通道重新连接，<b>保留现有 emulator 与滚屏历史</b>。
     * 断线重连走这里：用户看到的是历史之上接着刷新，而不是清屏重来。
     *
     * @param transport 新通道；调用方需保证旧通道已关闭
     */
    public void reconnect(TerminalTransport transport) {
        if (mEmulator == null) {
            // 还没初始化过，等 TerminalView 测量完尺寸后自然会 initializeEmulator。
            mTransport = transport;
            return;
        }
        mTransport.close();
        mTransport = transport;
        synchronized (this) {
            mExitStatus = 0;
        }
        // [lightmux] 上一次断线时 cleanupResources 把两条队列都关了，不换新的话新通道的字节
        // 全被 ByteQueue.write 丢掉，用户看到的是一个再也不动的终端。
        //
        // 换代而不是给 ByteQueue 加个 reopen()，是因为重开有竞态：close() 只是置 mOpen=false 再
        // notify()，被唤醒的旧 writer 线程还要重抢 monitor 锁才能继续；若这中间队列已被重开，它
        // 醒来时 `mStoredBytes == 0 && mOpen` 依然成立，于是接着 wait 而不是返回 -1 退出。队列上
        // 就留下了两个消费者，重连后用户的第一次按键有可能被旧线程取走，走到代数检查时数据已经
        // 出队，只能静默丢掉——表现为重连后偶发丢一次输入。
        // 换代则把这件事变成结构性的：旧线程绑定的旧队列永远是 closed，它必然读到 -1 自行退出。
        mProcessToTerminalIOQueue = new ByteQueue(4096);
        mTerminalToProcessIOQueue = new ByteQueue(4096);
        startTransport(mEmulator.mColumns, mEmulator.mRows);
    }

    /** [lightmux] 起传输线程与写线程。取代上游 initializeEmulator 里的三个 fork/pty 线程。 */
    private void startTransport(int columns, int rows) {
        final TerminalTransport transport = mTransport;
        // [lightmux] 两条队列在这里快照成局部量：下面两个线程整条命都只碰自己这一代的队列，
        // 重连换代时它们读到的还是旧队列（已 closed），于是自然收尾，不会去动新连接的数据。
        final ByteQueue processToTerminal = mProcessToTerminalIOQueue;
        final ByteQueue terminalToProcess = mTerminalToProcessIOQueue;
        final int generation;
        synchronized (this) {
            mRunning = true;
            generation = ++mGeneration;
        }

        new Thread("TermSessionTransport[" + generation + "]") {
            @Override
            public void run() {
                transport.start(columns, rows, new TerminalTransport.Listener() {
                    @Override
                    public void onOutput(byte[] data, int offset, int count) {
                        if (!processToTerminal.write(data, offset, count)) return;
                        mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
                    }

                    @Override
                    public void onClosed(int exitCode, String message) {
                        synchronized (TerminalSession.this) {
                            // 旧代的通道收尾时不许覆盖新连接的状态。
                            if (generation != mGeneration) return;
                        }
                        // [lightmux] 代数要带进消息里，因为这里的检查只挡得住「发之前就已换代」。
                        // reconnect() 会先 close 掉还活着的旧传输，旧线程可能恰好赶在 mGeneration++
                        // 之前通过上面的检查、把消息塞进队列；等主线程取出来时早已是新的一代，
                        // 而 cleanupResources 关的是字段当前值——那就成了新连接刚建好就被自己关掉。
                        Message msg = mMainThreadHandler.obtainMessage(MSG_TRANSPORT_CLOSED, exitCode, generation, message);
                        mMainThreadHandler.sendMessage(msg);
                    }
                });
            }
        }.start();

        new Thread("TermSessionOutputWriter[" + generation + "]") {
            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                while (true) {
                    int bytesToWrite = terminalToProcess.read(buffer, true);
                    if (bytesToWrite == -1) return;
                    synchronized (TerminalSession.this) {
                        if (generation != mGeneration) return;
                    }
                    transport.write(buffer, 0, bytesToWrite);
                }
            }
        }.start();
    }

    /** Write data to the transport. */
    @Override
    public void write(byte[] data, int offset, int count) {
        // [lightmux] 这里读字段当前值（而非快照）是有意的：按键入口和 reconnect() 都在主线程上
        // 串行执行，不会交错，读到的必然是最新一代的队列——用户按下的键就该发给当前这条连接。
        if (isRunning()) mTerminalToProcessIOQueue.write(data, offset, count);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    /** [lightmux] Finish this session by closing the transport. 取代上游的 SIGKILL。 */
    public void finishIfRunning() {
        if (isRunning()) {
            mTransport.close();
        }
    }

    /** Cleanup resources when the transport closes. */
    void cleanupResources(int exitStatus) {
        synchronized (this) {
            mRunning = false;
            mExitStatus = exitStatus;
        }

        mTerminalToProcessIOQueue.close();
        mProcessToTerminalIOQueue.close();
        mTransport.close();
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        return mRunning;
    }

    /** Only valid if not {@link #isRunning()}. */
    public synchronized int getExitStatus() {
        return mExitStatus;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[4 * 1024];

        MainThreadHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            // [lightmux] 同样读字段当前值：本 handler 与 reconnect() 同在主线程串行。
            // 换代那一刻旧队列里可能还剩几个字节，丢掉即可——反正紧跟着就要打印断线提示。
            int bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false);
            if (bytesRead > 0) {
                mEmulator.append(mReceiveBuffer, bytesRead);
                notifyScreenUpdate();
            }

            if (msg.what == MSG_TRANSPORT_CLOSED) {
                // [lightmux] 第二道代数检查，理由见 onClosed 里的注释。
                // MSG_NEW_INPUT 不需要这道检查：它只是叫主线程去读当前队列，旧代发的顶多空读一次。
                synchronized (TerminalSession.this) {
                    if (msg.arg2 != mGeneration) return;
                }
                int exitCode = msg.arg1;
                String reason = (String) msg.obj;
                cleanupResources(exitCode);

                // [lightmux] 在终端里留一行可见的结束提示，措辞比上游的 "[Process completed]" 更贴近网络场景。
                StringBuilder exitDescription = new StringBuilder("\r\n[").append(sDisconnectedLabel);
                if (reason != null && !reason.isEmpty()) {
                    exitDescription.append(": ").append(reason);
                } else if (exitCode > 0) {
                    exitDescription.append(" (").append(exitCode).append(")");
                }
                exitDescription.append("]");

                byte[] bytesToWrite = exitDescription.toString().getBytes(StandardCharsets.UTF_8);
                mEmulator.append(bytesToWrite, bytesToWrite.length);
                notifyScreenUpdate();

                mClient.onSessionFinished(TerminalSession.this);
            }
        }

    }

}
