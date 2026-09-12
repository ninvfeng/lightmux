package net.lighttools.lightmux.ui.host

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.lighttools.lightmux.data.AuthMethod
import net.lighttools.lightmux.data.Host
import net.lighttools.lightmux.data.HostRoute
import net.lighttools.lightmux.data.HostStore
import net.lighttools.lightmux.data.KeyFormat
import net.lighttools.lightmux.data.PemFile
import net.lighttools.lightmux.data.SshKey
import net.lighttools.lightmux.data.SshKeyStore
import net.lighttools.lightmux.ssh.SshConnection
import net.lighttools.lightmux.ui.keys.KeyFileError
import net.lighttools.lightmux.ui.terminal.ConnectionFailure

/** 「保存前先试一次」的结果。 */
sealed interface HostTest {

    data object Idle : HostTest

    data object Running : HostTest

    /** @param fingerprint 这次握手拿到的主机公钥指纹，用户可以直接和 `ssh-keyscan` 的输出对照 */
    data class Ok(val fingerprint: String?) : HostTest

    /** @param endpoint 说不出具体原因时（[ConnectionFailure.Other]）文案要用它 */
    data class Failed(val failure: ConnectionFailure, val endpoint: String) : HostTest
}

/**
 * 主机增改。
 *
 * 校验与转换都在纯逻辑的 [HostForm] 里，这里只管加载、编辑中的状态和落库。
 */
class HostEditViewModel(
    private val hostStore: HostStore,
    keyStore: SshKeyStore,
    private val hostId: String?,
    /** 复制：拿 [hostId] 那台主机的字段当新建的初值，保存时落成新的一台，源主机不动。 */
    private val duplicate: Boolean = false,
) : ViewModel() {

    /**
     * [hostId] 那条原记录。保存时要从它身上取回「用户没改」的凭据。
     *
     * 复制模式下它是**凭据的来源而不是要覆盖的目标**——两者的分工见 [HostForm.toHost]。
     */
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

    /** 「测试」的结果。表单一改就作废——改完还挂着上一次的「连接成功」是彻头彻尾的误导。 */
    var test by mutableStateOf<HostTest>(HostTest.Idle)
        private set

    /**
     * 正在试连的那条连接。**必须自己攥着**：[SshConnection.connectBlocking] 阻塞在 socket 上，
     * 协程 cancel 打断不了阻塞 IO，页面关掉时只有 close 能把它踹醒；不然一台连不上的主机
     * 能让这条握手在后台再挂 20 秒。
     */
    @Volatile
    private var probe: SshConnection? = null

    init {
        viewModelScope.launch {
            if (hostId != null) {
                // 一次快照两用：捞源主机，顺便拿全部主机名给复制出来的那台避重名
                val all = hostStore.snapshot()
                val host = all.firstOrNull { it.id == hostId }
                existing = host
                if (host != null) {
                    form = if (duplicate) HostForm.copyOf(host, all.map { it.name })
                    else HostForm.of(host)
                }
            }
            loaded = true
        }
        keyStore.keys.onEach { keys = it }.launchIn(viewModelScope)
        hostStore.hosts
            // 复制出来的是一台**还不存在**的主机，跳板候选按新建算：排除的是「自己」和
            // 会绕回自己的那些，而这台谁也没引用过，源主机本身也可以拿来当跳板
            .onEach { jumpCandidates = HostRoute.candidates(hostId.takeIf { !duplicate }, it) }
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

    /**
     * 拿当前表单**试连一次**，不落库。
     *
     * 存在的理由：填错一个字（端口、用户名、粘漏半行 PEM）以前要等到保存、回主页、开会话
     * 才看得到一句「认证失败」，而那时人已经离开表单了。
     */
    fun testConnection() {
        if (test == HostTest.Running) return
        val found = form.validate()
        errors = found
        if (found.isNotEmpty()) return
        // 引用密钥库时得把 PEM 装配进来：这份表单还没进过库，没人替它装（见 HostForm.toTestHost）
        val host = form.toTestHost(existing, keys)
        test = HostTest.Running
        viewModelScope.launch {
            test = withContext(Dispatchers.IO) {
                val connection = SshConnection(host).also { probe = it }
                try {
                    // 连上 + 认证通过就够了：网络、主机密钥、凭据、跳板链全在这一步里验完，
                    // 再跑一条命令只是多开一个 channel，验不出新东西。
                    connection.connectBlocking()
                    HostTest.Ok(connection.hostFingerprint)
                } catch (e: Exception) {
                    HostTest.Failed(
                        ConnectionFailure.of(e) ?: ConnectionFailure.Other(null),
                        host.endpoint,
                    )
                } finally {
                    probe = null
                    connection.close()
                }
            }
        }
    }

    override fun onCleared() {
        // 页面关了，这条试连没人要了。cancel 打不断阻塞的握手，close 才能。
        probe?.close()
    }

    fun update(transform: (HostForm) -> HostForm) {
        form = transform(form)
        // 改了任何一个字段，上一次的测试结论就不再是关于这份表单的了
        if (test != HostTest.Idle) test = HostTest.Idle
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

            // 无需认证没有任何凭据可沿用
            AuthKind.None -> form.copy(authKind = kind, keyId = null, keepSecret = false)
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
