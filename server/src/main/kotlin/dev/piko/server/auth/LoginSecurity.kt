package dev.piko.server.auth

import dev.piko.shared.log.PikoLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private const val TAG = "AuthLogin"

/** 账号只用来打限流键与日志，日志里要打码，避免把邮箱明文写进盘。 */
internal fun maskAccount(account: String): String {
    val at = account.indexOf('@')
    return when {
        at > 0 -> account.take(1) + "***" + account.substring(at)
        account.length <= 2 -> "***"
        else -> "***" + account.takeLast(2)
    }
}

/** PikPak 接受邮箱或手机号；这里只做「明显不是账号」的拦截，真正的判定交给服务端。 */
internal fun isPlausibleAccount(account: String): Boolean {
    if (account.length !in 3..254) return false
    val email = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    val phone = Regex("^[0-9+\\-() ]{6,20}$")
    return email.matches(account) || phone.matches(account)
}

/**
 * 固定窗口限流：同一 IP 与同一账号各自最多 [maxAttempts] 次/分钟。
 *
 * 只在本进程内计数，重启清零；够挡住脚本口令爆破，不引入额外依赖。
 */
class LoginAttemptLimiter(
    private val maxAttempts: Int = 5,
    private val windowMillis: Long = 60_000,
) {
    private val windows = HashMap<String, ArrayDeque<Long>>()

    /** 允许则记一次并返回 null；被挡住则返回建议的 Retry-After 秒数。 */
    @Synchronized
    fun acquire(key: String): Long? {
        val now = System.currentTimeMillis()
        val queue = windows.getOrPut(key) { ArrayDeque() }
        while (queue.isNotEmpty() && now - queue.first() >= windowMillis) queue.removeFirst()
        if (queue.size >= maxAttempts) {
            val waitMillis = windowMillis - (now - queue.first())
            return (waitMillis / 1000 + 1).coerceAtLeast(1)
        }
        queue.addLast(now)
        if (windows.size > 2048) {
            // 防内存被大量随机 IP 撑大：只清掉窗口已空的键
            windows.entries.removeIf { (_, q) -> q.isEmpty() || now - q.last() >= windowMillis }
        }
        return null
    }
}

/**
 * 验证码挑战表：一次消费、带 TTL。
 *
 * PikPak 的风控令牌本身有效期 300 秒，这里按上游给的 `expires_in` 截断，过期后消费方拿到明确的
 * [ConsumeResult.Expired]，而不是一直挂住等登录失败。
 */
class CaptchaChallengeStore(private val maxEntries: Int = 200) {
    sealed interface ConsumeResult {
        data class Accepted(val captchaToken: String) : ConsumeResult
        data object NotFound : ConsumeResult
        data object Expired : ConsumeResult
        data object AccountMismatch : ConsumeResult
    }

    private data class Challenge(val account: String, val captchaToken: String, val expiresAtMillis: Long)

    private val entries = LinkedHashMap<String, Challenge>()

    @Synchronized
    fun issue(account: String, captchaToken: String, expiresInSec: Long): String {
        val now = System.currentTimeMillis()
        entries.entries.removeIf { (_, value) -> value.expiresAtMillis <= now }
        while (entries.size >= maxEntries) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
        val id = buildString {
            repeat(16) { append(HEX[Random.nextInt(HEX.length)]) }
        }
        entries[id] = Challenge(account, captchaToken, now + expiresInSec.coerceIn(30, 3600) * 1000)
        return id
    }

    @Synchronized
    fun consume(challengeId: String, account: String): ConsumeResult {
        if (challengeId.isBlank()) return ConsumeResult.NotFound
        val now = System.currentTimeMillis()
        entries.entries.removeIf { (_, value) -> value.expiresAtMillis <= now }
        val challenge = entries[challengeId] ?: return ConsumeResult.NotFound
        // 无论结果如何都算一次消费，避免同一个挑战被反复试
        entries.remove(challengeId)
        return when {
            challenge.account != account -> ConsumeResult.AccountMismatch
            challenge.expiresAtMillis <= now -> ConsumeResult.Expired
            else -> ConsumeResult.Accepted(challenge.captchaToken)
        }
    }

    private companion object {
        const val HEX = "0123456789abcdef"
    }
}

data class CaptchaToken(val value: String, val expiresInSec: Long)

/**
 * 直接调用 PikPak 的风控验证码初始化接口，请求形状与 SDK 2.5.0 的 `AuthApi.initCaptcha` 一致：
 * `POST /v1/shield/captcha/init?client_id=…`，body 是 `{client_id, device_id, action, meta:{username}}`，
 * 响应只有 `captcha_token` 与 `expires_in`，**没有图片**。
 *
 * 令牌只在服务端保存，绝不回给浏览器，也不进日志。
 */
class PikPakCaptchaClient(private val home: Path) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
        }
        expectSuccess = false
    }

    private val deviceId: String by lazy { loadOrCreateDeviceId() }

    suspend fun init(account: String): Result<CaptchaToken> = runCatching {
        val response = client.post("$ENDPOINT?client_id=$CLIENT_ID") {
            header(HttpHeaders.UserAgent, USER_AGENT)
            header("X-Device-Id", deviceId)
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("client_id", CLIENT_ID)
                    put("device_id", deviceId)
                    put("action", ACTION_SIGNIN)
                    putJsonObject("meta") { put("username", account) }
                }.toString(),
            )
        }
        val status = response.status.value
        val body = response.bodyAsText()
        if (status != 200) throw IllegalStateException("captcha init HTTP $status")
        val root = json.parseToJsonElement(body).jsonObject
        val token = root["captcha_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (token.isEmpty()) throw IllegalStateException("captcha init 响应缺少 captcha_token")
        val expires = root["expires_in"]?.jsonPrimitive?.intOrNull?.toLong() ?: 300L
        CaptchaToken(token, expires)
    }.onFailure { PikoLog.w(TAG, "验证码初始化失败，账号=${maskAccount(account)}，${it::class.simpleName}") }

    override fun close() {
        client.close()
    }

    private fun loadOrCreateDeviceId(): String = runCatching {
        val file = home.resolve("device-id")
        if (Files.isRegularFile(file)) {
            Files.readString(file).trim().takeIf { it.length >= 8 }?.let { return@runCatching it }
        }
        val generated = buildString {
            repeat(32) { append(HEX[SecureRandom().nextInt(HEX.length)]) }
        }
        runCatching {
            Files.createDirectories(home)
            Files.writeString(file, generated)
        }
        generated
    }.getOrElse {
        // 落盘失败也不阻塞登录：这一次用临时设备号即可，下次启动会再生成
        buildString { repeat(32) { append(HEX[SecureRandom().nextInt(HEX.length)]) } }
    }

    private companion object {
        const val ENDPOINT = "https://user.mypikpak.com/v1/shield/captcha/init"
        const val CLIENT_ID = "YNxT9w7GMdWvEOKa"
        const val USER_AGENT = "ANDROID-com.pikcloud.pikpak/1.21.0"
        const val ACTION_SIGNIN = "POST:https://user.mypikpak.com/v1/auth/signin"
        const val HEX = "0123456789abcdef"
    }
}
