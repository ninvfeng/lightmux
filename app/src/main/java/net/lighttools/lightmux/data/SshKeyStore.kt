package net.lighttools.lightmux.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.keyDataStore: DataStore<Preferences> by preferencesDataStore(name = "ssh_keys")

/**
 * 私钥库的持久化。结构与 [HostStore] 同构（整张列表一个 JSON、密文经 [Secrets]），
 * 但**独立一个 DataStore 文件**：钥匙的生命周期比主机长，导入一次要被好几台主机引用，
 * 混在主机列表里会让「删主机顺带删了别人在用的钥匙」变得可能。
 */
class SshKeyStore(private val context: Context) {

    val keys: Flow<List<SshKey>> = context.keyDataStore.data.map { prefs ->
        decodeAll(prefs[KEY_KEYS])
    }

    suspend fun snapshot(): List<SshKey> = keys.first()

    /** 按 [SshKey.id] 覆盖或追加。 */
    suspend fun upsert(key: SshKey) = mutate { list ->
        val index = list.indexOfFirst { it.id == key.id }
        if (index >= 0) list.toMutableList().also { it[index] = key } else list + key
    }

    /**
     * 删除。引用它的主机不在这里连带修改——那些主机会退化成「凭据丢了」，
     * 走的是和密文解不开同一条提示路径（见 [HostStore.decodeAuth]）。
     * 调用方有责任在删之前告诉用户有几台主机在用。
     */
    suspend fun delete(id: String) = mutate { list -> list.filterNot { it.id == id } }

    private suspend fun mutate(transform: (List<SshKey>) -> List<SshKey>) {
        context.keyDataStore.edit { prefs ->
            // 和 HostStore 一样：解析失败（rewrite 返回 null）就整个放弃写入。
            val next = StoreCodec.rewrite(
                raw = prefs[KEY_KEYS],
                parse = ::parseAll,
                transform = transform,
                encode = ::encodeAll,
            ) ?: return@edit
            prefs[KEY_KEYS] = next
        }
    }

    private fun encodeAll(keys: List<SshKey>): String {
        val array = JSONArray()
        keys.forEach { array.put(encode(it)) }
        return JSONObject().put(FIELD_VERSION, VERSION).put(FIELD_KEYS, array).toString()
    }

    /** 和 HostStore 一样：宁可这次读出空列表，也绝不覆盖原始数据。 */
    private fun decodeAll(raw: String?): List<SshKey> = parseAll(raw).orEmpty()

    /** 返回 null = 解析失败，写入路径靠它区分「本来就是空的」和「读不出来」。 */
    private fun parseAll(raw: String?): List<SshKey>? {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONObject(raw).optJSONArray(FIELD_KEYS) ?: return null
            (0 until array.length()).mapNotNull { i -> decode(array.getJSONObject(i)) }
        } catch (e: Exception) {
            Log.e(TAG, "key store corrupted, ignoring", e)
            null
        }
    }

    // 只搬字段，判断在 StoreCodec 里（理由见那边的注释）。
    private fun encode(key: SshKey): JSONObject {
        val raw = StoreCodec.encodeKey(key, Secrets::encrypt)
        return JSONObject().apply {
            put("id", raw.id)
            put("name", raw.name)
            putOpt("pem", raw.pem)
            putOpt("passphrase", raw.passphrase)
        }
    }

    private fun decode(json: JSONObject): SshKey? {
        val id = json.optString("id").ifBlank { return null }
        return StoreCodec.decodeKey(
            raw = RawKey(
                id = id,
                name = json.optString("name"),
                pem = json.optStringOrNull("pem"),
                passphrase = json.optStringOrNull("passphrase"),
            ),
            decrypt = Secrets::decryptOrNull,
        )
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).ifBlank { null }

    private companion object {
        const val TAG = "SshKeyStore"
        const val VERSION = 1
        const val FIELD_VERSION = "v"
        const val FIELD_KEYS = "keys"
        val KEY_KEYS = stringPreferencesKey("keys_json")
    }
}
