package dev.piko.server.download

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 跨重启的限流惩罚记忆，落在数据目录的 `download-throttle.json`。
 *
 * 为什么需要它：真机日志里进程一重启，自适应并发就从 16 条重新开始，立刻又撞上 CDN 的惩罚窗口，
 * 再把冷却「续期」一遍——用户每个小时重启一次就永远等不到窗口结束。把上次限流时间与惩罚到期时间
 * 落盘后，重启会从 2 条起步并继续静默，直到窗口真的过期。
 */
@Serializable
data class ThrottleState(
    /** 最近一次真实限流的时间（epoch 毫秒）。0 表示从未限流。 */
    val lastThrottleAtMs: Long = 0L,
    /** 惩罚窗口到期时间（epoch 毫秒）；启动时大于当前时间就从最低并发静默。 */
    val penaltyUntilMs: Long = 0L,
    /** 限流当时生效的并发（供日志与排查）。 */
    val limitAtThrottle: Int = 0,
    /** 触发限流的原因（零字节响应 / HTTP 503 / Retry-After 等）。 */
    val reason: String = "",
)

class ThrottleMemory(private val file: Path) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    fun load(): ThrottleState? {
        if (!Files.exists(file)) return null
        return runCatching {
            json.decodeFromString<ThrottleState>(Files.readString(file))
        }.getOrNull()
    }

    /** 原子写：先写临时文件再改名，进程在写入途中被杀也不会留下半个 JSON。 */
    @Synchronized
    fun save(state: ThrottleState) {
        runCatching {
            Files.createDirectories(file.parent)
            val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(
                temp,
                json.encodeToString(ThrottleState.serializer(), state) + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            runCatching {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.onFailure {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    /** 惩罚窗口已经过去时清掉，避免文件里留着旧原因干扰下一轮排查。 */
    fun clear() {
        runCatching { Files.deleteIfExists(file) }
    }
}
