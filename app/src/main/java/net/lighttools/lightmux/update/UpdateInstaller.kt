package net.lighttools.lightmux.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 安装前校验的结论。除 [Ok] 外的每一项都必须让下载的文件被删掉。
 */
sealed interface ApkVerdict {

    data object Ok : ApkVerdict

    /** 连包名/版本号/签名都读不出来：多半下了半截，或者服务端给的根本不是 APK。 */
    data object Unreadable : ApkVerdict

    data object PackageMismatch : ApkVerdict

    data object NotNewer : ApkVerdict

    data object SignatureMismatch : ApkVerdict

    /**
     * 响应体超过下载上限。**唯一一个不是 [ApkGuard.verify] 给出的结论**——
     * 它在下载途中就判了，因为等下完再判等于已经把磁盘写满了。
     */
    data object TooLarge : ApkVerdict
}

/** 下载超限。单独一个类型，好让上层把它和普通网络失败分开提示。 */
class ApkTooLargeException : IOException("apk exceeds size cap")

/**
 * 三重校验的决策部分，抽成纯函数是为了能在没有 Android 环境的地方单测——
 * 这段逻辑一旦写错，后果是引导用户亲手装上一个别人签名的同名包，属于安全关键路径。
 *
 * 校验顺序有意为之：先包名（错得最离谱）、再版本（防降级安装）、最后签名（最贵的一步）。
 */
object ApkGuard {

    fun verify(
        apkPackageName: String?,
        apkVersionCode: Long?,
        apkSignatures: Set<String>,
        currentPackageName: String,
        currentVersionCode: Long,
        currentSignatures: Set<String>,
    ): ApkVerdict = when {
        apkPackageName.isNullOrBlank() || apkVersionCode == null || apkSignatures.isEmpty() ->
            ApkVerdict.Unreadable

        // 自身签名读不到就没有比对基准，此时「放行」等于没校验，只能判失败
        currentSignatures.isEmpty() -> ApkVerdict.Unreadable

        apkPackageName != currentPackageName -> ApkVerdict.PackageMismatch
        apkVersionCode <= currentVersionCode -> ApkVerdict.NotNewer
        apkSignatures != currentSignatures -> ApkVerdict.SignatureMismatch
        else -> ApkVerdict.Ok
    }
}

/**
 * 下载 APK、校验、拉起系统安装器。
 *
 * 只写应用私有的 `cacheDir`：公共存储上的文件别的应用能改，校验和安装之间存在替换窗口
 * （而且从 Android 10 起也不该往那儿写）。
 */
class UpdateInstaller(private val context: Context) {

    private val downloadDir: File get() = File(context.cacheDir, "updates")

    /**
     * 下载到私有目录。每次先清空目录：上一次失败留下的半截文件会顶掉同名的新文件。
     *
     * 写入量卡死在 `MAX_APK_BYTES`：`Content-Length` 是服务端说了算的，`chunked` 响应干脆没有它，
     * 拿它当上限等于没有上限。真正管用的是数自己写了多少字节，超了就 [ApkTooLargeException]，
     * 免得一个畸形响应把用户的存储写满。
     *
     * @param onProgress 百分比，只在整数位变化时回调，避免每读一块就切一次线程
     */
    suspend fun download(release: ReleaseInfo, onProgress: suspend (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            downloadDir.deleteRecursively()
            downloadDir.mkdirs()
            // 文件名写死，不拼 release.version：那是服务端给的字符串，含 `/` 就会写到目录外面去。
            // 版本信息在 verify 里从 APK 自身读，用不着体现在文件名上。
            val target = File(downloadDir, "update.apk")
            val url = URL(release.apkUrl)
            // 签名校验已经能挡住被掉包的 APK，但明文下载仍会把「谁在更新什么」暴露给中间人。
            // 只查起始 URL 就够：CNB 的附件链接会 302 到 asset.cnb.cool，而 HttpURLConnection
            // 本来就拒绝跨协议跳转（https→http 不会自动跟随），降级到明文的路是断的。
            if (!url.protocol.equals("https", ignoreCase = true)) throw IOException("insecure url")
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                val total = connection.contentLength.toLong()
                // 服务端先报了个离谱的长度就不必开始下：省掉几十兆的流量和写盘
                if (total > MAX_APK_BYTES) throw ApkTooLargeException()
                var done = 0L
                var lastPercent = -1
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            done += read
                            if (done > MAX_APK_BYTES) throw ApkTooLargeException()
                            output.write(buffer, 0, read)
                            if (total <= 0) continue
                            val percent = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                // 半截文件得当场删：目录只在下一次下载开头才清，中间这段时间它一直占着盘，
                // 而「超限中止」留下的正好是最大的那一份
                target.delete()
                throw e
            } finally {
                connection.disconnect()
            }
            target
        }

