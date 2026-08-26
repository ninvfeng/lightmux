package com.termux.terminal;

/**
 * [lightmux] 终端的字节传输通道。
 * <p>
 * 上游 termux 的 {@link TerminalSession} 通过 JNI forkpty 绑定本地 PTY。lightmux 连的是远程主机，
 * 因此把「字节从哪来、到哪去」抽象成本接口：终端内核只管解析字节流，不关心它来自本地进程还是 SSH channel。
 * <p>
 * 线程约定：
 * <ul>
 * <li>{@link #start} 由 {@link TerminalSession} 在独立线程上调用，实现方应<b>阻塞</b>到通道结束
 *     （建立连接 → 循环读取 → 断开），期间通过 {@link Listener} 回灌数据。</li>
 * <li>{@link #write} / {@link #updateSize} / {@link #close} 可能从其他线程调用，实现方需自行保证线程安全。</li>
 * </ul>
 */
public interface TerminalTransport {

    /** 通道产生的事件回调。实现方可在任意线程调用，{@link TerminalSession} 内部会切回主线程。 */
    interface Listener {
        /** 收到远端输出。data 在本次调用返回后可能被复用，实现方必须同步消费。 */
        void onOutput(byte[] data, int offset, int count);

        /**
         * 通道结束。
         *
         * @param exitCode 远端退出码；未知时传 0
         * @param message  展示给用户的结束原因（如「连接超时」），可为 null
         */
        void onClosed(int exitCode, String message);
    }

    /**
     * 建立通道并阻塞读取，直到连接结束或 {@link #close()} 被调用。
     * 结束前必须调用一次 {@link Listener#onClosed}。
     */
    void start(int columns, int rows, Listener listener);

    /** 向远端写入字节。 */
    void write(byte[] data, int offset, int count);

    /** 通知远端窗口尺寸变化。 */
    void updateSize(int columns, int rows);

    /** 主动关闭通道，应使阻塞中的 {@link #start} 尽快返回。可重复调用。 */
    void close();
}
