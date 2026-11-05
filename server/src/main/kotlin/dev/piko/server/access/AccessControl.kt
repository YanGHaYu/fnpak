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
 * 启动时优先级是 `--access-token` / `FNPAK_SERVER_TOKEN`（兼容旧名 `PIKO_SERVER_TOKEN`；外部托管，只读不写）> 文件 > 无口令状态。
 * **首次运行不再自动生成口令**：没有文件时进入「设置向导」，由用户在页面里设置后原子写盘。
 * 页面改口令时原子写回同一个文件并同步内存，重启后依然是新值。
 *
 * 口令与客户端输入都先 SHA-256 再比较（常数时间），全程不记录明文：诊断只打两侧指纹的前 8 位。
 */
class AccessControl(
    initialPasscode: String,
    /** 口令来自启动参数/环境变量时，页面不允许改（写文件也改不动下一次启动实际用的值）。 */
    val managedExternally: Boolean,
    private val tokenFile: Path,
    /** 首次设置口令的窗口（分钟）。0 表示不限；默认 15。 */
    val setupWindowMinutes: Int = 15,
    private val startedAtMs: Long = System.currentTimeMillis(),
) {
    /** 无口令状态（首次运行）：除设置接口外全部 API 返回 setup_required。 */
    @Volatile
    var needsSetup: Boolean = initialPasscode.isBlank()
        private set

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

    /** 首次设置窗口是否仍开放。0 分钟表示不限（仍只允许在 needsSetup 状态下调用）。 */
    fun setupWindowOpen(now: Long = System.currentTimeMillis()): Boolean {
        if (setupWindowMinutes <= 0) return true
        return now <= startedAtMs + setupWindowMinutes * 60_000L
    }

    /**
     * 首次设置口令：校验强度 → 0600 原子写盘 → 更新内存 → 退出 needsSetup。
     * 日志只记来源 IP 与指纹前 8 位，不打印口令明文。
     */
    fun completeSetup(raw: String, sourceIp: String, userAgent: String?): String {
        check(needsSetup) { "口令已设置，不能再次进入首次设置流程" }
        val value = raw.trim()
        require(validatePasscode(value) == null) { validatePasscode(value) ?: "" }
        writeTokenFile(tokenFile, value)
        passcode = value
        fingerprint = fingerprintOf(value)
        needsSetup = false
        PikoLog.w(
            TAG,
            "首次设置访问口令成功：来源IP=${sourceIp.ifBlank { "unknown" }}，指纹 sha256[:8]=$fingerprint，" +
                "User-Agent=${(userAgent ?: "unknown").take(80)}",
        )
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
        const val COOKIE_NAME = "fnpak_session"
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
         * 绝不重新生成）；文件不存在或为空则进入**无口令状态**，不再静默生成文件，由首次向导设置。
         */
        fun resolve(config: ServerConfig): AccessSetup {
            val file = config.home.resolve(TOKEN_FILE)
            config.accessToken?.let {
                return AccessSetup(
                    control = AccessControl(
                        initialPasscode = it,
                        managedExternally = true,
                        tokenFile = file,
                        setupWindowMinutes = config.setupWindowMinutes,
                    ),
                    needsSetup = false,
                    reason = SETUP_REASON_EXTERNAL,
                )
            }
            val existing = runCatching {
                if (Files.exists(file)) Files.readString(file).trim().takeIf { it.isNotEmpty() } else null
            }.getOrNull()
            if (existing != null) {
                return AccessSetup(
                    control = AccessControl(
                        initialPasscode = existing,
                        managedExternally = false,
                        tokenFile = file,
                        setupWindowMinutes = config.setupWindowMinutes,
                    ),
                    needsSetup = false,
                    reason = SETUP_REASON_TOKEN_FILE,
                )
            }
            return AccessSetup(
                control = AccessControl(
                    initialPasscode = "",
                    managedExternally = false,
                    tokenFile = file,
                    setupWindowMinutes = config.setupWindowMinutes,
                ),
                needsSetup = true,
                reason = SETUP_REASON_NO_TOKEN,
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
 * 启动期口令装配结果。三种状态：
 * - `needsSetup=true`：首次运行、没有 token 文件，页面显示设置向导；
 * - `needsSetup=false, reason=token_file`：读到了已有 token 文件（老用户，不受影响）；
 * - `needsSetup=false, reason=managed_externally`：口令由启动参数/环境变量注入。
 */
class AccessSetup(val control: AccessControl, val needsSetup: Boolean, val reason: String)

/** `GET /api/setup/state` 的 reason 取值。 */
const val SETUP_REASON_NO_TOKEN = "no_token_file"
const val SETUP_REASON_TOKEN_FILE = "token_file"
const val SETUP_REASON_EXTERNAL = "managed_externally"
