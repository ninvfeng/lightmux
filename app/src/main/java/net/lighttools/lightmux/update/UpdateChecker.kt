package net.lighttools.lightmux.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 仓库归属只写在这一处。将来仓库改名或换 owner 只动 [SLUG]，
 * 项目主页、Releases 页面、更新检查的接口地址都跟着走。
 *
 * 认 cnb.cool 而不是 GitHub：代码两边都推，但发版流水线只在 `.cnb.yml`，签名 APK 也是挂在
 * CNB 的 Release 附件上。更新源指向 GitHub 等于要求「代码在哪、包在哪」有两个答案，
 * 而其中一个迟早会忘记同步——这里认包所在的那一边。
 */
object Repo {

    /** CNB 的路径是三段：用户 / 仓库组 / 仓库。 */
    const val SLUG = "ninvfeng/lighttools/lightmux"

    const val HOME_URL = "https://cnb.cool/$SLUG"

    const val RELEASES_URL = "$HOME_URL/-/releases"

    /**
     * 查发布列表的接口，**和 [RELEASES_URL] 是同一个地址**——CNB 按 `Accept` 头决定回 HTML 还是 JSON，
     * 见 [UpdateChecker.httpGet]。
     *
     * 不走 `api.cnb.cool`：那套 OpenAPI 即使对公开仓库也要 Bearer Token，
     * 而任何打进 APK 的 token 都等于公开的 token。这个地址匿名可读。
     *
     * 也没有 GitHub 那样的 `/latest` 端点：CNB 只给整个列表，最新版本由
     * [UpdateChecker.evaluate] 按版本号自己挑。
     */
    const val RELEASES_API = RELEASES_URL
}

/**
 * 版本号比较。
 *
 * 必须按段比数字：字符串序下 `0.10.0 < 0.2.0`、`0.2.0 < 0.1.9`，
 * 用错的话「有新版」会在跨十位数时静默失效，而这是没人会去测的路径。
 */
object AppVersion {

    /**
     * 解析成数字段。不认识就返回 null——**认不出来一律当作「不是更新」**，
     * 宁可漏提示也不能让一个 tag 打错的发布把用户引去装一个更旧的包。
     */
    fun parse(raw: String?): List<Int>? {
        val trimmed = raw?.trim().orEmpty().removePrefix("v").removePrefix("V")
        // 预发布/构建元数据（`1.2.0-rc1`、`1.2.0+build3`）只比较前面的数字部分
        val core = trimmed.takeWhile { it != '-' && it != '+' }
        if (core.isEmpty()) return null
        return core.split('.').map { part -> part.toIntOrNull()?.takeIf { it >= 0 } ?: return null }
    }

