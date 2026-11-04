package dev.piko.server.share

import dev.piko.server.ServerConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** 一条可供选择下载位置的根目录。source：`declared`（config/resource 声明）或 `authorized`（管理员在应用设置里授权）。 */
data class ShareEntry(val path: Path, val source: String)

/**
 * 授权目录快照。
 *
 * - [entries]：内置共享根 + 已授权目录（启动时的环境变量 + 实时查询结果）
 * - [liveQuery]：本次是否真的通过 fnOS 授权接口实时查到了结果
 * - [note]：给页面看的中文说明（为什么看不到新加的目录 / 已实时刷新）
 */
data class ShareSnapshot(
    val entries: List<ShareEntry>,
    val liveQuery: Boolean,
    val note: String,
)

/**
 * fnOS「应用设置 → 访问权限」里的授权目录有两个来源：
 *
 * 1. 启动时注入的环境变量 `TRIM_DATA_ACCESSIBLE_PATHS`（冒号分隔），进程启动后不再变化；
 * 2. 后端 API `trim.file.getSharedAccessibleFolders`（Unix Socket + `TRIM_API_TOKEN`），可实时查。
 *
 * 所以这里每次调用都尽量实时查一次；查不到就退回环境变量里的集合，并在 [ShareSnapshot.note] 里说清
 * 「新增授权要重启应用」。容器/桌面环境没有 socket 时会自然走退回分支，不影响功能。
 */
object ShareDiscovery {
    const val APP_NAME = "piko"
    private const val SOCKET_PATH = "/var/run/trim_open_gateway_apiscope.socket"
    private const val TIMEOUT_MS = 1500L

    fun snapshot(config: ServerConfig): ShareSnapshot {
        val merged = LinkedHashMap<String, ShareEntry>()
        config.declaredShares.forEach { merged.putIfAbsent(it.toString(), ShareEntry(it, "declared")) }
        config.authorizedShares.forEach { merged.putIfAbsent(it.toString(), ShareEntry(it, "authorized")) }

        val token = System.getenv("TRIM_API_TOKEN")?.trim()
        val socket = Path.of(SOCKET_PATH)
        if (token.isNullOrEmpty()) {
            return ShareSnapshot(
                merged.values.toList(),
                liveQuery = false,
                note = "未检测到 TRIM_API_TOKEN（非 fpk 运行环境），只能看到启动时的目录",
            )
        }
        if (!Files.exists(socket)) {
            return ShareSnapshot(
                merged.values.toList(),
                liveQuery = false,
                note = "未找到 fnOS 授权查询 socket，只能看到启动时的目录；在应用设置里新加的目录需重启应用后可见",
            )
        }
        return runCatching { query(token) }.fold(
            onSuccess = { paths ->
                paths.forEach { merged.putIfAbsent(it.toString(), ShareEntry(it, "authorized")) }
                ShareSnapshot(merged.values.toList(), liveQuery = true, note = "已实时查询 fnOS 授权目录")
            },
            onFailure = { error ->
                ShareSnapshot(
                    merged.values.toList(),
                    liveQuery = false,
                    note = "实时查询授权目录失败（${error.message}），已退回启动时的目录；新加的目录需重启应用后可见",
                )
            },
        )
    }

    /**
     * 直接走 Unix Socket 发一个 HTTP/1.1 POST。用 `SocketChannel` 是因为 `HttpClient` 不支持 unix domain，
     * 而 `java.base` 自带 `UnixDomainSocketAddress`，不需要额外依赖。
     */
    private fun query(token: String): List<Path> {
        val body = """
            {"reqId":"${System.nanoTime()}","req":"trim.file.getSharedAccessibleFolders","appName":"$APP_NAME","data":{}}
        """.trimIndent()
        val payload = body.toByteArray(StandardCharsets.UTF_8)
        val channel = SocketChannel.open(UnixDomainSocketAddress.of(SOCKET_PATH))
        val finished = AtomicBoolean(false)
        // 看门狗：socket 那边不响应时主动关通道，避免设置页被拖死（1.5s 足够本机 IPC）
        thread(isDaemon = true, name = "piko-share-watchdog") {
            runCatching {
                Thread.sleep(TIMEOUT_MS)
                if (finished.compareAndSet(false, true)) channel.close()
            }
        }
        return channel.use {
            val head = "POST /api/v1/trimapp HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: application/json\r\n" +
                "Authorization: Bearer $token\r\n" +
                "Content-Length: ${payload.size}\r\n" +
                "Connection: close\r\n\r\n"
            it.write(ByteBuffer.wrap(head.toByteArray(StandardCharsets.UTF_8)))
            it.write(ByteBuffer.wrap(payload))

            val out = ByteArrayOutputStream()
            val buffer = ByteBuffer.allocate(8192)
            while (it.read(buffer) >= 0) {
                buffer.flip()
                out.write(buffer.array(), 0, buffer.remaining())
                buffer.clear()
            }
            finished.set(true)
            parsePaths(out.toString(StandardCharsets.UTF_8))
        }
    }

    private fun parsePaths(response: String): List<Path> {
        val bodyStart = response.indexOf("\r\n\r\n")
        if (bodyStart < 0) throw IllegalMessage("响应不是合法 HTTP：${response.take(80)}")
        val status = response.lineSequence().firstOrNull().orEmpty()
        val json = response.substring(bodyStart + 4)
        val root = Json.parseToJsonElement(json).jsonObject
        val code = root["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
        if (code != 0) {
            throw IllegalMessage("授权接口返回 code=$code msg=${root["msg"]?.jsonPrimitive?.content.orEmpty()}（$status）")
        }
        assertFpEnvironment(status)
        return root["data"]?.jsonObject?.get("paths")?.jsonArray
            ?.mapNotNull { runCatching { Path.of(it.jsonPrimitive.content).toAbsolutePath().normalize() }.getOrNull() }
            ?.filter { Files.isDirectory(it) }
            .orEmpty()
    }

    /** 记录状态行，便于在日志里区分「socket 连上但网关报错」与「根本没连上」。 */
    private fun assertFpEnvironment(status: String) {
        if (!status.contains(" 200")) {
            System.err.println("[W] ShareDiscovery: 授权接口状态行=$status")
        }
    }

    private class IllegalMessage(message: String) : IllegalStateException(message)
}
