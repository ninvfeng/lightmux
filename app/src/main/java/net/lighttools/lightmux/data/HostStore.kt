package net.lighttools.lightmux.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

private val Context.hostDataStore: DataStore<Preferences> by preferencesDataStore(name = "hosts")

/**
 * 主机列表的持久化。
 *
 * 整张列表当作一个 JSON 字符串存在单个 Preferences key 下，而不是拆成一堆扁平 key：
 * 主机数量在几十这个量级，整存整取的一致性远比省下的那点 IO 重要
 * （拆开存会出现「删了一半字段」的中间态）。
 *
 * 落盘的 JSON 里，密码与私钥都是 [Secrets] 加密后的密文，**任何路径都不写明文**。
 *
 * 引用密钥库的主机只落一个 `keyId`，PEM 在读取时装配进来（见 [decodeAuth]）——
 * 于是「改一把钥匙，所有引用它的主机跟着变」是 Flow 自己的事，不需要谁去同步。
 */
class HostStore(private val context: Context, private val keyStore: SshKeyStore) {

    val hosts: Flow<List<Host>> =
        combine(context.hostDataStore.data, keyStore.keys) { prefs, keys ->
            decodeAll(prefs[KEY_HOSTS], keys)
        }

    suspend fun snapshot(): List<Host> = hosts.first()

    suspend fun get(id: String): Host? = snapshot().firstOrNull { it.id == id }

    /** 按 [Host.id] 覆盖或追加。 */
    suspend fun upsert(host: Host) = mutate { list ->
        val index = list.indexOfFirst { it.id == host.id }
        if (index >= 0) list.toMutableList().also { it[index] = host } else list + host
    }

    suspend fun delete(id: String) = mutate { list -> list.filterNot { it.id == id } }

    suspend fun replaceAll(hosts: List<Host>) = mutate { hosts }

    private suspend fun mutate(transform: (List<Host>) -> List<Host>) {
        // 改写只碰主机自己的字段，装配用的密钥快照给空即可：引用密钥库的记录落盘只写 keyId，
        // 而没引用的记录 PEM 本来就在自己身上。
        context.hostDataStore.edit { prefs ->
            // 解析失败时 rewrite 返回 null，这次改写整个放弃——绝不拿空列表当基线覆盖原始数据。
            val next = StoreCodec.rewrite(
                raw = prefs[KEY_HOSTS],
                parse = { parseAll(it, emptyList()) },
                transform = transform,
                encode = ::encodeAll,
            ) ?: return@edit
            prefs[KEY_HOSTS] = next
        }
    }

    private fun encodeAll(hosts: List<Host>): String {
        val array = JSONArray()
        hosts.forEach { array.put(encode(it)) }
        return JSONObject().put(FIELD_VERSION, VERSION).put(FIELD_HOSTS, array).toString()
    }

    /** 存储损坏时返回空列表比崩溃好，但绝不覆盖原始数据——用户还有导出/手工修复的机会。 */
    private fun decodeAll(raw: String?, keys: List<SshKey>): List<Host> =
        parseAll(raw, keys).orEmpty()

    /** 返回 null = 解析失败，写入路径靠它区分「本来就是空的」和「读不出来」。 */
    private fun parseAll(raw: String?, keys: List<SshKey>): List<Host>? {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONObject(raw).optJSONArray(FIELD_HOSTS) ?: return null
            (0 until array.length()).mapNotNull { i -> decode(array.getJSONObject(i), keys) }
        } catch (e: Exception) {
            Log.e(TAG, "host store corrupted, ignoring", e)
            null
        }
    }

    private fun encode(host: Host): JSONObject = JSONObject().apply {
        put("id", host.id)
        put("name", host.name)
        put("hostname", host.hostname)
        put("port", host.port)
        put("username", host.username)
        putOpt("loginCommand", host.loginCommand)
        putOpt("proxyJumpId", host.proxyJumpId)
        put("auth", encodeAuth(host.auth))
    }

    private fun decode(json: JSONObject, keys: List<SshKey>): Host? {
        val id = json.optString("id").ifBlank { return null }
        return Host(
            id = id,
            name = json.optString("name"),
            hostname = json.optString("hostname"),
            port = json.optInt("port", 22),
            username = json.optString("username"),
            auth = decodeAuth(json.optJSONObject("auth"), keys),
            loginCommand = json.optStringOrNull("loginCommand"),
            proxyJumpId = json.optStringOrNull("proxyJumpId"),
        )
    }

    // 这一层只搬字段：该不该重新加密、丢失标记怎么传，全在 StoreCodec 那个可测的纯逻辑里。
    private fun encodeAuth(auth: AuthMethod): JSONObject {
        val raw = StoreCodec.encodeAuth(auth, Secrets::encrypt)
        return JSONObject().apply {
            put("type", raw.type)
            putOpt("keyId", raw.keyId)
            putOpt("password", raw.password)
            putOpt("pem", raw.pem)
            putOpt("passphrase", raw.passphrase)
        }
    }

    private fun decodeAuth(json: JSONObject?, keys: List<SshKey>): AuthMethod = StoreCodec.decodeAuth(
        raw = json?.let {
            RawAuth(
                type = it.optString("type"),
                keyId = it.optStringOrNull("keyId"),
                password = it.optStringOrNull("password"),
                pem = it.optStringOrNull("pem"),
                passphrase = it.optStringOrNull("passphrase"),
            )
        },
        keys = keys,
        decrypt = Secrets::decryptOrNull,
    )

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).ifBlank { null }

    private companion object {
        const val TAG = "HostStore"
        const val VERSION = 1
        const val FIELD_VERSION = "v"
        const val FIELD_HOSTS = "hosts"
        val KEY_HOSTS = stringPreferencesKey("hosts_json")
    }
}
