package net.lighttools.lightmux.web

/**
 * 地址栏那点输入的规范化。
 *
 * 单独抽出来是因为这里全是「用户打了半截地址，他想要什么」的判断，而它恰好是纯逻辑、能自测
 * （CLAUDE.md 架构要点 ⑤）。
 */
object WebUrl {

    /** 转发落地的地方。与 `ForwardSpec.LOOPBACK` 同一个地址——那边用来绑口，这边用来拼 URL。 */
    const val LOOPBACK = "127.0.0.1"

    const val MAX_PORT = 65535

    /**
     * 把地址栏里的东西变成能喂给 WebView 的 URL；空输入返回 null。
     *
     * 三条规则都是照着「这个浏览器是拿来看转发端口的」定的：
     * - **纯数字 = 端口号**：`3000` → `http://127.0.0.1:3000`。转发出来的服务全在环回口上，
     *   在手机上敲四个数字比敲完整 URL 快得多，而「3000」在这个语境里不可能是别的意思。
     * - **没写协议一律补 `http://`**，不补 https：本地起的 dev server 几乎没有配证书的，
     *   补成 https 会让最常见的那条路直接握手失败。
     * - **写了 `scheme://` 就原样交出去**，认不认由 WebView 那侧决定。判据要求带 `//` 是有意的：
     *   `myhost:8080` 里的冒号是端口号不是协议，按协议处理就成了一个打不开的地址。
     */
    fun normalize(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        if (text.all { it.isDigit() }) {
            val port = text.toIntOrNull() ?: return null
            return if (port in 1..MAX_PORT) "http://$LOOPBACK:$port" else null
        }
        if (SCHEME.containsMatchIn(text)) return text
        return "http://$text"
    }

    /**
     * 地址栏上显示的样子：砍掉 `http://` 与末尾那条孤零零的 `/`。
     *
     * **只砍 http**：https 前缀是安全信息，砍掉等于把「这条连接是加密的」抹成「不知道」。
     * 手机上这一行本来就窄，省下的七个字符正好多露出一截路径。
     */
    fun display(url: String): String {
        val stripped = url.removePrefix("http://")
        return if (stripped.count { it == '/' } == 1 && stripped.endsWith("/")) {
            stripped.dropLast(1)
        } else {
            stripped
        }
    }

    /** `scheme:` 开头才算写了协议。`foo:8080/bar` 这种半截地址里的冒号不该被误认成协议。 */
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
}
