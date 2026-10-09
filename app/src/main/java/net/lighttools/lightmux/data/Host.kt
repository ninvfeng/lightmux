package net.lighttools.lightmux.data

import java.util.UUID

/**
 * 一台可连接的主机。
 *
 * 注意：内存中的 [auth] 持有的是**明文**凭据（[HostStore] 读取时已解密），
 * 落盘前必须经 [Secrets] 加密——不要把 Host 直接序列化到任何地方。
 */
data class Host(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val hostname: String,
    val port: Int = 22,
    val username: String,
    val auth: AuthMethod,
    /** 登录后自动执行的命令（如 `tmux ls`）。M2 的 attach 走的是另一条路径，不复用这里。 */
    val loginCommand: String? = null,
    /**
     * 跳板机（`ssh -J`）：**主机表里另一台主机的 id**，连它之前先连这一台并从那里开隧道。
     *
     * 存 id 而不是另存一套地址与凭据，理由见 [HostRoute]。链的解析（多跳、成环、引用失效）
     * 也在那里，这里只是一条引用。
     */
    val proxyJumpId: String? = null,
) {
    /** 供 known_hosts 与 UI 展示用的 `user@host:port`。 */
    val endpoint: String get() = "$username@$hostname:$port"
}

sealed interface AuthMethod {

    /**
     * 落盘的密文解不开（Keystore 密钥没了：改锁屏方式、恢复出厂、跨设备恢复备份都会这样）。
     *
     * [HostStore] 遇到这种记录会把凭据退化成空串保住其余配置，但**必须把这件事传下去**：
     * 拿一个空密码去认证，服务端回的是普通的认证失败，用户于是被指去检查用户名和密码——
     * 而那里怎么改都没用，真正的出路是重新填一次凭据。
     */
    val credentialLost: Boolean get() = false

    /**
     * @param cipher 解不开的那段原始密文，**只在 [credentialLost] 为真时非空**。
     *               留着它是因为整张主机表是「解密 → 改一条 → 整表加密写回」的：
     *               重填 A 的密码会让 B 的密文被 `encrypt("")` 覆盖，丢失标记随之消失，
     *               下次连 B 就变成拿空密码去撞服务端。原样写回密文才能让往返无损。
     */
    data class Password(
        val password: String,
        override val credentialLost: Boolean = false,
        val cipher: String? = null,
    ) : AuthMethod

    /**
     * @param pem    OpenSSH 或 PKCS#8 格式的私钥全文，含 BEGIN/END 头尾
     * @param keyId  非空 = 私钥来自密钥库里的这把钥匙，主机自己不存 PEM。
     *               [pem] / [passphrase] 由 [HostStore] 读取时装配进来，连接层不必知道来源
     * @param pemCipher        解不开的 PEM 密文，理由同 [Password.cipher]
     * @param passphraseCipher 解不开的口令密文。两者分开存：私钥好好的、只有口令解不开，
     *                         那份完好的 PEM 仍该正常重新加密，不能一起冻住
     */
    data class PrivateKey(
        val pem: String,
        val passphrase: String?,
        val keyId: String? = null,
        override val credentialLost: Boolean = false,
        val pemCipher: String? = null,
        val passphraseCipher: String? = null,
    ) : AuthMethod

    /** 占位：V1 不实现 ssh-agent，认证时会抛 [UnsupportedOperationException]。 */
    data object Agent : AuthMethod

    /**
     * 真正实现的「无凭据」认证：SSH 协议里的 `none` 方法，密码和私钥都不问。
     *
     * 少数服务端本来就不需要认证（比如临时云开发环境的会话网关，身份信息已经编在用户名里），
     * 硬逼用户填一个密码或私钥占位既没有意义、连上去也没用。
     */
    data object None : AuthMethod
}
