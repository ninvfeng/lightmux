package net.lighttools.lightmux.session

/**
 * 断线重连的退避状态机。
 *
 * 刻意不依赖任何 Android API：本机跑不了真机，纯 Kotlin 逻辑是唯一能自测的部分（见 PRD §6.3）。
 * 也刻意不加随机抖动——移动端同一时刻只有一个用户在重连，抖动带来的只有「测不准」这一个后果。
 *
 * @param baseDelayMs 首次退避
 * @param maxDelayMs  封顶。移动网络恢复通常在十几秒内，超过这个值再等下去只会让用户以为 app 死了
 */
class ReconnectPolicy(
    private val baseDelayMs: Long = 1_000,
    private val maxDelayMs: Long = 15_000,
) {

    /** 已经排过队的重连次数。UI 拿它显示「第 N 次重试」。 */
    var attempts: Int = 0
        private set

    /** 下一次是否跳过等待。仅由 [resetForNetworkRestored] 置位，消费一次即清。 */
    private var retryImmediately: Boolean = false

    /**
     * 取下一次重连前该等多久，并把计数推进一格。
     *
     * 序列（默认参数）：1s → 2s → 4s → 8s → 15s → 15s …
     */
    fun nextDelayMs(): Long {
        if (retryImmediately) {
            retryImmediately = false
            // 不计入 attempts：网络刚恢复这一枪是「白给」的，打空了也该从 1s 重新退避，
            // 而不是接着上一轮的指数往下走。
            return 0
        }
        // 左移位数必须夹住，否则 attempts 到 64 会绕回、delay 变成 baseDelayMs。
        val shift = attempts.coerceAtMost(MAX_SHIFT)
        attempts++
        val delay = baseDelayMs shl shift
        // 溢出后 delay 会变负，一并归到封顶值。
        return if (delay <= 0 || delay > maxDelayMs) maxDelayMs else delay
    }

    /** 连接成功后调用，让下一次断线从头退避。 */
    fun reset() {
        attempts = 0
        retryImmediately = false
    }

    /**
     * 网络恢复（如从飞行模式切回 Wi-Fi）时调用：既归零退避，也让**下一次** [nextDelayMs] 返回 0。
     *
     * 只归零是不够的——退避到 15s 时网络刚好恢复，用户仍要干等 1s 才看到重连，
     * 而系统已经明确告诉我们「现在可以连了」，这一秒纯属浪费。
     */
    fun resetForNetworkRestored() {
        attempts = 0
        retryImmediately = true
    }

    private companion object {
        /** 1s shl 20 ≈ 12 天，任何合理的 maxDelayMs 都早已封顶。 */
        const val MAX_SHIFT = 20
    }
}