    /** 位数不等按缺位补 0 比：`1.0` == `1.0.0`。解析不了的当作最小值，绝不抛异常。 */
    fun compare(a: String?, b: String?): Int {
        val left = parse(a) ?: emptyList()
        val right = parse(b) ?: emptyList()
        for (i in 0 until maxOf(left.size, right.size)) {
            val diff = (left.getOrElse(i) { 0 }).compareTo(right.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    /** 远端版本是否严格新于当前版本。任一侧解析失败都返回 false。 */
    fun isNewer(remote: String?, current: String?): Boolean {
        if (parse(remote) == null || parse(current) == null) return false
        return compare(remote, current) > 0
    }
}

/**
 * 一次发布的关键信息。
 *
 * @param version 从 tag 里剥掉 `v` 前缀的版本号
 * @param apkUrl  assets 里第一个 `.apk` 的直链
 */
data class ReleaseInfo(
    val tag: String,
    val version: String,
    val apkUrl: String,
    val notes: String,
    val prerelease: Boolean,
)

/**
 * 从响应里抠出来的原始字段，**不带 org.json**。
 *
 * 多这一层的理由很实在：`org.json` 在 JVM 单测里是个只会抛 `Stub!` 的桩，
 * 解析逻辑写在 `JSONObject` 上就一行都测不了。把「读字段」和「判断这个发布能不能用」
 * 切开之后，后者是纯 Kotlin，前者只剩十几行 `optString`。
 *
 * @param assets 资产的 (文件名, 直链) 对，顺序与响应一致
 */
data class RawRelease(
    val tag: String?,
    val prerelease: Boolean,
    val draft: Boolean,
    val notes: String?,
    val assets: List<Pair<String, String>>,
)

object ReleaseParser {

    /**
     * 原始字段 → 可用的发布。
     *
     * 返回 null 的几种情况都不是异常，是「这个发布用不了」：tag 缺失或不是版本号、
     * 还是草稿、assets 里没有 APK（只挂了源码包）、APK 的直链是空的。
     * **任何一种都不该弹更新提示**。
     */
    fun from(raw: RawRelease?): ReleaseInfo? {
        val tag = raw?.tag?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // 草稿只有仓库成员看得见，附件随时会被换掉，不能当成发布出去的版本
        if (raw.draft) return null
        val version = AppVersion.parse(tag)?.joinToString(".") ?: return null
        val apkUrl = raw.assets
            .firstOrNull { (name, url) -> name.endsWith(".apk", ignoreCase = true) && url.isNotBlank() }
            ?.second ?: return null
        return ReleaseInfo(
            tag = tag,
            version = version,
            apkUrl = apkUrl,
            notes = raw.notes?.trim().orEmpty(),
            prerelease = raw.prerelease,
        )
    }

    /** 解析发布列表的响应体。CNB 给的是数组（没有 `latest` 端点）。org.json 只出现在这一层。 */
    fun parse(body: String?): List<ReleaseInfo> = readRaw(body).mapNotNull(::from)

    private fun readRaw(body: String?): List<RawRelease> {
        if (body.isNullOrBlank()) return emptyList()
        val array = try {
            JSONArray(body)
        } catch (_: Exception) {
            return emptyList()
        }
        return (0 until array.length()).mapNotNull { i ->
            val release = array.optJSONObject(i) ?: return@mapNotNull null
            val assets = release.optJSONArray("assets")
            RawRelease(
                tag = release.optString("tag_name"),
                prerelease = release.optBoolean("prerelease", false),
                draft = release.optBoolean("draft", false),
                notes = release.optString("body"),
                assets = (0 until (assets?.length() ?: 0)).mapNotNull { j ->
                    val asset = assets?.optJSONObject(j) ?: return@mapNotNull null
                    // 取 browser_download_url：同一条资产里的 `url` 指向 api.cnb.cool，要鉴权才下得动
                    asset.optString("name") to asset.optString("browser_download_url")
                },
            )
        }
    }
}

sealed interface UpdateResult {

    /** 已是最新，或最新发布是预发布版。 */
    data object UpToDate : UpdateResult

    data class Available(val release: ReleaseInfo) : UpdateResult

    /** 网络不通、限流、响应看不懂——都归这里，UI 上是同一句「查不到」加重试。 */
    data class Failed(val reason: String) : UpdateResult
}

/**
 * 只在用户点「检查更新」时查一次，**没有后台轮询**：省电，也免得一个 SSH 客户端
 * 在用户不知情时定期往第三方服务器发请求。
 */
class UpdateChecker(private val fetch: (String) -> String = ::httpGet) {

    suspend fun check(currentVersion: String): UpdateResult = withContext(Dispatchers.IO) {
        val body = try {
            fetch(Repo.RELEASES_API)
        } catch (e: Exception) {
            return@withContext UpdateResult.Failed(
                e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            )
        }
        evaluate(ReleaseParser.parse(body), currentVersion)
    }

    companion object {

        /**
         * 纯决策，方便单测：解析出来的发布列表 + 当前版本 → 该给用户看什么。
         *
         * 「哪个最新」按版本号自己挑，既不信任列表顺序，也不看响应里的 `is_latest`——
         * 后者在 CNB 上只有一个发布时也是 `false`，跟着它走会永远提示不了更新。
         */
        fun evaluate(releases: List<ReleaseInfo>, currentVersion: String): UpdateResult {
            val newest = releases
                // 预发布版不推给普通用户：他们没订阅 beta，也没有回滚手段
                .filterNot { it.prerelease }
                .maxWithOrNull { a, b -> AppVersion.compare(a.version, b.version) }
                ?: return UpdateResult.UpToDate
            return if (AppVersion.isNewer(newest.version, currentVersion)) UpdateResult.Available(newest)
            else UpdateResult.UpToDate
        }

        private const val TIMEOUT_MS = 15_000

        /**
         * 发布列表的读取上限。几十个发布的 JSON 也就几十 KB，1MB 够宽松了；
         * 没有上限的话，一个畸形（或者被劫持的）响应能让 `readText` 一路吃到 OOM。
         */
        private const val MAX_BODY_CHARS = 1024 * 1024

        /** 用 JDK 自带的 HttpURLConnection，不为一次 GET 引 OkHttp。 */
        private fun httpGet(url: String): String {
            val connection = URL(url).openConnection() as HttpURLConnection
            return try {
                connection.requestMethod = "GET"
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                // 这个头就是 CNB 的开关：不带它，同一个地址返回的是给浏览器看的 HTML
                connection.setRequestProperty("Accept", "application/json")
                // 非 2xx 抛出去而不是返回 null：403 限流和「没有新版」在用户眼里必须是两回事
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                connection.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(MAX_BODY_CHARS)
                    var filled = 0
                    while (filled < buffer.size) {
                        val read = reader.read(buffer, filled, buffer.size - filled)
                        if (read < 0) break
                        filled += read
                    }
                    // 装满了还没读到流尾 = 这不是我们要的那份列表，当失败处理而不是拿半截去解析
                    if (filled == buffer.size && reader.read() >= 0) throw IOException("response too large")
                    String(buffer, 0, filled)
                }
            } finally {
                connection.disconnect()
            }
        }
    }
}
