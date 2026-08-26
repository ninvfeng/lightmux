package net.lighttools.lightmux.ui.keys

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import net.lighttools.lightmux.data.AuthMethod
import net.lighttools.lightmux.data.HostStore
import net.lighttools.lightmux.data.KeyFormat
import net.lighttools.lightmux.data.PemFile
import net.lighttools.lightmux.data.SshKey
import net.lighttools.lightmux.data.SshKeyStore

/** 列表里的一行：钥匙 + 有几台主机在用它。 */
data class KeyRow(val key: SshKey, val usedBy: Int)

/**
 * 密钥管理。
 *
 * 「有几台主机在用」是这个页面存在的理由之一——删钥匙是个不可逆动作，
 * 用户必须在按下删除之前就看见代价，而不是事后在某台主机上撞见「凭据丢了」。
 */
class KeysViewModel(
    private val keyStore: SshKeyStore,
    hostStore: HostStore,
) : ViewModel() {

    var rows by mutableStateOf(emptyList<KeyRow>())
        private set

    /** 非空 = 增改弹窗开着。 */
    var editor by mutableStateOf<KeyForm?>(null)
        private set

    var errors by mutableStateOf(emptySet<KeyFormError>())
        private set

    var keyFileError by mutableStateOf<KeyFileError?>(null)
        private set

    /** 非空 = 删除确认弹窗开着。 */
    var deleting by mutableStateOf<KeyRow?>(null)
        private set

    /** 正在编辑的原记录，保存时要从它身上取回没改动的 PEM。 */
    private var editing: SshKey? = null

    init {
        combine(keyStore.keys, hostStore.hosts) { keys, hosts ->
            keys.map { key ->
                KeyRow(key, hosts.count { (it.auth as? AuthMethod.PrivateKey)?.keyId == key.id })
            }
        }.onEach { rows = it }.launchIn(viewModelScope)
    }

    fun add() {
        editing = null
        errors = emptySet()
        keyFileError = null
        editor = KeyForm()
    }

    fun edit(row: KeyRow) {
        editing = row.key
        errors = emptySet()
        keyFileError = null
        editor = KeyForm.of(row.key)
    }

    fun dismissEditor() {
        editor = null
        editing = null
    }

    fun update(transform: (KeyForm) -> KeyForm) {
        val next = transform(editor ?: return)
        editor = next
        if (errors.isNotEmpty()) errors = next.validate()
    }

    fun setName(value: String) = update { it.copy(name = value) }

    /** 用户动了 PEM 输入框就不再沿用旧值。 */
    fun setPem(value: String) = update { it.copy(pem = value, keepSecret = false) }

    fun setPassphrase(value: String) = update { it.copy(passphrase = value) }

    /** 从系统文件选择器挑的文件里读私钥。文件名顺手当默认名字——多数人给钥匙起的就是文件名。 */
    fun loadPemFromFile(resolver: ContentResolver, uri: Uri) {
        keyFileError = null
        viewModelScope.launch {
            val picked = PemFile.read(resolver, uri)
            if (picked == null) {
                keyFileError = KeyFileError.Unreadable
                return@launch
            }
            val format = KeyFormat.of(picked.pem)
            if (!format.supported) {
                keyFileError = if (format == KeyFormat.Putty) KeyFileError.Putty else KeyFileError.NotAKey
                return@launch
            }
            update {
                it.copy(pem = picked.pem, keepSecret = false, suggestedName = picked.name.orEmpty())
            }
        }
    }

    fun save() {
        val form = editor ?: return
        val found = form.validate()
        errors = found
        if (found.isNotEmpty()) return
        val key = form.toKey(editing)
        viewModelScope.launch {
            keyStore.upsert(key)
            dismissEditor()
        }
    }

    fun confirmDelete(row: KeyRow) {
        deleting = row
    }

    fun dismissDelete() {
        deleting = null
    }

    fun delete() {
        val row = deleting ?: return
        deleting = null
        viewModelScope.launch { keyStore.delete(row.key.id) }
    }
}
