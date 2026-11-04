package dev.piko.server.access

import dev.piko.server.ServerConfig
import dev.piko.shared.log.PikoLog
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "Access"

/**
 * 最小够用的访问控制：一道口令 + 一枚短期会话 Cookie。
 *
 * 单一可信来源：口令只存在两个地方——数据目录里的 `access-token` 文件（0600）与本实例的内存副本。
 * 启动时优先级是 `--access-token` / `PIKO_SERVER_TOKEN`（外部托管，只读不写）> 文件 > 首次随机生成并落盘。
 * 页面改口令时原子写回同一个文件并同步内存，重启后依然是新值。
 *
 * 口令与客户端输入都先 SHA-256 再比较（常数时间），全程不记录明文：诊断只打两侧指纹的前 8 位。
 */
class AccessControl(
    initialPasscode: String,
    /** 口令来自启动参数/环境变量时，页面不允许改（写文件也改不动下一次启动实际用的值）。 */
    val managedExternally: Boolean,
    private val tokenFile: Path,
) {
    private val random = SecureRandom()
    private val sessions = ConcurrentHashMap<String, Long>()

    @Volatile
    private var passcode: String = initialPasscode

    /** 当前口令的 sha256 前 8 位，供日志与设置页核对（不可逆）。 */
    @Volatile
    var fingerprint: String = fingerprintOf(initialPasscode)
        private set

    val tokenFilePath: String get() = tokenFile.toString()

    /** 常数时间比较。输入先 trim（终端复制常带尾随空白，文件里的 CRLF 也在读取时被清掉）。 */
    fun verify(input: String): Boolean {
        val candidate = input.trim()
        if (candidate.isEmpty()) {
            PikoLog.w(TAG, "口令校验失败：输入为空；期望=${fingerprint}")
            return false
        }
        val ok = MessageDigest.isEqual(digest(passcode), digest(candidate))
        if (!ok) {
            // 只打两侧指纹，绝不打明文；用户可用 sha256sum 对照文件验证
            PikoLog.w(
                TAG,
                "口令校验失败：期望=${fingerprint} 实际=${fingerprintOf(candidate)} 输入长度=${candidate.length}",
            )
        }
        return ok
    }

    /** 改口令：原子写回文件 → 更新内存 → 其它会话全部失效（[keepSession] 是发起这次修改的那个会话）。 */
    fun changePasscode(raw: String, keepSession: String?): String {
        val value = raw.trim()
        val problem = validatePasscode(value)
        require(problem == null) { problem ?: "" }
        writeTokenFile(tokenFile, value)
        passcode = value
        fingerprint = fingerprintOf(value)
        sessions.keys.toList().filter { it != keepSession }.forEach { sessions.remove(it) }
        return fingerprint
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

    fun activeSessionCount(): Int = sessions.size

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
        const val MIN_PASSCODE_LENGTH = 12
        private const val TOKEN_BYTES = 32
        private const val TOKEN_FILE = "access-token"

        private val PASSCODE_PATTERN = Regex("[A-Za-z0-9._-]{$MIN_PASSCODE_LENGTH,}")

        /** 口令强度校验；通过返回 null，否则返回可展示的中文原因。 */
        fun validatePasscode(value: String): String? {
            val trimmed = value.trim()
            if (trimmed.length < MIN_PASSCODE_LENGTH) return "口令至少 $MIN_PASSCODE_LENGTH 个字符（当前 ${trimmed.length}）"
            if (!PASSCODE_PATTERN.matches(trimmed)) return "口令只能用字母、数字和 - _ ."
            return null
        }

        fun fingerprintOf(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
                .take(4).joinToString("") { "%02x".format(it) }

        /**
         * 解析出入口令：命令行/环境变量优先（外部托管，不读不写文件）；否则读文件（trim 后非空即用，
         * 绝不重新生成）；文件不存在或为空才生成一份并原子落盘。
         */
        fun resolve(config: ServerConfig): AccessSetup {
            val file = config.home.resolve(TOKEN_FILE)
            config.accessToken?.let {
                return AccessSetup(AccessControl(it, managedExternally = true, tokenFile = file), generatedPasscode = null)
            }
            val existing = runCatching {
                if (Files.exists(file)) Files.readString(file).trim().takeIf { it.isNotEmpty() } else null
            }.getOrNull()
            if (existing != null) {
                return AccessSetup(AccessControl(existing, managedExternally = false, tokenFile = file), generatedPasscode = null)
            }
            val generated = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes)
                .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
            writeTokenFile(file, generated)
            return AccessSetup(
                AccessControl(generated, managedExternally = false, tokenFile = file),
                generatedPasscode = generated,
            )
        }

        /** 先写 `.tmp` 再原子改名，权限 0600；失败抛异常（调用方决定是否降级）。 */
        fun writeTokenFile(file: Path, value: String) {
            Files.createDirectories(file.parent)
            val temp = file.resolveSibling("${file.fileName}.tmp")
            Files.writeString(
                temp,
                value,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            runCatching { Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------")) }
            runCatching { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                .onFailure { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING) }
        }
    }
}

/**
 * 启动期口令装配结果。[generatedPasscode] 非空表示本次刚生成，调用方只打印它的指纹与文件路径，
 * 不把明文写进日志。
 */
class AccessSetup(val control: AccessControl, val generatedPasscode: String?)
