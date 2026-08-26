package net.lighttools.lightmux.data

/**
 * 落盘的 auth 对象原样搬进来的字段，**不带 org.json**。除 [type] / [keyId] 外都是密文。
 */
data class RawAuth(
    val type: String?,
    val keyId: String? = null,
    val password: String? = null,
    val pem: String? = null,
    val passphrase: String? = null,
)

/** 落盘的一把钥匙。[pem] / [passphrase] 是密文。 */
data class RawKey(
    val id: String,
    val name: String,
    val pem: String? = null,
    val passphrase: String? = null,
)

/**
 * [HostStore] / [SshKeyStore] 共用的**纯逻辑**：凭据编解码，以及读-改-写要不要写。
 *
 * 单独抽出来的理由和 [net.lighttools.lightmux.update.ReleaseParser] 一样：`org.json` 在 JVM 单测里
 * 是个只会抛 `Stub!` 的桩，[Secrets] 又要 Android Keystore，编解码混在 store 里就一行都测不了——
 * 而凭据往返写错的代价是用户的密码被静默清空。这里只做判断，加解密从外面注入，JSON 留在 store。
 */
object StoreCodec {

    const val TYPE_PASSWORD = "password"
    const val TYPE_KEY = "key"
    const val TYPE_AGENT = "agent"

    /**
     * 读-改-写里「该不该写」的那一步。
     *
     * @param raw    落盘的原始字符串，null / 空白 = 本来就没数据，拿空列表当基线是对的
     * @param parse  解析原始字符串，返回 null 表示**解析失败**
     * @return 待写入的字符串；null = 放弃这次写入
     */
    fun <T> rewrite(
        raw: String?,
        parse: (String) -> List<T>?,
        transform: (List<T>) -> List<T>,
        encode: (List<T>) -> String,
    ): String? {
        // 读取路径上「解析失败 → 空列表」是为了别崩，但拿它当改写的基线就成了另一回事：
        // 用户看见空列表随手加一台主机，还能导出/手工修复的原始数据就被永久盖掉了。
        val current = if (raw.isNullOrBlank()) emptyList() else parse(raw) ?: return null
        return encode(transform(current))
    }

    /**
     * 解密失败时退化成空凭据（而非丢弃整台主机）：主机名、端口这些配置还在，
     * 用户只需重新填一次密码，比整条记录消失友好得多。
     *
     * 退化的同时打上 [AuthMethod.credentialLost]——「存了密文但解不开」和「本来就没填」
     * 落到内存里长得一模一样，不在这里分开，下游就再也分不出来了。
     *
     * @param decrypt 解不开返回 null
     */
    fun decodeAuth(raw: RawAuth?, keys: List<SshKey>, decrypt: (String) -> String?): AuthMethod =
        when (raw?.type) {
            TYPE_KEY -> raw.keyId?.let { keyId ->
                val key = keys.firstOrNull { it.id == keyId }
                AuthMethod.PrivateKey(
                    pem = key?.pem.orEmpty(),
                    passphrase = key?.passphrase,
                    keyId = keyId,
                    // 钥匙被删了、或它自己的密文也解不开：两种情况下拿到的都是一把空私钥，
                    // 认证必然失败且检查用户名密码毫无用处，走和「密文解不开」同一条提示路径。
                    credentialLost = key == null || key.broken,
                )
            } ?: run {
                val plainPem = raw.pem?.let(decrypt)
                val plainPassphrase = raw.passphrase?.let(decrypt)
                val pemLost = raw.pem != null && plainPem == null
                val passphraseLost = raw.passphrase != null && plainPassphrase == null
                AuthMethod.PrivateKey(
                    pem = plainPem.orEmpty(),
                    passphrase = plainPassphrase,
                    // 口令解不开也算：私钥本身完好但口令没了，认证同样必然失败，
                    // 而用户在「检查用户名和凭据」那条提示下是找不到出路的。
                    credentialLost = pemLost || passphraseLost,
                    pemCipher = raw.pem.takeIf { pemLost },
                    passphraseCipher = raw.passphrase.takeIf { passphraseLost },
                )
            }

            TYPE_AGENT -> AuthMethod.Agent
            else -> {
                val plain = raw?.password?.let(decrypt)
                val lost = raw?.password != null && plain == null
                AuthMethod.Password(
                    password = plain.orEmpty(),
                    credentialLost = lost,
                    cipher = raw?.password.takeIf { lost },
                )
            }
        }

    /** 解不开的字段原样写回密文，其余重新加密——往返必须无损，否则整表重写会抹掉别人的凭据。 */
    fun encodeAuth(auth: AuthMethod, encrypt: (String) -> String): RawAuth = when (auth) {
        is AuthMethod.Password -> RawAuth(
            type = TYPE_PASSWORD,
            password = auth.cipher ?: encrypt(auth.password),
        )

        is AuthMethod.PrivateKey -> RawAuth(
            type = TYPE_KEY,
            keyId = auth.keyId,
            // 引用密钥库时**绝不再存一份 PEM**：存了就会有两份各自演化的副本，
            // 用户在密钥库里换掉钥匙，主机却还拿着旧的连——这正是密钥库要消灭的问题。
            pem = if (auth.keyId != null) null else auth.pemCipher ?: encrypt(auth.pem),
            passphrase = if (auth.keyId != null) {
                null
            } else {
                auth.passphraseCipher ?: auth.passphrase?.let(encrypt)
            },
        )

        AuthMethod.Agent -> RawAuth(type = TYPE_AGENT)
    }

    /**
     * 解不开的密钥退化成空 PEM（[SshKey.broken]）而不是被丢掉：钥匙的**名字**还在，
     * 用户才知道该去补哪一把；整条记录消失的话，引用它的主机只会剩下一个陌生的 id。
     */
    fun decodeKey(raw: RawKey, decrypt: (String) -> String?): SshKey {
        val plainPem = raw.pem?.let(decrypt)
        val plainPassphrase = raw.passphrase?.let(decrypt)
        return SshKey(
            id = raw.id,
            name = raw.name,
            pem = plainPem.orEmpty(),
            passphrase = plainPassphrase,
            pemCipher = raw.pem.takeIf { plainPem == null },
            passphraseCipher = raw.passphrase.takeIf { plainPassphrase == null },
        )
    }

    fun encodeKey(key: SshKey, encrypt: (String) -> String): RawKey = RawKey(
        id = key.id,
        name = key.name,
        pem = key.pemCipher ?: encrypt(key.pem),
        passphrase = key.passphraseCipher ?: key.passphrase?.let(encrypt),
    )
}