    /**
     * 安装前的三重校验：包名一致、版本严格更高、签名与已安装的自己一致。
     *
     * 不过就**立刻删文件**——留着它，用户下次在文件管理器里点开就绕过了这里的全部保护。
     */
    fun verify(apk: File): ApkVerdict {
        val pm = context.packageManager
        val archive = archiveInfo(pm, apk)
        val installed = try {
            pm.getPackageInfo(context.packageName, signatureFlag())
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val verdict = ApkGuard.verify(
            apkPackageName = archive?.packageName,
            apkVersionCode = archive?.let { PackageInfoCompat.getLongVersionCode(it) },
            apkSignatures = signaturesOf(archive),
            currentPackageName = context.packageName,
            currentVersionCode = installed?.let { PackageInfoCompat.getLongVersionCode(it) } ?: Long.MAX_VALUE,
            currentSignatures = signaturesOf(installed),
        )
        if (verdict != ApkVerdict.Ok) apk.delete()
        return verdict
    }

    /** Android 8 起「安装未知应用」是按来源授权的，没授权时 startActivity 会被静默丢弃。 */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** 把用户送到本应用的「安装未知应用」开关页，而不是让他自己在系统设置里找。 */
    fun openUnknownSourcesSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * 把 APK 交给系统安装。只应在 [verify] 返回 [ApkVerdict.Ok] 之后调用。
     *
     * 走 [PackageInstaller] 会话而不是 `ACTION_VIEW` + FileProvider：
     * 后者是把一个 content URI 甩给「谁能处理 apk 谁来」，结果有二——
     * 一是接手的未必是系统安装器（不少 ROM 会截给自家的安装/安全中心），
     * 二是**发完 Intent 就撒手，安装结果一个字都回不来**。
     * 实际踩到的就是这个：ROM 提示「已安装」，版本却纹丝没动，而应用这边只会转回 Idle。
     *
     * 会话这条路上字节是我们自己写进去的，装的必定是这个文件；结果经
     * [UpdateInstallReceiver] 回到 [UpdateInstallState]，失败时能把系统的原话摆给用户看。
     */
    suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val sessionId = packageInstaller.createSession(params)
        try {
            packageInstaller.openSession(sessionId).use { session ->
                session.openWrite(SESSION_ENTRY, 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    // 不 fsync 的话缓冲还可能留在内存里，commit 拿到的是个短包
                    session.fsync(output)
                }
                session.commit(statusSender(sessionId))
            }
        } catch (e: Exception) {
            // 提交失败的会话不会自己消失，一个就压着十几 MB 的暂存空间
            runCatching { packageInstaller.abandonSession(sessionId) }
            throw e
        }
    }

    /**
     * 会话状态的回传通道。**必须是 mutable 的 PendingIntent**——
     * 系统要往里填状态码和确认框 Intent，不可变的话回来的是个空壳。
     */
    private fun statusSender(sessionId: Int): IntentSender {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            context,
            sessionId,
            Intent(context, UpdateInstallReceiver::class.java),
            flags,
        ).intentSender
    }

    private fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? {
        val info = pm.getPackageArchiveInfo(apk.absolutePath, signatureFlag()) ?: return null
        // 部分系统版本读签名时会回头去 sourceDir 找文件，而归档解析出来的 ApplicationInfo 里它是空的
        info.applicationInfo?.apply {
            sourceDir = apk.absolutePath
            publicSourceDir = apk.absolutePath
        }
        return info
    }

    private fun signatureFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

    /**
     * 证书指纹集合。两侧用同一段代码算，密钥轮换与多签名的取值口径才不会打架。
     */
    private fun signaturesOf(info: PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        return raw.orEmpty().filterNotNull().map { sha256(it.toByteArray()) }.toSet()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TIMEOUT_MS = 30_000
        const val BUFFER_SIZE = 32 * 1024

        /**
         * APK 写入上限。本体才 4MB 上下，64MB 留够了几十倍的增长余量，
         * 同时把「服务端返回一条无穷流」挡在磁盘写满之前。
         */
        const val MAX_APK_BYTES = 64L * 1024 * 1024

        /** 会话里的条目名，只在会话内部有意义，随便取一个稳定的即可。 */
        const val SESSION_ENTRY = "lightmux.apk"
    }
}
