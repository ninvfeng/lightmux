package net.lighttools.lightmux.ui.keys

import net.lighttools.lightmux.data.KeyFormat
import net.lighttools.lightmux.data.SshKey
import java.util.UUID

enum class KeyFormError { Name, Pem, PemFormat }

/**
 * 密钥库增改表单的**纯数据**表示。和 [net.lighttools.lightmux.ui.host.HostForm] 一个路数：
 * 校验与转换写成无 Android 依赖的纯函数，本机能自测的只有这层。
 */
data class KeyForm(
    /** null = 新导入。 */
    val id: String? = null,
    val name: String = "",
    val pem: String = "",
    val passphrase: String = "",
    /**
     * 沿用已保存的 PEM。编辑已有钥匙时不回显私钥明文（理由同主机表单），
     * 用户只改名字的话，保存时把原来的 PEM 原样带回去。
     */
    val keepSecret: Boolean = false,
    /** 从文件导入时的文件名，用户没起名字时拿它顶上。 */
    val suggestedName: String = "",
) {

    fun validate(): Set<KeyFormError> = buildSet {
        if (name.isBlank() && suggestedName.isBlank()) add(KeyFormError.Name)
        if (keepSecret) return@buildSet
        when {
            pem.isBlank() -> add(KeyFormError.Pem)
            // 格式在导入时就拦住，而不是等到某台主机连不上再说：那时候用户面对的是
            // 一句「认证失败」，根本联想不到问题出在钥匙的格式上。
            !KeyFormat.of(pem).supported -> add(KeyFormError.PemFormat)
        }
    }

    /**
     * 落库前的转换。调用方须保证 [validate] 为空。
     *
     * @param existing 编辑时的原记录，[keepSecret] 为真时从这里取回旧 PEM
     */
    fun toKey(existing: SshKey? = null): SshKey = SshKey(
        id = existing?.id ?: id ?: UUID.randomUUID().toString(),
        name = name.trim().ifBlank { suggestedName.trim() },
        pem = if (keepSecret) existing?.pem.orEmpty() else pem.trim(),
        // 口令留空时：换了新 PEM 就是「这把钥匙没口令」，没换则是「没动它」。
        passphrase = passphrase.ifBlank { if (keepSecret) existing?.passphrase else null },
    )

    companion object {

        fun of(key: SshKey): KeyForm = KeyForm(
            id = key.id,
            name = key.name,
            // 明文不进表单，连 State 都不放。
            pem = "",
            passphrase = "",
            // 密文解不开的钥匙没什么可沿用的，必须逼用户重新导入一次。
            keepSecret = !key.broken,
        )
    }
}
