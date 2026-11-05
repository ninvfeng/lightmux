package net.lighttools.lightmux.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.lighttools.lightmux.sftp.SftpPath

private val Context.fileBookmarks: DataStore<Preferences> by preferencesDataStore(name = "file_bookmarks")

/** 每台主机独立存路径集合，避免把一台服务器的目录带到另一台。 */
class FileBookmarkStore(private val context: Context) {
    fun paths(hostId: String): Flow<Set<String>> = context.fileBookmarks.data.map { prefs ->
        prefs[key(hostId)].orEmpty()
    }

    suspend fun toggle(hostId: String, path: String) {
        val normalized = SftpPath.normalize(path)
        context.fileBookmarks.edit { prefs ->
            val key = key(hostId)
            val paths = prefs[key].orEmpty()
            prefs[key] = if (normalized in paths) paths - normalized else paths + normalized
        }
    }

    private fun key(hostId: String) = stringSetPreferencesKey("host:$hostId")
}
