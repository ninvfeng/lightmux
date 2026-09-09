package net.lighttools.lightmux.ui.host

import net.lighttools.lightmux.data.AuthMethod
import net.lighttools.lightmux.data.Host
import net.lighttools.lightmux.data.SshKey
import java.util.UUID

enum class AuthKind { Password, PrivateKey }

enum class HostFormError { Hostname, Port, Username, Secret }

/**
 * 主机编辑表单的**纯数据**表示：全部字段都是字符串，和输入框一一对应。
 *
 * 校验与「表单 → Host」的转换写成无 Android 依赖的纯函数，理由和别处一样：
 * 本机没有真机，能自测的只有这层（PRD §6.2）。端口越界这类错全在这里拦住，
 * Compose 那边只负责把错误码翻译成文案。
 */
data class HostForm(
    /** null = 新建。 */
    val id: String? = null,
    val name: String = "",
    val hostname: String = "",
    val port: String = "22",
    val username: String = "",
    val authKind: AuthKind = AuthKind.Password,
    val password: String = "",
    val pem: String = "",
    val passphrase: String = "",
    /**
     * 沿用已保存的凭据。
     *
     * 编辑已有主机时**不回显密码明文**，输入框留空 + 占位文案；只要用户没重新输入，
     * 保存时就把原来的 [AuthMethod] 原样带过去。切换认证方式会让它失效（没有可沿用的东西了）。
     */
    val keepSecret: Boolean = false,
    /** 非空 = 私钥取自密钥库里的这把钥匙，[pem] 与 [passphrase] 不再参与。 */
    val keyId: String? = null,
    val loginCommand: String = "",
    /** 非空 = 经这台主机跳一下（`ssh -J`），见 [net.lighttools.lightmux.data.HostRoute]。 */
    val proxyJumpId: String? = null,
) {

    /** 私钥来自密钥库时表单里没有任何凭据要填——PEM 在钥匙那边，改也是去那边改。 */
    private val usesStoredKey: Boolean get() = authKind == AuthKind.PrivateKey && keyId != null

    fun validate(): Set<HostFormError> = buildSet {
        if (hostname.isBlank()) add(HostFormError.Hostname)
        if (port.trim().toIntOrNull() !in 1..65535) add(HostFormError.Port)
        if (username.isBlank()) add(HostFormError.Username)
        if (!keepSecret && !usesStoredKey) {
            val secret = if (authKind == AuthKind.Password) password else pem
            if (secret.isBlank()) add(HostFormError.Secret)
        }
    }

    /**
     * 落库前的转换。调用方须保证 [validate] 为空。
     *
     * @param existing **凭据的来源**，不一定是「要覆盖的那条记录」：[keepSecret] 为真时从这里
     *                 取回旧凭据，而复制出来的表单也拿源主机当来源（见 [copyOf]）
     */
    fun toHost(existing: Host? = null): Host {
        val auth = when (authKind) {
            AuthKind.Password ->
                (existing?.auth as? AuthMethod.Password)?.takeIf { keepSecret }
                    ?: AuthMethod.Password(password)

            // 引用密钥库时只带 id 落库，PEM 由 HostStore 读取时装配，这里给空串
            AuthKind.PrivateKey -> keyId?.let { AuthMethod.PrivateKey("", null, keyId = it) }
                ?: (existing?.auth as? AuthMethod.PrivateKey)?.takeIf { keepSecret && it.keyId == null }
                ?: AuthMethod.PrivateKey(pem.trim(), passphrase.ifBlank { null })
        }
        val cleanHostname = hostname.trim()
        return Host(
            // 身份只认表单自己的 id，**不看 [existing]**：编辑时它由 [of] 从原记录带过来，
            // 而复制时它被清成 null，必须落到一个新 UUID 上——这里退一步取 existing.id，
            // 「复制」就变成了「覆盖源主机」。existing 在这个函数里只负责凭据。
            id = id ?: UUID.randomUUID().toString(),
            // 名称留空就拿主机名顶上：列表里总得有东西可显示，逼用户起名字没必要。
            name = name.trim().ifBlank { cleanHostname },
            hostname = cleanHostname,
            port = port.trim().toInt(),
            username = username.trim(),
            auth = auth,
            loginCommand = loginCommand.trim().ifBlank { null },
            proxyJumpId = proxyJumpId,
        )
    }

    /**
     * 「测试连接」用的临时 [Host]，**不落库**。
     *
     * 和 [toHost] 只差密钥库那把钥匙的装配：落库时主机身上只写一个 keyId，PEM 由
     * [net.lighttools.lightmux.data.HostStore] 读取时装配进来；而这里要连的是一份**还没进过库**
     * 的表单，没人替它装，不自己装就是拿一把空私钥去撞服务端。
     */
    fun toTestHost(existing: Host? = null, keys: List<SshKey> = emptyList()): Host {
        val host = toHost(existing)
        val auth = host.auth
        if (auth !is AuthMethod.PrivateKey || auth.keyId == null) return host
        val key = keys.firstOrNull { it.id == auth.keyId }
        return host.copy(
            auth = auth.copy(
                pem = key?.pem.orEmpty(),
                passphrase = key?.passphrase,
                // 钥匙被删了或它自己的密文解不开：和 StoreCodec.decodeAuth 走同一条路，
                // 让失败说成「凭据解不开」而不是「认证失败」——后者会把人送去查用户名密码。
                credentialLost = key == null || key.broken,
            ),
        )
    }

    companion object {

        fun of(host: Host): HostForm = HostForm(
            id = host.id,
            name = host.name,
            hostname = host.hostname,
            port = host.port.toString(),
            username = host.username,
            authKind = if (host.auth is AuthMethod.PrivateKey) AuthKind.PrivateKey else AuthKind.Password,
            // 明文一律不进表单，连 State 都不放：截屏、无障碍读屏、状态恢复都可能把它抖出去。
            password = "",
            pem = "",
            passphrase = "",
            keepSecret = host.hasSecret,
            keyId = (host.auth as? AuthMethod.PrivateKey)?.keyId,
            loginCommand = host.loginCommand.orEmpty(),
            proxyJumpId = host.proxyJumpId,
        )

        /**
         * 以一台主机为模板新建一台：字段照搬，但 id 清成 null，保存时才落到新 UUID 上。
         *
         * 凭据也沿用源主机的（[keepSecret]）——一台机器上开两个账号、或同一个跳板机后面
         * 一串配置相同的机器，是「复制」唯一的用处，逼用户把密码或整段 PEM 再输一遍就白复制了。
         *
         * @param taken 已有的主机名。列表就是靠名字认人的，复制出来的两行同名等于认不出来
         */
        fun copyOf(host: Host, taken: Collection<String>): HostForm =
            of(host).copy(id = null, name = uniqueName(host.name, taken))

        /** `web` → `web 2` → `web 3`。 */
        private fun uniqueName(base: String, taken: Collection<String>): String {
            if (base !in taken) return base
            var index = 2
            while ("$base $index" in taken) index++
            return "$base $index"
        }

        /** 凭据解密失败时 [Host.auth] 会退化成空串，那种情况必须逼用户重填，不能假装「已保存」。 */
        private val Host.hasSecret: Boolean
            get() = when (val a = auth) {
                is AuthMethod.Password -> a.password.isNotEmpty()
                // 引用密钥库的不算「已保存的凭据」：钥匙本身就在下拉框里明摆着，
                // 再显示一句「已保存，留空表示不修改」只会让人以为表单里还藏着一份 PEM。
                is AuthMethod.PrivateKey -> a.keyId == null && a.pem.isNotEmpty()
                AuthMethod.Agent -> false
            }
    }
}
