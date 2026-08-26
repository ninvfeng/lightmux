package net.lighttools.lightmux.data

/**
 * 跳板机路由（等价于 `ssh -J`）的解析。
 *
 * 跳板机**引用主机表里已有的一台主机**，不在目标主机身上另存一份 host/port/user/凭据：
 * 堡垒机本来就是一台配好的机器，复制一份等于同一套凭据存两处，改密码要改两遍，
 * 而漏掉的那一份会以「连不上」的形式在几周后发作。引用之后 known_hosts、密钥库、
 * 凭据加密全都自动复用。
 *
 * 链是一串 `proxyJumpId` 顺着往上找出来的，因此天然支持多跳。但它也天然能绕成环
 * （A 的跳板是 B，B 的跳板是 A），解析必须自己防住——成环的表现不是报错，是连接线程死循环。
 *
 * 纯逻辑，无 Android 依赖，可单测（PRD §6.2）。
 */
object HostRoute {

    /**
     * 最多几台跳板。
     *
     * 不是技术限制，是防呆：真实场景里两跳已经很少见，一条五跳以上的链更可能是配错了，
     * 而每一跳都要完整握手 + 认证，链越长连接越慢、越脆。
     */
    const val MAX_HOPS = 5

    sealed interface Route {

        /** 直连，没有跳板。 */
        data object Direct : Route

        /**
         * 走跳板。[jumps] **按连接顺序**排列：先直连第一台，从它开隧道连第二台……
         * 最后一台负责开到目标主机的隧道。
         */
        data class Via(val jumps: List<Host>) : Route

        /** 引用的跳板机不在主机表里（多半是被删了）。带上 id 是为了让报错能说清是哪一条引用断了。 */
        data class Missing(val hostId: String) : Route

        /** 链绕回了自己。 */
        data object Cycle : Route

        /** 超过 [MAX_HOPS]。 */
        data object TooDeep : Route
    }

    /**
     * 解出 [target] 的跳板链。
     *
     * @param all 当前主机表。查不到的引用返回 [Route.Missing]，**不静默降级成直连**——
     *            那等于绕过跳板直接去撞目标地址，而目标多半是个内网地址，
     *            用户看到的会是一条莫名其妙的超时
     */
    fun resolve(target: Host, all: List<Host>): Route {
        val jumps = mutableListOf<Host>()
        val seen = mutableSetOf(target.id)
        var current = target
        while (true) {
            val nextId = current.proxyJumpId ?: break
            if (!seen.add(nextId)) return Route.Cycle
            if (jumps.size >= MAX_HOPS) return Route.TooDeep
            val jump = all.firstOrNull { it.id == nextId } ?: return Route.Missing(nextId)
            jumps += jump
            current = jump
        }
        // 收集顺序是「离目标最近的先出来」，连接顺序正好相反。
        return if (jumps.isEmpty()) Route.Direct else Route.Via(jumps.asReversed())
    }

    /**
     * [targetId] 这台主机能选哪些主机当跳板。
     *
     * 把会成环的选项**从选择器里去掉**，而不是让用户选完再报一个错：环是两台主机配合出来的，
     * 报错只能报在后配的那一台身上，而用户看着那台的表单是想不明白错在哪的。
     *
     * @param targetId 正在编辑的主机 id；新建时为 null，此时所有主机都可选
     */
    fun candidates(targetId: String?, all: List<Host>): List<Host> {
        if (targetId == null) return all
        return all.filter { it.id != targetId && !reaches(it, targetId, all) }
    }

    /** [from] 顺着跳板链能不能走到 [targetId]。表里已经有环时也必须能停下来。 */
    private fun reaches(from: Host, targetId: String, all: List<Host>): Boolean {
        val seen = mutableSetOf(from.id)
        var current = from
        while (true) {
            val nextId = current.proxyJumpId ?: return false
            if (nextId == targetId) return true
            if (!seen.add(nextId)) return false
            current = all.firstOrNull { it.id == nextId } ?: return false
        }
    }
}
