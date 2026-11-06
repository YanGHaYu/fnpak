package dev.piko.server.links

import dev.piko.server.ServiceGraph
import dev.piko.server.drive.requireLogin
import dev.piko.server.respondError
import dev.piko.server.torrent.TorrentParseException
import dev.piko.server.torrent.TorrentParser
import dev.piko.shared.log.PikoLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import kotlinx.serialization.Serializable

@Serializable
data class TorrentOpenRequest(val fileId: String = "")

@Serializable
data class TorrentFileDto(val path: String, val sizeBytes: Long)

@Serializable
data class TorrentOpenResponse(
    val name: String,
    val totalSizeBytes: Long,
    val fileCount: Int,
    val folderCount: Int,
    val files: List<TorrentFileDto>,
    val trackers: List<String>,
    val infoHash: String,
    val magnet: String,
    val truncated: Boolean,
)

private const val TAG = "Torrent"
private const val MAX_RESPONSE_FILES = 1000
private const val BROWSER_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"

/**
 * 种子「打开」：只解析，不落盘、不入库。
 * 字节来自 `MediaService.meta(fileId).directUrl`，读取上限 10MB。
 * 「加入离线」仍走既有的 `POST /api/links/save`（磁力链路），这里只负责产出 magnet。
 */
fun Route.torrentRoutes(graph: ServiceGraph) {
    route("/api/links") {
        post("/torrent") {
            if (graph.requireLogin(call)) return@post
            val body = runCatching { call.receive<TorrentOpenRequest>() }.getOrNull()
                ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_body", "请求体必须是 JSON")
            val fileId = body.fileId.trim()
            if (fileId.isEmpty()) {
                return@post call.respondError(HttpStatusCode.BadRequest, "missing_file_id", "缺少文件 ID")
            }
            val meta = graph.mediaService.meta(fileId).getOrElse { error ->
                PikoLog.w(TAG, "取种子直链失败，文件=$fileId：${error.message}")
                return@post call.respondError(HttpStatusCode.BadGateway, "torrent_fetch_failed", "无法获取种子文件，请稍后重试")
            }
            if (!meta.extension.equals("torrent", ignoreCase = true)) {
                return@post call.respondError(HttpStatusCode.BadRequest, "not_torrent", "这个文件不是 .torrent 种子")
            }
            if (meta.sizeBytes > TorrentParser.MAX_BYTES) {
                return@post call.respondError(HttpStatusCode.PayloadTooLarge, "torrent_too_large", "种子文件超过 10MB，无法解析")
            }
            val bytes = try {
                fetchTorrentBytes(meta.directUrl)
            } catch (error: TorrentFetchLocalException) {
                PikoLog.w(TAG, "下载种子字节失败，文件=$fileId：${error.message}")
                return@post call.respondError(
                    if (error.tooLarge) HttpStatusCode.PayloadTooLarge else HttpStatusCode.BadGateway,
                    if (error.tooLarge) "torrent_too_large" else "torrent_fetch_failed",
                    error.message ?: "下载种子失败",
                )
            }
            val listing = try {
                TorrentParser.parse(bytes)
            } catch (error: TorrentParseException) {
                PikoLog.w(TAG, "种子解析失败，文件=$fileId，code=${error.code}：${error.message}")
                val status = when (error.code) {
                    "torrent_too_large", "torrent_too_many_files" -> HttpStatusCode.PayloadTooLarge
                    else -> HttpStatusCode.BadRequest
                }
                return@post call.respondError(status, error.code, error.message ?: "种子解析失败")
            }
            PikoLog.i(TAG, "种子解析成功：${listing.files.size} 个文件，infohash=${listing.infoHash}")
            val capped = listing.files.take(MAX_RESPONSE_FILES)
            call.respond(
                TorrentOpenResponse(
                    name = listing.name,
                    totalSizeBytes = listing.totalSizeBytes,
                    fileCount = listing.files.size,
                    folderCount = listing.folderCount,
                    files = capped.map { TorrentFileDto(it.path, it.sizeBytes) },
                    trackers = listing.trackers,
                    infoHash = listing.infoHash,
                    magnet = listing.magnet,
                    truncated = listing.files.size > MAX_RESPONSE_FILES,
                ),
            )
        }
    }
}

private class TorrentFetchLocalException(message: String, val tooLarge: Boolean = false) : Exception(message)

private val torrentHttp = HttpClient(OkHttp) {
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 30_000
        socketTimeoutMillis = 30_000
    }
}

private suspend fun fetchTorrentBytes(url: String): ByteArray = torrentHttp
    .prepareGet(url) {
        header(HttpHeaders.UserAgent, BROWSER_UA)
    }
    .execute { response ->
        if (response.status.value !in 200..299) {
            throw TorrentFetchLocalException("种子下载返回 HTTP ${response.status.value}")
        }
        val declared = response.contentLength() ?: -1L
        if (declared > TorrentParser.MAX_BYTES) {
            throw TorrentFetchLocalException("种子文件超过 10MB", tooLarge = true)
        }
        val channel = response.bodyAsChannel()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = channel.readAvailable(buffer)
            if (read <= 0) break
            total += read
            if (total > TorrentParser.MAX_BYTES) throw TorrentFetchLocalException("种子文件超过 10MB", tooLarge = true)
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    }
