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
import net.lighttools.lightmux.forward.ForwardSpec
import org.json.JSONArray
import org.json.JSONObject

private val Context.forwardDataStore: DataStore<Preferences> by preferencesDataStore(name = "forwards")

/**
 * 端口转发配置的持久化。存法与 [HostStore] 一致：整张列表一个 JSON 串。
 *
 * 这里**只存配置，不存开关状态**——app 冷启动时不会自动把上次开着的转发拉起来。
 * 自动拉起意味着一进 app 就往每台主机拨连接，正是 PRD §4.3「绝不自动全量探测」要避免的事；
 * 而且用户重开 app 的常见理由恰恰是「刚才那条转发不对劲」。转发一律手动开。
 *
 * 转发配置里没有任何凭据，所以不像 [HostStore] 那样要过 [Secrets]。
 */
class ForwardStore(private val context: Context) {

    val forwards: Flow<List<ForwardSpec>> =
        context.forwardDataStore.data.map { decodeAll(it[KEY_FORWARDS]) }

    suspend fun snapshot(): List<ForwardSpec> = forwards.first()

    /** 按 [ForwardSpec.id] 覆盖或追加。 */
    suspend fun upsert(spec: ForwardSpec) = mutate { list ->
        val index = list.indexOfFirst { it.id == spec.id }
        if (index >= 0) list.toMutableList().also { it[index] = spec } else list + spec
    }

    suspend fun delete(id: String) = mutate { list -> list.filterNot { it.id == id } }

    /** 主机被删掉时顺手清干净，否则这些记录再也没有入口能看到、也删不掉。 */
    suspend fun deleteForHost(hostId: String) =
        mutate { list -> list.filterNot { it.hostId == hostId } }

    private suspend fun mutate(transform: (List<ForwardSpec>) -> List<ForwardSpec>) {
        context.forwardDataStore.edit { prefs ->
            prefs[KEY_FORWARDS] = encodeAll(transform(decodeAll(prefs[KEY_FORWARDS])))
        }
    }

    private fun encodeAll(specs: List<ForwardSpec>): String {
        val array = JSONArray()
        specs.forEach { spec ->
            array.put(
                JSONObject().apply {
                    put("id", spec.id)
                    put("hostId", spec.hostId)
                    put("remoteHost", spec.remoteHost)
                    put("remotePort", spec.remotePort)
                    put("localPort", spec.localPort)
                    spec.label?.let { put("label", it) }
                }
            )
        }
        return JSONObject().put(FIELD_VERSION, VERSION).put(FIELD_FORWARDS, array).toString()
    }

    private fun decodeAll(raw: String?): List<ForwardSpec> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONObject(raw).optJSONArray(FIELD_FORWARDS) ?: return emptyList()
            (0 until array.length()).mapNotNull { i -> decode(array.getJSONObject(i)) }
        }.getOrElse {
            // 存坏了就当没有。转发配置是能一分钟重建的东西，为它崩掉整个主页不值当。
            Log.w(TAG, "failed to decode forwards", it)
            emptyList()
        }
    }

    private fun decode(json: JSONObject): ForwardSpec? {
        val hostId = json.optString("hostId").ifBlank { return null }
        val remotePort = json.optInt("remotePort").takeIf { it > 0 } ?: return null
        val localPort = json.optInt("localPort").takeIf { it > 0 } ?: return null
        return ForwardSpec(
            id = json.optString("id").ifBlank { return null },
            hostId = hostId,
            remotePort = remotePort,
            localPort = localPort,
            remoteHost = json.optString("remoteHost").ifBlank { ForwardSpec.DEFAULT_REMOTE_HOST },
            label = json.optString("label").ifBlank { null },
        )
    }

    private companion object {
        const val TAG = "ForwardStore"
        const val VERSION = 1
        const val FIELD_VERSION = "version"
        const val FIELD_FORWARDS = "forwards"
        val KEY_FORWARDS = stringPreferencesKey("forwards_json")
    }
}
