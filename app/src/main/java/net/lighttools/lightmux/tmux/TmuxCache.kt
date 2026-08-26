package net.lighttools.lightmux.tmux

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONObject

private val Context.tmuxDataStore: DataStore<Preferences> by preferencesDataStore(name = "tmux_cache")

/** 一台主机上次探测到的原始输出 + 采集时刻。 */
data class CachedProbe(val raw: String, val probedAt: Long)

/**
 * 探测结果的持久化。冷启动主页要在**不发起任何网络请求**的前提下把树画出来（PRD §4.3）。
 *
 * 存的是 tmux 输出的**原始文本**而不是解析后的结构：解码就是再跑一遍 [Tmux.parseProbe]，
 * 不必再写一套 JSON schema，也不会出现「缓存格式和解析器各自演进后对不上」的经典故障。
 */
class TmuxCache(private val context: Context) {

    suspend fun load(): Map<String, CachedProbe> {
        return try {
            // 读 DataStore 这一步也必须在 try 里：文件损坏时它自己就会抛 CorruptionException，
            // 而这条路径在主页初始化上，异常冒出去就是「打开 app 直接崩」。
            val raw = context.tmuxDataStore.data.first()[KEY] ?: return emptyMap()
            val json = JSONObject(raw)
            buildMap {
                json.keys().forEach { hostId ->
                    val entry = json.optJSONObject(hostId) ?: return@forEach
                    val text = entry.optString(FIELD_RAW)
                    if (text.isNotEmpty()) {
                        put(hostId, CachedProbe(text, entry.optLong(FIELD_AT)))
                    }
                }
            }
        } catch (e: Exception) {
            // 缓存读不出来最多是冷启动少一屏内容，没必要为它崩溃，也没必要清空（下次探测会自然覆盖）。
            Log.w(TAG, "tmux cache corrupted, ignoring", e)
            emptyMap()
        }
    }

    suspend fun put(hostId: String, raw: String, probedAt: Long = System.currentTimeMillis()) {
        // 几百个窗口的机器能吐出很大一坨，缓存是为了冷启动那一屏，不值得为它撑大 DataStore。
        if (raw.length > MAX_RAW_CHARS) return
        edit { json ->
            json.put(hostId, JSONObject().put(FIELD_RAW, raw).put(FIELD_AT, probedAt))
        }
    }

    /** 主机被删掉时清掉它的缓存，否则这条记录永远留在 DataStore 里。 */
    suspend fun remove(hostId: String) = edit { it.remove(hostId) }

    private suspend fun edit(mutate: (JSONObject) -> Unit) {
        context.tmuxDataStore.edit { prefs ->
            val json = runCatching { JSONObject(prefs[KEY].orEmpty()) }.getOrElse { JSONObject() }
            mutate(json)
            prefs[KEY] = json.toString()
        }
    }

    private companion object {
        const val TAG = "TmuxCache"
        const val FIELD_RAW = "raw"
        const val FIELD_AT = "at"
        const val MAX_RAW_CHARS = 64 * 1024
        val KEY = stringPreferencesKey("probes_json")
    }
}
