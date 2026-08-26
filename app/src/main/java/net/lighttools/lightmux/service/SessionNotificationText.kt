package net.lighttools.lightmux.service

/**
 * 前台服务通知的正文拼装。**纯逻辑，不碰 Context**。
 *
 * 抽出来的理由：Service 在本机测不了（没有真机与 instrumentation 环境），
 * 而「0 个传输时不该出现『0 个传输』」这类分段规则恰恰是最容易写错、
 * 又会被用户天天看见的部分。文案本身仍走 strings.xml，由调用方注入。
 */
object SessionNotificationText {

    /** 中英都读得通的分隔符，和主页的会话副标题保持一致。 */
    const val SEPARATOR = " · "

    /**
     * @param sessionText  格式化「N 个会话」，由调用方从 strings.xml 取
     * @param transferText 格式化「M 个传输」
     * @param forwardText  格式化「K 条端口转发」
     * @return 数量为 0 的段直接不出现；三段都为 0 时返回空串（此时服务本就该停了）
     */
    fun content(
        sessions: Int,
        transfers: Int,
        forwards: Int,
        sessionText: (Int) -> String,
        transferText: (Int) -> String,
        forwardText: (Int) -> String,
    ): String = buildList {
        if (sessions > 0) add(sessionText(sessions))
        if (transfers > 0) add(transferText(transfers))
        if (forwards > 0) add(forwardText(forwards))
    }.joinToString(SEPARATOR)
}
