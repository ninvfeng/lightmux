package net.lighttools.lightmux.ui.host

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import net.lighttools.lightmux.data.AuthMethod
import net.lighttools.lightmux.data.Host
import net.lighttools.lightmux.data.HostRoute
import net.lighttools.lightmux.data.HostStore
import net.lighttools.lightmux.data.KeyFormat
import net.lighttools.lightmux.data.PemFile
import net.lighttools.lightmux.data.SshKey
import net.lighttools.lightmux.data.SshKeyStore
import net.lighttools.lightmux.ui.keys.KeyFileError

/**
 * 主机增改。
 *
 * 校验与转换都在纯逻辑的 [HostForm] 里，这里只管加载、编辑中的状态和落库。
 */
class HostEditViewModel(
    private val hostStore: HostStore,
    keyStore: SshKeyStore,
    private val hostId: String?,
) : ViewModel() {

    /** 编辑时的原记录。保存时要从它身上取回「用户没改」的凭据。 */
    private var existing: Host? = null

    var form by mutableStateOf(HostForm())
        private set

    var errors by mutableStateOf(emptySet<HostFormError>())
        private set

    var loaded by mutableStateOf(hostId == null)
        private set

    /** 密钥库里可选的钥匙。跟着 Flow 走，从这里跳去密钥管理页导完新钥匙回来即可见。 */
    var keys by mutableStateOf(emptyList<SshKey>())
        private set

    /** 上一次「从文件选择」失败了（读不出来或格式不认识），下次选文件时清掉。 */
    var keyFileError by mutableStateOf<KeyFileError?>(null)
        private set

    /** 能选作跳板机的主机。会绕成环的那些在这一步就被排掉，不留到保存时才报错。 */
    var jumpCandidates by mutableStateOf(emptyList<Host>())
        private set

    init {
        viewModelScope.launch {
            existing = hostId?.let { hostStore.get(it) }
            existing?.let { form = HostForm.of(it) }
            loaded = true
        }
        keyStore.keys.onEach { keys = it }.launchIn(viewModelScope)
        hostStore.hosts
            .onEach { jumpCandidates = HostRoute.candidates(hostId, it) }
            .launchIn(viewModelScope)
    }

    /** 选跳板机；[id] 为 null 表示直连。 */
    fun setProxyJump(id: String?) = update { it.copy(proxyJumpId = id) }

    /**
     * 从系统文件选择器挑的文件里读私钥，直接填进粘贴框。
     *
     * `ContentResolver` 由 UI 传进来而不是让 ViewModel 持有 Context：这层只有这一个地方
     * 需要它，为它留一个 Application 引用不划算。
     */
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
                // 在这里拦住，比等到连接时报一句「认证失败」强得多——尤其 .ppk，
                // 用户得到的提示必须是「去 puttygen 转一下」而不是「检查用户名和密码」。
                keyFileError = if (format == KeyFormat.Putty) KeyFileError.Putty else KeyFileError.NotAKey
                return@launch
            }
            // 读出来的内容进粘贴框，同时脱离密钥库：用户刚挑了一个文件，意思显然是用这一把
            update { it.copy(pem = picked.pem, keyId = null, keepSecret = false) }
        }
    }

    fun update(transform: (HostForm) -> HostForm) {
        form = transform(form)
        // 已经报过错就实时清，别让用户改完还盯着一片红。
        if (errors.isNotEmpty()) errors = form.validate()
    }

    /**
     * 切换认证方式时，「沿用已保存凭据」只在新方式和原来一致时才成立——
     * 从密码换成私钥，本来就没有旧私钥可沿用，必须逼用户填。
     */
    fun setAuthKind(kind: AuthKind) = update { form ->
        val key = existing?.auth as? AuthMethod.PrivateKey
        when (kind) {
            AuthKind.Password -> form.copy(
                authKind = kind,
                keyId = null,
                keepSecret = (existing?.auth as? AuthMethod.Password)?.password?.isNotEmpty() == true,
            )

            AuthKind.PrivateKey -> form.copy(
                authKind = kind,
                // 原本就引用密钥库的，切回来要恢复那把钥匙，别逼用户重挑一遍
                keyId = key?.keyId,
                keepSecret = key != null && key.keyId == null && key.pem.isNotEmpty(),
            )
        }
    }

    /** 改用密钥库里的钥匙。粘贴框里的内容一并清掉，免得存了 id 又留着一份影子 PEM。 */
    fun selectKey(id: String) = update {
        it.copy(keyId = id, pem = "", passphrase = "", keepSecret = false)
    }

    /** 改回手工粘贴。只有原记录本来就是粘贴来的，才谈得上「沿用已保存的私钥」。 */
    fun usePastedKey() = update { form ->
        val key = existing?.auth as? AuthMethod.PrivateKey
        form.copy(keyId = null, keepSecret = key != null && key.keyId == null && key.pem.isNotEmpty())
    }

    /** 用户一旦动了凭据输入框，就不再沿用旧值。 */
    fun setPassword(value: String) = update { it.copy(password = value, keepSecret = false) }

    fun setPem(value: String) = update { it.copy(pem = value, keepSecret = false) }

    fun setPassphrase(value: String) = update { it.copy(passphrase = value, keepSecret = false) }

    /** @return 保存成功。失败时 [errors] 已经填好，页面别关 */
    fun save(onSaved: () -> Unit) {
        val found = form.validate()
        errors = found
        if (found.isNotEmpty()) return
        val host = form.toHost(existing)
        viewModelScope.launch {
            hostStore.upsert(host)
            onSaved()
        }
    }
}
