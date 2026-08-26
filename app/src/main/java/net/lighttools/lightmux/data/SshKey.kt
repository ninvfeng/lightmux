package net.lighttools.lightmux.data

import java.util.UUID

/**
 * 密钥库里的一把私钥。
 *
 * 和 [Host] 一样：内存里的 [pem] / [passphrase] 是**明文**（[SshKeyStore] 读取时已解密），
 * 落盘前必须经 [Secrets] 加密。
 *
 * 一把钥匙可以被任意多台主机引用（[AuthMethod.PrivateKey.keyId]），这正是它存在的理由——
 * 同一把钥匙分发到十几台机器上是常态，此前每台主机各存一份 PEM，换钥匙就得挨个改一遍。
 */
data class SshKey(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val pem: String,
    val passphrase: String? = null,
    /**
     * 解不开的原始密文，**只在解密失败时非空**（理由见 [AuthMethod.Password.cipher]）：
     * 改一把钥匙要整表重写，不原样写回的话，别的钥匙那份解不开的口令会在
     * `passphrase?.let { encrypt(it) }` 里被整个字段丢掉。
     */
    val pemCipher: String? = null,
    val passphraseCipher: String? = null,
) {

    val format: KeyFormat get() = KeyFormat.of(pem)

    /** 密文解不开（Keystore 密钥没了）。空 PEM 存不进来，所以空即损坏。 */
    val broken: Boolean get() = pem.isEmpty()
}

/**
 * 私钥的封装格式，只看 PEM 头。
 *
 * 用途是**导入时就拦住 sshj 认不了的东西**：等到连接时才发现格式不认识，用户看到的是
 * 一条连接失败，而问题其实出在几天前那次导入上。
 */
enum class KeyFormat {

    /** `-----BEGIN OPENSSH PRIVATE KEY-----`，ssh-keygen 现在的默认输出。 */
    OpenSsh,

    /** `-----BEGIN PRIVATE KEY-----` / `ENCRYPTED PRIVATE KEY`。 */
    Pkcs8,

    /** `-----BEGIN RSA|DSA|EC PRIVATE KEY-----`，老式 `-m PEM` 输出。 */
    Pkcs1,

    /** PuTTY 的 `.ppk`。sshj 读不了，得先用 puttygen 转成 OpenSSH。 */
    Putty,

    Unknown;

    /** sshj 能不能直接吃。[Putty] 与 [Unknown] 一律拦在导入这一步。 */
    val supported: Boolean get() = this == OpenSsh || this == Pkcs8 || this == Pkcs1

    companion object {

        /** 头部之外不看：私钥正文是 base64，而用户从聊天软件粘来的文本常带空行和签名档。 */
        private const val HEAD_LINES = 10

        fun of(pem: String): KeyFormat {
            for (line in pem.lineSequence().take(HEAD_LINES).map { it.trim() }) {
                when {
                    line.startsWith("PuTTY-User-Key-File-") -> return Putty
                    !line.startsWith("-----BEGIN ") || !line.contains("PRIVATE KEY") -> continue
                    line.contains("OPENSSH") -> return OpenSsh
                    line.startsWith("-----BEGIN PRIVATE KEY") ||
                        line.startsWith("-----BEGIN ENCRYPTED PRIVATE KEY") -> return Pkcs8

                    else -> return Pkcs1
                }
            }
            return Unknown
        }
    }
}
