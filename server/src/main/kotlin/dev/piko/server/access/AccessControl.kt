package dev.piko.server.access

import dev.piko.server.ServerConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * 最小够用的访问控制：一道口令 + 一枚短期会话 Cookie。
 *
 * 它只能挡住同网段随手打开页面的人。真正的对外暴露仍应交给 fnOS 反向代理 + 强口令
 * （反代上做 TLS、限流、来源限制），这里的口令与会话都不足以当成公网鉴权。
 *
 * 口令与客户端输入都先做 SHA-256 再比较，避免 [MessageDigest.isEqual] 暴露长度差异；
 * 这两步都不记录明文，日志里只出现「已配置 / 已生成」这一种事实。
 */
class AccessControl(private val passcode: String) {
    private val random = SecureRandom()
    private val sessions = ConcurrentHashMap<String, Long>()

    /** 常数时间比较；空输入直接判否，省掉一次哈希。 */
    fun verify(input: String): Boolean {
        if (input.isEmpty()) return false
        val expected = digest(passcode)
        val actual = digest(input)
        return MessageDigest.isEqual(expected, actual)
    }

    fun issueSession(): String {
        pruneExpired()
        val token = token()
        sessions[token] = System.currentTimeMillis() + SESSION_TTL_SECONDS * 1000
        return token
    }

    fun isValidSession(token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        val expiresAt = sessions[token] ?: return false
        if (expiresAt <= System.currentTimeMillis()) {
            sessions.remove(token)
            return false
        }
        return true
    }

    fun revoke(token: String?) {
        if (!token.isNullOrEmpty()) sessions.remove(token)
    }

    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        sessions.entries.removeAll { it.value <= now }
    }

    private fun token(): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun digest(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    companion object {
        const val COOKIE_NAME = "piko_session"
        const val SESSION_TTL_SECONDS = 8 * 60 * 60
        private const val TOKEN_BYTES = 32

        /** 口令文件放在数据目录，不进仓库；生成一次后重启复用，不会每次都变。 */
        private const val TOKEN_FILE = "access-token"

        /**
         * 解析出入口令：命令行/环境变量优先；没给时只允许回环监听，并生成（或复用）一份随机口令。
         * 非回环且没给口令的情况在 [ServerConfig.from] 就被拒绝了，这里不会再走到。
         */
        fun resolve(config: ServerConfig): AccessSetup {
            config.accessToken?.let { return AccessSetup(AccessControl(it), generatedPasscode = null) }
            val file = config.home.resolve(TOKEN_FILE)
            val existing = runCatching {
                if (Files.exists(file)) Files.readString(file).trim().takeIf { it.isNotEmpty() } else null
            }.getOrNull()
            if (existing != null) return AccessSetup(AccessControl(existing), generatedPasscode = null)
            val generated = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes)
                .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
            // 写不进去也不影响本次启动，只是下次会换一份新的
            runCatching { writeTokenFile(file, generated) }
            return AccessSetup(AccessControl(generated), generatedPasscode = generated)
        }

        private fun writeTokenFile(file: Path, value: String) {
            Files.createDirectories(file.parent)
            Files.writeString(file, value)
            runCatching {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
            }
        }
    }
}

/**
 * 启动期口令装配结果。[generatedPasscode] 非空时是本次刚生成的值，需要打印一次让用户知道；
 * 其余情况下不保留明文（只留 [AccessControl] 里的散列比较对象）。
 */
class AccessSetup(val control: AccessControl, val generatedPasscode: String?)
